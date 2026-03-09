package com.cyberspace.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class CyberSpaceParser {

    // ==========================================
    // 1. ВНУТРЕННИЕ СТРУКТУРЫ ДАННЫХ (RECORDS)
    // ==========================================
    public record ComponentContext(List<List<String>> triggers, DataComponentType<?> type, Map<String, Object> subcomponents, String ruleName) {}
    public record ItemContext(List<List<String>> triggers, Item target) {}
    public record BlockContext(List<List<String>> triggers, Block target) {}
    public record BlockStateContext(List<List<String>> triggers, String property, String value) {}
    public record EntityContext(List<List<String>> triggers, Set<EntityType<?>> targets) {}
    public record AttributeContext(List<List<String>> triggers, Holder<Attribute> attribute, double absurdVal, String ruleName) {}
    // flag != null → атом; atoms/attrAtoms != null → составной объект
    public record EntityFlagContext(String ruleName, List<List<String>> triggers, String flag, double absurdVal, List<String> atoms, List<String> attrAtoms) {
        public boolean isComposite() {
            return (atoms != null && !atoms.isEmpty()) || (attrAtoms != null && !attrAtoms.isEmpty());
        }
    }

    // ==========================================
    // 2. КЭШИ В ПАМЯТИ
    // ==========================================
    public static String WHISPER_INITIAL_PROMPT = ""; // Строка-подсказка для нейросети Whisper

    public static final Map<String, List<List<String>>> SYSTEM_TRIGGERS = new HashMap<>();
    public static final Map<String, List<List<String>>> ACTIONS = new HashMap<>();

    public static final List<ComponentContext> COMPONENT_RULES = new ArrayList<>();
    public static final List<ItemContext> ITEM_RULES = new ArrayList<>();
    public static final List<BlockContext> BLOCK_RULES = new ArrayList<>();
    public static final List<BlockStateContext> BLOCKSTATE_RULES = new ArrayList<>();
    public static final List<EntityContext> ENTITY_RULES = new ArrayList<>();
    public static final List<AttributeContext> ATTRIBUTE_RULES = new ArrayList<>();
    public static final List<EntityFlagContext> FLAG_RULES = new ArrayList<>();

    // ==========================================
    // 3. ИНИЦИАЛИЗАЦИЯ
    // ==========================================
    public static void initialize() {
        JsonObject root = CyberSpaceConfig.getConfig();

        if (root == null || !root.has("registry")) {
            System.err.println("[CyberSpaceParser] Ошибка: Пустой конфиг или нет блока 'registry'!");
            return;
        }

        JsonObject registry = root.getAsJsonObject("registry");

        WHISPER_INITIAL_PROMPT = ""; // Очищаем перед парсингом

        parseSystemTriggers(registry);
        parseActions(registry);
        parseUniversalItems(registry); // Общий парсер для предметов и блоков
        parseComponents(registry);
        parseBlockStates(registry);
        parseEntities(registry);
        parseAttributes(registry);
        parseFlags(registry);

        // Убираем последнюю запятую с пробелом из промпта
        if (WHISPER_INITIAL_PROMPT.endsWith(", ")) {
            WHISPER_INITIAL_PROMPT = WHISPER_INITIAL_PROMPT.substring(0, WHISPER_INITIAL_PROMPT.length() - 2);
        }

        System.out.println("[CyberSpaceParser] База знаний загружена:");
        System.out.println("  Модификаторов/Макросов: " + (SYSTEM_TRIGGERS.size() + ACTIONS.size()));
        System.out.println("  Универсальных сущностей: " + ITEM_RULES.size() + " предметов, " + BLOCK_RULES.size() + " блоков");
        System.out.println("  Точечных компонентов: " + COMPONENT_RULES.size() + " | Состояний: " + BLOCKSTATE_RULES.size());
        System.out.println("  Подсказки для Whisper: [" + WHISPER_INITIAL_PROMPT + "]");
        System.out.println("  Атрибуты: " + ATTRIBUTE_RULES.size());

        generateAiDictionary();
        dumpWhisperPrompt();
    }

    // --- ВСПОМОГАТЕЛЬНЫЙ МЕТОД ДЛЯ WHISPER ---
    private static void processSttHints(JsonObject obj) {
        if (obj.has("stt_hints")) {
            for (JsonElement el : obj.getAsJsonArray("stt_hints")) {
                String hint = el.getAsString().trim();
                if (!WHISPER_INITIAL_PROMPT.contains(hint)) {
                    WHISPER_INITIAL_PROMPT += hint + ", ";
                }
            }
        }
    }

    // --- ПАРСЕРЫ ОТДЕЛЬНЫХ БЛОКОВ ---
    private static void parseSystemTriggers(JsonObject registry) {
        if (!registry.has("system_triggers")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("system_triggers").entrySet()) {
            SYSTEM_TRIGGERS.put(entry.getKey(), parseTriggers(entry.getValue().getAsJsonArray()));
        }
    }

    private static void parseActions(JsonObject registry) {
        if (!registry.has("actions")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("actions").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            processSttHints(obj);
            ACTIONS.put(entry.getKey(), parseTriggers(obj.getAsJsonArray("entry_points")));
        }
    }

    private static void parseAttributes(JsonObject registry) {
        if (!registry.has("attributes")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("attributes").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            processSttHints(obj);
            JsonObject exit = obj.getAsJsonObject("exit_points");

            String attrString = exit.get("attribute").getAsString();
            Identifier attrId = Identifier.parse(attrString);

            // IDE сама подсказывает, что get() съест Identifier и вернет нужный Optional
            Optional<Holder.Reference<Attribute>> attrOpt = BuiltInRegistries.ATTRIBUTE.get(attrId);

            if (attrOpt.isEmpty()) {
                System.out.println("[Parser] Ошибка: Атрибут " + attrString + " не найден в реестре!");
                continue;
            }

            double absurdVal = exit.get("absurd_val").getAsDouble();

            ATTRIBUTE_RULES.add(new AttributeContext(
                    parseTriggers(obj.getAsJsonArray("entry_points")),
                    attrOpt.get(),
                    absurdVal,
                    entry.getKey()
            ));
        }
    }

    public static void parseFlags(JsonObject root) {
        if (!root.has("flags")) return;
        JsonObject flagsBlock = root.getAsJsonObject("flags");

        for (String ruleName : flagsBlock.keySet()) {
            JsonElement rawEntry = flagsBlock.get(ruleName);
            if (!rawEntry.isJsonObject()) continue; // пропускаем "//" комментарии
            JsonObject ruleObj = rawEntry.getAsJsonObject();
            processSttHints(ruleObj);
            JsonObject exitPoints = ruleObj.getAsJsonObject("exit_points");
            List<List<String>> triggers = parseTriggers(ruleObj.getAsJsonArray("entry_points"));

            if (exitPoints.has("atoms") || exitPoints.has("attr_atoms")) {
                // Составной объект: флаговые атомы + атрибутные атомы
                List<String> atoms = new ArrayList<>();
                List<String> attrAtoms = new ArrayList<>();
                if (exitPoints.has("atoms"))
                    for (JsonElement el : exitPoints.getAsJsonArray("atoms"))
                        atoms.add(el.getAsString());
                if (exitPoints.has("attr_atoms"))
                    for (JsonElement el : exitPoints.getAsJsonArray("attr_atoms"))
                        attrAtoms.add(el.getAsString());
                FLAG_RULES.add(new EntityFlagContext(ruleName, triggers, null, 1.0,
                        atoms.isEmpty() ? null : atoms,
                        attrAtoms.isEmpty() ? null : attrAtoms));
            } else {
                // Атом: простой флаг, absurd_val по умолчанию 1.0
                String flag = exitPoints.get("flag").getAsString();
                double absurdVal = exitPoints.has("absurd_val") ? exitPoints.get("absurd_val").getAsDouble() : 1.0;
                FLAG_RULES.add(new EntityFlagContext(ruleName, triggers, flag, absurdVal, null, null));
            }
        }
        System.out.println("[Parser] Флаги загружены: " + FLAG_RULES.size());
    }



    // ГЛАВНЫЙ АПГРЕЙД: Единый парсер для Блоков и Предметов
    private static void parseUniversalItems(JsonObject registry) {
        if (!registry.has("items")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("items").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            processSttHints(obj);
            List<List<String>> triggers = parseTriggers(obj.getAsJsonArray("entry_points"));
            JsonObject exit = obj.getAsJsonObject("exit_points");

            // Если есть цель-блок, регистрируем правило для чанков
            if (exit.has("target_block")) {
                BuiltInRegistries.BLOCK.getOptional(Identifier.parse(exit.get("target_block").getAsString()))
                        .ifPresent(block -> BLOCK_RULES.add(new BlockContext(triggers, block)));
            }

            // Если есть цель-предмет, регистрируем правило для инвентарей
            if (exit.has("target_item")) {
                BuiltInRegistries.ITEM.getOptional(Identifier.parse(exit.get("target_item").getAsString()))
                        .ifPresent(item -> ITEM_RULES.add(new ItemContext(triggers, item)));
            }
        }
    }

    private static void parseComponents(JsonObject registry) {
        if (!registry.has("components")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("components").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            processSttHints(obj);
            JsonObject exit = obj.getAsJsonObject("exit_points");

            String compString = exit.get("component").getAsString();
            DataComponentType<?> compType = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(Identifier.parse(compString)).orElse(null);

            if (compType == null) {
                System.out.println("[Parser] Ошибка: Компонент " + compString + " не найден в реестре!");
                continue;
            }

            Map<String, Object> parsedModifiers = new HashMap<>();
            if (exit.has("subcomponents")) {
                for (Map.Entry<String, JsonElement> prop : exit.getAsJsonObject("subcomponents").entrySet()) {
                    JsonElement val = prop.getValue();
                    if (val.isJsonNull()) parsedModifiers.put(prop.getKey(), null);
                    else if (val.getAsJsonPrimitive().isNumber()) parsedModifiers.put(prop.getKey(), val.getAsString().contains(".") ? val.getAsFloat() : val.getAsInt());
                    else if (val.getAsJsonPrimitive().isBoolean()) parsedModifiers.put(prop.getKey(), val.getAsBoolean());
                    else parsedModifiers.put(prop.getKey(), val.getAsString());
                }
            }

            COMPONENT_RULES.add(new ComponentContext(parseTriggers(obj.getAsJsonArray("entry_points")), compType, parsedModifiers, entry.getKey()));
        }
    }

    private static void parseBlockStates(JsonObject registry) {
        if (!registry.has("blockstates")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("blockstates").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            processSttHints(obj);
            JsonObject exit = obj.getAsJsonObject("exit_points");
            BLOCKSTATE_RULES.add(new BlockStateContext(
                    parseTriggers(obj.getAsJsonArray("entry_points")),
                    exit.get("property").getAsString(),
                    exit.get("value").getAsString()
            ));
        }
    }

    private static void parseEntities(JsonObject registry) {
        if (!registry.has("entities")) return;
        for (Map.Entry<String, JsonElement> entry : registry.getAsJsonObject("entities").entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            processSttHints(obj);
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
        if (array == null) return orList;
        for (JsonElement orElem : array) {
            List<String> andList = new ArrayList<>();
            for (JsonElement andElem : orElem.getAsJsonArray()) {
                String word = andElem.getAsString().toLowerCase();
                andList.add(word);
            }
            orList.add(andList);
        }
        return orList;
    }

    // ==========================================
    // 4. ДВИЖОК ПОИСКА СОВПАДЕНИЙ (МЭТЧЕР)
    // ==========================================
    public record MatchResult(int weight, BitSet indices) {}

    public static boolean checkMatch(String input, List<List<String>> triggers) {
        return getMatchResult(input, triggers) != null;
    }

    /**
     * Возвращает результат самого "тяжелого" совпадения для группы триггеров.
     */
    public static MatchResult getMatchResult(String input, List<List<String>> triggers) {
        if (input == null || triggers == null) return null;
        String lowerInput = input.toLowerCase();
        MatchResult bestResult = null;

        for (List<String> andGroup : triggers) {
            // Собираем фразу из группы триггеров
            String phrase = String.join(" ", andGroup);
            int startIdx = lowerInput.indexOf(phrase);

            if (startIdx != -1) {
                // Если нашли фразу целиком - это идеальное совпадение
                BitSet indices = new BitSet(input.length());
                indices.set(startIdx, startIdx + phrase.length());
                
                // Вес - это полная длина фразы в строке
                if (bestResult == null || phrase.length() > bestResult.weight) {
                    bestResult = new MatchResult(phrase.length(), indices);
                }
            } else {
                // Если фраза целиком не найдена, пробуем найти все слова по отдельности (старый вариант)
                BitSet groupIndices = new BitSet(input.length());
                boolean allMatch = true;
                int combinedWeight = 0;

                for (String word : andGroup) {
                    int matchIdx = lowerInput.indexOf(word);
                    if (matchIdx != -1) {
                        groupIndices.set(matchIdx, matchIdx + word.length());
                        combinedWeight += word.length();
                    } else {
                        allMatch = false;
                        break;
                    }
                }

                if (allMatch && (bestResult == null || combinedWeight > bestResult.weight)) {
                    bestResult = new MatchResult(combinedWeight, groupIndices);
                }
            }
        }
        return bestResult;
    }

    public static MatchResult getSystemMatchResult(String input, String triggerKey) {
        List<List<String>> triggers = SYSTEM_TRIGGERS.get(triggerKey);
        return triggers != null ? getMatchResult(input, triggers) : null;
    }

    public static boolean hasSystemTrigger(String input, String triggerKey) {
        return checkMatch(input, SYSTEM_TRIGGERS.get(triggerKey));
    }

    // ==========================================
    // 5. ГЕНЕРАТОР ШПАРГАЛОК ДЛЯ ИИ
    // ==========================================
    public static void generateAiDictionary() {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path dumpFile = configDir.resolve("CyberSpace_AI_Dictionary.txt");

            StringBuilder sb = new StringBuilder();
            sb.append("=== СЛОВАРЬ ТРИГГЕРОВ ДЛЯ НЕЙРОСЕТИ (CyberSpace) ===\n");
            sb.append("Инструкция: Для вызова нужного действия используйте корни слов из раздела 'Фразы'.\n\n");

            sb.append("--- СИСТЕМНЫЕ ТРИГГЕРЫ (Модификаторы) ---\n");
            SYSTEM_TRIGGERS.forEach((k, v) -> sb.append("Модификатор [").append(k).append("]: ").append(formatTriggers(v)).append("\n"));

            sb.append("\n--- ГЛОБАЛЬНЫЕ ДЕЙСТВИЯ (Макросы) ---\n");
            ACTIONS.forEach((k, v) -> sb.append("Макрос [").append(k).append("]: ").append(formatTriggers(v)).append("\n"));

            sb.append("\n--- УНИВЕРСАЛЬНЫЕ ПРЕДМЕТЫ / БЛОКИ ---\n");
            for (ItemContext ctx : ITEM_RULES) {
                sb.append("Предмет [").append(BuiltInRegistries.ITEM.getKey(ctx.target())).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }
            for (BlockContext ctx : BLOCK_RULES) {
                sb.append("Блок [").append(BuiltInRegistries.BLOCK.getKey(ctx.target())).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }

            sb.append("\n--- СОСТОЯНИЯ БЛОКОВ (Фильтры) ---\n");
            for (BlockStateContext ctx : BLOCKSTATE_RULES) {
                sb.append("Свойство [").append(ctx.property()).append("=").append(ctx.value()).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }

            sb.append("\n--- ТОЧЕЧНЫЕ КОМПОНЕНТЫ ---\n");
            for (ComponentContext ctx : COMPONENT_RULES) {
                sb.append("Компонент [").append(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(ctx.type())).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }

            sb.append("\n--- ГЛОБАЛЬНЫЕ АТРИБУТЫ (Физика и статы) ---\n");
            for (AttributeContext ctx : ATTRIBUTE_RULES) {
                sb.append("Атрибут [").append(ctx.ruleName()).append("] -> Фразы: ").append(formatTriggers(ctx.triggers())).append("\n");
            }

            Files.writeString(dumpFile, sb.toString());
        } catch (Exception e) {
            System.err.println("[CyberSpaceParser] Ошибка при генерации словаря для ИИ!");
            e.printStackTrace();
        }
    }

    public static void dumpWhisperPrompt() {
        try {
            // Берем папку config твоего сервера/клиента
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path dumpFile = configDir.resolve("CyberSpace_Whisper_Prompt.txt");

            StringBuilder sb = new StringBuilder();
            sb.append("=== INITIAL PROMPT ДЛЯ WHISPER.CPP ===\n");
            sb.append("Скопируй текст ниже и вставь в параметр --prompt (или initial_prompt) твоей STT модели:\n\n");

            // Вставляем саму строку, которую мы собрали при парсинге
            sb.append(WHISPER_INITIAL_PROMPT);

            Files.writeString(dumpFile, sb.toString());
            System.out.println("[CyberSpaceParser] Промпт для Whisper успешно сохранен в CyberSpace_Whisper_Prompt.txt");

        } catch (Exception e) {
            System.err.println("[CyberSpaceParser] Ошибка при генерации файла Whisper Prompt!");
            e.printStackTrace();
        }
    }

    private static String formatTriggers(List<List<String>> triggers) {
        return triggers.stream().map(g -> "(" + String.join(" + ", g) + ")").collect(Collectors.joining(" ИЛИ "));
    }
}