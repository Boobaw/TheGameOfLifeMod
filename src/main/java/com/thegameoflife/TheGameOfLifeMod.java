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

	static {
		COMMAND_MAP.put("dirt", player -> {
			WorldHacker.toggle(player, Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK), null);
		});

		COMMAND_MAP.put("grass", player -> {
			WorldHacker.toggle(player, Set.of(Blocks.GRASS_BLOCK), null);
		});

		// Команда на воду (ищем И блоки, И фильтр WATERLOGGED за один проход!)
		COMMAND_MAP.put("water", player -> {
			WorldHacker.toggle(player,
					Set.of(Blocks.WATER, Blocks.BUBBLE_COLUMN, Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS),
					Set.of(new WorldHacker.StateFilter(null, net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true))
			);
		});

		// Команда на Вардена
		COMMAND_MAP.put("warden", player -> {
			EntityHacker.toggleEntity(SERVER, EntityType.WARDEN);
		});

		// Команда на Крипера
		COMMAND_MAP.put("creeper", player -> {
			EntityHacker.toggleEntity(SERVER, EntityType.CREEPER);
		});

		// Команда на Элитру
		COMMAND_MAP.put("elytra", player -> {
			ItemHacker.toggleItem(SERVER, Items.ELYTRA);
		});

		// Команда на Алмазный меч
		COMMAND_MAP.put("sword", player -> {
			ItemHacker.toggleItem(SERVER, Items.DIAMOND_SWORD);
		});

		COMMAND_MAP.put("potions", player -> {
			// Просто просим игру дать нам стандартный компонент зелья!
			var potionConsumable = new ItemStack(Items.POTION).get(DataComponents.CONSUMABLE);

			DataHacker.toggleRule(SERVER, "rule_potions", DataComponents.CONSUMABLE, potionConsumable);
		});

		COMMAND_MAP.put("apple_power", player -> {
			// Копируем свойства еды прямо с ванильного яблока
			var appleFood = new ItemStack(Items.APPLE).get(DataComponents.FOOD);

			DataHacker.toggleRule(SERVER, "rule_apples", DataComponents.FOOD, appleFood);
		});

		// ЭЛИТРА ИЗ ЧЕГО УГОДНО (Позволяет летать, если взять предмет в руки/надеть)
		COMMAND_MAP.put("glider", player -> {
			DataHacker.toggleRule(SERVER, "rule_glider", DataComponents.GLIDER, net.minecraft.util.Unit.INSTANCE);
		});

		// НЕРАЗРУШИМОСТЬ (Исправленный вариант под твои маппинги)
		COMMAND_MAP.put("immortal", player -> {
			DataHacker.toggleRule(SERVER, "rule_immortal", DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
		});

		// ПРОХОДЯЩИЙ СКВОЗЬ СТЕНЫ СНАРЯД (Если применить к стрелам или снежкам)
		COMMAND_MAP.put("ghost_arrow", player -> {
			DataHacker.toggleRule(SERVER, "rule_ghost_projectile", DataComponents.INTANGIBLE_PROJECTILE, net.minecraft.util.Unit.INSTANCE);
		});

		// СТАК 99 (Переопределяет лимит стака)
		COMMAND_MAP.put("stack99", player -> {
			DataHacker.toggleRule(SERVER, "rule_stack99", DataComponents.MAX_STACK_SIZE, 99);
		});

		// ЦВЕТНОЕ КАСТОМНОЕ ИМЯ
		COMMAND_MAP.put("rename_red", player -> {
			net.minecraft.network.chat.Component name = net.minecraft.network.chat.Component.literal("Хакерский Предмет").withStyle(net.minecraft.ChatFormatting.RED);
			DataHacker.toggleRule(SERVER, "rule_rename", DataComponents.CUSTOM_NAME, name);
		});

		// УБРАТЬ АНИМАЦИЮ И УРОН (Предмет выглядит целым, но damage = 0)
		COMMAND_MAP.put("fix_damage", player -> {
			DataHacker.toggleRule(SERVER, "rule_fix", DataComponents.DAMAGE, 0);
		});

		// СВЕЧЕНИЕ (Глитч-эффект зачарования без самих чаров)
		COMMAND_MAP.put("glint", player -> {
			DataHacker.toggleRule(SERVER, "rule_glint", DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		});

		// ЭПИЧЕСКАЯ РЕДКОСТЬ (Делает название предмета розовым)
		COMMAND_MAP.put("epic", player -> {
			DataHacker.toggleRule(SERVER, "rule_epic", DataComponents.RARITY, net.minecraft.world.item.Rarity.EPIC);
		});

		// КРАСНЫЙ ОШЕЙНИК ВОЛКА (Если применить это к яйцу призыва волка, он заспавнится с красным ошейником!)
		COMMAND_MAP.put("red_collar", player -> {
			DataHacker.toggleRule(SERVER, "rule_collar", DataComponents.WOLF_COLLAR, net.minecraft.world.item.DyeColor.RED);
		});
	}

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			SERVER = server; // Теперь переменная не null
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// 1. Вызываем радар (теперь это одна чистая строчка)
			WorldHacker.tickBlockRadar(server);

			GlobalRadar.tickEntityRadar(server);

			// 2. Дозатор света
			WorldHacker.processLightQueues(server);
		});

		// Времменный блок для тестов
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.signedContent(); // текст сообщения
			ServerPlayer serverplayer = sender;          // игрок, который написал

			// 🔹 Проверяем совпадения
			if (COMMAND_MAP.containsKey(text)) {
				// Достаем функцию из словаря, запускаем её и передаем туда нашего игрока!
				COMMAND_MAP.get(text).accept(serverplayer);
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

