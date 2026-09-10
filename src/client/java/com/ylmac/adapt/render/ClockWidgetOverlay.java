package com.ylmac.adapt.render;

import com.ylmac.adapt.QplayerModClient;
import com.ylmac.adapt.features.module.ModuleRegistry;
import com.ylmac.adapt.features.module.impl.render.ClockWidgetModule;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.RSXform;
import io.github.humbleui.skija.TextBlob;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import io.github.humbleui.skija.Typeface;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Google Clock 9.1 的模拟时钟小组件（表盘）—— Minecraft HUD 复刻。
 *
 * <h3>数据来源（全部反编译核对，不是照印象）</h3>
 * <ul>
 *   <li>四种表盘：{@code ckn}（AnalogAppWidgetController 里的枚举）
 *       SCALLOP / NUMERAL / DAY / CLOVER，各自的背景圆缩放 0.9333 / 1.0 / 1.0 / 0.8812。</li>
 *   <li>尺寸：{@code csb.b()} —— 表盘直径 = {@code hrz.t(380dp, 控件宽, 控件高)}（取最小值），
 *       背景圆直径 = 表盘 × 面缩放，圆角 = 直径/2（圆形）。</li>
 *   <li>指针角度：AOSP {@code android/widget/AnalogClock.java} onDraw ——
 *       时针 {@code mHour/12*360}、分针 {@code mMinutes/60*360}、秒针 {@code mSeconds/60*360}，
 *       其中 {@code mMinutes = 分 + 秒/60}、{@code mHour = 时 + mMinutes/60}，
 *       秒按 {@code mSecondsHandFps}（默认 1）取整。三根针都绕表盘中心旋转，
 *       指针 drawable 按 intrinsic 尺寸居中、再按 InsetDrawable 的百分比内缩。</li>
 *   <li>扇贝日期环：{@code csb.b()} SCALLOP 分支 —— 半径 = 中心×14/19，
 *       字号 = 表盘×0.1（dimen/analog_widget_font_ratio），字距 -0.02，
 *       google-sans 字重 500，文本"ccc d"（如 "周一 8"）沿圆路径居中，
 *       然后整张位图被当作<b>秒针</b>绘制 → 日期永远与秒点相反（Google 官方描述
 *       "weekday and date appearing (and rotating) opposite the seconds dot"）。
 *       只有表盘够大（字号 ≥ 10sp，即 ≥ 100dp）才显示。</li>
 *   <li>DAY 面：表盘圆右侧有个圆角窗口（矢量里挖掉的洞），窗口里是日期数字
 *       （"dd" 格式）—— 尺寸 0.22×0.15 表盘、左边距 0.345 表盘、内边距 0.018 表盘，
 *       字号二分搜索到刚好放进窗口（{@code dmg.a}）。</li>
 * </ul>
 * 矢量路径在 {@link ClockWidgetArt}（自动生成，pathData 原样）。
 */
public final class ClockWidgetOverlay implements HudElement, MovableHud {

    private static final ClockWidgetOverlay INSTANCE = new ClockWidgetOverlay();

    public static ClockWidgetOverlay get() {
        return INSTANCE;
    }

    /** 一格设计像素（与音乐小组件同一套格子模型）。 */
    static final int CELL = 100;
    /** 画布外扩：影子/bloom 画在卡片外面，不留空间会被裁掉。 */
    private static final int SHADOW_MARGIN = 40;
    /** Modern 的流体背景（歌词界面那套），每个时钟面各自一份状态 */
    private com.ylmac.adapt.features.music.lyric.skia.FluidBackground fluidBg;
    private io.github.humbleui.skija.DirectContext fluidCtx;
    private String fluidTrackKey = "";

    /** 表盘样式 —— 对应 Google Clock 的 {@code ckn} 枚举。 */
    public enum Face {
        SCALLOP("扇贝", 0.93333334f),
        NUMERAL("数字", 1.0f),
        DAY("日期", 1.0f),
        CLOVER("四叶", 0.8811881f);

        final String label;
        /** 背景圆直径相对表盘的比例（原版 {@code ckn.g}）。 */
        final float bgScale;

        Face(String label, float bgScale) {
            this.label = label;
            this.bgScale = bgScale;
        }
    }

