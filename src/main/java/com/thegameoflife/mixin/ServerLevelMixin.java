package com.thegameoflife.mixin;

import com.thegameoflife.TheGameOfLifeMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    // 1. ЩИТ ОТ НОВЫХ УГРОЗ:
    // Перехватываем спавн новых мобов (команды, яйца призыва, спавнеры, естественный спавн ночью)
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void blockBannedEntitySpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {

        // Если тип моба в нашем глобальном бан-листе
        if (TheGameOfLifeMod.BANNED_ENTITIES.contains(entity.getType())) {
            entity.discard();          // Стираем моба из оперативной памяти
            cir.setReturnValue(false); // Жестко запрещаем игре добавлять его в мир
        }
    }

    // 2. ЩИТ ОТ ПРИЗРАКОВ ПРОШЛОГО:
    // Перехватываем загрузку уже существующих мобов, если игрок пришел в старый чанк
    @Inject(method = "addWithUUID", at = @At("HEAD"), cancellable = true)
    private void blockBannedEntityLoad(Entity entity, CallbackInfoReturnable<Boolean> cir) {

        if (TheGameOfLifeMod.BANNED_ENTITIES.contains(entity.getType())) {
            entity.discard();
            cir.setReturnValue(false);
        }
    }
}