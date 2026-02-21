package com.thegameoflife;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class VoiceServer {
    private static final Logger LOGGER = TheGameOfLIfeMod.LOGGER;

    private static final String MODEL_EN =
            "thegameoflife/vosk-model/vosk-model-small-en-us-0.15";
    private static final String MODEL_RU =
            "thegameoflife/vosk-model/vosk-model-small-ru-0.22";

    private static final Path KEYWORDS_FILE = Path.of("thegameoflife/keywords.txt");

    private static final Object MODEL_LOCK = new Object();
    private static Model modelEn;
    private static Model modelRu;

    private static ExecutorService executor;
    private static volatile List<String> keywords = new ArrayList<>();
    private static final ConcurrentHashMap<UUID, LastKeys> lastKeys = new ConcurrentHashMap<>();

    private static boolean receiverRegistered = false;

    private VoiceServer() {
    }

    public static void init() {
        ensureExecutor();
        loadKeywords();

        if (!receiverRegistered) {
            receiverRegistered = true;
            ServerPlayNetworking.registerGlobalReceiver(VoiceChunkPayload.TYPE, (payload, context) -> {
                byte[] data = payload.data();
                if (data.length == 0) return;
                if (data.length > VoiceChunkPayload.MAX_BYTES) {
                    LOGGER.warn("[Voice] payload too large: {}", data.length);
                    return;
                }

                UUID playerId = context.player().getUUID();
                MinecraftServer server = context.server();
                executor.execute(() -> handleChunk(server, playerId, data));
            });
        }
    }

    public static void shutdown() {
        ExecutorService exec = executor;
        executor = null;
        if (exec != null) {
            exec.shutdownNow();
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (MODEL_LOCK) {
            if (modelEn != null) {
                modelEn.close();
                modelEn = null;
            }
            if (modelRu != null) {
                modelRu.close();
                modelRu = null;
            }
        }
    }

    private static void ensureExecutor() {
        if (executor != null && !executor.isShutdown()) return;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "Voice-STT");
            t.setDaemon(true);
            return t;
        };
        executor = Executors.newSingleThreadExecutor(tf);
    }

    private static void handleChunk(MinecraftServer server, UUID playerId, byte[] wavData) {
        try {
            ensureModels();
        } catch (Exception e) {
            LOGGER.error("[Voice] failed to load models", e);
            sendToPlayer(server, playerId, "[KEY][ERROR] model load");
            return;
        }

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavData))) {
            float sr = ais.getFormat().getSampleRate();
            byte[] data = ais.readAllBytes();

            String en = sanitizeForChat(fixMojibake(recognize(modelEn, sr, data)));
            String ru = sanitizeForChat(fixMojibake(recognize(modelRu, sr, data)));

            String enKeys = extractKeywords(en);
            String ruKeys = extractKeywords(ru);

            LastKeys last = lastKeys.computeIfAbsent(playerId, id -> new LastKeys());
            boolean posted = false;

            if (!enKeys.isBlank() && !enKeys.equalsIgnoreCase(last.en)) {
                sendToPlayer(server, playerId, "[KEY][EN] " + enKeys);
                last.en = enKeys;
                posted = true;
            }

            if (!ruKeys.isBlank() && !ruKeys.equalsIgnoreCase(last.ru)) {
                sendToPlayer(server, playerId, "[KEY][RU] " + ruKeys);
                last.ru = ruKeys;
                posted = true;
            }

            if (!posted) {
                sendToPlayer(server, playerId, "[KEY][EMPTY]");
            }
        } catch (Exception e) {
            LOGGER.error("[Voice] STT error", e);
            sendToPlayer(server, playerId, "[KEY][ERROR] " + e.getClass().getSimpleName());
        }
    }

    private static void ensureModels() throws Exception {
        synchronized (MODEL_LOCK) {
            if (modelEn == null) modelEn = new Model(MODEL_EN);
            if (modelRu == null) modelRu = new Model(MODEL_RU);
        }
    }

    private static void loadKeywords() {
        try {
            if (!Files.exists(KEYWORDS_FILE)) {
                Files.createDirectories(KEYWORDS_FILE.getParent());
                Files.writeString(KEYWORDS_FILE, "# one keyword per line\n", StandardCharsets.UTF_8);
            }

            List<String> lines = Files.readAllLines(KEYWORDS_FILE, StandardCharsets.UTF_8);
            List<String> list = new ArrayList<>();

            for (String line : lines) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                list.add(s.toLowerCase(Locale.ROOT));
            }

            keywords = list;
            LOGGER.info("[Voice] keywords loaded: {}", keywords.size());
        } catch (Exception e) {
            LOGGER.error("[Voice] keywords load error", e);
            keywords = new ArrayList<>();
        }
    }

    private static String extractKeywords(String text) {
        if (text == null) return "";
        String t = text.toLowerCase(Locale.ROOT);

        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String k : keywords) {
            if (!k.isEmpty() && t.contains(k)) found.add(k);
        }

        if (found.isEmpty()) return "";
        return String.join(", ", found);
    }

    private static String recognize(Model model, float sampleRate, byte[] data) throws Exception {
        try (Recognizer rec = new Recognizer(model, sampleRate)) {
            rec.acceptWaveForm(data, data.length);
            String result = rec.getFinalResult();
            return result.replaceAll("(?s).*\"text\"\\s*:\\s*\"(.*?)\".*", "$1").trim();
        }
    }

    private static String fixMojibake(String s) {
        if (s == null) return "";
        if (s.contains("\u00C3\u0090") || s.contains("\u00C3\u0091")) {
            return new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }
        return s;
    }

    private static String sanitizeForChat(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c) || Character.isISOControl(c)) continue;

            if (Character.isLetterOrDigit(c) ||
                    c == ' ' || c == '\'' || c == '-' ||
                    c == '.' || c == ',' || c == '!' || c == '?' || c == ':') {
                b.append(c);
            }
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    private static void sendToPlayer(MinecraftServer server, UUID playerId, String msg) {
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return;
            if (!ServerPlayNetworking.canSend(player, VoiceResultPayload.TYPE)) return;
            ServerPlayNetworking.send(player, new VoiceResultPayload(msg));
        });
    }

    private static final class LastKeys {
        String en = "";
        String ru = "";
    }
}
