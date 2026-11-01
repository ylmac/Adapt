package com.ylmac.adapt.injection.mixins;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.ylmac.adapt.render.AdaptEntityPipelines;
import com.ylmac.adapt.render.AdaptLightingUniform;
import com.ylmac.adapt.render.AdaptShadowMap;
import com.ylmac.adapt.render.DeferredGBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Sodium 地形管线替换 + AdaptLighting/Shadow UBO 绑定。
 * <p>
 * 在 DefaultChunkRenderer.render 的
 * {@code pass.setPipeline(this.activeProgram)} 调用点拦截：
 * 1. 把 activeProgram（compileProgram 返回）替换成 Adapt 副本：
 *    - 阴影 pass（isRendering）→ sodium_shadow（深度 caster）
 *    - 半透明（color target 带 TRANSLUCENT blend）→ sodium_terrain_translucent
 *    - 不透明（SOLID/CUTOUT）→ sodium_terrain_gbuffer（延迟几何阶段）
 * 2. setPipeline 后补绑 AdaptLighting + ShadowFrame/ShadowMapSampler
 *    （Sodium 的 pass 不经过 RenderSystem.bindDefaultUniforms）。
 * <p>
 * 半透明/不透明判定完全使用管线自身状态（getColorTargetStates()[0]
 * .blendFunction().isPresent()），不依赖任何外部 flag：Sodium 的
 * sodium$renderGroup 是 cancellable HEAD 且内部同步画完全部地形，曾经依赖
 * ChunkSectionsToRenderMixin 注入的 ThreadLocal flag（opaqueActive）——日志
 * 实证 opaqueGroups=0，flag 从未在画图前成立（注入顺序竞争），导致地形全走
 * 原管线。管线自身 blend 状态与注入顺序无关，任何时刻都确定。
 * <p>
 * 注意：不能在 @Shadow 里拿 ShaderChunkRenderer.activeProgram——该字段在父类，
 * Mixin 0.8.7 的 @Shadow 只在目标类内查找，会 InvalidMixinException 崩溃
 * （"field activeProgram was not located in the target class"）。改用
 * render(HEAD) 注入：方法参数第 3 个就是 TerrainRenderPass，isTranslucent()
 * 直接可用，且在 render 开头就执行（javap：offset 0-6），远早于 createRenderPass
 * （offset 246），时序可靠。
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer")
public abstract class SodiumTerrainUniformMixin {

    /** 当前 render() 的 pass 是否半透明（由 render HEAD 注入写入）。 */
    private static final ThreadLocal<Boolean> CURRENT_TRANSLUCENT = ThreadLocal.withInitial(() -> false);

