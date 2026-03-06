package com.cyberspace;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.cyberspace.utils.CommandRouter;
import org.slf4j.Logger;
import org.vosk.Model;
import org.vosk.Recognizer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import static com.cyberspace.CyberSpaceMod.SERVER;

public final class VoiceServer {
    private static final Logger LOGGER = CyberSpaceMod.LOGGER;

    private static final String MODEL_EN =
            "thegameoflife/vosk-model/vosk-model-en-us-0.22";
    private static final String MODEL_RU =
            "thegameoflife/vosk-model/vosk-model-ru-0.42";

    private static final Gson GSON = new Gson();

    private static final Path KEYWORDS_FILE = Path.of("thegameoflife/keywords.txt");

    private static final Object MODEL_LOCK = new Object();
    private static Model modelEn;
    private static Model modelRu;

    private static ExecutorService executor;
    private static volatile List<String> keywords = new ArrayList<>();

    private static boolean receiverRegistered = false;

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    public enum VoiceMode {
        AUTO, EN, RU
    }

    private static volatile VoiceMode currentMode = VoiceMode.AUTO;

    public static void setMode(VoiceMode mode) {
        currentMode = mode;
        LOGGER.info("[Voice] Mode changed to: {}", mode);
    }

    private VoiceServer() {
    }

    public static void onServerStarted(MinecraftServer server) {
        LOGGER.info("[Voice] Server started, starting executor...");
        ensureExecutor();
        loadKeywords();

        // Запускаем загрузку моделей сразу при старте сервера в фоновом режиме
        executor.execute(() -> {
            try {
                LOGGER.info("[Voice] Preloading models...");
                ensureModels(); // Это тяжелая операция, она займет время
                LOGGER.info("[Voice] Models loaded successfully.");
                server.execute(() -> {
                    server.getPlayerList().broadcastSystemMessage(Component.literal("§a[Voice] Голосовой чат готов к работе!"), false);
                });
            } catch (Exception e) {
                LOGGER.error("[Voice] Failed to preload models", e);
            }
        });
    }

