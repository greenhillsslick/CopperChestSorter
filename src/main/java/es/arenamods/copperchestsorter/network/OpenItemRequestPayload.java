package es.arenamods.copperchestsorter.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record OpenItemRequestPayload(int entityId) implements CustomPacketPayload {
    public static final Type<OpenItemRequestPayload> TYPE = new Type<>(Identifier.parse("copperchestsorter:open_item_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenItemRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenItemRequestPayload::entityId, OpenItemRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
