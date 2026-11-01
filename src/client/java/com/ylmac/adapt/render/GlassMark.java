package com.ylmac.adapt.render;

import com.ylmac.adapt.AdaptClient;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.ColorMatrix;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/**
 * 左上角的 “a”。字形就是玻璃：边缘按圆角透镜那套往里折射，中间几乎不动，
 * 再加一条朝左上的亮边。源是这一帧的世界颜色，不是那张 σ12 的卡片模糊。
 */
final class GlassMark {

    private static final float X = 6f;
    private static final float Y = 6f;
    /** 设计像素。字身大约半个 em，笔画要宽过折射带，中间才留得住。 */
    private static final float FONT_PX = 72f;
    /** 边缘梯度的宽度。太宽整笔都会被拉走。 */
    private static final float FIELD_SIGMA = 1.8f;
    private static final float REFRACTION = 4f;

    private static final String SKSL = """
            uniform shader content;
            uniform shader field;
            uniform shader cover;
            uniform float refraction;
            uniform float2 center;
            uniform float soft;

            float circleMap(float x) {
                return 1.0 - sqrt(max(0.0, 1.0 - x * x));
            }

            half3 straight(half4 s) {
                return s.a > 0.04 ? s.rgb / s.a : s.rgb;
            }

            half3 blurred(float2 p) {
                float r = soft;
                half3 c = straight(content.eval(p)) * 0.40;
                c += straight(content.eval(p + float2(r, 0.0))) * 0.15;
                c += straight(content.eval(p + float2(-r, 0.0))) * 0.15;
                c += straight(content.eval(p + float2(0.0, r))) * 0.15;
                c += straight(content.eval(p + float2(0.0, -r))) * 0.15;
                return c;
            }

            half4 main(float2 coord) {
                half4 ink = cover.eval(coord);
                if (ink.a < 0.02) return half4(0.0, 0.0, 0.0, 0.0);
                float e = max(soft, 1.0);
                float left = field.eval(coord + float2(-e, 0.0)).a;
                float right = field.eval(coord + float2(e, 0.0)).a;
                float up = field.eval(coord + float2(0.0, -e)).a;
                float down = field.eval(coord + float2(0.0, e)).a;
                float2 g = float2(right - left, down - up);
                float glen = length(g);
                float2 n = glen > 0.001 ? g / glen : float2(0.0, 0.0);
                float edge = clamp((1.0 - field.eval(coord).a) / 0.62, 0.0, 1.0);
                float2 toCenter = center - coord;
                float clen = length(toCenter);
                float2 dir = n;
                if (clen > 0.5 && glen > 0.001) {
                    dir = normalize(n + 0.45 * (toCenter / clen));
                }
                float disp = circleMap(edge) * refraction;
                half3 rgb = blurred(coord + disp * dir);
                float luma = dot(rgb, half3(0.213, 0.715, 0.072));
                rgb = mix(half3(luma), rgb, 1.28);
                rgb = mix(rgb, half3(1.0, 1.0, 1.0), 0.07);
                float spec = pow(clamp(dot(-n, float2(-0.7071, -0.7071)), 0.0, 1.0), 1.15) * edge;
                rgb = rgb + half3(spec * 0.72);
                float a = ink.a;
                return half4(rgb * a, a);
            }
            """;

    private static Typeface typeface;
    private static Path glyph;
    private static float glyphW;
    private static float glyphH;
    private static float padDesign;
    private static Image cover;
    private static Image field;
    private static int maskKey = Integer.MIN_VALUE;
    private static RuntimeEffect effect;
    private static boolean effectFailed;
    private static boolean fontFailed;
    private static boolean ready;
    private static boolean loggedGlyph;
    private static Surface cropSurface;
    private static Surface shadeSurface;
    private static int surfaceW;
    private static int surfaceH;
    private static ColorFilter forceAlpha;
    private static ImageFilter cropBlur;
    private static int cropBlurKey = Integer.MIN_VALUE;

