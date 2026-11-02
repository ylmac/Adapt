package com.ylmac.adapt.render.hud;

import com.ylmac.adapt.render.font.RiseFont;

import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.event.bus.EventBus;
import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.MousePressEvent;
import com.ylmac.adapt.module.ModuleRegistry;
import com.ylmac.adapt.module.render.MusicWidgetModule;
import com.ylmac.adapt.module.misc.music.PlayerController;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.Map;
import com.ylmac.adapt.compose.screens.music.AdaptScreen;
import com.ylmac.adapt.render.surface.GpuContextHelper;
import com.ylmac.adapt.render.surface.MinecraftSurfaceBackend;
import com.ylmac.adapt.render.profile.Probe;
import com.ylmac.adapt.render.surface.SkijaNativeFix;

/**
 * 音乐小组件。一格 100 设计像素，上限 4×3。
 * 数据来自 {@link PlayerController}，配色取当前 Monet 主题。
 * 图标用 SVG 路径画。
 */
public final class MusicWidgetOverlay implements HudElement, MovableHud {

    private static final MusicWidgetOverlay INSTANCE = new MusicWidgetOverlay();

    public static MusicWidgetOverlay get() {
        return INSTANCE;
    }

    /** 一格的设计边长（≈ 的 100dp 基准）。 */
    static final int CELL = 100;
    /** 画布外扩：投影/bloom 画在卡片外面，不留空间就被裁掉（bloom 一直看不到的根因）。 */
    private static final int SHADOW_MARGIN = 40;

    /**
 * 布局形态 — 由格子数推导，逐个对应 的真实布局函数：
 * <pre>
 * ONE_BY_ONE 整块播放键
 * GABE_TWO 封面 / 播放 / 下一首
 * GABE 封面 / 上一首 / 播放 / 下一首
 * BAR / Thin 家族 封面 + 文字 + 播放[+下一首][+上一首]
 * SMALL 2×2 网格：封面 / 播放 / 上一首 / 下一首
 * SMALL_TALL 封面 / 播放 / 上一首+下一首
 * CONTROL 五键大卡
 * </pre>
 */
    private enum Layout { ONE_BY_ONE, GABE_TWO, GABE, BAR, SMALL, SMALL_TALL, CONTROL }

    private MinecraftSurfaceBackend surf;
    private String surfaceKey = "";
    /** Modern 模式：歌词界面的流体背景（用封面取色 + 流动） */
    private com.ylmac.adapt.module.misc.music.lyric.skia.FluidBackground fluidBg;
    private io.github.humbleui.skija.DirectContext fluidCtx;
    private String fluidTrackKey = "";
    private final Paint paint = new Paint();

    /** 封面缓存：字节哈希 → 解码后的 Skia Image（换歌才重新解码）。 */
    private int coverHash;
    private Image coverImage;

    /** 位置（GUI 坐标）。posSet=false 时用默认位置（贴左下角）。 */
    private float posX, posY;
    private boolean posSet;

    /** 格子数（HUD 编辑器拖右下角手柄调整）。 */
    private int cols = 4;
    private int rows = 1;

    /** 当前帧的按钮命中矩形（GUI 坐标）+ 动作。 */
    private float[][] hitRects = new float[0][];
    private String[] hitActions = new String[0];

    private boolean subscribed;
    private boolean registered;

    private MusicWidgetOverlay() {
    }

    // ───────────────────────── 模块状态 ─────────────────────────

    private static MusicWidgetModule module() {
        for (ModuleRegistry.ModuleEntry e : ModuleRegistry.getAllModules()) {
            if (e.linkedRealModule() instanceof MusicWidgetModule m) {
                return m;
            }
        }
        return null;
    }

    private static boolean isEnabled() {
        MusicWidgetModule m = module();
        return m != null && m.isEnabled();
    }

    public void onModuleStateChanged() {
        surfaceKey = "";
        if (isEnabled() && !subscribed) {
            EventBus.INSTANCE.subscribe(this);
            subscribed = true;
        } else if (!isEnabled() && subscribed) {
            EventBus.INSTANCE.unsubscribe(this);
            subscribed = false;
        }
    }