    private MinecraftSurfaceBackend surf;
    private String surfaceKey = "";
    private final Paint paint = new Paint();

    private float posX, posY;
    private boolean posSet;
    private int cols = 2;
    private int rows = 2;

    /** 1Hz 重绘：秒变才重画（原版 AnalogClock 的秒针 FPS 也是这个粒度）。 */
    private long lastBucket = Long.MIN_VALUE;

    /** 日期环字体缓存：资源路径 → 基础字体；码点 → 选中的字体。 */
    private final java.util.Map<String, Font> baseFonts = new java.util.HashMap<>();
    private final java.util.Map<Integer, Font> cpFontCache = new java.util.HashMap<>();
    private static final String[] BCP47 = {"zh-Hans", "zh-Hant", "ja", "ko", "th", "ar", "he", "hi"};

    private boolean registered;
    private boolean loggedBlurUnavailable;

    private ClockWidgetOverlay() {
    }

    // ───────────────────────── 模块状态 ─────────────────────────

    private static ClockWidgetModule module() {
        for (ModuleRegistry.ModuleEntry e : ModuleRegistry.getAllModules()) {
            if (e.linkedRealModule() instanceof ClockWidgetModule m) {
                return m;
            }
        }
        return null;
    }

    /** 时钟卡片是否用封面取色（时钟模块自己的 Color Source 设置）。 */
    private static boolean useCoverColors() {
        ClockWidgetModule m = module();
        return m != null && m.getColorSource() == ClockWidgetModule.ColorSource.COVER;
    }

    private static boolean isEnabled() {
        ClockWidgetModule m = module();
        return m != null && m.isEnabled();
    }

    private static Face face() {
        ClockWidgetModule m = module();
        return m == null ? Face.SCALLOP : m.getFace();
    }

    private static boolean showSeconds() {
        ClockWidgetModule m = module();
        return m == null || m.showSeconds();
    }

    private static double scaleValue() {
        ClockWidgetModule m = module();
        return m == null ? 1.0 : m.getScale();
    }

    public double hudScale() {
        return scaleValue();
    }

    public void onModuleStateChanged() {
        surfaceKey = "";
    }

