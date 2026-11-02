package com.ylmac.adapt.render.font;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * 1. 每个字符用 AWT 渲染到透明 BufferedImage（白字，AA on，画在 y=fontSize）
 * 2. 纹理 RGBA 直通（未预乘）：边缘像素 = (255,255,255,α)
 * 3. GL glScalef(0.5) + SRC_ALPHA 混合 → 边缘 = 白×α + 背景×(1-α) → 无黑边
 *
 * 本实现：
 * - 同样 AWT 渲染每字符到位图（缓存一次）
 * - 转 Skia Image（RGBA_8888 UNPREMUL — Skia 内部自动预乘：
 * - drawImageRect 半尺寸绘制
 *
 * 对齐：AWT 位图顶部 = quadTop，无 Skia 字形基线偏移问题。
 *
 * 度量：height = boundsH/2；getStringWidth = 逐字符 ceil(boundsW)/2（8 空字符=0）。
 */
public final class RiseFont {

    private static final Map<String, RiseFont> CACHE = new HashMap<>();
    private static final java.awt.font.FontRenderContext FRC =
            new java.awt.font.FontRenderContext(new java.awt.geom.AffineTransform(), true, true);
    private static final String ALPHABET = "ABCDEFGHOKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /**
 * 字形位图的超采样倍率。
 * <p>
 * — 也就是每个字形只有 1 设计像素 1 位图像素。
 * 我们把 HUD 改成"原生设备像素"渲染后，画布倍率 ≈ 3.4（guiScale 3 × 面板缩放），
 * 位图被放大 1.7 倍 → 文字发虚（用户："左上角客户端名字和右上角功能列表文字依旧模糊"）。
 * 而积分榜/聊天框走的是 Skia <b>矢量</b>文字（drawVector），按设备分辨率光栅化 → 清晰。
 * <p>
 * 这里让 AWT 按 {@code 字号 × GLYPH_SUPER} 渲染位图、贴图时按 {@code 1/(2×GLYPH_SUPER)}
 * 缩小，字形形状/间距与原来完全一致，但位图分辨率 ≥ 设备分辨率 → 和矢量一样清晰。
 */
    private static final int GLYPH_SUPER = 4;

    private final java.awt.Font awt;          // 主字体（product_sans）
    private final java.awt.Font cjk;          // 中文 fallback（HarmonyOS_Sans_SC）
    private final float size;
    private final float height;               // height = boundsH / 2

    /** Skia 矢量 Typeface（BLOOM 发光用：矢量文字走几何路径，blur ImageFilter 才生效；
 * AWT 位图 drawImageRect 是纹理采样 pass，MoltenVK 上 blur filter 对其失效） */
    private final io.github.humbleui.skija.Typeface vectorTypeface;
    private final io.github.humbleui.skija.Typeface vectorCjkTypeface;
    private io.github.humbleui.skija.Font vectorFont;
    private io.github.humbleui.skija.Font vectorCjkFont;

    /**
 * 码点 → 能画出它的 Skia Font。主字体（product_sans）画不出来的字符按
 * 中文 → 系统字体逐级兜底，结果按码点缓存。
 * <p>
 * 为什么要这一层：{@code drawVector} 原先只在"主字体画不了但中文能画"时切到
 * 中文，其余一律用主字体 — 于是韩文、泰文、日文假名、emoji、各种符号
 * 全部画成空白/tofu（实测"小组件存在乱码，有些字符不会显示"）。音乐播放器
 * 系统字体，这里补上同样的能力。
 */
    private final Map<Integer, io.github.humbleui.skija.Font> cpFontCache = new HashMap<>();
    /** 系统兜底字体查询用的语言标签（与 lyric/skia/Fonts 的 Fallback 同一套思路）。 */
    private static final String[] BCP47 = {"zh-Hans", "zh-Hant", "ja", "ko", "th", "ar", "he", "hi"};

    // 字符位图缓存（AWT 渲染一次 → Skia Image 一次，永久复用）
    private final Map<Character, CharGlyph> glyphCache = new HashMap<>();

