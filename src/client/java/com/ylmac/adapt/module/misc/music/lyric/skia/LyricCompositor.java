package com.ylmac.adapt.module.misc.music.lyric.skia;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import com.ylmac.adapt.render.profile.FrameProfiler;
import com.ylmac.adapt.module.misc.music.PlayerController;
import com.ylmac.adapt.module.misc.music.lyric.LyricLine;

import java.util.List;

public final class LyricCompositor {

    public interface SettingsBridge {
        float topInset();
        boolean lyricBgStatic();

        boolean showLyrics();
    }

    private static final float L_TRANSPORT_H = 150f;
    private static final float L_LANDSCAPE_TOP = 24f;
    private static final float L_LANDSCAPE_BOTTOM = 24f;

    private final LyricRenderer lyricRenderer = new LyricRenderer();
    private final FluidBackground fluidBg = new FluidBackground(System.nanoTime());

    private List<LyricLine> lastLyrics;
    private float lyricSlide;

    private long lyRawLast = -1;
    private boolean lyPlayingLast;
    private long lyBaseMs;
    private long lyBaseNanos;

    private float lyShaderColH = -1f;
    private float lyShaderTopY = -1f;
    private Shader lyFadeShader;
    private final Paint lyMaskPaint = new Paint();

    private static final float EDGE_BLUR_SIGMA_RATIO = 0.11f;
    private static final float[] BAND_STOPS = {0f, 0.26f, 0.44f, 0.70f, 1f};
    private final Paint blurBasePaint = new Paint();
    private ImageFilter blurFilter;
    private float blurSigmaApplied = -1f;
    private Shader blurBandShader;
    private Shader sharpBandShader;
    private float bandShaderColH = -1f;
    private float bandShaderTopY = -1f;
    private float bandFracTop = -1f, bandFracBottom = -1f;
    private static final float BAND_RAMP = 0.24f;

    private static final float LYRIC_ZOOM_TAU = 0.13f;
    private float lyricShow = 0f;
    private long lyricShowNs = 0L;
    private final Paint lyLayerPaint = new Paint();

    private Rect lyColRect;
    private float lyColW = -1f, lyColH = -1f, lyColTopY = -1f, lyColLeft = -1f;
    private boolean coverOnlyCached;
    private Object lyKeyTitle, lyKeyUrl;
    private String lyCoverKey;

    public LyricRenderer lyricRenderer() {
        return lyricRenderer;
    }

    public float lyricSlide() {
        return lyricSlide;
    }

    public float lyricTopY(float topInset) {
        return topInset + 144f;
    }

    public boolean lyricsScrollable(float x, float y, float surfaceWLogical,
                                    float surfaceHeightLogical, float topInset) {
        if (lyricSlide < 0.99f || !lyricRenderer.hasLines()) return false;
        if (surfaceWLogical > surfaceHeightLogical) {
            if (coverOnlyCached) return false;
            float topY = topInset + L_LANDSCAPE_TOP;
            float bottomY = surfaceHeightLogical - L_LANDSCAPE_BOTTOM;
            return x >= surfaceWLogical * 0.5f && y >= topY && y <= bottomY;
        }
        float topY = lyricTopY(topInset);
        float bottomY = surfaceHeightLogical - L_TRANSPORT_H;
        return y >= topY && y <= bottomY;
    }

