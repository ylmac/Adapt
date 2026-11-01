package com.ylmac.adapt.render.clickgui;

import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.ModuleDescriptions;
import com.ylmac.adapt.features.module.ModuleManager;
import com.ylmac.adapt.features.module.impl.render.gui.RiseConfigs;
import com.ylmac.adapt.features.module.impl.render.gui.RiseLang;
import com.ylmac.adapt.features.module.impl.render.gui.RiseTheme;
import com.ylmac.adapt.features.module.settings.Setting;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ButtonSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.ColorSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.EnumSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import com.ylmac.adapt.features.module.settings.impl.StringSetting;
import com.ylmac.adapt.render.GpuContextHelper;
import com.ylmac.adapt.render.MinecraftSurfaceBackend;
import com.ylmac.adapt.render.RiseFont;
import com.ylmac.adapt.render.SkijaNativeFix;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.types.Point;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rise 6.9.5 ClickGUI —— <b>逐像素一致</b>移植（MC 26.2 / Fabric / Skija）。
 * <p>
 * 来源：{@code com.alan.clients.ui.click.standard.RiseClickGUI} + components/ModuleComponent +
 * UIColors + util/render/RenderUtil。所有数值照抄原版：
 * <ul>
 *   <li>窗口 400×300 / r=12 / BACKGROUND(23,26,33,254)，dropShadow(18 层同心圆角矩形,
 *       外扩 30.0, 圆角 ×1.3 = 15.6)；</li>
 *   <li>侧栏 100px SECONDARY(18,20,25) + 右侧 30px 黑色渐隐(≤36)；品牌 32pt + 版本 16pt accent；</li>
 *   <li>分类：起始 y=45.5、行距 19.5、药丸 r=5 <b>accent.darker()</b>、图标 17pt、文字 16pt；</li>
 *   <li>模块行 283×38 r=6 OVERLAY(0,0,0,50)；悬停黑 20 / 按住黑 35（LINEAR 50ms）；
 *       名字 20pt（启用 = accent）、描述 15pt alpha70；<b>左键开关 / 右键展开</b>；</li>
 *   <li>展开高度 = 38 + Σ可见设置高 − 1，EASE_OUT_EXPO（时长 min(3ms×高, 450ms)），内容线性淡入；</li>
 *   <li>强调色 = Blend 主题正弦流动 sin(t/600 + x*0.005 + y*0.06)，位置参数传 y/5；</li>
 *   <li>滚动：滚轮/2（=60px/格）、clamp(min,0)、每 ms 1% 逼近；右缘 1px 滚动条(白 alpha60)；</li>
 *   <li>切页：旧页保持 200ms → 黑幕 200ms 淡出（总 400ms）；</li>
 *   <li>Shaders 开启时跑 BLOOM 通道（radius 14 / compression 2，离屏模糊后加法合成），
 *       发光图元 = 窗口矩形 + 侧栏 + 侧栏右缘 9 个同心圆(直径 0..400 步进 50) + 选中分类药丸。</li>
 * </ul>
 * 控件：Boolean 生长圆点（100ms 0→5）、Number 100px 轨道+5×5 手柄+数值框（可拖可输入）、
 * Bounds 双手柄、Mode/List 左键下一个/右键上一个、String 28 高文本框、Color 15/110 折叠
 * （105×105 取色面板：SV 方块 105×66、色相条 88×7、指示块 11×11、RGB 17pt、hex 13pt、
 * 底部两个 60×10 复制热区）。
 */
public class RiseClickGuiScreen extends Screen {

    // ─────────────── 布局常量（Rise 原值） ───────────────
    private static final float W = 400f, H = 300f, ROUND = 12f;
    private static final float SIDEBAR_W = 100f;
    private static final float ROW_W = 283f, ROW_H = 38f, ROW_GAP = 7f, ROW_R = 6f;
    private static final float CAT_Y0 = 45.5f, CAT_STEP = 19.5f, CAT_X = 10f;
    private static final float SCROLL_TRACK_H = H - 14f;
    private static final float SHADOW_EXPAND = 30f, SHADOW_CORNER = ROUND * 1.3f;
    /** dropShadow(18, …) —— Rise 窗口调用的层数 */
    private static final int SHADOW_ITERS = 18;

    // ─────────────── 颜色（UIColors / Themes） ───────────────
    private static final int C_BACKGROUND = 0xFE171A21;   // (23,26,33,254)
    private static final int C_SECONDARY = 0xFF121419;    // (18,20,25,255)
    private static final int C_OVERLAY = 0x32000000;      // 黑 alpha 50
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_SECONDARY_TEXT = 0xDCFFFFFF; // 220
    private static final int C_TRINARY_TEXT = 0x82FFFFFF;   // 130
    private static final int ACCENT_A = 0xFF4794FD;       // Themes.BLEND 主色 1
    private static final int ACCENT_B = 0xFF47FDA0;       // Themes.BLEND 主色 2

    // ─────────────── 字体（Rise 点值 → RiseFont 内部 ÷2 渲染） ───────────────
    private final RiseFont f32 = RiseFont.main(32, RiseFont.Weight.REGULAR);
    private final RiseFont f20 = RiseFont.main(20, RiseFont.Weight.REGULAR);
    private final RiseFont f17 = RiseFont.main(17, RiseFont.Weight.REGULAR);
    private final RiseFont f18 = RiseFont.main(18, RiseFont.Weight.REGULAR);
    private final RiseFont f16 = RiseFont.main(16, RiseFont.Weight.REGULAR);
    private final RiseFont f15 = RiseFont.main(15, RiseFont.Weight.REGULAR);
    private final RiseFont f13 = RiseFont.main(13, RiseFont.Weight.REGULAR);
    /** Rise 品牌基线用 42pt 字高（SidebarCategory 原式，与 32pt 名字字高不同） */
    private final RiseFont f42 = RiseFont.main(42, RiseFont.Weight.REGULAR);
    /** Rise 图标字体（拷贝自 Rise 资源 assets/minecraft/rise/font/Icon-*.ttf） */
    private final RiseFont icons1 = RiseFont.get("Icon-1.ttf", 17);
    /** ⚠ Rise FontManager：ICONS_1 = Icon-1.ttf，ICONS_2 = **Icon-3.ttf**（不是 Icon-2）——
     *  之前加载 Icon-2 → 搜索/主题/语言/脚本 四个图标全空白（离线渲染验证：Icon-2 只有个别字形） */
    private final RiseFont icons2 = RiseFont.get("Icon-3.ttf", 17);
    /** 页面图标字形（Rise Category 枚举原值；SEARCH/THEME/LANGUAGE/SCRIPT 用 ICONS_2） */
    private static final String[] CAT_GLYPHS = {"U", "a", "b", "c", "g", "f", "m", "U", "U"};
    private static final boolean[] CAT_ICON2 = {true, false, false, false, false, false, true, true, true};

    private final Paint paint = new Paint();
    private final Paint textPaint = new Paint();

    // ─────────────── 状态 ───────────────
    private MinecraftSurfaceBackend surf;
    private int surfW = -1, surfH = -1;

    private float wx, wy;
    private boolean posSet;
    private boolean dragging;
    private float dragDX, dragDY;

    /** 设计单位 → 屏幕单位 的等比缩放（保证 400×300 窗口 + 边距始终放得下） */
    private float uiScale = 1f;
    /** 诊断转储开关（-Dadapt.riseGuiDump=true 或 system property 关闭时静默） */
    private static final boolean GUI_DUMP = Boolean.getBoolean("adapt.riseGuiDump");
    private int guiFrame;
    private int guiDumpCount;

    private final long openNs = System.nanoTime();
    private long closeStartNs = 0L;
    private boolean closing;
    private boolean pendingClose;
    private long lastFrameNs;

    /** 页面：0 = 搜索，1..n = 分类（顺序对齐 Rise：战斗/移动/玩家/渲染/设置） */
    private int page;
    private int prevPage;
    private long pageSwitchNs;
    private String searchText = "";

    private float scroll, scrollTarget;
    private float scrollMin;
    /** 9 个页面各自记住 (scroll, target, min) —— Rise 每个 Screen 一个 ScrollUtil */
    private final float[][] pageScroll = new float[9][3];
    /** 侧栏不透明度（Rise axT：每 ms +2 / −1.5） */
    private float sidebarAlpha = 255f;
    /** 侧栏滑出/收回（Rise SidebarCategory.animation：0 .. −66.67） */
    private final Anim sidebarAnim = new Anim(2000f, Ease.EASE_OUT_EXPO);
    /** 侧栏是否展开（Rise ayo：注意语义是"显示"） */
    private boolean sidebarShown = true;
    /** 鼠标连续离开窗口的时长（滞回，防止单帧误判导致侧栏抖动） */
    private float outsideMs;   // Rise axT：非主题页恒 255

    private final List<Category> cats = orderCategories();

    private static final class RowUi {
        final Anim hover = new Anim(50f, Ease.LINEAR);
        final Anim press = new Anim(50f, Ease.LINEAR);
        final Anim expand = new Anim(450f, Ease.EASE_OUT_EXPO);
        /** 展开内容透明度（Rise ayf：LINEAR，时长 = 展开时长/2、收起 /3，目标 255/0） */
        final Anim content = new Anim(150f, Ease.LINEAR);
        float expandValue;
    }

    private final Map<Module, RowUi> rowUi = new HashMap<>();
    private final Map<Module, Boolean> expanded = new HashMap<>();
    private final Map<Setting<?>, Float> boolAnim = new HashMap<>();
    private final Map<Setting<?>, Float> trackAnim = new HashMap<>();
    /** 分类选中动画（Rise CategoryComponent：LINEAR 200ms，0..255） */
    private final float[] catSelAnim = new float[16];
    /** 取色器 SV 光标位置（Rise azc：相对面板左上角的像素偏移） */
    private final Map<ColorSetting, float[]> colorCursor = new HashMap<>();
    /** Rise 取色器色相位置（ayZ：0..88px） */
    private final Map<ColorSetting, Float> colorHue = new HashMap<>();
    /** 搜索框 CENTER 对齐的平滑位置（Rise TextBox.posX） */
    private float searchPosX = Float.NaN;
    /** 首次输入清空搜索框（Rise typedWhileOpen） */
    private boolean searchTyped;
    /** 数值/字符串编辑框的光标动画基准 */
    private long editStartNs = System.nanoTime();

    /** 拖动会话（Rise: Number/Bounds 手柄拖动） */
    private Setting<?> dragSetting;
    private float dragTrackX;
    private boolean dragMax;         // Bounds 的第二个手柄
    private boolean numericEdit;
    private final StringBuilder editBuf = new StringBuilder();

    /** 取色器 */
    private Setting<?> openColor;
    private ColorSetting editingColor;
    private boolean colorDragSV, colorDragHue;

    /** 本帧布局缓存（点击命中） */
    private final List<Object[]> rowHits = new ArrayList<>();      // {Module,x,y,w,h}
    private final List<Object[]> valueHits = new ArrayList<>();    // {Setting,x,y,w,h}
    private final List<Object[]> catHits = new ArrayList<>();      // {pageIndex,x,y,w,h}
    private final List<Object[]> copyHits = new ArrayList<>();     // {ColorSetting, x, y, w, h, kind}
    private final List<Object[]> themeHits = new ArrayList<>();    // {RiseTheme|x,y,w,h} / 选中标记用 5 元素
    private final List<Object[]> langHits = new ArrayList<>();     // {RiseLang.Lang,x,y,w,h}
    private final List<Object[]> scriptHits = new ArrayList<>();   // {RiseConfigs.Card,x,y,w,h,index}
    /** 主题页当前 keycolor 过滤（null = 全部） */
    private RiseTheme.KeyColor themeFilter;
    /** 主题卡片 x/y 滑入动画（Rise ThemeComponent.getXAnimation/getYAnimation） */
    private final Map<RiseTheme, float[]> themeCardAnim = new HashMap<>();
    private float dtTheme = 16f;