    public void registerHud() {
        if (registered) return;
        registered = true;
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.parse("adaptclient:musicwidget"), this);
    }

    // ───────────────────────── MovableHud ─────────────────────────

    @Override
    public String hudId() {
        return "musicwidget";
    }

    @Override
    public String hudLabel() {
        return "音乐小组件";
    }

    /** 不显示名字标签（用户要求：编辑器里只留手柄）。 */
    @Override
    public boolean hudShowLabel() {
        return false;
    }

    @Override
    public float hudX() {
        return posSet ? posX : 12;
    }

    @Override
    public float hudY() {
        if (posSet) return posY;
        Minecraft mc = Minecraft.getInstance();
        return Math.max(12, mc.getWindow().getGuiScaledHeight() - hudH() - 12);
    }

    @Override
    public float hudW() {
        return designW() * (float) scaleValue();
    }

    @Override
    public float hudH() {
        return designH() * (float) scaleValue();
    }

    @Override
    public boolean hudVisible() {
        // 不检查 screen：GUI 打开时也要显示（ModuleListOverlay 同款语义）。
        Minecraft mc = Minecraft.getInstance();
        return isEnabled() && mc.level != null;
    }

    /** Modern 卡片会取世界模糊。Classic 是纯色底，不采。 */
    public static boolean wantsBackdrop() {
        MusicWidgetOverlay overlay = get();
        if (!overlay.hudVisible()) return false;
        com.ylmac.adapt.module.render.MusicWidgetModule m = module();
        return m != null && m.getStyle() == com.ylmac.adapt.module.render.MusicWidgetModule.Style.MODERN;
    }

    @Override
    public void setHudPos(float x, float y) {
        posX = x;
        posY = y;
        posSet = true;
    }

    @Override
    public boolean hudResizable() {
        return true;
    }

    @Override
    public int hudGridCols() {
        return cols;
    }

    @Override
    public int hudGridRows() {
        return rows;
    }

    @Override
    public int hudMinCols() {
        return 1;
    }

    @Override
    public int hudMinRows() {
        return 1;
    }

    /** 上限 4×3 = 400×300，与 的 一致。 */
    @Override
    public int hudMaxCols() {
        return 4;
    }

    @Override
    public int hudMaxRows() {
        return 3;
    }

    @Override
    public int hudCellSize() {
        return CELL;
    }

    @Override
    public void setHudGridSize(int newCols, int newRows) {
        int c = Math.max(hudMinCols(), Math.min(hudMaxCols(), newCols));
        int r = Math.max(hudMinRows(), Math.min(hudMaxRows(), newRows));
        if (c == cols && r == rows) return;
        cols = c;
        rows = r;
        surfaceKey = "";
    }

    private static double scaleValue() {
        MusicWidgetModule m = module();
        return m == null ? 1.0 : m.getScale();
    }

    public double hudScale() {
        return scaleValue();
    }

    private int designW() {
        return cols * CELL;
    }

    private int designH() {
        return rows * CELL;
    }

    /**
 * 布局分派 — 阈值照，但把它的
 * "一行高"分支单独成 BAR： 的 {@code isSmallHeight} 是
 * {@code height < 100}，而我们的 1 行恰好是 100，所以 2×1/3×1/4×1 会落到
 * "方形"分支里 — 那是给 200×200 以上设计的，压到 100 高会挤成一团。
 * 自己的一行高是独立小组件，这里用它的结构。
 */
    private Layout layoutFor() {
        int w = designW(), h = designH();
        if (w < 120) {
            if (h <= 110) return Layout.ONE_BY_ONE;   // 1×1
            if (h <= 200) return Layout.GABE_TWO;     // 1×2
            return Layout.GABE;                       // 1×3
        }
        if (h <= 100) return Layout.BAR;              // 2×1 / 3×1 / 4×1
        if (w < 250) return h < 250 ? Layout.SMALL : Layout.SMALL_TALL;  // 2×2 / 2×3
        return Layout.CONTROL;                        // 3×2 / 4×2 / 3×3 / 4×3
    }

    // ───────────────────────── 点击（播放控制） ─────────────────────────

    @EventHandler
    public void onMouse(MousePressEvent event) {
        if (event.getAction() != 1 || event.getButton() != 0) return;
        if (!hudVisible() || HudEditor.get().isActive()) return;
        if (Minecraft.getInstance().gui.screen() != null) return;
        Minecraft mc = Minecraft.getInstance();
        float mx = (float) mc.mouseHandler.getScaledXPos(mc.getWindow());
        float my = (float) mc.mouseHandler.getScaledYPos(mc.getWindow());
        if (clickAt(mx, my)) {
            event.cancel();
        }
    }

    /**
 * HUD 编辑器里的"短按 = 点击"入口：编辑器判定为点击（按下到松开很快、
 * 且没有移动）时调这里，于是编辑态下按钮依然能按，长按才进入拖动。
 */
    @Override
    public void hudClick(float mx, float my) {
        if (hudVisible()) {
            clickAt(mx, my);
        }
    }

    /** 命中按钮就执行动作，返回是否命中。 */
    private boolean clickAt(float mx, float my) {
        for (int i = 0; i < hitRects.length; i++) {
            float[] r = hitRects[i];
            if (r == null) continue;
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                PlayerController pc = AdaptScreen.getPlayerController();
                if (pc == null) return false;
                switch (hitActions[i]) {
                    case "prev" -> pc.prev();
                    case "next" -> pc.next();
                    case "play" -> pc.playPause();
                    // 随机 / 循环共用一个三态模式（0=列表循环 1=随机 2=单曲循环）
                    case "shuffle" -> pc.setPlayMode(pc.playMode() == 1 ? 0 : 1);
                    case "repeat" -> pc.setPlayMode(pc.playMode() == 2 ? 0 : 2);
                    default -> { }
                }
                return true;
            }
        }
        return false;
    }

    // ───────────────────────── 渲染 ─────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!hudVisible()) {
            hitRects = new float[0][];
            hitActions = new String[0];
            return;
        }
        Probe.begin("hud.music");
        try {
            render(g);
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[MusicWidget] render error: {}", t.toString());
        } finally {
            Probe.end("hud.music");
        }
    }

    private void render(GuiGraphicsExtractor g) {
        int dw = designW(), dh = designH();
        String key = cols + "x" + rows;
        // 原生设备像素：物理纹理 = 设计尺寸 × 组件缩放 × 窗口 GUI scale（1:1 贴图）
        float deviceScale = (float) scaleValue()
                * Minecraft.getInstance().getWindow().getGuiScale();
        int sw = dw + 2 * SHADOW_MARGIN, sh = dh + 2 * SHADOW_MARGIN;
        if (surf == null || !key.equals(surfaceKey)) {
            if (surf != null) {
                try { surf.dispose(); } catch (Exception ignored) { }
                surf = null;
            }
            SkijaNativeFix.preload();
            surf = new MinecraftSurfaceBackend("music_widget", sw, sh, deviceScale);
            surfaceKey = key;
        } else {
            surf.ensureDeviceScale(deviceScale);
        }

        // acquireCanvas 已经 clear(透明) + scale(2,2)：直接用设计坐标画
        Canvas canvas = surf.acquireCanvas();
        if (canvas == null) return;
        canvas.translate(SHADOW_MARGIN, SHADOW_MARGIN);   // 设计坐标不变，四周留给投影/bloom

        Map<String, String> sc = colorScheme();   // World / Cover（设置里切）
        PlayerController pc = AdaptScreen.getPlayerController();
        clearHitRects();
        switch (layoutFor()) {
            case ONE_BY_ONE -> drawOneByOne(canvas, sc, pc);
            case GABE_TWO -> drawGabeTwo(canvas, sc, pc);
            case GABE -> drawGabe(canvas, sc, pc);
            case BAR -> drawBar(canvas, sc, pc);
            case SMALL -> drawSmall(canvas, sc, pc);
            case SMALL_TALL -> drawSmallTall(canvas, sc, pc);
            case CONTROL -> drawControl(canvas, sc, pc);
        }
        surf.present();

        int texW = surf.getDisplayWidth(), texH = surf.getDisplayHeight();
        float scale = (float) scaleValue();
        int x = Math.round(hudX()), y = Math.round(hudY());
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x - SHADOW_MARGIN * scale, y - SHADOW_MARGIN * scale);
        pose.scale(scale, scale);
        g.blit(surf.blitPipeline(), surf.getTextureId(),
                0, 0, 0f, 0f, sw, sh, texW, texH, texW, texH);
        pose.popMatrix();
    }

    // ── 1×1：整块播放键（：只有一个 fillMaxSize 的播放键） ──
    private void drawOneByOne(Canvas c, Map<String, String> sc, PlayerController pc) {
        int W = designW(), H = designH();
        int surface = col(sc, "surface", 0xFF1C1B1F);
        int playBg = col(sc, "primary", 0xFFD0BCFF);
        int playFg = col(sc, "onPrimaryColor", 0xFF381E72);
        // 单列档位的圆角是 60dp（≥ 半宽）→ 两端全圆。
        panelBg(c, W, H, W * 0.5f, surface);

        float pad = 16;
        float bs = Math.min(W, H) - pad * 2;
        boolean pl = playing(pc);
        roundRect(c, pad, pad, bs, bs, 30, playBg);
        icon(c, pl ? "pause" : "play", W / 2f, H / 2f, bs * 0.46f, playFg);
        addHit(pad, pad, bs, bs, "play");
    }

    /**
 * 1×2：封面 / 播放 / 下一首。
 * 原版结构是"上下两个等权重的 Column"：上半封面、下半两个等高的整宽按钮，
 * 没有标题/艺术家 — 之前画成"封面+两行文字+一个播放键"是错的。
 */
    private void drawGabeTwo(Canvas c, Map<String, String> sc, PlayerController pc) {
        int W = designW(), H = designH();
        int surface = col(sc, "surface", 0xFF1C1B1F);
        int onSurface = col(sc, "onSurfaceColor", 0xFFE6E1E5);
        int ctrlBg = col(sc, "surfaceContainerHigh", 0xFF2B2930);
        int playBg = col(sc, "primary", 0xFFD0BCFF);
        int playFg = col(sc, "onPrimaryColor", 0xFF381E72);
        panelBg(c, W, H, W * 0.5f, surface);

        float pad = 16, spacer = 14;
        float cw = W - pad * 2, ch = H - pad * 2;
        float half = (ch - spacer) / 2f;
        drawCover(c, pad, pad, cw, half, 64, ctrlBg);

        boolean pl = playing(pc);
        float by = pad + half + spacer;
        float bh = (half - 10) / 2f;
        button(c, pad, by, cw, bh, 10, playBg, playFg, pl ? "pause" : "play", 26);
        button(c, pad, by + bh + 10, cw, bh, 10, ctrlBg, onSurface, "next", 26);
        addHit(pad, by, cw, bh, "play");
        addHit(pad, by + bh + 10, cw, bh, "next");
    }

    /**
 * 1×3：封面 / 上一首 / 播放 / 下一首。
 * 下半是三个等高、整宽的按钮竖排 — 不是一行三键。
 */
    private void drawGabe(Canvas c, Map<String, String> sc, PlayerController pc) {
        int W = designW(), H = designH();
        int surface = col(sc, "surface", 0xFF1C1B1F);
        int onSurface = col(sc, "onSurfaceColor", 0xFFE6E1E5);
        int ctrlBg = col(sc, "surfaceContainerHigh", 0xFF2B2930);
        int playBg = col(sc, "primary", 0xFFD0BCFF);
        int playFg = col(sc, "onPrimaryColor", 0xFF381E72);
        panelBg(c, W, H, W * 0.5f, surface);

        float pad = 16, spacer = 14, gap = 10;
        float cw = W - pad * 2, ch = H - pad * 2;
        float half = (ch - spacer) / 2f;
        drawCover(c, pad, pad, cw, half, 64, ctrlBg);

        boolean pl = playing(pc);
        float by = pad + half + spacer;
        float bh = (half - gap * 2) / 3f;
        button(c, pad, by, cw, bh, 10, ctrlBg, onSurface, "prev", 26);
        button(c, pad, by + bh + gap, cw, bh, 10, playBg, playFg, pl ? "pause" : "play", 26);
        button(c, pad, by + (bh + gap) * 2, cw, bh, 10, ctrlBg, onSurface, "next", 26);
        addHit(pad, by, cw, bh, "prev");
        addHit(pad, by + bh + gap, cw, bh, "play");
        addHit(pad, by + (bh + gap) * 2, cw, bh, "next");
    }

    /**
 * 一行高（2×1 / 3×1 / 4×1）：封面 + 标题/艺术家 + 播放[+下一首][+上一首]。
 * 结构照 （窄档照 /
 *）。
 * <p>
 * <b>为什么不重叠。</b>先把按钮块的总宽算出来，剩下的宽度才给封面和文字：
 * 文字放不下就整块丢掉、封面吃掉文字空间；封面也放不下就只留按钮。
 * 之前的写法是"封面先占满高度、文字最小 20px"，200×100 时封面 76px 宽压到
 * 了按钮上、文字也被压在按钮下面 — 就是用户看到的"1×2 叠加"。
 */
    private void drawBar(Canvas c, Map<String, String> sc, PlayerController pc) {
        int W = designW(), H = designH();
        int surface = col(sc, "surface", 0xFF1C1B1F);
        int onSurface = col(sc, "onSurfaceColor", 0xFFE6E1E5);
        int onVariant = col(sc, "onSurfaceVariantColor", 0xFFCAC4D0);
        int ctrlBg = col(sc, "surfaceContainerHigh", 0xFF2B2930);
        int playBg = col(sc, "primary", 0xFFD0BCFF);
        int playFg = col(sc, "onPrimaryColor", 0xFF381E72);
        panelBg(c, W, H, 28, surface);

        float pad = 16, gap = 6;
        float bs = Math.max(24, Math.min(44, H - pad * 2));
        // 宽度不够时少放一个键：< 250 只有 播放/下一首（VeryThin 档位如此）。
        boolean three = W >= 250;
        float reserve = (three ? 3 : 2) * bs + (three ? 2 : 1) * gap;
        float availW = W - pad * 2;

        float art = Math.min(H - pad * 2, 56);
        float textX = pad + art + 10;
        float textW = availW - art - 10 - reserve - 8;
        if (textW < 40) {
            // 文字放不下：整块丢掉，封面吃掉这段空间；封面也放不下就只留按钮。
            textW = 0;
            art = Math.min(H - pad * 2, availW - reserve - 10);
            if (art < 28) art = 0;
            textX = pad + art + (art > 0 ? 10 : 0);
        }
        if (art > 0) {
            drawCover(c, pad, (H - art) / 2f, art, art, 14, ctrlBg);
        }
        if (textW > 0) {
            float ty = Math.max(pad, (H - 30) / 2f);
            text(c, title(pc), 14, onSurface, textX, ty, textW);
            text(c, artist(pc), 12, onVariant, textX, ty + 16, textW);
        }

        boolean pl = playing(pc);
        float by = (H - bs) / 2f;
        float bx = W - pad - reserve;
        if (three) {
            button(c, bx, by, bs, bs, 16, ctrlBg, onSurface, "prev", barIconSize(bs, false));
            addHit(bx, by, bs, bs, "prev");
            bx += bs + gap;
        }
        button(c, bx, by, bs, bs, pl ? 16 : 20, playBg, playFg, pl ? "pause" : "play",
                barIconSize(bs, true));
        addHit(bx, by, bs, bs, "play");
        bx += bs + gap;
        button(c, bx, by, bs, bs, 16, ctrlBg, onSurface, "next", barIconSize(bs, false));
        addHit(bx, by, bs, bs, "next");
    }

    // ── 2×2：2×2 网格（：封面 / 播放 / 上一首 / 下一首） ──
    private void drawSmall(Canvas c, Map<String, String> sc, PlayerController pc) {
        int W = designW(), H = designH();
        int surface = col(sc, "surface", 0xFF1C1B1F);
        int onSurface = col(sc, "onSurfaceColor", 0xFFE6E1E5);
        int ctrlBg = col(sc, "surfaceContainerHigh", 0xFF2B2930);
        int playBg = col(sc, "primary", 0xFFD0BCFF);
        int playFg = col(sc, "onPrimaryColor", 0xFF381E72);
        panelBg(c, W, H, 28, surface);

        // ：gap 6dp、item 圆角 16dp、图标 = minSide×0.14（控制键）
        // 与 minSide×0.16（播放键）— 原版是动态算的，不是固定值。
        float pad = 16, gap = 6;
        float cell = Math.min((W - pad * 2 - gap) / 2f, (H - pad * 2 - gap) / 2f);
        float x0 = pad, x1 = pad + cell + gap;
        float y0 = pad, y1 = pad + cell + gap;
        float minSide = Math.min(W, H);
        boolean pl = playing(pc);

        drawCover(c, x0, y0, cell, cell, 16, ctrlBg);
        roundRect(c, x1, y0, cell, cell, 16, playBg);
        icon(c, pl ? "pause" : "play", x1 + cell / 2f, y0 + cell / 2f,
                minSide * 0.16f, playFg);
        roundRect(c, x0, y1, cell, cell, 16, ctrlBg);
        icon(c, "prev", x0 + cell / 2f, y1 + cell / 2f, minSide * 0.14f, onSurface);
        roundRect(c, x1, y1, cell, cell, 16, ctrlBg);
        icon(c, "next", x1 + cell / 2f, y1 + cell / 2f, minSide * 0.14f, onSurface);

        addHit(x1, y0, cell, cell, "play");
        addHit(x0, y1, cell, cell, "prev");
        addHit(x1, y1, cell, cell, "next");
    }

    /**
 * 2×3：封面 / 播放 / 上一首+下一首。
 * 原版是三条等权重行：封面占满宽、播放键整宽、底部两键平分。
 * 播放键的圆角在播放时是 12dp、暂停时是胶囊（原版
 * {@code playButtonCornerRadius = if (isPlaying) 12.dp else 60.dp}）。
 */
    private void drawSmallTall(Canvas c, Map<String, String> sc, PlayerController pc) {
        int W = designW(), H = designH();
        int surface = col(sc, "surface", 0xFF1C1B1F);
        int onSurface = col(sc, "onSurfaceColor", 0xFFE6E1E5);
        int ctrlBg = col(sc, "surfaceContainerHigh", 0xFF2B2930);
        int playBg = col(sc, "primary", 0xFFD0BCFF);
        int playFg = col(sc, "onPrimaryColor", 0xFF381E72);
        panelBg(c, W, H, 28, surface);

        float pad = 12, gap = 8;
        float cw = W - pad * 2, ch = H - pad * 2;
        float rowH = (ch - gap * 2) / 3f;
        boolean pl = playing(pc);

        drawCover(c, pad, pad, cw, rowH, 16, ctrlBg);

        float y = pad + rowH + gap;
        float pr = pl ? 12 : rowH / 2f;
        roundRect(c, pad, y, cw, rowH, pr, playBg);
        icon(c, pl ? "pause" : "play", pad + cw / 2f, y + rowH / 2f, 26, playFg);
        addHit(pad, y, cw, rowH, "play");

        y += rowH + gap;
        float bw = (cw - gap) / 2f;
        button(c, pad, y, bw, rowH, 16, ctrlBg, onSurface, "prev", 26);
        button(c, pad + bw + gap, y, bw, rowH, 16, ctrlBg, onSurface, "next", 26);
        addHit(pad, y, bw, rowH, "prev");
        addHit(pad + bw + gap, y, bw, rowH, "next");
    }

    /**
 * 大卡：封面 + 两行标题 + <b>五个按钮</b>
 * （随机 / 上一首 / 播放 / 下一首 / 循环）— 照。
 * 原版这一档没有进度条，五个键平分底行宽度；随机/循环激活时反色。
 */
    private void drawControl(Canvas c, Map<String, String> sc, PlayerController pc) {
        int W = designW(), H = designH();
        int surface = col(sc, "surface", 0xFF1C1B1F);
        int onSurface = col(sc, "onSurfaceColor", 0xFFE6E1E5);
        int onVariant = col(sc, "onSurfaceVariantColor", 0xFFCAC4D0);
        int ctrlBg = col(sc, "surfaceContainerHigh", 0xFF2B2930);
        int playBg = col(sc, "primary", 0xFFD0BCFF);
        int playFg = col(sc, "onPrimaryColor", 0xFF381E72);
        panelBg(c, W, H, 28, surface);

        float pad = 16;
        float art = Math.min(80, H * 0.45f);
        drawCover(c, pad, pad, art, art, 16, ctrlBg);

        float tx = pad + art + 12;
        float tw = Math.max(20, W - tx - pad);
        text(c, title(pc), 16, onSurface, tx, pad + 2, tw);
        text(c, artist(pc), 14, onVariant, tx, pad + 26, tw);

        boolean pl = playing(pc);
        int mode = pc == null || pc.playMode.peek() == null ? 0 : pc.playMode.peek();
        float bh = Math.min(48, Math.max(34, H - pad * 2 - art - 14));
        float gap = 6;
        float bw = (W - pad * 2 - gap * 4) / 5f;
        float by = H - pad - bh;

        button(c, pad, by, bw, bh, 16, mode == 1 ? playBg : ctrlBg,
                mode == 1 ? playFg : onSurface, "shuffle", 20);
        button(c, pad + (bw + gap), by, bw, bh, 16, ctrlBg, onSurface, "prev", 20);
        button(c, pad + (bw + gap) * 2, by, bw, bh, pl ? 16 : 20, playBg, playFg,
                pl ? "pause" : "play", 22);
        button(c, pad + (bw + gap) * 3, by, bw, bh, 16, ctrlBg, onSurface, "next", 20);
        button(c, pad + (bw + gap) * 4, by, bw, bh, 16, mode == 2 ? playBg : ctrlBg,
                mode == 2 ? playFg : onSurface, "repeat", 20);

        addHit(pad, by, bw, bh, "shuffle");
        addHit(pad + (bw + gap), by, bw, bh, "prev");
        addHit(pad + (bw + gap) * 2, by, bw, bh, "play");
        addHit(pad + (bw + gap) * 3, by, bw, bh, "next");
        addHit(pad + (bw + gap) * 4, by, bw, bh, "repeat");
    }

    // ───────────────────────── 绘制工具 ─────────────────────────

    private void clearHitRects() {
        hitRects = new float[0][];
        hitActions = new String[0];
    }

    /** 设计坐标 → GUI 坐标（乘 Scale + 加元素原点）；点击测试用的是 GUI 坐标。 */
    private void addHit(float x, float y, float w, float h, String action) {
        float s = (float) scaleValue();
        float ox = hudX(), oy = hudY();
        float[][] rects = Arrays.copyOf(hitRects, hitRects.length + 1);
        String[] acts = Arrays.copyOf(hitActions, hitActions.length + 1);
        rects[rects.length - 1] = new float[]{ox + x * s, oy + y * s, w * s, h * s};
        acts[acts.length - 1] = action;
        hitRects = rects;
        hitActions = acts;
    }

    /**
 * 卡片底（7 个布局都调它）：
 * Classic = 原来的纯色圆角矩形；Modern = 记分榜式面板 —
 * 黑 190 投影 + 歌词界面的流体背景（裁剪到圆角）+ 压暗 + 主题色 bloom。
 */
    private void panelBg(Canvas c, float w, float h, float r, int surface) {
        com.ylmac.adapt.module.render.MusicWidgetModule m =
                com.ylmac.adapt.module.render.MusicWidgetModule.get();
        if (m == null || m.getStyle() != com.ylmac.adapt.module.render.MusicWidgetModule.Style.MODERN) {
            roundRect(c, 0, 0, w, h, r, surface);
            return;
        }
        // 完全照积分榜那张卡片：影子(黑190 模糊6) → 毛玻璃 → 半透明流体背景 → bloom。
        // 圆角用各布局原来的 r。
        // 1) 影子（积分榜的 BLOOM 队列 = Themes.rE 黑 190）
        try {
            io.github.humbleui.skija.ImageFilter blur = CachedBlur.of(
                    6f, io.github.humbleui.skija.FilterTileMode.DECAL);
            Paint lp = new Paint().setImageFilter(blur);
            c.saveLayer(Rect.makeXYWH(-24, -24, w + 48, h + 48), lp);
            paint.setColor(0xC0000000);
            c.drawRRect(RRect.makeXYWH(1, 1, w - 2, h - 2, r), paint);
            c.restore();
            lp.close();
        } catch (Throwable ignored) {
        }
        // 2) bloom：主题/封面主色，模糊 20、alpha 0x99。
        // ⚠ 用 DIFFERENCE 裁掉卡片区域 → 光晕只出现在卡片**外面**
        // （否则卡片是半透明的，彩色光晕会透上来"叠在卡片上"）。
        try {
            io.github.humbleui.skija.ImageFilter blur = CachedBlur.of(
                    20f, io.github.humbleui.skija.FilterTileMode.DECAL);
            Paint lp = new Paint().setImageFilter(blur);
            int saveOuter = c.save();
            c.clipRRect(RRect.makeXYWH(0, 0, w, h, r), io.github.humbleui.skija.ClipMode.DIFFERENCE, true);
            c.saveLayer(Rect.makeXYWH(-56, -56, w + 112, h + 112), lp);
            paint.setColor(withAlpha(col(colorScheme(), "primary", 0xFFD0BCFF), 0x99));
            c.drawRRect(RRect.makeXYWH(1, 1, w - 2, h - 2, r), paint);
            c.restore();
            c.restoreToCount(saveOuter);
            lp.close();
        } catch (Throwable ignored) {
        }
        // 3) 毛玻璃 + 半透明流体背景（裁到圆角）
        int save = c.save();
        c.clipRRect(RRect.makeXYWH(0, 0, w, h, r), io.github.humbleui.skija.ClipMode.INTERSECT, true);
        // 3a) 毛玻璃（本帧共享截帧，和积分榜/模块列表同一套映射）
        try {
            io.github.humbleui.skija.Image bd = BackdropBlurCapture.getBlurredImage();
            if (bd != null) {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    int guiW = mc.getWindow().getGuiScaledWidth();
                    int guiH = mc.getWindow().getGuiScaledHeight();
                    // 卡片在屏幕上的左上角就是 (hudX, hudY)（画布平移 M 与贴图回退 M 相抵）
                    float gx = hudX();
                    float gy = hudY();
                    float imgW = bd.getWidth(), imgH = bd.getHeight();
                    c.drawImageRect(bd,
                            Rect.makeXYWH(gx * imgW / guiW, gy * imgH / guiH,
                                    w * (float) scaleValue() * imgW / guiW,
                                    h * (float) scaleValue() * imgH / guiH),
                            Rect.makeXYWH(0, 0, w, h),
                            io.github.humbleui.skija.SamplingMode.LINEAR, null, true);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
        }
        // 3b) 流体背景（半透明叠在毛玻璃上，两种效果都看得见）
        boolean painted = false;
        try {
            if (fluidCtx == null) fluidCtx = GpuContextHelper.getOrCreateContext();
            if (fluidBg == null) fluidBg = new com.ylmac.adapt.module.misc.music.lyric.skia.FluidBackground(System.nanoTime());
            PlayerController pc = AdaptScreen.getPlayerController();
            byte[] cover = pc == null ? null : pc.coverBytes.peek();
            String key = pc == null ? "" : (String.valueOf(pc.title.peek()) + "|" + pc.artist.peek());
            if (!key.equals(fluidTrackKey)) fluidTrackKey = key;
            if (fluidCtx != null && m.dynamicBackground()) {
                Paint layer = new Paint().setColor(0x8C000000);   // 层 alpha 55%
                int s2 = c.saveLayer(Rect.makeXYWH(0, 0, w, h), layer);
                fluidBg.render(c, fluidCtx, 1f, w, h, cover, fluidTrackKey, System.nanoTime(), false);
                c.restoreToCount(s2);
                layer.close();
                painted = true;
            }
        } catch (Throwable ignored) {
        }
        if (!painted) {
            paint.setColor(surface);
            c.drawRect(Rect.makeXYWH(0, 0, w, h), paint);
        }
        c.restoreToCount(save);
    }

    private void roundRect(Canvas c, float x, float y, float w, float h, float r, int color) {
        if (w <= 0 || h <= 0) return;
        paint.setColor(color);
        c.drawRRect(RRect.makeXYWH(x, y, w, h, r), paint);
    }

    /**
 * 图标按钮：圆角矩形底 + 居中图标。
 * <p>
 * <b>圆角与图标尺寸一律照 的调用点。</b>原版每个档位都显式传
 * {@code cornerRadius} 和 {@code iconSize}（的
 *），之前这里统一用 {@code min(w,h)*0.42} 当图标
 * 尺寸 — 大卡里 48px 按钮配 20 图标是对的，但 1×2 的 33px 按钮配 14 图标
 * 就比原版（26dp）小一半，看起来"形状不一样"。
 */
    private void button(Canvas c, float x, float y, float w, float h, float r,
                        int bg, int fg, String iconName, float iconSize) {
        roundRect(c, x, y, w, h, r, bg);
        icon(c, iconName, x + w / 2f, y + h / 2f, iconSize, fg);
    }

    /** 图标尺寸按"原版按钮 40dp"等比缩放（一行高按钮在我们的格子里是 44px）。 */
    private static float barIconSize(float bs, boolean play) {
        return (play ? 22f : 20f) * (bs / 40f);
    }

    /** 一行文字（矢量，自动省略号，字体按码点回退）。 */
    private void text(Canvas c, String s, float size, int color, float x, float y, float maxW) {
        if (s == null || s.isEmpty() || maxW <= 4) return;
        RiseFont font = RiseFont.main(size * 2f, RiseFont.Weight.MEDIUM);
        font.drawVector(c, elide(font, s, maxW), x, y, color);
    }

    /**
 * 矢量图标。 用的是 Material Symbols Rounded 的
 * {@code rounded_*_filled_24} drawable（{@code res/drawable/rounded_*.xml}），
 * 这里直接嵌它的 {@code pathData} — 之前是自己拼的近似图形（尖角三角、
 * 直边竖条），和原版圆角图标对不上（用户："控件的形状和原版不一样"）。
 * 坐标是 960×960 viewport，绘制时缩放平移到目标方框。
 */
    private static final float ICON_VIEWPORT = 960f;

    private static final Map<String, String> ICON_PATHS = Map.of(
            // rounded_skip_previous_filled_24.xml
            "prev", "M220,680L220,280Q220,263 231.5,251.5Q243,240 260,240Q277,240 288.5,251.5"
                    + "Q300,263 300,280L300,680Q300,697 288.5,708.5Q277,720 260,720Q243,720 231.5,708.5"
                    + "Q220,697 220,680ZM678,679L430,513Q421,507 416.5,498.5Q412,490 412,480"
                    + "Q412,470 416.5,461.5Q421,453 430,447L678,281Q683,277 689,276Q695,275 700,275"
                    + "Q716,275 728,286Q740,297 740,315L740,645Q740,663 728,674Q716,685 700,685"
                    + "Q695,685 689,684Q683,683 678,679Z",
            // rounded_skip_next_filled_24.xml
            "next", "M660,680L660,280Q660,263 671.5,251.5Q683,240 700,240Q717,240 728.5,251.5"
                    + "Q740,263 740,280L740,680Q740,697 728.5,708.5Q717,720 700,720Q683,720 671.5,708.5"
                    + "Q660,697 660,680ZM220,645L220,315Q220,297 232,286Q244,275 260,275"
                    + "Q265,275 271,276Q277,277 282,281L530,447Q539,453 543.5,461.5Q548,470 548,480"
                    + "Q548,490 543.5,498.5Q539,507 530,513L282,679Q277,683 271,684Q265,685 260,685"
                    + "Q244,685 232,674Q220,663 220,645Z",
            // rounded_play_arrow_filled_24.xml
            "play", "M320,687L320,273Q320,256 332,244.5Q344,233 360,233Q365,233 370.5,234.5"
                    + "Q376,236 381,239L707,446Q716,452 720.5,461Q725,470 725,480Q725,490 720.5,499"
                    + "Q716,508 707,514L381,721Q376,724 370.5,725.5Q365,727 360,727Q344,727 332,715.5"
                    + "Q320,704 320,687Z",
            // rounded_pause_filled_24.xml
            "pause", "M320,760Q287,760 263.5,736.5Q240,713 240,680L240,280Q240,247 263.5,223.5"
                    + "Q287,200 320,200Q353,200 376.5,223.5Q400,247 400,280L400,680Q400,713 376.5,736.5"
                    + "Q353,760 320,760ZM640,760Q607,760 583.5,736.5Q560,713 560,680L560,280"
                    + "Q560,247 583.5,223.5Q607,200 640,200Q673,200 696.5,223.5Q720,247 720,280"
                    + "L720,680Q720,713 696.5,736.5Q673,760 640,760Z",
            // rounded_shuffle_24.xml
            "shuffle", "M600,800Q583,800 571.5,788.5Q560,777 560,760Q560,743 571.5,731.5"
                    + "Q583,720 600,720L664,720L565,621Q553,609 553.5,592.5Q554,576 566,564"
                    + "Q578,552 594.5,552Q611,552 623,564L720,662L720,600Q720,583 731.5,571.5"
                    + "Q743,560 760,560Q777,560 788.5,571.5Q800,583 800,600L800,760Q800,777 788.5,788.5"
                    + "Q777,800 760,800L600,800ZM172,788Q161,777 161,760Q161,743 172,732L664,240"
                    + "L600,240Q583,240 571.5,228.5Q560,217 560,200Q560,183 571.5,171.5Q583,160 600,160"
                    + "L760,160Q777,160 788.5,171.5Q800,183 800,200L800,360Q800,377 788.5,388.5"
                    + "Q777,400 760,400Q743,400 731.5,388.5Q720,377 720,360L720,296L228,788"
                    + "Q217,799 200,799Q183,799 172,788ZM171,228Q160,217 160,200Q160,183 171,172"
                    + "Q182,161 198.5,161Q215,161 227,172L395,339Q406,350 406.5,366.5Q407,383 395,395"
                    + "Q384,406 367,406Q350,406 339,395L171,228Z",
            // rounded_repeat_24.xml
            "repeat", "M274,760L308,794Q320,806 319.5,822Q319,838 308,850Q296,862 279.5,862.5"
                    + "Q263,863 251,851L148,748Q142,742 139.5,735Q137,728 137,720Q137,712 139.5,705"
                    + "Q142,698 148,692L251,589Q263,577 279.5,577.5Q296,578 308,590Q319,602 319.5,618"
                    + "Q320,634 308,646L274,680L680,680Q680,680 680,680Q680,680 680,680L680,560"
                    + "Q680,543 691.5,531.5Q703,520 720,520Q737,520 748.5,531.5Q760,543 760,560L760,680"
                    + "Q760,713 736.5,736.5Q713,760 680,760L274,760ZM686,280L280,280Q280,280 280,280"
                    + "Q280,280 280,280L280,400Q280,417 268.5,428.5Q257,440 240,440Q223,440 211.5,428.5"
                    + "Q200,417 200,400L200,280Q200,247 223.5,223.5Q247,200 280,200L686,200L652,166"
                    + "Q640,154 640.5,138Q641,122 652,110Q664,98 680.5,97.5Q697,97 709,109L812,212"
                    + "Q818,218 820.5,225Q823,232 823,240Q823,248 820.5,255Q818,262 812,268L709,371"
                    + "Q697,383 680.5,382.5Q664,382 652,370Q641,358 640.5,342Q640,326 652,314L686,280Z",
            // rounded_repeat_one_24.xml
            "repeat_one", "M460,420L430,420Q417,420 408.5,411.5Q400,403 400,390Q400,377 408.5,368.5"
                    + "Q417,360 430,360L480,360Q497,360 508.5,371.5Q520,383 520,400L520,570"
                    + "Q520,583 511.5,591.5Q503,600 490,600Q477,600 468.5,591.5Q460,583 460,570L460,420Z"
                    + "M274,760L308,794Q320,806 319.5,822Q319,838 308,850Q296,862 279.5,862.5"
                    + "Q263,863 251,851L148,748Q142,742 139.5,735Q137,728 137,720Q137,712 139.5,705"
                    + "Q142,698 148,692L251,589Q263,577 279.5,577.5Q296,578 308,590Q319,602 319.5,618"
                    + "Q320,634 308,646L274,680L680,680Q680,680 680,680Q680,680 680,680L680,560"
                    + "Q680,543 691.5,531.5Q703,520 720,520Q737,520 748.5,531.5Q760,543 760,560L760,680"
                    + "Q760,713 736.5,736.5Q713,760 680,760L274,760ZM686,280L280,280Q280,280 280,280"
                    + "Q280,280 280,280L280,400Q280,417 268.5,428.5Q257,440 240,440Q223,440 211.5,428.5"
                    + "Q200,417 200,400L200,280Q200,247 223.5,223.5Q247,200 280,200L686,200L652,166"
                    + "Q640,154 640.5,138Q641,122 652,110Q664,98 680.5,97.5Q697,97 709,109L812,212"
                    + "Q818,218 820.5,225Q823,232 823,240Q823,248 820.5,255Q818,262 812,268L709,371"
                    + "Q697,383 680.5,382.5Q664,382 652,370Q641,358 640.5,342Q640,326 652,314L686,280Z");

    private void icon(Canvas c, String name, float cx, float cy, float size, int color) {
        String d = ICON_PATHS.get(name);
        if (d == null) d = ICON_PATHS.get("play");
        Path path = Path.makeFromSVGString(d);
        paint.setColor(color);
        int save = c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(size / ICON_VIEWPORT, size / ICON_VIEWPORT);
        c.drawPath(path, paint);
        c.restoreToCount(save);
        path.close();
    }

    /**
 * 封面：圆角裁剪 + <b>居中裁切填充</b>（Compose 的 {@code ContentScale.Crop}，
 * 的 用的就是它）；没有封面就画一个底色块。
 * 非正方形区域（1×2/1×3/2×3 的封面是整宽的横条）不能拉伸 — 拉伸会变形。
 */
    private void drawCover(Canvas c, float x, float y, float w, float h, float r, int placeholder) {
        Image img = coverImage();
        if (img == null) {
            roundRect(c, x, y, w, h, r, placeholder);
            return;
        }
        float sw = img.getWidth(), sh = img.getHeight();
        float destAspect = w / h, srcAspect = sw / sh;
        float sx = 0, sy = 0, cw = sw, chh = sh;
        if (srcAspect > destAspect) {          // 图更宽 → 左右裁掉
            cw = sh * destAspect;
            sx = (sw - cw) / 2f;
        } else {                               // 图更高 → 上下裁掉
            chh = sw / destAspect;
            sy = (sh - chh) / 2f;
        }
        int save = c.save();
        c.clipRRect(RRect.makeXYWH(x, y, w, h, r), ClipMode.INTERSECT, true);
        c.drawImageRect(img, Rect.makeXYWH(sx, sy, cw, chh),
                Rect.makeXYWH(x, y, w, h));
        c.restoreToCount(save);
    }

    /** 正方形封面（2×2 网格 / 大卡的 80×80）。 */
    private void drawCover(Canvas c, float x, float y, float size, float r, int placeholder) {
        drawCover(c, x, y, size, size, r, placeholder);
    }

    private Image coverImage() {
        PlayerController pc = AdaptScreen.getPlayerController();
        byte[] bytes = pc == null ? null : pc.coverBytes.peek();
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        int hash = Arrays.hashCode(bytes);
        if (hash == coverHash && coverImage != null) {
            return coverImage;
        }
        try {
            Image decoded = Image.makeFromEncoded(bytes);
            if (coverImage != null) {
                try { coverImage.close(); } catch (Exception ignored) { }
            }
            coverImage = decoded;
            coverHash = hash;
            return coverImage;
        } catch (Throwable t) {
            coverHash = hash;
            return null;
        }
    }

    // ───────────────────────── 数据与工具 ─────────────────────────

    private static boolean playing(PlayerController pc) {
        return pc != null && Boolean.TRUE.equals(pc.playing.peek());
    }

    private static String title(PlayerController pc) {
        String s = pc == null ? null : pc.title.peek();
        return s == null || s.isEmpty() ? "未在播放" : s;
    }

    private static String artist(PlayerController pc) {
        String s = pc == null ? null : pc.artist.peek();
        return s == null || s.isEmpty() ? "QPlayer" : s;
    }

    private static String elide(RiseFont font, String text, float maxWidth) {
        if (text == null) return "";
        if (font.getVectorWidth(text) <= maxWidth) return text;
        String ellipsis = "…";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (font.getVectorWidth(sb.toString() + text.substring(i, i + n) + ellipsis) > maxWidth) {
                break;
            }
            sb.append(text, i, i + n);
            i += n;
        }
        return sb + ellipsis;
    }

    /** 封面取色缓存（字节数组变了才重算） */
    private static byte[] coverSchemeBytes;
    private static Map<String, String> coverSchemeCache;

    /**
 * 卡片配色：World = 世界取色的 Monet 主题；Cover = 从歌曲封面取主色当种子
 * 生成一套 Monet 方案。
 */
    /** 封面取色方案（供时钟组件复用）；取不到返回 null。 */
    public static Map<String, String> coverSchemeFromCover() {
        PlayerController pc = AdaptScreen.getPlayerController();
        byte[] bytes = pc == null ? null : pc.coverBytes.peek();
        if (bytes == null || bytes.length == 0) return null;
        if (bytes == coverSchemeBytes && coverSchemeCache != null) return coverSchemeCache;
        coverSchemeBytes = bytes;
        coverSchemeCache = buildCoverScheme(bytes);
        return coverSchemeCache;
    }

    /**
 * 卡片配色：World = 世界取色的 Monet 主题；Cover = 从封面取色。
 * <p>
 * ⚠ 必须由**调用方自己**传它的取色设置：音乐和时钟各有独立的
 * "Color Source" 选项。之前这里直接读音乐模块的设置，两边共用 →
 * 只有音乐也选了 Cover，时钟才跟着变（用户实测："只有两边选择是一样的时候
 * 才会生效 不然都是世界取色"）。
 */
    public static Map<String, String> colorScheme(boolean useCover) {
        if (!useCover) return themeScheme();
        PlayerController pc = AdaptScreen.getPlayerController();
        byte[] bytes = pc == null ? null : pc.coverBytes.peek();
        if (bytes == null || bytes.length == 0) return themeScheme();
        if (bytes == coverSchemeBytes && coverSchemeCache != null) return coverSchemeCache;
        coverSchemeBytes = bytes;
        coverSchemeCache = buildCoverScheme(bytes);
        return coverSchemeCache != null ? coverSchemeCache : themeScheme();
    }

    /** 音乐小组件自己的取色（读音乐模块的设置）。 */
    public static Map<String, String> colorScheme() {
        com.ylmac.adapt.module.render.MusicWidgetModule m =
                com.ylmac.adapt.module.render.MusicWidgetModule.get();
        return colorScheme(m != null
                && m.getColorSource() == com.ylmac.adapt.module.render.MusicWidgetModule.ColorSource.COVER);
    }

    /** 解码封面 → 取平均色 → Monet 生成方案。 */
    private static Map<String, String> buildCoverScheme(byte[] bytes) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(bytes));
            if (img == null) return null;
            java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(
                    8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = small.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, 8, 8, null);
            g.dispose();
            long r = 0, gg = 0, b = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int c = small.getRGB(x, y);
                    r += (c >> 16) & 0xFF;
                    gg += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
            }
            int seed = (int) (((r / 64) << 16) | ((gg / 64) << 8) | (b / 64));
            boolean dark = true;
            try {
                Object inst = com.ylmac.adapt.theme.StyleManager.__instance();
                if (inst instanceof com.ylmac.adapt.theme.StyleManager sm) {
                    dark = Boolean.TRUE.equals(sm.isDarkTheme.peek());
                }
            } catch (Throwable ignored) {
            }
            return com.ylmac.adapt.theme.color.Scheme.generate(seed, dark);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> themeScheme() {
        try {
            Object inst = com.ylmac.adapt.theme.StyleManager.__instance();
            if (inst instanceof com.ylmac.adapt.theme.StyleManager sm) {
                Object scheme = sm.currentScheme.peek();
                if (scheme instanceof Map<?, ?> m) {
                    return (Map<String, String>) m;
                }
            }
        } catch (Throwable ignored) {
        }
        return Map.of();
    }

    private static int withAlpha(int rgb, int a) {
        int alpha = Math.max(0, Math.min(255, a));
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static int col(Map<String, String> scheme, String role, int fallback) {
        String hex = scheme.get(role);
        if (hex == null) return fallback;
        try {
            String s = hex.startsWith("#") ? hex.substring(1) : hex;
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
            if (s.length() == 6) return 0xFF000000 | (int) Long.parseLong(s, 16);
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
