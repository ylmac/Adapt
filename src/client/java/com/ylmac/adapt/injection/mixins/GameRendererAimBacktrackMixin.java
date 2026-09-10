package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.ghost.AimBacktrack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AimBacktrack 渲染 Mixin：在 GameRenderer.renderLevel 返回后（世界已渲染、
 * GUI 未渲染，与 GameRendererMixin 同款注入点）回调 AimBacktrack.onRenderLevel，
 * 提交 vanilla Gizmos 回溯目标线框。
 * <p>
 * 渲染时机（已 javap 确证）：Minecraft.renderFrame 里
 * collectPerFrameMainThreadGizmos() 在帧首激活收集器，持续到 gameRenderer.render()
 * 结束；LevelRenderer.render → submitBlockOutline → finalizeGizmoCollection 消费
 * 收集器 —— 本注入点（renderLevel RETURN）处于激活窗口内，Gizmos.cuboid
 * 会被当前帧渲染。
 */
@Mixin(GameRenderer.class)
public class GameRendererAimBacktrackMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void adapt_aimBacktrackRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        AimBacktrack aimBacktrack = AimBacktrack.get();
        if (aimBacktrack != null) {
            aimBacktrack.onRenderLevel();
        }
    }
}
