package com.ylmac.adapt.features.module.impl.render.gui;

import java.awt.Color;

/**
 * Rise 6.9.5 主题系统移植（ui/theme/Themes.java + KeyColors + ThemeManager）。
 * <p>
 * 说明（用户要求：新建的系统只能放在 render 模块目录下，不污染项目其它位置）：
 * 本文件位于 {@code features/module/impl/render/gui/}，只服务 Rise 风格 ClickGUI。
 * <p>
 * 强调色公式（Themes.getAccentColor / getBlendFactor 原式）：
 * <pre>
 *   blend = sin(t_ms/600 + x*0.005 + y*0.06) * 0.5 + 0.5
 *   双色: ColorUtil.a(c1, c2, blend)          // = c1*t + c2*(1-t)
 *   三色: blend<=0.5 ? a(c2, c1, blend*2) : a(c3, c2, (blend-0.5)*2)
 *   Rainbow: Color.getHSBColor(ceil((now + (x+y)*10)/10) % 360 / 360, 0.6, 1)
 * </pre>
 */
public final class RiseTheme {

    /** Rise KeyColors（主题筛选色块，10 个） */
    public enum KeyColor {
        RED(255, 50, 50), ORANGE(255, 128, 50), YELLOW(255, 255, 50), LIME(128, 255, 50),
        DARK_GREEN(50, 128, 50), AQUA(50, 200, 255), DARK_BLUE(50, 100, 200), PURPLE(128, 50, 255),
        PINK(255, 128, 255), GRAY(100, 100, 110);

        public final int rgb;