    /** bloom 离屏（可选，Shaders 开启时） */
    private Surface bloomSurface;
    private int bloomW = -1, bloomH = -1;
    private final Paint bloomPaint = new Paint();

    public RiseClickGuiScreen() {
        super(Component.literal("ClickGUI"));
    }

    private static List<Category> orderCategories() {
        // Rise 侧栏顺序：COMBAT, MOVEMENT, PLAYER, RENDER, …（我们只有 5 类）
        List<Category> list = new ArrayList<>();
        list.add(Category.COMBAT);
        list.add(Category.MOVEMENT);
        list.add(Category.PLAYER);
        list.add(Category.RENDER);
        list.add(Category.SETTINGS);
        return list;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void removed() {
        super.removed();
        if (surf != null) { try { surf.dispose(); } catch (Throwable ignored) { } surf = null; surfW = surfH = -1; }
        if (bloomSurface != null) { try { bloomSurface.close(); } catch (Throwable ignored) { } bloomSurface = null; }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
        // 无背景：世界透出（Rise 同款）
    }

    // ───────────────────────── 渲染入口 ─────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Minecraft mc = Minecraft.getInstance();
        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();
        // 等比缩放：设计 400×300 在窗口里放不下时缩小（Rise 原版会溢出屏幕 → 表现为"重叠/被裁"）
        uiScale = Math.min(1f, Math.min((guiW - 24f) / W, (guiH - 24f) / H));
        if (uiScale < 0.35f) uiScale = 0.35f;
        float designW = guiW / uiScale, designH = guiH / uiScale;
        if (!posSet) {
            for (float[] sc : pageScroll) sc[2] = -1f;   // min 未计算标记
            // Rise initGui：首次居中（初值 416×338 只用于居中），越界时重新居中
            wx = Math.round((designW - 416f) / 2f);
            wy = Math.round((designH - 338f) / 2f);
            posSet = true;
        }
        wx = clamp(wx, 0f, Math.max(0f, designW - W));
        wy = clamp(wy, 0f, Math.max(0f, designH - H));

        long now = System.nanoTime();
        float dtMs = lastFrameNs == 0 ? 16f : Math.min(100f, (now - lastFrameNs) / 1_000_000f);
        lastFrameNs = now;

        try {
            SkijaNativeFix.preload();
            float scale = mc.getWindow().getGuiScale();
            if (surf == null || surfW != guiW || surfH != guiH) {
                if (surf != null) { try { surf.dispose(); } catch (Exception ignored) { } }
                surf = new MinecraftSurfaceBackend("rise_clickgui", guiW, guiH, scale);
                surfW = guiW;
                surfH = guiH;
            } else {
                surf.ensureDeviceScale(scale);
            }
            Canvas c = surf.acquireCanvas();
            if (c == null) return;
            draw(c, mx, my, now, dtMs, guiW, guiH);
            surf.present();

            int tw = surf.getDisplayWidth(), th = surf.getDisplayHeight();
            g.blit(surf.blitPipeline(), surf.getTextureId(), 0, 0, 0f, 0f, guiW, guiH, tw, th, tw, th);
            autoDump(guiW, guiH);
            if (pendingClose) {
                pendingClose = false;
                closing = false;
                this.onClose();
            }
            // 诊断：打开界面后按帧转储（最多 12 张，间隔 20 帧）→ ~/.adapt-rise-gui/
            // 用于离线比对本界面每一帧的布局（不需要第二次进游戏就能核对）
            if (GUI_DUMP && guiDumpCount < 12 && (guiFrame++ % 20) == 0) {
                dumpFrame();
            }
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[RiseClickGUI] render failed: {}", t.toString());
        }
    }

    /** 打开/关闭动画（Rise：开 300ms EASE_OUT_EXPO；关 100ms LINEAR，scale→0 时关屏）。 */
    private float windowAnim(long nowNs) {
        if (!closing) {
            float t = Math.min(1f, (nowNs - openNs) / 300_000_000f);
            return Math.max(0.01f, Ease.EASE_OUT_EXPO.apply(t));
        }
        float t = Math.min(1f, (nowNs - closeStartNs) / 100_000_000f);
        float v = 1f - t;
        if (t >= 1f) pendingClose = true;   // 帧末再关：不能在绘制中途 dispose 表面
        return Math.max(0.01f, v);
    }

    private void draw(Canvas c, float mx, float my, long nowNs, float dtMs, int guiW, int guiH) {
        rowHits.clear();
        valueHits.clear();
        catHits.clear();
        copyHits.clear();
        themeHits.clear();
        langHits.clear();
        scriptHits.clear();
        try {
            var im = com.ylmac.adapt.features.module.impl.render.InterfaceModule.get();
            shadersOn = im != null && im.shadersEnabled();
        } catch (Throwable ignored) {
            shadersOn = false;
        }

        // 主通道（收集命中）
        collecting = true;
        drawWindowContent(c, mx, my, nowNs, dtMs, guiW, guiH, false);

        // Bloom 通道（Rise：把主通道全部绘制再跑一遍 → 模糊 → 叠加）
        if (shadersOn) {
            collecting = false;
            drawBloom(c, mx, my, nowNs, dtMs, guiW, guiH);
            collecting = true;
        }
    }

    /**
     * 窗口内容绘制（主通道 / bloom 通道共用）。
     *
     * @param plainAlpha true = 不套开合淡入（bloom 通道用）
     */
    private void drawWindowContent(Canvas c, float mx, float my, long nowNs, float dtMs,
                                   int guiW, int guiH, boolean plainAlpha) {
        dtTheme = dtMs;
        float anim = windowAnim(nowNs);
        float opacity = plainAlpha ? 1f
                : (closing ? 1f : Math.min(1f, (nowNs - openNs) / 300_000_000f));
        if (opacity < 0.01f) opacity = 0.01f;
        // Rise SidebarCategory.renderSidebar：axT 只在 qa() 页（ThemeScreen）随自动隐藏变化；
        // 其它页面每帧都 +elapsed*2 直到 255 → 恒为满不透明。打开界面第一帧就是 255，
        // 不会"栏目先消失再飞回来"。
        sidebarAlpha = 255f;

        float cx = wx + W / 2f, cy = wy + H / 2f;
        int layer = c.saveLayer(null, new Paint().setAlphaf(opacity));
        c.save();
        c.scale(uiScale, uiScale);            // 设计单位 → 屏幕单位（输入侧 dX/dY 反向换算）
        c.translate(cx, cy);
        c.scale(anim, anim);
        c.translate(-cx, -cy);

        // 1) 窗口投影（仅 axS > 0.993；Rise 原式 alpha 1..5 × 19 层）
        if (anim > 0.993f) {
            dropShadow(c, wx, wy, W, H, SHADOW_EXPAND, SHADOW_CORNER);
        }

        // 2) 窗口底
        paint.setColor(C_BACKGROUND);
        c.drawRRect(RRect.makeXYWH(wx, wy, W, H, ROUND), paint);

        // 3) 页面内容（裁到窗口）—— Rise oT 顺序：页面 → 转场遮罩 → 9 圆 → 侧栏
        int s1 = c.save();
        try {
            c.clipRRect(RRect.makeXYWH(wx + 1f, wy + 1f, W - 2f, H - 2f, ROUND),
                    ClipMode.INTERSECT, true);
            drawModuleArea(c, mx, my, nowNs, dtMs, guiW);
            drawScrollbar(c);
            drawColorPicker(c);
            // 4) 切页遮罩（BACKGROUND alpha k）：**盖在页面上、但盖不到侧栏** ——
        //    Rise 把遮罩画在 sidebar.pF() 之前，所以换栏目时只有内容在闪，侧栏不动
        drawPageOverlay(c, nowNs);
        } finally {
            c.restoreToCount(s1);
        }

        // 5) 9 个同心圆（侧栏右缘居中，直径 0/50/…/400，主题主色 alpha 1）
        for (int l = 0; l <= 8; l++) {
            paint.setColor(withAlpha(themePrimary() & 0xFFFFFF, 1));
            c.drawCircle(wx + SIDEBAR_W, wy + H / 2f, l * 50f / 2f, paint);
        }

        // 6) 侧栏 + 品牌 + 分类（含 Rise 的滑出/收回动画；永远画在遮罩之上）
        drawSidebar(c, mx, my, dtMs);

        c.restore();
        c.restoreToCount(layer);
    }

    /**
     * Rise BLOOM 通道（Interface.Shaders 开启时）：
     * Rise 把主通道的**全部绘制命令**再入队一次（BLOOM 队列），
     * 经 bloom.frag（radius 14 / compression 2）模糊后按 ONE / ONE_MINUS_SRC_ALPHA 叠加。
     * 这里等价实现：整屏内容重绘到半分辨率离屏 → 高斯 σ=7 → PLUS 合成。
     * （默认关，与 Rise Interface.Shaders=false 一致。）
     */
    private void drawBloom(Canvas c, float mx, float my, long nowNs, float dtMs, int guiW, int guiH) {
        var ctx = GpuContextHelper.getOrCreateContext();
        if (ctx == null) return;
        int bw = Math.max(8, guiW / 2), bh = Math.max(8, guiH / 2);
        if (bloomSurface == null || bloomW != bw || bloomH != bh) {
            if (bloomSurface != null) { try { bloomSurface.close(); } catch (Throwable ignored) { } }
            bloomSurface = Surface.makeRenderTarget(ctx, false, ImageInfo.makeN32Premul(bw, bh));
            bloomW = bw;
            bloomH = bh;
        }
        if (bloomSurface == null) return;
        Canvas bc = bloomSurface.getCanvas();
        bc.clear(0x00000000);
        bc.save();
        bc.scale(0.5f, 0.5f);                       // compression 2
        drawWindowContent(bc, mx, my, nowNs, dtMs, guiW, guiH, true);
        bc.restore();

        Image bloom = bloomSurface.makeImageSnapshot();
        if (bloom == null) return;
        bloomPaint.setBlendMode(BlendMode.PLUS);
        ImageFilter blur = ImageFilter.makeBlur(7f, 7f, FilterTileMode.CLAMP);
        bloomPaint.setImageFilter(blur);
        c.drawImageRect(bloom, Rect.makeWH(bw, bh), Rect.makeXYWH(0f, 0f, guiW, guiH),
                SamplingMode.LINEAR, bloomPaint, false);
        bloomPaint.setImageFilter(null);
        bloomPaint.setBlendMode(BlendMode.SRC_OVER);
        blur.close();
        bloom.close();
    }

    /**
     * Rise RenderUtil.dropShadow 原式：
     * <pre>
     *   for (f = 0; f <= iters/2; f += 0.5)
     *       roundedRectangle(x − f/2, y − f/2, w + f, h + f, corner,
     *                        Color(0,0,0, (int)max(0.5, (spread − f·1.2) / 5.5)));
     * </pre>
     * 每层 alpha 只有 1..5，叠 19 层才形成很柔的暗晕 —— 之前按"层 alpha 放大到 140"画，
     * 所以影子又黑又硬（用户："影子好奇怪"）。
     */
    private void dropShadow(Canvas c, float x, float y, float w, float h, float spread, float corner) {
        for (float f = 0f; f <= SHADOW_ITERS / 2f; f += 0.5f) {
            int a = (int) Math.max(0.5, (spread - f * 1.2) / 5.5);
            paint.setColor(withAlpha(0x000000, a));
            c.drawRRect(RRect.makeXYWH(x - f / 2f, y - f / 2f, w + f, h + f, corner), paint);
        }
    }

