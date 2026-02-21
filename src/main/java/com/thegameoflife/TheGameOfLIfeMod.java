package com.thegameoflife;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.level.chunk.LevelChunkSection;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Text;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;



public class TheGameOfLIfeMod implements ModInitializer {
	public static final String MOD_ID = "thegameoflife";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static MinecraftServer SERVER;

	public static final Set<LevelChunk> LOADED_CHUNKS = ConcurrentHashMap.newKeySet();

	public static final Map<String, Runnable> COMMAND_MAP = new HashMap<>();

	// Большой блок действий на слова
	static {
		COMMAND_MAP.put("stop", () -> runCommand("tick freeze"));
		COMMAND_MAP.put("start", () -> runCommand("tick unfreeze"));
		COMMAND_MAP.put("dirt", () -> removeAll(SERVER.overworld(), Set.of(Blocks.STONE)));
	}




	@Override
	public void onInitialize() {


		// Подписываемся на загрузку чанка
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			LOADED_CHUNKS.add(chunk);
		});

		// Подписываемся на выгрузку чанка
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			LOADED_CHUNKS.remove(chunk);
		});

		System.out.println("Мой мод успешно запущен и начал следить за чанками!");

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

	public static void removeAll(ServerLevel level, Set<Block> targetBlocks) {
		long startTime = System.currentTimeMillis();
		BlockState air = Blocks.AIR.defaultBlockState();

		// 1. Уходим в фон. Основной сервер летит без лагов!
		CompletableFuture.runAsync(() -> {
			int blocksRemoved = 0;
			List<LevelChunk> modifiedChunks = new ArrayList<>();

			LevelLightEngine lightEngine = level.getLightEngine();
			BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

			for (LevelChunk chunk : LOADED_CHUNKS) {
				if (chunk.getLevel() != level) continue;

				boolean chunkModified = false;
				LevelChunkSection[] sections = chunk.getSections();
				int startX = chunk.getPos().getMinBlockX();
				int startZ = chunk.getPos().getMinBlockZ();

				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section == null || section.hasOnlyAir()) continue;

					// Наш мощный фильтр палетки
					if (!section.getStates().maybeHas(state -> targetBlocks.contains(state.getBlock()))) continue;

					int startY = -64 + (i * 16);

					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							for (int y = 0; y < 16; y++) {

								int realY = startY + y;
								mutablePos.set(startX + x, realY, startZ + z);

								if (targetBlocks.contains(chunk.getBlockState(mutablePos).getBlock())) {

									// МЕНЯЕМ БЛОК В ФОНЕ (Максимальная скорость)
									chunk.setBlockState(mutablePos, air);

									// ОБНОВЛЯЕМ СВЕТ В ФОНЕ (Сразу же, внутри цикла)
									lightEngine.checkBlock(mutablePos);

									blocksRemoved++;
									chunkModified = true;
								}
							}
						}
					}
				}

				if (chunkModified) {
					modifiedChunks.add(chunk);
				}
			}

			final int finalRemoved = blocksRemoved;

			// 2. Возвращаемся в Главный поток ТОЛЬКО чтобы отправить пакеты
			level.getServer().execute(() -> {
				for (LevelChunk chunk : modifiedChunks) {
					// Сохраняем чанк на диск
					chunk.markUnsaved();

					ChunkPos pos = chunk.getPos();
					ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null);

					// Рассылаем игрокам поблизости
					for (ServerPlayer player : level.players()) {
						if (player.distanceToSqr(pos.x * 16, player.getY(), pos.z * 16) < 16384) {
							player.connection.send(packet);
						}
					}
				}
				System.out.println("Молниеносная очистка: Удалено " + finalRemoved + " блоков за " + (System.currentTimeMillis() - startTime) + " мс.");
			});

		}, Util.backgroundExecutor());
	}


	public static void removeAll1(ServerLevel level, Block targetBlock) {
		long startTime = System.currentTimeMillis();
		BlockState replacementState = Blocks.AIR.defaultBlockState();

		// 1. УХОДИМ В ФОНОВЫЙ ПОТОК (Async)
		// Сервер продолжит работать без лагов, пока мы перебираем блоки
		CompletableFuture.runAsync(() -> {
			int blocksReplaced = 0;
			List<LevelChunk> modifiedChunks = new ArrayList<>();

			LevelLightEngine lightEngine = level.getLightEngine();
			BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

			for (LevelChunk chunk : LOADED_CHUNKS) {
				if (chunk.getLevel() != level) continue;

				boolean chunkModified = false;

				// МАГИЯ СВЕТА: Массивы для хранения самой высокой и самой низкой точки в столбце.
				// В чанке 16x16 = 256 вертикальных столбцов.
				int[] highestY = new int[256];
				int[] lowestY = new int[256];
				for (int j = 0; j < 256; j++) {
					highestY[j] = Integer.MIN_VALUE;
					lowestY[j] = Integer.MAX_VALUE;
				}

				LevelChunkSection[] sections = chunk.getSections();
				int startX = chunk.getPos().getMinBlockX();
				int startZ = chunk.getPos().getMinBlockZ();

				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];

					if (section == null || section.hasOnlyAir()) continue;
					if (!section.getStates().maybeHas(state -> state.is(targetBlock))) continue;

					int startY = -64 + (i * 16);

					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							for (int y = 0; y < 16; y++) {

								int realY = startY + y;
								mutablePos.set(startX + x, realY, startZ + z);

								if (chunk.getBlockState(mutablePos).is(targetBlock)) {

									// Меняем блок в памяти чанка
									chunk.setBlockState(mutablePos, replacementState);

									// ВЫНОСИМ СВЕТ: Запоминаем только самую высокую и низкую точку
									int index = x + z * 16;
									if (realY > highestY[index]) highestY[index] = realY;
									if (realY < lowestY[index]) lowestY[index] = realY;

									blocksReplaced++;
									chunkModified = true;
								}
							}
						}
					}
				}

				// ПОСЛЕ ПРОХОЖДЕНИЯ ВСЕХ СЕКЦИЙ ЧАНКА:
				if (chunkModified) {
					// Перебираем наши 256 столбцов и пингуем свет только по краям
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							int index = x + z * 16;
							if (highestY[index] != Integer.MIN_VALUE) {

								// Запрос света для "крыши" изменения
								mutablePos.set(startX + x, highestY[index], startZ + z);
								lightEngine.checkBlock(mutablePos);

								// Запрос света для "пола" (чтобы тени внизу пересчитались)
								if (lowestY[index] != highestY[index]) {
									mutablePos.set(startX + x, lowestY[index], startZ + z);
									lightEngine.checkBlock(mutablePos);
								}
							}
						}
					}
					modifiedChunks.add(chunk);
				}
			}

			final int finalReplaced = blocksReplaced;

			// 2. ВОЗВРАЩАЕМСЯ В ОСНОВНОЙ ПОТОК (Sync)
			// Сеть и сохранение файлов можно делать только в главном потоке!
			level.getServer().execute(() -> {

				for (LevelChunk chunk : modifiedChunks) {
					chunk.markUnsaved();
					ChunkPos pos = chunk.getPos();
					ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null);

					for (ServerPlayer player : level.players()) {
						if (player.distanceToSqr(pos.x * 16, player.getY(), pos.z * 16) < 16384) {
							player.connection.send(packet);
						}
					}
				}

				System.out.println("УМНАЯ Асинхронная замена завершена! Заменено: " + finalReplaced + " блоков за " + (System.currentTimeMillis() - startTime) + " мс.");
			});

		}, Util.backgroundExecutor());
	}



