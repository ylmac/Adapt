package com.ylmac.adapt.mixins.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.ylmac.adapt.render.lighting.AdaptLightingUniform;
import com.ylmac.adapt.render.lighting.AdaptShadowMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AdaptLighting UBO 绑定挂载点。
 * <p>
 * RenderSystem.bindDefaultUniforms 是所有世界渲染路径创建 RenderPass 后
 * 统一调用的入口：实体/物品走 PreparedRenderType.drawFromBuffer，地形
 * chunk 走 ChunkSectionsToRender 的独立 pass（均直接调用该静态方法）。
 * 在 TAIL 注入使全部路径获得 AdaptLighting 绑定；对未声明该 uniform 的
 * 管线，后端会忽略多余条目，无副作用。
 */
@Mixin(RenderSystem.class)
public abstract class AdaptRenderSystemMixin {

    @Inject(method = "bindDefaultUniforms", at = @At("TAIL"))
    private static void adapt$bindAdaptLighting(RenderPass pass, CallbackInfo ci) {
        // 总开关关闭：管线是原版，不认识 AdaptLighting/ShadowFrame — 不绑。
        if (!com.ylmac.adapt.render.lighting.AdaptRenderToggle.isWorldEffectsEnabled()) {
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
