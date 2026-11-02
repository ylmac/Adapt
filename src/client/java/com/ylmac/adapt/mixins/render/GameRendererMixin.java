package com.ylmac.adapt.mixins.render;

import com.ylmac.adapt.render.hud.BackdropBlurCapture;
import com.ylmac.adapt.render.profile.FrameProfiler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在渲染世界之后、GUI 之前截取帧缓冲，确保只截到世界不截到 GUI。
 * <p>
 * 26.3：{@code GameRenderer.renderLevel} 无参数；partialTicks 从
 * {@code Minecraft.getDeltaTracker} 取（本 mixin 不需要）。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onRenderLevel(CallbackInfo ci) {
        // renderLevel 渲染完世界后立即截取帧缓冲
        // 此时 GUI 尚未渲染，g.blit 尚未合成 Skia 表面
        FrameProfiler.begin(FrameProfiler.BLUR_CAPTURE);
        BackdropBlurCapture.captureFrame();
        FrameProfiler.end(FrameProfiler.BLUR_CAPTURE);
    }
}
