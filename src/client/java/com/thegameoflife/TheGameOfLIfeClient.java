package com.thegameoflife;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class TheGameOfLIfeClient implements ClientModInitializer {

	private static final AudioRecorderMod recorder = new AudioRecorderMod();
	private static ChatLogger logger;

	private static KeyMapping keyMic;
	private static KeyMapping keyRec;

	private static volatile boolean running = false;
	private static Thread sttThread;

	private static String lastEnKeys = "";
	private static String lastRuKeys = "";

	private static final String MODEL_EN =
			"thegameoflife/vosk-model/vosk-model-small-en-us-0.15";
	private static final String MODEL_RU =
			"thegameoflife/vosk-model/vosk-model-small-ru-0.22";

	private static final Path KEYWORDS_FILE = Path.of("thegameoflife/keywords.txt");
	private static List<String> keywords = new ArrayList<>();

	@Override
	public void onInitializeClient() {
		logger = new ChatLogger(new File("thegameoflife/chat.log"));
		recorder.setLogger(logger);

		loadKeywords();

		keyMic = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.thegameoflife.mic_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_M,
				KeyMapping.Category.MISC
		));

		keyRec = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.thegameoflife.voice_record",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_R,
				KeyMapping.Category.MISC
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (keyMic.consumeClick()) {
				client.setScreen(new MicrophoneSelectionScreen(client.screen, recorder));
			}

			while (keyRec.consumeClick()) {
				running = !running;

				if (client.player != null) {
					client.player.displayClientMessage(
							Component.literal("Voice: " + (running ? "ON" : "OFF")), false);
				}

				if (running) startVoiceLoop();
			}
		});

		logger.log("[Client] init done");
	}

	private static void startVoiceLoop() {
		if (sttThread != null && sttThread.isAlive()) return;

		sttThread = new Thread(() -> {
			try (Model modelEn = new Model(MODEL_EN);
				 Model modelRu = new Model(MODEL_RU)) {

				logger.log("[Client] STT started (EN+RU) keywords-only");

				while (running) {
					File wav = recorder.record5s(
							new File("thegameoflife/chunks"),
							"chunk_" + System.currentTimeMillis());

					if (wav == null) {
						postChat("[KEY][ERROR] record failed");
						try { Thread.sleep(1000); } catch (Exception ignored) {}
						continue;
					}

					try (AudioInputStream ais = AudioSystem.getAudioInputStream(wav)) {
						float sr = ais.getFormat().getSampleRate();
						byte[] data = ais.readAllBytes();

						String en = recognize(modelEn, sr, data);
						String ru = recognize(modelRu, sr, data);

						en = sanitizeForChat(fixMojibake(en));
						ru = sanitizeForChat(fixMojibake(ru));

						String enKeys = extractKeywords(en);
						String ruKeys = extractKeywords(ru);

						boolean posted = false;

						if (!enKeys.isBlank() && !enKeys.equalsIgnoreCase(lastEnKeys)) {
							postChat("[KEY][EN] " + enKeys);
							lastEnKeys = enKeys;
							logger.log("[Client] EN keys: " + enKeys);
							posted = true;
						}

						if (!ruKeys.isBlank() && !ruKeys.equalsIgnoreCase(lastRuKeys)) {
							postChat("[KEY][RU] " + ruKeys);
							lastRuKeys = ruKeys;
							logger.log("[Client] RU keys: " + ruKeys);
							posted = true;
						}

						if (!posted) {
							postChat("[KEY][EMPTY]");
							logger.log("[Client] keys empty");
						}

					} catch (Exception e) {
						postChat("[KEY][ERROR] " + e.getClass().getSimpleName());
						logger.log("[Client] STT error: " + e);
					}
				}

				logger.log("[Client] STT stopped");

			} catch (Exception e) {
				logger.log("[Client] STT fatal: " + e);
				postChat("[KEY][ERROR] STT fatal");
			}
		}, "STT-Loop");

		sttThread.start();
	}

	private static String recognize(Model model, float sampleRate, byte[] data) throws Exception {
		try (Recognizer rec = new Recognizer(model, sampleRate)) {
			rec.acceptWaveForm(data, data.length);
			String result = rec.getFinalResult();
			return result.replaceAll("(?s).*\"text\"\\s*:\\s*\"(.*?)\".*", "$1").trim();
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
			logger.log("[Client] keywords loaded: " + keywords.size());
		} catch (Exception e) {
			logger.log("[Client] keywords load error: " + e);
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

	private static String fixMojibake(String s) {
		if (s == null) return "";
		if (s.contains("Ð") || s.contains("Ñ")) {
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

	private static void postChat(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null)
				mc.player.displayClientMessage(Component.literal(msg), false);
		});
	}
}
