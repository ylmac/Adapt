package com.ylmac.adapt.features.music.lyric.skia;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.util.Objects;
import com.ylmac.adapt.features.music.util.Logger;

public final class FluidBackground {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("qplayer-mod");
    private static final int TEX_SIZE = 32;

    private static final String SKSL_HEAD =
            "const float TEX_SIZE  = 32.0;\n" +
            "const float WARP_A    = 0.10;\n" +
            "const float WARP_B    = 0.06;\n" +
            "const float ROT_SPEED = 0.18;\n" +
            "half4 main(float2 fragCoord) {\n" +
            "    float2 screenUV = fragCoord / resolution;\n" +
            "    // cover-fit the square texture: scale by the long axis, crop the\n" +
            "    // short one so the cover never stretches with the viewport aspect.\n" +
            "    float2 corr = float2(min(1.0, resolution.x / resolution.y),\n" +
            "                         min(1.0, resolution.y / resolution.x));\n" +
            "    float2 uv = (screenUV - 0.5) * corr + 0.5;\n" +
            "    float2 w1 = float2(sin(time * 0.42 + uv.y * 3.7),\n" +
            "                       cos(time * 0.37 + uv.x * 3.1));\n" +
            "    float2 w2 = float2(cos(time * 0.29 + uv.x * 2.3),\n" +
            "                       sin(time * 0.51 + uv.y * 4.4));\n" +
            "    float2 warpedUV = uv + WARP_A * w1 + WARP_B * w2;\n" +
            "    float a  = time * ROT_SPEED;\n" +
            "    float cs = cos(a);\n" +
            "    float sn = sin(a);\n" +
            "    float2 c = warpedUV - 0.5;\n" +
            "    float2 rotUV = float2(c.x * cs - c.y * sn,\n" +
            "                          c.x * sn + c.y * cs) + 0.5;\n" +
            "    rotUV = clamp(rotUV, float2(0.005), float2(0.995));\n";
    private static final String SKSL_TAIL =
            "    float vignette = smoothstep(0.8, 0.3, distance(screenUV, float2(0.5)));\n" +
            "    half3 rgb = col.rgb * half(0.6 + vignette * 0.4);\n" +
            "    float dither = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453);\n" +
            "    rgb += half3(dither - 0.5) / 255.0;\n" +
            "    return half4(rgb, 1.0);\n" +
            "}\n";
    private static final String SKSL =
            "uniform float2 resolution;\n" +
            "uniform float  time;\n" +
            "uniform shader cover;\n" +
            SKSL_HEAD +
            "    half4 col = cover.eval(rotUV * TEX_SIZE);\n" +
            SKSL_TAIL;
    private static final String SKSL_FADE =
            "uniform float2 resolution;\n" +
            "uniform float  time;\n" +
            "uniform shader cover;\n" +
            "uniform shader coverPrev;\n" +
            "uniform float  fade;\n" +
            SKSL_HEAD +
            "    half4 col = mix(coverPrev.eval(rotUV * TEX_SIZE), cover.eval(rotUV * TEX_SIZE), fade);\n" +
            SKSL_TAIL;

    private static RuntimeEffect effect;
    private static RuntimeEffect effectFade;

    private static RuntimeEffect effect() {
        if (effect == null) {
            try {
                effect = RuntimeEffect.makeForShader(SKSL);
            } catch (Throwable t) {
                com.ylmac.adapt.features.music.util.Logger.warn("fluid sksl compile failed: {}", t.getMessage());
                throw t;
            }
        }
        return effect;
    }

    private static RuntimeEffect effectFade() {
        if (effectFade == null) {
            try {
                effectFade = RuntimeEffect.makeForShader(SKSL_FADE);
            } catch (Throwable t) {
                com.ylmac.adapt.features.music.util.Logger.warn("fluid fade sksl compile failed: {}", t.getMessage());
                throw t;
            }
        }
        return effectFade;
    }

