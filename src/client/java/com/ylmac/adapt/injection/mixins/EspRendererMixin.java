package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.render.ESP;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ESP 3D 渲染 Mixin：在 GameRenderer.renderLevel 返回后回调 ESP.onRenderLevel，
 * 提交 vanilla Gizmos 3D 包围盒（Box 模式）。
 * <p>
 * 同时把当前帧相机位置传给 ESP（供距离过滤），与 HatsRendererMixin /
 * LegitReachRendererMixin 同款注入点。
 * <p>
 * 26.3：{@code renderLevel()} 无参数；partialTicks 从
 * {@code Minecraft.getDeltaTracker()} 取。
 */
@Mixin(GameRenderer.class)
public class EspRendererMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void adapt_espRender(CallbackInfo ci) {
        ESP esp = ESP.get();
        if (esp == null) return;
        float partialTicks = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 camPos = Vec3.ZERO;
        try {
            CameraRenderState cameraState = Minecraft.getInstance()
                    .gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            if (cameraState != null && cameraState.pos != null) {
                camPos = cameraState.pos;
            }
        } catch (Throwable ignored) {
            // 相机状态不可用时退化为 Vec3.ZERO（距离过滤会保守通过）
        }
        esp.onRenderLevel(partialTicks, camPos);
    }
}
