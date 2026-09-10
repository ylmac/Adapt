package com.ylmac.adapt.render.clickgui;

import com.ylmac.adapt.render.QmlScreen;

/**
 * Epsilon 风格 Click GUI 屏幕。
 * <p>
 * 使用 QmlScreen 基类的简化 API 配置：
 * - 加载 clickgui/ClickGui.qml
 * - 使用世界画面模糊背景（毛玻璃效果）
 * - 透明背景，游戏世界可见
 * - 注入 bridge 桥接对象供 QML 调用
 */
public class ClickGuiScreen extends QmlScreen {

    /** 动态背景（封面流体）：模仿音乐小组件 Modern 模式的流体底 */
    private com.ylmac.adapt.features.music.lyric.skia.FluidBackground fluidBg;
    private String fluidKey = "";

    public ClickGuiScreen() {
        super("ClickGUI");
        setQmlPath("clickgui/ClickGui.qml");
        setSurfaceName("clickgui");
        setUseBackdropBlur(true);   // 启用毛玻璃背景
        // 背景色默认透明（QmlScreen 默认 0x00000000），无需显式设置
        setContext("bridge", ClickGuiBridge.INSTANCE);
        setContext("settings", com.ylmac.adapt.render.QplayerScreen.getOrCreateDesktopSettings());
    }

    /**
     * QML 渲染之前：把封面流体背景画在整块画布上（设置 → ClickGUI 动态背景）。
     * 之后 QML 的毛玻璃面板会盖在半透明顶层，形成"面板底下有流体背景"的观感。
     * 变换与 LyricCompositor 同款：raw canvas 先 scale(guiScale) 再用逻辑坐标绘制。
     */
    @Override
    protected void onBeforeRender(io.github.humbleui.skija.Canvas canvas, int mx, int my, float delta) {
        try {
            var ds = com.ylmac.adapt.render.QplayerScreen.getDesktopSettings();
            if (ds == null || !Boolean.TRUE.equals(ds.guiFluidBg.peek())) return;
            if (surfaceBackend == null) return;
            var ctx = com.ylmac.adapt.render.GpuContextHelper.getOrCreateContext();
            if (ctx == null) return;
            var pc = com.ylmac.adapt.render.QplayerScreen.getPlayerController();
            byte[] cover = pc == null ? null : pc.coverBytes.peek();
            String key = pc == null ? "" : (String.valueOf(pc.title.peek()) + "|" + pc.artist.peek());
            if (!key.equals(fluidKey)) fluidKey = key;
            if (fluidBg == null) {
                fluidBg = new com.ylmac.adapt.features.music.lyric.skia.FluidBackground(System.nanoTime());
            }
            float sc = surfaceBackend.getGuiScale();
            int fbW = surfaceBackend.getDisplayWidth(), fbH = surfaceBackend.getDisplayHeight();
            int save = canvas.save();
            canvas.scale(sc, sc);
            fluidBg.render(canvas, ctx, 1f, fbW / sc, fbH / sc, cover, fluidKey,
                    System.nanoTime(), false);
            canvas.restoreToCount(save);
        } catch (Throwable t) {
            com.ylmac.adapt.QplayerModClient.LOGGER.warn("[ClickGui] fluid bg failed: {}", t.toString());
        }
    }

    /**
     * 每次打开界面时刷新模块状态：复用缓存 QmlView 时 Component.onCompleted 不会重跑，
     * categories 是旧快照 → 开关状态不反映最新（用户看到"从关闭到开启的动画"但实际已启用）。
     * 通过 writeMember 递增 QML 根节点的 refreshTick → QML 的 onRefreshTickChanged 触发
     * syncState() 重新从 bridge 拉取真实状态（qml4j 无 callMember，只能用属性写触发）。
     * <p>
     * 同时触发世界取色快照（Monet）：与音乐播放器一致，打开 ClickGUI 时截取当前
     * 世界画面取色，让 ClickGUI 主题色跟随世界（QplayerScreen 由 openMusicPlayer
     * 的 forceSnapshot 驱动；ClickGuiScreen 在此补齐）。
     */
    @Override
    protected void onScreenOpened() {
        try {
            if (qmlRoot != null) {
                Object cur = io.github.timer_err.qml4j.runtime.member.MemberAccess.readMember(qmlRoot, "refreshTick");
                int next = (cur instanceof Number) ? ((Number) cur).intValue() + 1 : 1;
                io.github.timer_err.qml4j.runtime.member.MemberAccess.writeMember(qmlRoot, "refreshTick", next);
            }
            com.ylmac.adapt.render.WorldColorSampler.get().forceSnapshot();
        } catch (Throwable t) {
            com.ylmac.adapt.QplayerModClient.LOGGER.warn("[ClickGui] refresh categories failed: {}", t.toString());
        }
    }
}
