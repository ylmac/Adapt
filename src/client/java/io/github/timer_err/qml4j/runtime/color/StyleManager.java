/*
 * Decompiled with CFR 0.152.
 */
package io.github.timer_err.qml4j.runtime.color;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.runtime.color.Hct;
import io.github.timer_err.qml4j.runtime.color.Scheme;
import java.util.Objects;

public final class StyleManager
extends QObject {
    private static StyleManager instance;
    public final Property<Object> isDarkTheme = new Property<>(Boolean.FALSE);
    public final Property<Object> seedColor = new Property<>("#6750A4");
    public final Property<Object> currentScheme = new Property();
    public final Property<Object> lightScheme = new Property();
    public final Property<Object> darkScheme = new Property();
    public final Property<Object> hctHue = new Property<>(0.0);
    public final Property<Object> hctChroma = new Property<>(0.0);
    public final Property<Object> hctTone = new Property<>(0.0);

    public static Object __instance() {
        if (instance == null) {
            instance = new StyleManager();
        }
        return instance;
    }

    private StyleManager() {
        this.isDarkTheme.setInterceptor((p, v) -> {
            p.setBypassInterceptor(v);
            this.updateSchemes();
        });
        this.seedColor.setInterceptor((p, v) -> {
            Object old = p.peek();
            p.setBypassInterceptor(v);
            if (!Objects.equals(old, p.peek())) {
                this.updateSchemes();
            }
        });
        this.updateSchemes();
    }

    public void setSeedColorHct(double hue, double chroma, double tone) {
        this.seedColor.set(StyleManager.hex(Hct.from(hue, chroma, tone).toInt()));
    }

    /**
     * 过渡中间步的色更新 —— 必须走完整 scheme 重建。
     * <p>
     * 这里曾经只写 hctHue/hctChroma/hctTone 三个数值属性以"省开销"，但 QML 里
     * 所有颜色都绑定 {@code Theme.color.*} ← {@link #currentScheme}，三个 HCT 属性
     * 没有任何消费者 → 中间步界面纹丝不动，世界取色的过渡动画等于不存在
     * （只有终点那一次完整重建生效，看起来就是硬切换）。
     * 实测 Scheme.generate ≈ 1.9 ms/次，真正的成本在 QML 绑定刷新，
     * 由调用方（WorldColorSampler）按耗时自适应节流，这里只负责把颜色真正落地。
     */
    public void setSeedColorHctLight(double hue, double chroma, double tone) {
        setSeedColorHct(hue, chroma, tone);
    }

    public void setSourceImage(Object fileUrl) {
    }

    // 主题色模式：surface/background 用 primary 混合 → 浓郁深色（PixelPlayer 风格）
    private boolean themedSurface = false;

    public void setThemedSurface(boolean enabled) {
        if (this.themedSurface != enabled) {
            this.themedSurface = enabled;
            updateSchemes();
        }
    }

    private void updateSchemes() {
        int argb = StyleManager.parseSeed(this.seedColor.peek());
        // 激进取色：boost chroma 到最少 48（PixelPlayer 风格高对比度）
        // PixelPlayer 用 SchemeVibrant/SchemeExpressive，我们用 boost chroma 达到类似效果
        Hct seedHct = Hct.fromInt(argb);
        double boostedChroma = Math.max(seedHct.getChroma(), 48.0);
        if (boostedChroma != seedHct.getChroma()) {
            argb = Hct.from(seedHct.getHue(), boostedChroma, seedHct.getTone()).toInt();
        }
        boolean dark = Boolean.TRUE.equals(this.isDarkTheme.peek());
        if (dark) {
            java.util.Map<String, String> scheme = new java.util.LinkedHashMap<>(
                Scheme.generate(argb, true));
            // 主题色模式：surface × 0.82 + primary × 0.18 → 深色带浓郁主题色调
            // 模仿 PixelPlayer: background=#1E1234, surface=#2A1F40
            // 不用手选——Monet 的 primary 已经是浓郁的主题色，和 surface 混合即可
            if (themedSurface) {
                String primaryHex = scheme.get("primary");
                String surfaceHex = scheme.get("surface");
                // 更浓郁：primary 混合比例从 0.18/0.22 提到 0.28/0.35
                scheme.put("surface", blendHex(surfaceHex, primaryHex, 0.35));
                scheme.put("background", blendHex(surfaceHex, primaryHex, 0.28));
                scheme.put("surfaceContainer", blendHex(surfaceHex, primaryHex, 0.32));
                scheme.put("surfaceContainerLow", blendHex(surfaceHex, primaryHex, 0.30));
                scheme.put("surfaceContainerLowest", blendHex(surfaceHex, primaryHex, 0.25));
                scheme.put("surfaceContainerHigh", blendHex(surfaceHex, primaryHex, 0.45));
                scheme.put("surfaceContainerHighest", blendHex(surfaceHex, primaryHex, 0.52));
                scheme.put("surfaceDim", blendHex(surfaceHex, primaryHex, 0.24));
                scheme.put("surfaceBright", blendHex(surfaceHex, primaryHex, 0.48));
            }
            this.darkScheme.set(scheme);
            this.currentScheme.set(this.darkScheme.peek());
        } else {
            this.lightScheme.set(Scheme.generate(argb, false));
            this.currentScheme.set(this.lightScheme.peek());
        }
        Hct hct = Hct.fromInt(argb);
        this.hctHue.set(hct.getHue());
        this.hctChroma.set(hct.getChroma());
        this.hctTone.set(hct.getTone());
    }

    /** Blend two hex colors: result = base × (1-t) + target × t */
    private static String blendHex(String baseHex, String targetHex, double t) {
        int base = parseHex(baseHex);
        int target = parseHex(targetHex);
        int r = (int)(r(base) * (1-t) + r(target) * t);
        int g = (int)(g(base) * (1-t) + g(target) * t);
        int b = (int)(b(base) * (1-t) + b(target) * t);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static int parseHex(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() == 6) return Integer.parseInt(s, 16);
        if (s.length() == 8) return (int)Long.parseLong(s, 16);
        return 0;
    }
    private static int r(int argb) { return (argb >> 16) & 0xFF; }
    private static int g(int argb) { return (argb >> 8) & 0xFF; }
    private static int b(int argb) { return argb & 0xFF; }

    private static int parseSeed(Object v) {
        if (v instanceof Number) {
            return ((Number)v).intValue();
        }
        String s = String.valueOf(v).trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 8) {
            return (int)Long.parseLong(s, 16);
        }
        if (s.length() == 6) {
            return 0xFF000000 | Integer.parseInt(s, 16);
        }
        return -10006364;
    }

    private static String hex(int argb) {
        return String.format("#%06x", argb & 0xFFFFFF);
    }
}