    private static final class CharGlyph {
        final Image image;   // UNPREMUL RGBA，白字
        final float w;       // 位图宽度（ceil(boundsW)+8）
        final float h;       // 位图高度（ceil(boundsH)）
        CharGlyph(Image image, float w, float h) { this.image = image; this.w = w; this.h = h; }
    }

    private RiseFont(java.awt.Font awt, java.awt.Font cjk, float size) {
        this.awt = awt;
        this.cjk = cjk;
        this.size = size;
        // awt/cjk 都是 size×GLYPH_SUPER 的字体，位图贴图时再缩 1/(2×GLYPH_SUPER)
        this.height = (float) (awt.getStringBounds(ALPHABET, FRC).getHeight() / (2.0 * GLYPH_SUPER));
        this.vectorTypeface = loadTypeface(awt.getFontName(), awt);
        this.vectorCjkTypeface = loadTypeface(cjk.getFontName(), cjk);
        // （位图路径 glScalef(0.5)、height = boundsH/2）。矢量路径之前用完整 size，
        // 字号是原版的两倍（用户："字号都明显不对"），阴影也被自身字形盖住（"没有阴影"）。
        this.vectorFont = vectorTypeface != null
                ? new io.github.humbleui.skija.Font(vectorTypeface, size / 2f) : null;
        this.vectorCjkFont = vectorCjkTypeface != null
                ? new io.github.humbleui.skija.Font(vectorCjkTypeface, size / 2f) : null;
    }

