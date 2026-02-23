package com.thegameoflife.mixin;

import com.thegameoflife.TheGameOfLifeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
public class FlowingFluidMixin {

    // Врезаемся в тик ВСЕХ жидкостей на сервере
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void globalFluidBanTick(ServerLevel serverLevel, BlockPos blockPos, BlockState blockState, FluidState fluidState, CallbackInfo ci) {

        // Достаем блок из состояния жидкости (например, Blocks.WATER)
        // ИСПОЛЬЗУЕМ fluidState ВМЕСТО state!
        // Если он в бане — отменяем его тик. Вода превращается в неподвижный камень.
        if (TheGameOfLifeMod.BANNED_BLOCKS.contains(fluidState.createLegacyBlock().getBlock())) {
            ci.cancel();
        }
    }
}
