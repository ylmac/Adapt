package com.ylmac.adapt.mixins.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.ylmac.adapt.render.lighting.AdaptEntityPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 实体/物品管线替换入口（26.3）。
 * <p>
 * 26.3 起 {@code RenderPass.setPipeline} 只接受 {@link CompiledRenderPipeline}，
 * 原版调用链是 {@code RenderSystem.getCompiledPipeline(RenderPipeline)} → setPipeline。
 * 在 lookup 前替换 {@link RenderPipeline}，GL/Vulkan 共用同一替换表。
 */
@Mixin(RenderSystem.class)
public abstract class AdaptRenderPassPipelineMixin {

    @ModifyVariable(
            method = "getCompiledPipelineNullable",
            at = @At("HEAD"),
            argsOnly = true
    )
    private static RenderPipeline adapt$swapPipeline(RenderPipeline pipeline) {
        return AdaptEntityPipelines.redirect(pipeline);
    }
}
