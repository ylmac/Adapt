package com.ylmac.adapt.mixins.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import com.ylmac.adapt.render.lighting.AdaptShadowMap;

/**
 * 禁用原版 blob shadow。
 * <p>
 * 实体现在画进 Adapt 方向 shadow map（AdaptShadowPassMixin →
 * AdaptShadowMap.renderEntitiesShadow），地形/实体 receiver 用
 * adaptShadowVisibility 采样真实方向阴影。原版 blob（ShadowFeatureRenderer，
 * executeTranslucent 的 shadows phase 用 ENTITY_SHADOW 管线画平面圆斑）与
 * 方向影叠印视觉脏，且永远与光照方向无关— 用 @Redirect 把
 * submitShadow 调用整体移除，主渲染不再收集/绘制任何 blob。
 * <p>
 * 与 26.2 字节码一致：EntityRenderDispatcher.submit 在 shadowPieces 非空时
 * invokeinterface SubmitNodeCollector.submitShadow(PoseStack, float, List)
 * （javap 确认 #282）。Redirect 后实体完全不再有原版圆斑—方向影是唯一
 * 实体阴影（与 语义一致；若实体 caster 未画进 shadow map，实体将
 * 没有地面投影，因此必须与 renderEntitiesShadow 配套）。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class BlobShadowMixin {

    @Redirect(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                            + "submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "FLjava/util/List;)V"
            )
    )
    private void adapt$removeVanillaShadow(SubmitNodeCollector instance, PoseStack poseStack,
                                           float radius, List<?> pieces) {
        // 光影总开关关闭 → 原版 blob shadow 必须回来：方向影不跑，blob 又被
        // 本 mixin 拿掉的话实体就完全没有阴影了。"关掉光影 = 完全原版"要求
        // 这条路径也还原。
        if (!com.ylmac.adapt.render.lighting.AdaptRenderToggle.isWorldEffectsEnabled()) {
            @SuppressWarnings("unchecked")
            List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> cast =
                    (List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece>) pieces;
            instance.submitShadow(poseStack, radius, cast);
        }
        // 总开关打开时：空方法，移除原版 blob shadow（同款），
            // 方向影是实体唯一地面阴影。
    }
}
