package com.thegameoflife;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;

public class DataHacker {

    // =========================================
    // ГЛОБАЛЬНАЯ ЭПОХА И ПРАВИЛА
    // =========================================
    public static int DATA_EPOCH = 0;

    public record RuleData<T>(DataComponentType<T> type, T value) {}

    public static final Map<String, RuleData<?>> REGISTERED_RULES = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    public static final java.util.List<String> ACTIVE_RULES = new java.util.concurrent.CopyOnWriteArrayList<>();

    // =========================================
    // БИНАРНЫЙ РУБИЛЬНИК
    // =========================================
    public static <T> void toggleRule(MinecraftServer server, String ruleId, DataComponentType<T> type, T value) {
        REGISTERED_RULES.putIfAbsent(ruleId, new RuleData<>(type, value));

        if (ACTIVE_RULES.contains(ruleId)) {
            ACTIVE_RULES.remove(ruleId);
            System.out.println("[DataHacker] Правило " + ruleId + " ОТКЛЮЧЕНО.");
        } else {
            ACTIVE_RULES.add(ruleId);
            System.out.println("[DataHacker] Правило " + ruleId + " ВКЛЮЧЕНО.");
        }

        // Повышаем эпоху! Предметы с устаревшим паспортом отправятся на хирургический стол.
        DATA_EPOCH++;
    }

    // =========================================
    // ОБРАБОТЧИКИ ИЗ КОНВЕЙЕРА GLOBAL RADAR
    // =========================================

    public static void processPlayerInventory(ServerPlayer player) {
        if (REGISTERED_RULES.isEmpty()) return;
        boolean invChanged = false;

        // ВАЖНО: В некоторых маппингах getSelectedSlot(), в других selected. Оставил твой вариант!

        // ПРОХОД А: СМЕРТНЫЕ (Рюкзак)
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            boolean isEquipped = (i == player.getInventory().getSelectedSlot()) || (i >= 36);
            if (!isEquipped && processItemStack(player.getInventory().getItem(i), false)) {
                invChanged = true;
            }
        }

        // ПРОХОД Б: БОГИ (Руки и Броня)
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            boolean isEquipped = (i == player.getInventory().getSelectedSlot()) || (i >= 36);
            if (isEquipped && processItemStack(player.getInventory().getItem(i), true)) {
                invChanged = true;
            }
        }

        if (invChanged) player.inventoryMenu.broadcastChanges();
    }

    public static void processBlockEntity(BlockEntity be) {
        if (be instanceof Container container) {
            boolean changed = false;
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (processItemStack(container.getItem(i), false)) changed = true;
            }
            if (changed) be.setChanged();
        }
    }

    public static void processEntity(Entity e) {
        if (e instanceof ServerPlayer) return;

        if (e instanceof ItemEntity item) {
            processItemStack(item.getItem(), false);
        } else if (e instanceof ItemFrame frame) {
            processItemStack(frame.getItem(), false);
        } else if (e instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                processItemStack(living.getItemBySlot(slot), false);
            }
        }
    }

    // =========================================
    // ХИРУРГИЧЕСКИЙ СТОЛ (Ядро DataHacker)
    // =========================================
    public static boolean isProcessing = false;

    public static boolean processItemStack(ItemStack stack, boolean isEquipped) {
        if (stack.isEmpty() || isProcessing) return false;

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : new CompoundTag();

        // 1. ПРОВЕРКА ТЕНЕВОГО ПАСПОРТА
        int itemEpoch = tag.getInt("datahacker_epoch").orElse(0);

        if (itemEpoch == DATA_EPOCH) return false;

        isProcessing = true;
        boolean changed = false;

        try {
            // 2. ПЕРЕСЧЕТ ВСЕХ ПРАВИЛ
            for (Map.Entry<String, RuleData<?>> entry : REGISTERED_RULES.entrySet()) {
                try {
                    changed |= applyRule(stack, tag, isEquipped, entry.getKey(), entry.getValue(), ACTIVE_RULES.contains(entry.getKey()));
                } catch (Exception ex) {
                    // Игнорируем краш конкретного правила на сломанном предмете
                }
            }

            // 3. ОБНОВЛЕНИЕ ПАСПОРТА
            tag.putInt("datahacker_epoch", DATA_EPOCH);

            if (tag.isEmpty()) {
                stack.remove(DataComponents.CUSTOM_DATA);
            } else {
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            }

            changed = true;

        } finally {
            isProcessing = false;
        }

        return changed;
    }

    // =========================================
    // ЛОГИКА ПРАВИЛ (Идеальная память компонентов)
    // =========================================
    @SuppressWarnings("unchecked")
    private static <T> boolean applyRule(ItemStack stack, CompoundTag tag, boolean isEquipped, String ruleId, RuleData<?> rawRule, boolean isActive) {
        RuleData<T> rule = (RuleData<T>) rawRule;
        boolean changed = false;

        String buffTag = "buff_" + ruleId;
        String nerfTag = "nerf_" + ruleId;

        if (isActive) {
            if (isEquipped) {
                // ================= ПУТЬ БОГОВ =================
                // 1. Восстанавливаем память: Если предмет был занерфлен в сундуке, возвращаем родное свойство
                if (tag.contains(nerfTag)) {
                    stack.set(rule.type(), rule.value());
                    tag.remove(nerfTag);
                    changed = true;
                }

                // 2. Накладываем бафф, если его еще нет
                T currentVal = stack.get(rule.type());
                if (currentVal == null || !currentVal.equals(rule.value())) {
                    stack.set(rule.type(), rule.value());
                    tag.putBoolean(buffTag, true);
                    changed = true;
                }
            } else {
                // ================= ПУТЬ СМЕРТНЫХ =================
                // 1. Восстанавливаем память: Если мы наложили бафф в руках, стираем его, чтобы вернуть предмет в исходный вид
                if (tag.contains(buffTag)) {
                    stack.remove(rule.type());
                    tag.remove(buffTag);
                    changed = true;
                }

                // 2. Ищем ИДЕАЛЬНОЕ СОВПАДЕНИЕ и отбираем родное свойство.
                // Заметь: если бафф был стерт шагом выше, currentVal будет null, и мы не повесим ошибочный nerfTag!
                T currentVal = stack.get(rule.type());
                if (currentVal != null && currentVal.equals(rule.value())) {
                    stack.remove(rule.type());
                    tag.putBoolean(nerfTag, true);
                    changed = true;
                }
            }
        } else {
            // ================= ОТМЕНА ПРАВИЛА =================
            if (tag.contains(buffTag)) {
                stack.remove(rule.type());
                tag.remove(buffTag);
                changed = true;
            }
            if (tag.contains(nerfTag)) {
                stack.set(rule.type(), rule.value());
                tag.remove(nerfTag);
                changed = true;
            }
        }

        return changed;
    }
}