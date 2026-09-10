package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.render.BedPlates;
import com.ylmac.adapt.features.module.impl.render.Halo3D;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 世界 3D feature 提交入口。
 * <p>
 * 26.2 的 LevelRenderer.render 顺序是 submitFeatures →
 * FeatureRenderDispatcher.prepareFrame(submitNodeStorage) → FrameGraph；
 * Halo3D 必须在 prepareFrame 之前写入当前 SubmitNodeStorage，不能像旧 Hats
 * 那样挂在 GameRenderer.renderLevel RETURN（那时本帧 storage 已经准备完毕）。
 */
@Mixin(LevelRenderer.class)
public abstract class Halo3DSubmitMixin {

    @Shadow
    @Final
    private SubmitNodeStorage submitNodeStorage;

    /**
     * 在 FeatureRenderDispatcher.prepareFrame 前追加 Halo3D/BedPlates 的当前帧提交。
     * 回调参数与 26.2 LevelRenderer.render 完整 descriptor 对齐；
     * submitNodeStorage 是本帧实际会被 FrameGraph 消费的 storage。
     */
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
            )
    )
    private void adapt_submitFeatures(
            GraphicsResourceAllocator resourceAllocator,
            DeltaTracker deltaTracker,
            boolean unusedFeatureFlag,
            CameraRenderState cameraState,
            Matrix4fc modelViewMatrix,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            CallbackInfo ci
    ) {
        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);

        Halo3D halo = Halo3D.get();
        if (halo != null) {
            halo.submit(this.submitNodeStorage, cameraState, partialTicks);
        }

        // BedPlates 原来借用旧 HatsRendererMixin；迁移到相同的、正确的
        // prepareFrame 前窗口，保留其 Gizmos 行为但不再依赖 Hats。
        BedPlates bedPlates = BedPlates.get();
        if (bedPlates != null) {
            bedPlates.onRenderLevel(partialTicks);
        }
    }

}
