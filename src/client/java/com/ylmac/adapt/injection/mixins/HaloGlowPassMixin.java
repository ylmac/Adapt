package com.ylmac.adapt.injection.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.ylmac.adapt.render.HaloGlowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class HaloGlowPassMixin {

    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Inject(
            method = "lambda$addMainPass$0(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                    + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                    + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                    + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
                            + "executeTranslucent()V"
            )
    )
    private void adapt_prepareHaloMask(
            GpuBufferSlice fog,
            LevelRenderState levelRenderState,
            ProfilerFiller profiler,
            ChunkSectionsToRender chunkSectionsToRender,
            ResourceHandle entityOutline,
            PreparedFrame preparedFrame,
            ResourceHandle translucent,
            ResourceHandle mainHandle,
            ResourceHandle itemEntity,
            ResourceHandle particles,
            CallbackInfo ci
    ) {
        if (HaloGlowRenderer.hasMask()) {
            HaloGlowRenderer.prepareMask((RenderTarget) mainHandle.get());
        }
    }

    @Inject(method = "addAlwaysOnTopPass", at = @At("RETURN"))
    private void adapt_addHaloGlowPass(
            FrameGraphBuilder graph,
            PreparedFrame preparedFrame,
            GpuBufferSlice fog,
            CallbackInfo ci
    ) {
        if (!HaloGlowRenderer.hasMask()) {
            return;
        }

        RenderTarget mainTarget = (RenderTarget) this.targets.main.get();
        HaloGlowRenderer.addToFrame(
                graph,
                this.targets.main,
                mainTarget.width,
                mainTarget.height,
                handle -> this.targets.main = handle
        );
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void adapt_closeHaloGlow(CallbackInfo ci) {
        HaloGlowRenderer.close();
    }
}