    public void registerHud() {
        if (registered) return;
        registered = true;
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.parse("adaptclient:clockwidget"), this);
    }

    // ───────────────────────── MovableHud ─────────────────────────

    @Override
    public String hudId() {
        return "clockwidget";
    }

    @Override
    public String hudLabel() {
        return "时钟小组件";
    }

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
        Minecraft mc = Minecraft.getInstance();
        return isEnabled() && mc.level != null;
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

    private int designW() {
        return cols * CELL;
    }

    private int designH() {
        return rows * CELL;
    }

    // ───────────────────────── 渲染 ─────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!hudVisible()) return;
        try {
            render(g);
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[ClockWidget] render error: {}", t.toString());
        }
    }

    private void render(GuiGraphicsExtractor g) {
        int dw = designW(), dh = designH();
        Face face = face();
        boolean seconds = showSeconds();
        String key = cols + "x" + rows + "|" + face + "|" + (seconds ? 1 : 0);
        // 原生设备像素：物理纹理 = 设计尺寸 × 组件缩放 × 窗口 GUI scale（1:1 贴图）
        float deviceScale = (float) scaleValue()
                * Minecraft.getInstance().getWindow().getGuiScale();
        if (surf == null || !key.equals(surfaceKey)) {
            if (surf != null) {
                try { surf.dispose(); } catch (Exception ignored) { }
                surf = null;
            }
            SkijaNativeFix.preload();
            surf = new MinecraftSurfaceBackend("clock_widget",
                    dw + 2 * SHADOW_MARGIN, dh + 2 * SHADOW_MARGIN, deviceScale);
            surfaceKey = key;
            lastBucket = Long.MIN_VALUE;
        } else {
            surf.ensureDeviceScale(deviceScale);
        }

        // Modern 卡片的内容（世界毛玻璃 + 流体背景）本身就是逐帧变化的 —— 必须每帧重绘，
        // 否则模糊按秒跳变（用户："时钟的模糊一卡一卡的"，而每帧重绘的播放器正常）。
        // Classic 面与帧无关（原版 AnalogClock 按 mSecondsHandFps=1 驱动）：有秒针按秒、
        // 没秒针按分钟重绘即可。
        long now = System.currentTimeMillis();
        boolean perFrame = isModern();
        long bucket = seconds ? now / 1000L : now / 60000L;
        if (perFrame || bucket != lastBucket) {
            Canvas canvas = surf.acquireCanvas();
            if (canvas == null) return;
            canvas.translate(SHADOW_MARGIN, SHADOW_MARGIN);   // 设计坐标不变，四周留给影子/bloom
            drawFace(canvas, face, seconds);
            surf.present();
            lastBucket = bucket;
        }

        int texW = surf.getDisplayWidth(), texH = surf.getDisplayHeight();
        float scale = (float) scaleValue();
        int x = Math.round(hudX()), y = Math.round(hudY());
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x - SHADOW_MARGIN * scale, y - SHADOW_MARGIN * scale);
        pose.scale(scale, scale);
        g.blit(surf.blitPipeline(), surf.getTextureId(),
                0, 0, 0f, 0f, dw + 2 * SHADOW_MARGIN, dh + 2 * SHADOW_MARGIN,
                texW, texH, texW, texH);
        pose.popMatrix();
    }

    private void drawFace(Canvas c, Face face, boolean showSeconds) {
        int W = designW(), H = designH();
        float dial = Math.min(W, H);
        float unit = dial / ClockWidgetArt.DIAL_DP;      // 380 坐标系 → 设计像素
        float cx = W / 2f, cy = H / 2f;

        // 用时钟模块自己的 Color Source（不再共用音乐模块的设置）
        Map<String, String> sc = MusicWidgetOverlay.colorScheme(useCoverColors());
        int bg = col(sc, "secondaryContainer", 0xFFE8DEF8);
        int hourColor = col(sc, "secondary", 0xFF625B71);
        int minuteColor = col(sc, "primary", 0xFF6750A4);
        int secondColor = col(sc, "tertiary", 0xFF7D5260);
        int numberColor = col(sc, "primary", 0xFF6750A4);
        int onNumber = col(sc, "onPrimaryColor", 0xFFFFFFFF);
        int dateColor = col(sc, "onSurfaceColor", 0xFF1D1B20);

        ClockWidgetArt.Vec[] paths = dialPaths(face);
        boolean modern = isModern();
        // Modern 的"卡片形状" = 表盘矢量第一段（扇贝 12 角星 / 四叶 / 圆）——形状与原来一模一样
        io.github.humbleui.skija.Path cardShape = null;   // Modern 下由 drawModernCard 内部构造

        // 1) 背景：Classic 画原来的背景圆；Modern 画记分榜式卡片
        paint.setMode(PaintMode.FILL);
        if (modern) {
            drawModernCard(c, paths[0], face, dial, cx, cy, bg);
        } else {
            paint.setColor(bg);
            c.drawCircle(cx, cy, dial * face.bgScale / 2f, paint);
        }

        // 2) DAY 面的日期芯片：画在表盘**下面**，透过表盘右侧的窗口显示（原版层级如此）
        if (face == Face.DAY) {
            drawDayChip(c, dial, cx, cy, numberColor, onNumber);
        }

        // 3) 表盘矢量（Modern 下跳过第一段纯色填充，让动态背景透出来）
        for (int i = 0; i < paths.length; i++) {
            if (modern && i == 0) continue;
            if (face == Face.SCALLOP) {
                if (i == 0) {
                    drawVector(c, paths[i], cx - dial / 2f, cy - dial / 2f, dial, 380f, bg, 0f);
                }
                // ⚠ 第二段是描边（strokeColor="?colorOutline"，1dp）——**不画**。
                //   原版的控件主题是 Theme.DeskClock.AnalogWidget（继承
                //   android:Theme.DeviceDefault.DayNight），里面没有定义 colorOutline
                //   这个属性（它是 Material 组件的 attr，不是 android 的），主题解析失败
                //   → 描边颜色为透明 → 原版软件上根本看不到外框线。我们之前把它当可见
                //   颜色画了出来，于是扇贝面多了一圈诡异的外框（用户实测："原本的软件是
                //   没有的，其他式样也没有框"）。
            } else {
                int color = i == 0 ? bg : numberColor;
                drawVector(c, paths[i], cx - dial / 2f, cy - dial / 2f, dial, 182f, color, 0f);
            }
        }

        // 4) 指针（角度照 AOSP AnalogClock.onDraw）
        LocalTime t = LocalTime.now();
        double sec = showSeconds ? t.getSecond() : 0.0;
        double minutes = t.getMinute() + sec / 60.0;
        double hours = t.getHour() + minutes / 60.0;
        drawHand(c, hourHand(face), cx, cy, unit, hours / 12.0 * 360.0, hourColor);
        drawHand(c, minuteHand(face), cx, cy, unit, minutes / 60.0 * 360.0, minuteColor);
        if (showSeconds) {
            drawHand(c, secondHand(face), cx, cy, unit, sec / 60.0 * 360.0, secondColor);
        }

        // 5) 扇贝日期环：与秒点相反，跟着秒一起转
        if (face == Face.SCALLOP && dial * 0.1f >= 10f) {
            drawDateRing(c, dial, cx, cy, sec, dateColor);
        }
    }

    private static boolean isModern() {
        ClockWidgetModule m = module();
        return m != null && m.getCardStyle() == ClockWidgetModule.Style.MODERN;
    }

    /**
     * 记分榜式卡片：影子 + bloom（只在外侧）+ 毛玻璃 +（可选）动态背景。
     * <p>
     * 形状 = 表盘矢量第一段（扇贝 12 角星 / 四叶 / 圆），**完全原来的路径**，
     * 只做 canvas 变换（translate/scale），不拼 {@code Matrix33} ——
     * Skija 的 {@code makeConcat} 乘法顺序和直觉相反，拼出来形状会被放大/平移错位，
     * 裁剪区域跟着错 → 表盘只剩右下角（用户实测）。
     * <p>
     * 所有 canvas 状态都用 try/finally 还原：中途抛异常（流体背景/毛玻璃失败）
     * 也必须 restore，否则残留的 translate/clip/saveLayer 会把之后的表盘画歪。
     */
    /**
     * 记分榜式卡片：影子 + bloom（只在外侧）+ 毛玻璃 +（可选）动态背景。
     * <p>
     * <b>关键：形状变换烘进路径</b>。Skija 里 {@code Path.makeScale(s)} /
     * {@code makeOffset(x,y)} 是无歧义的（不像 {@code Matrix33.makeConcat} 乘法顺序会反），
     * 变换后得到的 {@code screenShape} 就在<b>设计坐标系</b>里 ——
     * 于是 clip 和内容同坐标系，不需要 save/restore 包着变换
     * （clipPath 后被 restore 会把 clip 一起还原掉，之前"内容没被裁到/位置错"就是这么来的）。
     */
    private void drawModernCard(Canvas c, ClockWidgetArt.Vec vec, Face face,
                               float dial, float cx, float cy, int bg) {
        ClockWidgetModule m = module();
        boolean dyn = m == null || m.dynamicBackground();
        float vp = face == Face.SCALLOP ? 380f : 182f;
        float sc = dial / vp;
        io.github.humbleui.skija.Path shape = io.github.humbleui.skija.Path.makeFromSVGString(vec.d());
        if (vec.evenOdd()) {
            io.github.humbleui.skija.Path f = shape.makeWithFillMode(
                    io.github.humbleui.skija.PathFillMode.EVEN_ODD);
            shape.close();
            shape = f;
        }
        io.github.humbleui.skija.Path screenShape = shape.makeScale(sc)
                .makeOffset(cx - vp / 2f * sc, cy - vp / 2f * sc);
        try {
            // 1) 影子（黑 190，模糊 6）
            try {
                var blur = io.github.humbleui.skija.ImageFilter.makeBlur(
                        6f, 6f, io.github.humbleui.skija.FilterTileMode.DECAL);
                Paint lp = new Paint().setImageFilter(blur);
                int s1 = c.save();
                try {
                    c.saveLayer(Rect.makeXYWH(cx - dial, cy - dial, dial * 2f, dial * 2f), lp);
                    paint.setColor(0xC0000000);
                    c.drawPath(screenShape, paint);
                    c.restoreToCount(s1 + 1);
                } finally {
                    c.restoreToCount(s1);
                }
                lp.close();
                blur.close();
            } catch (Throwable ignored) {
            }

            // 2) bloom（主色 alpha 0x99，模糊 20）—— DIFFERENCE 挖掉卡片区域，只在外侧溢出
            try {
                var blur = io.github.humbleui.skija.ImageFilter.makeBlur(
                        20f, 20f, io.github.humbleui.skija.FilterTileMode.DECAL);
                Paint lp = new Paint().setImageFilter(blur);
                int s1 = c.save();
                try {
                    c.clipPath(screenShape, io.github.humbleui.skija.ClipMode.DIFFERENCE, true);
                    c.saveLayer(Rect.makeXYWH(cx - dial * 2f, cy - dial * 2f, dial * 4f, dial * 4f), lp);
                    paint.setColor(withAlpha(col(MusicWidgetOverlay.colorScheme(useCoverColors()), "primary", 0xFFD0BCFF), 0x99));
                    c.drawPath(screenShape, paint);
                    c.restoreToCount(s1 + 1);
                } finally {
                    c.restoreToCount(s1);
                }
                lp.close();
                blur.close();
            } catch (Throwable ignored) {
            }

            // 3) 卡片内部：裁到形状 → 毛玻璃 +（可选）动态背景（都在设计坐标系）
            int s1 = c.save();
            boolean painted = false;
            try {
                c.clipPath(screenShape, io.github.humbleui.skija.ClipMode.INTERSECT, true);
                Minecraft mc = Minecraft.getInstance();
                int guiW = mc.getWindow().getGuiScaledWidth();
                int guiH = mc.getWindow().getGuiScaledHeight();
                io.github.humbleui.skija.Image bd = BackdropBlurCapture.getBlurredImage(24f);
                if (bd == null && !loggedBlurUnavailable) {
                    loggedBlurUnavailable = true;
                    QplayerModClient.LOGGER.warn("[ClockWidget] backdrop blur unavailable");
                }
                if (bd != null) {
                    float scale = (float) scaleValue();
                    // 卡片在屏幕上的左上角 = 表盘框左上角（画布平移 M 与贴图回退 M 相抵）
                    float gx = hudX() + (cx - dial / 2f) * scale;
                    float gy = hudY() + (cy - dial / 2f) * scale;
                    float imgW = bd.getWidth(), imgH = bd.getHeight();
                    c.drawImageRect(bd,
                            Rect.makeXYWH(gx * imgW / guiW, gy * imgH / guiH,
                                    dial * scale * imgW / guiW, dial * scale * imgH / guiH),
                            Rect.makeXYWH(cx - dial / 2f, cy - dial / 2f, dial, dial),
                            io.github.humbleui.skija.SamplingMode.LINEAR, null, true);
                    painted = true;
                }
                if (dyn) {
                    if (fluidCtx == null) fluidCtx = GpuContextHelper.getOrCreateContext();
                    if (fluidBg == null) {
                        fluidBg = new com.ylmac.adapt.features.music.lyric.skia.FluidBackground(System.nanoTime());
                    }
                    com.ylmac.adapt.features.music.PlayerController pc = QplayerScreen.getPlayerController();
                    byte[] cover = pc == null ? null : pc.coverBytes.peek();
                    String key = pc == null ? "" : (String.valueOf(pc.title.peek()) + "|" + pc.artist.peek());
                    if (!key.equals(fluidTrackKey)) fluidTrackKey = key;
                    if (fluidCtx != null) {
                        Paint layer = new Paint().setColor(0x8C000000);
                        int s2 = c.save();
                        try {
                            c.saveLayer(Rect.makeXYWH(cx - dial / 2f, cy - dial / 2f, dial, dial), layer);
                            c.translate(cx - dial / 2f, cy - dial / 2f);
                            fluidBg.render(c, fluidCtx, 1f, dial, dial, cover, fluidTrackKey,
                                    System.nanoTime(), false);
                            c.restoreToCount(s2 + 1);
                            painted = true;
                        } finally {
                            c.restoreToCount(s2);
                            layer.close();
                        }
                    }
                }
                if (!painted) {
                    paint.setColor(dyn ? 0x6E000000 : bg);
                    c.drawPath(screenShape, paint);
                }
            } catch (Throwable t) {
                QplayerModClient.LOGGER.warn("[ClockWidget] modern card failed: {}", t.toString());
            } finally {
                c.restoreToCount(s1);
            }
        } finally {
            shape.close();
            screenShape.close();
        }
    }

    private static ClockWidgetArt.Vec[] dialPaths(Face face) {
        return switch (face) {
            case SCALLOP -> ClockWidgetArt.DIAL_SCALLOP;
            case NUMERAL -> ClockWidgetArt.DIAL_NUMERAL;
            case DAY -> ClockWidgetArt.DIAL_DAY;
            case CLOVER -> ClockWidgetArt.DIAL_CLOVER;
        };
    }

    private static ClockWidgetArt.Hand hourHand(Face face) {
        return switch (face) {
            case SCALLOP -> ClockWidgetArt.SCALLOP_HOUR;
            case DAY -> ClockWidgetArt.DAY_HOUR;
            default -> ClockWidgetArt.SIMPLE_HOUR;
        };
    }

    private static ClockWidgetArt.Hand minuteHand(Face face) {
        return face == Face.SCALLOP ? ClockWidgetArt.SCALLOP_MINUTE : ClockWidgetArt.SIMPLE_MINUTE;
    }

    private static ClockWidgetArt.Hand secondHand(Face face) {
        return face == Face.SCALLOP ? ClockWidgetArt.SCALLOP_SECOND : ClockWidgetArt.SIMPLE_SECOND;
    }

    /** 画一条矢量：把 viewport 缩放到 [x,y,x+size] 方框（非等比也照填，与原版 VectorDrawable 一致）。 */
    private void drawVector(Canvas c, ClockWidgetArt.Vec v, float x, float y, float size,
                            float viewport, int color, float strokeWidth) {
        Path path = Path.makeFromSVGString(v.d());
        if (v.evenOdd()) {
            // Skija 的 Path 不可变：换填充规则要拿新对象（DAY 表盘的日期窗口靠 evenOdd 挖洞）
            Path filled = path.makeWithFillMode(io.github.humbleui.skija.PathFillMode.EVEN_ODD);
            path.close();
            path = filled;
        }
        paint.setColor(color);
        if (strokeWidth > 0f) {
            paint.setMode(PaintMode.STROKE);
            paint.setStrokeWidth(strokeWidth);
        } else {
            paint.setMode(PaintMode.FILL);
        }
        int save = c.save();
        c.translate(x, y);
        c.scale(size / viewport, size / viewport);
        c.drawPath(path, paint);
        c.restoreToCount(save);
        path.close();
        paint.setMode(PaintMode.FILL);
        paint.setStrokeWidth(1f);
    }

    /**
     * 画一根指针：绕表盘中心旋转 {@code degrees}，再按 {@link ClockWidgetArt.Hand}
     * 记录的矩形（相对中心、380 坐标系）画矢量。
     */
    private void drawHand(Canvas c, ClockWidgetArt.Hand h, float cx, float cy,
                          float unit, double degrees, int color) {
        Path path = Path.makeFromSVGString(h.d());
        paint.setColor(color);
        if (h.stroked()) {
            paint.setMode(PaintMode.STROKE);
            paint.setStrokeWidth(h.strokeWidth() * unit);
        } else {
            paint.setMode(PaintMode.FILL);
        }
        int save = c.save();
        // Skija 的 Canvas.rotate 只绕原点转（没有 AOSP 的 rotate(deg, px, py)），
        // 所以先平移到圆心、旋转、再平移回去。
        c.translate(cx, cy);
        c.rotate((float) degrees);
        c.translate(-cx, -cy);
        c.translate(cx + h.x0() * unit, cy + h.y0() * unit);
        // 矢量 viewport 缩放到指针矩形（InsetDrawable 的等比缩放）
        c.scale(h.w() * unit / h.vpW(), h.h() * unit / h.vpH());
        c.drawPath(path, paint);
        c.restoreToCount(save);
        path.close();
        paint.setMode(PaintMode.FILL);
        paint.setStrokeWidth(1f);
    }

    /**
     * DAY 面的日期数字芯片：尺寸 0.22×0.15 表盘、左边距 0.345 表盘（原版
     * {@code setViewLayoutMargin(day_text_clock, MARGIN_LEFT, 0.345*dial)}）、
     * 文字 "dd"，字号往下试到刚好放进芯片（原版 {@code dmg.a} 二分搜索）。
     */
    private void drawDayChip(Canvas c, float dial, float cx, float cy,
                             int chipColor, int textColor) {
        float chipW = 0.22f * dial;
        float chipH = 0.15f * dial;
        float pad = 0.018f * dial;
        // 居中 + 左边距（FrameLayout layout_gravity=center + leftMargin）
        float left = cx - chipW / 2f + 0.345f * dial;
        float top = cy - chipH / 2f;

        paint.setMode(PaintMode.FILL);
        paint.setColor(chipColor);
        c.drawRect(io.github.humbleui.types.Rect.makeXYWH(left, top, chipW, chipH), paint);

        String text = String.format(Locale.ROOT, "%02d", LocalDate.now().getDayOfMonth());
        float maxW = chipW - pad * 2f, maxH = chipH - pad * 2f;
        float size = chipH;   // 显示像素
        RiseFont f = RiseFont.main(size * 2f, RiseFont.Weight.MEDIUM);
        while (size > 4f && (f.getVectorWidth(text) > maxW || f.height() > maxH)) {
            size -= 1f;
            f = RiseFont.main(size * 2f, RiseFont.Weight.MEDIUM);
        }
        float tw = f.getVectorWidth(text);
        f.drawVector(c, text, left + (chipW - tw) / 2f, cy - f.height() * 0.5f, textColor);
    }

    /**
     * 扇贝面的日期环（{@code csb.b()} SCALLOP 分支）。
     * <p>
     * 原版把"ccc d"（周三 9）画进一张表盘大小的位图，再把位图当作<b>秒针</b>。
     * 因此文字与秒点始终关于圆心对称，并跟着秒一起转。这里直接在弧上排版，
     * 得到同一结果：半径 = 中心×14/19，字号 = 表盘×0.1，字距 -0.02。
     * <p>
     * 字体按码点回退（product_sans_medium → HarmonyOS CJK → 系统字体）：
     * 原版用 google-sans，中日韩靠 Android 的系统字体回退，这里补上同样的链条，
     * 否则中文日期会画成豆腐块（实测）。
     */
    private void drawDateRing(Canvas c, float dial, float cx, float cy,
                              double seconds, int color) {
        float size = dial * 0.1f;
        if (size < 6f) return;
        String text = DateTimeFormatter.ofPattern("ccc d", Locale.getDefault())
                .format(LocalDate.now());
        if (text.isEmpty()) return;

        int n = text.codePointCount(0, text.length());
        if (n == 0) return;
        Font[] glyphFonts = new Font[n];
        short[] glyphs = new short[n];
        float[] adv = new float[n];
        float spacing = -0.02f * size;

        int i = 0;
        float total = 0f;
        for (int idx = 0; idx < text.length(); ) {
            int cp = text.codePointAt(idx);
            int len = Character.charCount(cp);
            Font f = fontForCodepoint(cp, size);
            glyphFonts[i] = f;
            glyphs[i] = f.getUTF32Glyph(cp);
            adv[i] = f.measureTextWidth(text.substring(idx, idx + len));
            total += adv[i] + spacing;
            i++;
            idx += len;
        }
        if (total <= 0f) return;

        float radius = dial * 14f / 19f / 2f;
        // 文字中心：秒点位置 + 180°。秒点在指针 drawable 的顶部 → 零秒时在 12 点方向，
        // 所以角度从"12 点方向"起算、顺时针为正。
        double centerRad = Math.toRadians(180.0 + seconds * 6.0);
        double halfArc = total / 2.0 / radius;      // 半个文字弧长对应的圆心角

        RSXform[] xforms = new RSXform[n];
        double dist = 0.0;
        for (int k = 0; k < n; k++) {
            // ⚠ 弧长用的是字形**左边缘**的位置，不是中心 —— Android 的
            //   drawTextOnPath 就是把每个字形的 origin 摆在路径上（Align.CENTER
            //   只影响整串的起点）。之前按"中心"摆，每个字形都被右移了半个字宽，
            //   相邻字互相压住（用户："扇贝形状文字有点堆叠"）。
            double along = dist;
            // 从右往左排（屏幕上看是从左到右）：角度递减
            double phi = centerRad + halfArc - along / radius;
            float gx = cx + (float) (Math.sin(phi) * radius);
            float gy = cy - (float) (Math.cos(phi) * radius);
            // 基线方向 = 角度递减方向的切线；字形"上"指向圆心
            xforms[k] = RSXform.makeFromRadians(1f, (float) (phi + Math.PI), gx, gy, 0f, 0f);
            dist += adv[k] + spacing;
        }

        // 相邻同字体的字形合并成一段 TextBlob（TextBlob 只接受单一字体）
        paint.setMode(PaintMode.FILL);
        paint.setColor(color);
        int runStart = 0;
        for (int k = 1; k <= n; k++) {
            if (k < n && glyphFonts[k] == glyphFonts[runStart]) continue;
            short[] runGlyphs = java.util.Arrays.copyOfRange(glyphs, runStart, k);
            RSXform[] runXforms = java.util.Arrays.copyOfRange(xforms, runStart, k);
            TextBlob blob = TextBlob.makeFromRSXform(runGlyphs, runXforms, glyphFonts[runStart]);
            c.drawTextBlob(blob, 0f, 0f, paint);
            blob.close();
            runStart = k;
        }
    }

    /** 日期环的逐码点字体回退（与 {@link RiseFont} 同一套策略）。 */
    private Font fontForCodepoint(int cp, float size) {
        Font cached = cpFontCache.get(cp);
        if (cached == null) {
            cached = pickBaseFont(cp);
            cpFontCache.put(cp, cached);
        }
        if (cached != null) {
            cached.setSize(size);
        }
        return cached;
    }

    private Font pickBaseFont(int cp) {
        Font latin = baseFont("/assets/adaptclient/qml/fonts/product_sans_medium.ttf");
        if (latin != null && covers(latin.getTypeface(), cp)) return latin;
        Font cjk = baseFont("/assets/adaptclient/qml/fonts/HarmonyOS_Sans_SC_Medium.ttf");
        if (cjk != null && covers(cjk.getTypeface(), cp)) return cjk;
        try {
            var mgr = io.github.humbleui.skija.FontMgr.getDefault();
            if (mgr != null) {
                for (String tag : BCP47) {
                    Typeface t = mgr.matchFamilyStyleCharacter(
                            null, io.github.humbleui.skija.FontStyle.NORMAL, new String[]{tag}, cp);
                    if (t != null && covers(t, cp)) return new Font(t, 12f);
                }
            }
        } catch (Throwable ignored) {
        }
        return latin != null ? latin : cjk;
    }

    private static boolean covers(Typeface t, int cp) {
        return t != null && t.getUTF32Glyph(cp) != 0;
    }

    /** 一次性加载的字体文件（基础尺寸 12，用之前 setSize）。 */
    private Font baseFont(String resource) {
        if (baseFonts.containsKey(resource)) return baseFonts.get(resource);
        Font loaded = null;
        try (java.io.InputStream in = ClockWidgetOverlay.class.getResourceAsStream(resource)) {
            if (in != null) {
                Typeface tf = io.github.humbleui.skija.FontMgr.getDefault()
                        .makeFromData(Data.makeFromBytes(in.readAllBytes()));
                loaded = new Font(tf, 12f);
            }
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[ClockWidget] font {} failed: {}", resource, t.toString());
        }
        baseFonts.put(resource, loaded);
        return loaded;
    }

    // ───────────────────────── 主题色 ─────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, String> themeScheme() {
        try {
            Object inst = io.github.timer_err.qml4j.runtime.color.StyleManager.__instance();
            if (inst instanceof io.github.timer_err.qml4j.runtime.color.StyleManager sm) {
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
