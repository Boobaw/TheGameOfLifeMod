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

public class TheGameOfLIfeClient implements ClientModInitializer {

	private static final AudioRecorderMod recorder = new AudioRecorderMod();
	private static ChatLogger logger;

	private static KeyMapping keyMic;
	private static KeyMapping keyRec;

	private static volatile boolean running = false;
	private static Thread sttThread;

	@Override
	public void onInitializeClient() {
		logger = new ChatLogger(new File("thegameoflife/chat.log"));
		recorder.setLogger(logger);

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
			try (Model model = new Model("thegameoflife/vosk-model/vosk-model-small-en-us-0.15")) {

				logger.log("[Client] STT started");

				while (running) {
					File wav = recorder.record5s(new File("thegameoflife/chunks"),
							"chunk_" + System.currentTimeMillis());

					if (wav == null) {
						postChat("[VOICE][ERROR] record failed");
						try { Thread.sleep(1000); } catch (Exception ignored) {}
						continue;
					}


					try (AudioInputStream ais = AudioSystem.getAudioInputStream(wav)) {
						float sr = ais.getFormat().getSampleRate();

						try (Recognizer rec = new Recognizer(model, sr)) {
							byte[] buf = new byte[4096];
							int n;
							while ((n = ais.read(buf)) >= 0) {
								rec.acceptWaveForm(buf, n);
							}

							String result = rec.getFinalResult();
							String text = result.replaceAll("(?s).*\"text\"\\s*:\\s*\"(.*?)\".*", "$1").trim();

							if (text.isBlank()) {
								postChat("[VOICE][ERROR] empty");
								logger.log("[Client] STT empty");
							} else {
								postChat("[VOICE] " + text);
								logger.log("[Client] STT: " + text);
							}
						}

					} catch (Exception e) {
						postChat("[VOICE][ERROR] " + e.getClass().getSimpleName());
						logger.log("[Client] STT error: " + e);
					}
				}

				logger.log("[Client] STT stopped");

			} catch (Exception e) {
				logger.log("[Client] STT fatal: " + e);
				postChat("[VOICE][ERROR] STT fatal");
			}
		}, "STT-Loop");

		sttThread.start();
	}

	private static void postChat(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
		});
	}

}
