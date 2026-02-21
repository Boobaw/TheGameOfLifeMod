package com.thegameoflife;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record VoiceResultPayload(String message) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<VoiceResultPayload> TYPE =
            new CustomPacketPayload.Type<>(ModIds.VOICE_RESULT);

    public static final StreamCodec<RegistryFriendlyByteBuf, VoiceResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeUtf(payload.message()),
                    buf -> new VoiceResultPayload(buf.readUtf())
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
