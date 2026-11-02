package com.ylmac.adapt.render.screen;

import com.ylmac.adapt.config.DesktopSettings;
import com.ylmac.adapt.module.misc.music.PlayerController;
import com.ylmac.adapt.module.misc.music.lyric.skia.Fonts;
import com.ylmac.adapt.module.misc.music.lyric.skia.LyricCompositor;
import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.render.surface.GpuSharedTexture;

/**
 * 音乐播放器 / ClickGUI 共享运行时（PlayerController、DesktopSettings、歌词合成器）。
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
                byte[] regular = loadFont("fonts/PingFangSC-Regular.otf");
                byte[] medium = loadFont("fonts/PingFangSC-Medium.otf");
                byte[] light = loadFont("fonts/PingFangSC-Light.otf");
                byte[] thin = loadFont("fonts/PingFangSC-Thin.otf");
                Fonts.init(thin, light, regular, medium);
            }
            if (Fonts.iconEmpty()) {
                byte[] icon = loadFont("fonts/MaterialSymbolsRounded.ttf");
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

    /** 后台预热控制器和字体。 */
    public static void warmup() {
        AdaptClient.LOGGER.info("[AdaptRuntime] warming controllers/fonts");
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
