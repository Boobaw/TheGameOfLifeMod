package com.thegameoflife;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.File;

public class TheGameOfLIfeClient implements ClientModInitializer {

	private static final AudioRecorderMod recorder = new AudioRecorderMod();
	private static ChatLogger logger;

	private static KeyMapping keyMic;
	private static KeyMapping keyRec;

	private static volatile boolean running = false;
	private static Thread sttThread;

	private static long nextHud = 0;

	@Override
	public void onInitializeClient() {
		logger = new ChatLogger(new File("thegameoflife/chat.log"));
		recorder.setLogger(logger);
		VoiceNetworking.registerPayloads();

		ClientPlayNetworking.registerGlobalReceiver(VoiceResultPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				if (context.client().player != null) {
					context.client().player.displayClientMessage(
							Component.literal(payload.message()), false);
				}
			});
		});

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

					if (running) {
						client.player.displayClientMessage(
								Component.literal("Mic: " + recorder.getSelectedMixerName()), false);
					}
				}

				if (running) startVoiceLoop();
			}

			showMicHud();
		});

		logger.log("[Client] init done");
	}

	private static void showMicHud() {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (now < nextHud) return;
		nextHud = now + 1000;

		if (mc.player != null) {
			mc.player.displayClientMessage(
					Component.literal("Mic: " + recorder.getSelectedMixerName()
							+ " | Voice: " + (running ? "ON" : "OFF")),
					true
			);
		}
	}

	private static void startVoiceLoop() {
		if (sttThread != null && sttThread.isAlive()) return;

		if (!ClientPlayNetworking.canSend(VoiceChunkPayload.TYPE)) {
			running = false;
			postChat("[VOICE][ERROR] server has no voice support");
			return;
		}

		sttThread = new Thread(() -> {
			logger.log("[Client] voice loop started (send to server)");

			while (running) {
				byte[] data = recorder.record5sBytes();

				if (data == null) {
					postChat("[VOICE][ERROR] record failed");
					try { Thread.sleep(1000); } catch (Exception ignored) {}
					continue;
				}

				if (data.length > VoiceChunkPayload.MAX_BYTES) {
					postChat("[VOICE][ERROR] chunk too large");
					logger.log("[Client] chunk too large: " + data.length);
					continue;
				}

				try {
					ClientPlayNetworking.send(new VoiceChunkPayload(data));
				} catch (Exception e) {
					postChat("[VOICE][ERROR] " + e.getClass().getSimpleName());
					logger.log("[Client] voice loop error: " + e);
				}
			}

			logger.log("[Client] voice loop stopped");
		}, "Voice-Loop");

		sttThread.start();
	}

	private static void postChat(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null)
				mc.player.displayClientMessage(Component.literal(msg), false);
		});
	}
}