    /** 从资源加载 Skia Typeface（与 AWT 同款字体文件） */
    private static io.github.humbleui.skija.Typeface loadTypeface(String name, java.awt.Font awtFont) {
        try {
            // 从 AWT 字体推断资源文件名（product_sans / HarmonyOS_Sans_SC）
            String lower = name.toLowerCase();
            String file;
            if (lower.contains("harmonyos")) {
                file = lower.contains("medium") ? "HarmonyOS_Sans_SC_Medium.ttf" : "HarmonyOS_Sans_SC_Regular.ttf";
            } else if (lower.contains("bold")) {
                file = "product_sans_bold.ttf";
            } else if (lower.contains("medium")) {
                file = "product_sans_medium.ttf";
            } else {
                file = "product_sans_regular.ttf";
            }
            String[] dirs = {
                    "/assets/adaptclient/fonts/",
            };
            for (String dir : dirs) {
                try (java.io.InputStream in = RiseFont.class.getResourceAsStream(dir + file)) {
                    if (in == null) continue;
                    byte[] data = in.readAllBytes();
                    if (data == null || data.length == 0) continue;
                    return io.github.humbleui.skija.FontMgr.getDefault().makeFromData(
                            io.github.humbleui.skija.Data.makeFromBytes(data));
                } catch (Exception ignored) {
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Skia 矢量文字（BLOOM 发光层用）：几何路径绘制，blur ImageFilter 生效。
 * 基线 = y + height */
    public void drawVector(Canvas canvas, String text, float x, float y, int color) {
        Paint p = new Paint().setColor(color);
        // 按**码点**遍历（不是 char）：emoji 等增补平面字符是代理对，按 char 切
        // 会把一个字符拆成两半分别绘制 → 两半都画不出来（乱码/缺字的来源之一）。
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            String s = text.substring(i, i + n);
            io.github.humbleui.skija.Font f = fontForCodepoint(cp);
            if (f != null) {
                if (!Character.isWhitespace(cp)) {
                    // ⚠ 必须先画、再前进宽度：Skia 的 drawString 以 x 为字符起始
                    // （左边缘），若先前进再画，每个字符都右移一个宽度 → 整串
                    // 文字偏移 → 光晕画在错误位置。
                    canvas.drawString(s, x, y + height, f, p);
                }
                x += f.measureTextWidth(s);
            }
            i += n;
        }
        p.close();
    }

    /**
 * 按 {@code FormattedCharSequence} 的样式分段绘制（保留 § 颜色 / 组件颜色），
 * （带阴影）与 {@code a(...)}（不带）。
 * <p>
 * 之前积分榜/聊天框用 {@code Component.getString} 拿到的是**纯文本**（§ 码全丢），
 * 所以颜色没了、特殊符号也没法解析（用户："特殊符号也没有办法解析"）。
 *
 * @return 整段宽度（设计像素）
 */
    public static float drawStyled(io.github.humbleui.skija.Canvas canvas,
                                   net.minecraft.util.FormattedCharSequence seq,
                                   RiseFont font, float x, float y,
                                   int baseColor, int alpha, boolean shadow) {
        java.util.List<Object[]> runs = new java.util.ArrayList<>(4);
        StringBuilder sb = new StringBuilder();
        int[] cur = {baseColor};
        seq.accept((net.minecraft.util.FormattedCharSink) (index, style, codepoint) -> {
            int col = style != null && style.getColor() != null
                    ? style.getColor().getValue() : baseColor;
            if (col != cur[0]) {
                if (sb.length() > 0) runs.add(new Object[]{cur[0], sb.toString()});
                sb.setLength(0);
                cur[0] = col;
            }
            sb.appendCodePoint(codepoint);
            return true;
        });
        if (sb.length() > 0) runs.add(new Object[]{cur[0], sb.toString()});

        float cx = x;
        for (Object[] run : runs) {
            String text = (String) run[1];
            if (text.isEmpty()) continue;
            int rgb = (Integer) run[0];
            if (shadow) {
                font.drawVector(canvas, text, cx + 1f, y + 1f, shadowOf(rgb, alpha));
            }
            font.drawVector(canvas, text, cx, y, withAlpha(rgb, alpha));
            cx += font.getVectorWidth(text);
        }
        return cx - x;
    }

 /** 原版阴影色：每通道 ÷4。 */
    public static int shadowOf(int rgb, int alpha) {
        int r = ((rgb >> 16) & 0xFF) / 4;
        int g = ((rgb >> 8) & 0xFF) / 4;
        int b = (rgb & 0xFF) / 4;
        return withAlpha((r << 16) | (g << 8) | b, alpha);
    }

    public static int withAlpha(int rgb, int a) {
        int alpha = Math.max(0, Math.min(255, a));
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    public float height() { return height; }

    /** 缓存 key（宽度缓存按字体区分，模式切换字体变 → 重算） */
    public String cacheKey() { return size + ":" + awt.getFontName(); }

    private static boolean isChinese(char c) {
        if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) return true;
        if (c >= 0x3000 && c <= 0x303F) return true;  // CJK 标点
        if (c >= 0xFF00 && c <= 0xFFEF) return true;  // 全角
        return "·…–—‘’“”".indexOf(c) >= 0;
    }

 /** 选择 AWT 字体 */
    private java.awt.Font fontFor(char c) {
        return (isChinese(c) && cjk != null) ? cjk : awt;
    }

    /**
 * AWT 渲染字符到位图：
 * - 位图 w = ceil(boundsW)+8, h = ceil(boundsH)
 * - 透明填充，白字画在 (4, fontSize)
 * - AA on + RENDER_QUALITY
 */
    private CharGlyph renderChar(char c) {
        java.awt.Font f = fontFor(c);
        if (!f.canDisplay(c)) return null;
        Rectangle2D b = f.getStringBounds(String.valueOf(c), FRC);
        int w = (int) Math.ceil(b.getWidth()) + 8 * GLYPH_SUPER;
        int h = (int) Math.ceil(b.getHeight());
        if (w <= 0 || h <= 0) return null;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(255, 255, 255, 0));
        g.fillRect(0, 0, w, h);
        g.setFont(f);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(java.awt.Color.WHITE);
        g.drawString(String.valueOf(c), 4 * GLYPH_SUPER, (int) (size * GLYPH_SUPER));
        g.dispose();

        // AWT int ARGB (0xAARRGGBB) → RGBA_8888 字节序 (R,G,B,A) UNPREMUL
        byte[] px = new byte[w * h * 4];
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        int p = 0;
        for (int i = 0; i < argb.length; i++) {
            int v = argb[i];
            px[p++] = (byte) (v >> 16);       // R
            px[p++] = (byte) (v >> 8);        // G
            px[p++] = (byte) (v);             // B
            px[p++] = (byte) (v >> 24);       // A（未预乘：RGB 保持白）
        }
        Image image = Image.makeRasterFromBytes(
                new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
                px, w * 4L);
        return new CharGlyph(image, w, h);
    }

    private CharGlyph glyph(char c) {
        CharGlyph g = glyphCache.get(c);
        if (g == null) {
            g = renderChar(c);
            glyphCache.put(c, g);
        }
        return g;
    }

 /** 单字符超采样宽度，缓存 */
    private float charWidth(char c) {
        CharGlyph g = glyph(c);
        return g == null ? 0f : g.w - 8f * GLYPH_SUPER;
    }

    public float getStringWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        // 字面量替换（replaceAll 每次编译正则 = 性能炸弹）
        s = s.replace("§l", "");
        int j = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 167) { i++; continue; } // § 格式码跳过
            if (c == 8) continue;            // 8 空字符
            j += (int) charWidth(c);
        }
        return j / (2 * GLYPH_SUPER);
    }

    /** 单字符屏幕宽度（logo 逐字符用） */
    public float getCharWidth(char c) {
        return charWidth(c) / (2f * GLYPH_SUPER);
    }

    /** Typeface 是否带这个码点的字形。 */
    private static boolean covers(io.github.humbleui.skija.Typeface t, int cp) {
        if (t == null) return false;
        try {
            return t.getUTF32Glyph(cp) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 码点 → 能画出它的 Font（主字体 → 中文 → 系统字体），带缓存。 */
    private io.github.humbleui.skija.Font fontForCodepoint(int cp) {
        io.github.humbleui.skija.Font cached = cpFontCache.get(cp);
        if (cached != null) return cached;
        io.github.humbleui.skija.Font chosen = null;
        if (covers(vectorTypeface, cp)) {
            chosen = vectorFont;
        } else if (covers(vectorCjkTypeface, cp)) {
            chosen = vectorCjkFont;
        } else {
            chosen = resolveSystemFont(cp);
        }
        if (chosen == null) {
            chosen = vectorFont != null ? vectorFont : vectorCjkFont;
        }
        cpFontCache.put(cp, chosen);
        return chosen;
    }

    /** 问系统要一个覆盖该码点的字体（找不到返回 null）。 */
    private io.github.humbleui.skija.Font resolveSystemFont(int cp) {
        try {
            io.github.humbleui.skija.FontMgr mgr = io.github.humbleui.skija.FontMgr.getDefault();
            if (mgr == null) return null;
            io.github.humbleui.skija.FontStyle style = io.github.humbleui.skija.FontStyle.NORMAL;
            for (String tag : BCP47) {
                io.github.humbleui.skija.Typeface t = mgr.matchFamilyStyleCharacter(
                        null, style, new String[]{tag}, cp);
                if (covers(t, cp)) {
                    return new io.github.humbleui.skija.Font(t, size / 2f);
                }
            }
        } catch (Throwable ignored) {
            // 某些平台/字体缺失时直接放弃，退回主字体
        }
        return null;
    }

    /**
 * 矢量文字（{@link #drawVector}）的**实际**像素宽度。
 * <p>
 * 不能用 {@link #getStringWidth}：那个是位图路径的度量 — 字符宽度来自
 * AWT 在 2× 尺寸下预渲染的字形位图，最后整串除以 2（{@code j / 2}），
 * 所以只有矢量文字真实宽度的一半。拿它去开画布，文字会被裁掉一半
 * （实测 5 个汉字只显示 3 个）。这里用与 drawVector 完全相同的 Skija 字体
 * 逐字符 measureTextWidth，含中文 fallback 判断。
 */
    public float getVectorWidth(String s) {
        if (s == null || s.isEmpty() || vectorFont == null) return 0;
        float w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            io.github.humbleui.skija.Font f = fontForCodepoint(cp);
            if (f != null) {
                w += f.measureTextWidth(s.substring(i, i + n));
            }
            i += n;
        }
        return w;
    }

    // 颜色 → ColorFilter 缓存（drawImageRect 不响应 paint.setColor，须用 SRC_IN 染色）
    private final Map<Integer, io.github.humbleui.skija.ColorFilter> colorFilters = new HashMap<>();

    private io.github.humbleui.skija.ColorFilter colorFilter(int color) {
        io.github.humbleui.skija.ColorFilter cf = colorFilters.get(color);
        if (cf == null) {
            cf = io.github.humbleui.skija.ColorFilter.makeBlend(color, io.github.humbleui.skija.BlendMode.SRC_IN);
            colorFilters.put(color, cf);
        }
        return cf;
    }

    /**
 * 整串文字预合成白字位图（性能关键：逐字符 drawImageRect → 每行 1 次 drawImageRect）。
 * 合成语义与 draw 完全一致：字符按 (w-8)/2 间距排，空格/无字形占位透明。
 * 高度 = 最高字符位图高；宽度 = sum(w-8) + 首字符左边距 4px + 末字符右边距 4px。
 * 缓存 per text（文字内容不变时复用，GPU 纹理一次上传）。
 */
    private final Map<String, TextImage> textCache = new HashMap<>();

    private static final class TextImage {
        final io.github.humbleui.skija.Image image;
        final float w, h;   // 合成图尺寸（超采样像素）
        final float totalW; // 字符总宽（浮点和，无 ceil 误差）
        TextImage(io.github.humbleui.skija.Image image, float w, float h, float totalW) { this.image = image; this.w = w; this.h = h; this.totalW = totalW; }
    }

    public TextImage textImage(String text) {
        TextImage cached = textCache.get(text);
        if (cached != null) return cached;
        // 计算总宽/最高
        float totalW = 0; float maxH = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 167) { i++; continue; }
            if (c == 8) continue;
            CharGlyph g = glyph(c);
            if (g == null) continue;
            totalW += (g.w - 8f * GLYPH_SUPER);
            maxH = Math.max(maxH, g.h);
        }
        if (totalW <= 0 || maxH <= 0) return null;
        // 关键：画布宽必须 = ceil(totalW) + 8 — 最后一个字符的位图宽 = ceil+8，
        // 画在 cx 后右边缘延伸到 totalW+8。若画布只有 ceil(totalW)，最后一个字符
        // 右 8px（约半个字符）被画布裁剪 → "最后一个字三分之一被挡住"。
        int W = (int) Math.ceil(totalW) + 8 * GLYPH_SUPER;
        int H = (int) Math.ceil(maxH);
        // 把各字符位图画到合成画布（CPU，一次）
        io.github.humbleui.skija.Surface tmp = io.github.humbleui.skija.Surface.makeRasterN32Premul(W, H);
        Canvas tc = tmp.getCanvas();
        tc.clear(0x00000000);
        float cx = 0f;   // 无左边距（逐字符位图 0 左边缘 = x-2，主体从 x 开始）
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 167) { i++; continue; }
            if (c == 8) continue;
            CharGlyph g = glyph(c);
            if (g == null) continue;
 // 位图左边缘在 cx，文字主体在 cx+2
            tc.drawImageRect(g.image,
                    Rect.makeXYWH(0, 0, g.w, g.h),
                    Rect.makeXYWH(cx, 0, g.w, g.h),
                    new Paint());
            cx += (g.w - 8f * GLYPH_SUPER);
        }
        io.github.humbleui.skija.Image img = tmp.makeImageSnapshot();
        tmp.close();
        TextImage ti = new TextImage(img, W, H, totalW);
        textCache.put(text, ti);
        return ti;
    }

    /** 整串文字绘制：1 次 drawImageRect + ColorFilter 染色（性能：替代逐字符） */
    public void drawText(Canvas canvas, String text, float x, float y, int color, Paint paint) {
        TextImage ti = textImage(text);
        if (ti == null) return;
        paint.setColorFilter(colorFilter(color));
        float quadTop = y - 2.0f - height / 10.0f;
        // 合成图 → 半尺寸。src = 全图（含末字符右边距 8px），dst 宽 = w/2 —
        float dw = ti.w / (2f * GLYPH_SUPER);
        canvas.drawImageRect(ti.image,
                Rect.makeXYWH(0, 0, ti.w, ti.h),
                Rect.makeXYWH(x - 2f, quadTop, dw, ti.h / (2f * GLYPH_SUPER)),
                paint);
        paint.setColorFilter(null);
    }

    /**
 * 位图顶部 = y - 2 - fontHeight/10（quadTop）；半尺寸绘制。
 * 空白/无字形：只前进宽度，不绘制（U+2009 等不会显示豆腐）。
 *
 * 黑边根治：UNPREMUL 白字位图 → Skia 自动预乘 → SRC_OVER 混合 =
 * 白×α + 背景×(1-α)。
 * 染色：drawImageRect 不响应 paint.setColor → 用 ColorFilter(SRC_IN) 按 color 染色。
 */
    public void draw(Canvas canvas, String text, float x, float y, int color, Paint paint) {
        paint.setColorFilter(colorFilter(color));
        float quadTop = y - 2.0f - height / 10.0f;
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 167) { i++; continue; }
            if (c == 8) continue;                    // 8 空字符
            CharGlyph g = glyph(c);
            if (g == null) continue;                 // 无字形（U+2009 等）：不画不占位
            if (Character.isWhitespace(c)) { cx += (g.w - 8f * GLYPH_SUPER) / (2f * GLYPH_SUPER); continue; } // 空格只前进
 // 位图 w×h 缩放到 w/2 × h/2，顶部 quadTop
 // dst.x = cx - 2
            canvas.drawImageRect(g.image,
                    Rect.makeXYWH(0, 0, g.w, g.h),
                    Rect.makeXYWH(cx - 2f, quadTop, g.w / (2f * GLYPH_SUPER), g.h / (2f * GLYPH_SUPER)),
                    paint);
            cx += (g.w - 8f * GLYPH_SUPER) / (2f * GLYPH_SUPER);
        }
        paint.setColorFilter(null);
    }

    /**
 * 模糊文字绘制：
 * 把文字画进离屏层，restore 时应用高斯模糊（ImageFilter.makeBlur），
 * 输出 = 模糊发光文字。
 * 颜色由 paint 决定（黑色阴影 / 行色发光）。
 */
    public void drawBlurred(Canvas canvas, String text, float x, float y, int color,
                            Paint paint, io.github.humbleui.skija.ImageFilter blur) {
        // 离屏层：restore 时把层内容模糊后合成
        Paint layerPaint = new Paint().setImageFilter(blur);
        int save = canvas.saveLayer(null, layerPaint);
        draw(canvas, text, x, y, color, paint);
        canvas.restoreToCount(save);
        layerPaint.close();
    }

    public static RiseFont get(String file, float size) {
        String key = file + ":" + size;
        RiseFont f = CACHE.get(key);
        if (f != null) return f;
        // AWT 按 size×GLYPH_SUPER 渲染（位图超采样），贴图时再缩回去
        float raster = size * GLYPH_SUPER;
        java.awt.Font awt = Typefaces.awt(file, raster);
        String cjkFile = file.contains("medium") || file.contains("bold")
                ? "HarmonyOS_Sans_SC_Medium.ttf" : "HarmonyOS_Sans_SC_Regular.ttf";
        java.awt.Font cjk = Typefaces.awt(cjkFile, raster);
        f = new RiseFont(awt, cjk, size);
        CACHE.put(key, f);
        return f;
    }

    public static RiseFont main(float size, Weight weight) {
        return get(weight.file, size);
    }

    public enum Weight {
        REGULAR("product_sans_regular.ttf"),
        MEDIUM("product_sans_medium.ttf"),
        BOLD("product_sans_bold.ttf"),
        LIGHT("product_sans_light.ttf");

        final String file;
        Weight(String file) { this.file = file; }
    }
}