        KeyColor(int r, int g, int b) {
            this.rgb = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private final String name;
    private final int c1, c2, c3;         // c3 = 0 → 双色主题
    private final KeyColor[] keys;
    private final boolean rainbow;

    private RiseTheme(String name, int c1, int c2, int c3, boolean rainbow, KeyColor... keys) {
        this.name = name;
        this.c1 = c1;
        this.c2 = c2;
        this.c3 = c3;
        this.rainbow = rainbow;
        this.keys = keys;
    }

    public String name() { return name; }

    public KeyColor[] keys() { return keys; }

    public boolean hasKey(KeyColor k) {
        for (KeyColor key : keys) if (key == k) return true;
        return false;
    }

    /** 主题代表色（卡片/色块预览用，等价 Rise rA()） */
    public int primary() { return c1; }

    public int secondary() { return c2; }

    public int tertiary() { return c3; }

    public boolean triColor() { return c3 != 0; }

    /** Themes.getBlendFactor(vector2d) 原式 */
    public static double blendFactor(float x, float y) {
        return Math.sin(System.currentTimeMillis() / 600.0 + x * 0.005 + y * 0.06) * 0.5 + 0.5;
    }

    /** Themes.getAccentColor(vector2d) 原式（返回 0xFF000000|RGB） */
    public int accentAt(float x, float y) {
        if (rainbow) {
            // ColorUtil.aB((int)((x + y) * 10))
            int offset = (int) ((x + y) * 10.0);
            double hue = Math.ceil((System.currentTimeMillis() + offset) / 10.0) % 360.0 / 360.0;
            return 0xFF000000 | (Color.HSBtoRGB((float) hue, 0.6f, 1.0f) & 0xFFFFFF);
        }
        double f = blendFactor(x, y);
        if (triColor()) {
            return f <= 0.5 ? lerp(c2, c1, f * 2.0) : lerp(c3, c2, (f - 0.5) * 2.0);
        }
        return lerp(c1, c2, f);
    }

    /** ColorUtil.a(c1, c2, t) = c1·t + c2·(1−t)（alpha 固定 255） */
    private static int lerp(int c1, int c2, double t) {
        double d = 1.0 - t;
        int r = (int) (((c1 >> 16) & 0xFF) * t + ((c2 >> 16) & 0xFF) * d);
        int g = (int) (((c1 >> 8) & 0xFF) * t + ((c2 >> 8) & 0xFF) * d);
        int b = (int) ((c1 & 0xFF) * t + (c2 & 0xFF) * d);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int rgb(int r, int g, int b) { return 0xFF000000 | (r << 16) | (g << 8) | b; }

    // ─────────────────── 39 个主题（Rise Themes 枚举原值） ───────────────────

    public static final RiseTheme[] ALL = {
            new RiseTheme("Aubergine", rgb(170, 7, 107), rgb(97, 4, 95), 0, false, KeyColor.PURPLE, KeyColor.RED),
            new RiseTheme("Aqua", rgb(185, 250, 255), rgb(79, 199, 200), 0, false, KeyColor.AQUA),
            new RiseTheme("Banana", rgb(253, 236, 177), rgb(255, 255, 255), 0, false, KeyColor.YELLOW),
            new RiseTheme("Blend", rgb(71, 148, 253), rgb(71, 253, 160), 0, false, KeyColor.AQUA, KeyColor.LIME),
            new RiseTheme("Blossom", rgb(226, 208, 249), rgb(49, 119, 115), 0, false, KeyColor.PINK, KeyColor.GRAY),
            new RiseTheme("Bubblegum", rgb(243, 145, 216), rgb(152, 165, 243), 0, false, KeyColor.PINK, KeyColor.PURPLE),
            new RiseTheme("Candy Cane", rgb(255, 0, 0), rgb(255, 255, 255), 0, false, KeyColor.RED),
            new RiseTheme("Cherry", rgb(187, 55, 125), rgb(251, 211, 233), 0, false, KeyColor.RED, KeyColor.PURPLE, KeyColor.PINK),
            new RiseTheme("Christmas", rgb(255, 64, 64), rgb(255, 255, 255), rgb(64, 255, 64), false, KeyColor.RED, KeyColor.LIME),
            new RiseTheme("Coral", rgb(244, 168, 150), rgb(52, 133, 151), 0, false, KeyColor.PINK, KeyColor.ORANGE, KeyColor.DARK_BLUE),
            new RiseTheme("Digital Horizon", rgb(95, 195, 228), rgb(229, 93, 135), 0, false, KeyColor.AQUA, KeyColor.RED, KeyColor.PINK),
            new RiseTheme("Express", rgb(173, 83, 137), rgb(60, 16, 83), 0, false, KeyColor.PURPLE, KeyColor.PINK),
            new RiseTheme("Lime Water", rgb(18, 255, 247), rgb(179, 255, 171), 0, false, KeyColor.AQUA, KeyColor.LIME),
            new RiseTheme("Lush", rgb(168, 224, 99), rgb(86, 171, 47), 0, false, KeyColor.LIME, KeyColor.DARK_GREEN),
            new RiseTheme("Halogen", rgb(255, 65, 108), rgb(255, 75, 43), 0, false, KeyColor.RED, KeyColor.ORANGE),
            new RiseTheme("Hyper", rgb(236, 110, 173), rgb(52, 148, 230), 0, false, KeyColor.PINK, KeyColor.DARK_BLUE, KeyColor.AQUA),
            new RiseTheme("Magic", rgb(74, 0, 224), rgb(142, 45, 226), 0, false, KeyColor.DARK_BLUE, KeyColor.PURPLE),
            new RiseTheme("May", rgb(238, 79, 238), rgb(253, 219, 245), 0, false, KeyColor.PINK, KeyColor.PURPLE),
            new RiseTheme("Orange Juice", rgb(252, 74, 26), rgb(247, 183, 51), 0, false, KeyColor.ORANGE, KeyColor.YELLOW),
            new RiseTheme("Pastel", rgb(243, 155, 178), rgb(207, 196, 243), 0, false, KeyColor.PINK),
            new RiseTheme("Pumpkin", rgb(241, 166, 98), rgb(255, 216, 169), rgb(227, 139, 42), false, KeyColor.ORANGE),
            new RiseTheme("Satin", rgb(215, 60, 67), rgb(140, 23, 39), 0, false, KeyColor.RED),
            new RiseTheme("Snowy Sky", rgb(1, 171, 179), rgb(234, 234, 234), rgb(18, 232, 232), false, KeyColor.AQUA, KeyColor.GRAY),
            new RiseTheme("Steel Fade", rgb(66, 134, 244), rgb(55, 59, 68), 0, false, KeyColor.DARK_BLUE, KeyColor.GRAY),
            new RiseTheme("Sundae", rgb(206, 74, 126), rgb(122, 44, 77), 0, false, KeyColor.PINK, KeyColor.PURPLE, KeyColor.RED),
            new RiseTheme("Sunkist", rgb(242, 201, 76), rgb(242, 153, 74), 0, false, KeyColor.YELLOW, KeyColor.ORANGE),
            new RiseTheme("Water", rgb(12, 232, 199), rgb(12, 163, 232), 0, false, KeyColor.AQUA, KeyColor.DARK_BLUE),
            new RiseTheme("Legacy", rgb(0x70, 0xCD, 0xFF), rgb(0x70, 0xCD, 0xFF), 0, false, KeyColor.AQUA, KeyColor.DARK_BLUE),
            new RiseTheme("Winter", rgb(255, 255, 255), rgb(255, 255, 255), 0, false, KeyColor.GRAY),
            new RiseTheme("Peony", rgb(226, 208, 249), rgb(207, 171, 255), 0, false, KeyColor.PINK, KeyColor.GRAY),
            new RiseTheme("Shadow", rgb(97, 131, 255), rgb(206, 212, 255), 0, false, KeyColor.AQUA),
            new RiseTheme("Wood", rgb(79, 109, 81), rgb(170, 139, 87), rgb(240, 235, 206), false, KeyColor.DARK_GREEN),
            new RiseTheme("Creida", new Color(-11644304).brighter().brighter().getRGB() & 0xFFFFFF,
                    new Color(-11644304).darker().getRGB() & 0xFFFFFF, 0, false, KeyColor.GRAY),
            new RiseTheme("Creida Two", new Color(-6632725).getRGB() & 0xFFFFFF,
                    new Color(-8406042).darker().getRGB() & 0xFFFFFF, 0, false, KeyColor.GRAY),
            new RiseTheme("Gothic", rgb(31, 30, 30), rgb(196, 190, 190), 0, false, KeyColor.GRAY),
            new RiseTheme("Rue", rgb(234, 118, 176), rgb(31, 30, 30), 0, false, KeyColor.PINK),
            new RiseTheme("Purple", new Color(5391249).getRGB() & 0xFFFFFF, new Color(5391249).brighter().getRGB() & 0xFFFFFF, 0, false),
            new RiseTheme("Rainbow", 0, 0, 0, true),
            new RiseTheme("Nord", rgb(143, 188, 187), rgb(163, 190, 140), rgb(236, 239, 244), false, KeyColor.AQUA, KeyColor.GRAY),
    };

    /** ThemeManager：当前主题（默认 BLEND，与 Rise 一致） */
    private static RiseTheme active = ALL[3];

    public static RiseTheme active() { return active; }

    public static void setActive(RiseTheme t) {
        if (t != null) active = t;
    }

    public static RiseTheme byName(String name) {
        for (RiseTheme t : ALL) if (t.name().equalsIgnoreCase(name)) return t;
        return null;
    }
}
