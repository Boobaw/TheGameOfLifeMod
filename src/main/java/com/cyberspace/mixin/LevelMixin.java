package com.cyberspace.mixin;

import com.cyberspace.hacker.WorldHacker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public class LevelMixin {

    // Перехватываем установку блоков ВО ВСЕМ МИРЕ
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void globalAntiPlace(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {

        // Если блок, который игра пытается поставить, забанен — бьем по рукам
        if (WorldHacker.BANNED_BLOCKS.contains(state.getBlock())) {
            cir.setReturnValue(false);
        }
    }
}
