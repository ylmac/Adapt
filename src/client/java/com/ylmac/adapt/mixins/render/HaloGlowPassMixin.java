package com.ylmac.adapt.mixins.render;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.ylmac.adapt.render.halo.HaloGlowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3：实体通道在 {@code RenderPass.close} 之后才关掉。清屏必须等这次 close，
 * 否则 {@code clearColorAndDepthTextures} 会在通道还开着时再开一次。
 */
@Mixin(LevelRenderer.class)
public abstract class HaloGlowPassMixin {

    @Shadow
    @Final
    private LevelTargetBundle targets;

    private static final String ADD_MAIN_PASS_LAMBDA =
            "lambda$addMainPass$0(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Z"
                    + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
                    + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;ZZ)V";

    @Inject(
            method = ADD_MAIN_PASS_LAMBDA,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/commands/RenderPass;close()V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void adapt_prepareHaloMask(
            GpuBufferSlice fog,
            boolean improvedTransparency,
            ChunkSectionsToRender chunkSectionsToRender,
            PreparedFrame preparedFrame,
            boolean hasAlwaysOnTop,
            boolean alwaysOnTopFlag,
            CallbackInfo ci
    ) {
        if (HaloGlowRenderer.hasMask()) {
            HaloGlowRenderer.prepareMask((RenderTarget) this.targets.main.get());
        }
    }

    @Inject(method = "addMainPass", at = @At("RETURN"))
    private void adapt_addHaloGlowPass(
            FrameGraphBuilder graph,
            PreparedFrame preparedFrame,
            GpuBufferSlice fog,
            ChunkSectionsToRender sections,
            boolean improvedTransparency,
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
