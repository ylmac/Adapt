package com.ylmac.adapt.injection.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.ylmac.adapt.render.DeferredGBuffer;
import com.ylmac.adapt.render.ReimaginedCloudRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Executes Complementary Reimagined's deferred cloud phase inside Minecraft's single
 * main runnable, immediately after opaque terrain/features and before translucent
 * terrain/water. A FrameGraph pass outside this runnable would be too late.
 */
@Mixin(LevelRenderer.class)
public abstract class ReimaginedCloudPassMixin {

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
                            + "executeSolid()V",
                    shift = At.Shift.AFTER
            )
    )
    private void adapt$renderReimaginedClouds(
            GpuBufferSlice fog,
            LevelRenderState levelRenderState,
            ProfilerFiller profiler,
            ChunkSectionsToRender chunkSectionsToRender,
            ResourceHandle<?> entityOutline,
            PreparedFrame preparedFrame,
            ResourceHandle<?> translucent,
            ResourceHandle<RenderTarget> mainHandle,
            ResourceHandle<?> itemEntity,
            ResourceHandle<?> particles,
            CallbackInfo ci
    ) {
        // 总开关关闭：不跑实体 caster、不跑延迟光照、不画 Reimagined 云，
        // 主 target 就是原版 executeSolid 的结果。
        if (!com.ylmac.adapt.render.AdaptRenderToggle.isWorldEffectsEnabled()) {
            return;
        }
        // 实体/玩家 caster。必须在主渲染 executeSolid 之后、延迟光照之前：
        // 之后 → 本体一定已经画进主 target（上一版放在主渲染前，实测
        // "动物看不到，但看到了它们的影子"）；之前 → 光照 pass 采样 shadow
        // map 时实体那份深度已经在里面。
        profiler.push("adaptEntityShadow");
        com.ylmac.adapt.render.AdaptShadowMap.renderEntityShadow(preparedFrame);
        profiler.pop();
        profiler.push("adaptDeferredLighting");
        DeferredGBuffer.renderLighting(mainHandle.get());
        profiler.pop();
        profiler.push("reimaginedClouds");
        ReimaginedCloudRenderer.renderAfterOpaque(mainHandle.get());
        profiler.pop();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void adapt$closeReimaginedClouds(CallbackInfo ci) {
        ReimaginedCloudRenderer.close();
        DeferredGBuffer.close();
        // 体素遮挡场与光列表跟 LevelRenderer 生命周期，不能在
        // DeferredGBuffer.close() 里释放（那个会在窗口尺寸变化时被调用，
        // 同帧 use-after-free）。
        com.ylmac.adapt.render.VoxelOcclusionField.close();
        com.ylmac.adapt.render.LightListBuffer.close();
        com.ylmac.adapt.render.LightTileGrid.close();
        com.ylmac.adapt.render.AdaptDirectLighting.close();
    }
}