    private GlassMark() {}

    /** 光晕已经叠回主画布之后调用。字形盖住的地方光晕去掉，字洞里留着。 */
    static void punchBloom(Canvas canvas, float deviceScale) {
        if (!ensure(deviceScale) || cover == null) return;
        Paint paint = new Paint();
        paint.setBlendMode(BlendMode.DST_OUT);
        paint.setAntiAlias(true);
        canvas.drawImageRect(cover, placedRect(), paint);
        paint.close();
    }

    static void draw(Canvas canvas, Image world, int guiW, int guiH, float deviceScale) {
        if (!ensure(deviceScale)) return;
        if (world == null || guiW <= 0 || guiH <= 0) {
            drawFallback(canvas);
            return;
        }
        Image glass = null;
        try {
            glass = shade(world, guiW, guiH, deviceScale);
            if (glass == null) {
                drawFallback(canvas);
                return;
            }
            Paint paint = new Paint().setAntiAlias(true);
            canvas.drawImageRect(glass, placedRect(), paint);
            paint.close();
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[GlassMark] draw failed: {}", t.toString());
            drawFallback(canvas);
        } finally {
            if (glass != null) glass.close();
        }
    }

    /**
     * 遮罩和世界裁块都是同一张像素图。着色器的坐标就是这张图的像素，
     * 不再拿画布上的设计坐标去乘矩阵 —— 上次那样采样落到字形外面，字是全透明的。
     */
    private static Image shade(Image world, int guiW, int guiH, float deviceScale) {
        int pw = cover.getWidth();
        int ph = cover.getHeight();
        if (!ensureSurfaces(pw, ph)) return null;
        float span = glyphW + padDesign * 2f;
        float scale = pw / span;

        Canvas crop = cropSurface.getCanvas();
        crop.restoreToCount(1);
        crop.clear(0xFF000000);
        Paint blit = new Paint();
        blit.setBlendMode(BlendMode.SRC);
        blit.setColorFilter(opaqueAlpha());
        crop.drawImageRect(world, worldSrc(world, guiW, guiH), Rect.makeWH(pw, ph),
                SamplingMode.LINEAR, blit, false);
        blit.close();
        Image sharp = cropSurface.makeImageSnapshot();

        Canvas shade = shadeSurface.getCanvas();
        shade.restoreToCount(1);
        shade.clear(0xFF000000);
        float sigma = 1.5f * scale;
        Paint blurPaint = new Paint();
        blurPaint.setBlendMode(BlendMode.SRC);
        blurPaint.setImageFilter(cropBlur(sigma));
        shade.drawImage(sharp, 0, 0, blurPaint);
        blurPaint.close();
        sharp.close();
        Image blurred = shadeSurface.makeImageSnapshot();
        if (effect == null) return blurred;

        Shader content = null;
        Shader fieldShader = null;
        Shader coverShader = null;
        Shader shader = null;
        RuntimeEffectBuilder builder = null;
        Paint paint = null;
        boolean shaded = false;
        try {
            content = blurred.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                    SamplingMode.LINEAR, Matrix33.IDENTITY);
            fieldShader = field.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                    SamplingMode.LINEAR, Matrix33.IDENTITY);
            coverShader = cover.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                    SamplingMode.LINEAR, Matrix33.IDENTITY);
            builder = new RuntimeEffectBuilder(effect);
            builder.setChild("content", content);
            builder.setChild("field", fieldShader);
            builder.setChild("cover", coverShader);
            builder.setUniform("refraction", REFRACTION * scale);
            builder.setUniform("soft", Math.max(1f, 1.5f * scale));
            builder.setUniform("center",
                    (padDesign + glyphW * 0.5f) * scale,
                    (padDesign + glyphH * 0.5f) * scale);
            shader = builder.makeShader();
            paint = new Paint().setShader(shader).setBlendMode(BlendMode.SRC);
            // 写到另一张表面上。blurred 还指着 shadeSurface，不能边读边清。
            crop.clear(0);
            crop.drawRect(Rect.makeWH(pw, ph), paint);
            Image glass = cropSurface.makeImageSnapshot();
            shaded = true;
            return glass;
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[GlassMark] shade failed: {}", t.toString());
            return blurred;
        } finally {
            if (paint != null) paint.close();
            if (shader != null) shader.close();
            if (builder != null) builder.close();
            if (content != null) content.close();
            if (fieldShader != null) fieldShader.close();
            if (coverShader != null) coverShader.close();
            if (shaded) blurred.close();
        }
    }

    private static boolean ensure(float deviceScale) {
        if (fontFailed) return false;
        if (!ready && !loadFont()) return false;
        if (deviceScale < 0.5f) deviceScale = 1f;
        int key = Math.round(deviceScale * 50f);
        if (cover != null && key == maskKey) return true;
        return rebuildMask(deviceScale, key);
    }

    private static boolean loadFont() {
        try (java.io.InputStream in = GlassMark.class.getResourceAsStream(
                "/assets/adaptclient/fonts/IntelOneMono-Medium.ttf")) {
            if (in == null) {
                fontFailed = true;
                AdaptClient.LOGGER.warn("[GlassMark] Intel One Mono missing");
                return false;
            }
            byte[] bytes = in.readAllBytes();
            Data data = Data.makeFromBytes(bytes);
            typeface = FontMgr.getDefault().makeFromData(data);
            data.close();
            if (typeface == null) {
                fontFailed = true;
                return false;
            }
            Font font = new Font(typeface, FONT_PX);
            short[] glyphs = font.getStringGlyphs("a");
            font.close();
            if (glyphs == null || glyphs.length == 0) {
                fontFailed = true;
                return false;
            }
            Font font2 = new Font(typeface, FONT_PX);
            Path raw = font2.getPath(glyphs[0]);
            font2.close();
            if (raw == null || raw.isEmpty()) {
                fontFailed = true;
                return false;
            }
            Rect bounds = raw.getBounds();
            glyph = raw.makeOffset(-bounds.getLeft(), -bounds.getTop());
            raw.close();
            Rect placed = glyph.getBounds();
            glyphW = Math.max(1f, placed.getWidth());
            glyphH = Math.max(1f, placed.getHeight());
            padDesign = (float) Math.ceil(FIELD_SIGMA * 3f + REFRACTION + 2f);
            if (!loggedGlyph) {
                loggedGlyph = true;
                AdaptClient.LOGGER.info("[GlassMark] glyph {}x{}", glyphW, glyphH);
            }
            if (!effectFailed && effect == null) {
                try {
                    effect = RuntimeEffect.makeForShader(SKSL);
                    if (effect == null) effectFailed = true;
                } catch (Throwable t) {
                    effectFailed = true;
                    AdaptClient.LOGGER.warn("[GlassMark] shader failed: {}", t.toString());
                }
            }
            ready = true;
            return true;
        } catch (Throwable t) {
            fontFailed = true;
            AdaptClient.LOGGER.warn("[GlassMark] font failed: {}", t.toString());
            return false;
        }
    }

    private static boolean rebuildMask(float deviceScale, int key) {
        int pw = Math.max(1, Math.round((glyphW + padDesign * 2f) * deviceScale));
        int ph = Math.max(1, Math.round((glyphH + padDesign * 2f) * deviceScale));
        Surface raster = Surface.makeRasterN32Premul(pw, ph);
        if (raster == null) return false;
        Canvas c = raster.getCanvas();
        c.scale(deviceScale, deviceScale);
        c.translate(padDesign, padDesign);
        Paint paint = new Paint().setColor(0xFFFFFFFF).setAntiAlias(true);
        c.drawPath(glyph, paint);
        paint.close();
        Image nextCover = raster.makeImageSnapshot();
        raster.close();
        if (nextCover == null) return false;

        Surface blurred = Surface.makeRasterN32Premul(pw, ph);
        if (blurred == null) {
            nextCover.close();
            return false;
        }
        float sigmaPx = FIELD_SIGMA * deviceScale;
        ImageFilter blur = ImageFilter.makeBlur(sigmaPx, sigmaPx, FilterTileMode.DECAL);
        Paint blurPaint = new Paint().setImageFilter(blur);
        blurred.getCanvas().drawImage(nextCover, 0, 0, blurPaint);
        blurPaint.close();
        blur.close();
        Image nextField = blurred.makeImageSnapshot();
        blurred.close();
        if (nextField == null) {
            nextCover.close();
            return false;
        }
        if (cover != null) cover.close();
        if (field != null) field.close();
        cover = nextCover;
        field = nextField;
        maskKey = key;
        return true;
    }

    private static Rect placedRect() {
        return Rect.makeXYWH(X - padDesign, Y - padDesign,
                glyphW + padDesign * 2f, glyphH + padDesign * 2f);
    }

    private static boolean ensureSurfaces(int pw, int ph) {
        if (cropSurface != null && shadeSurface != null && pw == surfaceW && ph == surfaceH) return true;
        if (cropSurface != null) cropSurface.close();
        if (shadeSurface != null) shadeSurface.close();
        cropSurface = null;
        shadeSurface = null;
        DirectContext ctx = GpuContextHelper.getOrCreateContext();
        ImageInfo info = ImageInfo.makeN32Premul(pw, ph);
        if (ctx != null) {
            cropSurface = Surface.makeRenderTarget(ctx, false, info);
            shadeSurface = Surface.makeRenderTarget(ctx, false, info);
        }
        if (cropSurface == null) cropSurface = Surface.makeRasterN32Premul(pw, ph);
        if (shadeSurface == null) shadeSurface = Surface.makeRasterN32Premul(pw, ph);
        if (cropSurface == null || shadeSurface == null) return false;
        surfaceW = pw;
        surfaceH = ph;
        return true;
    }

    private static ColorFilter opaqueAlpha() {
        if (forceAlpha == null) {
            forceAlpha = ColorFilter.makeMatrix(new ColorMatrix(
                    1, 0, 0, 0, 0,
                    0, 1, 0, 0, 0,
                    0, 0, 1, 0, 0,
                    0, 0, 0, 0, 1));
        }
        return forceAlpha;
    }

    private static ImageFilter cropBlur(float sigma) {
        int key = Math.round(sigma * 100f);
        if (cropBlur != null && key == cropBlurKey) return cropBlur;
        if (cropBlur != null) cropBlur.close();
        cropBlur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
        cropBlurKey = key;
        return cropBlur;
    }

    /** 和列表磨砂同一条：设计坐标 → 帧缓冲。范围含字形外面一圈，折射才有像素可采。 */
    private static Rect worldSrc(Image img, int guiW, int guiH) {
        float dx = X - padDesign;
        float dy = Y - padDesign;
        float dw = glyphW + padDesign * 2f;
        float dh = glyphH + padDesign * 2f;
        float sc = Math.min(guiW / 854f, guiH / 480f);
        float offX = (guiW - 854f * sc) / 2f;
        float gx = offX + dx * sc;
        float gy = dy * sc;
        float gw = dw * sc;
        float gh = dh * sc;
        float imgW = img.getWidth();
        float imgH = img.getHeight();
        return Rect.makeXYWH(gx * imgW / guiW, gy * imgH / guiH, gw * imgW / guiW, gh * imgH / guiH);
    }

    private static void drawFallback(Canvas canvas) {
        if (glyph == null) return;
        Paint paint = new Paint().setColor(0x8CFFFFFF).setAntiAlias(true);
        canvas.save();
        canvas.translate(X, Y);
        canvas.drawPath(glyph, paint);
        canvas.restore();
        paint.close();
    }
}
