package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.render.BackdropBlurCapture;
import com.ylmac.adapt.render.FrameProfiler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在渲染世界之后、GUI 之前截取帧缓冲，确保只截到世界不截到 GUI。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        // renderLevel 渲染完世界后立即截取帧缓冲
        // 此时 GUI 尚未渲染，g.blit() 尚未合成 Skia 表面
        FrameProfiler.begin(FrameProfiler.BLUR_CAPTURE);
        BackdropBlurCapture.captureFrame();
        FrameProfiler.end(FrameProfiler.BLUR_CAPTURE);
    }
}