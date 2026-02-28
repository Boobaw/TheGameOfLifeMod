package com.thegameoflife.mixin;

import com.thegameoflife.DataHacker;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
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
     * СТИРАЕМ ДЖЕНЕРИКИ: Используем <?> и Object, чтобы соответствовать байт-коду JVM.
     * CallbackInfoReturnable тоже типизируем как <Object>.
     */
    @Inject(method = "set", at = @At("HEAD"))
    private void autoPatchOnSet(DataComponentType<?> type, Object value, CallbackInfoReturnable<Object> cir) {

        // 1. ЗАЩИТА ОТ РЕКУРСИИ
        // Если DataHacker сам записывает свой паспорт в предмет - мы игнорируем этот вызов!
        if (type == DataComponents.CUSTOM_DATA) {
            return;
        }

        // 2. БЫСТРАЯ ПРОВЕРКА
        if (!this.isEmpty()) {
            DataHacker.processItemStack((ItemStack) (Object) this, false, false);
        }
    }
}