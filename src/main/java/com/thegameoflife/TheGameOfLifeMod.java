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

		// =========================================
// ТЕСТЫ ITEM HACKER (Запрет и Бюро находок)
// =========================================

// Команда на Элитру (проверка неполных стаков и прочности)
		COMMAND_MAP.put("elytra", player -> {
			ItemHacker.toggleItem(SERVER, Items.ELYTRA);
		});

// Команда на Золотое яблоко (проверка стакания после возврата)
		COMMAND_MAP.put("gapple", player -> {
			ItemHacker.toggleItem(SERVER, Items.GOLDEN_APPLE);
		});

// Команда на Динамит (проверка изъятия блоков из сундуков)
		COMMAND_MAP.put("tnt_item", player -> {
			ItemHacker.toggleItem(SERVER, Items.TNT);
		});


// =========================================
// ТЕСТЫ ENTITY HACKER (Камень Души)
// =========================================

// Команда на Зомби (проверка сохранения брони и оружия в руках)
		COMMAND_MAP.put("zombie", player -> {
			EntityHacker.toggleEntity(SERVER, EntityType.ZOMBIE);
		});

// Команда на Жителя (проверка сохранения профессии и торгов!)
		COMMAND_MAP.put("villager", player -> {
			EntityHacker.toggleEntity(SERVER, EntityType.VILLAGER);
		});

// Команда на Вагонетку с сундуком (проверка сохранения лута ВНУТРИ энтити)
		COMMAND_MAP.put("chest_minecart", player -> {
			EntityHacker.toggleEntity(SERVER, EntityType.CHEST_MINECART);
		});

// Команда на Стойку для брони (проверка энтити-декораций)
		COMMAND_MAP.put("armor_stand", player -> {
			EntityHacker.toggleEntity(SERVER, EntityType.ARMOR_STAND);
		});


		// =========================================
		// ТЕСТЫ DATA HACKER (ДНК-Хакер и Компоненты)
		// =========================================

		// Команда на Зелья (твоя базовая проверка)
		COMMAND_MAP.put("potions", player -> {
			var potionConsumable = new ItemStack(Items.POTION).get(DataComponents.CONSUMABLE);
			DataHacker.toggleRule(SERVER, "rule_potions", DataComponents.CONSUMABLE, potionConsumable);
		});

		// Команда на "Всё съедобно" (Берем компонент еды от яблока и вешаем на всё, что в руках)
		COMMAND_MAP.put("make_food", player -> {
			var foodComp = new ItemStack(Items.APPLE).get(DataComponents.FOOD);
			var consumableComp = new ItemStack(Items.APPLE).get(DataComponents.CONSUMABLE);

			DataHacker.toggleRule(SERVER, "rule_food", DataComponents.FOOD, foodComp);
			DataHacker.toggleRule(SERVER, "rule_consumable", DataComponents.CONSUMABLE, consumableComp);
		});

		// Команда на магическое свечение (Glint) для любого предмета в руках
		COMMAND_MAP.put("glint", player -> {
			DataHacker.toggleRule(SERVER, "rule_glint", DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		});

				// =========================================
		// ЭКСТРЕМАЛЬНЫЕ ТЕСТЫ DATA HACKER (Компоненты 1.21.11)
		// =========================================

		// 1. Тест "Тотем Бессмертия" (DeathProtection)
		// Превращает ЛЮБОЙ предмет в руках (даже кусок земли) в рабочий тотем.
		COMMAND_MAP.put("totem", player -> {
			var deathProtection = new ItemStack(Items.TOTEM_OF_UNDYING).get(DataComponents.DEATH_PROTECTION);
			if (deathProtection != null) {
				DataHacker.toggleRule(SERVER, "rule_totem", DataComponents.DEATH_PROTECTION, deathProtection);
			}
		});

		// 2. Тест "Иерихонская труба" (InstrumentComponent - тот, что выделен на скрине!)
		// Теперь при нажатии ПКМ любым предметом он будет трубить как козий рог.
		COMMAND_MAP.put("horn", player -> {
			var instrument = new ItemStack(Items.GOAT_HORN).get(DataComponents.INSTRUMENT);
			if (instrument != null) {
				DataHacker.toggleRule(SERVER, "rule_horn", DataComponents.INSTRUMENT, instrument);
			}
		});

		// 3. Тест "Несгораемый" (DamageResistant)
		// Дает предметам огнеупорность незерита. Если выкинуть предмет в лаву - он не сгорит!
		COMMAND_MAP.put("fire_proof", player -> {
			var damageResistant = new ItemStack(Items.NETHERITE_SCRAP).get(DataComponents.DAMAGE_RESISTANT);
			if (damageResistant != null) {
				DataHacker.toggleRule(SERVER, "rule_fire_proof", DataComponents.DAMAGE_RESISTANT, damageResistant);
			}
		});

		// 4. Тест "Абсолютный блок" (BlocksAttacks)
		// Позволяет блокировать урон мечом, киркой или палкой, как если бы это был щит.
		COMMAND_MAP.put("shield", player -> {
			var blocksAttacks = new ItemStack(Items.SHIELD).get(DataComponents.BLOCKS_ATTACKS);
			if (blocksAttacks != null) {
				DataHacker.toggleRule(SERVER, "rule_shield", DataComponents.BLOCKS_ATTACKS, blocksAttacks);
			}
		});

		// 5. Тест "Клеймо Хакера" (ItemLore)
		// Насильно вписываем кастомный текст в описание всем предметам в инвентарях игроков.
		COMMAND_MAP.put("lore", player -> {
			// Тут мы создаем компонент с нуля, чтобы проверить работу конструкторов
			var customLore = new net.minecraft.world.item.component.ItemLore(
					java.util.List.of(net.minecraft.network.chat.Component.literal("§c§lВЗЛОМАНО DATAHACKER"))
			);
			DataHacker.toggleRule(SERVER, "rule_lore", DataComponents.LORE, customLore);
		});

		// 6. Тест "Подозрительный суп" (SuspiciousStewEffects)
		// Вешаем на любой предмет эффекты, которые он выдаст при съедании (сочетается с командой make_food из прошлого ответа)
		COMMAND_MAP.put("poison_pill", player -> {
			var stewEffects = new ItemStack(Items.SUSPICIOUS_STEW).get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
			if (stewEffects != null) {
				DataHacker.toggleRule(SERVER, "rule_stew", DataComponents.SUSPICIOUS_STEW_EFFECTS, stewEffects);
			}
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

