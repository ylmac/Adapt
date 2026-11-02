package com.ylmac.adapt.mixins.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.ylmac.adapt.render.lighting.DeferredGBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 地形 opaque 组标记（延迟渲染用）。
 * <p>
 * 26.3：{@code renderGroup} 改为接收外部 {@link RenderPass}，不再自建
 * pass / 内调 {@code setPipeline}，因此旧的 createRenderPass / setPipeline
 * Redirect 已移除。G-buffer 多附件改道需改挂到
 * {@code LevelRenderer} 创建 pass 处（后续单独做）；这里只维护
 * OpaqueActive 标志供光照/阴影路径查询。
 */
@Mixin(value = ChunkSectionsToRender.class, priority = 2000)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderGroup", at = @At("HEAD"))
    private void adapt$gbufferGroupBegin(
            ChunkSectionLayerGroup group,
            RenderPass pass,
            GpuSampler sampler,
            GpuTextureView textureView,
            boolean wireframe,
            CallbackInfo ci
    ) {
        boolean opaque = com.ylmac.adapt.render.lighting.AdaptRenderToggle.isWorldEffectsEnabled()
                && group == ChunkSectionLayerGroup.OPAQUE;
        DeferredGBuffer.setOpaqueActive(opaque);
        if (opaque) {
            DeferredGBuffer.onOpaqueGroupBegin();
        }
    }

    @Inject(method = "renderGroup", at = @At("RETURN"))
    private void adapt$gbufferGroupEnd(
            ChunkSectionLayerGroup group,
            RenderPass pass,
            GpuSampler sampler,
            GpuTextureView textureView,
            boolean wireframe,
            CallbackInfo ci
    ) {
        DeferredGBuffer.setOpaqueActive(false);
    }
}
