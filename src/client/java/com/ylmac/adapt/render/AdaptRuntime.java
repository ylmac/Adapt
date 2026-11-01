package com.ylmac.adapt.render;

import com.ylmac.adapt.config.DesktopSettings;
import com.ylmac.adapt.features.music.PlayerController;
import com.ylmac.adapt.features.music.lyric.skia.Fonts;
import com.ylmac.adapt.features.music.lyric.skia.LyricCompositor;
import com.ylmac.adapt.AdaptClient;

/**
 * 音乐播放器 / ClickGUI 共享运行时（PlayerController、DesktopSettings、歌词合成器）。
 * 从原 QplayerScreen / AdaptScreen QML 宿主抽出，Compose 与 Skia HUD 共用。
 */
public final class AdaptRuntime {
    private static LyricCompositor lyricCompositor;
    private static volatile PlayerController playerController;
    private static volatile DesktopSettings desktopSettings;

    private AdaptRuntime() {}

    public static PlayerController getPlayerController() {
        return playerController;
    }

    public static DesktopSettings getDesktopSettings() {
        return desktopSettings;
    }

    public static synchronized DesktopSettings getOrCreateDesktopSettings() {
        if (desktopSettings == null) desktopSettings = new DesktopSettings();
        return desktopSettings;
    }

    public static synchronized PlayerController getOrCreatePlayerController() {
        DesktopSettings settings = getOrCreateDesktopSettings();
        if (playerController == null) playerController = new PlayerController(settings);
        return playerController;
    }

    public static synchronized LyricCompositor getOrCreateLyricCompositor() {
        ensureFonts();
        if (lyricCompositor == null) lyricCompositor = new LyricCompositor();
        return lyricCompositor;
    }

    public static void ensureFonts() {
        try {
            if (Fonts.isEmpty()) {
                byte[] regular = loadFont(
                        "qml-retired/fonts/PingFangSC-Regular.otf",
                        "fonts/PingFangSC-Regular.otf");
                byte[] medium = loadFont(
                        "qml-retired/fonts/PingFangSC-Medium.otf",
                        "fonts/PingFangSC-Medium.otf");
                byte[] light = loadFont(
                        "qml-retired/fonts/PingFangSC-Light.otf",
                        "fonts/PingFangSC-Light.otf");
                byte[] thin = loadFont(
                        "qml-retired/fonts/PingFangSC-Thin.otf",
                        "fonts/PingFangSC-Thin.otf");
                Fonts.init(thin, light, regular, medium);
            }
            if (Fonts.iconEmpty()) {
                byte[] icon = loadFont(
                        "qml-retired/fonts/MaterialSymbolsRounded.ttf",
                        "fonts/MaterialSymbolsRounded.ttf");
                Fonts.initIcon(icon);
            }
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[AdaptRuntime] font init failed: {}", t.toString());
        }
    }

    private static byte[] loadFont(String... paths) {
        for (String path : paths) {
            try (var in = AdaptRuntime.class.getResourceAsStream("/assets/adaptclient/" + path)) {
                if (in == null) continue;
                byte[] bytes = in.readAllBytes();
                if (bytes != null && bytes.length > 0) return bytes;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 兼容旧预编译入口：Compose 路径下仅预热控制器与字体。 */
    public static void precompileQmlBackground() {
        AdaptClient.LOGGER.info("[AdaptRuntime] warming controllers/fonts (Compose path, skip QML)");
        try {
            getOrCreatePlayerController();
            ensureFonts();
            getOrCreateLyricCompositor();
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[AdaptRuntime] warmup failed: {}", t.toString());
        }
    }

    public static void precreateGpuSurface() {
        // Compose 按需建 GpuSharedTexture；此处 no-op 保留调用点
    }
}
