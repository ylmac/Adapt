package com.ylmac.adapt.render;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Typeface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Rise 字体资源加载：从 assets 加载 product_sans_*.ttf，
 * 同时构建 AWT Font（用于 Rise 算法测量）和 Skia Typeface（用于绘制）。
 */
public final class Typefaces {

    private static final Map<String, byte[]> FILES = new HashMap<>();
    private static final Map<String, java.awt.Font> AWT = new HashMap<>();
    private static final Map<java.awt.Font, Typeface> SKIA = new HashMap<>();

    private Typefaces() {}

    /** qml → qml-retired：字体随 QML 退役迁路径，旧路径保留兜底 */
    private static final String[] FONT_DIRS = {
            "/assets/adaptclient/qml-retired/fonts/",
            "/assets/adaptclient/qml/fonts/",
            "/assets/adaptclient/fonts/",
    };

    /** 从 assets 加载 TTF/OTF 字节 */
    private static byte[] load(String name) {
        byte[] cached = FILES.get(name);
        if (cached != null) return cached;
        for (String dir : FONT_DIRS) {
            try (InputStream in = Typefaces.class.getResourceAsStream(dir + name)) {
                if (in == null) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                byte[] data = out.toByteArray();
                if (data.length == 0) continue;
                FILES.put(name, data);
                return data;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** AWT Font：Font.createFont + deriveFont(size)（Rise FontUtil.p 同款） */
    public static java.awt.Font awt(String file, float size) {
        String key = file + ":" + size;
        java.awt.Font f = AWT.get(key);
        if (f != null) return f;
        byte[] data = load(file);
        try {
            if (data == null) return new java.awt.Font("SansSerif", java.awt.Font.PLAIN, (int) size);
            java.awt.Font base = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,
                    new java.io.ByteArrayInputStream(data));
            f = base.deriveFont(size);
        } catch (Exception e) {
            f = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, (int) size);
        }
        AWT.put(key, f);
        return f;
    }

    /** Skia Typeface：从同一 TTF 字节构建（Skija FontMgr.makeFromData） */
    public static Typeface skija(java.awt.Font awt) {
        Typeface t = SKIA.get(awt);
        if (t != null) return t;
        byte[] data = load(fileOf(awt));
        if (data != null) {
            try {
                t = io.github.humbleui.skija.FontMgr.getDefault().makeFromData(Data.makeFromBytes(data));
            } catch (Throwable ignored) {}
        }
        if (t == null) t = io.github.humbleui.skija.FontMgr.getDefault().matchFamilyStyle(null, io.github.humbleui.skija.FontStyle.NORMAL);
        SKIA.put(awt, t);
        return t;
    }

    private static String fileOf(java.awt.Font awt) {
        // 反向查找：从缓存里找该 AWT Font 对应的文件
        for (Map.Entry<String, java.awt.Font> e : AWT.entrySet()) {
            if (e.getValue() == awt) return e.getKey().substring(0, e.getKey().lastIndexOf(':'));
        }
        return "product_sans_regular.ttf";
    }

    /** RiseFont 用：直接由文件名构建 Skia Typeface */
    public static Typeface fromFile(String file) {
        byte[] data = load(file);
        if (data == null) return io.github.humbleui.skija.FontMgr.getDefault().matchFamilyStyle(null, io.github.humbleui.skija.FontStyle.NORMAL);
        try { return io.github.humbleui.skija.FontMgr.getDefault().makeFromData(Data.makeFromBytes(data)); }
        catch (Throwable t) { return io.github.humbleui.skija.FontMgr.getDefault().matchFamilyStyle(null, io.github.humbleui.skija.FontStyle.NORMAL); }
    }
}
