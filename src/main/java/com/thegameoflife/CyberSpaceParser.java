package com.thegameoflife;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class CyberSpaceParser {

    // ==========================================
    // 1. ВНУТРЕННИЕ СТРУКТУРЫ ДАННЫХ (RECORDS)
    // ==========================================
    public record ComponentContext(List<List<String>> triggers, DataComponentType<?> type, Item targetItem, List<String> subcomponents,
                                   String ruleName) {}
    public record ItemContext(List<List<String>> triggers, Set<Item> targets) {}
    public record BlockContext(List<List<String>> triggers, Set<Block> targets) {}
    public record BlockStateContext(List<List<String>> triggers, String property, String value) {}
    public record EntityContext(List<List<String>> triggers, Set<EntityType<?>> targets) {}

    // ==========================================
    // 2. КЭШИ В ПАМЯТИ
    // ==========================================
    public static final Map<String, List<List<String>>> SYSTEM_TRIGGERS = new HashMap<>();
    public static final List<ComponentContext> COMPONENT_RULES = new ArrayList<>();
    public static final List<ItemContext> ITEM_RULES = new ArrayList<>();
    public static final List<BlockContext> BLOCK_RULES = new ArrayList<>();
    public static final List<BlockStateContext> BLOCKSTATE_RULES = new ArrayList<>();
    public static final List<EntityContext> ENTITY_RULES = new ArrayList<>();

    // ==========================================
    // 3. ИНИЦИАЛИЗАЦИЯ ИЗ ТВОЕГО КОНФИГА
    // ==========================================
    public static void initialize() {
        JsonObject root = CyberSpaceConfig.getConfig(); // БЕРЕМ ДАННЫЕ ИЗ ТВОЕГО КЛАССА!

        if (root == null || !root.has("registry")) {
            System.err.println("[CyberSpaceParser] Ошибка: Пустой конфиг или нет блока 'registry'!");
            return;
        }

        JsonObject registry = root.getAsJsonObject("registry");

        parseSystemTriggers(registry);
        parseComponents(registry);
        parseItems(registry);
        parseBlocks(registry);
        parseBlockStates(registry);
        parseEntities(registry);

        System.out.println("[CyberSpaceParser] База знаний загружена в память:");
        System.out.println("  Триггеров: " + SYSTEM_TRIGGERS.size());
        System.out.println("  Компонентов: " + COMPONENT_RULES.size());
        System.out.println("  Блоков: " + BLOCK_RULES.size() + " | Состояний: " + BLOCKSTATE_RULES.size());

        generateAiDictionary();
    }

    // --- ПАРСЕРЫ ОТДЕЛЬНЫХ БЛОКОВ ---
    private static void parseSystemTriggers(JsonObject registry) {
        if (!registry.has("system_triggers")) return;
        JsonObject group = registry.getAsJsonObject("system_triggers");
        for (Map.Entry<String, JsonElement> entry : group.entrySet()) {
            SYSTEM_TRIGGERS.put(entry.getKey(), parseTriggers(entry.getValue().getAsJsonArray()));
        }
    }

    private static void parseComponents(JsonObject registry) {
        if (!registry.has("components")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("components").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            String ruleName = entry.getKey();
            JsonObject exit = obj.getAsJsonObject("exit_points");

            DataComponentType<?> compType = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(Identifier.parse(exit.get("component").getAsString())).orElse(null);
            if (compType == null) continue;

            Item targetItem = exit.has("target_item") ? BuiltInRegistries.ITEM.getOptional(Identifier.parse(exit.get("target_item").getAsString())).orElse(null) : null;

            List<String> subKeys = new ArrayList<>();
            if (exit.has("subcomponents")) {
                for (JsonElement el : exit.getAsJsonArray("subcomponents")) subKeys.add(el.getAsString());
            }

            COMPONENT_RULES.add(new ComponentContext(parseTriggers(obj.getAsJsonArray("entry_points")), compType, targetItem, subKeys, ruleName));
        }
    }

    private static void parseBlocks(JsonObject registry) {
        if (!registry.has("blocks")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("blocks").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            JsonObject exit = obj.getAsJsonObject("exit_points");

            Set<Block> blocks = new HashSet<>();
            for (JsonElement el : exit.getAsJsonArray("target_blocks")) {
                BuiltInRegistries.BLOCK.getOptional(Identifier.parse(el.getAsString())).ifPresent(blocks::add);
            }
            if (!blocks.isEmpty()) BLOCK_RULES.add(new BlockContext(parseTriggers(obj.getAsJsonArray("entry_points")), blocks));
        }
    }

    private static void parseBlockStates(JsonObject registry) {
        if (!registry.has("blockstates")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("blockstates").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            JsonObject exit = obj.getAsJsonObject("exit_points");
            BLOCKSTATE_RULES.add(new BlockStateContext(
                    parseTriggers(obj.getAsJsonArray("entry_points")),
                    exit.get("property").getAsString(),
                    exit.get("value").getAsString()
            ));
        }
    }

    private static void parseItems(JsonObject registry) {
        if (!registry.has("items")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("items").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            Set<Item> items = new HashSet<>();
            for (JsonElement el : obj.getAsJsonObject("exit_points").getAsJsonArray("target_items")) {
                BuiltInRegistries.ITEM.getOptional(Identifier.parse(el.getAsString())).ifPresent(items::add);
            }
            if (!items.isEmpty()) ITEM_RULES.add(new ItemContext(parseTriggers(obj.getAsJsonArray("entry_points")), items));
        }
    }

    private static void parseEntities(JsonObject registry) {
        if (!registry.has("entities")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("entities").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            Set<EntityType<?>> entities = new HashSet<>();
            for (JsonElement el : obj.getAsJsonObject("exit_points").getAsJsonArray("target_entities")) {
                BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(el.getAsString())).ifPresent(entities::add);
            }
            if (!entities.isEmpty()) ENTITY_RULES.add(new EntityContext(parseTriggers(obj.getAsJsonArray("entry_points")), entities));
        }
    }

    // Универсальный метод чтения массивов ИЛИ / И
    private static List<List<String>> parseTriggers(JsonArray array) {
        List<List<String>> orList = new ArrayList<>();
        for (JsonElement orElem : array) {
            List<String> andList = new ArrayList<>();
            for (JsonElement andElem : orElem.getAsJsonArray()) {
                andList.add(andElem.getAsString().toLowerCase());
            }
            orList.add(andList);
        }
        return orList;
    }

    // ==========================================
    // 4. ДВИЖОК ПОИСКА СОВПАДЕНИЙ (МЭТЧЕР)
    // ==========================================
    public static boolean checkMatch(String input, List<List<String>> triggers) {
        String lowerInput = input.toLowerCase();

        for (List<String> andGroup : triggers) {
            boolean allMatch = true;
            for (String word : andGroup) {
                if (!lowerInput.contains(word)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) return true;
        }
        return false;
    }

    public static boolean hasSystemTrigger(String input, String triggerKey) {
        List<List<String>> triggers = SYSTEM_TRIGGERS.get(triggerKey);
        return triggers != null && checkMatch(input, triggers);
    }

    public static void generateAiDictionary() {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path dumpFile = configDir.resolve("CyberSpace_AI_Dictionary.txt");

            StringBuilder sb = new StringBuilder();
            sb.append("=== СЛОВАРЬ ТРИГГЕРОВ ДЛЯ НЕЙРОСЕТИ (CyberSpace) ===\n");
            sb.append("Инструкция для ИИ: Для вызова нужного действия используйте слова из раздела 'Фразы'.\n");
            sb.append("Слова соединены плюсом (+) — должны присутствовать вместе. Блоки ИЛИ — достаточно одного совпадения.\n\n");

            sb.append("--- [1] СИСТЕМНЫЕ ТРИГГЕРЫ (Модификаторы) ---\n");
            SYSTEM_TRIGGERS.forEach((key, triggers) ->
                    sb.append("Модификатор [").append(key).append("]: ").append(formatTriggers(triggers)).append("\n")
            );

            sb.append("\n--- [2] БЛОКИ (Удаление / Изменение в мире) ---\n");
            for (BlockContext ctx : BLOCK_RULES) {
                String targets = ctx.targets().stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).collect(Collectors.joining(", "));
                sb.append("Цель [").append(targets).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }

            sb.append("\n--- [3] СОСТОЯНИЯ БЛОКОВ (Фильтры свойств) ---\n");
            for (BlockStateContext ctx : BLOCKSTATE_RULES) {
                sb.append("Свойство [").append(ctx.property()).append("=").append(ctx.value()).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }

            sb.append("\n--- [4] ПРЕДМЕТЫ (Удаление из инвентарей) ---\n");
            for (ItemContext ctx : ITEM_RULES) {
                String targets = ctx.targets().stream().map(i -> BuiltInRegistries.ITEM.getKey(i).toString()).collect(Collectors.joining(", "));
                sb.append("Цель [").append(targets).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }

            sb.append("\n--- [5] КОМПОНЕНТЫ (Модификация свойств предметов) ---\n");
            for (ComponentContext ctx : COMPONENT_RULES) {
                String comp = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(ctx.type()).toString();
                sb.append("Компонент [").append(comp).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }

            sb.append("\n--- [6] СУЩНОСТИ (Удаление мобов) ---\n");
            for (EntityContext ctx : ENTITY_RULES) {
                String targets = ctx.targets().stream().map(e -> BuiltInRegistries.ENTITY_TYPE.getKey(e).toString()).collect(Collectors.joining(", "));
                sb.append("Цель [").append(targets).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }

            Files.writeString(dumpFile, sb.toString());
            System.out.println("[CyberSpaceParser] Шпаргалка для нейросети успешно сгенерирована в CyberSpace_AI_Dictionary.txt");

        } catch (Exception e) {
            System.err.println("[CyberSpaceParser] Ошибка при генерации словаря для ИИ!");
            e.printStackTrace();
        }
    }

    // Красиво форматирует наши массивы И / ИЛИ для чтения человеком/ИИ
    private static String formatTriggers(List<List<String>> triggers) {
        return triggers.stream()
                .map(andGroup -> "(" + String.join(" + ", andGroup) + ")")
                .collect(Collectors.joining(" ИЛИ "));
    }
}