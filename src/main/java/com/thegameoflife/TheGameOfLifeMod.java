package com.thegameoflife;


import com.thegameoflife.WorldHacker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
import java.util.function.Consumer;

import static com.thegameoflife.CyberSpaceConfig.loadConfig;
import static com.thegameoflife.WorldHacker.*;


public class TheGameOfLifeMod implements ModInitializer {
    public static final String MOD_ID = "thegameoflife";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static MinecraftServer SERVER;


    // 1. Полное уничтожение блока (Блок -> Воздух)
    public static final Set<Block> BANNED_BLOCKS = ConcurrentHashMap.newKeySet();
    public static final Set<Block> UNBANNED_BLOCKS = ConcurrentHashMap.newKeySet();

    // Списки для ПОЛНОГО УНИЧТОЖЕНИЯ (превращения в Воздух)
    public static final Set<WorldHacker.StateFilter> BANNED_BREAK_FILTERS = ConcurrentHashMap.newKeySet();
    public static final Set<WorldHacker.StateFilter> UNBANNED_BREAK_FILTERS = ConcurrentHashMap.newKeySet();

    // Списки для ОБНУЛЕНИЯ (превращения в дефолтный сухой/потушенный блок)
    public static final Set<WorldHacker.StateFilter> BANNED_RESET_FILTERS = ConcurrentHashMap.newKeySet();
    public static final Set<WorldHacker.StateFilter> UNBANNED_RESET_FILTERS = ConcurrentHashMap.newKeySet();

    public static final Set<EntityType<?>> BANNED_ENTITIES = ConcurrentHashMap.newKeySet();
    public static final Set<EntityType<?>> UNBANNED_ENTITIES = ConcurrentHashMap.newKeySet();

    public static final Set<net.minecraft.world.item.Item> BANNED_ITEMS = ConcurrentHashMap.newKeySet();
    public static final Set<net.minecraft.world.item.Item> UNBANNED_ITEMS = ConcurrentHashMap.newKeySet();

    // Универсальный список активных правил (хранит строковые ID, например "rule_food", "rule_sharpness")
    public static final java.util.Set<String> BANNED_RULES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Эпоха и Память чанков остаются без изменений
    public static int currentRuleVersion = 0;
    public static final ConcurrentHashMap<Long, Integer> CHUNK_VERSIONS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Long, ConcurrentHashMap<Long, BlockState>> CHUNK_MEMORY = new ConcurrentHashMap<>();

    // Очереди для плавного света
    public static final ConcurrentLinkedQueue<WorldHacker.ChunkUpdateData> LIGHT_CALC_QUEUE = new ConcurrentLinkedQueue<>();
    public static final ConcurrentLinkedQueue<WorldHacker.ChunkUpdateData> LIGHT_PACKET_QUEUE = new ConcurrentLinkedQueue<>();


    public static final Map<String, Consumer<ServerPlayer>> COMMAND_MAP = new HashMap<>();

    @Override
    public void onInitialize() {
        VoiceNetworking.registerPayloads();
        VoiceServer.registerNetworking(); // Регистрируем прием пакетов СРАЗУ
        // Загружаем конфиг
        loadConfig();

        // Инициализация парсера (подгружаем json в память)
        CyberSpaceParser.initialize();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER = server; // Теперь переменная не null
        });

        // Подписываемся на ивент использования пкм для корректной работы с компонентами, зависящих от пкм
        DataHackerEvents.register();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // 1. Вызываем радар (теперь это одна чистая строчка)
            WorldHacker.tickBlockRadar(server);

            GlobalRadar.tickEntityRadar(server);

            // 2. Дозатор света
            WorldHacker.processLightQueues(server);
        });

        // Временный блок для тестов (Интеграция с CyberSpaceParser)
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.signedContent(); // текст сообщения от ИИ или игрока
            ServerPlayer player = sender;

            // 1. Разбиваем пакет команд по запятым
            String[] commandChunks = text.split(",");

            // 2. Прогоняем каждый кусок через роутер
            for (String rawChunk : commandChunks) {
                String chunk = rawChunk.trim().toLowerCase();
                if (chunk.isEmpty()) continue;

                // Отправляем кусок на анализ и исполнение
                CommandRouter.processChunk(chunk, player, SERVER);
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