package com.thegameoflife.mixin;

import com.thegameoflife.TheGameOfLifeMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    // Перехват появления НОВЫХ сущностей (взрывы сундуков, дроп, спавн мобов)
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void onAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (handleBannedEntity(entity)) {
            cir.setReturnValue(false); // Жестко блокируем добавление в мир
        }
    }

    // Перехват загрузки СТАРЫХ сущностей (когда игрок прогружает чанк из сохранения)
    @Inject(method = "addWithUUID", at = @At("HEAD"), cancellable = true)
    private void onAddWithUUID(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (handleBannedEntity(entity)) {
            cir.setReturnValue(false); // Жестко блокируем добавление в мир
        }
    }

    /**
     * Универсальный и умный обработчик.
     * Возвращает TRUE, если сущность нужно полностью уничтожить и запретить её появление.
     * Возвращает FALSE, если сущность безопасна (или мы её безопасно "обезвредили").
     */
    private boolean handleBannedEntity(Entity entity) {
        // 1. Глобальный бан мобов (Вардены, Зомби и т.д.)
        if (TheGameOfLifeMod.BANNED_ENTITIES.contains(entity.getType())) {
            entity.discard();
            return true;
        }

        // 2. Глобальный бан физических предметов (дроп на земле)
        if (entity instanceof ItemEntity itemEntity) {
            if (TheGameOfLifeMod.BANNED_ITEMS.contains(itemEntity.getItem().getItem())) {
                itemEntity.discard();
                return true;
            }
        }

        // 3. Ювелирная работа с рамками (ItemFrame и GlowItemFrame)
        if (entity instanceof ItemFrame itemFrame) {
            if (TheGameOfLifeMod.BANNED_ITEMS.contains(itemFrame.getItem().getItem())) {
                itemFrame.setItem(ItemStack.EMPTY);
                // Возвращаем FALSE! Сама пустая рамка имеет право на существование
                return false;
            }
        }

        return false;
    }
}