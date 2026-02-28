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
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

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
    public static final Set<String> ACTIVE_RULES = ConcurrentHashMap.newKeySet();


    public record RuleData<T>(
            DataComponentType<T> type,
            List<String> subcomponentKeys,
            Item targetItem,
            boolean requireDefault,
            boolean isBan // <-- TRUE (сжигаем везде), FALSE (баффаем только экипировку)
    ) {}

    public static final Map<String, Object> ABSURD_VALUES = Map.of(
            "mining_speed", 0.001f,
            "nutrition", 0,
            "consume_seconds", 0.001f,
            "max_damage", 999999,
            "canDestroyBlocksInCreative", 0
    );

    // =========================================
    // ТУМБЛЕР (Генеральный контроллер)
    // =========================================
    public static <T> void toggleRule(MinecraftServer server, String ruleId, RuleData<T> templateRule) {
        if (ACTIVE_RULES.contains(ruleId)) {
            // ФАЗА ОТКЛЮЧЕНИЯ (Откат Бана или Баффа)
            ACTIVE_RULES.remove(ruleId);
            System.out.println("[DataHacker] Правило " + ruleId + " ОТКЛЮЧЕНО. Восстанавливаем ванильный баланс.");
        } else {
            // ФАЗА ВКЛЮЧЕНИЯ
            // Запускаем разведчика из твоей старой логики!
            boolean foundInWorld = checkComponentExists(server, templateRule);

            // Пересобираем правило, записывая в него результаты разведки
            RuleData<T> finalRule = new RuleData<>(
                    templateRule.type(),
                    templateRule.subcomponentKeys(),
                    templateRule.targetItem(),
                    templateRule.requireDefault(),
                    foundInWorld // Записываем решение: БАН или БАФФ
            );

            REGISTERED_RULES.put(ruleId, finalRule);
            ACTIVE_RULES.add(ruleId);

            if (foundInWorld) {
                System.out.println("[DataHacker] " + ruleId + " (БАН): Найдено в мире. Сжигаем отовсюду.");
            } else {
                System.out.println("[DataHacker] " + ruleId + " (БАФФ): Не найдено. Накладываем на экипировку.");
            }
        }

        // Сигнал радарам перепроверить всё
        DATA_EPOCH++;
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

    // Метод теперь снова строго требует флаги инвентаря и экипировки
    public static boolean processItemStack(ItemStack stack, boolean inPlayerInventory, boolean isEquipped) {
        if (stack.isEmpty() || isProcessing) return false;

        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();

        // Сверка часов: Если предмет живет в текущей эпохе - не трогаем его
        int itemEpoch = tag.getInt("datahacker_epoch").orElse(0);
        if (itemEpoch == DATA_EPOCH) return false;

        isProcessing = true;
        boolean changed = false;

        try {
            // Прогоняем предмет через все зарегистрированные правила
            for (Map.Entry<String, RuleData<?>> entry : REGISTERED_RULES.entrySet()) {
                try {
                    // Вызываем applyRule, пробрасывая в него inPlayerInventory и isEquipped
                    changed |= applyRule(
                            stack,
                            tag,
                            entry.getKey(),
                            entry.getValue(),
                            ACTIVE_RULES.contains(entry.getKey()),
                            inPlayerInventory,
                            isEquipped
                    );
                } catch (Exception ex) {
                    // Тихо гасим ошибки отдельного правила, чтобы не прерывать цикл
                }
            }

            // Штампуем новую эпоху в паспорт предмета
            tag.putInt("datahacker_epoch", DATA_EPOCH);
            if (tag.isEmpty()) {
                stack.remove(DataComponents.CUSTOM_DATA);
            } else {
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            }

            // Возвращаем true, если NBT изменился (даже если только обновилась эпоха)
            if (!changed) changed = true;

        } finally {
            isProcessing = false;
        }

        return changed;
    }

    @SuppressWarnings("unchecked")
    private static <T> T modifySubcomponents(T existingComponent, List<String> absurdKeys) {
        if (existingComponent == null || absurdKeys == null || absurdKeys.isEmpty()) return existingComponent;

        // --- ИНСТРУМЕНТЫ (Tool) ---
        if (existingComponent instanceof Tool tool) {
            float speed = tool.defaultMiningSpeed();
            int damagePerBlock = tool.damagePerBlock();
            boolean canDestroyBlocksInCreative = tool.canDestroyBlocksInCreative();

            // ЦИКЛ: Применяем все запрошенные изменения за один раз
            for (String key : absurdKeys) {
                Object val = ABSURD_VALUES.get(key);
                if (val == null) continue;

                if (key.equals("mining_speed")) speed = (Float) val;
                if (key.equals("damage_per_block")) damagePerBlock = (Integer) val;
                if (key.equals("canDestroyBlocksInCreative")) canDestroyBlocksInCreative = (Boolean) val;
            }
            return (T) new Tool(tool.rules(), speed, damagePerBlock, canDestroyBlocksInCreative);
        }

        // --- СВОЙСТВА ЕДЫ (FoodProperties) ---
        if (existingComponent instanceof FoodProperties food) {
            int nutrition = food.nutrition();
            float saturation = food.saturation();

            for (String key : absurdKeys) {
                Object val = ABSURD_VALUES.get(key);
                if (val == null) continue;

                if (key.equals("nutrition")) nutrition = (Integer) val;
                if (key.equals("saturation")) saturation = (Float) val;
            }

            FoodProperties.Builder builder = new FoodProperties.Builder()
                    .nutrition(nutrition)
                    .saturationModifier(saturation);
            if (food.canAlwaysEat()) builder.alwaysEdible();

            return (T) builder.build();
        }

        // --- ПОЕДАНИЕ (Consumable) ---
        if (existingComponent instanceof Consumable consumable) {
            float consumeSeconds = consumable.consumeSeconds();

            for (String key : absurdKeys) {
                Object val = ABSURD_VALUES.get(key);
                if (val == null) continue;

                if (key.equals("consume_seconds")) consumeSeconds = (Float) val;
            }

            return (T) Consumable.builder()
                    .consumeSeconds(consumeSeconds)
                    .animation(consumable.animation())
                    .sound(consumable.sound())
                    .hasConsumeParticles(consumable.hasConsumeParticles())
                    .build();
        }

        return existingComponent;
    }

    private static <T> boolean applyRule(ItemStack stack, CompoundTag tag, String ruleId, RuleData<?> rawRule, boolean isActive, boolean inPlayerInventory, boolean isEquipped) {
        @SuppressWarnings("unchecked")
        RuleData<T> rule = (RuleData<T>) rawRule;
        boolean changed = false;
        String hackedTag = "hacked_" + ruleId;

        if (isActive) {
            if (!isMatch(stack, rule)) return false;

            if (stack.has(rule.type()) && !tag.contains(hackedTag)) {
                T currentComponent = stack.get(rule.type());

                // Проверка на "девственность" компонента
                if (rule.requireDefault() && rule.targetItem() != null) {
                    T defaultComponent = rule.targetItem().components().get(rule.type());
                    if (defaultComponent == null || !currentComponent.equals(defaultComponent)) return false;
                }

                // ================= ВЫПОЛНЕНИЕ =================
                if (rule.subcomponentKeys() == null || rule.subcomponentKeys().isEmpty()) {
                    stack.remove(rule.type());
                    tag.putBoolean(hackedTag, true);
                    changed = true;
                } else {
                    T hackedComponent = modifySubcomponents(currentComponent, rule.subcomponentKeys());
                    if (hackedComponent != null && !hackedComponent.equals(currentComponent)) {
                        stack.set(rule.type(), hackedComponent);
                        tag.putBoolean(hackedTag, true);
                        changed = true;
                    }
                }
            }
        } else {
            // ================= ОТКАТ (Работает идеально для всего) =================
            if (tag.contains(hackedTag)) {
                // И для снятия баффов с брони, и для возврата урона мечам в сундуках
                // мы просто берем чистый исходник предмета из игры
                T vanillaComponent = stack.getItem().components().get(rule.type());
                if (vanillaComponent != null) {
                    stack.set(rule.type(), vanillaComponent);
                } else {
                    stack.remove(rule.type());
                }
                tag.remove(hackedTag);
                changed = true;
            }
        }

        return changed;
    }

    public static boolean isMatch(ItemStack stack, RuleData<?> rule) {
        if (stack.isEmpty() || !stack.has(rule.type())) return false;

        // 1. НЕЗАВИСИМЫЙ МЭТЧ ПО ПРЕДМЕТУ
        // Если в JSON указан target_item (например, diamond_sword), отсекаем всё остальное.
        if (rule.targetItem() != null && !stack.is(rule.targetItem())) {
            return false;
        }

        // 2. НЕЗАВИСИМЫЙ МЭТЧ ПО ДЕФОЛТНОСТИ
        // Если в JSON сказано require_default: true, проверяем "девственность" компонента.
        if (rule.requireDefault()) {
            Object currentComponent = stack.get(rule.type());

            // Берем ванильный чертеж ИМЕННО ЭТОГО предмета, который сейчас в радаре
            Object defaultComponent = stack.getItem().components().get(rule.type());

            // Если компонент изменен (зачарован, баффнут плагином), мы его не трогаем
            if (defaultComponent == null || !currentComponent.equals(defaultComponent)) {
                return false;
            }
        }

        return true; // Предмет прошел все активные фильтры
    }
}