//	public static void removeAll(ServerLevel mcWorld) {
//
//		var weWorld = FabricAdapter.adapt(mcWorld);
//
//		var min = BlockVector3.at(-300, -64, -300);
//		var max = BlockVector3.at(300, 319, 300);
//
//		var region = new CuboidRegion(weWorld, min, max);
//
//		try (EditSession editSession = WorldEdit.getInstance()
//				.newEditSessionBuilder()
//				.world(weWorld)
//				.maxBlocks(-1) // без лимита
//				.build()) {
//
//			// отключаем историю
//			editSession.setReorderMode(EditSession.ReorderMode.FAST);
//
//			editSession.replaceBlocks(
//					region,
//					new BlockTypeMask(editSession, BlockTypes.DIRT),
//					BlockTypes.AIR.getDefaultState().toBaseBlock()
//			);
//
//			editSession.flushSession(); // ОБЯЗАТЕЛЬНО для FAWE
//
//		} catch (Exception e) {
//			LOGGER.error("Failed to remove dirt blocks", e);
//		}
//	}

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

//	public static void removeAllDirt(ServerLevel mcWorld) {
//		// Получаем FAWE адаптер для мира
//		var weWorld = com.fastasyncworldedit.fabric.FabricAdapter.adapt(mcWorld);
//
//		// Получаем TaskManager
//		TaskManager taskManager = FabricWorldEdit.inst.getTaskManager();
//
//		// Получаем QueueHandler
//		QueueHandler queue = FabricWorldEdit.inst.getQueueHandler();
//
//		// Асинхронно ставим задачу
//		taskManager.async(() -> {
//			for (LevelChunk chunk : mcWorld.getChunkSource().chunkMap.getChunks()) {
//				// Определяем регион для чанка
//				BlockVector3 min = BlockVector3.at(chunk.getPos().getMinBlockX(),
//						mcWorld.getMinBuildHeight(),
//						chunk.getPos().getMinBlockZ());
//				BlockVector3 max = BlockVector3.at(chunk.getPos().getMaxBlockX(),
//						mcWorld.getMaxBuildHeight() - 1,
//						chunk.getPos().getMaxBlockZ());
//
//				var region = new com.fastasyncworldedit.core.regions.FaweCuboidRegion(weWorld, min, max);
//
//				// Ставим задачу на замену блоков через очередь FAWE
//				queue.add(region,
//						new com.fastasyncworldedit.core.function.mask.BlockTypeMask(region, BlockTypes.DIRT),
//						BlockTypes.AIR.getDefaultState().toBaseBlock());
//			}
//
//			// После добавления всех чанков — выполняем очередь
//			queue.flush();
//		});
}

