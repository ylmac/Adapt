package com.ylmac.adapt.injection.mixins;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.ylmac.adapt.render.AdaptEntityPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 实体/物品管线替换入口（修复版）。
 * <p>
 * 之前只 hook GlRenderPass/VulkanRenderPass 的 setPipeline（包私有内部类），
 * 但 26.2 实际调用的是 {@code com.mojang.blaze3d.systems.RenderPass} 包装器
 * 的 setPipeline（javap 证实：RenderPass.setPipeline(RenderPipeline) 存在，
 * 内部转发到 backend）。所以之前的替换完全不命中 → 实体/物品管线是原版，
 * adapt_entity 光照/接收阴影从未生效、blob shadow 全原版。
 * <p>
 * 修复：直接 hook RenderPass.setPipeline（编译期可引用），ModifyVariable
 * 替换传入的管线（isRendering 时换成 shadow caster，否则换成 adapt_entity/
 * adapt_item receiver）。对未注册的管线（sky/cloud/particle 等）返回原样。
 */
@Mixin(RenderPass.class)
public abstract class AdaptRenderPassPipelineMixin {

    @ModifyVariable(
            method = "setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private RenderPipeline adapt$swapPipeline(RenderPipeline pipeline) {
        return AdaptEntityPipelines.redirect(pipeline);
    }
}
