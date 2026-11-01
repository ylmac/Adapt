package com.ylmac.adapt.render;

import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.features.module.ModuleRegistry;
import com.ylmac.adapt.features.module.impl.render.ScoreBoardModule;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 积分榜（侧栏计分板）—— Rise 6.9.5 {@code ScoreBoard} 的复刻。
 *
 * <h3>Rise 的做法（逐行对照 {@code module/impl/render/ScoreBoard.java}）</h3>
 * <ul>
 *   <li>tick 里读侧栏目标（{@code getScoreObjective()}），取排序后的分数列表，
 *       过滤 {@code #} 开头的隐藏行，最多 15 行；maxWidth = 标题与所有行的最大宽度。</li>
 *   <li>渲染分四个队列：
 *     <ul>
 *       <li>REGULAR：可选外框（{@code roundedOutlineGradientRectangle}，默认关）</li>
 *       <li>BLUR：圆角矩形当模糊遮罩（毛玻璃底）</li>
 *       <li>BLOOM：同一圆角矩形用辉光色画一遍（外发光）</li>
 *       <li>REGULAR,1：黑色 alpha 100（或主题色 alpha 60）圆角底 + 文字</li>
 *     </ul>
 *   </li>
 *   <li>几何：{@code n4 = fontHeight*行数 + 3}；面板 {@code maxWidth+12 × n4+fontHeight+3}；
 *       标题居中于 {@code y+4.5}，行从 {@code y+4.5+fontHeight} 起、每行 {@code fontHeight}，
 *       左边距 6；圆角 5。</li>
 * </ul>
 * <p>
 * 数据源用 26.2 的 {@code Scoreboard.listPlayerScores(objective)}（= 原版侧栏同一份数据，
 * 排序/过滤/上限 15 与 {@code Hud.displayScoreboardSidebar} 一致），原版侧栏由
 * {@link com.ylmac.adapt.injection.mixins.HudScoreboardMixin} 在本模块开启时取消渲染。
 */
public final class ScoreBoardOverlay implements HudElement, MovableHud {

    private static final ScoreBoardOverlay INSTANCE = new ScoreBoardOverlay();

    public static ScoreBoardOverlay get() {
        return INSTANCE;
    }

    /** Rise：{@code fontHeight = 9}（原版字体行高），我们用 RiseFont(18) 的行高。 */
    /** Rise：{@code padding = 3}（实际用在 n4 的 +3 与面板的 +3）。 */
    private static final float PAD = 3f;
    /** Rise：圆角（informationType == "Rise" → 5）。 */
    private static final float RADIUS = 5f;
    /**
     * 画布外扩（像素）：投影画在面板**外面**，画布必须留出空间，
     * 否则被画布裁掉 → 看不见阴影（用户："还是没有阴影"）。
     * 3σ + 余量，σ = 6。
     */
    private static final int SHADOW_MARGIN = 24;

    private MinecraftSurfaceBackend surf;
    private String surfaceKey = "";
    private final Paint paint = new Paint();

    private float posX, posY;
    private boolean posSet;

    // ── 数据（tick 更新，渲染读）──
    private net.minecraft.network.chat.Component titleComponent = net.minecraft.network.chat.Component.empty();
    private final List<net.minecraft.network.chat.Component[]> rows = new ArrayList<>();  // {名称, 分值}
    private final List<String[]> rowPlain = new ArrayList<>();   // 纯文本（测量/兜底）
    private float maxWidth;

    private boolean registered;
    private boolean loggedBlurUnavailable;

    private ScoreBoardOverlay() {
    }

    private static ScoreBoardModule module() {
        for (ModuleRegistry.ModuleEntry e : ModuleRegistry.getAllModules()) {
            if (e.linkedRealModule() instanceof ScoreBoardModule m) {
                return m;
            }
        }
        return null;
    }

    private static boolean isEnabled() {
        ScoreBoardModule m = module();
        return m != null && m.isEnabled();
    }

    private static boolean outline() {
        ScoreBoardModule m = module();
        return m != null && m.outline();
    }

    private static boolean blurColor() {
        ScoreBoardModule m = module();
        return m != null && m.blurColor();
    }

    public void onModuleStateChanged() {
        surfaceKey = "";
    }

    public void registerHud() {
        if (registered) return;
        registered = true;
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.parse("adaptclient:scoreboard"), this);
    }

    // ───────────────────────── 数据 ─────────────────────────

    /** 每帧读取侧栏数据（等价 Rise 的 TickEvent；开销很小，直接每帧读）。 */
    private void updateData() {
        Minecraft mc = Minecraft.getInstance();
        rows.clear();
        rowPlain.clear();
        titleComponent = net.minecraft.network.chat.Component.empty();
        maxWidth = 0f;
        if (mc.level == null) return;
        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
        if (objective == null) return;

        // Rise 用的是**原版字体**（fontRendererObj），位置/阴影/§颜色/特殊符号全部由它负责
        net.minecraft.client.gui.Font font = mc.font;
        NumberFormat fmt = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);

        List<PlayerScoreEntry> entries = scoreboard.listPlayerScores(objective).stream()
                .filter(s -> !s.isHidden())
                .sorted(Comparator.comparing(PlayerScoreEntry::value).reversed()
                        .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER))
                .limit(15L)
                .toList();

        titleComponent = objective.getDisplayName();
        maxWidth = font.width(titleComponent);
        for (PlayerScoreEntry e : entries) {
            PlayerTeam team = scoreboard.getPlayersTeam(e.owner());
            // 保留 Component（样式/§ 颜色/特殊符号），不要 getString() 之后再拼
            Component name = PlayerTeam.formatNameForTeam(team, e.ownerName());
            Component score = e.formatValue(fmt);
            rows.add(new Component[]{name, score});
            rowPlain.add(new String[]{name.getString(), score.getString()});
            float w = font.width(name) + font.width(": " + score.getString());
            if (w > maxWidth) maxWidth = w;
        }
        maxWidth += 2f;   // Rise: maxWidth += 2
    }

    // ───────────────────────── MovableHud ─────────────────────────

    @Override
    public String hudId() {
        return "scoreboard";
    }

    @Override
    public String hudLabel() {
        return "积分榜";
    }

    @Override
    public boolean hudShowLabel() {
        return false;
    }

    @Override
    public float hudX() {
        if (posSet) return posX;
        Minecraft mc = Minecraft.getInstance();
        return Math.max(2, mc.getWindow().getGuiScaledWidth() - hudW() - 12);
    }

    @Override
    public float hudY() {
        if (posSet) return posY;
        Minecraft mc = Minecraft.getInstance();
        return Math.max(2, mc.getWindow().getGuiScaledHeight() / 2f - hudH() / 3f);
    }

    @Override
    public float hudW() {
        return maxWidth + 12f;
    }

    @Override
    public float hudH() {
        // 与原版字体一致的行高（Rise: fontHeight = 9）
        float fh = Minecraft.getInstance().font.lineHeight;
        return fh * rows.size() + PAD + fh + PAD;
    }

    @Override
    public boolean hudVisible() {
        return isEnabled() && !rows.isEmpty() && Minecraft.getInstance().level != null;
    }

    public static boolean wantsBackdrop() {
        return get().hudVisible() && ScoreBoardModule.blurEnabled();
    }

    @Override
    public void setHudPos(float x, float y) {
        posX = x;
        posY = y;
        posSet = true;
    }

    /** Rise 的积分榜没有缩放设置（原版字体不可缩放）。 */
    public double hudScale() {
        return 1.0;
    }

    // ───────────────────────── 渲染 ─────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        Probe.begin("hud.scoreboard");
        try {
            updateData();
            if (!hudVisible()) return;
            render(g);
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[ScoreBoard] render error: {}", t.toString());
        } finally {
            Probe.end("hud.scoreboard");
        }
    }

    private void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.Font font = mc.font;
        float fh = font.lineHeight;                       // 原版行高（Rise: fontHeight = 9）
        float w = maxWidth + 12f;
        float h = fh * rows.size() + PAD + fh + PAD;      // Rise: n4 + fontHeight + 3

        int dw = Math.max(1, Math.round(w)) + 2 * SHADOW_MARGIN;
        int dh = Math.max(1, Math.round(h)) + 2 * SHADOW_MARGIN;
        String key = dw + "x" + dh;
        float deviceScale = (float) mc.getWindow().getGuiScale();
        if (surf == null || !key.equals(surfaceKey)) {
            if (surf != null) {
                try { surf.dispose(); } catch (Exception ignored) { }
                surf = null;
            }
            SkijaNativeFix.preload();
            surf = new MinecraftSurfaceBackend("scoreboard", dw, dh, deviceScale);
            surfaceKey = key;
        } else {
            surf.ensureDeviceScale(deviceScale);
        }

        Canvas canvas = surf.acquireCanvas();
        if (canvas == null) return;
        canvas.translate(SHADOW_MARGIN, SHADOW_MARGIN);   // 面板坐标不变，四周留给投影
        Map<String, String> sc = themeScheme();

        int bg = blurColor()
                ? withAlpha(col(sc, "secondary", 0xFF625B71), 60)
                : 0x64000000;                       // Rise: new Color(0,0,0,100)

        Image backdrop = null;
        if (ScoreBoardModule.blurEnabled()) {
            backdrop = BackdropBlurCapture.getBlurredImage();
            if (backdrop == null && !loggedBlurUnavailable) {
                loggedBlurUnavailable = true;
                AdaptClient.LOGGER.warn("[ScoreBoard] backdrop blur unavailable");
            }
        }

        float radius = RADIUS;
        try {
            // 1) 外发光 = **黑色阴影**（Rise 的 BLOOM 队列用 Themes.rE() = Color(0,0,0,190)，
            //    经 bloom 模糊后就是一圈暗色投影 —— 不是主题色光晕！之前画成主题色，
            //    用户看到的"神秘主题色 bloom"就是这么来的）
            try {
                ImageFilter blur = CachedBlur.of(6f, FilterTileMode.DECAL);
                Paint glowPaint = new Paint().setImageFilter(blur);
                canvas.saveLayer(Rect.makeXYWH(-24, -24, w + 48, h + 48), glowPaint);
                paint.setColor(0xC0000000);   // Rise rE: 黑 alpha 190
                canvas.drawRRect(RRect.makeXYWH(1, 1, w - 2, h - 2, radius), paint);
                canvas.restore();
                glowPaint.close();
            } catch (Throwable ignored) {
            }

            // 2) 毛玻璃底 + 圆角裁切（Rise 的 BLUR 队列遮罩就是圆角矩形 —— 必须裁，
            //    否则模糊图是方角，面板圆角处会露出方形背景，即"圆角裁切失败"）
            int save = canvas.save();
            canvas.clipRRect(RRect.makeXYWH(0, 0, w, h, radius),
                    io.github.humbleui.skija.ClipMode.INTERSECT, true);
            if (backdrop != null) {
                drawBackdrop(canvas, backdrop, 0, 0, w, h);
            }
            paint.setColor(bg);
            canvas.drawRRect(RRect.makeXYWH(0, 0, w, h, radius), paint);
            canvas.restoreToCount(save);

            // 3) 可选外框（Rise: roundedOutlineGradientRectangle）
            if (outline()) {
                paint.setMode(io.github.humbleui.skija.PaintMode.STROKE);
                paint.setStrokeWidth(1f);
                paint.setColor(col(sc, "primary", 0xFFD0BCFF));
                canvas.drawRRect(RRect.makeXYWH(0.5f, 0.5f, w - 1f, h - 1f, radius), paint);
                paint.setMode(io.github.humbleui.skija.PaintMode.FILL);
            }
        } finally {
            // backdrop 由 BackdropBlurCapture 持有（每帧缓存），不要 close
        }
        surf.present();

        int texW = surf.getDisplayWidth(), texH = surf.getDisplayHeight();
        int x = Math.round(hudX()), y = Math.round(hudY());
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x - SHADOW_MARGIN, y - SHADOW_MARGIN);
        g.blit(surf.blitPipeline(), surf.getTextureId(),
                0, 0, 0f, 0f, dw, dh, texW, texH, texW, texH);
        pose.popMatrix();

        // 4) 文字：原版字体 + 1px 投影（Rise 的 fontRendererObj.b(...)）——
        //    位置/行距/§颜色/特殊符号全部与原版一致
        float ty = y + 4.5f;
        float tw = font.width(titleComponent);
        g.text(font, titleComponent.getVisualOrderText(),
                Math.round(x + 6f + maxWidth / 2f - tw / 2f), Math.round(ty), 0xFFFFFFFF, true);
        float ly = ty + fh;
        for (int i = 0; i < rows.size(); i++) {
            Component[] row = rows.get(i);
            g.text(font, row[0].getVisualOrderText(), Math.round(x + 6f), Math.round(ly), 0xFFFFFFFF, true);
            float sw = font.width(row[1]);
            g.text(font, row[1].getVisualOrderText(),
                    Math.round(x + 6f + maxWidth - sw - 2f), Math.round(ly), 0xFFFFFFFF, true);
            ly += fh;
        }
    }

    /** 毛玻璃底：把本帧截取+模糊的帧缓冲按面板矩形画进来（与模块列表同一套映射）。 */
    private void drawBackdrop(Canvas canvas, Image img, float dx, float dy, float dw, float dh) {
        Minecraft mc = Minecraft.getInstance();
        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();
        // 面板的 GUI 坐标（blit 在 (hudX, hudY)，1:1）
        float gx = Math.round(hudX()) + dx;
        float gy = Math.round(hudY()) + dy;
        float imgW = img.getWidth(), imgH = img.getHeight();
        try {
            canvas.drawImageRect(img,
                    Rect.makeXYWH(gx * imgW / guiW, gy * imgH / guiH,
                            dw * imgW / guiW, dh * imgH / guiH),
                    Rect.makeXYWH(dx, dy, dw, dh),
                    io.github.humbleui.skija.SamplingMode.LINEAR, null, true);
        } catch (Throwable ignored) {
        }
    }

    private static int withAlpha(int rgb, int a) {
        int alpha = Math.max(0, Math.min(255, a));
        return (alpha << 24) | (rgb & 0xFFFFFF);
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