    public static void registerNetworking() {
        if (!receiverRegistered) {
            receiverRegistered = true;
            ServerPlayNetworking.registerGlobalReceiver(VoiceChunkPayload.TYPE, (payload, context) -> {
                byte[] data = payload.data();
                if (data.length == 0) return;
                if (data.length > VoiceChunkPayload.MAX_BYTES) {
                    LOGGER.warn("[Voice] payload too large: {}", data.length);
                    return;
                }

                // Раскомментируем для отладки
                // LOGGER.info("[Voice] Packet received from {}, size={}", context.player().getScoreboardName(), data.length);
                
                UUID playerId = context.player().getUUID();
                MinecraftServer server = context.server();
                
                if (executor == null || executor.isShutdown()) return;
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
        // Используем ограниченную очередь (размер 2). Если очередь полная, удаляем САМЫЙ СТАРЫЙ пакет.
        // Это гарантирует, что сервер всегда обрабатывает свежий голос, а не то, что было 3 минуты назад.
        executor = new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                tf,
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    private static void handleChunk(MinecraftServer server, UUID playerId, byte[] wavData) {
        try {
            ensureModels();
        } catch (Exception e) {
            LOGGER.error("[Voice] failed to load models", e);
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    player.displayClientMessage(Component.literal("§c[Voice] Ошибка: Модели не найдены или повреждены!"), false);
                }
            });
            return;
        }

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavData))) {
            float sr = ais.getFormat().getSampleRate();
            byte[] data = ais.readAllBytes();
            
            // --- ДИАГНОСТИКА ГРОМКОСТИ ---
            double rms = calculateRMS(data);
            LOGGER.info("[Voice] Chunk from {}: size={} bytes, RMS={:.2f}", playerId, data.length, rms);

            // ОПТИМИЗАЦИЯ: Если тишина, вообще не запускаем нейросеть
            if (rms < 100) {
                return;
            }
            // -----------------------------

            RecognitionResult resEn = new RecognitionResult("", 0);
            RecognitionResult resRu = new RecognitionResult("", 0);

            // Запускаем только нужные модели в зависимости от режима
            if (currentMode == VoiceMode.AUTO || currentMode == VoiceMode.EN) {
                resEn = recognize(modelEn, sr, data);
            }
            if (currentMode == VoiceMode.AUTO || currentMode == VoiceMode.RU) {
                resRu = recognize(modelRu, sr, data);
            }

            String en = sanitizeForChat(fixMojibake(resEn.text()));
            String ru = sanitizeForChat(fixMojibake(resRu.text()));

            LOGGER.info("[Voice] Recognition -> EN: '{}' ({:.2f}), RU: '{}' ({:.2f})", en, resEn.confidence(), ru, resRu.confidence());

            String text;
            if (!en.isEmpty() && !ru.isEmpty()) {
                // Выбираем тот вариант, где нейросеть больше уверена (confidence)
                text = (resEn.confidence() > resRu.confidence()) ? en : ru;
            } else if (!en.isEmpty()) {
                text = en;
            } else if (!ru.isEmpty()) {
                text = ru;
            } else {
                text = "";
            }

            if (!text.isBlank()) {
                final String msg = text;
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null) {
                        Component chat = Component.translatable("chat.type.text", player.getDisplayName(), Component.literal(msg));
                        server.getPlayerList().broadcastSystemMessage(chat, false);

                        // Проверяем команды вручную, так как системные сообщения не триггерят событие чата
                        String lower = msg.toLowerCase(Locale.ROOT);
                        for (Map.Entry<String, Consumer<ServerPlayer>> entry : CyberSpaceMod.COMMAND_MAP.entrySet()) {
                            String[] commandChunks = text.split(",");
                            for (String rawChunk : commandChunks) {
                                String chunk = rawChunk.trim().toLowerCase();
                                if (chunk.isEmpty()) continue;

                                // Отправляем кусок на анализ и исполнение
                                CommandRouter.processChunk(chunk, player, SERVER);
                            }
                        }

                        // Интеграция с ИИ (если фраза начинается с "бот " или "bot ")
                        // Исправлено: теперь реагирует на "бот", "бот..." и "bot..." корректно
                        if (lower.equals("бот") || lower.equals("bot") || lower.startsWith("бот ") || lower.startsWith("bot ")) {
                            String prompt = msg.replaceFirst("(?i)^(бот|bot)\\s*", "").trim();
                            if (prompt.isEmpty()) {
                                // Если игрок сказал просто "Бот", считаем это приветствием
                                prompt = "Привет";
                            }
                            askAI(server, player, prompt);
                        }
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.error("[Voice] STT error", e);
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

    private static RecognitionResult recognize(Model model, float sampleRate, byte[] data) {
        try (Recognizer rec = new Recognizer(model, sampleRate)) {
            rec.acceptWaveForm(data, data.length);
            String jsonStr = rec.getFinalResult();

            JsonObject json = GSON.fromJson(jsonStr, JsonObject.class);
            String text = json.has("text") ? json.get("text").getAsString() : "";
            double conf = 0.0;

            if (json.has("result")) {
                JsonArray arr = json.getAsJsonArray("result");
                if (arr.size() > 0) {
                    double sum = 0;
                    for (int i = 0; i < arr.size(); i++) {
                        sum += arr.get(i).getAsJsonObject().get("conf").getAsDouble();
                    }
                    conf = sum / arr.size();
                }
            }
            return new RecognitionResult(text, conf);
        } catch (Exception e) {
            LOGGER.error("[Voice] recognize error", e);
            return new RecognitionResult("", 0);
        }
    }

    // Метод отправки запроса в локальную нейросеть (Ollama)
    private static void askAI(MinecraftServer server, ServerPlayer player, String prompt) {
        LOGGER.info("[Voice] Asking AI: {}", prompt);
        // ВАЖНО: Запускаем в отдельном потоке (commonPool), чтобы не блокировать обработку голоса
        java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
            try {
                // Формируем JSON для Ollama (модель llama3 или mistral, можно поменять)
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", "llama3"); // Убедитесь, что модель скачана в Ollama
                requestBody.addProperty("prompt", prompt + " (Отвечай кратко, на русском языке)");
                requestBody.addProperty("stream", false);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:11434/api/generate"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10)) // Таймаут 10 секунд, чтобы не зависало
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    String aiResponse = json.has("response") ? json.get("response").getAsString() : "Thinking...";
                    LOGGER.info("[Voice] AI Response: {}", aiResponse);

                    server.execute(() -> {
                        Component chat = Component.literal("§b[AI] " + aiResponse);
                        server.getPlayerList().broadcastSystemMessage(chat, false);
                    });
                } else {
                    server.execute(() -> {
                        player.displayClientMessage(Component.literal("§c[AI] Error: Ollama not running?"), false);
                    });
                }
            } catch (Exception e) {
                server.execute(() -> {
                    player.displayClientMessage(Component.literal("§c[AI] Connection failed"), false);
                });
            }
        });
    }

    private static double calculateRMS(byte[] pcmData) {
        long sum = 0;
        for (int i = 0; i < pcmData.length - 1; i += 2) {
            short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
            sum += sample * sample;
        }
        int samples = pcmData.length / 2;
        return samples == 0 ? 0 : Math.sqrt((double) sum / samples);
    }

    private record RecognitionResult(String text, double confidence) {}

    private static String fixMojibake(String s) {
        if (s == null) return "";
        // Если строка содержит Ð (U+00D0) или Ñ (U+00D1), это признак того, что UTF-8 байты были прочитаны в системной кодировке
        if (s.contains("\u00D0") || s.contains("\u00D1")) {
            try {
                // Явно используем Windows-1252, так как именно она обычно создает такие символы из UTF-8 байт
                return new String(s.getBytes("Windows-1252"), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return s;
            }
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
}