    private void drawSidebar(Canvas c, float mx, float my, float dtMs) {
        // ── Rise SidebarCategory 原式：动画目标 0 / −aym/1.5 = −66.67，
        //    时长：显示 2000ms、隐藏 700ms（EASE_OUT_EXPO）；侧栏宽度 = 100 + anim ──
        // ⚠ Rise 原版在 Theme 页鼠标移出左侧区域就收起侧栏（动画 −66.67px）→ 栏目被拖到窗口外，
        //   用户实测"滑动时栏目全被拖到界面外"。这里改成**只有鼠标离开整个窗口**才收起。
        // ⚠ 单帧误判会让 EASE_OUT_EXPO 的滑出立刻跑掉 ~10px 再回来（"抖一下像收起又回来"）
        //   → 用「鼠标连续离开窗口 200ms」才收起（滞回），并留 60px 余量
        Minecraft mc0 = Minecraft.getInstance();
        boolean inWindow = mx > -60f && mx < mc0.getWindow().getGuiScaledWidth() / uiScale + 60f
                && my > -60f && my < mc0.getWindow().getGuiScaledHeight() / uiScale + 60f;
        if (inWindow) {
            outsideMs = 0f;
            sidebarShown = true;
        } else {
            outsideMs += dtMs;
            if (outsideMs > 200f) sidebarShown = false;
        }
        sidebarAnim.setDuration(sidebarShown ? 2000f : 700f);
        sidebarAnim.setTarget(sidebarShown ? 0f : -SIDEBAR_W / 1.5f);
        if (collecting) sidebarAnim.update(dtMs);
        float sbAnim = sidebarAnim.value();
        float sbW = SIDEBAR_W + sbAnim;
        // Rise axT：显示时每 ms +2、收起时每 ms −1.5（收起时整条侧栏同时淡出）
        sidebarAlpha = clamp(sidebarAlpha + (sidebarShown ? 2f : -1.5f) * dtMs, 0f, 255f);
        int sa = (int) sidebarAlpha;

        // ⚠ 整个侧栏（面板 + 渐隐 + 品牌 + 栏目）必须都在**窗口裁剪**内：
        //   Rise 是整窗 scissor；我原来只裁了面板，品牌/栏目文字画在裁剪外 →
        //   侧栏一收起，栏目文字就画到窗口外面去了（用户实测："文字显示在界面外"）
        int s = c.save();
        try {
            c.clipRRect(RRect.makeXYWH(wx, wy, W, H, ROUND), ClipMode.INTERSECT, true);

            // 1) 面板（左圆角由窗口圆角裁出）
            paint.setColor(withAlpha(C_SECONDARY & 0xFFFFFF, sa));
            c.drawRect(Rect.makeXYWH(wx, wy, sbW, H), paint);
            // 2) 右缘 30px 黑色渐隐（Rise: 黑 alpha 0 → ≈36 → 透明）
            float fx = wx + sbW;
            paint.setShader(Shader.makeLinearGradient(fx, 0f, fx + 30f, 0f,
                    new int[]{withAlpha(0x000000, (int) (36 * sa / 255f)), 0x00000000},
                    new float[]{0f, 1f}));
            c.drawRect(Rect.makeXYWH(fx, wy, 30f, H), paint);
            paint.setShader(null);

            // 3) 品牌（Rise SidebarCategory.renderSidebar 原式）
            //    f = axI.x + 9 + anim；f1 = axI.y + 24.75 − height(42pt)/2
            int ta = sa;
            float f = wx + 9f + sbAnim;
            float f1 = wy + 24.75f - f42.height() / 2f;
            f32.drawText(c, "Rise", f + 5f, f1 + 2f, withAlpha(0xFFFFFF, ta), textPaint);
            float w32 = f32.getStringWidth("Rise");
            f16.drawText(c, "6.9.5", f + 5f + w32, f1,
                    withAlpha(themePrimary() & 0xFFFFFF, Math.min(ta, 200)), textPaint);

            // 4) 分类（0 = 搜索 + 各分类 + 脚本/主题/语言）
            String[] labels = new String[cats.size() + 4];
            labels[0] = RiseLang.tr("ui.search");
            for (int i = 0; i < cats.size(); i++) labels[i + 1] = cats.get(i).getDisplayName();
            labels[cats.size() + 1] = RiseLang.tr("ui.script");
            labels[cats.size() + 2] = RiseLang.tr("ui.theme");
            labels[cats.size() + 3] = RiseLang.tr("ui.language");

            for (int i = 0; i < labels.length; i++) {
                float catY = wy + CAT_Y0 + CAT_STEP * i;
                float catX = wx + CAT_X + sbAnim;
                boolean selected = page == i;
                RiseFont iconFont = CAT_ICON2[i] ? icons2 : icons1;
                float iconW = iconFont.getStringWidth(CAT_GLYPHS[i]);
                float textW = f16.getStringWidth(labels[i]) + 8f + iconW;
                float pillW = textW + 8f;
                float pillY = catY - 5.5f;

                float sel = clamp(catSelAnim[i] + (selected ? 1.275f : -1.275f) * dtMs, 0f, 1f);
                catSelAnim[i] = sel;
                if (sel > 0.01f) {
                    paint.setColor(withAlpha(darker(accent(catY / 5f)) & 0xFFFFFF,
                            (int) Math.min(255f * sel, sidebarAlpha)));
                    c.drawRRect(RRect.makeXYWH(catX, pillY, pillW, 15f, 5f), paint);
                }
                int j = withAlpha(0xFFFFFF, (int) Math.min(selected ? 255f : 200f, sidebarAlpha));
                float slide = sel * 255f / 80f;
                iconFont.drawText(c, CAT_GLYPHS[i], catX + slide + 3f, catY, j, textPaint);
                f16.drawText(c, labels[i], catX + slide + 3f + 4f + iconW, catY, j, textPaint);
            }
        } finally {
            c.restoreToCount(s);
        }

        // 命中框在裁剪外也算（Rise 的 scissor 只影响绘制）——但收起时不该可点
        if (sidebarAlpha > 40f) {
            for (int i = 0; i < cats.size() + 4; i++) {
                float catY = wy + CAT_Y0 + CAT_STEP * i;
                float catX = wx + CAT_X + sbAnim;
                addHit(catHits, new Object[]{i, catX - 11f, catY - 5f, 70f, 22f});
            }
        }
    }

    // ───────────────────────── 模块列表 ─────────────────────────

    private List<Module> visibleModules() {
        String q = searchText == null ? "" : searchText.toLowerCase().replace(" ", "");
        List<Module> out = new ArrayList<>();
        for (Module m : ModuleManager.INSTANCE.getModules()) {
            if (page == 0) {
                if (q.isEmpty()) {
                    out.add(m);
                } else {
                    String name = m.getName() == null ? "" : m.getName().toLowerCase().replace(" ", "");
                    String id = m.getModuleId() == null ? "" : m.getModuleId().toLowerCase();
                    if (name.contains(q) || id.contains(q)) out.add(m);
                }
            } else if (page - 1 < cats.size() && m.getCategory() == cats.get(page - 1)) {
                out.add(m);
            }
        }
        out.sort(Comparator.comparing(m -> m.getName() == null ? "" : m.getName()));
        return out;
    }

    private static final int PAGE_SCRIPT = 6, PAGE_THEME = 7, PAGE_LANG = 8;

    private void drawModuleArea(Canvas c, float mx, float my, long nowNs, float dtMs, int guiW) {
        // Rise oT：axL = (切换后 < 200ms) ? 旧页 : 新页 —— 前 200ms 仍渲染**旧页**，
        // 黑幕在 t=200ms 把窗口盖到最暗，之后才切换成新页（"按下栏目会有特殊的动画"）
        int keepPage = page;
        boolean transition = pageSwitchNs != 0L && (nowNs - pageSwitchNs) / 1_000_000f < 200f
                && prevPage != page;
        if (transition) {
            page = prevPage;
            collecting = false;
        }
        try {
            drawPage(c, mx, my, nowNs, dtMs, guiW);
        } finally {
            page = keepPage;
            collecting = true;
        }
    }

    private void drawPage(Canvas c, float mx, float my, long nowNs, float dtMs, int guiW) {
        if (page == PAGE_THEME) { drawThemePage(c, mx, my); return; }
        if (page == PAGE_LANG) { drawLangPage(c, mx, my); return; }
        if (page == PAGE_SCRIPT) { drawScriptPage(c, mx, my); return; }
        List<Module> mods = visibleModules();
        float x0 = wx + SIDEBAR_W + 8f;
        boolean search = page == 0;
        float topExtra = search ? 28f : 0f;

        // 内容高度（Rise: scale.y + 7 步进，用动画行高）
        float contentH = 0f;
        for (Module m : mods) {
            float h = rowHeight(m);
            RowUi ui = rowUi.get(m);
            contentH += (ui == null ? h : ROW_H + (h - ROW_H) * ui.expand.value()) + ROW_GAP;
        }
        // Rise ScrollUtil.V：CategoryScreen → −contentH + H − 7；SearchScreen → −contentH + H − 37
        scrollMin = Math.min(0f, -contentH + H - (search ? 37f : 7f));

        if (search) drawSearchBox(c, mx, my, guiW);

        updateScroll(dtMs);        // 所有页面共用（滚轮/橡皮筋/1%·ms 平滑）
        float y = wy + (search ? 35f : 7f) + scroll;
        for (Module m : mods) {
            float h = rowHeight(m);
            if (y + h >= wy && y + ROW_GAP <= wy + H) {
                drawRow(c, m, x0, y, h, mx, my, dtMs, guiW);
            }
            // 步进 = 动画行高 + 7（Rise CategoryScreen：d0 += moduleComponent.scale.y + 7）
            RowUi ui = rowUi.get(m);
            float animated = ui == null ? h : ROW_H + (h - ROW_H) * ui.expand.value();
            y += animated + ROW_GAP;
        }
    }

    /**
     * Rise ModuleComponent：f1 = 38；每个可见值只累加自身 height（**无额外间隙**）；
     * 循环结束 f1-- → 展开高 = 38 + Σh − 1。
     */
    private float rowHeight(Module m) {
        if (!Boolean.TRUE.equals(expanded.get(m))) return ROW_H;
        float h = 0f;
        for (Setting<?> s : m.getSettings()) {
            if (!visible(s)) continue;
            h += valueHeight(s);
        }
        return h <= 0f ? ROW_H : ROW_H + h - 1f;
    }

    private static boolean visible(Setting<?> s) {
        return s.getDependency() == null || s.getDependency().check();
    }

    private float valueHeight(Setting<?> s) {
        if (s instanceof StringSetting) return 28f;
        if (s instanceof ColorSetting) return openColor == s ? 110f : 15f;
        return 14f;
    }

