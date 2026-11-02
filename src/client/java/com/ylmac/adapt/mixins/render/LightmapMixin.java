package com.ylmac.adapt.mixins.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.ylmac.adapt.module.render.Fullbright;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <p>
 * Fullbright（Gamma 模式）启用时，在 Lightmap.render HEAD 取消原渲染，
 * 直接把光照贴图纹理清成纯白（0xFFFFFFFF），等价原版 1.8.9 把 gamma
 * 拉到 1000 的效果（世界无阴影、全亮）。26.2 中 gamma 设置已不参与光照
 * javap 验证：Lightmap.render(LightmapRenderState) + texture 私有字段 +
 * RenderSystem.getDevice.createCommandEncoder.clearColorTexture(GpuTexture, Vector4fc)）。
 */
@Mixin(Lightmap.class)
public class LightmapMixin {

    @Final
    @Shadow
    private GpuTexture texture;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void adapt_onLightmapRender(LightmapRenderState renderState, CallbackInfo ci) {
        Fullbright fullbright = Fullbright.INSTANCE;
        if (fullbright != null && fullbright.isGammaMode()) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(this.texture, new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
            ci.cancel();
        }
    }
}
