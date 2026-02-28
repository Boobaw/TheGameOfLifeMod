package com.thegameoflife;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.slf4j.Logger;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class VoiceServer {
    private static final Logger LOGGER = TheGameOfLIfeMod.LOGGER;

    private static final String MODEL_PATH =
            "thegameoflife/whisper-model/ggml-medium.bin";

    // ─── Параметры аудио ─────────────────────────────────────────────────────

    /** Whisper ВСЕГДА требует 16 000 Hz, mono, float. */
    private static final int WHISPER_SAMPLE_RATE = 16_000;

    /** Минимум для отправки в Whisper: 1 секунда. Меньше — только галлюцинации. */
    private static final int MIN_SAMPLES = WHISPER_SAMPLE_RATE; // 1 сек

    /** Максимум буфера: 15 секунд. Whisper теряет качество на длинных записях. */
    private static final int MAX_BUFFER_SAMPLES = WHISPER_SAMPLE_RATE * 15;

    /**
     * RMS-порог тишины (в диапазоне 0-32768).
     * Уменьши до 100 если плохо ловит тихую речь,
     * увеличь до 400 если фоновый шум мешает.
     */
    private static final double SILENCE_RMS_THRESHOLD = 200.0;

    /**
     * Через сколько мс тишины считаем фразу законченной и отправляем в Whisper.
     * 800 мс — нормальная пауза между предложениями.
     */
    private static final long SILENCE_FLUSH_MS = 800;

    // ─── Инфраструктура ──────────────────────────────────────────────────────

    private static final Gson GSON = new Gson();

    private static final Object MODEL_LOCK = new Object();
    private static WhisperJNI whisper;
    private static WhisperContext whisperCtx;

    /** Память контекста: UUID -> последняя фраза (для initialPrompt) */
    private static final Map<UUID, String> playerContexts = new ConcurrentHashMap<>();

    /**
     * Буферы накопления фраз. Копим аудио пока игрок говорит,
     * отправляем целым предложением — это главное улучшение качества.
     */
    private static final Map<UUID, SpeechBuffer> speechBuffers = new ConcurrentHashMap<>();

    private static ScheduledExecutorService flushScheduler;
    private static ExecutorService executor;
    private static boolean receiverRegistered = false;
    public static volatile boolean isReady = false;

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    // ─── Таблица авто-исправлений ────────────────────────────────────────────
    /**
     * Whisper на русском делает одни и те же ошибки — исправляем после распознавания.
     */
    private static final Map<String, String> CORRECTIONS_RU = new LinkedHashMap<>();
    static {
        CORRECTIONS_RU.put("майн крафт",                    "майнкрафт");
        CORRECTIONS_RU.put("ред стоун",                     "редстоун");
        CORRECTIONS_RU.put("обси диан",                     "обсидиан");
        CORRECTIONS_RU.put("эндер мен",                     "эндермен");
        CORRECTIONS_RU.put("эндер мэн",                     "эндермен");
        CORRECTIONS_RU.put("бед рок",                       "бедрок");
        CORRECTIONS_RU.put("\\bnether\\b",                  "незер");
        CORRECTIONS_RU.put("\\bcreeper\\b",                 "крипер");
        CORRECTIONS_RU.put("\\bspawn\\b",                   "спавн");
        CORRECTIONS_RU.put("\\bgamemode\\b",                "геймод");
        CORRECTIONS_RU.put("\\bcreative\\b",                "креатив");
        CORRECTIONS_RU.put("\\bsurvival\\b",                "выживание");
        CORRECTIONS_RU.put("\\bchunk\\b",                   "чанк");
        CORRECTIONS_RU.put("\\bbiome\\b",                   "биом");
        CORRECTIONS_RU.put("\\bт п\\b",                     "тп");
        CORRECTIONS_RU.put("\\bти пи\\b",                   "тп");
        CORRECTIONS_RU.put("\\bтэ пэ\\b",                   "тп");
        CORRECTIONS_RU.put("\\bгейм мод\\b",                "геймод");
    }

    // ─── Режим распознавания ─────────────────────────────────────────────────

    public enum VoiceMode { EN, RU }
    private static volatile VoiceMode currentMode = VoiceMode.RU;

    public static void setMode(VoiceMode mode) {
        currentMode = mode;
        LOGGER.info("[Voice] Mode changed to: {}", mode);
    }

    public static Component getStatus() {
        int queue = 0, active = 0;
        if (executor instanceof ThreadPoolExecutor tpe) {
            queue  = tpe.getQueue().size();
            active = tpe.getActiveCount();
        }
        String statusText = isReady ? "§aReady" : "§eLoading...";
        return Component.literal(
                "§8[§dVoice§8] §7Status: " + statusText +
                "\n§7Mode: §b" + currentMode +
                "\n§7Queue: §e" + queue +
                "\n§7Active Threads: §a" + active +
                "\n§7Buffers: §e" + speechBuffers.size());
    }

    private VoiceServer() {}

    // ─── Жизненный цикл ──────────────────────────────────────────────────────

    public static void onServerStarted(MinecraftServer server) {
        LOGGER.info("[Voice] Server started, starting executor...");
        ensureExecutor();

        flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Voice-FlushTimer");
            t.setDaemon(true);
            return t;
        });

        executor.execute(() -> {
            try {
                LOGGER.info("[Voice] Preloading Whisper model...");
                ensureModels();
                LOGGER.info("[Voice] Models loaded successfully.");
                server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                        Component.literal("§a[Voice] Голосовой чат готов к работе!"), false));
            } catch (Exception e) {
                LOGGER.error("[Voice] Failed to preload models", e);
            }
        });
    }

    public static void registerNetworking() {
        if (receiverRegistered) return;
        receiverRegistered = true;

        ServerPlayNetworking.registerGlobalReceiver(VoiceChunkPayload.TYPE, (payload, context) -> {
            byte[] data = payload.data();
            if (data.length == 0) return;
            if (data.length > VoiceChunkPayload.MAX_BYTES) {
                LOGGER.warn("[Voice] payload too large: {}", data.length);
                return;
            }
            UUID playerId          = context.player().getUUID();
            MinecraftServer server = context.server();
            if (executor == null || executor.isShutdown()) return;
            executor.execute(() -> handleChunk(server, playerId, data));
        });
    }

    public static void shutdown() {
        if (flushScheduler != null) flushScheduler.shutdownNow();
        speechBuffers.clear();

        ExecutorService exec = executor;
        executor = null;
        if (exec != null) {
            exec.shutdownNow();
            try { exec.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        synchronized (MODEL_LOCK) {
            if (whisperCtx != null) { whisperCtx.close(); whisperCtx = null; }
        }
    }

    private static void ensureExecutor() {
        if (executor != null && !executor.isShutdown()) return;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "Voice-STT");
            t.setDaemon(true);
            return t;
        };
        executor = new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(20),
                tf,
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    // ─── Обработка чанка ─────────────────────────────────────────────────────

    private static void handleChunk(MinecraftServer server, UUID playerId, byte[] wavData) {
        try { ensureModels(); }
        catch (Exception e) {
            LOGGER.error("[Voice] failed to load models", e);
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                if (p != null) p.displayClientMessage(
                        Component.literal("§c[Voice] Ошибка: модель не найдена!"), false);
            });
            return;
        }

        try {
            // 1. Декодируем WAV, ресэмплируем до 16 000 Hz mono
            float[] samples = decodeAndResample(wavData);
            if (samples == null) return;

            // 2. Pre-emphasis — усиливает согласные (с/ш/т/к), улучшает чёткость речи
            applyPreEmphasis(samples, 0.97f);

            // 3. Нормализация амплитуды — поднимает тихий голос
            normalizeAmplitude(samples);

            // 4. Определяем: речь или тишина
            double rms       = calculateRMSFloat(samples);
            boolean isSilent = rms < SILENCE_RMS_THRESHOLD;

            LOGGER.debug("[Voice] Chunk from {}: {} samples, RMS={}, silent={}",
                    playerId, samples.length, String.format("%.0f", rms), isSilent);

            // 5. Аккумулятор — копим чанки пока говорим, flush по тишине
            SpeechBuffer buf = speechBuffers.computeIfAbsent(
                    playerId, id -> new SpeechBuffer());

            if (isSilent) {
                if (buf.hasSpeech()) {
                    buf.scheduleSilenceFlush(flushScheduler, () ->
                            executor.execute(() -> flushBuffer(server, playerId)));
                }
            } else {
                buf.cancelFlush();
                buf.addSamples(samples);
                if (buf.size() >= MAX_BUFFER_SAMPLES) {
                    LOGGER.debug("[Voice] Buffer full, forcing flush for {}", playerId);
                    flushBuffer(server, playerId);
                }
            }

        } catch (Exception e) {
            LOGGER.error("[Voice] STT error", e);
        }
    }

    /** Сбрасывает накопленный буфер в Whisper и отправляет результат в чат. */
    private static void flushBuffer(MinecraftServer server, UUID playerId) {
        SpeechBuffer buf = speechBuffers.get(playerId);
        if (buf == null) return;

        float[] samples = buf.drainAndReset();
        if (samples == null || samples.length < MIN_SAMPLES) {
            LOGGER.debug("[Voice] Skipping flush for {}: too short ({} samples)",
                    playerId, samples == null ? 0 : samples.length);
            return;
        }

        LOGGER.info("[Voice] Flushing {:.1f}s for {}",
                samples.length / (float) WHISPER_SAMPLE_RATE, playerId);

        String lastContext = playerContexts.getOrDefault(playerId, "");
        if (lastContext.length() > 200) lastContext = lastContext.substring(lastContext.length() - 200);

        String text = runWhisper(samples, lastContext);
        text = applyCorrections(text);
        text = sanitizeForChat(text);

        LOGGER.info("[Voice] Whisper result for {}: '{}'", playerId, text);

        if (!text.isBlank()) {
            playerContexts.put(playerId, text);
            final String msg = text;
            server.execute(() -> dispatchMessage(server, playerId, msg));
        }
    }

    // ─── Класс буфера фразы ──────────────────────────────────────────────────

    private static final class SpeechBuffer {
        private final List<float[]> chunks = new ArrayList<>();
        private int totalSamples = 0;
        private volatile ScheduledFuture<?> pendingFlush = null;

        synchronized void addSamples(float[] s) {
            chunks.add(s);
            totalSamples += s.length;
        }

        synchronized boolean hasSpeech() { return totalSamples > 0; }
        synchronized int size()           { return totalSamples; }

        synchronized float[] drainAndReset() {
            if (totalSamples == 0) return null;
            float[] merged = new float[totalSamples];
            int pos = 0;
            for (float[] c : chunks) { System.arraycopy(c, 0, merged, pos, c.length); pos += c.length; }
            chunks.clear();
            totalSamples = 0;
            return merged;
        }

        void scheduleSilenceFlush(ScheduledExecutorService scheduler, Runnable task) {
            if (pendingFlush != null && !pendingFlush.isDone()) return;
            pendingFlush = scheduler.schedule(task, SILENCE_FLUSH_MS, TimeUnit.MILLISECONDS);
        }

        void cancelFlush() {
            ScheduledFuture<?> f = pendingFlush;
            if (f != null) { f.cancel(false); pendingFlush = null; }
        }
    }

    // ─── Декодирование + ресэмплирование ─────────────────────────────────────

    private static float[] decodeAndResample(byte[] wavData) throws Exception {
        AudioInputStream sourceAis =
                AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavData));

        AudioFormat sourceFormat = sourceAis.getFormat();
        float sourceSampleRate   = sourceFormat.getSampleRate();
        int   sourceChannels     = sourceFormat.getChannels();

        LOGGER.debug("[Voice] Source audio: {}Hz, {}ch, {}bit",
                (int) sourceSampleRate, sourceChannels, sourceFormat.getSampleSizeInBits());

        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                WHISPER_SAMPLE_RATE, 16, 1, 2, WHISPER_SAMPLE_RATE, false);

        AudioInputStream convertedAis;
        if (AudioSystem.isConversionSupported(targetFormat, sourceFormat)) {
            convertedAis = AudioSystem.getAudioInputStream(targetFormat, sourceAis);
        } else {
            AudioFormat intermediate = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceSampleRate, 16, sourceChannels,
                    sourceChannels * 2, sourceSampleRate, false);
            if (AudioSystem.isConversionSupported(intermediate, sourceFormat)) {
                AudioInputStream step1 = AudioSystem.getAudioInputStream(intermediate, sourceAis);
                convertedAis = AudioSystem.getAudioInputStream(targetFormat, step1);
            } else {
                LOGGER.warn("[Voice] Cannot convert audio format: {}", sourceFormat);
                return null;
            }
        }

        byte[] pcm;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            convertedAis.transferTo(baos);
            pcm = baos.toByteArray();
        }

        float[] samples = new float[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            short val = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            samples[i] = val / 32768.0f;
        }
        return samples;
    }

    // ─── Обработка сигнала ───────────────────────────────────────────────────

    /**
     * Pre-emphasis: y[n] = x[n] - alpha * x[n-1], alpha = 0.97
     *
     * Усиливает высокие частоты (2-8 кГц) где находятся согласные звуки русской
     * речи (с, ш, ч, т, к...). Именно из-за их плохого распознавания получается
     * "говорб" вместо "говорю", "кто" вместо "хто" и т.д.
     */
    private static void applyPreEmphasis(float[] samples, float alpha) {
        for (int i = samples.length - 1; i > 0; i--) {
            samples[i] -= alpha * samples[i - 1];
        }
        samples[0] *= (1.0f - alpha);
    }

    /** Масштабирует сигнал к пику 0.95. Критично для тихой речи. */
    private static void normalizeAmplitude(float[] samples) {
        float peak = 0f;
        for (float s : samples) { float a = Math.abs(s); if (a > peak) peak = a; }
        if (peak < 0.05f || peak >= 0.95f) return;
        float gain = 0.95f / peak;
        for (int i = 0; i < samples.length; i++) samples[i] *= gain;
    }

    private static double calculateRMSFloat(float[] samples) {
        if (samples.length == 0) return 0;
        double sum = 0;
        for (float s : samples) sum += (double) s * s;
        return Math.sqrt(sum / samples.length) * 32768.0;
    }

    // ─── Whisper inference ───────────────────────────────────────────────────

    private static String runWhisper(float[] samples, String lastContext) {
        synchronized (MODEL_LOCK) {
            if (whisperCtx == null) return "";

            WhisperFullParams params = new WhisperFullParams();
            params.language      = currentMode == VoiceMode.RU ? "ru" : "en";
            params.initialPrompt = buildPrompt(lastContext);

            if (whisper.full(whisperCtx, params, samples, samples.length) != 0) {
                LOGGER.warn("[Voice] whisper.full() returned non-zero");
                return "";
            }

            int n = whisper.fullNSegments(whisperCtx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(whisper.fullGetSegmentText(whisperCtx, i));
            return sb.toString();
        }
    }

    private static String buildPrompt(String lastContext) {
        String domain = currentMode == VoiceMode.RU
                ? "Майнкрафт, крипер, незер, эндермен, редстоун, алмаз, обсидиан, спавн, чанк, биом, телепорт, геймод, выживание, креатив, бедрок"
                : "Minecraft, creeper, nether, enderman, redstone, diamond, obsidian, spawn, chunk, biome, teleport, gamemode, survival, creative, bedrock";
        return lastContext.isEmpty() ? domain : lastContext + ". " + domain;
    }

    // ─── Авто-исправления ────────────────────────────────────────────────────

    private static String applyCorrections(String text) {
        if (text == null || text.isBlank()) return text;
        if (currentMode != VoiceMode.RU) return text;

        String result = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : CORRECTIONS_RU.entrySet()) {
            result = result.replaceAll(e.getKey(), e.getValue());
        }
        if (!result.isEmpty()) {
            result = Character.toUpperCase(result.charAt(0)) + result.substring(1);
        }
        return result;
    }

    // ─── Dispatch ────────────────────────────────────────────────────────────

    private static void dispatchMessage(MinecraftServer server, UUID playerId, String msg) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;

        Component chat = Component.translatable("chat.type.text",
                player.getDisplayName(), Component.literal(msg));
        server.getPlayerList().broadcastSystemMessage(chat, false);

        String lower = msg.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, Runnable> entry : TheGameOfLIfeMod.COMMAND_MAP.entrySet()) {
            if (lower.contains(entry.getKey())) {
                entry.getValue().run();
                break;
            }
        }

        if (lower.equals("бот") || lower.equals("bot") ||
                lower.startsWith("бот ") || lower.startsWith("bot ")) {
            String prompt = msg.replaceFirst("(?i)^(бот|bot)\\s*", "").trim();
            if (prompt.isEmpty()) prompt = "Привет";
            askAI(server, player, prompt);
        }
    }

    // ─── Загрузка модели ─────────────────────────────────────────────────────

    private static void ensureModels() throws Exception {
        synchronized (MODEL_LOCK) {
            if (whisper == null) {
                WhisperJNI.loadLibrary();
                whisper = new WhisperJNI();
            }
            if (whisperCtx == null) {
                Path modelPath = Path.of(MODEL_PATH);
                if (!Files.exists(modelPath.getParent()))
                    Files.createDirectories(modelPath.getParent());
                if (!Files.exists(modelPath)) {
                    LOGGER.info("[Voice] Model not found. Downloading ggml-medium.bin...");
                    try { downloadModel(modelPath); }
                    catch (Exception e) {
                        LOGGER.error("[Voice] Download failed", e);
                        throw new java.io.FileNotFoundException(
                                "Whisper model not found and download failed: " + modelPath.toAbsolutePath());
                    }
                }
                whisperCtx = whisper.init(modelPath);
                isReady    = true;
            }
        }
    }

    private static void downloadModel(Path target) throws java.io.IOException {
        String url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin";
        try (java.io.InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ─── AI (Ollama) ─────────────────────────────────────────────────────────

    private static void askAI(MinecraftServer server, ServerPlayer player, String prompt) {
        LOGGER.info("[Voice] Asking AI: {}", prompt);
        java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", "llama3");
                requestBody.addProperty("prompt", prompt + " (Отвечай кратко, на русском языке)");
                requestBody.addProperty("stream", false);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:11434/api/generate"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                        .build();

                HttpResponse<String> response =
                        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    String aiResponse = json.has("response")
                            ? json.get("response").getAsString() : "Thinking...";
                    LOGGER.info("[Voice] AI Response: {}", aiResponse);
                    server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                            Component.literal("§b[AI] " + aiResponse), false));
                } else {
                    server.execute(() -> player.displayClientMessage(
                            Component.literal("§c[AI] Error: Ollama not running?"), false));
                }
            } catch (Exception e) {
                LOGGER.error("[Voice] AI connection failed", e);
                server.execute(() -> player.displayClientMessage(
                        Component.literal("§c[AI] Connection failed"), false));
            }
        });
    }

    // ─── Утилиты ─────────────────────────────────────────────────────────────

    private static String sanitizeForChat(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.equalsIgnoreCase("[blank_audio]") ||
            s.equalsIgnoreCase("[music]")       ||
            s.equalsIgnoreCase("(тишина)")      ||
            s.equalsIgnoreCase("(silence)")     ||
            s.equalsIgnoreCase("(музыка)"))     return "";

        StringBuilder b = new StringBuilder(s.length());
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
}