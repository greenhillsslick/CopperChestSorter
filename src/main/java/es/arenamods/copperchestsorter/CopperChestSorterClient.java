package es.arenamods.copperchestsorter;

import es.arenamods.copperchestsorter.network.OpenItemRequestPayload;
import es.arenamods.copperchestsorter.network.SubmitItemRequestPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CopperChestSorterClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(OpenItemRequestPayload.TYPE, (payload, context) ->
                context.client().execute(() -> context.client().setScreenAndShow(new ItemRequestScreen(payload.entityId()))));
    }

    private static final class ItemRequestScreen extends Screen {
        private final int entityId;
        private EditBox itemName;
        private EditBox count;
        private String status = "";

        private ItemRequestScreen(int entityId) {
            super(Component.literal("Pedir un objeto al golem"));
            this.entityId = entityId;
        }

        @Override
        protected void init() {
            int center = width / 2;
            int top = height / 2 - 65;
            itemName = addRenderableWidget(new EditBox(font, center - 100, top + 28, 200, 20,
                    Component.literal("Objeto o bloque")));
            itemName.setMaxLength(100);
            itemName.setHint(Component.literal("Ej.: minecraft:stone"));
            count = addRenderableWidget(new EditBox(font, center - 100, top + 69, 200, 20,
                    Component.literal("Cantidad")));
            count.setMaxLength(6);
            count.setHint(Component.literal("Cantidad (1–9999)"));

            addRenderableWidget(Button.builder(Component.literal("Solicitar"), button -> submit())
                    .bounds(center - 100, top + 105, 96, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancelar solicitud"), button -> cancelRequest())
                    .bounds(center + 4, top + 105, 104, 20).build());
            setInitialFocus(itemName);
        }

        private void submit() {
            String requested = itemName.getValue().trim();
            int amount;
            try { amount = Integer.parseInt(count.getValue().trim()); }
            catch (NumberFormatException ignored) { amount = 0; }
            if (requested.isEmpty() || amount < 1 || amount > 9999) {
                status = "Escribe un objeto y una cantidad entre 1 y 9999.";
                return;
            }
            ClientPlayNetworking.send(new SubmitItemRequestPayload(entityId, requested, amount));
            onClose();
        }

        private void cancelRequest() {
            ClientPlayNetworking.send(new SubmitItemRequestPayload(entityId, "", 0));
            onClose();
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            extractMenuBackground(graphics);
            int center = width / 2;
            int top = height / 2 - 65;
            graphics.fill(center - 118, top - 6, center + 118, top + 170, 0xE0101010);
            graphics.outline(center - 118, top - 6, 236, 176, 0xFFB87535);
            graphics.centeredText(font, title, center, top + 2, 0xFFFFFFFF);
            graphics.text(font, "Nombre exacto o ID del objeto/bloque:", center - 100, top + 16, 0xFFE0E0E0);
            graphics.text(font, "Cantidad total que debe traerte:", center - 100, top + 57, 0xFFE0E0E0);
            graphics.text(font, "Ejemplo: stone = solo piedra; minecraft:diamond = diamante.", center - 112, top + 132, 0xFFBBBBBB);
            if (!status.isEmpty()) graphics.centeredText(font, status, center, top + 151, 0xFFFF5555);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
