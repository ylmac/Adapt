package com.ylmac.adapt.mixins.sodium;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.ylmac.adapt.render.lighting.AdaptShadowMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes Sodium terrain draws to the shadow / sky-occlusion target during caster passes. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass", remap = false)
public abstract class SodiumTerrainTargetMixin {

    @Inject(method = "getTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void adapt$shadowTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (AdaptShadowMap.isRenderingSky()) {
            // 天光可见度图（lightmap.y 的自建替代）优先判定：它也在
            // isRendering 窗口内，必须先分流，否则会画进方向影 target。
            cir.setReturnValue(AdaptShadowMap.skyTarget());
        } else if (AdaptShadowMap.isRendering()) {
            cir.setReturnValue(AdaptShadowMap.target());
        }
    }
}