    private void drawRow(Canvas c, Module m, float x, float y, float h, float mx, float my,
                         float dtMs, int guiW) {
        RowUi ui = rowUi.computeIfAbsent(m, k -> new RowUi());
        // Rise ModuleComponent.click：命中框高 = moduleDefaultScale.y − 3 = 35（不是 38）
        float topH = Math.min(h, ROW_H - 3f);
        boolean hovered = mx >= x && mx <= x + ROW_W && my >= y && my <= y + topH;

        // 展开动画（Rise：EASE_OUT_EXPO，时长 min(3ms×目标高, 450ms)）
        float targetH = rowHeight(m);
        float targetVal = Boolean.TRUE.equals(expanded.get(m)) ? 1f : 0f;
        float dur = Math.min(450f, Math.max(60f, 3f * Math.max(1f, targetH - ROW_H)));
        // ⚠ bloom 通道会二次调用本方法：只在主通道推进动画（否则 Shaders 开时动画/滚动跑两倍速）
        ui.expand.setDuration(dur);
        if (collecting) {
            ui.expand.setTarget(targetVal);
            ui.expand.update(dtMs);
            // Rise ayf：内容透明度独立动画（展开 duration/2、收起 duration/3，LINEAR）
            ui.content.setDuration(Boolean.TRUE.equals(expanded.get(m)) ? Math.max(1f, dur / 2f)
                    : Math.max(1f, dur / 3f));
            ui.content.setTarget(Boolean.TRUE.equals(expanded.get(m)) ? 255f : 0f);
            ui.content.update(dtMs);
        }
        float e = ui.expand.value();

        // 悬停 / 按住叠加（LINEAR 50ms：0→20 / 按住 35）
        ui.hover.setTarget(hovered ? 20f : 0f);
        ui.press.setTarget(hovered && leftDown ? 35f : 0f);
        if (collecting) {
            ui.hover.update(dtMs);
            ui.press.update(dtMs);
        }

        // ⚠ Rise 的行高是**动画值**（this.scale.y = aye.getValue()），背景/悬停层都按它画，
        //   后续行的步进也用 scale.y + 7 → 展开时下面的行被平滑推下去，而不是瞬间跳。
        float bgH = ROW_H + (h - ROW_H) * e;
        paint.setColor(C_OVERLAY);
        c.drawRRect(RRect.makeXYWH(x, y, ROW_W, bgH, ROW_R), paint);
        float ov = Math.max(ui.hover.value(), ui.press.value());
        if (ov > 0.5f) {
            // Rise：悬停叠加纯黑 alpha 20 / 按住 35（LINEAR 50ms）
            paint.setColor(withAlpha(0x000000, (int) ov));
            c.drawRRect(RRect.makeXYWH(x, y, ROW_W, bgH, ROW_R), paint);
        }

        // 名字（启用 = accent(0, y/5)，禁用 = 白 200/255）
        boolean on = m.isEnabled();
        int nameColor = on ? accent(y / 5f) : withAlpha(0xFFFFFF, 0xDC);
        f20.drawText(c, m.getName(), x + 6f, y + 8f, nameColor, textPaint);
        // 描述
        String desc = ModuleDescriptions.get(m.getName(), m.getCategory().getDisplayName());
        if (desc != null && !desc.isEmpty()) {
            f15.drawText(c, desc, x + 6f, y + 25f, 0x46FFFFFF, textPaint);
        }
        // 搜索页：分类标签
        if (page == 0) {
            float nw = f20.getStringWidth(m.getName());
            f15.drawText(c, "(" + m.getCategory().getDisplayName() + ")", x + nw + 10f, y + 10f, 0x40FFFFFF, textPaint);
        }

        addHit(rowHits, new Object[]{m, x, y, ROW_W, topH});

        // 展开的设置项：裁到行动画高度；每个值行 alpha = 内容动画值，
        // 仅当自己的 y < 行顶 + 动画行高 + 15 时才显示（Rise ModuleComponent 的 U(...) 规则）
        if (e > 0.01f || ui.content.value() > 0.5f) {
            // ⚠ Rise **不裁剪**值行——它靠下面的 alpha 规则（值 y < 行顶 + 动画行高 + 15 才显示）。
            //   之前用 clipRect 硬裁：展开过程中文字被切一半 → "展开界面排版错误"。
            {
                float vy = y + ROW_H + 1f;           // Rise：首个值在 position.y + 38 + 1
                for (Setting<?> st : m.getSettings()) {
                    if (!visible(st)) continue;
                    float vh = valueHeight(st);
                    int alpha = vy < y + bgH + 15f ? (int) ui.content.value() : 0;
                    drawValue(c, st, x + 6f + (st.getDependency() != null ? 10f : 0f), vy, mx, my, alpha, guiW);
                    addHit(valueHits, new Object[]{st, x + 6f + (st.getDependency() != null ? 10f : 0f), vy, ROW_W - 12f, vh});
                    vy += vh;                        // 无额外间隙
                }
            }
        }
    }

    /** Rise SearchScreen：文本框 @(axI.x+100+(400−100)/2, axI.y+17+scroll)，20pt CENTER，宽 150。 */
    private void drawSearchBox(Canvas c, float mx, float my, int guiW) {
        float bx = wx + SIDEBAR_W + (W - SIDEBAR_W) / 2f;
        float by = wy + 17f + scroll;
        // 滚动时文字淡出（Rise：目标滚动为负 → 每 ms −4，否则 +4）
        searchTextAlpha += (scrollTarget < 0f ? -4f : 4f) * 16f;
        searchTextAlpha = clamp(searchTextAlpha, 0f, 255f);
        int col = withAlpha(0xFFFFFF, (int) searchTextAlpha);
        drawTextBox(c, f20, bx, by, 150f, col, true, true, searchText, "Start typing to search...", true);
    }

    /** 搜索框文字透明度（Rise SearchScreen.axT） */
    private float searchTextAlpha = 255f;

    private void drawScrollbar(Canvas c) {
        float trackX = wx + W - 4f;
        float trackY = wy + 7f;
        if (scrollMin >= 0f || scroll == 0f && scrollTarget == 0f && scrollMin == 0f) {
            // 无滚动：Rise 依然按公式画（内容不足时滑块=满高）→ 保留公式
        }
        float contentH = SCROLL_TRACK_H - scrollMin;     // scrollMin ≤ 0
        if (contentH <= SCROLL_TRACK_H) {
            // 内容不超过可视区：不画
            return;
        }
        float barH = Math.max(10f, SCROLL_TRACK_H * (SCROLL_TRACK_H / contentH));
        float barY = trackY + (SCROLL_TRACK_H - barH) * (scroll / scrollMin);
        paint.setColor(0x3CFFFFFF);
        c.drawRRect(RRect.makeXYWH(trackX, Math.max(trackY, Math.min(trackY + SCROLL_TRACK_H - barH, barY)),
                SCROLLBAR_W, barH, 0.5f), paint);
    }

    private static final float SCROLLBAR_W = 1f;

    // ───────────────────────── 设置项（ValueRow） ─────────────────────────

    /**
     * 单个设置项绘制 —— 全部按 Rise 原控件几何（components/value/impl/*）。
     */
    private void drawValue(Canvas c, Setting<?> s, float x, float y, float mx, float my, int alphaMul, int guiW) {
        float lw16 = f16.getStringWidth(s.getName());

        if (s instanceof BoolSetting bs) {
            // Rise BooleanValueComponent：标签 16pt；d0 = x + width16 + 3；
            //   底孔 5×5 r2.5 BACKGROUND @(d0+2.5, y+2.5) 中心；
            //   accent 圆点 axp×axp r=axp/2（axp: 0→5，elapsedMs/20）
            int label = mulAlpha(C_SECONDARY_TEXT, alphaMul);
            f16.drawText(c, s.getName(), x, y, label, textPaint);
            float d0 = x + lw16 + 3f;
            float target = Boolean.TRUE.equals(bs.getValue()) ? 5f : 0f;
            float cur = boolAnim.getOrDefault(s, target);
            cur += Math.signum(target - cur) * Math.min(Math.abs(target - cur), 0.35f);
            boolAnim.put(s, cur);
            paint.setColor(mulAlpha(C_BACKGROUND, alphaMul));
            c.drawRRect(RRect.makeXYWH(d0 + 2.5f, y + 2.5f, 5f, 5f, 2.5f), paint);
            if (cur > 0.05f) {
                paint.setColor(mulAlpha(accent(y / 5f), alphaMul));
                c.drawRRect(RRect.makeXYWH(d0 + 5f - cur / 2f, y + 2.5f - cur / 2f, cur, cur, cur / 2f), paint);
            }
            return;
        }
        if (s instanceof IntSetting is) {
            drawNumber(c, s, x, y, alphaMul, is.getValue(), is.getMin(), is.getMax(), true);
            return;
        }
        if (s instanceof DoubleSetting ds) {
            drawNumber(c, s, x, y, alphaMul, ds.getValue(), ds.getMin(), ds.getMax(), false);
            return;
        }
        if (s instanceof ChoiceSetting cs) {
            drawMode(c, s, x, y, String.valueOf(cs.getValue()), alphaMul);
            return;
        }
        if (s instanceof EnumSetting<?> es) {
            drawMode(c, s, x, y, String.valueOf(es.getValue()), alphaMul);
            return;
        }
        if (s instanceof StringSetting ss) {
            // Rise StringValueComponent：标签 16pt @(x,y)；文本框 @(x, y+14) 宽 230.5，白字 16pt（无底色）
            f16.drawText(c, s.getName(), x, y, mulAlpha(C_SECONDARY_TEXT, alphaMul), textPaint);
            boolean editing = numericEdit && dragSetting == ss;
            drawTextBox(c, f16, x, y + 14f, 230.5f, mulAlpha(0xFFFFFF, alphaMul), false, editing,
                    String.valueOf(ss.getValue() == null ? "" : ss.getValue()), "", false);
            return;
        }
        if (s instanceof ColorSetting cs) {
            // Rise ColorValueComponent：标签 16pt；f = width16+4；色块 (x+f, y, 15, 7, r2.5)
            f16.drawText(c, s.getName(), x, y, mulAlpha(C_SECONDARY_TEXT, alphaMul == 255 ? 255 : alphaMul), textPaint);
            float f = lw16 + 4f;
            Color col = cs.getValue();
            int argb = (col.getAlpha() << 24) | (col.getRed() << 16) | (col.getGreen() << 8) | col.getBlue();
            paint.setColor(argb);
            c.drawRRect(RRect.makeXYWH(x + f, y, 15f, 7f, 2.5f), paint);
            return;
        }
        if (s instanceof ButtonSetting) {
            f16.drawText(c, s.getName(), x, y, mulAlpha(accent(y / 5f), alphaMul), textPaint);
            return;
        }
        if (s instanceof com.ylmac.adapt.features.module.settings.impl.KeybindSetting ks) {
            // 按键：按 Mode 行画（标签 + 当前键名）
            drawMode(c, s, x, y, keyName(ks.getValue()), alphaMul);
            return;
        }
        if (s instanceof com.ylmac.adapt.features.module.settings.impl.StringListSetting sl) {
            java.util.List<String> v = sl.getValue();
            String joined = v == null || v.isEmpty() ? "无" : String.join(", ", v);
            if (joined.length() > 28) joined = joined.substring(0, 28) + "...";
            drawMode(c, s, x, y, joined, alphaMul);
            return;
        }
        // 其它类型（注册表列表等）：标签 + 摘要
        Object v = s.getValue();
        String summary = v == null ? "" : String.valueOf(v);
        if (summary.length() > 28) summary = summary.substring(0, 28) + "...";
        drawMode(c, s, x, y, summary, alphaMul);
    }

    /** GLFW 键码 → 显示名（Rise 的 .bind 同款文案） */
    private static String keyName(Integer code) {
        if (code == null || code == 0) return "无";
        return org.lwjgl.glfw.GLFW.glfwGetKeyName(code, 0) != null
                ? org.lwjgl.glfw.GLFW.glfwGetKeyName(code, 0).toUpperCase()
                : ("键 " + code);
    }

    /**
     * Number 控件（Rise NumberValueComponent 原式）：
     * f = width16(名)+7；轨道 rrect(x+f, y+1.5, 100, 2, r1)；
     * 手柄 rrect(trackX + t·100 − 2.5, y, 5, 5, r2.5)；数值文本框 @(x+f+105, y) 宽 20。
     */
    private void drawNumber(Canvas c, Setting<?> s, float x, float y, int alphaMul,
                            double value, double min, double max, boolean isInt) {
        f16.drawText(c, s.getName(), x, y, mulAlpha(C_SECONDARY_TEXT, alphaMul), textPaint);
        float f = f16.getStringWidth(s.getName()) + 7f;
        double frac = max > min ? (value - min) / (max - min) : 0;
        float kx = x + f + (float) (100f * frac);

        // 手柄动画：azl = (azl*29 + 目标)/30（每 ms）
        float cur = trackAnim.getOrDefault(s, kx);
        cur += (kx - cur) / 30f;
        trackAnim.put(s, cur);

        paint.setColor(mulAlpha(C_BACKGROUND, alphaMul));
        c.drawRRect(RRect.makeXYWH(x + f, y + 1.5f, 100f, 2f, 1f), paint);
        paint.setColor(mulAlpha(accent(y / 5f), alphaMul));
        c.drawRRect(RRect.makeXYWH(cur - 2.5f, y, 5f, 5f, 2.5f), paint);

        String vs = isInt ? String.valueOf((int) value) : trimDouble(value);
        boolean editing = numericEdit && dragSetting == s;
        drawTextBox(c, f16, x + f + 105f, y, 20f, mulAlpha(C_SECONDARY_TEXT, alphaMul), false, editing, vs, "", false);
    }

