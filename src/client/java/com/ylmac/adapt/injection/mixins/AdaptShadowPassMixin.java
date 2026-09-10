package com.ylmac.adapt.injection.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.ylmac.adapt.render.AdaptShadowMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the directional shadow caster before the normal opaque world pass. */
@Mixin(LevelRenderer.class)
public abstract class AdaptShadowPassMixin {

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
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
                            + "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                            + "Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            )
    )
    private void adapt$renderShadow(
            GpuBufferSlice fog,
            LevelRenderState state,
            ProfilerFiller profiler,
            ChunkSectionsToRender sections,
            ResourceHandle<?> entityOutline,
            PreparedFrame preparedFrame,
            ResourceHandle<?> translucent,
            ResourceHandle<RenderTarget> mainHandle,
            ResourceHandle<?> itemEntity,
            ResourceHandle<?> particles,
            CallbackInfo ci
    ) {
        CameraRenderState camera = state.cameraRenderState;
        // 总开关关闭：不建 shadow map、不跑 caster，方向影完全不存在。
        if (!com.ylmac.adapt.render.AdaptRenderToggle.isWorldEffectsEnabled()) {
            return;
        }
        AdaptShadowMap.updateFrame(net.minecraft.client.Minecraft.getInstance().level, camera, 0.0f);
        profiler.push("adaptShadowMap");
        // 地形 + 实体都画进 shadow map（Beryl ShadowMap.renderShadowMap 语义：
        // 先地形 caster、再实体 renderAllFeatures；实体 caster 管线
        // adapt_entity_shadow 自带 ModelViewMat→ViewToWorld→LightViewProjection
        // 完整矩阵链）。主渲染 executeSolid 会把同一批实体再画一遍进 main
        // target —— 正确且必须（Beryl 同款）。blob shadow 已由 BlobShadowMixin
        // 移除，方向影是实体唯一地面阴影。
        AdaptShadowMap.renderTerrainShadow(sections, camera, preparedFrame);
        profiler.pop();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void adapt$closeShadow(CallbackInfo ci) {
        AdaptShadowMap.close();
    }
}
