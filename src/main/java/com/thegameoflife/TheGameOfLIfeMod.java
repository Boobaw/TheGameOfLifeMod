package com.thegameoflife;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import static net.minecraft.commands.Commands.literal;


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
		COMMAND_MAP.put("стоп", () -> runCommand("tick freeze"));
		COMMAND_MAP.put("старт", () -> runCommand("tick unfreeze"));
		
		COMMAND_MAP.put("режим русский", () -> VoiceServer.setMode(VoiceServer.VoiceMode.RU));
		COMMAND_MAP.put("режим английский", () -> VoiceServer.setMode(VoiceServer.VoiceMode.EN));
		//COMMAND_MAP.put("dirt", () -> printLoadedChunks(SERVER.overworld()));
	}




	@Override
	public void onInitialize() {
		VoiceNetworking.registerPayloads();
		VoiceServer.registerNetworking(); // Регистрируем прием пакетов СРАЗУ

		// Регистрация команды /voice status
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("voice")
				.then(literal("status").executes(context -> {
					context.getSource().sendSuccess(() -> VoiceServer.getStatus(), false);
					return 1;
				}))
			);
		});

		// Получаем сервер (официальный lifecycle event)
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			SERVER = s;
			VoiceServer.onServerStarted(s); // Запускаем потоки обработки
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> VoiceServer.shutdown());

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {

			String text = message.signedContent();             // текст сообщения
			String playerName = sender.getName().getString();  // имя игрока

			LOGGER.info("Player {} said: {}", playerName, text);

			// 🔹 Проверяем совпадения
			String lower = text.toLowerCase(Locale.ROOT);
			for (Map.Entry<String, Runnable> entry : COMMAND_MAP.entrySet()) {
				if (lower.contains(entry.getKey())) {
					entry.getValue().run();
					break; // Выполняем первую найденную команду и выходим
				}
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

	public static void removeAll(ServerLevel level) {
		// Засекаем время, чтобы узнать, насколько быстро сработал наш код
		long startTime = System.currentTimeMillis();
		int blocksRemoved = 0;

		// Кешируем нужные состояния, чтобы не доставать их из памяти миллион раз
		BlockState air = Blocks.AIR.defaultBlockState();

		// СЕКРЕТ №1: Создаем ОДИН изменяемый объект координат
		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

		// Проходим по нашему списку загруженных чанков
		for (LevelChunk chunk : LOADED_CHUNKS) {

			// ВАЖНАЯ ПРОВЕРКА: Наш список хранит чанки из ВСЕХ измерений (Обычный мир, Ад, Энд).
			// Нам нужно убедиться, что чанк принадлежит тому миру, который мы сейчас обрабатываем.
			if (chunk.getLevel() != level) {
				continue;
			}

			// Получаем реальные координаты начала чанка в мире
			int startX = chunk.getPos().getMinBlockX();
			int startZ = chunk.getPos().getMinBlockZ();

			// Получаем высоту мира (например, от -64 до 320)
			int minY = -64;
			int maxY = 319;

			// Проходим по всем координатам внутри этого чанка (16x16 в ширину, и вся высота мира)
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					for (int y = minY; y < maxY; y++) {

						// Обновляем нашу единственную координату (без создания новых объектов!)
						mutablePos.set(startX + x, y, startZ + z);

						// СЕКРЕТ №2: Мы обращаемся к chunk.getBlockState, а не к level.getBlockState.
						// Это в разы быстрее, так как игра не тратит время на поиск чанка.
						if (chunk.getBlockState(mutablePos).is(Blocks.STONE)) {

							// СЕКРЕТ №3: Магические флаги оптимизации Minecraft.
							// 2 = отправить обновление игрокам (чтобы они не видели блоков-призраков).
							// 16 = не обновлять форму соседних блоков (запрещает перерисовку заборов и т.д.).
							// 32 = запретить выпадение предмета (чтобы сервер не заспавнил миллион блоков земли на полу).
							int flags = 2 | 16 | 32;

							// Заменяем блок
							level.setBlock(mutablePos, air, flags);
							blocksRemoved++;
						}
					}
				}
			}
		}

		// Выводим результат в консоль сервера
		System.out.println("Готово! Удалено " + blocksRemoved + " блоков земли за " + (System.currentTimeMillis() - startTime) + " мс.");
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
