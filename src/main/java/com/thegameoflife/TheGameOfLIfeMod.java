package com.thegameoflife;


import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import net.minecraft.server.level.ServerLevel;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;


public class TheGameOfLIfeMod implements ModInitializer {
	public static final String MOD_ID = "thegameoflife";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static MinecraftServer SERVER;


	public static final Map<String, Runnable> COMMAND_MAP = new HashMap<>();

	// Большой блок действий на слова
	static {
		COMMAND_MAP.put("stop", () -> runCommand("tick freeze"));
		COMMAND_MAP.put("start", () -> runCommand("tick unfreeze"));
		COMMAND_MAP.put("dirt", () -> removeAll(SERVER.overworld(), Set.of(Blocks.STONE)));
	}


	public static final Set<LevelChunk> LOADED_CHUNKS = ConcurrentHashMap.newKeySet();

	// Две очереди: просчет теней и отправка готовых пакетов
	public static final ConcurrentLinkedQueue<ChunkUpdateData> LIGHT_CALC_QUEUE = new ConcurrentLinkedQueue<>();
	public static final ConcurrentLinkedQueue<ChunkUpdateData> LIGHT_PACKET_QUEUE = new ConcurrentLinkedQueue<>();

	public static class ChunkUpdateData {
		public LevelChunk chunk;
		public LongArrayList changedPositions = new LongArrayList();
		public int currentLightIndex = 0;

		public ChunkUpdateData(LevelChunk c) {
			this.chunk = c;
		}
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


		ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> LOADED_CHUNKS.add(chunk));
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> LOADED_CHUNKS.remove(chunk));

		ServerTickEvents.END_SERVER_TICK.register(server -> {

			// 1. ПЛАВНАЯ РАССЫЛКА СВЕТА (Строго по 2 чанка в тик!)
			// Это навсегда убьет зависание сервера на 34 секунды.
			int packetsSentThisTick = 0;
			while (!LIGHT_PACKET_QUEUE.isEmpty() && packetsSentThisTick < 2) {
				ChunkUpdateData data = LIGHT_PACKET_QUEUE.poll();

				LevelLightEngine lightEngine = data.chunk.getLevel().getLightEngine();
				ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(data.chunk, lightEngine, null, null);

				ChunkPos pos = data.chunk.getPos();
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					if (player.level() == data.chunk.getLevel() && player.distanceToSqr(pos.x * 16, player.getY(), pos.z * 16) < 16384) {
						player.connection.send(packet);
					}
				}
				packetsSentThisTick++;
			}

			// 2. КОРМЛЕНИЕ ДВИЖКА СВЕТА (20 000 блоков в тик)
			int lightBlocksProcessed = 0;
			while (!LIGHT_CALC_QUEUE.isEmpty() && lightBlocksProcessed < 20000) {
				ChunkUpdateData data = LIGHT_CALC_QUEUE.peek();
				LevelLightEngine lightEngine = data.chunk.getLevel().getLightEngine();
				BlockPos.MutableBlockPos syncPos = new BlockPos.MutableBlockPos();

				while (data.currentLightIndex < data.changedPositions.size() && lightBlocksProcessed < 20000) {
					syncPos.set(data.changedPositions.getLong(data.currentLightIndex));
					lightEngine.checkBlock(syncPos);
					data.currentLightIndex++;
					lightBlocksProcessed++;
				}

				if (data.currentLightIndex >= data.changedPositions.size()) {
					LIGHT_CALC_QUEUE.poll();
					// Как только свет просчитан, отдаем чанк в очередь на отправку пакета
					LIGHT_PACKET_QUEUE.add(data);
				}
			}
		});
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

		// 1. ФОН: Бесшумная разведка
		CompletableFuture.supplyAsync(() -> {
			List<ChunkUpdateData> processedChunks = new ArrayList<>();
			BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

			for (LevelChunk chunk : LOADED_CHUNKS) {
				if (chunk.getLevel() != level) continue;

				ChunkUpdateData data = new ChunkUpdateData(chunk);
				boolean modified = false;

				LevelChunkSection[] sections = chunk.getSections();
				int startX = chunk.getPos().getMinBlockX();
				int startZ = chunk.getPos().getMinBlockZ();

				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section == null || section.hasOnlyAir()) continue;
					if (!section.getStates().maybeHas(state -> targetBlocks.contains(state.getBlock()))) continue;

					int startY = -64 + (i * 16);

					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							for (int y = 0; y < 16; y++) {
								int realY = startY + y;
								mutablePos.set(startX + x, realY, startZ + z);

								if (targetBlocks.contains(chunk.getBlockState(mutablePos).getBlock())) {
									data.changedPositions.add(mutablePos.asLong());
									modified = true;
								}
							}
						}
					}
				}
				if (modified) {
					processedChunks.add(data);
				}
			}
			return processedChunks;

			// 2. ГЛАВНЫЙ ПОТОК: Мгновенный удар
		}).thenAcceptAsync(processedChunks -> {
			if (processedChunks.isEmpty()) return;

			LevelLightEngine lightEngine = level.getLightEngine();
			BlockPos.MutableBlockPos syncPos = new BlockPos.MutableBlockPos();
			int totalBlocksRemoved = 0;

			// ВЕСЬ этот цикл выполнится за 1 тик.
			for (ChunkUpdateData data : processedChunks) {

				// Физически удаляем все блоки чанка
				for (int i = 0; i < data.changedPositions.size(); i++) {
					syncPos.set(data.changedPositions.getLong(i));
					data.chunk.setBlockState(syncPos, air);
					totalBlocksRemoved++;
				}
				data.chunk.markUnsaved();

				ChunkPos pos = data.chunk.getPos();

				// Отправляем пакет мгновенно. Игроки видят резкое исчезновение!
				ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(data.chunk, lightEngine, null, null);
				for (ServerPlayer player : level.players()) {
					if (player.distanceToSqr(pos.x * 16, player.getY(), pos.z * 16) < 16384) {
						player.connection.send(packet);
					}
				}

				// Передаем чанк в дозатор, чтобы он медленно исправил тени
				LIGHT_CALC_QUEUE.add(data);
			}

			System.out.println("ЩЕЛЧОК ТАНОСА! Убрано " + totalBlocksRemoved + " блоков за " + (System.currentTimeMillis() - startTime) + " мс.");

		}, level.getServer());
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

