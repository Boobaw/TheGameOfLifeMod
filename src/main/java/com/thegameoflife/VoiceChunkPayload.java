package com.thegameoflife;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record VoiceChunkPayload(byte[] data) implements CustomPacketPayload {
    public static final int MAX_BYTES = 1_000_000;

    public static final CustomPacketPayload.Type<VoiceChunkPayload> TYPE =
            new CustomPacketPayload.Type<>(ModIds.VOICE_CHUNK);

    public static final StreamCodec<RegistryFriendlyByteBuf, VoiceChunkPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeByteArray(payload.data()),
                    buf -> new VoiceChunkPayload(buf.readByteArray(MAX_BYTES))
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