    /** Mode/List 控件（Rise ModeValueComponent）：标签+":"，值 @x+width16(label+":")+2。 */
    private void drawMode(Canvas c, Setting<?> s, float x, float y, String value, int alphaMul) {
        String labelTxt = s.getName() + ":";
        f16.drawText(c, labelTxt, x, y, mulAlpha(C_SECONDARY_TEXT, alphaMul), textPaint);
        f16.drawText(c, value, x + f16.getStringWidth(labelTxt) + 2f, y, mulAlpha(C_SECONDARY_TEXT, alphaMul), textPaint);
    }

    /**
     * Rise TextBox.draw 等价：无底色；空文本画占位符（alpha × selected?0.3:0.2）；
     * CENTER 对齐时位置按 (posX·19+目标)/20 每 ms 平滑；选中时光标 "|" 正弦闪烁。
     */
    private void drawTextBox(Canvas c, RiseFont font, float x, float y, float width, int color,
                             boolean center, boolean selected, String text, String placeholder, boolean isSearch) {
        boolean empty = text == null || text.isEmpty();
        String shown = empty ? placeholder : text;
        String draw = shown == null ? "" : shown;
        while (font.getStringWidth(draw) > width && !draw.isEmpty()) {
            draw = draw.substring(0, draw.length() - 1);
        }
        float w = font.getStringWidth(draw);
        float px;
        if (center) {
            float target = x - w / 2f;
            if (isSearch) {
                if (Float.isNaN(searchPosX)) searchPosX = target;
                searchPosX = (searchPosX * 19f + target) / 20f;
                px = searchPosX;
            } else {
                px = target;
            }
        } else {
            px = x;
        }
        if (empty) {
            int a = (int) ((color >>> 24) * (selected ? 0.3f : 0.2f));
            f16.drawText(c, draw, px, y, (a << 24) | 0xFFFFFF, textPaint);
        } else {
            font.drawText(c, draw, px, y, color, textPaint);
        }
        if (selected) {
            // Rise：光标 = "|"（18pt），alpha = (sin(now/150)+1)/2·255
            float caretAlpha = (float) ((Math.sin(System.currentTimeMillis() / 150.0) + 1.0) / 2.0 * 255.0);
            f16.drawText(c, "|", px + w + 1f, y - 1f, withAlpha(0xFFFFFF, (int) caretAlpha), textPaint);
        }
    }

    // ── 取色器面板（105×105） ──

    /** 取色面板锚点：d0 = x + 0.5 + 10 + f + 8（f = width16(名)+4），d1 = y + 0.5。 */
    private float[] colorPanelRect(ColorSetting cs) {
        for (Object[] hit : valueHits) {
            if (hit[0] == cs) {
                float x = (Float) hit[1], y = (Float) hit[2];
                float f = f16.getStringWidth(cs.getName()) + 4f;
                float d0 = x + 0.5f + 10f + f + 8f;
                float d1 = y + 0.5f;
                float pw = 105f, ph = 105f;
                float maxX = wx + W - 6f, maxY = wy + H - 6f;
                if (d0 + pw > maxX) d0 = maxX - pw;
                if (d1 + ph > maxY) d1 = maxY - ph;
                return new float[]{d0, d1, pw, ph, x, y, f};
            }
        }
        return null;
    }

    /**
     * Rise ColorValueComponent 面板（aza=105, azb=120 → 可见高 105）：
     * 外壳 SECONDARY r(round−3) + 内衬 BACKGROUND；SV = 104×66 r7（白→色相 RGQ + 黑罩）；
     * 色相条 88×7 @(+8.5, +70)，指示块 11×11 r(round/3+1) 描边 1px 黑；
     * 预览色块 15×15.5 r3.5；光标 7/6/5 三层；RGB 17pt @+8.5/+30/+51；hex 13pt；两个 60×10 复制热区。
     */
    private void drawColorPanel(Canvas c, ColorSetting cs, float ignoreX, float ignoreY, int alphaMul) {
        float[] r = colorPanelRect(cs);
        if (r == null) return;
        float d0 = r[0], d1 = r[1], aza = r[2], azb = r[3];
        Color col = cs.getValue();
        float[] hsv = Color.RGBtoHSB(col.getRed(), col.getGreen(), col.getBlue(), null);
        float[] cur = colorCursor.get(cs);
        if (cur == null) {
            cur = new float[]{hsv[1] * (aza - 1f), (1f - hsv[2]) * (azb * 0.55f)};
            colorCursor.put(cs, cur);
        }
        float hueZ = colorHue.getOrDefault(cs, -1f);
        if (hueZ < 0f) {
            hueZ = hsv[0] * (aza - 17f);
            colorHue.put(cs, hueZ);
        }
        Color hueColor = Color.getHSBColor(hueZ / (aza - 17f), 1f, 1f);

        // 外壳 + 内衬（dropShadow 10 层）
        dropShadow(c, d0, d1, aza, azb - 15f, 40f, ROUND * 2f);
        paint.setColor(C_SECONDARY);
        c.drawRRect(RRect.makeXYWH(d0 - 0.5f, d1 - 0.5f, aza, azb - 15f, ROUND - 3f), paint);
        paint.setColor(C_BACKGROUND);
        c.drawRRect(RRect.makeXYWH(d0, d1, aza - 1f, azb - 16f, ROUND - 3f), paint);

        double d2 = azb * 0.55;                        // 66
        // SV 方块：水平 RGQ(白 → 色相) + 纵向 RGQ(透明 → 黑)
        paint.setShader(rgq(d0, d1, (float) (aza - 1), (float) d2, false, 0xFFFFFFFF, 0xFF000000 | (hueColor.getRGB() & 0xFFFFFF)));
        c.drawRRect(RRect.makeXYWH(d0, d1, aza - 1f, (float) d2, 7f), paint);
        paint.setShader(rgqVertical((float) d0, (float) d1, (float) (aza), (float) (d2 + 0.5), 0x00000000, 0xFF000000));
        c.drawRRect(RRect.makeXYWH(d0 - 0.5f, d1, aza, (float) (d2 + 0.5), 7f), paint);
        paint.setShader(null);

        // 色相条 + 指示块
        double d3 = 8.5;
        double d4 = d0 + d3;
        double d5 = d1 + d3 + d2 - 5.0;
        double d6 = aza - d3 * 2.0;                    // 88
        paint.setShader(Shader.makeLinearGradient((float) d4, 0f, (float) (d4 + d6), 0f, HUE_COLORS, HUE_STOPS));
        c.drawRRect(RRect.makeXYWH((float) d4, (float) (d5 + 2.5), (float) d6, ROUND - 5f, 3.5f), paint);
        paint.setShader(null);
        paint.setColor(hueColor.getRGB());
        c.drawRRect(RRect.makeXYWH((float) (d4 + hueZ - ROUND / 2f + 0.5), (float) (d5 + 0.5),
                ROUND - 1f, ROUND - 1f, ROUND / 3f + 1f), paint);
        paint.setMode(PaintMode.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0xFF000000);
        c.drawRRect(RRect.makeXYWH((float) (d4 + hueZ - ROUND / 2f + 0.5), (float) (d5 + 0.5),
                ROUND - 1f, ROUND - 1f, ROUND / 5f + 1f), paint);
        paint.setMode(PaintMode.FILL);
        paint.setStrokeWidth(1f);

        // 当前颜色预览（15×15.5 r3.5）
        float pvY = (float) (d1 + d2 + d3 + d3 + ROUND - 11.0);
        dropShadow(c, (float) d4, pvY, 15f, 15.5f, 40f, ROUND / 2f);
        paint.setColor(col.getRGB());
        c.drawRRect(RRect.makeXYWH((float) d4, pvY, 15f, 15.5f, 3.5f), paint);

        // SV 光标（Rise 7/6/5 三层圆角块）
        float cx = (float) (d0 + cur[0]), cy = (float) (d1 + cur[1]);
        paint.setColor(0xFFFFFFFF); c.drawRRect(RRect.makeXYWH(cx - 2.5f, cy - 2.5f, 7f, 7f, 3.5f), paint);
        paint.setColor(0xFF000000); c.drawRRect(RRect.makeXYWH(cx - 2f, cy - 2f, 6f, 6f, 3f), paint);
        paint.setColor(col.getRGB()); c.drawRRect(RRect.makeXYWH(cx - 2.5f, cy - 2.5f, 5f, 5f, 2.5f), paint);

        // RGB / hex
        double d7 = d0 + d3 * 2.0 + 15.0;
        double d8 = pvY;
        f17.drawText(c, String.valueOf(col.getRed()), (float) (d7 + d3), (float) d8, C_SECONDARY_TEXT, textPaint);
        f17.drawText(c, String.valueOf(col.getGreen()), (float) (d7 + 30.0), (float) d8, C_SECONDARY_TEXT, textPaint);
        f17.drawText(c, String.valueOf(col.getBlue()), (float) (d7 + d3 * 6.0), (float) d8, C_SECONDARY_TEXT, textPaint);
        f13.drawText(c, String.format("#%02X%02X%02X", col.getRed(), col.getGreen(), col.getBlue()),
                (float) d7, (float) (d8 + 13.0), 0xFF373B3D, textPaint);

        // 复制热区（两个 60×10）
        double czX = r[4] + 14.5 + r[6] + 32.0;
        double czY = r[5] + 0.5 + azb - 40.0 + ROUND;
        addHit(copyHits, new Object[]{cs, (float) czX, (float) czY, 60f, 10f, 0});
        addHit(copyHits, new Object[]{cs, (float) czX, (float) (czY + 13.0), 60f, 10f, 1});
        paint.setColor(0x14FFFFFF);
        c.drawRRect(RRect.makeXYWH((float) czX, (float) czY, 60f, 10f, 3f), paint);
        c.drawRRect(RRect.makeXYWH((float) czX, (float) (czY + 13.0), 60f, 10f, 3f), paint);
        f13.drawText(c, "RGB", (float) czX + 22f, (float) czY + 1.5f, C_SECONDARY_TEXT, textPaint);
        f13.drawText(c, "HEX", (float) czX + 24f, (float) (czY + 14.5), C_SECONDARY_TEXT, textPaint);
    }