    private void drawLyricOverlay(Canvas canvas, PlayerController controller,
                                  SettingsBridge settings, DirectContext ctx,
                                  float uiScale, int fbW, int fbH) {
        if (controller == null) return;
        // 设置页"桌面歌词"关闭时：不绘制歌词层，也不驱动 lyricSlide/lyricProgress
        if (settings != null && !settings.showLyrics()) return;
        boolean open = Boolean.TRUE.equals(controller.lyricsOpen.peek());
        float target = open ? 1f : 0f;
        // 下拉手势（拖拽）进行中：不接受自动 lerp 的旧值 — host 服从 直接写入的
        // lyricSlide（把宿主字段同步成控制器里的当前值），手指拖动能实时反映在宿主歌词层/
        // 渲染上（predictive-back）。
        boolean dragging = Boolean.TRUE.equals(controller.lyricDrag.peek());
        if (dragging) {
            lyricSlide = (float) controller.lyricSlide.peekDouble();
        } else {
            lyricSlide += (target - lyricSlide) * 0.22f;
            if (Math.abs(target - lyricSlide) < 0.002f) lyricSlide = target;
        }
        controller.lyricSlide.set((float) lyricSlide);
        if (lyricSlide <= 0.001f && !open) return;

        // lyricProgress 不再每帧 set（改用 PlayerController.displayProgress 降频更新）
        // controller.lyricProgress.set(...) 已移除 — 进度条改绑 displayProgress

        List<LyricLine> lyObj = controller.lyrics.get();
        if (lyObj != lastLyrics) {
            lastLyrics = lyObj;
            lyricRenderer.setLyrics(lastLyrics);
        }

        float w = fbW / uiScale;
        float h = fbH / uiScale;
        float topInset = settings != null ? settings.topInset() : 0f;

        boolean landscape = w > h;
        boolean coverOnly = Boolean.TRUE.equals(controller.lyricsCoverOnly.get());
        coverOnlyCached = coverOnly;
        float pad = 28f;
        float colLeft, colTopY, colW, colH;
        if (landscape) {
            colLeft = w * 0.5f;
            colTopY = topInset + L_LANDSCAPE_TOP;
            colW = (w - colLeft) - 2f * pad;
            colH = h - colTopY - L_LANDSCAPE_BOTTOM;
        } else {
            colLeft = 0f;
            colTopY = lyricTopY(topInset);
            colW = w - 2f * pad;
            colH = h - colTopY - L_TRANSPORT_H;
        }
        ensureLyricShaders(colTopY, colH);
        int sc = canvas.save();
        canvas.scale(uiScale, uiScale);

        float ease = lyricSlide * lyricSlide * (3f - 2f * lyricSlide);
        canvas.translate(0f, (1f - ease) * h);

        byte[] cover = (byte[]) controller.coverBytes.get();
        Object title = controller.title.get();
        Object coverUrl = controller.coverUrl.get();
        if (lyCoverKey == null || title != lyKeyTitle || coverUrl != lyKeyUrl) {
            lyKeyTitle = title;
            lyKeyUrl = coverUrl;
            lyCoverKey = title + "|" + coverUrl;
        }
        boolean bgStatic = settings != null && settings.lyricBgStatic();
        FrameProfiler.begin(FrameProfiler.FLUID_BG);
        com.ylmac.adapt.render.profile.Probe.begin("lyric.fluid");
        try {
            fluidBg.render(canvas, ctx, uiScale, w, h, cover, lyCoverKey,
                    System.nanoTime(), bgStatic);
        } finally {
            com.ylmac.adapt.render.profile.Probe.end("lyric.fluid");
            FrameProfiler.end(FrameProfiler.FLUID_BG);
        }

        float zoomTarget = coverOnly ? 0f : 1f;
        long zoomNow = System.nanoTime();
        if (lyricShowNs != 0L) {
            float dt = (zoomNow - lyricShowNs) / 1_000_000_000f;
            if (dt > 0.05f) dt = 0.05f;
            if (dt > 0f) lyricShow += (zoomTarget - lyricShow) * (1f - (float) Math.exp(-dt / LYRIC_ZOOM_TAU));
        } else {
            lyricShow = zoomTarget;
        }
        lyricShowNs = zoomNow;
        if (Math.abs(lyricShow - zoomTarget) < 0.002f) lyricShow = zoomTarget;

        if (lyricShow > 0.001f) {
            if (lyColRect == null || lyColLeft != colLeft || lyColTopY != colTopY
                    || lyColW != w || lyColH != colH) {
                lyColRect = Rect.makeXYWH(colLeft, colTopY, w - colLeft, colH);
                lyColLeft = colLeft;
                lyColTopY = colTopY;
                lyColW = w;
                lyColH = colH;
            }
            Rect colRect = lyColRect;
            long pos = controller.position();
            int alpha = Math.round(Math.max(0f, Math.min(1f, lyricShow)) * 255f);
            lyLayerPaint.setAlpha(alpha);
            int lc = canvas.saveLayer(colRect, alpha < 255 ? lyLayerPaint : null);
            float s = 0.95f + 0.05f * lyricShow;
            float cx = colLeft + (w - colLeft) * 0.5f;
            float cy = colTopY + colH * 0.5f;
            int zc = canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(s, s);
            canvas.translate(-cx, -cy);
            LyricSkia.setCanvas(canvas);
            if (Boolean.TRUE.equals(LyricConfig.instance.edgeBlur.getValue())) {
                FrameProfiler.begin(FrameProfiler.EDGE_BLUR);
                drawProgressiveBlurColumn(canvas, colRect, colLeft + pad, colTopY, colW, colH, pos);
                FrameProfiler.end(FrameProfiler.EDGE_BLUR);
            } else {
                lyricRenderer.render(canvas, colLeft + pad, colTopY, colW, colH, pos);
            }
            canvas.restoreToCount(zc);
            lyMaskPaint.setShader(lyFadeShader);
            lyMaskPaint.setBlendMode(BlendMode.DST_IN);
            canvas.drawRect(colRect, lyMaskPaint);
            lyMaskPaint.setShader(null);
            canvas.restoreToCount(lc);
        }

        canvas.restoreToCount(sc);
    }

