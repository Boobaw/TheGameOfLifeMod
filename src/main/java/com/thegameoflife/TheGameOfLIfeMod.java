package com.thegameoflife;


import com.fastasyncworldedit.fabric.FabricTaskManager;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EditSessionBuilder;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.function.mask.BlockTypeMask;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.World;


import com.sk89q.worldedit.fabric.FabricWorld;
import com.fastasyncworldedit.fabric.FaweFabric;
import com.fastasyncworldedit.fabric.FabricQueueHandler;




import com.sk89q.worldedit.world.block.BlockTypes;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.level.ServerLevel;


import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


public class TheGameOfLIfeMod implements ModInitializer {
	public static final String MOD_ID = "thegameoflife";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static MinecraftServer SERVER;

	public static final Map<String, Runnable> COMMAND_MAP = new HashMap<>();

	// Большой блок действий на слова
	static {
		COMMAND_MAP.put("stop", () -> runCommand("tick freeze"));
		COMMAND_MAP.put("start", () -> runCommand("tick unfreeze"));
		COMMAND_MAP.put("dirt", () -> printLoadedChunks(SERVER.overworld()));
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

		try (EditSession editSession = WorldEdit.getInstance()
				.newEditSessionBuilder()
				.world(weWorld)
				.maxBlocks(-1) // без лимита
				.build()) {

			// отключаем историю
			editSession.setReorderMode(EditSession.ReorderMode.FAST);

			editSession.replaceBlocks(
					region,
					new BlockTypeMask(editSession, BlockTypes.DIRT),
					BlockTypes.AIR.getDefaultState().toBaseBlock()
			);

			editSession.flushSession(); // ОБЯЗАТЕЛЬНО для FAWE

		} catch (Exception e) {
			LOGGER.error("Failed to remove dirt blocks", e);
		}
	}

//	public void removeAllDirt(ServerLevel mcWorld) {
//
//		// Адаптируем мир под FAWE
//		World world = FabricAdapter.adapt(mcWorld);
//
//		// Получаем FAWE очередь
//		FabricQueueHandler queue = world.getQueue();
//
//		// Определяем диапазон (например, весь загруженный регион вокруг 0,0)
//		BlockVector3 min = BlockVector3.at(-300, -64, -300);
//		BlockVector3 max = BlockVector3.at(300, 319, 300);
//
//		CuboidRegion region = new CuboidRegion(world, min, max);
//
//		// Создаем очередь для региона
//		RegionQueue regionQueue = new RegionQueue(queue, region, block -> {
//			// Если блок dirt, меняем на air
//			return block.getBlockType() == BlockTypes.DIRT;
//		}, BlockTypes.AIR.getDefaultState());
//
//		// Добавляем в очередь FAWE
//		queue.add(regionQueue);
//
//		// Запускаем очередь асинхронно
//		queue.flush(); // FAWE сама распределяет по чанкам и потокам
//	}

	private static final AtomicInteger TASK_COUNTER = new AtomicInteger(0);

//	public static void removeAllDirt(ServerLevel mcWorld, FabricTaskManager taskManager) {
//		// Адаптируем мир к WorldEdit
//		World weWorld = FabricAdapter.adapt(mcWorld);
//
//		// Берём все реально загруженные чанки
//		mcWorld.getChunkSource().chunkMap.getChunks().forEach(chunkHolder -> {
//			LevelChunk chunk = chunkHolder.getTickingChunk();
//			if (chunk == null) return;
//
//			// Рассчитываем координаты чанка
//			int chunkX = chunk.getPos().x << 4;
//			int chunkZ = chunk.getPos().z << 4;
//			int minY = mcWorld.getMinBuildHeight();
//			int maxY = mcWorld.getMaxBuildHeight() - 1;
//
//			// Создаём регион чанка
//			BlockVector3 min = BlockVector3.at(chunkX, minY, chunkZ);
//			BlockVector3 max = BlockVector3.at(chunkX + 15, maxY, chunkZ + 15);
//			CuboidRegion region = new CuboidRegion(weWorld, min, max);
//
//			// Асинхронная задача на замену блоков
//			taskManager.async(() -> {
//				try (EditSession editSession = WorldEdit.getInstance()
//						.newEditSessionBuilder()
//						.world(weWorld)
//						.maxBlocks(1_000_000_000)
//						.build()) {
//
//					editSession.replaceBlocks(
//							region,
//							new BlockTypeMask(editSession, BlockTypes.DIRT),
//							BlockTypes.AIR.getDefaultState().toBaseBlock()
//					);
//
//					System.out.println("Chunk cleaned: " + chunk.getPos());
//				} catch (Exception e) {
//					e.printStackTrace();
//				} finally {
//					TASK_COUNTER.decrementAndGet();
//				}
//			});
//
//			TASK_COUNTER.incrementAndGet();
//		});
//	}
//
//	public static boolean isCleaningInProgress() {
//		return TASK_COUNTER.get() > 0;
//	}


}

