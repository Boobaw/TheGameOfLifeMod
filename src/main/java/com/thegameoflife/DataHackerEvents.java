package com.thegameoflife;

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

            // 1. ПРОВЕРКА НА ВЗЛОМ: Есть ли на предмете искусственно добавленные нами компоненты?
            boolean isHacked = false;
            for (String key : tag.keySet()) {
                if (key.startsWith("added_")) {
                    isHacked = true;
                    break;
                }
            }

            // Если предмет чистый (ванильное яблоко и т.д.) - пропускаем, пусть ядро само убавляет стаки!
            if (!isHacked) return InteractionResult.PASS;

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