    private void drawProgressiveBlurColumn(Canvas canvas, Rect colRect, float leftX,
                                           float colTopY, float colW, float colH, long pos) {
        float sigma = Math.max(1f, LyricConfig.instance.lyricFontSize.getValue() * EDGE_BLUR_SIGMA_RATIO);
        ensureBlurFilter(sigma);

        int mb = canvas.saveLayer(colRect, null);
        int b = canvas.saveLayer(colRect, blurBasePaint);
        lyricRenderer.render(canvas, leftX, colTopY, colW, colH, pos);
        canvas.restoreToCount(b);
        ensureBandShaders(colTopY, colH, lyricRenderer.litBandBounds());
        lyMaskPaint.setShader(blurBandShader);
        lyMaskPaint.setBlendMode(BlendMode.DST_IN);
        canvas.drawRect(colRect, lyMaskPaint);
        lyMaskPaint.setShader(null);
        canvas.restoreToCount(mb);

        int ms = canvas.saveLayer(colRect, null);
        lyricRenderer.render(canvas, leftX, colTopY, colW, colH, pos);
        lyMaskPaint.setShader(sharpBandShader);
        lyMaskPaint.setBlendMode(BlendMode.DST_IN);
        canvas.drawRect(colRect, lyMaskPaint);
        lyMaskPaint.setShader(null);
        canvas.restoreToCount(ms);
    }

    private void ensureBlurFilter(float sigma) {
        if (sigma == blurSigmaApplied && blurFilter != null) return;
        blurSigmaApplied = sigma;
        if (blurFilter != null) blurFilter.close();
        blurFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
        blurBasePaint.setImageFilter(blurFilter);
    }

    private void ensureBandShaders(float topY, float colH, float[] lit) {
        float fTop, fBot;
        if (lit != null && colH > 0f) {
            fTop = (lit[0] - topY) / colH;
            fBot = (lit[1] - topY) / colH;
            if (fBot < fTop) { float m = fTop; fTop = fBot; fBot = m; }
        } else {
            fTop = BAND_STOPS[1];
            fBot = BAND_STOPS[2];
        }
        if (topY == bandShaderTopY && colH == bandShaderColH && blurBandShader != null
                && Math.abs(fTop - bandFracTop) < 0.004f && Math.abs(fBot - bandFracBottom) < 0.004f) {
            return;
        }
        bandShaderTopY = topY;
        bandShaderColH = colH;
        bandFracTop = fTop;
        bandFracBottom = fBot;
        if (blurBandShader != null) blurBandShader.close();
        if (sharpBandShader != null) sharpBandShader.close();
        float[] stops = monotonic(fTop - BAND_RAMP, fTop, fBot, fBot + BAND_RAMP);
        int o = 0xFFFFFFFF, t = 0x00FFFFFF;
        blurBandShader = Shader.makeLinearGradient(0f, topY, 0f, topY + colH,
                new int[]{o, t, t, o}, stops);
        sharpBandShader = Shader.makeLinearGradient(0f, topY, 0f, topY + colH,
                new int[]{t, o, o, t}, stops);
    }

    private static float[] monotonic(float s0, float s1, float s2, float s3) {
        float[] s = {s0, s1, s2, s3};
        float eps = 1e-4f;
        for (int i = 0; i < s.length; i++) {
            if (s[i] < 0f) s[i] = 0f;
            else if (s[i] > 1f) s[i] = 1f;
        }
        for (int i = 1; i < s.length; i++) {
            if (s[i] <= s[i - 1]) s[i] = Math.min(1f, s[i - 1] + eps);
        }
        return s;
    }

    private void ensureLyricShaders(float topY, float colH) {
        if (topY == lyShaderTopY && colH == lyShaderColH && lyFadeShader != null) return;
        lyShaderTopY = topY;
        lyShaderColH = colH;
        if (lyFadeShader != null) lyFadeShader.close();
        float f = Math.min(0.4f, 40f / colH);
        lyFadeShader = Shader.makeLinearGradient(
                0f, topY, 0f, topY + colH,
                new int[]{0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, f, 1f - f, 1f});
    }

    public void renderOverlay(Canvas canvas, PlayerController controller,
                          SettingsBridge settings, DirectContext ctx,
                          float uiScale, int fbW, int fbH) {
    if (controller == null) return;
    int sc = canvas.save();
    // Undo acquireCanvas scale so drawLyricOverlay runs on identity canvas
    canvas.scale(1f / uiScale, 1f / uiScale);
    drawLyricOverlay(canvas, controller, settings, ctx, uiScale, fbW, fbH);
    canvas.restoreToCount(sc);
}
}
