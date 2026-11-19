package es.arenamods.copperchestsorter.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SubmitItemRequestPayload(int entityId, String itemName, int count) implements CustomPacketPayload {
    public static final Type<SubmitItemRequestPayload> TYPE = new Type<>(Identifier.parse("copperchestsorter:submit_item_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SubmitItemRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SubmitItemRequestPayload::entityId,
            ByteBufCodecs.STRING_UTF8, SubmitItemRequestPayload::itemName,
            ByteBufCodecs.VAR_INT, SubmitItemRequestPayload::count,
            SubmitItemRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
