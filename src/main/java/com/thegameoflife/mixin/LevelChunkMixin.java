package com.thegameoflife.mixin;

import com.thegameoflife.WorldHacker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public class LevelChunkMixin {

    // Перехватываем самое низкоуровневое изменение блока внутри чанка
    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void absoluteFreeze(BlockPos blockPos, BlockState blockState, int i, CallbackInfoReturnable<BlockState> cir) {

        // Получаем сам чанк, в котором мы сейчас находимся
        LevelChunk chunk = (LevelChunk) (Object) this;

        // Если чанк находится в мясорубке (окно сканирования) — блокируем ВСЁ
        if (WorldHacker.isChunkFrozen(chunk.getPos().toLong())) {

            // Возвращаем null. Игра будет думать, что блок не удалось поставить.
            // Вода попытается разлиться, ударится в эту стену и исчезнет.
            cir.setReturnValue(null);
        }
    }
}