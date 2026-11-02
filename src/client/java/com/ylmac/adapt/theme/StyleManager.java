package com.ylmac.adapt.theme;

import com.ylmac.adapt.config.Slot;
import com.ylmac.adapt.theme.color.Hct;
import com.ylmac.adapt.theme.color.Scheme;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class StyleManager {

    private static StyleManager instance;

    public final Slot<Object> isDarkTheme = new Slot<>(Boolean.FALSE);
    public final Slot<Object> seedColor = new Slot<>("#6750A4");
    public final Slot<Object> currentScheme = new Slot<>();
    public final Slot<Object> lightScheme = new Slot<>();
    public final Slot<Object> darkScheme = new Slot<>();
    public final Slot<Object> hctHue = new Slot<>(0.0);
    public final Slot<Object> hctChroma = new Slot<>(0.0);
    public final Slot<Object> hctTone = new Slot<>(0.0);

    private boolean themedSurface;

    public static Object __instance() {
        if (instance == null) instance = new StyleManager();
        return instance;
    }

    private StyleManager() {
        this.isDarkTheme.setInterceptor((p, v) -> {
            p.setPaintOnly(v);
            updateSchemes();
        });
        this.seedColor.setInterceptor((p, v) -> {
            Object old = p.peek();
            p.setPaintOnly(v);
            if (!Objects.equals(old, p.peek())) updateSchemes();
        });
        updateSchemes();
    }

    public void setSeedColorHct(double hue, double chroma, double tone) {
        this.seedColor.set(hex(Hct.from(hue, chroma, tone).toInt()));
    }

    public void setSeedColorHctLight(double hue, double chroma, double tone) {
        setSeedColorHct(hue, chroma, tone);
    }

    public void setThemedSurface(boolean enabled) {
        if (this.themedSurface != enabled) {
            this.themedSurface = enabled;
            updateSchemes();
        }
    }

    private void updateSchemes() {
        int argb = parseSeed(this.seedColor.peek());
        Hct seedHct = Hct.fromInt(argb);
        double boostedChroma = Math.max(seedHct.getChroma(), 48.0);
        if (boostedChroma != seedHct.getChroma()) {
            argb = Hct.from(seedHct.getHue(), boostedChroma, seedHct.getTone()).toInt();
        }
        boolean dark = Boolean.TRUE.equals(this.isDarkTheme.peek());
        if (dark) {
            Map<String, String> scheme = new LinkedHashMap<>(Scheme.generate(argb, true));
            if (themedSurface) {
                String primaryHex = scheme.get("primary");
                String surfaceHex = scheme.get("surface");
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

    private static String blendHex(String baseHex, String targetHex, double t) {
        int base = parseHex(baseHex);
        int target = parseHex(targetHex);
        int r = (int) (r(base) * (1 - t) + r(target) * t);
        int g = (int) (g(base) * (1 - t) + g(target) * t);
        int b = (int) (b(base) * (1 - t) + b(target) * t);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static int parseHex(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() == 6) return Integer.parseInt(s, 16);
        if (s.length() == 8) return (int) Long.parseLong(s, 16);
        return 0;
    }

    private static int r(int argb) { return (argb >> 16) & 0xFF; }
    private static int g(int argb) { return (argb >> 8) & 0xFF; }
    private static int b(int argb) { return argb & 0xFF; }

    private static int parseSeed(Object v) {
        if (v instanceof Number number) return number.intValue();
        String s = String.valueOf(v).trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 8) return (int) Long.parseLong(s, 16);
        if (s.length() == 6) return 0xFF000000 | Integer.parseInt(s, 16);
        return -10006364;
    }

    private static String hex(int argb) {
        return String.format("#%06x", argb & 0xFFFFFF);
    }
}
