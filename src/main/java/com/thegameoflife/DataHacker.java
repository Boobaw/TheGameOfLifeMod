package com.thegameoflife;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.item.enchantment.Enchantments;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.Tool;
import net.minecraft.nbt.CompoundTag;

import java.util.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class DataHacker {

    // =========================================
    // ГЛОБАЛЬНЫЙ ДВИЖОК ЭПОХ
    // =========================================
    public static int DATA_EPOCH = 0;

    public static final Map<String, RuleData<?>> REGISTERED_RULES = new ConcurrentHashMap<>();
    public static final java.util.concurrent.CopyOnWriteArrayList<String> ACTIVE_RULES = new java.util.concurrent.CopyOnWriteArrayList<>();


    public record ModResult<T>(T component, boolean isModified) {}

    public record RuleData<T>(
            DataComponentType<T> type,
            Map<String, Object> subcomponents,
            Item targetItem,
            boolean requireDefault
    ) {}

    public static final Map<String, Object> ABSURD_VALUES = Map.ofEntries(
            // ДНО (0, 1, false, COMMON)
            Map.entry("max_stack_size", 1),
            Map.entry("damage", 0),
            Map.entry("max_damage", 1),
            Map.entry("repair_cost", 0),
            Map.entry("mining_speed", 0.0f),
            Map.entry("nutrition", 0),
            Map.entry("saturation", 0.0f),
            Map.entry("consume_seconds", 0.0f),
            Map.entry("minimum_attack_charge", 0.0f),
            Map.entry("enchantment_glint_override", false),
            Map.entry("rarity", net.minecraft.world.item.Rarity.COMMON),
            Map.entry("clear_custom_data", true),
            Map.entry("use_effects", true),
            Map.entry("custom_name", true),
            Map.entry("item_name", true),
            Map.entry("item_model", true),
            Map.entry("lore", true),
            Map.entry("damage_type", true),
            Map.entry("use_cooldown", 0.0f),
            Map.entry("enchantments", true),
            Map.entry("can_place_on", true),
            Map.entry("can_break", true),
            Map.entry("attribute_modifiers", true),
            Map.entry("custom_model_data", true),
            Map.entry("tooltip_display", true),
            Map.entry("use_remainder", true),
            Map.entry("damage_resistant", true),
            Map.entry("attack_range", 0.0f),           // Нулевая дальность атаки
            Map.entry("enchantable", 0),               // Предмет невозможно зачаровать
            Map.entry("damage_per_block", 0),          // Инструмент не наносит урон блоку
            Map.entry("canDestroyBlocksInCreative", false),
            Map.entry("weapon", true),
            Map.entry("equippable", true),
            Map.entry("repairable", true),
            Map.entry("glider", true),
            Map.entry("death_protection", true),
            Map.entry("blocks_attacks", true),
            Map.entry("piercing_weapon", true),
            Map.entry("kinetic_weapon", true),
            Map.entry("swing_animation", true),
            Map.entry("stored_enchantments", true),
            Map.entry("dyed_color", true),
            Map.entry("potion_duration_scale", 0.0f),  // Зелье действует 0 секунд
            Map.entry("map_color", true),
            Map.entry("map_id", true),
            Map.entry("map_decorations", true),
            Map.entry("map_post_processing", true),
            Map.entry("charged_projectiles", true),
            Map.entry("bundle_contents", true),
            Map.entry("potion_contents", true),
            Map.entry("suspicious_stew_effects", true),
            Map.entry("writable_book_content", true),
            Map.entry("written_book_content", true),
            Map.entry("trim", true),
            Map.entry("debug_stick_state", true),
            Map.entry("entity_data", true),
            Map.entry("bucket_entity_data", true),
            Map.entry("block_entity_data", true),
            Map.entry("instrument", true),
            Map.entry("provides_trim_material", true),
            Map.entry("ominous_bottle_amplifier", true),
            Map.entry("jukebox_playable", true),
            Map.entry("provides_banner_patterns", true),
            Map.entry("recipes", true),
            Map.entry("lodestone_tracker", true),
            Map.entry("firework_explosion", true),
            Map.entry("fireworks", true),
            Map.entry("profile", true),
            Map.entry("note_block_sound", true),
            Map.entry("banner_patterns", true),
            Map.entry("base_color", true),
            Map.entry("pot_decorations", true),
            Map.entry("container", true),
            Map.entry("block_state", true),
            Map.entry("bees", true),
            Map.entry("lock", true),
            Map.entry("container_loot", true),
            Map.entry("break_sound", true),
            Map.entry("villager_variant", true),
            Map.entry("wolf_variant", true),
            Map.entry("wolf_sound_variant", true),
            Map.entry("wolf_collar", true),
            Map.entry("fox_variant", true),
            Map.entry("salmon_size", true),
            Map.entry("parrot_variant", true),
            Map.entry("tropical_fish_pattern", true),
            Map.entry("tropical_fish_base_color", true),
            Map.entry("tropical_fish_pattern_color", true),
            Map.entry("mooshroom_variant", true),
            Map.entry("rabbit_variant", true),
            Map.entry("pig_variant", true),
            Map.entry("cow_variant", true),
            Map.entry("chicken_variant", true),
            Map.entry("zombie_nautilus_variant", true),
            Map.entry("frog_variant", true),
            Map.entry("horse_variant", true),
            Map.entry("painting_variant", true),
            Map.entry("llama_variant", true),
            Map.entry("axolotl_variant", true),
            Map.entry("cat_variant", true),
            Map.entry("cat_collar", true),
            Map.entry("sheep_color", true),
            Map.entry("shulker_color", true)
    );

    public static final Map<String, Object> INVERTED_VALUES = Map.ofEntries(
            // ПОТОЛОК (9999, true, EPIC)
            Map.entry("max_stack_size", 99),
            Map.entry("damage", 9999),
            Map.entry("max_damage", 999999),
            Map.entry("repair_cost", 9999),
            Map.entry("mining_speed", 9999.0f),
            Map.entry("nutrition", 20),
            Map.entry("saturation", 20.0f),
            Map.entry("consume_seconds", 99.0f),
            Map.entry("minimum_attack_charge", 10.0f),
            Map.entry("enchantment_glint_override", true),
            Map.entry("rarity", net.minecraft.world.item.Rarity.EPIC),
            Map.entry("use_cooldown", 9999.0f),
            Map.entry("attack_range", 99.0f),           // Бьет за горизонт
            Map.entry("enchantable", 99),              // Безумный шанс крутых чар
            Map.entry("damage_per_block", 9999),
            Map.entry("potion_duration_scale", 99.0f) // Зелье действует целую вечность
    );

    // =========================================
    // ТУМБЛЕР (Генеральный контроллер)
    // =========================================
    public static <T> void toggleRule(MinecraftServer server, String ruleId, RuleData<T> templateRule) {
        if (ACTIVE_RULES.contains(ruleId)) {
            // ВЫКЛЮЧЕНИЕ ПРАВИЛА (Радар сам откатит забаненные шмотки)
            ACTIVE_RULES.remove(ruleId);
            System.out.println("[DataHacker] Правило " + ruleId + " отключено.");
            applyOneTimeBuff(server, ruleId, templateRule);
        } else {
            // ПРОВЕРКА МИРА
            boolean foundInWorld = checkComponentExists(server, templateRule);

            REGISTERED_RULES.put(ruleId, templateRule);

            // ФАЗА 1: ОБЪЕКТА НЕТ -> Выдаем бафф
            if (!foundInWorld) {
                System.out.println("[DataHacker] ОБЪЕКТА НЕТ: Выдаем бафф надетым вещам!");
                applyOneTimeBuff(server, ruleId, templateRule);
            }
            // ФАЗА 2: ОБЪЕКТ ЕСТЬ -> Включается Радар
            else {
                System.out.println("[DataHacker] ОБЪЕКТ ЕСТЬ: Включаем безусловный БАН!");
                resolveSeesawConflicts(ruleId, templateRule);
                ACTIVE_RULES.add(ruleId);
            }
        }
        DATA_EPOCH++;
    }

    // =========================================
    // ВЫШИБАЛА (Разрешение конфликтов по качелям)
    // =========================================
    private static void resolveSeesawConflicts(String newRuleId, RuleData<?> newRule) {
        if (newRule.subcomponents() == null || newRule.subcomponents().isEmpty()) return;

        List<String> rulesToKill = new java.util.ArrayList<>();

        // 1. Анализируем параметры нового правила
        for (Map.Entry<String, Object> entry : newRule.subcomponents().entrySet()) {
            String key = entry.getKey();
            Object targetVal = entry.getValue();

            Object absurdVal = ABSURD_VALUES.get(key);
            Object invertedVal = INVERTED_VALUES.get(key);

            // Если для этого ключа нет качелей - пропускаем
            if (absurdVal == null || invertedVal == null) continue;

            // 2. Вычисляем "вражеское" значение
            Object enemyVal = null;
            if (checkValueMatch(targetVal, absurdVal)) {
                enemyVal = invertedVal; // Мы баним Абсурд -> ищем Инверсию
            } else if (checkValueMatch(targetVal, invertedVal)) {
                enemyVal = absurdVal;   // Мы баним Инверсию -> ищем Абсурд
            }

            // 3. Ищем активные правила с вражеским значением
            if (enemyVal != null) {
                for (String activeId : ACTIVE_RULES) {
                    RuleData<?> activeRule = REGISTERED_RULES.get(activeId);
                    if (activeRule != null && activeRule.subcomponents() != null) {
                        Object activeTargetVal = activeRule.subcomponents().get(key);

                        if (activeTargetVal != null && checkValueMatch(activeTargetVal, enemyVal)) {
                            rulesToKill.add(activeId);
                        }
                    }
                }
            }
        }

        // 4. Убиваем найденные конфликты
        for (String killId : rulesToKill) {
            ACTIVE_RULES.remove(killId);
            System.out.println("[DataHacker] Качели перевесили! Правило " + killId + " автоматически отключено в пользу " + newRuleId);
        }
    }

    // =========================================
    // ИНЖЕКТОР БАФФОВ (ОБЪЕКТА НЕТ)
    // =========================================
    public static void applyOneTimeBuff(MinecraftServer server, String ruleId, RuleData<?> rule) {
        if (rule == null) return;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean inventoryChanged = false;

            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                boolean isEquipped = (i == p.getInventory().getSelectedSlot()) || (i >= 36);
                ItemStack stack = p.getInventory().getItem(i);

                if (isEquipped && !stack.isEmpty()) {
                    if (rule.targetItem() != null) {
                        for (var typedComp : rule.targetItem().components()) {
                            stack.set((DataComponentType) typedComp.type(), typedComp.value());
                        }
                    } else if (rule.type() != null && rule.subcomponents() != null) {
                        applyModifierSafe(stack, rule.type(), rule.subcomponents());
                    }

                    // Срываем клеймо бана, если оно было, и ставим маркер измененного предмета
                    CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                    CompoundTag tag = customData.copyTag();
                    tag.remove("hacked_" + ruleId);

                    markCyberSpaced(stack);
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                    inventoryChanged = true;
                }
            }
            if (inventoryChanged) p.inventoryMenu.broadcastChanges();
        }
    }

    // =========================================
    // ЛОКАТОР СОВПАДЕНИЙ (Обновленный, без targetValue)
    // =========================================
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean isMatch(ItemStack stack, RuleData<?> rawRule) {
        if (stack.isEmpty()) return false;

        // --- СЦЕНАРИЙ А: Квантовый Слепок (Предмет) ---
        if (rawRule.targetItem() != null) {
            if (!rawRule.requireDefault() && stack.is(rawRule.targetItem())) {
                return true; // Кувалда
            }

            for (DataComponentType compType : rawRule.targetItem().components().keySet()) {
                if (!stack.has(compType)) continue;

                Object currentComponent = stack.get(compType);
                Object defaultDonorComponent = rawRule.targetItem().components().get(compType);

                if (defaultDonorComponent != null && currentComponent.equals(defaultDonorComponent)) {
                    return true; // Вирус
                }
            }
            return false;
        }

        // --- СЦЕНАРИЙ Б: Точечное правило (Универсальный Стрингификатор) ---
        else if (rawRule.type() != null) {
            DataComponentType type = rawRule.type();
            if (!stack.has(type)) return false;

            Object currentComponent = stack.get(type);

            // ЕСЛИ ЕСТЬ ВЛОЖЕННЫЕ ПАРАМЕТРЫ
            if (rawRule.subcomponents() != null && !rawRule.subcomponents().isEmpty()) {

                // Превращаем любой ванильный компонент (Enum, Чары, Примитив) в единую строку!
                String currentStr = String.valueOf(currentComponent).toLowerCase();

                for (Map.Entry<String, Object> entry : rawRule.subcomponents().entrySet()) {
                    if (entry.getValue() == null) return true; // Джокер

                    String reqValue = String.valueOf(entry.getValue()).toLowerCase();
                    String reqKey = entry.getKey().toLowerCase();

                    // Ищем совпадение прямо в сыром тексте компонента.
                    // Это покроет 99% случаев (Rarity.EPIC -> "epic", Чары -> ключи и уровни)
                    // Ищем совпадение прямо в сыром тексте компонента.
                    if (currentStr.contains(reqValue) || currentStr.contains(reqKey)) {
                        return true;
                    }
                }
                return false;
            }

            // ЕСЛИ SUBCOMPONENTS ПУСТ
            if (rawRule.requireDefault()) {
                Object defaultComponent = stack.getItem().components().get(type);
                return defaultComponent != null && currentComponent.equals(defaultComponent);
            }

            return true;
        }

        return false;
    }

    // =========================================
    // РАЗВЕДЧИК (Ищет совпадения за миллисекунды)
    // =========================================
    private static <T> boolean checkComponentExists(MinecraftServer server, RuleData<T> rule) {
        // 1. Проверяем карманы игроков
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (isMatch(stack, rule)) return true;
            }
        }

        // 2. Проверяем мир (сущности и сундуки вокруг игроков)
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ServerPlayer) continue;

                if (e instanceof ItemEntity item && isMatch(item.getItem(), rule)) return true;
                if (e instanceof ItemFrame frame && isMatch(frame.getItem(), rule)) return true;

                if (e instanceof LivingEntity living) {
                    for (EquipmentSlot slot : EquipmentSlot.values()) {
                        if (isMatch(living.getItemBySlot(slot), rule)) return true;
                    }
                }
            }

            // 3. Проверяем загруженные чанки (сундуки, печки, воронки)
            int viewDist = server.getPlayerList().getViewDistance();
            Set<Long> scannedChunks = new java.util.HashSet<>();

            for (ServerPlayer player : level.players()) {
                ChunkPos pPos = player.chunkPosition();
                for (int x = -viewDist; x <= viewDist; x++) {
                    for (int z = -viewDist; z <= viewDist; z++) {
                        long chunkPosLong = ChunkPos.asLong(pPos.x + x, pPos.z + z);
                        if (scannedChunks.add(chunkPosLong)) {
                            LevelChunk chunk = level.getChunkSource().getChunkNow(pPos.x + x, pPos.z + z);
                            if (chunk != null) {
                                for (BlockEntity be : chunk.getBlockEntities().values()) {
                                    if (be instanceof Container container) {
                                        for (int i = 0; i < container.getContainerSize(); i++) {
                                            if (isMatch(container.getItem(i), rule)) return true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return false; // Разведчик прочесал весь активный мир и ничего не нашел (будет БАФФ)
    }

    // =========================================
    // КОНВЕЙЕР РАДАРА (Скармливает предметы ядру)
    // =========================================
    public static void processPlayerInventory(ServerPlayer player) {
        if (REGISTERED_RULES.isEmpty()) return;
        boolean invChanged = false;

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            boolean isEquipped = (i == player.getInventory().getSelectedSlot()) || (i >= 36); // В руках или броня
            if (processItemStack(player.getInventory().getItem(i), true, isEquipped)) invChanged = true;
        }
        if (invChanged) player.inventoryMenu.broadcastChanges();
    }

    public static void processBlockEntity(BlockEntity be) {
        if (REGISTERED_RULES.isEmpty()) return;
        if (be instanceof Container container) {
            boolean changed = false;
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (processItemStack(container.getItem(i), false, false)) changed = true;
            }
            if (changed) be.setChanged();
        }
    }

    public static void processEntity(Entity e) {
        if (e instanceof ServerPlayer) return;
        if (REGISTERED_RULES.isEmpty()) return;

        if (e instanceof ItemEntity item) {
            processItemStack(item.getItem(), false, false);
        } else if (e instanceof ItemFrame frame) {
            processItemStack(frame.getItem(), false, false);
        } else if (e instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                processItemStack(living.getItemBySlot(slot), false, false);
            }
        }
    }

    // =========================================
    // ХИРУРГИЧЕСКИЙ СТОЛ (Ядро DataHacker)
    // =========================================
    public static boolean isProcessing = false;


    public static boolean processItemStack(ItemStack stack, boolean inPlayerInventory, boolean isEquipped) {
        if (stack.isEmpty() || isProcessing) return false;

        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();

        // Сверка часов: Если предмет живет в текущей эпохе - не трогаем его
        // (Используем обычный getInt, так как в CompoundTag он возвращает 0, если ключа нет)
        int itemEpoch = tag.getInt("datahacker_epoch").orElse(0);
        if (itemEpoch == DATA_EPOCH) return false;

        isProcessing = true;
        boolean changed = false;

        try {
            // ==========================================
            // ФАЗА 1: ОЧИСТКА (Откат отключенных правил)
            // ==========================================
            // Прогоняем только те правила, которые мы выключили (их нет в ACTIVE_RULES)
            for (Map.Entry<String, RuleData<?>> entry : REGISTERED_RULES.entrySet()) {
                if (!ACTIVE_RULES.contains(entry.getKey())) {
                    try {
                        changed |= applyRule(
                                stack, tag, entry.getKey(), entry.getValue(),
                                false, // isActive = false запускает блок ОТКАТА в applyRule
                                inPlayerInventory, isEquipped
                        );
                    } catch (Exception ex) {
                        // Тихо гасим ошибки, чтобы один кривой компонент не убил всю очистку
                    }
                }
            }

            // ==========================================
            // ФАЗА 2: НАКАТ (Применение активных правил)
            // ==========================================
            // Теперь накатываем активные правила поверх чистого листа
            for (String activeId : ACTIVE_RULES) {
                RuleData<?> rule = REGISTERED_RULES.get(activeId);
                if (rule != null) {
                    try {
                        changed |= applyRule(
                                stack, tag, activeId, rule,
                                true, // isActive = true запускает блок БАНА/БАФФА
                                inPlayerInventory, isEquipped
                        );
                    } catch (Exception ex) {
                        // Тихо гасим ошибки
                    }
                }
            }

            // Штампуем новую эпоху в паспорт предмета
            tag.putInt("datahacker_epoch", DATA_EPOCH);

            // Сохраняем NBT
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

            // Возвращаем true, чтобы инвентарь синхронизировался с клиентом.
            // Эпоха обновилась в любом случае, так что предмет изменился.
            changed = true;

        } finally {
            isProcessing = false;
        }

        return changed;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean applyRule(ItemStack stack, CompoundTag tag, String ruleId, RuleData<?> rawRule, boolean isActive, boolean inPlayerInventory, boolean isEquipped) {
        boolean changed = false;
        String hackedTag = "hacked_" + ruleId;

        if (isActive) {
            if (tag.contains(hackedTag)) return false;

            // ==========================================
            // БЕЗУСЛОВНЫЙ БАН (Выжигание компонентов)
            // ==========================================

            // --- СЦЕНАРИЙ А: Правило от Предмета ---
            if (rawRule.targetItem() != null) {
                for (DataComponentType compType : rawRule.targetItem().components().keySet()) {
                    if (!stack.has(compType)) continue;

                    Object currentComponent = stack.get(compType);
                    Object defaultDonorComponent = rawRule.targetItem().components().get(compType);
                    boolean match = false;

                    if (defaultDonorComponent != null && currentComponent.equals(defaultDonorComponent)) {
                        match = true;
                    } else if (!rawRule.requireDefault() && stack.is(rawRule.targetItem())) {
                        match = true;
                    }

                    if (match) {
                        if (rawRule.subcomponents() == null || rawRule.subcomponents().isEmpty()) {
                            stack.remove(compType);
                            changed = true;
                        } else {
                            // Скальпель сам всё проверит и изменит
                            ModResult result = modifySubcomponents(currentComponent, rawRule.subcomponents());
                            if (result.isModified()) {
                                stack.set(compType, result.component());
                                changed = true;
                            }
                        }
                    }
                }
            }
            // --- СЦЕНАРИЙ Б: Точечное правило ---
            else if (rawRule.type() != null) {
                DataComponentType type = rawRule.type();
                if (!stack.has(type)) return false;

                Object currentComponent = stack.get(type);

                // ЕСЛИ ЕСТЬ ВЛОЖЕННЫЕ ПАРАМЕТРЫ -> ОТДАЕМ ВСЁ СКАЛЬПЕЛЮ
                if (rawRule.subcomponents() != null && !rawRule.subcomponents().isEmpty()) {
                    ModResult result = modifySubcomponents(currentComponent, rawRule.subcomponents());
                    if (result.isModified()) {
                        stack.set(type, result.component());
                        changed = true;
                    }

                }
                // ЕСЛИ SUBCOMPONENTS ПУСТ (Глобальный бан или проверка дефолта)
                else {
                    if (rawRule.requireDefault()) {
                        Object defaultComponent = stack.getItem().components().get(type);
                        if (defaultComponent != null && currentComponent.equals(defaultComponent)) {
                            stack.remove(type);
                            changed = true;
                        }
                    } else {
                        stack.remove(type); // Глобальный бан компонента
                        changed = true;
                    }
                }
            }

            if (changed) {
                markCyberSpaced(stack);
                tag.putBoolean(hackedTag, true);
            }

        }
        // ==========================================
        // ОТКАТ
        // ==========================================
        else {
            if (tag.contains(hackedTag)) {
                if (rawRule.targetItem() != null) {
                    for (DataComponentType compType : rawRule.targetItem().components().keySet()) {
                        Object vanillaComponent = stack.getItem().components().get(compType);
                        if (vanillaComponent != null) stack.set((DataComponentType<Object>) compType, vanillaComponent);
                        else stack.remove(compType);
                    }
                } else if (rawRule.type() != null) {
                    DataComponentType type = rawRule.type();
                    Object vanillaComponent = stack.getItem().components().get(type);
                    if (vanillaComponent != null) stack.set((DataComponentType<Object>) type, vanillaComponent);
                    else stack.remove(type);
                }

                unmarkCyberSpaced(stack);
                tag.remove(hackedTag);
                changed = true;
            }
        }

        return changed;
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean applyModifierSafe(ItemStack stack, DataComponentType<T> type, Map<String, Object> subcomponents) {
        T currentComponent = stack.get(type);
        ModResult<T> result = modifySubcomponents(currentComponent, subcomponents);

        if (result.isModified()) {
            if (result.component() == null) stack.remove(type);
            else stack.set(type, result.component());
            return true;
        }
        return false;
    }

    // =========================================
    // УНИВЕРСАЛЬНЫЕ КАЧЕЛИ (Anti-Absurd Logic)
    // =========================================
    private static Object applySeesaw(Object currentValue, String key, Object targetVal) {
        Object absurdVal = ABSURD_VALUES.get(key);
        Object invertedVal = INVERTED_VALUES.get(key);

        // 1. Безусловный бан
        if (targetVal == null) return absurdVal;

        // 2. Если текущее значение совпадает с целью
        if (checkValueMatch(currentValue, targetVal)) {
            // МАГИЯ: Если цель УЖЕ абсурд и есть инверсия — выдаем инверсию
            if (invertedVal != null && checkValueMatch(absurdVal, targetVal)) {
                return invertedVal;
            }
            // Иначе бьем об пол
            return absurdVal;
        }

        // 3. Если не совпало — возвращаем оригинал (без изменений)
        return currentValue;
    }

    // =========================================
    // СКАЛЬПЕЛЬ (С оптимизированным возвратом)
    // =========================================
    @SuppressWarnings("unchecked")
    private static <T> ModResult<T> modifySubcomponents(T existingComponent, Map<String, Object> modifiers) {
        if (existingComponent == null || modifiers == null || modifiers.isEmpty()) {
            return new ModResult<>(existingComponent, false);
        }

        // ==========================================
        // 1. ПРИМИТИВЫ (С качелями applySeesaw)
        // ==========================================
        if (existingComponent instanceof Integer currentInt) {
            for (Map.Entry<String, Object> entry : modifiers.entrySet()) {
                String key = entry.getKey();
                // MAX_STACK_SIZE, MAX_DAMAGE, DAMAGE
                if (key.equals("max_stack_size") || key.equals("damage") || key.equals("max_damage") || key.equals("repair_cost")) {
                    Object res = applySeesaw(currentInt, key, entry.getValue());
                    if (!res.equals(currentInt)) return new ModResult<>((T) res, true);
                }
            }
        }
        if (existingComponent instanceof Float currentFloat) {
            if (modifiers.containsKey("minimum_attack_charge")) {
                Object res = applySeesaw(currentFloat, "minimum_attack_charge", modifiers.get("minimum_attack_charge"));
                if (!res.equals(currentFloat)) return new ModResult<>((T) res, true);
            }
            // Множитель длительности зелий
            if (modifiers.containsKey("potion_duration_scale")) {
                Object res = applySeesaw(currentFloat, "potion_duration_scale", modifiers.get("potion_duration_scale"));
                if (!res.equals(currentFloat)) return new ModResult<>((T) res, true);
            }
        }
        if (existingComponent instanceof Boolean currentBool) {
            if (modifiers.containsKey("enchantment_glint_override")) {
                Object res = applySeesaw(currentBool, "enchantment_glint_override", modifiers.get("enchantment_glint_override"));
                if (!res.equals(currentBool)) return new ModResult<>((T) res, true);
            }
        }


        // ==========================================
        // 2. ПУСТЫЕ МАРКЕРЫ И УДАЛЯЕМЫЕ КОМПОНЕНТЫ
        // (Возвращаем null для полного удаления из NBT)
        // ==========================================

        if (existingComponent instanceof net.minecraft.util.Unit && (modifiers.containsKey("unbreakable") || modifiers.containsKey("creative_slot_lock") || modifiers.containsKey("intangible_projectile") || modifiers.containsKey("glider"))) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.network.chat.Component && (modifiers.containsKey("custom_name") || modifiers.containsKey("item_name"))) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.ItemLore && modifiers.containsKey("lore")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.CustomData && (modifiers.containsKey("clear_custom_data") || modifiers.containsKey("bucket_entity_data"))) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.UseEffects && modifiers.containsKey("use_effects")) return new ModResult<>(null, true);
        if ((existingComponent.getClass().getName().contains("EitherHolder") || existingComponent.getClass().getName().contains("DamageType")) && modifiers.containsKey("damage_type")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.AdventureModePredicate && (modifiers.containsKey("can_place_on") || modifiers.containsKey("can_break"))) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.ItemAttributeModifiers && modifiers.containsKey("attribute_modifiers")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.CustomModelData && modifiers.containsKey("custom_model_data")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.TooltipDisplay && modifiers.containsKey("tooltip_display")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.UseRemainder && modifiers.containsKey("use_remainder")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.DamageResistant && modifiers.containsKey("damage_resistant")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.DyedItemColor && modifiers.containsKey("dyed_color")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.SwingAnimation && modifiers.containsKey("swing_animation")) return new ModResult<>(null, true);

        // КОНТЕЙНЕРЫ (Мешки, зелья, супы, заряженные арбалеты)
        if (existingComponent instanceof net.minecraft.world.item.component.BundleContents && modifiers.containsKey("bundle_contents")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.alchemy.PotionContents && modifiers.containsKey("potion_contents")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.SuspiciousStewEffects && modifiers.containsKey("suspicious_stew_effects")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.ChargedProjectiles && modifiers.containsKey("charged_projectiles")) return new ModResult<>(null, true);

        // КНИГИ (Перо и Написанные)
        if (existingComponent instanceof net.minecraft.world.item.component.WritableBookContent && modifiers.containsKey("writable_book_content")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.WrittenBookContent && modifiers.containsKey("written_book_content")) return new ModResult<>(null, true);

        // КАРТЫ (Удаляем ID, цвета, маркеры и эффекты)
        if (existingComponent instanceof net.minecraft.world.item.component.MapItemColor && modifiers.containsKey("map_color")) return new ModResult<>(null, true);
        if (existingComponent instanceof   net.minecraft.world.level.saveddata.maps.MapId && modifiers.containsKey("map_id")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.MapDecorations && modifiers.containsKey("map_decorations")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.MapPostProcessing && modifiers.containsKey("map_post_processing")) return new ModResult<>(null, true);

        // ВИЗУАЛ И УТИЛИТЫ (Шаблоны кузнеца, броня, рог)
        if (existingComponent instanceof net.minecraft.world.item.equipment.trim.ArmorTrim && modifiers.containsKey("trim")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.ProvidesTrimMaterial && modifiers.containsKey("provides_trim_material")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.InstrumentComponent && modifiers.containsKey("instrument")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.DebugStickState && modifiers.containsKey("debug_stick_state")) return new ModResult<>(null, true);

        // ДАННЫЕ СУЩНОСТЕЙ И БЛОКОВ (Яйца призыва, шалкеры в инвентаре)
        // Используем проверку по имени класса, так как TypedEntityData - дженерик
        if (existingComponent.getClass().getName().contains("TypedEntityData")) {
            if (modifiers.containsKey("entity_data") || modifiers.containsKey("block_entity_data")) {
                return new ModResult<>(null, true);
            }
        }
        // КОНТЕЙНЕРЫ, УЛЬИ И ЛУТ (Очистка шалкеров, пчел и генерации лута)
        if (existingComponent instanceof net.minecraft.world.item.component.ItemContainerContents && modifiers.containsKey("container")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.SeededContainerLoot && modifiers.containsKey("container_loot")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.BlockItemStateProperties && modifiers.containsKey("block_state")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.Bees && modifiers.containsKey("bees")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.LockCode && modifiers.containsKey("lock")) return new ModResult<>(null, true);

        // ЗВУКИ И МУЗЫКА (Ломаем пластинки, убираем зловещие бутылочки)
        if (existingComponent instanceof net.minecraft.world.item.JukeboxPlayable && modifiers.containsKey("jukebox_playable")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.OminousBottleAmplifier && modifiers.containsKey("ominous_bottle_amplifier")) return new ModResult<>(null, true);

        // ДЕКОРАЦИИ И ВИЗУАЛ (Фейерверки, баннеры, горшки, головы)
        if (existingComponent instanceof net.minecraft.world.item.component.FireworkExplosion && modifiers.containsKey("firework_explosion")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.Fireworks && modifiers.containsKey("fireworks")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.ResolvableProfile && modifiers.containsKey("profile")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.level.block.entity.BannerPatternLayers && modifiers.containsKey("banner_patterns")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.level.block.entity.PotDecorations && modifiers.containsKey("pot_decorations")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.DyeColor && modifiers.containsKey("base_color")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.world.item.component.LodestoneTracker && modifiers.containsKey("lodestone_tracker")) return new ModResult<>(null, true);

        // ДЖЕНЕРИКИ (Яйца призыва, рецепты, звуки поломки)
        if (existingComponent instanceof java.util.List && modifiers.containsKey("recipes")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.tags.TagKey && modifiers.containsKey("provides_banner_patterns")) return new ModResult<>(null, true);
        if (existingComponent instanceof net.minecraft.core.Holder) {
            if (modifiers.containsKey("break_sound") ||
                    modifiers.containsKey("villager_variant") ||
                    modifiers.containsKey("wolf_variant") ||
                    modifiers.containsKey("wolf_sound_variant")) {
                return new ModResult<>(null, true);
            }
        }
        // ЦВЕТА (Ошейники, овцы, шалкеры, рыбы, базовые цвета)
        if (existingComponent instanceof net.minecraft.world.item.DyeColor) {
            if (modifiers.containsKey("base_color") ||
                    modifiers.containsKey("wolf_collar") ||
                    modifiers.containsKey("cat_collar") ||
                    modifiers.containsKey("sheep_color") ||
                    modifiers.containsKey("shulker_color") ||
                    modifiers.containsKey("tropical_fish_base_color") ||
                    modifiers.containsKey("tropical_fish_pattern_color")) {
                return new ModResult<>(null, true);
            }
        }

        // ХОЛДЕРЫ ВАРИАНТОВ (Свиньи, коровы, лягушки, картины, коты, волки и т.д.)
        if (existingComponent instanceof net.minecraft.core.Holder) {
            if (modifiers.containsKey("break_sound") ||
                    modifiers.containsKey("villager_variant") ||
                    modifiers.containsKey("wolf_variant") ||
                    modifiers.containsKey("wolf_sound_variant") ||
                    modifiers.containsKey("pig_variant") ||
                    modifiers.containsKey("cow_variant") ||
                    modifiers.containsKey("frog_variant") ||
                    modifiers.containsKey("painting_variant") ||
                    modifiers.containsKey("cat_variant")) {
                return new ModResult<>(null, true);
            }
        }

        // EITHER ХОЛДЕРЫ (Курицы, зомби-наутилусы, типы урона)
        if (existingComponent.getClass().getName().contains("EitherHolder")) {
            if (modifiers.containsKey("damage_type") ||
                    modifiers.containsKey("chicken_variant") ||
                    modifiers.containsKey("zombie_nautilus_variant")) {
                return new ModResult<>(null, true);
            }
        }

        // ENUM ВАРИАНТЫ (Лисы, лососи, попугаи, кролики, лошади, аксолотли и т.д.)
        // Все эти Variant классы наследуются от Enum, поэтому мы ловим их одной проверкой!
        if (existingComponent instanceof Enum<?>) {
            if (modifiers.containsKey("fox_variant") ||
                    modifiers.containsKey("salmon_size") ||
                    modifiers.containsKey("parrot_variant") ||
                    modifiers.containsKey("tropical_fish_pattern") ||
                    modifiers.containsKey("mooshroom_variant") ||
                    modifiers.containsKey("rabbit_variant") ||
                    modifiers.containsKey("horse_variant") ||
                    modifiers.containsKey("llama_variant") ||
                    modifiers.containsKey("axolotl_variant")) {
                return new ModResult<>(null, true);
            }
        }

        // ==========================================
        // 3. ОДНОРАЗОВАЯ ЗАМЕНА ЗНАЧЕНИЯ
        // ==========================================

        // ITEM_MODEL (Подменяем модель на воздух, чтобы сделать предмет невидимым)
        if (existingComponent instanceof net.minecraft.resources.Identifier) {
            if (modifiers.containsKey("item_model") || modifiers.containsKey("tooltip_style")) {
                return new ModResult<>((T) net.minecraft.resources.Identifier.parse("minecraft:air"), true);
            }
        }
        // EQUIPPABLE (Сносим модель и звук брони, оставляем только слот, чтобы игра не крашнулась)
        if (existingComponent instanceof net.minecraft.world.item.equipment.Equippable equippable && modifiers.containsKey("equippable")) {
            return new ModResult<>((T) net.minecraft.world.item.equipment.Equippable.builder(equippable.slot()).build(), true);
        }
        // REPAIRABLE (Запрещаем чинить предмет, передав пустой список материалов)
        if (existingComponent instanceof net.minecraft.world.item.enchantment.Repairable && modifiers.containsKey("repairable")) {
            return new ModResult<>((T) new net.minecraft.world.item.enchantment.Repairable(net.minecraft.core.HolderSet.empty()), true);
        }
        // DEATH_PROTECTION (Тотем бессмертия без эффектов восстановления)
        if (existingComponent instanceof net.minecraft.world.item.component.DeathProtection && modifiers.containsKey("death_protection")) {
            return new ModResult<>((T) new net.minecraft.world.item.component.DeathProtection(java.util.List.of()), true);
        }
        // BLOCKS_ATTACKS (Сломанный щит, не блокирует урон)
        if (existingComponent instanceof net.minecraft.world.item.component.BlocksAttacks blocksAttacks && modifiers.containsKey("blocks_attacks")) {
            return new ModResult<>((T) new net.minecraft.world.item.component.BlocksAttacks(
                    0.0f,                               // float f (вероятно, задержка перед блоком)
                    0.0f,                               // float g (угол блокирования)
                    java.util.List.of(),                // пустой список поглощения урона (щит ничего не впитывает)
                    blocksAttacks.itemDamage(), // берем функцию поломки из оригинала, чтобы IDE не ругалась
                    java.util.Optional.empty(),         // без особых тегов пробивания
                    java.util.Optional.empty(),         // без звука успешного блока
                    java.util.Optional.empty()          // без звука отключения щита топором
            ), true);
        }
        // WEAPON (Убираем базовый урон оружия)
        if (existingComponent instanceof net.minecraft.world.item.component.Weapon && modifiers.containsKey("weapon")) {
            return new ModResult<>((T) new net.minecraft.world.item.component.Weapon(0, 0.0f), true);
        }
        // PIERCING_WEAPON (Ломаем трезубец)
        if (existingComponent instanceof net.minecraft.world.item.component.PiercingWeapon && modifiers.containsKey("piercing_weapon")) {
            return new ModResult<>((T) new net.minecraft.world.item.component.PiercingWeapon(
                    false,                      // boolean b1 (флаг пробивания/возврата)
                    false,                      // boolean b2 (второй флаг)
                    java.util.Optional.empty(), // Звук 1
                    java.util.Optional.empty()  // Звук 2
            ), true);
        }
        // KINETIC_WEAPON (Ломаем лук/арбалет)
        if (existingComponent instanceof net.minecraft.world.item.component.KineticWeapon && modifiers.containsKey("kinetic_weapon")) {
            return new ModResult<>((T) new net.minecraft.world.item.component.KineticWeapon(
                    0,                          // int i (что-то вроде времени натяжения)
                    0,                          // int j (базовое количество стрел)
                    java.util.Optional.empty(), // Condition 1
                    java.util.Optional.empty(), // Condition 2
                    java.util.Optional.empty(), // Condition 3
                    0.0f,                       // float f (множитель скорости)
                    0.0f,                       // float g (разброс)
                    java.util.Optional.empty(), // Sound 1
                    java.util.Optional.empty()  // Sound 2
            ), true);
        }
        // IDENTIFIER (Модели и звуки)
        if (existingComponent instanceof net.minecraft.resources.Identifier) {
            // Если банят звук головы на нотном блоке - удаляем его
            if (modifiers.containsKey("note_block_sound")) {
                return new ModResult<>(null, true);
            }
            // Подменяем модель на воздух
            if (modifiers.containsKey("item_model") || modifiers.containsKey("tooltip_style")) {
                return new ModResult<>((T) net.minecraft.resources.Identifier.parse("minecraft:air"), true);
            }
        }

        // ==========================================
        // 4. СЛОЖНЫЕ ОБЪЕКТЫ С КАЧЕЛЯМИ
        // ==========================================

        // TOOL (Инструменты: Кирки, топоры, лопаты)
        if (existingComponent instanceof net.minecraft.world.item.component.Tool tool) {
            float speed = tool.defaultMiningSpeed();
            int damagePerBlock = tool.damagePerBlock();
            boolean canDestroyBlocksInCreative = tool.canDestroyBlocksInCreative();
            boolean isModified = false;

            if (modifiers.containsKey("mining_speed")) {
                Object res = applySeesaw(speed, "mining_speed", modifiers.get("mining_speed"));
                if (!res.equals(speed)) { speed = (Float) res; isModified = true; }
            }
            if (modifiers.containsKey("damage_per_block")) {
                Object res = applySeesaw(damagePerBlock, "damage_per_block", modifiers.get("damage_per_block"));
                if (!res.equals(damagePerBlock)) { damagePerBlock = (Integer) res; isModified = true; }
            }
            if (modifiers.containsKey("canDestroyBlocksInCreative")) {
                Object res = applySeesaw(canDestroyBlocksInCreative, "canDestroyBlocksInCreative", modifiers.get("canDestroyBlocksInCreative"));
                if (!res.equals(canDestroyBlocksInCreative)) { canDestroyBlocksInCreative = (Boolean) res; isModified = true; }
            }
            if (isModified) {
                return new ModResult<>((T) new net.minecraft.world.item.component.Tool(tool.rules(), speed, damagePerBlock, canDestroyBlocksInCreative), true);
            }
        }

        // ENCHANTABLE (Уровень зачаровываемости)
        if (existingComponent instanceof net.minecraft.world.item.enchantment.Enchantable enchantable && modifiers.containsKey("enchantable")) {
            int val = enchantable.value();
            Object res = applySeesaw(val, "enchantable", modifiers.get("enchantable"));
            if (!res.equals(val)) {
                return new ModResult<>((T) new net.minecraft.world.item.enchantment.Enchantable((Integer) res), true);
            }
        }

        // ATTACK_RANGE (Дальность атаки)
        if (existingComponent instanceof net.minecraft.world.item.component.AttackRange range && modifiers.containsKey("attack_range")) {
            // Приводим double от maxRange() к float
            float val = (float) range.maxRange();
            Object res = applySeesaw(val, "attack_range", modifiers.get("attack_range"));

            if (!res.equals(val)) {
                float newRange = (Float) res;
                // Бьем наверняка: передаем новое значение во все 6 параметров (f, g, h, i, j, k)
                return new ModResult<>((T) new net.minecraft.world.item.component.AttackRange(newRange, newRange, newRange, newRange, newRange, newRange), true);
            }
        }

        // CONSUMABLE (Время поедания / использования)
        if (existingComponent instanceof net.minecraft.world.item.component.Consumable consumable) {
            if (modifiers.containsKey("consume_seconds")) {
                float consumeSeconds = consumable.consumeSeconds();
                Object res = applySeesaw(consumeSeconds, "consume_seconds", modifiers.get("consume_seconds"));

                if (!res.equals(consumeSeconds)) {
                    // Пересобираем компонент с новым временем, но старыми партиклами и звуками
                    net.minecraft.world.item.component.Consumable newConsumable = net.minecraft.world.item.component.Consumable.builder()
                            .consumeSeconds((Float) res)
                            .animation(consumable.animation())
                            .sound(consumable.sound())
                            .hasConsumeParticles(consumable.hasConsumeParticles())
                            .build();
                    return new ModResult<>((T) newConsumable, true);
                }
            }
        }

        // USE_COOLDOWN (Перезарядка после использования, например, эндер-жемчуга)
        if (existingComponent instanceof net.minecraft.world.item.component.UseCooldown cooldownComp) {
            if (modifiers.containsKey("use_cooldown")) {
                float seconds = cooldownComp.seconds();
                Object res = applySeesaw(seconds, "use_cooldown", modifiers.get("use_cooldown"));

                if (!res.equals(seconds)) {
                    return new ModResult<>((T) new net.minecraft.world.item.component.UseCooldown((Float) res), true);
                }
            }
        }

        // RARITY
        if (existingComponent instanceof net.minecraft.world.item.Rarity currentRarity) {
            if (modifiers.containsKey("rarity")) {
                Object res = applySeesaw(currentRarity, "rarity", modifiers.get("rarity"));
                if (!res.equals(currentRarity)) return new ModResult<>((T) res, true);
            }
        }

        // ==========================================
        // ЗАЧАРОВАНИЯ (ItemEnchantments)
        // ==========================================
        if (existingComponent instanceof net.minecraft.world.item.enchantment.ItemEnchantments enchs) {
            if (modifiers.containsKey("enchantments") || modifiers.containsKey("stored_enchantments")) {
                return new ModResult<>(null, true);
            }

            net.minecraft.world.item.enchantment.ItemEnchantments.Mutable mutable = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(enchs);
            net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> registry =
                    TheGameOfLifeMod.SERVER.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);

            boolean isModified = false;
            for (Map.Entry<String, Object> entry : modifiers.entrySet()) {
                if (!entry.getKey().contains(":")) continue; // Обрабатываем только "minecraft:sharpness" и т.п.

                net.minecraft.resources.Identifier enchId = net.minecraft.resources.Identifier.parse(entry.getKey());
                var optEnch = registry.getOptional(enchId);

                if (optEnch.isPresent()) {
                    var holder = registry.wrapAsHolder(optEnch.get());
                    int currentLevel = enchs.getLevel(holder);

                    // ВОТ ОНА - ЕДИНАЯ ЛОГИКА ТВОЕГО МОДА!
                    // Прогоняем текущий уровень через твои Квантовые Качели
                    Object res = applySeesaw(currentLevel, entry.getKey(), entry.getValue());

                    // applySeesaw вернет Integer (новый уровень зачарования)
                    if (!res.equals(currentLevel)) {
                        int newLevel = (Integer) res;
                        // Если качели вернули 0 или меньше - стираем зачарование
                        if (newLevel <= 0) {
                            mutable.set(holder, 0);
                        } else {
                            mutable.set(holder, newLevel);
                        }
                        isModified = true;
                    }
                }
            }

            if (isModified) {
                return new ModResult<>((T) mutable.toImmutable(), true);
            }
        }

        // Возврат по умолчанию, если ничего не подошло
        return new ModResult<>(existingComponent, false);
    }




    private static boolean checkValueMatch(Object currentVal, Object targetVal) {
        // Если таргет null — значит баним безусловно
        if (targetVal == null) return true;

        // 1. Сравнение целых чисел
        if (currentVal instanceof Integer currentInt && targetVal instanceof Integer targetInt) {
            return currentInt.intValue() == targetInt.intValue();
        }

        // 2. Сравнение дробей (с учетом погрешности)
        if (currentVal instanceof Float currentFloat && targetVal instanceof Float targetFloat) {
            return Math.abs(currentFloat - targetFloat) < 0.001f;
        }

        // 2.5 Сравнение Double (специально для AttackRange)
        if (currentVal instanceof Double currentDouble && targetVal instanceof Double targetDouble) {
            return Math.abs(currentDouble - targetDouble) < 0.001;
        }

        // 3. Сравнение логики (Boolean)
        if (currentVal instanceof Boolean currentBool && targetVal instanceof Boolean targetBool) {
            return currentBool.booleanValue() == targetBool.booleanValue();
        }

        // 4. Fallback (для Enum, строк и прочего) - ИСПРАВЛЕНО
        return String.valueOf(currentVal).equalsIgnoreCase(String.valueOf(targetVal));
    }

    // Накладывает визуальное клеймо мутации
    private static void markCyberSpaced(ItemStack stack) {
        // Достаем текущий лор и добавляем нашу красную метку
        net.minecraft.world.item.component.ItemLore currentLore = stack.getOrDefault(net.minecraft.core.component.DataComponents.LORE, net.minecraft.world.item.component.ItemLore.EMPTY);
        java.util.List<net.minecraft.network.chat.Component> newLines = new java.util.ArrayList<>(currentLore.lines());

        net.minecraft.network.chat.Component mark = net.minecraft.network.chat.Component.literal("CyberSpace'ed")
                .withStyle(net.minecraft.ChatFormatting.DARK_RED, net.minecraft.ChatFormatting.BOLD);

        // Защита от спама (чтобы надпись не дублировалась)
        if (!newLines.contains(mark)) {
            newLines.add(mark);
            stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(newLines));
        }
    }

    // Стирает визуальное клеймо мутации
    private static void unmarkCyberSpaced(ItemStack stack) {
        // Аккуратно вырезаем нашу строчку из лора
        net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore != null) {
            java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>(lore.lines());
            lines.removeIf(c -> c.getString().contains("CyberSpace'ed"));

            if (lines.isEmpty()) stack.remove(net.minecraft.core.component.DataComponents.LORE);
            else stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lines));
        }
    }
}