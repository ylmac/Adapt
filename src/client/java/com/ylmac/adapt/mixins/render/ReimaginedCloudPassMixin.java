package com.ylmac.adapt.mixins.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.ylmac.adapt.render.lighting.DeferredGBuffer;
import com.ylmac.adapt.render.lighting.ReimaginedCloudRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3：挂在 {@code LevelRenderer.executeSolid} 里
 * {@code PreparedFrame.executeSolid} 之后，立刻做实体阴影 / 延迟光照 / 云。
 */
@Mixin(LevelRenderer.class)
public abstract class ReimaginedCloudPassMixin {

    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Inject(
            method = "executeSolid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
                            + "executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void adapt$renderReimaginedClouds(
            ChunkSectionsToRender chunkSectionsToRender,
            PreparedFrame preparedFrame,
            RenderPass pass,
            CallbackInfo ci
    ) {
        if (!com.ylmac.adapt.render.lighting.AdaptRenderToggle.isWorldEffectsEnabled()) {
            return;
        }
        ProfilerFiller profiler = net.minecraft.util.profiling.Profiler.get();
        profiler.push("adaptEntityShadow");
        com.ylmac.adapt.render.lighting.AdaptShadowMap.renderEntityShadow(preparedFrame);
        profiler.pop();
        RenderTarget main = (RenderTarget) this.targets.main.get();
        profiler.push("adaptDeferredLighting");
        DeferredGBuffer.renderLighting(main);
        profiler.pop();
        profiler.push("reimaginedClouds");
        ReimaginedCloudRenderer.renderAfterOpaque(main);
        profiler.pop();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void adapt$closeReimaginedClouds(CallbackInfo ci) {
        ReimaginedCloudRenderer.close();
        DeferredGBuffer.close();
        com.ylmac.adapt.render.lighting.VoxelOcclusionField.close();
        com.ylmac.adapt.render.lighting.LightListBuffer.close();
        com.ylmac.adapt.render.lighting.LightTileGrid.close();
        com.ylmac.adapt.render.lighting.AdaptDirectLighting.close();
    }
}
