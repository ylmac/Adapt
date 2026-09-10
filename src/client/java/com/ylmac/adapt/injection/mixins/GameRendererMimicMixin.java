package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.combat.Mimic;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mimic 渲染帧 Mixin：在 GameRenderer.renderLevel 返回后（世界已渲染、GUI 未渲染，
 * 与 GameRendererMixin / GameRendererAimBacktrackMixin 同款注入点）回调
 * Mimic.onRenderFrame，把旋转增量按帧施加到玩家视角。
 * <p>
 * 渲染时机（已 javap 确证）：Minecraft.renderFrame 每帧调用 gameRenderer.render()，
 * renderLevel 每帧执行一次 —— 等价 Rise 的 Render2DEvent 逐帧驱动（26.2 的
 * Render2DEvent.Level 事件未被派发，逐帧驱动统一走 renderLevel RETURN 回调）。
 */
@Mixin(GameRenderer.class)
public class GameRendererMimicMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void adapt_mimicRenderFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
        Mimic mimic = Mimic.get();
        if (mimic != null) {
            mimic.onRenderFrame();
        }
    }
}
