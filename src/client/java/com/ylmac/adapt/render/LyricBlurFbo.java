package com.ylmac.adapt.render;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import com.ylmac.adapt.QplayerModClient;

/**
 * 世界歌词"上下虚化"的离屏 GPU FBO 实现（高效结构）。
 *
 * qml4j / BackdropBlurCapture 验证过的路径：主 canvas 的 saveLayer + ImageFilter
 * 在 Vulkan（MoltenVK）上不渲染模糊；模糊必须在 Surface.makeRenderTarget 的
 * 离屏 FBO 上执行。本类把歌词渲染到 FBO → blur → 贴回主 canvas，并保留
 * blurBandShader（亮带外模糊）渐变蒙版。
 *
 * FBO 按 surface 物理尺寸复用，避免每帧创建（BackdropBlurCapture 同款）。
 */
public final class LyricBlurFbo {

    private static final LyricBlurFbo INSTANCE = new LyricBlurFbo();

    private Surface contentSurface;   // 歌词内容 FBO（无 blur）
    private Surface smallSurface;     // 1/4 模糊 FBO（BackdropBlurCapture 同款）
    private int fboW = -1, fboH = -1;

    private final Paint maskPaint = new Paint();

    private LyricBlurFbo() {}

    public static LyricBlurFbo get() { return INSTANCE; }

    /**
     * 渲染模糊层：
     * 1. 歌词渲染到 content FBO
     * 2. flushAndSubmit
     * 3. content snapshot → blur FBO（带 blur filter 的 drawImageRect）
     * 4. flushAndSubmit
     * 5. blur snapshot 贴回主 canvas（列区域），套 blurBandShader 渐变蒙版
     *
     * @return true 表示成功；false 表示 FBO 不可用（调用方降级为清晰渲染）
     */
    public boolean render(Canvas canvas,
                          com.ylmac.adapt.features.music.lyric.skia.WorldLyricRenderer renderer,
                          float pad, float colTop, float colW, float colH,
                          long positionMs, Rect colRect) {
        try {
            io.github.humbleui.skija.DirectContext ctx = GpuContextHelper.getOrCreateContext();
            if (ctx == null) return false;

            // FBO 尺寸 = surface 物理尺寸（逻辑 × guiScale=2），列区域裁剪到该范围
            int w = Math.max(16, (int) Math.ceil(colW * 2f));
            int h = Math.max(16, (int) Math.ceil(colH * 2f));
            ensureFbos(ctx, w, h);
            if (contentSurface == null) return false;

            // 1. 歌词内容画到 content FBO（坐标 = 列区域局部坐标）
            Canvas cc = contentSurface.getCanvas();
            cc.clear(0x00000000);
            // 列区域在 surface 逻辑坐标 (pad, colTop)，FBO 是物理像素（×2）。
            // 用局部坐标渲染：把 canvas 原点移到列区域，scale(2) 使逻辑→物理。
            cc.save();
            cc.scale(2f, 2f);
            // 与主 canvas 清晰层完全相同的全局坐标 (pad, colTop) —— 两层歌词位置必须一致，
            // 否则模糊层与清晰层错位 = 用户看到的"每行歌词出两行"
            renderer.render(cc, pad, colTop, colW, colH, positionMs);
            cc.restore();
            ctx.flushAndSubmit(false);

            // 2. BackdropBlurCapture 原版路径：降采样 1/4 + ImageFilter.makeBlur（GUI 验证过 work）
            Image contentSnap = contentSurface.makeImageSnapshot();
            if (contentSnap == null) return false;
            int sw = Math.max(16, w / 4), sh = Math.max(16, h / 4);
            ensureSmallSurface(ctx, sw, sh);
            if (smallSurface == null) { contentSnap.close(); return false; }
            float sigma = Math.max(1f, (28f * 0.11f) / 2f / 4f);
            io.github.humbleui.skija.ImageFilter blurFilter = io.github.humbleui.skija.ImageFilter.makeBlur(
                    sigma, sigma, io.github.humbleui.skija.FilterTileMode.CLAMP);
            Paint blurPaint = new Paint().setImageFilter(blurFilter);
            Canvas fc = smallSurface.getCanvas();
            fc.clear(0x00000000);
            fc.drawImageRect(contentSnap,
                    Rect.makeXYWH(0, 0, w, h),
                    Rect.makeXYWH(0, 0, sw, sh),
                    SamplingMode.LINEAR, blurPaint, false);
            blurPaint.close();
            blurFilter.close();
            contentSnap.close();
            ctx.flushAndSubmit(false);

            // 3. 模糊结果贴回主 canvas（列区域逻辑坐标）
            Image blurSnap = smallSurface.makeImageSnapshot();
            if (blurSnap == null) return false;
            canvas.save();
            // 模糊层先画，再用 blurBandShader 渐变蒙版裁出"亮带外"区域
            int mb = canvas.saveLayer(colRect, null);
            canvas.drawImageRect(blurSnap,
                    Rect.makeXYWH(0, 0, sw, sh),
                    Rect.makeXYWH(pad, colTop, colW, colH),
                    SamplingMode.LINEAR, null, false);
            blurSnap.close();
            Shader band = makeBandShader(colTop, colH, true);
            maskPaint.setShader(band);
            maskPaint.setBlendMode(BlendMode.DST_IN);
            canvas.drawRect(colRect, maskPaint);
            maskPaint.setShader(null);
            canvas.restoreToCount(mb);
            canvas.restore();
            return true;
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[WorldLyric] blur fbo failed: {}", t.toString());
            return false;
        }
    }

