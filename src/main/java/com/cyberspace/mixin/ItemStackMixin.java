package com.cyberspace.mixin;

import com.cyberspace.hacker.DataHacker;
import com.cyberspace.CyberSpaceMod;
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

    @Inject(method = "set", at = @At("RETURN"))
    private void autoPatchOnSet(DataComponentType<?> type, Object value, CallbackInfoReturnable<Object> cir) {
        // 1. ЩИТ: Работаем ТОЛЬКО на сервере и только когда он полностью запущен.
        // Игнорируем отрисовку UI клиента (Render thread) и генерацию мира!
        if (CyberSpaceMod.SERVER == null || !Thread.currentThread().getName().equals("Server thread")) return;

        // 2. Защита от рекурсии Конвейера
        if (type == DataComponents.CUSTOM_DATA || DataHacker.isProcessing) return;

        ItemStack stack = (ItemStack) (Object) this;

        // Записываем дифф и МОМЕНТАЛЬНО бьем конвейером (Предметы меняются ВЕЗДЕ)
        DataHacker.recordPlayerDiff(stack, type, value);
        if (!this.isEmpty()) {
            DataHacker.processItemStack(stack, false, false);
        }
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void autoPatchOnRemove(DataComponentType<?> type, CallbackInfoReturnable<Object> cir) {
        if (CyberSpaceMod.SERVER == null || !Thread.currentThread().getName().equals("Server thread")) return;
        if (type == DataComponents.CUSTOM_DATA || DataHacker.isProcessing) return;

        ItemStack stack = (ItemStack) (Object) this;
        DataHacker.recordPlayerDiff(stack, type, null);
        if (!this.isEmpty()) {
            DataHacker.processItemStack(stack, false, false);
        }
    }
}