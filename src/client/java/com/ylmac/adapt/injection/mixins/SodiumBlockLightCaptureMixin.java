package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.render.BlockLightSourceCache;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures real BlockState light sources at Sodium's chunk-build boundary.
 * The capture happens after renderModel has stored its current state/position and
 * emitted the model, while the original BlockState identity is still available.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumBlockLightCaptureMixin {
    @Inject(method = "prepare", at = @At("HEAD"), remap = false)
    private void adapt$beginBlockCapture(CallbackInfo ci) {
        BlockLightSourceCache.beginBuild();
    }

    /**
     * Capture before the model is emitted. renderModel already provides the real
     * model BlockState and the world BlockPos; no post-lighting inference is used.
     */
    @Inject(method = "renderModel", at = @At("HEAD"), remap = false)
    private void adapt$captureBlockState(BlockStateModel model, BlockState state, BlockPos pos,
                                         BlockPos origin, CallbackInfo ci) {
        BlockLightSourceCache.capture(state, pos);
    }

    @Inject(method = "release", at = @At("HEAD"), remap = false)
    private void adapt$publishBlockCapture(CallbackInfo ci) {
        BlockLightSourceCache.endBuild();
    }
}
