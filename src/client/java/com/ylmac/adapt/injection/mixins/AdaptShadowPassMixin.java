package com.ylmac.adapt.injection.mixins;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.ylmac.adapt.render.AdaptShadowMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3：阴影 caster 挂在 {@code executeSolid} 里第一次 {@code renderGroup}
 * 之前（不再塞进已精简的 {@code addMainPass} lambda）。
 */
@Mixin(LevelRenderer.class)
public abstract class AdaptShadowPassMixin {

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(
            method = "executeSolid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup("
                            + "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                            + "Lcom/mojang/renderpearl/api/commands/RenderPass;"
                            + "Lcom/mojang/renderpearl/api/textures/GpuSampler;"
                            + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            )
    )
    private void adapt$renderShadow(
            ChunkSectionsToRender sections,
            PreparedFrame preparedFrame,
            RenderPass pass,
            CallbackInfo ci
    ) {
        if (!com.ylmac.adapt.render.AdaptRenderToggle.isWorldEffectsEnabled()) {
            return;
        }
        CameraRenderState camera = this.levelRenderState.cameraRenderState;
        AdaptShadowMap.updateFrame(net.minecraft.client.Minecraft.getInstance().level, camera, 0.0f);
        ProfilerFiller profiler = net.minecraft.util.profiling.Profiler.get();
        profiler.push("adaptShadowMap");
        AdaptShadowMap.renderTerrainShadow(sections, camera, preparedFrame);
        profiler.pop();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void adapt$closeShadow(CallbackInfo ci) {
        AdaptShadowMap.close();
    }
}
