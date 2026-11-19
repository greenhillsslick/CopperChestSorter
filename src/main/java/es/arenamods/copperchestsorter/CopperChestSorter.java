package es.arenamods.copperchestsorter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import es.arenamods.copperchestsorter.network.OpenItemRequestPayload;
import es.arenamods.copperchestsorter.network.SubmitItemRequestPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CopperChestSorter implements ModInitializer {
    public static final String MOD_ID = "copperchestsorter";
    private static final String GOLEM_NAME = "Copper Chest Sorter";
    private static final int SEARCH_XZ = 12;
    private static final int SEARCH_Y = 5;
    private static final int WORK_INTERVAL = 20;
    private static final double WALK_SPEED = 1.3;
    private static final long ALARM_INTERVAL = 600L;
    private static final List<PendingBuild> PENDING_BUILDS = new ArrayList<>();
    private static final Set<CopperGolem> LOADED_GOLEMS = new HashSet<>();
    private static final Map<UUID, Long> LAST_ALARM = new HashMap<>();
    private static final Map<UUID, SortRoute> ROUTES = new HashMap<>();
    private static final Map<UUID, BlockPos> SEARCH_CENTERS = new HashMap<>();
    private static final Map<UUID, Map<BlockPos, Long>> UNREACHABLE_UNTIL = new HashMap<>();
    private static final Map<UUID, ScheduledChestClose> OPEN_CHESTS = new HashMap<>();
    private static final Map<UUID, ItemRequest> ITEM_REQUESTS = new HashMap<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(OpenItemRequestPayload.TYPE, OpenItemRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SubmitItemRequestPayload.TYPE, SubmitItemRequestPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SubmitItemRequestPayload.TYPE, (payload, context) ->
                context.server().execute(() -> handleItemRequest(context.player(), payload)));
        UseBlockCallback.EVENT.register(CopperChestSorter::onUseBlock);
        UseEntityCallback.EVENT.register(CopperChestSorter::onUseEntity);
        ServerTickEvents.END_SERVER_TICK.register(CopperChestSorter::tickServer);
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof CopperGolem golem) {
                LOADED_GOLEMS.add(golem);
                if (isSorter(golem)) {
                    golem.setCustomNameVisible(false);
                    disableVanillaSorterBrain(world, golem);
                    SEARCH_CENTERS.putIfAbsent(golem.getUUID(), golem.blockPosition().immutable());
                }
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof CopperGolem golem) {
                LOADED_GOLEMS.remove(golem);
                LAST_ALARM.remove(golem.getUUID());
                ROUTES.remove(golem.getUUID());
                OPEN_CHESTS.remove(golem.getUUID());
            }
        });
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (level instanceof ServerLevel serverLevel) {
            BlockPos placedPos = hit.getBlockPos().relative(hit.getDirection()).immutable();
            PENDING_BUILDS.add(new PendingBuild(serverLevel, placedPos));
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand,
                                                  net.minecraft.world.entity.Entity entity,
                                                  net.minecraft.world.phys.EntityHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)
                || !(entity instanceof CopperGolem golem) || !isSorter(golem) || !player.getItemInHand(hand).isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!ServerPlayNetworking.canSend(serverPlayer, OpenItemRequestPayload.TYPE)) {
            player.sendOverlayMessage(Component.literal("El menú requiere el cliente del mod Copper Chest Sorter."));
            return InteractionResult.SUCCESS;
        }
        ServerPlayNetworking.send(serverPlayer, new OpenItemRequestPayload(golem.getId()));
        return InteractionResult.SUCCESS;
    }

    private static void handleItemRequest(ServerPlayer player, SubmitItemRequestPayload payload) {
        if (!(player.level() instanceof ServerLevel level)) return;
        net.minecraft.world.entity.Entity entity = level.getEntity(payload.entityId());
        if (!(entity instanceof CopperGolem golem) || !isSorter(golem) || player.distanceToSqr(golem) > 64.0) {
            player.sendOverlayMessage(Component.literal("No encuentro ese Copper Chest Sorter cerca."));
            return;
        }
        if (payload.itemName().isBlank() && payload.count() == 0) {
            ItemRequest removed = ITEM_REQUESTS.remove(golem.getUUID());
            if (removed != null) returnRequestStack(level, golem, removed);
            player.sendOverlayMessage(Component.literal(removed == null ? "No había ninguna solicitud activa." : "Solicitud cancelada."));
            return;
        }
        String selector = payload.itemName().trim();
        if (selector.length() > 100 || payload.count() < 1 || payload.count() > 9999) {
            player.sendOverlayMessage(Component.literal("Nombre o cantidad no válidos."));
            return;
        }
        boolean registered = BuiltInRegistries.ITEM.stream().anyMatch(item -> matches(new ItemStack(item), selector));
        if (!registered) {
            player.sendOverlayMessage(Component.literal("No reconozco ese objeto. Prueba con su nombre o ID exacto, por ejemplo minecraft:stone."));
            return;
        }
        ItemRequest replaced = ITEM_REQUESTS.put(golem.getUUID(), new ItemRequest(player.getUUID(), selector, payload.count()));
        if (replaced != null) returnRequestStack(level, golem, replaced);
        ROUTES.remove(golem.getUUID());
        golem.getNavigation().stop();
        player.sendOverlayMessage(Component.literal("Solicitud enviada: " + payload.count() + " × " + selector + "."));
    }

    private static void tickServer(MinecraftServer server) {
        if (!PENDING_BUILDS.isEmpty()) {
            List<PendingBuild> builds = new ArrayList<>(PENDING_BUILDS);
            PENDING_BUILDS.clear();
            for (PendingBuild build : builds) {
                if (build.level().getServer() == server) tryCreateSorter(build.level(), build.pumpkinPos());
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            tickChestCloseEvents(level);
            tickSorters(level);
        }
    }

    private static void tryCreateSorter(ServerLevel level, BlockPos pumpkin) {
        if (!level.getBlockState(pumpkin).is(Blocks.CARVED_PUMPKIN)
                || !level.getBlockState(pumpkin.below()).is(Blocks.HOPPER)
                || !level.getBlockState(pumpkin.below(2)).is(Blocks.COPPER_BLOCK.weathering().unaffected())) return;

        CopperGolem golem = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.TRIGGERED);
        if (golem == null) return;
        BlockPos base = pumpkin.below(2);
        golem.setPos(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        golem.setCustomName(Component.literal(GOLEM_NAME));
        golem.setCustomNameVisible(false);
        disableVanillaSorterBrain(level, golem);

        // As with the vanilla iron-golem build, the three ingredients are consumed.
        level.removeBlock(pumpkin, false);
        level.removeBlock(pumpkin.below(), false);
        level.removeBlock(base, false);
        level.addFreshEntity(golem);
        LOADED_GOLEMS.add(golem);
        SEARCH_CENTERS.put(golem.getUUID(), base.immutable());
    }

    private static void returnRequestStack(ServerLevel level, CopperGolem golem, ItemRequest request) {
        if (request.carried.isEmpty()) return;
        ServerPlayer requester = level.getServer().getPlayerList().getPlayer(request.requester);
        ItemStack returned = request.carried.copy();
        if (requester != null && requester.level() instanceof ServerLevel requesterLevel) {
            if (!requester.getInventory().add(returned)) requester.spawnAtLocation(requesterLevel, returned);
        } else {
            ItemEntity dropped = new ItemEntity(level, golem.getX(), golem.getEyeY() - 0.15, golem.getZ(), returned);
            level.addFreshEntity(dropped);
        }
        request.carried = ItemStack.EMPTY;
        golem.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private static void completeItemRequest(ServerLevel level, CopperGolem golem, ItemRequest request) {
        ITEM_REQUESTS.remove(golem.getUUID(), request);
        ServerPlayer requester = level.getServer().getPlayerList().getPlayer(request.requester);
        if (requester == null) return;
        int missing = Math.max(0, request.remaining);
        int delivered = Math.max(0, request.requested - missing);
        requester.sendSystemMessage(Component.literal("Solicitud completada: recibiste " + delivered + " de "
                + request.requested + " " + request.itemName + "; faltan " + missing + "."));
    }

    private static boolean tickItemRequest(ServerLevel level, CopperGolem golem, ItemRequest request) {
        ServerPlayer requester = level.getServer().getPlayerList().getPlayer(request.requester);
        if (requester == null || requester.level() != level) return true;

        if (!request.carried.isEmpty()) {
            if (golem.distanceToSqr(requester) > 4.0) {
                if (golem.getNavigation().isDone()) golem.getNavigation().moveTo(requester, WALK_SPEED);
                return true;
            }
            ItemStack thrown = request.carried.copy();
            ItemEntity itemEntity = new ItemEntity(level, golem.getX(), golem.getEyeY() - 0.15, golem.getZ(), thrown);
            Vec3 flight = requester.position().subtract(golem.position()).normalize().scale(0.32).add(0.0, 0.16, 0.0);
            itemEntity.setDeltaMovement(flight);
            itemEntity.setThrower(requester);
            level.addFreshEntity(itemEntity);
            request.remaining -= thrown.getCount();
            request.carried = ItemStack.EMPTY;
            golem.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            if (request.remaining <= 0) completeItemRequest(level, golem, request);
            return true;
        }

        if (request.source != null) {
            if (!near(golem, request.source)) {
                if (golem.getNavigation().isDone()) golem.getNavigation().moveTo(
                        request.source.getX() + 0.5, request.source.getY(), request.source.getZ() + 0.5, WALK_SPEED);
                return true;
            }
            openChestForGolem(level, golem, request.source);
            BlockEntity entity = level.getBlockEntity(request.source);
            if (entity instanceof ChestBlockEntity chest && request.slot >= 0 && request.slot < chest.getContainerSize()) {
                ItemStack present = chest.getItem(request.slot);
                if (!present.isEmpty() && matches(present, request.itemName)) {
                    int amount = Math.min(request.remaining, present.getCount());
                    request.carried = chest.removeItem(request.slot, amount);
                    chest.setChanged();
                    golem.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, request.carried.copy());
                }
            }
            request.source = null;
            request.slot = -1;
            golem.getNavigation().stop();
            return true;
        }

        BlockPos center = SEARCH_CENTERS.computeIfAbsent(golem.getUUID(), id -> golem.blockPosition().immutable());
        for (ChestRef chest : nearbyChests(level, center).stream()
                .sorted(Comparator.comparingDouble(ref -> ref.pos().distToCenterSqr(golem.position()))).toList()) {
            for (int i = 0; i < chest.container().getContainerSize(); i++) {
                ItemStack stack = chest.container().getItem(i);
                if (stack.isEmpty() || !matches(stack, request.itemName)) continue;
                request.source = chest.pos();
                request.slot = i;
                ROUTES.remove(golem.getUUID());
                golem.getNavigation().stop();
                if (!near(golem, request.source)) golem.getNavigation().moveTo(
                        request.source.getX() + 0.5, request.source.getY(), request.source.getZ() + 0.5, WALK_SPEED);
                return true;
            }
        }
        completeItemRequest(level, golem, request);
        return true;
    }

    private static void tickSorters(ServerLevel level) {
        for (CopperGolem golem : List.copyOf(LOADED_GOLEMS)) {
            if (golem.level() != level || !golem.isAlive() || !isSorter(golem)) continue;
            if (golem.tickCount % WORK_INTERVAL == 0) {
                ItemRequest request = ITEM_REQUESTS.get(golem.getUUID());
                if (request != null && tickItemRequest(level, golem, request)) continue;
                doSortingStep(level, golem);
            }
        }
    }

    private static void doSortingStep(ServerLevel level, CopperGolem golem) {
        SortRoute route = ROUTES.get(golem.getUUID());
        BlockPos searchCenter = SEARCH_CENTERS.computeIfAbsent(golem.getUUID(), id -> golem.blockPosition().immutable());
        if (route == null) {
            route = findRoute(level, golem, searchCenter);
            if (route == null) return;
            ROUTES.put(golem.getUUID(), route);
        }
        continueRoute(level, golem, route);
    }

    private static SortRoute findRoute(ServerLevel level, CopperGolem golem, BlockPos searchCenter) {
        List<ChestRef> chests = nearbyChests(level, searchCenter);
        Map<BlockPos, Long> unreachable = UNREACHABLE_UNTIL.computeIfAbsent(golem.getUUID(), id -> new HashMap<>());
        unreachable.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());

        Map<BlockPos, SortRule> rules = new HashMap<>();
        for (ChestRef chest : chests) rules.put(chest.pos(), sortingRule(level, chest));
        List<ChestRef> targets = chests.stream()
                .filter(c -> rules.get(c.pos()) != null)
                .filter(c -> !unreachable.containsKey(c.pos()))
                .sorted(Comparator.comparingDouble(c -> c.pos().distToCenterSqr(golem.position())))
                .toList();
        List<ChestRef> ordinaryStorage = chests.stream()
                .filter(c -> rules.get(c.pos()) == null)
                .filter(c -> !unreachable.containsKey(c.pos()))
                .toList();

        // First move unwanted items out of any configured sorter chest.
        for (ChestRef target : targets) {
            SortRule rule = rules.get(target.pos());
            int wrongSlot = firstWrongSlot(target.container(), rule);
            if (wrongSlot < 0) continue;
            ItemStack stack = target.container().getItem(wrongSlot);
            List<ChestRef> destinations = ordinaryStorage.stream()
                    .filter(c -> capacity(List.of(c.container()), stack) > 0)
                    .sorted(Comparator.comparingDouble(c -> c.pos().distToCenterSqr(golem.position())))
                    .toList();
            if (destinations.isEmpty()) {
                playFullAlarm(level, golem);
                return null;
            }
            return new SortRoute(target.pos(), destinations.getFirst().pos(), rule, RouteKind.CLEAR_WRONG, RoutePhase.TO_SOURCE);
        }

        // Bring matching contents from ordinary chests to named or sign-configured chests.
        for (ChestRef target : targets) {
            SortRule rule = rules.get(target.pos());
            for (ChestRef source : ordinaryStorage.stream()
                    .sorted(Comparator.comparingDouble(c -> c.pos().distToCenterSqr(golem.position())))
                    .toList()) {
                int slot = firstMatchingSlot(source.container(), rule);
                if (slot >= 0 && capacity(List.of(target.container()), source.container().getItem(slot)) > 0) {
                    return new SortRoute(source.pos(), target.pos(), rule, RouteKind.MOVE_MATCHING, RoutePhase.TO_SOURCE);
                }
            }
        }
        return null;
    }

    private static void continueRoute(ServerLevel level, CopperGolem golem, SortRoute route) {
        BlockPos goal = route.phase == RoutePhase.TO_SOURCE ? route.source : route.destination;
        if (!near(golem, goal)) {
            if (golem.getNavigation().isDone()) {
                boolean started = golem.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, WALK_SPEED);
                if (!started) {
                    UNREACHABLE_UNTIL.computeIfAbsent(golem.getUUID(), id -> new HashMap<>())
                            .put(goal.immutable(), level.getGameTime() + 600L);
                    ROUTES.remove(golem.getUUID());
                }
            }
            return;
        }

        if (route.phase == RoutePhase.TO_SOURCE) {
            openChestForGolem(level, golem, route.source);
            route.phase = RoutePhase.TO_DESTINATION;
            return;
        }

        openChestForGolem(level, golem, route.destination);
        transferAtDestination(level, golem, route);
        ROUTES.remove(golem.getUUID());
    }

    private static void transferAtDestination(ServerLevel level, CopperGolem golem, SortRoute route) {
        BlockEntity sourceEntity = level.getBlockEntity(route.source);
        BlockEntity destinationEntity = level.getBlockEntity(route.destination);
        if (!(sourceEntity instanceof ChestBlockEntity source) || !(destinationEntity instanceof ChestBlockEntity destination)) return;

        int slot = route.kind == RouteKind.CLEAR_WRONG
                ? firstWrongSlot(source, route.rule)
                : firstMatchingSlot(source, route.rule);
        if (slot < 0) return;

        ItemStack sourceStack = source.getItem(slot);
        int room = capacity(List.of(destination), sourceStack);
        if (room <= 0) {
            if (route.kind == RouteKind.CLEAR_WRONG) playFullAlarm(level, golem);
            return;
        }

        int movingCount = Math.min(sourceStack.getCount(), room);
        ItemStack moving = source.removeItem(slot, movingCount);
        ItemStack remainder = insert(destination, moving);
        int deposited = moving.getCount() - remainder.getCount();
        if (deposited > 0) playDepositBell(level, route.destination);
        if (!remainder.isEmpty()) {
            restoreRemainder(level, source, route.source, slot, remainder);
        }
        source.setChanged();
        destination.setChanged();
    }

    private static void openChestForGolem(ServerLevel level, CopperGolem golem, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof ChestBlockEntity)) return;
        ScheduledChestClose previous = OPEN_CHESTS.remove(golem.getUUID());
        if (previous != null && previous.level == level) {
            level.blockEvent(previous.pos, level.getBlockState(previous.pos).getBlock(), 1, 0);
        }
        level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 1);
        OPEN_CHESTS.put(golem.getUUID(), new ScheduledChestClose(level, pos.immutable(), level.getGameTime() + 10));
    }

    private static void tickChestCloseEvents(ServerLevel level) {
        for (Map.Entry<UUID, ScheduledChestClose> entry : new HashMap<>(OPEN_CHESTS).entrySet()) {
            ScheduledChestClose close = entry.getValue();
            if (close.level == level && level.getGameTime() >= close.closeAt) {
                level.blockEvent(close.pos, level.getBlockState(close.pos).getBlock(), 1, 0);
                OPEN_CHESTS.remove(entry.getKey());
            }
        }
    }

    private static List<ChestRef> nearbyChests(ServerLevel level, BlockPos center) {
        List<ChestRef> result = new ArrayList<>();
        BlockPos.betweenClosed(center.offset(-SEARCH_XZ, -SEARCH_Y, -SEARCH_XZ),
                center.offset(SEARCH_XZ, SEARCH_Y, SEARCH_XZ)).forEach(pos -> {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof ChestBlockEntity chest) {
                result.add(new ChestRef(pos.immutable(), chest, chest));
            }
        });
        return result;
    }

    private static String customName(ChestBlockEntity chest) {
        Component name = chest.getCustomName();
        if (name == null) return null;
        String value = name.getString().trim();
        String normalized = normalize(value);
        // Treat vanilla default titles/translation keys as storage chests, not sorting targets.
        if (normalized.equals("chest") || normalized.equals("trappedchest")
                || normalized.equals("containerchest") || normalized.equals("containerchestdouble")
                || normalized.equals("containertrappedchest")
                || normalized.equals("blockminecraftchest") || normalized.equals("blockminecrafttrappedchest")
                || normalized.equals("cofre") || normalized.equals("cofretrampa") || normalized.equals("cofreatrapado")) {
            return null;
        }
        return value;
    }

    private static int firstWrongSlot(Container inventory, SortRule rule) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && !rule.matches(stack)) return i;
        }
        return -1;
    }

    private static int firstMatchingSlot(Container inventory, SortRule rule) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && rule.matches(stack)) return i;
        }
        return -1;
    }

    private static SortRule sortingRule(ServerLevel level, ChestRef chest) {
        SortRule ownRule = directSortingRule(level, chest);
        if (ownRule != null) return ownRule;

        // A double chest is one storage target: inherit the label/sign rule from its signed half.
        ChestRef partner = pairedChest(level, chest);
        return partner == null ? null : directSortingRule(level, partner);
    }

    private static SortRule directSortingRule(ServerLevel level, ChestRef chest) {
        String name = customName(chest.entity());
        if (name != null) {
            List<String> names = parseItemNames(name);
            return names.isEmpty() ? null : new SortRule(names, List.of());
        }
        List<String> categories = signCategories(level, chest.pos());
        return categories.isEmpty() ? null : new SortRule(List.of(), categories);
    }

    private static ChestRef pairedChest(ServerLevel level, ChestRef chest) {
        BlockState state = level.getBlockState(chest.pos());
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
        BlockPos partnerPos = chest.pos().relative(ChestBlock.getConnectedDirection(state));
        BlockEntity partnerEntity = level.getBlockEntity(partnerPos);
        if (partnerEntity instanceof ChestBlockEntity partner) return new ChestRef(partnerPos.immutable(), partner, partner);
        return null;
    }

    private static List<String> parseItemNames(String chestName) {
        if (chestName == null) return List.of();
        return java.util.Arrays.stream(chestName.split("[,;|]"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static List<String> signCategories(ServerLevel level, BlockPos chestPos) {
        List<String> categories = new ArrayList<>();
        // Signs can attach beside, above, or below either half of a chest; inspect the full 3x3x3 neighborhood.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockEntity entity = level.getBlockEntity(chestPos.offset(dx, dy, dz));
                    if (!(entity instanceof SignBlockEntity sign)) continue;
                    for (Component message : sign.getText(SignTextSlot.FRONT).getMessages(false)) addCategoryTokens(categories, message.getString());
                    for (Component message : sign.getText(SignTextSlot.BACK).getMessages(false)) addCategoryTokens(categories, message.getString());
                }
            }
        }
        return categories.stream().distinct().toList();
    }

    private static void addCategoryTokens(List<String> categories, String line) {
        for (String raw : line.split("[,;|]")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            if (token.startsWith("(") && token.endsWith(")") && token.length() > 2) {
                String exactName = token.substring(1, token.length() - 1).trim();
                if (!exactName.isEmpty()) categories.add("@exact:" + exactName);
                continue;
            }
            String key = normalize(token);
            switch (key) {
                case "wood", "woods", "wooden", "madera" -> categories.add("@wood");
                case "slab", "slabs", "losa", "losas" -> categories.add("@slabs");
                case "fence", "fences", "valla", "vallas" -> categories.add("@fences");
                case "wool", "wools", "lana", "lanas" -> categories.add("@wool");
                default -> {
                    if (token.startsWith("#")) {
                        String tagId = token.substring(1);
                        if (!tagId.contains(":")) tagId = "minecraft:" + tagId;
                        categories.add("#" + tagId.toLowerCase(java.util.Locale.ROOT));
                    } else {
                        // Generic terms such as "sword" match the corresponding tag or whole ID words.
                        categories.add("@item:" + key);
                    }
                }
            }
        }
    }

    private static boolean matches(ItemStack stack, String name) {
        if (stack.isEmpty() || name == null) return false;
        String wanted = normalize(name.trim());
        String displayName = normalize(stack.getHoverName().getString());
        if (displayName.equals(wanted)) return true;
        String registryId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String path = itemPath(stack);
        return normalize(registryId).equals(wanted) || normalize(path).equals(wanted);
    }

    private static String itemPath(ItemStack stack) {
        // Use the registry key, not Item.toString(), which is not a stable identifier in 26.x.
        String identifier = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int colon = identifier.lastIndexOf(':');
        return colon < 0 ? identifier : identifier.substring(colon + 1);
    }

    private static boolean matchesCategory(ItemStack stack, String category) {
        String path = itemPath(stack).toLowerCase(java.util.Locale.ROOT);
        return switch (category) {
            case "@wood" -> hasAnyTag(stack, "minecraft:logs", "minecraft:planks", "minecraft:wooden_slabs",
                    "minecraft:wooden_stairs", "minecraft:wooden_fences", "minecraft:wooden_fence_gates",
                    "minecraft:wooden_doors", "minecraft:wooden_trapdoors", "minecraft:wooden_buttons",
                    "minecraft:wooden_pressure_plates", "minecraft:wooden_signs", "minecraft:boats", "minecraft:chest_boats")
                    || isWoodPath(path);
            case "@slabs" -> hasAnyTag(stack, "minecraft:slabs", "minecraft:wooden_slabs", "minecraft:stone_slabs")
                    || path.equals("slab") || path.endsWith("_slab");
            case "@fences" -> hasAnyTag(stack, "minecraft:fences", "minecraft:wooden_fences")
                    || path.equals("fence") || path.endsWith("_fence");
            case "@wool" -> hasAnyTag(stack, "minecraft:wool", "minecraft:wool_carpets", "minecraft:wool_slabs", "minecraft:wool_stairs")
                    || path.equals("wool") || path.endsWith("_wool") || path.endsWith("_wool_carpet")
                    || path.endsWith("_wool_slab") || path.endsWith("_wool_stairs");
            default -> category.startsWith("#") && hasTag(stack, category.substring(1))
                    || category.startsWith("@exact:") && matches(stack, category.substring("@exact:".length()))
                    || category.startsWith("@item:") && matchesGenericItemType(stack, category.substring("@item:".length()));
        };
    }

    private static boolean matchesGenericItemType(ItemStack stack, String type) {
        String wanted = normalize(type);
        String registryId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(java.util.Locale.ROOT);
        String path = itemPath(stack).toLowerCase(java.util.Locale.ROOT);
        String displayName = normalize(stack.getHoverName().getString());

        // Exact names and registry IDs work for every registered vanilla or modded item/block.
        if (normalize(registryId).equals(wanted) || normalize(path).equals(wanted) || displayName.equals(wanted)) return true;

        String raw = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (raw.contains(":")) {
            String namespace = raw.substring(0, raw.indexOf(':'));
            String requestedPath = raw.substring(raw.indexOf(':') + 1);
            if (registryId.equals(namespace + ":" + requestedPath)) return true;
        }

        // Category words match complete ID words/phrases (e.g. "sword" matches iron_sword,
        // but "stone" does not accidentally match redstone). Also try common plurals.
        String[] pathWords = path.split("[^a-z0-9]+");
        List<String> forms = categoryForms(wanted);
        for (String form : forms) {
            String[] wantedWords = form.split("[^a-z0-9]+");
            if (containsWordPhrase(pathWords, wantedWords)) return true;
        }

        for (String form : forms) {
            String tagId = form.contains(":") ? form : "minecraft:" + form;
            if (hasTag(stack, tagId)) return true;
        }
        return false;
    }

    private static boolean containsWordPhrase(String[] haystack, String[] needle) {
        if (needle.length == 0) return false;
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            boolean matches = true;
            for (int i = 0; i < needle.length; i++) {
                if (!haystack[start + i].equals(needle[i])) { matches = false; break; }
            }
            if (matches) return true;
        }
        return false;
    }

    private static List<String> categoryForms(String value) {
        List<String> forms = new ArrayList<>();
        forms.add(value);
        String singular = singularizeCategory(value);
        if (!singular.equals(value)) forms.add(singular);
        return forms;
    }

    private static String singularizeCategory(String value) {
        return switch (value) {
            case "axes" -> "axe";
            case "swords" -> "sword";
            case "tools" -> "tool";
            case "berries" -> "berry";
            default -> value.endsWith("s") && value.length() > 1 && !value.endsWith("ss")
                    ? value.substring(0, value.length() - 1) : value;
        };
    }

    private static boolean isWoodPath(String path) {
        if (path.contains("log") || path.endsWith("wood") || path.contains("planks")
                || path.contains("stem") || path.contains("hyphae")) return true;
        for (String species : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
                "mangrove", "cherry", "bamboo", "crimson", "warped", "pale_oak")) {
            if (path.startsWith(species + "_") && (path.endsWith("_slab") || path.endsWith("_stairs")
                    || path.endsWith("_fence") || path.endsWith("_fence_gate") || path.endsWith("_door")
                    || path.endsWith("_trapdoor") || path.endsWith("_button") || path.endsWith("_pressure_plate")
                    || path.endsWith("_sign") || path.endsWith("_hanging_sign") || path.endsWith("_boat")
                    || path.endsWith("_chest_boat"))) return true;
        }
        return false;
    }

    private static boolean hasAnyTag(ItemStack stack, String... tagIds) {
        for (String id : tagIds) if (hasTag(stack, id)) return true;
        return false;
    }

    private static boolean hasTag(ItemStack stack, String tagId) {
        try {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, Identifier.parse(tagId));
            return stack.is(tag);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value.replace("_", "").replace(" ", "").replace("-", "").replace(".", "").replace(":", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static int capacity(List<Container> destinations, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        int room = 0;
        for (Container destination : destinations) {
            for (int i = 0; i < destination.getContainerSize(); i++) {
                ItemStack present = destination.getItem(i);
                if (present.isEmpty()) room += Math.min(stack.getMaxStackSize(), destination.getMaxStackSize());
                else if (ItemStack.isSameItemSameComponents(present, stack)) {
                    int limit = Math.min(present.getMaxStackSize(), destination.getMaxStackSize());
                    room += Math.max(0, limit - present.getCount());
                }
                if (room >= stack.getCount()) return room;
            }
        }
        return room;
    }

    private static void restoreRemainder(ServerLevel level, Container source, BlockPos sourcePos, int originalSlot, ItemStack remainder) {
        ItemStack left = insert(source, remainder);
        if (!left.isEmpty()) {
            ItemStack slot = source.getItem(originalSlot);
            if (slot.isEmpty()) {
                source.setItem(originalSlot, left.copy());
            } else {
                // Last-resort recovery: drop only the unaccepted remainder instead of overwriting existing contents.
                ItemEntity dropped = new ItemEntity(level, sourcePos.getX() + 0.5, sourcePos.getY() + 1.0, sourcePos.getZ() + 0.5, left.copy());
                level.addFreshEntity(dropped);
            }
        }
    }

    private static ItemStack insert(Container destination, ItemStack incoming) {
        ItemStack remainder = incoming.copy();
        for (int i = 0; i < destination.getContainerSize() && !remainder.isEmpty(); i++) {
            ItemStack current = destination.getItem(i);
            if (!current.isEmpty() && ItemStack.isSameItemSameComponents(current, remainder)) {
                int limit = Math.min(current.getMaxStackSize(), destination.getMaxStackSize());
                int add = Math.min(remainder.getCount(), limit - current.getCount());
                if (add > 0) {
                    ItemStack updated = current.copy();
                    updated.grow(add);
                    destination.setItem(i, updated);
                    remainder.shrink(add);
                }
            }
        }
        for (int i = 0; i < destination.getContainerSize() && !remainder.isEmpty(); i++) {
            if (!destination.getItem(i).isEmpty()) continue;
            int add = Math.min(remainder.getCount(), Math.min(remainder.getMaxStackSize(), destination.getMaxStackSize()));
            destination.setItem(i, remainder.copyWithCount(add));
            remainder.shrink(add);
        }
        destination.setChanged();
        return remainder;
    }

    private static boolean near(CopperGolem golem, BlockPos pos) {
        return golem.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 4.0;
    }

    private static void playDepositBell(ServerLevel level, BlockPos chestPos) {
        level.playSound(null, chestPos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.75F, 1.12F);
    }

    private static void playFullAlarm(ServerLevel level, CopperGolem golem) {
        long now = level.getGameTime();
        Long last = LAST_ALARM.get(golem.getUUID());
        if (last == null || now - last >= ALARM_INTERVAL) {
            level.playSound(null, golem.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.NEUTRAL, 1.0F, 1.45F);
            LAST_ALARM.put(golem.getUUID(), now);
        }
    }

    private static void disableVanillaSorterBrain(ServerLevel level, CopperGolem golem) {
        golem.getBrain().stopAll(level, golem);
        golem.getBrain().removeAllBehaviors();
    }

    private static boolean isSorter(CopperGolem golem) {
        return golem.getCustomName() != null && GOLEM_NAME.equals(golem.getCustomName().getString());
    }

    private enum RouteKind { CLEAR_WRONG, MOVE_MATCHING }
    private enum RoutePhase { TO_SOURCE, TO_DESTINATION }
    private static final class SortRoute {
        private final BlockPos source;
        private final BlockPos destination;
        private final SortRule rule;
        private final RouteKind kind;
        private RoutePhase phase;
        private SortRoute(BlockPos source, BlockPos destination, SortRule rule, RouteKind kind, RoutePhase phase) {
            this.source = source.immutable();
            this.destination = destination.immutable();
            this.rule = rule;
            this.kind = kind;
            this.phase = phase;
        }
    }
    private record SortRule(List<String> names, List<String> categories) {
        private boolean matches(ItemStack stack) {
            for (String name : names) if (CopperChestSorter.matches(stack, name)) return true;
            for (String category : categories) if (matchesCategory(stack, category)) return true;
            return false;
        }
    }
    private static final class ItemRequest {
        private final UUID requester;
        private final String itemName;
        private final int requested;
        private int remaining;
        private BlockPos source;
        private int slot = -1;
        private ItemStack carried = ItemStack.EMPTY;

        private ItemRequest(UUID requester, String itemName, int remaining) {
            this.requester = requester;
            this.itemName = itemName;
            this.requested = remaining;
            this.remaining = remaining;
        }
    }
    private record ScheduledChestClose(ServerLevel level, BlockPos pos, long closeAt) {}
    private record PendingBuild(ServerLevel level, BlockPos pumpkinPos) {}
    private record ChestRef(BlockPos pos, ChestBlockEntity entity, Container container) {}
}
