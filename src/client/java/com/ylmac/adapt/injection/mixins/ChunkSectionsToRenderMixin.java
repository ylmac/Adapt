package com.ylmac.adapt.injection.mixins;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.ylmac.adapt.render.AdaptEntityPipelines;
import com.ylmac.adapt.render.DeferredGBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * 地形 opaque pass 改道 G-buffer（延迟渲染）。
 * <p>
 * {@code ChunkSectionsToRender.renderGroup(OPAQUE, …)} 内部自建 RenderPass
 * （outputTarget() → color/depth view → CommandEncoder.createRenderPass）；
 * 这里在 OPAQUE 组时把颜色附件换成 G-buffer 四目标（深度复用主深度、不重清），
 * 半透明组保持原样。光照在全屏 light pass（DeferredGBuffer.renderLighting）
 * 中完成，由 ReimaginedCloudPassMixin 在 executeSolid 之后调用。
 * <p>
 * priority=2000：Sodium 的 sodium$renderGroup 也是 @Inject HEAD（默认 priority
 * 1000，cancellable，cancel 后同步画完全部地形）。同一注入点的 handler 按
 * priority 决定先后——本类的 HEAD flag 必须先写入，Sodium 的画图才吃到
 * isOpaqueActive()=true，否则 SOLID/CUTOUT 中某一层绕开 G-buffer（表现为
 * "只有部分方块 coverage / 世界透明"）。
 */
@Mixin(value = ChunkSectionsToRender.class, priority = 2000)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderGroup", at = @At("HEAD"))
    private void adapt$gbufferGroupBegin(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        // 总开关关闭：OpaqueActive 恒 false → 下面两个 redirect 都原样放行，
        // 地形照常画进主 target（纯原版路径）。
        boolean opaque = com.ylmac.adapt.render.AdaptRenderToggle.isWorldEffectsEnabled()
                && group == ChunkSectionLayerGroup.OPAQUE;
        DeferredGBuffer.setOpaqueActive(opaque);
        if (opaque) {
            DeferredGBuffer.onOpaqueGroupBegin();
        }
    }

    @Inject(method = "renderGroup", at = @At("RETURN"))
    private void adapt$gbufferGroupEnd(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        DeferredGBuffer.setOpaqueActive(false);
    }

    /**
     * Sodium 自己 mixin 进 renderGroup 的 handler（handler$dan000$sodium$renderGroup）
     * 会在内部直接 setPipeline(原始 sodium 管线)——那个调用点不走
     * DefaultChunkRenderer.render，管线替换 mixin 覆盖不到。opaque 时在这里
     * 把管线也换成 G-buffer 变体（4 color target），否则 4 附件 pass 配 1-target
     * 管线会抛 "color attachment count must match"。
     */
    @Redirect(
            method = "renderGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
            )
    )
    private void adapt$gbufferPipeline(RenderPass pass, RenderPipeline pipeline) {
        if (DeferredGBuffer.isOpaqueActive()) {
            pass.setPipeline(AdaptEntityPipelines.redirectSodiumTerrain(pipeline, false));
        } else {
            pass.setPipeline(pipeline);
        }
    }

    @Redirect(
            method = "renderGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
            )
    )
    private RenderPass adapt$gbufferOpaquePass(CommandEncoder encoder, Supplier<String> label,
                                               GpuTextureView colorTarget, Optional<Vector4fc> clearColor,
                                               GpuTextureView depthTarget, OptionalDouble clearDepth) {
        if (DeferredGBuffer.isOpaqueActive()) {
            return DeferredGBuffer.createOpaquePass(encoder, label, depthTarget);
        }
        return encoder.createRenderPass(label, colorTarget, clearColor, depthTarget, clearDepth);
    }
}
