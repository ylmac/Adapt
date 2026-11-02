package com.ylmac.adapt.mixins.render;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.ylmac.adapt.module.render.BedPlates;
import com.ylmac.adapt.module.render.Halo3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3：{@code LevelRenderer.render} 签名去掉了 DeltaTracker / Matrix4fc，
 * partialTicks 从 {@code Minecraft.getDeltaTracker} 取。
 */
@Mixin(LevelRenderer.class)
public abstract class Halo3DSubmitMixin {

    @Shadow
    @Final
    private SubmitNodeStorage submitNodeStorage;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame("
                            + "Lnet/minecraft/client/renderer/SubmitNodeStorage;)"
                            + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
            )
    )
    private void adapt_submitFeatures(
            GraphicsResourceAllocator resourceAllocator,
            boolean unusedFeatureFlag,
            CameraRenderState cameraState,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            boolean unusedFlag,
            CallbackInfo ci
    ) {
        float partialTicks = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);

        Halo3D halo = Halo3D.get();
        if (halo != null) {
            halo.submit(this.submitNodeStorage, cameraState, partialTicks);
        }

        BedPlates bedPlates = BedPlates.get();
        if (bedPlates != null) {
            bedPlates.onRenderLevel(partialTicks);
        }
    }
}
