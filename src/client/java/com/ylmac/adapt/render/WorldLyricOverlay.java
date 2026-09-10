package com.ylmac.adapt.render;

import com.ylmac.adapt.features.music.lyric.LyricLine;
import com.ylmac.adapt.features.music.lyric.skia.LyricSkia;
import com.ylmac.adapt.features.music.lyric.skia.WorldLyricRenderer;
import io.github.humbleui.skija.Canvas;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.List;
import com.ylmac.adapt.config.DesktopSettings;
import com.ylmac.adapt.features.music.lyric.skia.LyricCompositor;
import com.ylmac.adapt.features.music.lyric.skia.LyricRenderer;
import com.ylmac.adapt.features.music.PlayerController;
import com.ylmac.adapt.QplayerModClient;

/**
 * 世界内歌词（HUD 层）：在屏幕右下方渲染当前播放歌词。
 *
 * 移植自 skija-mod 的 QmlWorldOverlay（世界内 QML surface 嵌入 HUD 渲染管线）：
 * Skija 渲染到一个 GPU surface（MinecraftSurfaceBackend，VkImage 与 MC 纹理零拷贝共享），
 * 每帧经 GuiGraphicsExtractor.blit 贴到屏幕右下角（预乘 alpha 管线，见
 * MinecraftSurfaceBackend.blitPipeline）。
 *
 * 承载内容使用 WorldLyricRenderer —— 完整动画版（由桌面歌词 LyricRenderer 复制改名而来，
 * 保留滚动/缩放/发光/逐字高亮等全部效果，独立修改不影响 GUI 歌词）。
 *
 * 尺寸与对齐：
 * - surface 逻辑尺寸 840x640；物理纹理按原生设备像素（显示尺寸 × 窗口 GUI scale，1:1）
 *   （物理纹理 1680x1280，GPU 零拷贝 VkImage 共享，无 CPU 回读）
 * - 显示尺寸按屏幕宽度比例 REL_SCALE=0.45（≈ 屏幕面积四分之一，用户指定大小）
 * - 背景透明，无面板底色
 * - 面板位置跟随对齐方式：左对齐→左下角、中间→底部居中、右对齐→右下角
 *   （垂直恒为底部锚定：translate(x, scrH - MARGIN - dispH)）
 * - 歌词列放 surface 下部（COL_TOP=40%）：当前行对齐列高 35% → 文字带落窗口 ~70%
 *
 * 播放进度：界面关闭时 HUD 渲染循环每帧调用 PlayerController.pump()
 * （与界面渲染循环等效），保证 positionMs/歌词随播放推进。
 */
public final class WorldLyricOverlay implements HudElement {

    // surface 设计尺寸（逻辑坐标，渲染与显示共用同一坐标系）
    private static final int SURF_W = 840;      // surface 逻辑宽度（420×320 的两倍）
    private static final int SURF_H = 640;      // surface 逻辑高度
    private static final float PAD = 24f;       // 歌词列内边距（逻辑单位）
    private static final int MARGIN = 24;       // 屏幕右下角边距（GUI 单位）

    // 歌词列 = surface 全高（PAD 边距内）：上下都不截断。
    // 当前行在列内 60% 处（WorldLyricRenderer.ALIGN_POSITION=0.60），
    // 文字带落在窗口 ~60% → 配合底部锚定呈"右下角"观感
    private static final float COL_TOP = PAD;
    private static final float COL_H = SURF_H - 2f * PAD;

    // 显示尺寸按屏幕宽度比例（与 QplayerScreen 主界面同理念：相对屏幕比例，
    // 分辨率变化时占比不变）；0.45 ≈ 屏幕面积四分之一（用户指定的大小）
    private static final float REL_SCALE = 0.45f;

    private static WorldLyricOverlay instance;

    private MinecraftSurfaceBackend surf;
    private WorldLyricRenderer lyricRenderer;
    private List<LyricLine> lastLyrics;
    private Identifier texId;
    private boolean registered;
    private boolean logged;

    private WorldLyricOverlay() {}

    public static WorldLyricOverlay get() {
        if (instance == null) instance = new WorldLyricOverlay();
        return instance;
    }

    /** 注册 HUD 元素（客户端初始化时调用，不创建 GPU 资源） */
    public void register() {
        if (registered) return;
        registered = true;
        HudElementRegistry.addLast(Identifier.parse("adaptclient:world_lyric"), this);
    }

    /** 面板在屏幕上的显示尺寸（GUI px）—— 按屏幕宽度比例，高度保持设计宽高比。 */
    private static int dispWidth(int scrW) {
        return Math.max(160, (int) (scrW * REL_SCALE));
    }

    /** 首次渲染时懒创建 GPU surface（游戏内打开世界后才可能被调用） */
    private void ensureSurface(int scrW) {
        float deviceScale = dispWidth(scrW) / (float) SURF_W
                * (float) net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
        if (surf != null) {
            // 原生设备像素：窗口大小/GUI scale 变了才重建（1:1 贴图）
            surf.ensureDeviceScale(deviceScale);
            texId = surf.getTextureId();
            return;
        }
        try {
            SkijaNativeFix.preload();
            surf = new MinecraftSurfaceBackend("world_lyric", SURF_W, SURF_H, deviceScale);
            texId = surf.getTextureId();
            lyricRenderer = new WorldLyricRenderer();
            QplayerModClient.LOGGER.info("[QPlayer] WorldLyricOverlay surface ready");
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[QPlayer] WorldLyricOverlay init failed: {}", t.toString());
            surf = null;
        }
    }

