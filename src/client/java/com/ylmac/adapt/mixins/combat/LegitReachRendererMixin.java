package com.ylmac.adapt.mixins.combat;

import com.ylmac.adapt.module.combat.LegitReach;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LegitReach 渲染 Mixin：在 GameRenderer.renderLevel 返回后（世界已渲染、
 * GUI 未渲染，与 GameRendererAimBacktrackMixin 同款注入点）回调
 * LegitReach.onRenderLevel，提交 vanilla Gizmos 真实位置线框。
 * <p>
 * 渲染时机（已 javap 确证）：Minecraft.renderFrame 里
 * collectPerFrameMainThreadGizmos 在帧首激活收集器，持续到 gameRenderer.render
 * 结束；LevelRenderer.render → submitBlockOutline → finalizeGizmoCollection 消费
 * 收集器 — 本注入点（renderLevel RETURN）处于激活窗口内，Gizmos.cuboid
 * 会被当前帧渲染。
 * <p>
 * 26.3：{@code renderLevel} 无参数。
 */
@Mixin(GameRenderer.class)
public class LegitReachRendererMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void adapt_legitReachRender(CallbackInfo ci) {
        LegitReach reach = LegitReach.get();
        if (reach != null) {
            reach.onRenderLevel();
        }
    }
}
