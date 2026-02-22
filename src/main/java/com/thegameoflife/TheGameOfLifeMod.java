package com.thegameoflife;

import com.thegameoflife.WorldHacker;
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

import static com.thegameoflife.WorldHacker.banAndSnap;
import static com.thegameoflife.WorldHacker.unbanAndSnap;


public class TheGameOfLifeMod implements ModInitializer {
	public static final String MOD_ID = "thegameoflife";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static MinecraftServer SERVER;

	public static final Map<String, Runnable> COMMAND_MAP = new HashMap<>();

	// Большой блок действий на слова
	static {
		COMMAND_MAP.put("stop", () -> runCommand("tick freeze"));
		COMMAND_MAP.put("start", () -> runCommand("tick unfreeze"));
		COMMAND_MAP.put("dirt", () -> banAndSnap(Set.of(Blocks.DIRT)));
		COMMAND_MAP.put("undirt", () -> unbanAndSnap(Set.of(Blocks.DIRT)));
		COMMAND_MAP.put("stone", () -> banAndSnap(Set.of(Blocks.STONE)));
		COMMAND_MAP.put("unstone", () -> unbanAndSnap(Set.of(Blocks.STONE)));
	}


	// Текущие списки правил
	public static final Set<Block> BANNED_BLOCKS = ConcurrentHashMap.newKeySet();
	public static final Set<Block> UNBANNED_BLOCKS = ConcurrentHashMap.newKeySet(); // НОВОЕ: Буфер для возврата

	// Эпоха правил
	public static int currentRuleVersion = 0;

	// Карта версий чанков: [ChunkPos -> Версия]
	public static final ConcurrentHashMap<Long, Integer> CHUNK_VERSIONS = new ConcurrentHashMap<>();

	// НОВОЕ: Глобальный Архив Памяти
	// Формат: [Координата Чанка -> [Координата Блока -> Состояние Блока]]
	public static final ConcurrentHashMap<Long, ConcurrentHashMap<Long, BlockState>> CHUNK_MEMORY = new ConcurrentHashMap<>();

	// Очереди для плавного света
	public static final ConcurrentLinkedQueue<WorldHacker.ChunkUpdateData> LIGHT_CALC_QUEUE = new ConcurrentLinkedQueue<>();
	public static final ConcurrentLinkedQueue<WorldHacker.ChunkUpdateData> LIGHT_PACKET_QUEUE = new ConcurrentLinkedQueue<>();

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			SERVER = server; // Теперь переменная не null
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// 1. Вызываем радар (теперь это одна чистая строчка)
			WorldHacker.tickRadar(server);

			// 2. Дозатор света
			WorldHacker.processLightQueues(server);
		});

		// Времменный блок для тестов
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.signedContent();             // текст сообщения
			String playerName = sender.getName().getString();  // имя игрока

			// 🔹 Проверяем совпадения
			if (COMMAND_MAP.containsKey(text)) {
				COMMAND_MAP.get(text).run();// действие из словаря
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
}

