package com.thegameoflife;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class VoiceNetworking {
    private static boolean registered = false;

    private VoiceNetworking() {
    }

    public static void registerPayloads() {
        if (registered) return;
        registered = true;

        PayloadTypeRegistry.playC2S().registerLarge(
                VoiceChunkPayload.TYPE,
                VoiceChunkPayload.STREAM_CODEC,
                VoiceChunkPayload.MAX_BYTES
        );
        PayloadTypeRegistry.playS2C().register(
                VoiceResultPayload.TYPE,
                VoiceResultPayload.STREAM_CODEC
        );
    }
}