    /** Rise RGQ 变体：显式两端色（color = mix(c1,c2, 0.5+0.5·sin(3(t+time))))。 */
    private static Shader rgq(float x, float y, float w, float h, boolean vertical, int c1, int c2) {
        int n = 24;
        int[] cols = new int[n];
        float[] stops = new float[n];
        double time = System.currentTimeMillis() / 1000.0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) (n - 1);
            double m = 0.5 + 0.5 * Math.sin(3.0 * (t + time));
            cols[i] = lerpColor(c1, c2, (float) m);
            stops[i] = t;
        }
        return vertical ? Shader.makeLinearGradient(x, y, x, y + h, cols, stops)
                : Shader.makeLinearGradient(x, y, x + w, y, cols, stops);
    }

    private static Shader rgqVertical(float x, float y, float w, float h, int c1, int c2) {
        return rgq(x, y, w, h, true, c1, c2);
    }

    private static final int[] HUE_COLORS = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
    };
    private static final float[] HUE_STOPS = {0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f};

    /** 帧末绘制展开的取色面板（此时 valueHits 已含本帧位置）。 */
    private void drawColorPicker(Canvas c) {
        if (!(openColor instanceof ColorSetting cs)) return;
        drawColorPanel(c, cs, 0f, 0f, 255);
    }

    // ── 切页过渡（Rise：旧页 200ms + 黑幕 200ms） ──

    /**
     * Rise oT 切页过渡：遮罩 = BACKGROUND(23,26,33) alpha k，
     * k = 255 − min(255, t<200 ? 255 − t·1.275 : (t−200)·1.275)，t ≤ 400ms。
     * t=0 → 0（无遮罩）；t=200 → 255（整窗被底色盖住，此刻换页）；t=400 → 0。
     */
    private void drawPageOverlay(Canvas c, long nowNs) {
        if (pageSwitchNs == 0L) return;
        float t = (nowNs - pageSwitchNs) / 1_000_000f;
        if (t > 400f) { pageSwitchNs = 0L; return; }
        float inner = t < 200f ? 255f - t * (255f / 200f) : (t - 200f) * (255f / 200f);
        int k = 255 - (int) Math.max(0f, Math.min(255f, inner));
        if (k <= 1) return;
        paint.setColor(withAlpha(C_BACKGROUND & 0xFFFFFF, k));
        c.drawRRect(RRect.makeXYWH(wx, wy, W, H, ROUND), paint);
    }

    // ───────────────────────── 输入 ─────────────────────────

    private boolean leftDown;
    /** bloom 通道重绘时不收集命中（否则命中表重复） */
    private boolean collecting = true;
    /** Rise Interface.Shaders：true 才跑 bloom 通道（默认与 Rise 一致 = false） */
    private boolean shadersOn;

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        float mx = dX(e.x()), my = dY(e.y());
        int btn = e.button();
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) leftDown = true;

        // 拖动窗口（Rise：顶部 15px）
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && mx > wx && mx < wx + W && my > wy && my < wy + 15f) {
            dragging = true;
            dragDX = mx - wx;
            dragDY = my - wy;
            return true;
        }
        // 取色器复制热区（最上层）
        for (Object[] hit : copyHits) {
            if (inside(mx, my, hit)) {
                ColorSetting cs = (ColorSetting) hit[0];
                Color col = cs.getValue();
                String text = ((Integer) hit[5]) == 0
                        ? col.getRed() + ", " + col.getGreen() + ", " + col.getBlue()
                        : String.format("#%02X%02X%02X", col.getRed(), col.getGreen(), col.getBlue());
                GLFW.glfwSetClipboardString(Minecraft.getInstance().getWindow().handle(), text);
                return true;
            }
        }

        // 分类
        for (Object[] hit : catHits) {
            if (inside(mx, my, hit)) {
                int p = (Integer) hit[0];
                if (p != page) {
                    prevPage = page;
                    page = p;
                    pageSwitchNs = System.nanoTime();
                    scroll = scrollTarget = 0f;
                }
                searchFocused = p == 0;
                return true;
            }
        }
        // 设置项
        for (Object[] hit : valueHits) {
            if (inside(mx, my, hit) && handleValueClick((Setting<?>) hit[0], mx, my, btn, (Float) hit[1], (Float) hit[2])) {
                return true;
            }
        }
        // 主题页：色块筛选 / 卡片选主题
        for (Object[] hit : themeHits) {
            if (!inside(mx, my, hit)) continue;
            if ((Integer) hit[5] == -1) {
                RiseTheme.KeyColor k = (RiseTheme.KeyColor) hit[0];
                themeFilter = (themeFilter == k) ? null : k;
            } else {
                RiseTheme.setActive((RiseTheme) hit[0]);
            }
            return true;
        }
        // 语言页
        for (Object[] hit : langHits) {
            if (!inside(mx, my, hit)) continue;
            RiseLang.setCurrent((RiseLang.Lang) hit[0]);
            return true;
        }
        // 脚本页：卡片动作（左键执行；右键删除用户配置）
        for (Object[] hit : scriptHits) {
            if (!inside(mx, my, hit)) continue;
            RiseConfigs.Card card = (RiseConfigs.Card) hit[0];
            if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !"Default".equals(card.title())
                    && !"Legit".equals(card.title()) && !"Movement".equals(card.title())
                    && !RiseLang.tr("ui.script.save").equals(card.title())) {
                RiseConfigs.delete(card.title());
            } else {
                try { card.action().run(); } catch (Throwable ignored) { }
            }
            return true;
        }

        // 模块行：左键开关 / 右键展开（取色面板打开时模态阻挡 —— Rise overlayPresent）
        if (openColor != null) return false;
        for (Object[] hit : rowHits) {
            if (!inside(mx, my, hit)) continue;
            Module m = (Module) hit[0];
            // Rise：行操作还要求鼠标在窗口内（flag2 = GUIUtil.c(窗口矩形)）
            if (!hit(mx, my, wx, wy, W, H)) continue;
            if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                boolean has = false;
                for (Setting<?> st : m.getSettings()) { if (visible(st)) { has = true; break; } }
                if (has) expanded.put(m, !Boolean.TRUE.equals(expanded.get(m)));
            } else if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                m.toggle();
            }
            return true;
        }
        return false;
    }

    private boolean handleValueClick(Setting<?> s, float mx, float my, int btn, float x, float y) {
        float lw = f16.getStringWidth(s.getName());
        float hitW = Minecraft.getInstance().getWindow().getGuiScaledWidth() - 70f;   // Rise 原式
        if (s instanceof BoolSetting bs) {
            // Rise BooleanValueComponent.e：(x, y−3.5, width−70, 14)
            if (hit(mx, my, x, y - 3.5f, hitW, 14f)) {
                bs.setValue(!Boolean.TRUE.equals(bs.getValue()));
                return true;
            }
            return false;
        }
        if (s instanceof IntSetting is) {
            return numericClick(s, mx, my, x, y, is.getMin(), is.getMax(), true);
        }
        if (s instanceof DoubleSetting ds) {
            return numericClick(s, mx, my, x, y, ds.getMin(), ds.getMax(), false);
        }
        if (s instanceof ChoiceSetting cs) {
            if (hit(mx, my, x, y - 3.5f, hitW, 14f)) {
                List<String> choices = cs.getChoices();
                int idx = choices.indexOf(String.valueOf(cs.getValue()));
                idx += btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1;
                cs.setValue(choices.get(((idx % choices.size()) + choices.size()) % choices.size()));
                return true;
            }
            return false;
        }
        if (s instanceof EnumSetting<?> es) {
            if (hit(mx, my, x, y - 3.5f, hitW, 14f)) {
                int n = es.getModes().length;
                int idx = es.getModeIndex() + (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1);
                setEnumRaw(es, ((idx % n) + n) % n);
                return true;
            }
            return false;
        }
        if (s instanceof StringSetting ss) {
            if (hit(mx, my, x, y, 230.5f, 28f)) {
                dragSetting = ss;
                numericEdit = true;
                editStartNs = System.nanoTime();
                editBuf.setLength(0);
                editBuf.append(ss.getValue() == null ? "" : ss.getValue());
                return true;
            }
            return false;
        }
        if (s instanceof ColorSetting cs) {
            float f = lw + 4f;
            // Rise ColorValueComponent.e：点色块/行区域开合（ayU）
            if (openColor == cs) {
                // 面板已开：先判 SV / 色相条拖动
                float[] r = colorPanelRect(cs);
                if (r != null) {
                    double d2 = 120 * 0.55;
                    if (hit(mx, my, r[0], r[1], 105f, (float) d2)) {
                        colorDragSV = true;
                        editStartNs = System.nanoTime();
                        applyColor(cs, mx, my);
                        return true;
                    }
                    if (hit(mx, my, (float) (r[0] + 8.5), (float) (r[1] + 8.5 + d2 - 5 + 2.5), 88f, 12f)) {
                        colorDragHue = true;
                        editStartNs = System.nanoTime();
                        applyColor(cs, mx, my);
                        return true;
                    }
                }
                if (hit(mx, my, x, y - 2f, 20f, 12f)) {
                    openColor = null;
                    return true;
                }
                openColor = null;   // 点面板外 = 收起（Rise overlayPresent 语义：本次点击不再传给行）
                return true;
            }
            if (hit(mx, my, x + f, y - 2f, 20f, 12f) || hit(mx, my, x, y - 3.5f, hitW, 15f)) {
                openColor = cs;
                colorCursor.remove(cs);
                colorHue.remove(cs);
                return true;
            }
            return false;
        }
        if (s instanceof ButtonSetting) {
            if (hit(mx, my, x, y - 3.5f, hitW, 14f)) {
                Object v = s.getValue();
                if (v instanceof Runnable r) r.run();
                return true;
            }
            return false;
        }
        if (s instanceof com.ylmac.adapt.features.module.settings.impl.KeybindSetting ks) {
            if (hit(mx, my, x, y - 3.5f, hitW, 14f) && btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                ks.setValue(0);
                return true;
            }
            return hit(mx, my, x, y - 3.5f, hitW, 14f);
        }
        return false;
    }

    private static boolean hit(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** Number 命中（Rise：轨道 (x+f−5, y−3.5, 110, 14)；文本框 (x+f+105, y, 20)）。 */
    private boolean numericClick(Setting<?> s, float mx, float my, float x, float y,
                                 double min, double max, boolean isInt) {
        float f = f16.getStringWidth(s.getName()) + 7f;
        if (hit(mx, my, x + f - 5f, y - 3.5f, 110f, 14f)) {
            dragSetting = s;
            dragTrackX = x + f;
            applyTrack(mx, min, max, isInt);
            return true;
        }
        if (hit(mx, my, x + f + 105f, y, 20f, 12f)) {
            startNumericEdit(s, isInt && s instanceof IntSetting is ? is.getValue() : valueOf(s));
            return true;
        }
        return false;
    }

    private static Object valueOf(Setting<?> s) {
        if (s instanceof DoubleSetting ds) return ds.getValue();
        if (s instanceof IntSetting is) return is.getValue();
        return s.getValue();
    }

    private void startNumericEdit(Setting<?> s, Object value) {
        dragSetting = s;
        numericEdit = true;
        editBuf.setLength(0);
        editBuf.append(value);
    }

    private void applyTrack(float mx, double min, double max, boolean isInt) {
        // Rise：azj = (mouseX − (x+f)) / 100，clamp 0..1，再按小数位取整
        double frac = clamp((mx - dragTrackX) / 100.0, 0.0, 1.0);
        double v = min + (max - min) * frac;
        if (isInt && dragSetting instanceof IntSetting is) {
            int step = Math.max(1, is.getStep());
            is.setValueSilently(clamp((int) Math.round(v / (double) step) * step, is.getMin(), is.getMax()));
        } else if (dragSetting instanceof DoubleSetting ds) {
            double step = ds.getStep() > 0 ? ds.getStep() : 0.01;
            double dv = Math.round(v / step) * step;
            ds.setValueSilently(clamp(dv, ds.getMin(), ds.getMax()));
        }
    }

    /**
     * Rise ColorValueComponent 取色公式：
     *   p = (clamp(mx−d0, 0, aza), clamp(my−d1, 0, d2))
     *   inner = ColorUtil.a(hueColor, WHITE, p.x/aza)   // hue·t + white·(1−t)
     *   color = ColorUtil.a(transparent, inner, p.y/d2) // 全分量（含 alpha）一起 lerp
     * 即：横轴 = 色相混白，纵轴 = 亮度并带 alpha 衰减（底部 alpha 0）。
     */
    private void applyColor(ColorSetting cs, float mx, float my) {
        float[] r = colorPanelRect(cs);
        if (r == null) return;
        double d0 = r[0], d1 = r[1];
        double d2 = 120 * 0.55;
        double aza = 105;
        float[] cur = colorCursor.computeIfAbsent(cs, k -> new float[2]);
        float[] hsv0 = Color.RGBtoHSB(cs.getValue().getRed(), cs.getValue().getGreen(), cs.getValue().getBlue(), null);
        float hueZ = colorHue.getOrDefault(cs, hsv0[0] * (float) (aza - 17.0));

        if (colorDragHue) {
            hueZ = (float) clamp(mx - (d0 + 8.5), 0.0, aza - 17.0);
            colorHue.put(cs, hueZ);
        }
        if (colorDragSV) {
            cur[0] = (float) clamp(mx - d0, 0.0, aza);
            cur[1] = (float) clamp(my - d1, 0.0, d2);
        }
        Color hueColor = Color.getHSBColor(hueZ / (float) (aza - 17.0), 1f, 1f);

        double tx = cur[0] / aza;
        double ty = cur[1] / d2;
        int rr = (int) ((hueColor.getRed() * tx + 255 * (1 - tx)) * (1 - ty));
        int gg = (int) ((hueColor.getGreen() * tx + 255 * (1 - tx)) * (1 - ty));
        int bb = (int) ((hueColor.getBlue() * tx + 255 * (1 - tx)) * (1 - ty));
        int aa = (int) (255 * (1 - ty));
        cs.setValueSilently(new Color(clamp(rr, 0, 255), clamp(gg, 0, 255), clamp(bb, 0, 255), clamp(aa, 0, 255)));
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (colorDragSV || colorDragHue) {
            if (openColor instanceof ColorSetting cs) cs.setValue(cs.getValue());
            colorDragSV = colorDragHue = false;
        }
        if (dragSetting instanceof IntSetting is && !numericEdit) is.setValue(is.getValue());
        else if (dragSetting instanceof DoubleSetting ds && !numericEdit) ds.setValue(ds.getValue());
        dragSetting = null;
        numericEdit = false;
        dragging = false;
        leftDown = false;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dragX, double dragY) {
        float mx = dX(e.x()), my = dY(e.y());
        if (dragging) {
            Minecraft mc = Minecraft.getInstance();
            float dw = mc.getWindow().getGuiScaledWidth() / uiScale;
            float dh = mc.getWindow().getGuiScaledHeight() / uiScale;
            wx = clamp(mx - dragDX, 0f, Math.max(0f, dw - W));
            wy = clamp(my - dragDY, 0f, Math.max(0f, dh - H));
            return true;
        }
        if (colorDragSV || colorDragHue) {
            if (openColor instanceof ColorSetting cs) applyColor(cs, mx, my);
            return true;
        }
        if (dragSetting instanceof IntSetting is && !numericEdit) { applyTrack(mx, is.getMin(), is.getMax(), true); return true; }
        if (dragSetting instanceof DoubleSetting ds && !numericEdit) { applyTrack(mx, ds.getMin(), ds.getMax(), false); return true; }
        return false;
    }

    /**
     * 滚轮：**每个事件立即累加**到目标（不再 50ms 节流丢弃）。
     * MC（尤其 macOS 平滑滚动）会高频发**小数**增量，节流丢弃会导致一顿一顿的抖动；
     * Rise 用 LWJGL2 的整数 120/格 所以看不出问题。平滑仍由 1%/ms 的 lerp 负责。
     */
    private float pendingWheel;

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        pendingWheel += (float) (v * 60.0);
        return true;
    }

    private boolean searchFocused = true;

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (numericEdit) { numericEdit = false; dragSetting = null; return true; }
            if (openColor != null) { openColor = null; return true; }
            // Rise：关闭动画（scale → 0）结束后真正关屏
            closing = true;
            closeStartNs = System.nanoTime();
            return true;
        }
        if (numericEdit && dragSetting != null) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (editBuf.length() > 0) editBuf.deleteCharAt(editBuf.length() - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commitNumericEdit();
                return true;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (page != 0) switchPage(0);
            if (!searchTyped) { searchText = ""; searchTyped = true; }
            if (!searchText.isEmpty()) searchText = searchText.substring(0, searchText.length() - 1);
            return true;
        }
        return super.keyPressed(e);
    }

    private void commitNumericEdit() {
        String val = editBuf.toString().trim();
        try {
            if (dragSetting instanceof IntSetting is) {
                is.setValue((int) Double.parseDouble(val));
            } else if (dragSetting instanceof DoubleSetting ds) {
                ds.setValue(Double.parseDouble(val));
            } else if (dragSetting instanceof StringSetting ss) {
                ss.setValue(val);
            }
        } catch (NumberFormatException ignored) {
        }
        numericEdit = false;
        dragSetting = null;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        String ch = event.codepointAsString();
        if (ch == null || ch.isEmpty()) return false;
        if (numericEdit) {
            editBuf.append(ch);
            return true;
        }
        if (event.isAllowedChatCharacter()) {
            // Rise：非搜索页/未输入过 → 切到搜索页并清空旧查询，再敲入首字符
            if (!searchTyped || page != 0) {
                searchTyped = true;
                searchText = "";
                if (page != 0) switchPage(0);
            }
            searchText += ch;
            scroll = scrollTarget = 0f;
            return true;
        }
        return super.charTyped(event);
    }

    /** 切页：保存旧页滚动状态 → 载入新页（Rise：各 Screen 独立 ScrollUtil，min 不会串页） */
    private void switchPage(int p) {
        if (p == page) return;
        saveScroll(page);
        prevPage = page;
        page = p;
        pageSwitchNs = System.nanoTime();
        loadScroll(p);
        searchTyped = false;
        searchTextAlpha = 255f;
    }

    private void saveScroll(int p) {
        if (p < 0 || p >= pageScroll.length) return;
        pageScroll[p][0] = scroll;
        pageScroll[p][1] = scrollTarget;
        pageScroll[p][2] = scrollMin;
    }

    private void loadScroll(int p) {
        if (p < 0 || p >= pageScroll.length) return;
        scroll = pageScroll[p][0];
        scrollTarget = pageScroll[p][1];
        scrollMin = pageScroll[p][2];
    }

    // ───────────────────────── 工具 ─────────────────────────

    // ─────────────────── THEME 页（Rise ThemeScreen 几何） ───────────────────

    /** chips：宽 (400−100−43)/5 = 51.4、高 17、间距 7、行距 24，起点 (x+107, y+44+scroll)；
     *  卡片：3 列 宽 (400−100−29)/3 = 90.3、高 50、间距 7、行距 57，起点 y+104+scroll。 */
    private void drawThemePage(Canvas c, float mx, float my) {
        float baseX = wx + SIDEBAR_W + 7f;
        float y0 = wy + 44f + scroll;
        RiseTheme.KeyColor[] keys = RiseTheme.KeyColor.values();
        float chipW = (W - SIDEBAR_W - 43f) / 5f;
        for (int i = 0; i < keys.length; i++) {
            int col = i % 5, row = i / 5;
            float x = baseX + col * (chipW + 7f);
            float y = y0 + row * 24f;
            boolean sel = themeFilter == keys[i];
            paint.setColor(keys[i].rgb);
            c.drawRRect(RRect.makeXYWH(x, y, chipW, 17f, 5f), paint);
            if (sel) {
                paint.setMode(PaintMode.STROKE);
                paint.setStrokeWidth(1.6f);
                paint.setColor(0xFFFFFFFF);
                c.drawRRect(RRect.makeXYWH(x - 1f, y - 1f, chipW + 2f, 19f, 6f), paint);
                paint.setMode(PaintMode.FILL);
                paint.setStrokeWidth(1f);
            }
            addHit(themeHits, new Object[]{keys[i], x, y, chipW, 17f    , -1});
        }

        float cardW = (W - SIDEBAR_W - 29f) / 3f;
        float cy0 = wy + 104f + scroll;
        int col2 = 0, row2 = 0;
        int vis = 0;
        for (RiseTheme t : RiseTheme.ALL) {
            if (themeFilter != null && !t.hasKey(themeFilter)) continue;
            vis++;
        }
        visibleCount = vis;
        for (RiseTheme t : RiseTheme.ALL) {
            if (themeFilter != null && !t.hasKey(themeFilter)) continue;
            float tx0 = baseX + col2 * (cardW + 7f);
            float ty0 = cy0 + row2 * 57f;
            // Rise ThemeComponent：x/y 用 EASE_OUT_QUINT 500ms 动画滑入
            float[] anim = themeCardAnim.computeIfAbsent(t, k -> new float[]{tx0, ty0});
            if (collecting) {
                anim[0] += (tx0 - anim[0]) * Math.min(1f, dtTheme / 500f) * 3.2f;
                anim[1] += (ty0 - anim[1]) * Math.min(1f, dtTheme / 500f) * 3.2f;
            }
            float x = anim[0], y = anim[1];
            boolean active = RiseTheme.active() == t;
            // 卡片：底色 OVERLAY + 主题主色渐变头（Rise 卡片渐变头 r=9）
            paint.setColor(C_OVERLAY);
            c.drawRRect(RRect.makeXYWH(x, y, cardW, 50f, 8f), paint);
            // ⚠ stop 必须严格递增：之前用 blendFactor 当第一个 stop，可能 > 0.999 → Skija 抛异常
            //   → 整个 draw() 被 catch 掉 → 命中表没填 → "主题点了没反应 / 滚轮卡住"
            paint.setShader(Shader.makeLinearGradient(x, y, x + cardW, y,
                    new int[]{withAlpha(t.primary() & 0xFFFFFF, 210), withAlpha(t.secondary() & 0xFFFFFF, 210)},
                    new float[]{0f, 1f}));
            c.drawRRect(RRect.makeXYWH(x, y, cardW, 22f, 9f), paint);
            paint.setShader(null);
            f13.drawText(c, t.name(), x + 4f, y + 6f, 0xFFFFFFFF, textPaint);
            if (active) {
                // 选中标记：底部 accent 条（不用 ✓，避免字形缺失）
                paint.setColor(accent(y / 5f));
                c.drawRRect(RRect.makeXYWH(x + cardW / 2f - 10f, y + 42f, 20f, 3f, 1.5f), paint);
            }
            addHit(themeHits, new Object[]{t, x, y, cardW, 50f, 1});
            if (++col2 >= 3) { col2 = 0; row2++; }
        }
        // Rise ThemeScreen：V(-57 * max(0, ceil(visible/3) - 3))
        int rows = Math.max(0, (int) Math.ceil(visibleCount / 3.0) - 3);
        scrollMin = -57f * rows;
        updateScroll(dtTheme);
        // 标题（Rise：居中 @(窗口右−20, 顶部+20+scroll) 16pt alpha 128）
        String tt = RiseLang.tr("ui.theme");
        f16.drawText(c, tt, wx + W - 20f - f16.getStringWidth(tt) / 2f, wy + 20f + scroll,
                withAlpha(0xFFFFFF, 128), textPaint);
    }

    /** 主题页可见卡片数（供滚动下限公式用） */
    private int visibleCount;

    // ─────────────────── LANGUAGE 页（Rise LanguageScreen 几何） ───────────────────

    /**
     * LANGUAGE 页（Rise LanguageScreen + LanguageComponent 原式）：
     * 项 @(x+100+8, y + (i+1)*46 + scroll) 285×38 r6 OVERLAY；主行英文名 20pt
     * （当前语言 = accent(0, y/5)）；副行本地名 17pt alpha 100；
     * 国旗 15×15 @(x + width20(英文名) + 25, +5)（贴图拷贝自 Rise）；
     * 顶部 40px BACKGROUND 条（仅上两角圆角）+ 居中标题 @(窗口右−20, 顶+20) 16pt alpha 128；
     * 滚动下限 −2000（Rise 原值）。
     */
    private void drawLangPage(Canvas c, float mx, float my) {
        float x = wx + SIDEBAR_W + 8f;
        float y0 = wy + scroll;
        for (int i = 0; i < RiseLang.ALL.length; i++) {
            RiseLang.Lang l = RiseLang.ALL[i];
            float y = y0 + (i + 1) * 46f;
            boolean cur = RiseLang.current() == l;
            paint.setColor(C_OVERLAY);
            c.drawRRect(RRect.makeXYWH(x, y, 285f, 38f, ROW_R), paint);
            f20.drawText(c, l.english(), x + 10f, y + 9f, cur ? accent(y / 5f) : 0xFFFFFFFF, textPaint);
            f17.drawText(c, l.local(), x + 10f, y + 24f, withAlpha(0xFFFFFF, 100), textPaint);
            drawFlag(c, l.file(), x + f20.getStringWidth(l.english()) + 25f, y + 5f);
            addHit(langHits, new Object[]{l, x, y, 285f, 38f});
        }
        scrollMin = -2000f;      // Rise LanguageScreen：V(-2000)
        updateScroll(dtTheme);
        int s0 = c.save();
        try {
            c.clipRRect(RRect.makeXYWH(wx, wy, W, H, ROUND), ClipMode.INTERSECT, true);
            paint.setColor(C_BACKGROUND);
            c.drawRect(Rect.makeXYWH(wx + SIDEBAR_W, wy, W - SIDEBAR_W, 40f), paint);
        } finally {
            c.restoreToCount(s0);
        }
        String t = RiseLang.tr("ui.language");
        f16.drawText(c, t, wx + W - 20f - f16.getStringWidth(t) / 2f, wy + 20f,
                withAlpha(0xFFFFFF, 128), textPaint);
    }

    /** 国旗贴图（15×15；拷贝自 Rise：rise/icons/language/<file>.png） */
    private static final java.util.Map<String, Image> FLAGS = new HashMap<>();

    private void drawFlag(Canvas c, String file, float x, float y) {
        Image img = FLAGS.get(file);
        if (img == null) {
            try (var in = RiseClickGuiScreen.class.getResourceAsStream(
                    "/assets/adaptclient/rise/flags/" + file + ".png")) {
                if (in != null) {
                    img = Image.makeFromEncoded(in.readAllBytes());
                    FLAGS.put(file, img);
                }
            } catch (Throwable ignored) {
            }
        }
        if (img == null) return;
        c.drawImageRect(img, Rect.makeWH(img.getWidth(), img.getHeight()),
                Rect.makeXYWH(x, y, 15f, 15f), SamplingMode.LINEAR, null, false);
    }

    // ─────────────────── SCRIPT 页（Rise ScriptScreen 几何） ───────────────────

    /** 横幅 110 高 OVERLAY alpha 100 r10；卡片 86.45×86.45 r8 间距 10；标题 20pt / 副标题 16pt。 */
    private void drawScriptPage(Canvas c, float mx, float my) {
        float x = wx + SIDEBAR_W + 8f;
        float y = wy + 7f + scroll;
        paint.setColor(withAlpha(0x000000, 100));
        c.drawRRect(RRect.makeXYWH(x, y, ROW_W, 110f, 10f), paint);
        String banner = RiseLang.tr("ui.script");
        f20.drawText(c, banner, x + 10f, y + 10f, 0xFFFFFFFF, textPaint);

        y += 118f;
        float contentH = 118f;
        // Featured
        y = drawScriptSection(c, x, y, RiseLang.tr("ui.script.featured"), RiseConfigs.featured(), mx, my);
        contentH = y - wy - 7f - scroll;
        // 用户配置
        java.util.List<RiseConfigs.Card> users = new java.util.ArrayList<>(RiseConfigs.userCards());
        users.add(new RiseConfigs.Card(RiseLang.tr("ui.script.save"), "+",
                () -> RiseConfigs.saveCurrent()));
        y = drawScriptSection(c, x, y, RiseLang.tr("ui.script.yours"), users, mx, my);
        contentH = y - wy - 7f - scroll;
        scrollMin = Math.min(0f, -contentH + H - 7f);
        updateScroll(dtTheme);
    }

    private float drawScriptSection(Canvas c, float x, float y, String title,
                                    java.util.List<RiseConfigs.Card> cards, float mx, float my) {
        f18.drawText(c, title, x, y, 0xFFFFFFFF, textPaint);
        f18.drawText(c, String.valueOf(cards.size()), x + f18.getStringWidth(title) + 6f, y,
                accent(y / 5f), textPaint);
        y += 20f;
        float cw = 86.45f, gap = 10f;
        int col = 0;
        float rowY = y;
        for (RiseConfigs.Card card : cards) {
            float cx = x + col * (cw + gap);
            paint.setColor(C_OVERLAY);
            c.drawRRect(RRect.makeXYWH(cx, rowY, cw, cw, 8f), paint);
            float tw = f20.getStringWidth(card.title());
            f20.drawText(c, card.title(), cx + (cw - tw) / 2f, rowY + cw / 2f - f20.height() / 2f + 1f - 2.5f,
                    0xFFFFFFFF, textPaint);
            if (card.subtitle() != null && !card.subtitle().isEmpty()) {
                float sw = f15.getStringWidth(card.subtitle());
                f15.drawText(c, card.subtitle(), cx + (cw - sw) / 2f, rowY + cw / 2f + f20.height() / 2f + 3f,
                        0x82FFFFFF, textPaint);
            }
            addHit(scriptHits, new Object[]{card, cx, rowY, cw, cw});
            if (++col >= 3) { col = 0; rowY += cw + gap; }
        }
        if (col != 0) rowY += cw + gap;
        return rowY + 8f;
    }

    /** 把当前表面写成 PNG（诊断用，最多 12 张）。 */
    private void dumpFrame() {
        Image img = null;
        try {
            img = surf.snapshot();
            if (img == null) return;
            var path = java.nio.file.Path.of(System.getProperty("user.home"), ".adapt-rise-gui");
            java.nio.file.Files.createDirectories(path);
            var data = img.encodeToData(io.github.humbleui.skija.EncodedImageFormat.PNG, 90);
            if (data != null) {
                String name = String.format("rise-%02d.png", guiDumpCount);
                java.nio.file.Files.write(path.resolve(name), data.getBytes());
                data.close();
                AdaptClient.LOGGER.info("[RiseClickGUI] dumped {} ({}x{})",
                        path.resolve(name), img.getWidth(), img.getHeight());
                guiDumpCount++;
            }
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[RiseClickGUI] dump failed: {}", t.toString());
        } finally {
            if (img != null) try { img.close(); } catch (Throwable ignored) { }
        }
    }

    /** 每页只自动 dump 一次（会话内）—— 便于逐值核对每个界面的排版 */
    private final boolean[] dumpedPage = new boolean[16];
    private final long[] pageShownNs = new long[16];

    /** 把当前页渲染成 PNG 存到 ~/.adapt-risegui/page-<页名>.png（离线核对用）。 */
    private void autoDump(int guiW, int guiH) {
        if (page < 0 || page >= dumpedPage.length) return;
        if (pageShownNs[page] == 0L) pageShownNs[page] = System.nanoTime();
        if (dumpedPage[page]) return;
        if (System.nanoTime() - pageShownNs[page] < 900_000_000L) return;   // 等动画稳定
        dumpedPage[page] = true;
        try {
            float sc = Minecraft.getInstance().getWindow().getGuiScale();
            Surface s = Surface.makeRasterN32Premul(Math.round(guiW * sc), Math.round(guiH * sc));
            Canvas cc = s.getCanvas();
            cc.clear(0x00000000);
            cc.scale(sc, sc);
            drawWindowContent(cc, -100f, -100f, System.nanoTime(), 16f, guiW, guiH, true);
            Image img = s.makeImageSnapshot();
            var data = img.encodeToData(io.github.humbleui.skija.EncodedImageFormat.PNG, 90);
            if (data != null) {
                java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home"), ".adapt-risegui");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path out = dir.resolve("page-" + page + ".png");
                java.nio.file.Files.write(out, data.getBytes());
                data.close();
                AdaptClient.LOGGER.info("[RiseClickGUI] dumped {}", out);
            }
            img.close();
            s.close();
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[RiseClickGUI] dump page {} failed: {}", page, t.toString());
        }
    }

    /**
     * Rise ScrollUtil.E/qx 等价实现（**所有页面共用**）：
     * - 有滚轮输入的那一帧：target += dWheel/2，clamp 到 [min − 30, 30]（橡皮筋）；
     * - 其余帧：target 一律 clamp 回 [min, 0]（原来只在 min<0 时夹 → 顶部上滑会残留 +30 → 抖动）；
     * - 平滑：每经过 1ms 做一次 lerp(scroll, target, 0.01)（不封顶步数，Rise 同款循环）。
     */
    private void updateScroll(float dtMs) {
        if (!collecting) return;              // bloom 通道重绘不推进状态
        if (pendingWheel != 0f) {
            // Rise 原版上界是 +30（向上橡皮筋），但实测顶部上滑会出现 ~5px 过冲→回弹的抖动；
            // 上界收到 0：顶部上滑不动（无抖动），向下仍保留 30px 橡皮筋
            scrollTarget = clamp(scrollTarget + pendingWheel, scrollMin - 30f, 0f);
            pendingWheel = 0f;
        } else {
            scrollTarget = clamp(scrollTarget, Math.min(scrollMin, 0f), 0f);
        }
        int steps = Math.max(0, Math.min(200, (int) dtMs));
        for (int i = 0; i < steps; i++) {
            scroll += (scrollTarget - scroll) * 0.01f;
        }
        if (Math.abs(scrollTarget - scroll) < 0.05f) scroll = scrollTarget;
    }

    /** 屏幕坐标 → 设计坐标（draw() 里整体乘了 uiScale） */
    private float dX(double screenX) { return (float) (screenX / uiScale); }

    private float dY(double screenY) { return (float) (screenY / uiScale); }

    /** 命中表收集（bloom 重绘时跳过） */
    private void addHit(java.util.List<Object[]> list, Object[] hit) {
        if (collecting) list.add(hit);
    }

    private boolean inside(float mx, float my, Object[] hit) {
        return mx > (Float) hit[1] && mx < (Float) hit[1] + (Float) hit[3]
                && my > (Float) hit[2] && my < (Float) hit[2] + (Float) hit[4];
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setEnumRaw(EnumSetting es, int idx) {
        es.setValue(es.getModes()[idx]);
    }

    /**
     * Rise 强调色：ThemeManager 当前主题的 getAccentColor((0, y/5))
     * （Rise 处处传 {@code new Vector2d(0.0, y / 5.0)}）。
     */
    private static int accent(float pos) {
        return RiseTheme.active().accentAt(0f, pos);
    }

    /** Rise rz().rA()：主题主色（品牌版本号、9 个同心圆用它，不是流动色）。 */
    private static int themePrimary() {
        return RiseTheme.active().primary();
    }

    private static int lerpColor(int c1, int c2, float t) {
        int a = (int) (((c1 >>> 24) & 0xFF) * t + ((c2 >>> 24) & 0xFF) * (1 - t));
        int r = (int) (((c1 >> 16) & 0xFF) * t + ((c2 >> 16) & 0xFF) * (1 - t));
        int g = (int) (((c1 >> 8) & 0xFF) * t + ((c2 >> 8) & 0xFF) * (1 - t));
        int b = (int) ((c1 & 0xFF) * t + (c2 & 0xFF) * (1 - t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int darker(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * 0.7f);
        int g = (int) (((argb >> 8) & 0xFF) * 0.7f);
        int b = (int) ((argb & 0xFF) * 0.7f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int withAlpha(int rgb, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int mulAlpha(int argb, int mul) {
        int a = ((argb >>> 24) & 0xFF) * clamp(mul, 0, 255) / 255;
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static String trimDouble(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-6) return String.valueOf((long) Math.round(v));
        String s = String.format("%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : Math.min(v, hi); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
    private static int clamp(double v, int lo, int hi) { return (int) clamp(v, (double) lo, (double) hi); }

    // ── 动画（Rise util/animation/Animation + Easing 语义：按 duration 归一化单次插值） ──

    private enum Ease {
        LINEAR { float apply(float t) { return t; } },
        EASE_OUT_EXPO { float apply(float t) { return t >= 1f ? 1f : (float) (1.0 - Math.pow(2.0, -10.0 * t)); } },
        EASE_OUT_QUINT { float apply(float t) { return (float) (1.0 - Math.pow(1.0 - t, 5)); } },
        EASE_OUT_QUAD { float apply(float t) { return (float) (1.0 - Math.pow(1.0 - t, 2)); } };

        abstract float apply(float t);
    }

    /** 线性推进到目标的动画（每帧按 dt 逼近，带缓动曲线）。 */
    private static final class Anim {
        private float value, target, durationMs, elapsed;
        private final Ease ease;

        Anim(float durationMs, Ease ease) {
            this.durationMs = durationMs;
            this.ease = ease;
        }

        void setDuration(float ms) { this.durationMs = ms; }
        void setTarget(float t) {
            if (Math.abs(t - target) > 1e-4f) {
                // 以当前值为起点重新计时
                elapsed = 0f;
                from = value;
            }
            target = t;
        }

        private float from;

        void update(float dtMs) {
            if (Math.abs(value - target) < 1e-4f) { value = target; return; }
            elapsed += dtMs;
            float t = durationMs <= 0f ? 1f : Math.min(1f, elapsed / durationMs);
            value = from + (target - from) * ease.apply(t);
            if (t >= 1f) value = target;
        }

        float value() { return value; }
    }
}
