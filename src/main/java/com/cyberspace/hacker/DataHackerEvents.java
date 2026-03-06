package com.cyberspace.hacker;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;

public class DataHackerEvents {

    public static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty()) return InteractionResult.PASS;

            CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag tag = customData.copyTag();

            // =========================================
            // 1. ПРОВЕРКА НА ВЗЛОМ (Синхронизация с DataHacker)
            // =========================================
            // Ищем следы вмешательства: либо бан (hacked_), либо бафф (buffed_)
            boolean isMutated = false;
            for (String key : tag.keySet()) { // Если IDE ругается, замени keySet() на getAllKeys()
                if (key.startsWith("hacked_") || key.startsWith("buffed_")) {
                    isMutated = true;
                    break;
                }
            }

            // Если предмет девственно чист - пропускаем, пусть ядро само разбирается
            if (!isMutated) return InteractionResult.PASS;

            // =========================================
            // 2. ИСПОЛНЕНИЕ ДОБАВЛЕННЫХ КОМПОНЕНТОВ
            // =========================================

            var consumable = stack.get(DataComponents.CONSUMABLE);
            if (consumable != null) {
                InteractionResult result = consumable.startConsuming(player, stack, hand);
                if (result.consumesAction()) {
                    return result;
                }
            }

            if (stack.has(DataComponents.BLOCKS_ATTACKS)) {
                player.startUsingItem(hand);
                return InteractionResult.CONSUME;
            }

            var kinetic = stack.get(DataComponents.KINETIC_WEAPON);
            if (kinetic != null) {
                player.startUsingItem(hand);
                kinetic.makeSound(player);
                return InteractionResult.CONSUME;
            }

            return InteractionResult.PASS;
        });
    }
}