    /** shadow map 颜色清空值（caster 只写深度，颜色附件 WRITE_NONE）。 */
    private static final org.joml.Vector4f SHADOW_CLEAR = new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f);

    /**
     * render() 开头记录当前 pass 的半透明性。参数列表 = render 完整签名
     * （handler 必须完整参数或仅 CallbackInfo，否则 InvalidInjectionException）。
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void adapt$recordPassTranslucent(
            ChunkRenderMatrices matrices,
            ChunkRenderListIterable lists,
            TerrainRenderPass pass,
            CameraTransform cameraTransform,
            FogParameters fog,
            boolean useTranslucencySorting,
            GpuSampler sampler,
            GpuBufferSlice uniformBuffer,
            GpuBuffer sectionTimeInfo,
            CallbackInfo ci) {
        CURRENT_TRANSLUCENT.set(pass.isTranslucent());
    }

    private boolean adapt$currentPassTranslucent() {
        return CURRENT_TRANSLUCENT.get();
    }

    /**
     * OPAQUE（SOLID/CUTOUT）时把 Sodium 地形 pass 的附件换成 G-buffer 四目标
     * （深度复用主深度）。判定用 render HEAD 注入记录的 pass.isTranslucent()。
     * <p>
     * 注：Sodium 的 renderGroup handler（sodium$renderGroup，@Inject HEAD + cancel）
     * 会整段跳过原版 ChunkSectionsToRender.renderGroup——pass 实际由
     * DefaultChunkRenderer.render 内部创建（TerrainRenderPass.getTarget() →
     * createRenderPass，javap 实证）。所以重定向必须加在这里，而不是 renderGroup。
     */
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
            )
    )
    private RenderPass adapt$gbufferOpaquePass(CommandEncoder encoder, java.util.function.Supplier<String> label,
                                               GpuTextureView colorTarget, Optional<Vector4fc> clearColor,
                                               GpuTextureView depthTarget, OptionalDouble clearDepth) {
        // 总开关关闭：地形照常画进主 target，不碰 G-buffer。
        if (!com.ylmac.adapt.render.AdaptRenderToggle.isWorldEffectsEnabled()) {
            return encoder.createRenderPass(label, colorTarget, clearColor, depthTarget, clearDepth);
        }
        // shadow caster 复用同一 drawChunkLayer(OPAQUE) 路径（AdaptShadowMap
        // renderSodiumTerrainShadow 反射调用），必须放行让地形画进 shadow
        // target，否则地形 caster 会被劫持进 G-buffer，方向影直接消失。
        // 但清空必须在 pass 创建时做：独立的 clearColorAndDepthTextures 命令
        // 对同帧后续 LOAD pass 不保证可见（26.2 的 memoryBarrier 只在
        // submitRenderPass 发），深度会跨帧累积 —— 实测"站着影子越来越黑、
        // 转视角旧影子不消失"。每次 caster pass 只清第一个 pass。
        if (AdaptShadowMap.isRendering()) {
            if (AdaptShadowMap.consumeShadowClear()) {
                return encoder.createRenderPass(label, colorTarget,
                        Optional.of(SHADOW_CLEAR), depthTarget, OptionalDouble.of(1.0));
            }
            return encoder.createRenderPass(label, colorTarget, Optional.empty(),
                    depthTarget, OptionalDouble.empty());
        }
        boolean translucent = adapt$currentPassTranslucent();
        if (!translucent) {
            return DeferredGBuffer.createOpaquePass(encoder, label, depthTarget);
        }
        return encoder.createRenderPass(label, colorTarget, clearColor, depthTarget, clearDepth);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
            )
    )
    private void adapt$bindAdaptLighting(RenderPass pass, RenderPipeline pipeline) {
        // 半透明 = color target 0 带 blend 函数（Sodium createShader 对
        // translucent pass 设置 BlendFunction.TRANSLUCENT）。
        // 注意：ColorTargetState.blendFunction 是 java.util.Optional<BlendFunction>
        // （record 字段，javap 证实），永不 null——用 != null 判断恒真会把所有
        // 不透明地形误判成 translucent（走 sodium_terrain_translucent 近似光照
        // → 世界平板全亮）。必须用 isPresent()。
        boolean translucent = false;
        var targets = pipeline.getColorTargetStates();
        if (targets != null && !targets.isEmpty() && targets.get(0) != null
                && targets.get(0).blendFunction().isPresent()) {
            translucent = true;
        }
        // 先替换管线（Adapt 副本），再 setPipeline，最后补绑 UBO
        RenderPipeline replaced = AdaptEntityPipelines.redirectSodiumTerrain(pipeline, translucent);
        pass.setPipeline(com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(replaced));
        if (!com.ylmac.adapt.render.AdaptRenderToggle.isWorldEffectsEnabled()) {
            // 总开关关闭：管线已由上面原样返回，也不绑 AdaptLighting/ShadowFrame
            // ——原版地形管线不认识这些 uniform。
            return;
        }
        AdaptLightingUniform.bind(pass);
        if (AdaptShadowMap.isRendering()) {
            AdaptShadowMap.bindCasterUniforms(pass);
        } else {
            AdaptShadowMap.bindReceiver(pass);
        }
    }
}
