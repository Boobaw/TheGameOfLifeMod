package com.thegameoflife;


import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.function.mask.BlockTypeMask;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.level.ServerLevel;


import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;





public class TheGameOfLIfeMod implements ModInitializer {
	public static final String MOD_ID = "thegameoflife";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static MinecraftServer SERVER;

	public static final Map<String, Runnable> COMMAND_MAP = new HashMap<>();

	// Большой блок действий на слова
	static {
		COMMAND_MAP.put("stop", () -> runCommand("tick freeze"));
		COMMAND_MAP.put("start", () -> runCommand("tick unfreeze"));
		COMMAND_MAP.put("dirt", () -> removeAll(SERVER.overworld()));
	}




	@Override
	public void onInitialize() {

		// Получаем сервер (официальный lifecycle event)
		ServerLifecycleEvents.SERVER_STARTED.register(s -> SERVER = s);

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {

			String text = message.signedContent();             // текст сообщения
			String playerName = sender.getName().getString();  // имя игрока

			LOGGER.info("Player {} said: {}", playerName, text);

			// 🔹 Проверяем совпадения
			if (COMMAND_MAP.containsKey(text)) {
				COMMAND_MAP.get(text).run();// действие из словаря
			}
		});
	}

	private void onWorldLoaded(ServerLevel world) {
		LOGGER.info("Overworld is fully loaded.");
		runCommand("pos1 -300 -64 -300");
		runCommand("pos2 300 319 300");

		// Здесь можно выполнять команды, удалять блоки и т. д.
		// Например:
		// runCommand(server, "tick freeze");
	}


	private static void runCommand(String command) {

		CommandSourceStack source = SERVER.createCommandSourceStack();

		try {
			SERVER.getCommands()
					.getDispatcher()
					.execute(command, source);
		} catch (Exception e) {
			LOGGER.error("Failed to execute command: /{}", command, e);
		}
	}


	public static void removeAll(ServerLevel mcWorld) {
		var weWorld = FabricAdapter.adapt(mcWorld);

		var min = BlockVector3.at(-300, -64, -300);
		var max = BlockVector3.at(300, 319, 300);

		var region = new CuboidRegion(weWorld, min, max);

		try (var editSession = WorldEdit.getInstance().newEditSessionBuilder()
				.world(weWorld)
				.maxBlocks(1_000_000_000) // максимально быстро
				.build()) {

			editSession.replaceBlocks(
					region,
					new BlockTypeMask(editSession, BlockTypes.DIRT),
					BlockTypes.AIR.getDefaultState().toBaseBlock()
			);

		} catch (Exception e) {
			LOGGER.error("Failed to remove dirt blocks", e);
		}
	}

}

