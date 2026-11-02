package com.ylmac.adapt.mixins.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.ylmac.adapt.render.lighting.AdaptShadowMap;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 实体 caster pass 里跳过粒子。
 * <p>
 * {@code AdaptShadowMap.renderEntityShadow} 第二次调用
 * {@code PreparedFrame.executeSolid}，把实体几何再画一遍进 shadow map。
 * 重定向靠 {@code RenderSystem.outputColor/DepthTextureOverride} — 而
 * {@code PreparedRenderType.drawFromBuffer} 会读这两个 override（字节码
 * off.9-26 / off.36-53），所以模型/物品/方块模型都正确改道。
 * <p>
 * <b>粒子是唯一的例外</b>：{@code QuadParticleFeatureRenderer.executeGroup}
 * 自己建 pass / 用外部 pass，附件直接取
 * {@code Minecraft.gameRenderer.mainRenderTarget.getColorTextureView}
 * 与 {@code LevelRenderer.particlesTarget}（字节码 off.65-103），
 * <b>完全不看 override</b>。若放它跑第二遍会有两个后果：
 * <ol>
 * <li>粒子在主 target 上被画两次 — 半透明/加色混合下亮度翻倍；</li>
 * <li>它的 {@code drawLayers} 走 {@code pass.setPipeline}，而
 * AdaptRenderPassPipelineMixin 在 {@code isRendering} 为真时会换成
 * 深度 caster 管线（光空间矩阵 + 写深度）— 那会把光空间 Z 写进
 * <b>主深度缓冲</b>，破坏 composite 的实体遮挡判定。</li>
 * </ol>
 * 而且粒子本来就不该投影（的 shadow pass 只画地形 + 实体 +
 * 方块实体，不含粒子）。
 * <p>
 * {@code SubmitNodeCollection.submitQuadParticleGroup} 把粒子提交进
 * {@code solid} 与 {@code afterTerrain} 两个 phase（字节码实证），
 * 所以 executeSolid 一定会碰到它。
 * <p>
 * 26.3：{@code executeGroup} 签名增加了 {@link OitStage} 与 {@link RenderPass}。
 */
@Mixin(QuadParticleFeatureRenderer.class)
public abstract class ParticleShadowSkipMixin {

    @Inject(method = "executeGroup", at = @At("HEAD"), cancellable = true)
    private void adapt$skipParticlesInShadowPass(FeatureFrameContext context, OitStage oitStage,
                                                 RenderPass renderPass, int groupIndex,
                                                 List<?> submits, boolean strictlyOrdered,
                                                 CallbackInfo ci) {
        if (AdaptShadowMap.isRendering()) {
            ci.cancel();
        }
    }
}
