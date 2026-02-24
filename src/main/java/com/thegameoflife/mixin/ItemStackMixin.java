package com.thegameoflife.mixin;

import com.thegameoflife.DataHacker;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Shadow public abstract boolean isEmpty();

    /**
     * Исправляем ошибку со скриншота: используем инъекцию в метод 'set'.
     * Это гарантирует, что как только предмет получает данные (например, из сундука),
     * мы проверяем его на соответствие правилам.
     */
    @Inject(method = "set", at = @At("HEAD"))
    private <T> void autoPatchOnSet(DataComponentType<T> type, T value, CallbackInfoReturnable<T> cir) {
        if (!this.isEmpty()) {
            // Безусловная проверка правил при любом обновлении данных
            DataHacker.processItemStack((ItemStack) (Object) this, false);
        }
    }
}