    private final long startNs;
    private Image cover;
    private Shader coverShader;
    private String coverKey;
    private Shader coverPrevShader;
    private Image coverPrev;
    private long fadeStartNs;
    private static final float FADE_DUR = 0.6f;
    private final java.util.concurrent.ExecutorService decoder =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fluid-cover-decode");
                t.setDaemon(true);
                return t;
            });
    private volatile Image decodedCover;
    private volatile String decodedKey;
    private volatile String decodingKey;
    private volatile boolean fallbackWarned;
    private final Paint fluidPaint = new Paint();
    private Rect fullRect;
    private float fullRectW = -1f;
    private float fullRectH = -1f;
    private Image staticImage;
    private int staticW = -1;
    private int staticH = -1;
    // 半分辨率离屏 surface（动态背景渲染到半分辨率再上采样 → GPU 带宽 -75%）
    private Surface halfResSurface;
    private int halfResW = -1;
    private int halfResH = -1;

    public FluidBackground(long startNs) {
        this.startNs = startNs;
    }

    public void render(Canvas canvas, DirectContext ctx, float uiScale, float w, float h,
                       byte[] coverBytes, String trackKey, long nowNs, boolean staticMode) {
        boolean keyChanged = !Objects.equals(trackKey, coverKey);
        boolean nullButReady = cover == null && coverBytes != null && coverBytes.length > 0;
        if ((keyChanged || nullButReady) && coverBytes != null && coverBytes.length > 0
                && !Objects.equals(trackKey, decodingKey)) {
            decodingKey = trackKey;
            final byte[] cb = coverBytes;
            final String key = trackKey;
            decoder.submit(() -> {
                Image tex = buildTexture(cb);
                if (tex != null) { decodedKey = key; decodedCover = tex; }
            });
        }
        if (decodedCover != null && Objects.equals(decodedKey, trackKey)
                && !Objects.equals(coverKey, trackKey)) {
            releasePrev();
            coverPrevShader = coverShader;
            coverPrev = cover;
            cover = decodedCover;
            decodedCover = null;
            coverKey = trackKey;
            coverShader = cover.makeShader(
                    FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                    SamplingMode.LINEAR, Matrix33.IDENTITY);
            fadeStartNs = nowNs;
            invalidateStatic();
        }
        if (coverShader == null) {
            if (!fallbackWarned) {
                if (coverBytes == null || coverBytes.length == 0) {
                    LOG.warn("[FluidBackground] fallback: coverBytes is null/empty for trackKey={}", trackKey);
                } else {
                    LOG.warn("[FluidBackground] fallback: coverShader null but coverBytes={} bytes, decode may have failed for trackKey={}",
                            coverBytes.length, trackKey);
                }
                fallbackWarned = true;
            }
            renderFallback(canvas, w, h, 0xFF0A0A0E);
            return;
        }
        fallbackWarned = false;

        float time = (nowNs - startNs) / 1_000_000_000f;

        if (fullRect == null || fullRectW != w || fullRectH != h) {
            fullRect = Rect.makeXYWH(0, 0, w, h);
            fullRectW = w;
            fullRectH = h;
        }

        if (staticMode && ctx != null) {
            releasePrev();
            int dw = Math.max(1, Math.round(w * uiScale));
            int dh = Math.max(1, Math.round(h * uiScale));
            if (staticImage == null || staticW != dw || staticH != dh) {
                buildStatic(ctx, dw, dh, time);
            }
            if (staticImage != null) {
                canvas.drawImageRect(staticImage, fullRect);
                return;
            }
        }

        // 动态背景降分辨率：渲染到半分辨率离屏 surface → drawImageRect 上采样
        // GPU 像素写入 -75%（流体背景是模糊渐变，半分辨率视觉无损）
        if (ctx != null) {
            int hw = Math.max(1, Math.round(w * 0.5f));
            int hh = Math.max(1, Math.round(h * 0.5f));
            if (halfResSurface == null || halfResW != hw || halfResH != hh) {
                if (halfResSurface != null) { try { halfResSurface.close(); } catch (Exception ignored) {} }
                halfResSurface = Surface.makeRenderTarget(ctx, false, ImageInfo.makeN32Premul(hw, hh));
                halfResW = hw;
                halfResH = hh;
            }
            if (halfResSurface != null) {
                Canvas halfCanvas = halfResSurface.getCanvas();
                drawFluid(halfCanvas, Rect.makeXYWH(0, 0, hw, hh), hw, hh, time, fadeProgress(nowNs));
                Image snapshot = halfResSurface.makeImageSnapshot();
                canvas.drawImageRect(snapshot, fullRect);
                snapshot.close();
                return;
            }
        }
        drawFluid(canvas, fullRect, w, h, time, fadeProgress(nowNs));
    }

    private float fadeProgress(long nowNs) {
        if (coverPrevShader == null) return 1f;
        float t = (nowNs - fadeStartNs) / 1_000_000_000f / FADE_DUR;
        if (t >= 1f) { releasePrev(); return 1f; }
        return t < 0f ? 0f : t;
    }

    private void releasePrev() {
        if (coverPrevShader != null) { coverPrevShader.close(); coverPrevShader = null; }
        if (coverPrev != null) { coverPrev.close(); coverPrev = null; }
    }

    private void drawFluid(Canvas canvas, Rect dstRect, float resW, float resH, float time, float fade) {
        boolean fading = coverPrevShader != null && fade < 1f;
        Shader shaded = null;
        try {
            try (RuntimeEffectBuilder b = new RuntimeEffectBuilder(fading ? effectFade() : effect())) {
                b.setUniform("resolution", resW, resH);
                b.setUniform("time", time);
                b.setChild("cover", coverShader);
                if (fading) {
                    b.setChild("coverPrev", coverPrevShader);
                    b.setUniform("fade", fade);
                }
                shaded = b.makeShader();
            }
            fluidPaint.setShader(shaded);
            canvas.drawRect(dstRect, fluidPaint);
        } catch (Throwable e) {
            LOG.warn("[FluidBackground] fluid render failed: {}", e.getMessage());
            renderFallback(canvas, resW, resH, 0xFF0A0A0E);
        } finally {
            fluidPaint.setShader(null);
            if (shaded != null) shaded.close();
        }
    }

    private void buildStatic(DirectContext ctx, int dw, int dh, float time) {
        invalidateStatic();
        try (Surface off = Surface.makeRenderTarget(ctx, false, ImageInfo.makeN32Premul(dw, dh))) {
            drawFluid(off.getCanvas(), Rect.makeWH(dw, dh), dw, dh, time, 1f);
            staticImage = off.makeImageSnapshot();
            staticW = dw;
            staticH = dh;
        } catch (Throwable e) {
            LOG.warn("[FluidBackground] fluid static cache failed: {}", e.getMessage());
            invalidateStatic();
        }
    }

    private void invalidateStatic() {
        if (staticImage != null) {
            staticImage.close();
            staticImage = null;
        }
        staticW = -1;
        staticH = -1;
    }

    public void dispose() {
        decoder.shutdownNow();
        if (decodedCover != null) {
            decodedCover.close();
            decodedCover = null;
        }
        if (coverShader != null) {
            coverShader.close();
            coverShader = null;
        }
        if (cover != null) {
            cover.close();
            cover = null;
        }
        releasePrev();
        invalidateStatic();
        fluidPaint.close();
        coverKey = null;
    }

    private static Image buildTexture(byte[] coverBytes) {
        if (coverBytes == null || coverBytes.length == 0) return null;
        try {
            Image src = Image.makeDeferredFromEncodedBytes(coverBytes);
            ImageInfo thumbInfo = new ImageInfo(TEX_SIZE, TEX_SIZE,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
            Bitmap thumb = new Bitmap();
            thumb.allocPixels(thumbInfo);
            Canvas tc = new Canvas(thumb);
            tc.drawImageRect(src,
                    Rect.makeWH(src.getWidth(), src.getHeight()),
                    Rect.makeWH(TEX_SIZE, TEX_SIZE),
                    SamplingMode.LINEAR, null, true);
            src.close();
            byte[] raw = thumb.readPixels();
            thumb.close();
            if (raw == null) {
                LOG.warn("[FluidBackground] buildTexture: readPixels returned null");
                return null;
            }

            int[] argb = new int[TEX_SIZE * TEX_SIZE];
            for (int i = 0; i < argb.length; i++) {
                int r = raw[i * 4] & 0xFF;
                int g = raw[i * 4 + 1] & 0xFF;
                int b = raw[i * 4 + 2] & 0xFF;
                int a = raw[i * 4 + 3] & 0xFF;
                argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            float[] rgb = amllAdjust(argb);
            boxBlur(rgb, TEX_SIZE, TEX_SIZE, 2, 4);

            byte[] px = new byte[argb.length * 4];
            for (int i = 0; i < argb.length; i++) {
                px[i * 4] = (byte) clampU8(rgb[i * 3]);
                px[i * 4 + 1] = (byte) clampU8(rgb[i * 3 + 1]);
                px[i * 4 + 2] = (byte) clampU8(rgb[i * 3 + 2]);
                px[i * 4 + 3] = (byte) 0xFF;
            }
            ImageInfo info = new ImageInfo(TEX_SIZE, TEX_SIZE,
                    ColorType.RGBA_8888, ColorAlphaType.OPAQUE);
            Image result = Image.makeRasterFromBytes(info, px, TEX_SIZE * 4L);
            LOG.info("[FluidBackground] buildTexture OK: {} bytes -> {}x{} texture", coverBytes.length, TEX_SIZE, TEX_SIZE);
            return result;
        } catch (Throwable e) {
            LOG.warn("[FluidBackground] buildTexture failed: {} bytes, error: {}", coverBytes.length, e.getMessage());
            return null;
        }
    }

    private static float[] amllAdjust(int[] argb) {
        float[] out = new float[argb.length * 3];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            float r = (p >> 16) & 0xFF;
            float g = (p >> 8) & 0xFF;
            float b = p & 0xFF;

            r = (r - 128f) * 0.4f + 128f;
            g = (g - 128f) * 0.4f + 128f;
            b = (b - 128f) * 0.4f + 128f;

            float gray = r * 0.3f + g * 0.59f + b * 0.11f;
            r = gray * -2f + r * 3f;
            g = gray * -2f + g * 3f;
            b = gray * -2f + b * 3f;

            r = (r - 128f) * 1.7f + 128f;
            g = (g - 128f) * 1.7f + 128f;
            b = (b - 128f) * 1.7f + 128f;

            r *= 0.75f;
            g *= 0.75f;
            b *= 0.75f;

            out[i * 3] = r;
            out[i * 3 + 1] = g;
            out[i * 3 + 2] = b;
        }
        return out;
    }

    private static void boxBlur(float[] rgb, int w, int h, int radius, int passes) {
        float[] tmp = new float[rgb.length];
        for (int p = 0; p < passes; p++) {
            boxBlurH(rgb, tmp, w, h, radius);
            boxBlurV(tmp, rgb, w, h, radius);
        }
    }

    private static void boxBlurH(float[] src, float[] dst, int w, int h, int radius) {
        float div = 2f * radius + 1f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = Math.max(0, Math.min(w - 1, x + k));
                    int idx = (y * w + sx) * 3;
                    r += src[idx];
                    g += src[idx + 1];
                    b += src[idx + 2];
                }
                int o = (y * w + x) * 3;
                dst[o] = r / div;
                dst[o + 1] = g / div;
                dst[o + 2] = b / div;
            }
        }
    }

    private static void boxBlurV(float[] src, float[] dst, int w, int h, int radius) {
        float div = 2f * radius + 1f;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sy = Math.max(0, Math.min(h - 1, y + k));
                    int idx = (sy * w + x) * 3;
                    r += src[idx];
                    g += src[idx + 1];
                    b += src[idx + 2];
                }
                int o = (y * w + x) * 3;
                dst[o] = r / div;
                dst[o + 1] = g / div;
                dst[o + 2] = b / div;
            }
        }
    }

    private static int clampU8(float v) {
        if (v <= 0f) return 0;
        if (v >= 255f) return 255;
        return (int) (v + 0.5f);
    }

    private void renderFallback(Canvas canvas, float w, float h, int color) {
        try (Paint p = new Paint()) {
            p.setColor(color);
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
    }
}