    private boolean shouldShow() {
        PlayerController pc = QplayerScreen.getPlayerController();
        if (pc == null) return false;
        DesktopSettings st = QplayerScreen.getDesktopSettings();
        if (st != null && !st.showLyrics()) return false;
        List<LyricLine> lyrics = pc.lyrics.get();
        return lyrics != null && !lyrics.isEmpty();
    }

    /** 渲染歌词到 GPU surface（完整动画版世界歌词渲染器，左/中/右对齐可配） */
    private void renderLyrics() {
        PlayerController pc = QplayerScreen.getPlayerController();
        List<LyricLine> lyrics = pc.lyrics.get();
        if (lyrics != lastLyrics) {
            lastLyrics = lyrics;
            lyricRenderer.setLyrics(lyrics);
        }
        // 世界歌词独立设置：对齐 / 字号 / 描边颜色（DesktopSettings.worldLyric*）
        DesktopSettings st = QplayerScreen.getDesktopSettings();
        if (st != null) {
            lyricRenderer.setAlignMode(st.worldLyricAlign.get() != null
                    ? st.worldLyricAlign.get().intValue() : 2);
            lyricRenderer.setFontSize(st.worldLyricFontSize.get() != null
                    ? st.worldLyricFontSize.get().intValue() : 28);
            lyricRenderer.setFontWeight(st.worldLyricFontWeight.get() != null
                    ? st.worldLyricFontWeight.get().intValue() : 2);
            lyricRenderer.setBorderColor(st.worldLyricBorderColor.get() != null
                    ? st.worldLyricBorderColor.get().intValue() : 0);
        }
        Canvas canvas = surf.acquireCanvas();   // 自带透明清除 + guiScale 缩放（背景保持透明）
        if (canvas == null) return;
        LyricSkia.setCanvas(canvas);
        // 单层清晰渲染（用户要求：去掉实现错误的上下模糊，其他不变）。
        // 之前"模糊层 FBO + 清晰层蒙版"双通道坐标不一致，导致每行歌词出两行。
        lyricRenderer.render(canvas, PAD, COL_TOP, SURF_W - 2f * PAD, COL_H, pc.position());
        surf.present();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        // 界面关闭时由 HUD 循环推进播放进度（界面打开时 HUD 不渲染，由界面循环 pump）
        PlayerController pc = QplayerScreen.getPlayerController();
        if (pc != null) pc.pump();

        if (!shouldShow()) return;
        Minecraft mc = Minecraft.getInstance();
        int scrW = mc.getWindow().getGuiScaledWidth();
        int scrH = mc.getWindow().getGuiScaledHeight();
        ensureSurface(scrW);
        if (surf == null) return;

        try {
            renderLyrics();
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[QPlayer] world lyric render error: {}", t.toString());
            return;
        }

        int texW = surf.getDisplayWidth();      // 物理纹理分辨率（= 显示尺寸 × GUI scale，1:1）
        int texH = surf.getDisplayHeight();
        // 显示尺寸：按屏幕宽度比例（相对比例恒定，分辨率变化时占比不变），高度保持设计宽高比
        int dispW = dispWidth(scrW);
        int dispH = Math.max(120, (int) (dispW * SURF_H / (float) SURF_W));
        // 一次性定位日志：确认实际坐标与文字带预期位置（供后续调参对照）
        if (!logged) {
            logged = true;
            QplayerModClient.LOGGER.info("[QPlayer] WorldLyric pos: scrW={} scrH={} dispW={} dispH={} x={} y={} bandTopY={} texW={} texH={}",
                    scrW, scrH, dispW, dispH, scrW - MARGIN - dispW, scrH - MARGIN - dispH,
                    (int) (scrH - MARGIN - dispH + dispH * (COL_TOP + COL_H * 0.35f) / SURF_H),
                    texW, texH);
        }
        var pose = g.pose();
        pose.pushMatrix();
        // 面板水平位置跟随对齐（垂直保持底部锚定）：
        //   左对齐 → 左下角；中间 → 底部居中；右对齐 → 右下角
        int alignN = 2;
        DesktopSettings st = QplayerScreen.getDesktopSettings();
        if (st != null && st.worldLyricAlign.get() != null) alignN = st.worldLyricAlign.get().intValue();
        int panelX;
        switch (alignN) {
            case 0: panelX = MARGIN; break;
            case 1: panelX = (scrW - dispW) / 2; break;
            default: panelX = scrW - MARGIN - dispW;
        }
        pose.translate(panelX, scrH - MARGIN - dispH);
        // 12 参 blit：width/height = 显示尺寸（GUI 单位），uWidth/vHeight = 源纹理范围（全图）
        g.blit(surf.blitPipeline(), texId,
                0, 0, 0f, 0f,
                dispW, dispH,
                texW, texH,
                texW, texH);
        pose.popMatrix();
    }

    public void dispose() {
        if (surf != null) {
            try { surf.dispose(); } catch (Exception ignored) {}
            surf = null;
        }
        lastLyrics = null;
    }
}