    /** 固定虚化带渐变（与 WorldLyricOverlay.BAND_STOPS 同款）：
     *  blurBand: 上下边缘不透明（模糊显示），中部透明（露出清晰层）
     *  sharpBand: 上下透明，中部不透明（清晰显示） */
    private static Shader makeBandShader(float topY, float colH, boolean blur) {
        float ramp = 0.08f;
        float a = Math.max(0f, 0f - ramp);
        float b = 0.08f;
        float c = 0.92f;
        float d = Math.min(1f, 1f + ramp);
        // 保证单调递增（防 shader 报错）
        float[] stops = {a, b, c, d};
        float eps = 0.001f;
        for (int i = 1; i < stops.length; i++) {
            if (stops[i] <= stops[i - 1]) stops[i] = Math.min(1f, stops[i - 1] + eps);
        }
        int o = 0xFFFFFFFF, t = 0x00FFFFFF;
        int[] colors = blur ? new int[]{o, t, t, o} : new int[]{t, o, o, t};
        return Shader.makeLinearGradient(0f, topY, 0f, topY + colH, colors, stops);
    }

    private void ensureFbos(io.github.humbleui.skija.DirectContext ctx, int w, int h) {
        if (contentSurface != null && fboW == w && fboH == h) return;
        dispose();
        try {
            ImageInfo info = ImageInfo.makeN32Premul(w, h);
            contentSurface = Surface.makeRenderTarget(ctx, false, info);
            fboW = w; fboH = h;
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[WorldLyric] blur fbo init failed: {}", t.toString());
            dispose();
        }
    }

    private void ensureSmallSurface(io.github.humbleui.skija.DirectContext ctx, int w, int h) {
        if (smallSurface != null && smallSurface.getWidth() == w && smallSurface.getHeight() == h) return;
        if (smallSurface != null) { try { smallSurface.close(); } catch (Exception ignored) {} smallSurface = null; }
        try {
            smallSurface = Surface.makeRenderTarget(ctx, false, ImageInfo.makeN32Premul(w, h));
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[WorldLyric] small fbo init failed: {}", t.toString());
            smallSurface = null;
        }
    }

    public void dispose() {
        if (contentSurface != null) { try { contentSurface.close(); } catch (Exception ignored) {} contentSurface = null; }
        if (smallSurface != null) { try { smallSurface.close(); } catch (Exception ignored) {} smallSurface = null; }
        fboW = -1; fboH = -1;
    }
}
