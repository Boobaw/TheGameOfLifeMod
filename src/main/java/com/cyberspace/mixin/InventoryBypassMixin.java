package com.cyberspace.mixin;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Inventory.class)
public class InventoryBypassMixin {

    // Глушим ванильную паранойю: запрещаем инвентарю "выплевывать" или дюпать
    // предметы, если их структура компонентов была жестоко изуродована нашим модом.
    @Inject(method = "placeItemBackInInventory", at = @At("HEAD"), cancellable = true)
    private void stopItemDupeOnCorruption(ItemStack stack, CallbackInfo ci) {
        // Если на предмете висит наша метка взлома - запрещаем движку перемещать его
        // или создавать копии "в целях безопасности". Пусть лежит сломанным там, где лежал!
        if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
            var tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
            // Проверяем наличие ЛЮБОГО нашего тега
            if (tag.keySet().stream().anyMatch(key -> key.startsWith("hacked_") || key.startsWith("buffed_"))) {
                ci.cancel();
            }
        }
    }
}