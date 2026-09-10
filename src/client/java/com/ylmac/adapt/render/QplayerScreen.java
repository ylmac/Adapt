package com.ylmac.adapt.render;

import com.ylmac.adapt.features.music.PlayerController;
import com.ylmac.adapt.config.DesktopSettings;
import com.ylmac.adapt.features.music.lyric.skia.Fonts;
import com.ylmac.adapt.features.music.lyric.skia.LyricCompositor;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;
import com.ylmac.adapt.ui.QmlCacheManager;
import com.ylmac.adapt.ui.QplayerResourceLoader;
import com.ylmac.adapt.QplayerModClient;

import java.util.Map;

public class QplayerScreen extends QmlScreen {

    private static LyricCompositor lyricCompositor;
    private static volatile PlayerController playerController;
    private static volatile DesktopSettings desktopSettings;

    public static PlayerController getPlayerController() { return playerController; }
    public static DesktopSettings getDesktopSettings() { return desktopSettings; }

    /** Shared appearance settings for the player and ClickGUI. */
    public static synchronized DesktopSettings getOrCreateDesktopSettings() {
        if (desktopSettings == null) desktopSettings = new DesktopSettings();
        return desktopSettings;
    }

    public QplayerScreen() {
        super("QPlayer Music Player");
        setQmlPath("Main.qml");
        setSurfaceName("qplayer_main");
        setUseBackdropBlur(true);
        DesktopSettings settings = getOrCreateDesktopSettings();
        if (playerController == null) playerController = new PlayerController(settings);
        setContext("player", playerController);
        setContext("settings", settings);
        setContext("gpuBackend", GpuContextHelper.backendName);
        setContext("gpuName", GpuContextHelper.deviceName);
        setContext("gpuStatus", GpuContextHelper.gpuStatus);
    }

    @Override
    protected void init() {
        // 等待后台预编译完成
        joinQmlPrecompile();
        // 复用预编译 View
        if (precompiledView != null && precompiledRoot != null) {
            this.qmlView = precompiledView;
            this.qmlRoot = precompiledRoot;
            this.qmlLoaded = precompiledLoaded;
            this.uiW = precompiledW;
            this.uiH = precompiledH;
        }
        super.init();

        // 初始化 Skia 字体缓存
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

        // 初始化歌词合成器
        if (lyricCompositor == null) {
            lyricCompositor = new LyricCompositor();
        }
    }

    @Override
    protected void onBeforeRender(Canvas canvas, int mx, int my, float delta) {
        // 播放器状态泵
        if (playerController != null) playerController.pump();
    }

    @Override
    protected void onAfterRender(Canvas canvas, int mx, int my, float delta) {
        if (lyricCompositor != null && playerController != null && desktopSettings != null) {
            lyricCompositor.composite(canvas, qmlView.renderer(), qmlView,
                playerController, desktopSettings, surfaceBackend.recordingContext(),
                guiScale, surfaceBackend.getDisplayWidth(), surfaceBackend.getDisplayHeight());
        }
    }

    /** 后台预编译 QML（纯 CPU，不碰 GPU） */
    public static void precompileQmlBackground() {
        QplayerModClient.LOGGER.info("[QPlayer] QML background precompile starting...");
        try {
            DesktopSettings settings = getOrCreateDesktopSettings();
            if (playerController == null) playerController = new PlayerController(settings);
            QmlEngine engine = new QmlEngine();
            QmlView view = QmlView.withStockTypes(engine);
            view.resources(new QplayerResourceLoader());
            QmlCacheManager qcm = new QmlCacheManager();
            view.bytecodeCache(qcm);
            // 恢复预编译缓存类（defineClass + 推进 componentCounter），
            // 否则 Main.qml 运行时编译撞 Component$N 类名计数
            io.github.timer_err.qml4j.render.RestoreHelper.restoreAllFor(view);
            view.context("settings", settings);
            view.context("player", playerController);
            view.context("gpuBackend", GpuContextHelper.backendName);
            view.context("gpuName", GpuContextHelper.deviceName);
            view.context("gpuStatus", GpuContextHelper.gpuStatus);

            byte[] regular = loadFont("fonts/PingFangSC-Regular.otf");
            byte[] medium = loadFont("fonts/PingFangSC-Medium.otf");
            byte[] light = loadFont("fonts/PingFangSC-Light.otf");
            byte[] thin = loadFont("fonts/PingFangSC-Thin.otf");
            if (regular != null && medium != null) view.uiTypefaces(regular, medium);
            byte[] icon = loadFont("fonts/MaterialSymbolsRounded.ttf");
            if (icon != null) view.iconTypeface(icon);

            // 后台初始化 Skia 字体
            if (Fonts.isEmpty()) Fonts.init(thin, light, regular, medium);
            if (Fonts.iconEmpty()) Fonts.initIcon(icon);

            String qml = loadQml("Main.qml");
            if (qml != null) {
                Item root = view.load(qml, "");
                if (root != null) {
                    try {
                        int w = ((Number) MemberAccess.readMember(root, "width")).intValue();
                        int h = ((Number) MemberAccess.readMember(root, "height")).intValue();
                        if (w > 0) precompiledW = w;
                        if (h > 0) precompiledH = h;
                    } catch (Exception ignored) {}
                    precompiledView = view;
                    precompiledRoot = root;
                    precompiledLoaded = true;
                    precompiledQmlPath = "Main.qml";
                    // 后台布局收敛 30 轮（与 QmlScreen.warmupLayout 统一）：Repeater 异步
                    // 实例化需多轮 layout 才能完全收敛，缓存阶段做完 → 打开界面零收敛延迟。
                    com.ylmac.adapt.render.QmlScreen.warmupLayout(view, root, 30);
                }
            }
            QplayerModClient.LOGGER.info("[QPlayer] QML background precompile complete");

            // 也预编译 ClickGui.qml（供 ClickGuiScreen 后台预构建复用）
            try {
                java.util.Map<String, Object> clickGuiCtx = new java.util.HashMap<>();
                clickGuiCtx.put("bridge", com.ylmac.adapt.render.clickgui.ClickGuiBridge.INSTANCE);
                clickGuiCtx.put("settings", settings);
                precompileToCache("clickgui/ClickGui.qml", clickGuiCtx);
            } catch (Throwable ct) {
                QplayerModClient.LOGGER.warn("[QPlayer] ClickGui.qml precompile failed: {}", ct.toString());
            }

            // 也预编译 ModuleList.qml（供 ModuleListOverlay HUD 后台预构建复用）
            try {
                java.util.Map<String, Object> moduleListCtx = new java.util.HashMap<>();
                moduleListCtx.put("moduleList", new com.ylmac.adapt.render.ModuleListBridge());
                precompileToCache("modulelist/ModuleList.qml", moduleListCtx);
            } catch (Throwable mt) {
                QplayerModClient.LOGGER.warn("[QPlayer] ModuleList.qml precompile failed: {}", mt.toString());
            }
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[QPlayer] QML background precompile failed: {}", t.toString());
        }
    }

    /** 主线程调用：预创建 GPU surface */
    public static void precreateGpuSurface() {
        try {
            MinecraftSurfaceBackend shared = new MinecraftSurfaceBackend(
                "qplayer_main", precompiledW, precompiledH, 3);
            QplayerModClient.LOGGER.info("[QPlayer] GPU surface pre-created ({}x{})",
                shared.getDisplayWidth(), shared.getDisplayHeight());
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[QPlayer] GPU surface precreate failed: {}", t.toString());
        }
    }

    private static void joinQmlPrecompile() {
        // 等待逻辑已在 QplayerModClient 中处理
    }
}