package com.ylmac.adapt.render;

import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.features.module.impl.render.ChatModule;
import com.ylmac.adapt.injection.mixins.ChatComponentAccessor;
import com.ylmac.adapt.injection.mixins.ChatScreenAccessor;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天框（HUD）—— Rise 6.9.5 {@code Chat} 的复刻。
 *
 * <h3>Rise 的做法（逐行对照 {@code Chat.renderChat()/renderInputBox()}）</h3>
 * <ul>
 *   <li>字体就是<b>原版字体</b>（{@code mc.fontRendererObj}）：{@code chatFont.b(text, x, y, color)}
 *       —— {@code b()} 带 1px 投影。位置/行距/§ 颜色/特殊符号全部由它负责。</li>
 *   <li>面板：x = {@code Themes.qd() = 4.5}，底边 = {@code guiH - 30}，
 *       高度 = 行高 × 可见行数 + 7，圆角 = {@code min(高度/5, 6)}；
 *       消息自下而上，起点 y = 面板底 - 1.5 - 行高，每行 -行高，左边距 5。</li>
 *   <li>背景三队列：REGULAR 圆角底（{@code Themes.rK()} = 黑 alpha 110/150）、
 *       BLOOM（{@code Themes.rE()} = <b>黑 alpha 190</b>，模糊后是暗色投影）、
 *       BLUR（圆角矩形遮罩 → 毛玻璃，必须按圆角裁切）。</li>
 *   <li>输入框：钉在屏幕底部（底边 = {@code guiH - 4.5}），宽度 = 聊天宽度，
 *       高度 = 行高 + 7.5，圆角 6，文字白色，开合动画 EASE_OUT_ELASTIC。</li>
 * </ul>
 * 面板/输入框底由 Skia 画（毛玻璃 + 投影），文字用 {@code GuiGraphicsExtractor}
 * 走原版字体 —— 这样位置与 Rise 完全一致。
 */
public final class ChatOverlay implements HudElement, MovableHud {

    private static final ChatOverlay INSTANCE = new ChatOverlay();

    public static ChatOverlay get() {
        return INSTANCE;
    }

    /** Rise：{@code d2 = min(高度/5, 6)}。 */
    private static final float MAX_RADIUS = 6f;
    /** 面板内边距（Rise 消息从 x+5 起）。 */
    private static final float PAD = 5f;
    /** Rise：面板底距屏幕底 30px（{@code guiH - 10 - 20 - 高度}）。 */
    private static final float BOTTOM_MARGIN = 30f;
    /** Rise：输入框底距屏幕底 4.5px（{@code Themes.qd()}）。 */
    private static final float INPUT_MARGIN = 4.5f;
    /** 画布外扩：投影画在面板/输入框外面，必须留空间，否则被画布裁掉。 */
    private static final int SHADOW_MARGIN = 24;

    private MinecraftSurfaceBackend surf;
    private String surfaceKey = "";
    private final Paint paint = new Paint();

    private float posX, posY;
    private boolean posSet;

    /** 面板高度动画（Rise 的 heightAnimation：每帧向目标插值）。 */
    private float heightAnim;
    private long lastFrameNs;
    /** 输入框开合动画（Rise 的 openAnimation：0→1，EASE_OUT_ELASTIC 回弹）。 */
    private long openStartNs;
    private boolean wasOpen;

    private boolean registered;
    private boolean loggedBlurUnavailable;
    private boolean subscribed;

    // 本帧布局快照（命中测试与绘制必须同一套坐标）
    private final List<GuiMessage.Line> lastVisible = new ArrayList<>();
    private float lastX, lastY, lastH, lastLineH;
    private boolean lastOpen;

    private ChatOverlay() {
    }

    private static ChatModule module() {
        return ChatModule.get();
    }

    private static boolean isEnabled() {
        return ChatModule.overlayEnabled();
    }

    /** 开着背景、而且这一帧真有字要画时才采世界模糊。 */
    public static boolean wantsBackdrop() {
        if (!isEnabled() || Minecraft.getInstance().level == null || lines().isEmpty()) return false;
        ChatModule m = module();
        return m == null || m.background();
    }

    public void onModuleStateChanged() {
        surfaceKey = "";
        heightAnim = 0f;
        if (isEnabled() && !subscribed) {
            com.ylmac.adapt.features.event.bus.EventBus.INSTANCE.subscribe(this);
            subscribed = true;
        } else if (!isEnabled() && subscribed) {
            com.ylmac.adapt.features.event.bus.EventBus.INSTANCE.unsubscribe(this);
            subscribed = false;
        }
    }

    /** 点击派发：自己命中测试后调原版的 handleGameClickEvent（Rise 也是自己处理）。 */
    @com.ylmac.adapt.features.event.bus.EventHandler
    public void onMouse(com.ylmac.adapt.features.event.impl.MousePressEvent event) {
        if (event.getAction() != 1 || event.getButton() != 0) return;
        if (!isEnabled() || !lastOpen || lastVisible.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof ChatScreen)) return;
        double mx = mc.mouseHandler.getScaledXPos(mc.getWindow());
        double my = mc.mouseHandler.getScaledYPos(mc.getWindow());
        Style style = pickStyle(mc.font, (int) mx, (int) my);
        if (style != null && style.getClickEvent() != null) {
            com.ylmac.adapt.injection.mixins.ScreenInvoker.adapt$handleGameClick(
                    style.getClickEvent(), mc, mc.gui.screen());
            event.cancel();
        }
    }

    /** 鼠标位置下的文本样式（按本帧布局逐行扫描）。 */
    private Style pickStyle(Font font, int mouseX, int mouseY) {
        var finder = new net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder(
                font, mouseX, mouseY);
        finder.includeInsertions(Minecraft.getInstance().hasShiftDown());
        float ty = lastY + lastH - 1.5f - lastLineH;
        for (int i = 0; i < lastVisible.size(); i++) {
            finder.accept(net.minecraft.client.gui.TextAlignment.LEFT,
                    Math.round(lastX + PAD), Math.round(ty), lastVisible.get(i).content());
            ty -= lastLineH;
        }
        return finder.result();
    }

    public void registerHud() {
        if (registered) return;
        registered = true;
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.parse("adaptclient:chat"), this);
    }

    // ───────────────────────── MovableHud ─────────────────────────

    @Override
    public String hudId() {
        return "chat";
    }

    @Override
    public String hudLabel() {
        return "聊天框";
    }

    @Override
    public boolean hudShowLabel() {
        return false;
    }

    @Override
    public float hudX() {
        return posSet ? posX : 4.5f;      // Rise: Themes.qd()
    }

    @Override
    public float hudY() {
        if (posSet) return posY;
        Minecraft mc = Minecraft.getInstance();
        return Math.max(2, mc.getWindow().getGuiScaledHeight() - hudH() - BOTTOM_MARGIN);
    }

    @Override
    public float hudW() {
        return widthPx();
    }

    @Override
    public float hudH() {
        return Math.max(heightAnim, minPanelHeight());
    }

    @Override
    public boolean hudVisible() {
        return isEnabled() && Minecraft.getInstance().level != null && !lines().isEmpty();
    }

    @Override
    public void setHudPos(float x, float y) {
        posX = x;
        posY = y;
        posSet = true;
    }

    public double hudScale() {
        return 1.0;                        // Rise 的聊天没有缩放设置（原版字体不可缩放）
    }

    private static float widthPx() {
        ChatModule m = module();
        return m == null ? 320f : m.width();
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static float minPanelHeight() {
        return font().lineHeight + 7f;
    }

    private static List<GuiMessage.Line> lines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return List.of();
        var chat = mc.gui.hud.getChat();
        if (chat == null) return List.of();
        return ((ChatComponentAccessor) chat).adapt_getTrimmedMessages();
    }

    /** 输入框开合进度：0→1，带回弹（easeOutBack，近似 Rise 的 EASE_OUT_ELASTIC）。 */
    private float openAnim() {
        long now = System.nanoTime();
        if (!wasOpen) {
            openStartNs = now;
            wasOpen = true;
        }
        float t = Math.min(1f, (now - openStartNs) / 350_000_000f);
        float c = 1.70158f;
        float u = t - 1f;
        return 1f + (c + 1f) * u * u * u + c * u * u;
    }

    // ───────────────────────── 渲染 ─────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!isEnabled()) return;
        Probe.begin("hud.chat");
        try {
            render(g);
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[Chat] render error: {}", t.toString());
        } finally {
            Probe.end("hud.chat");
        }
    }

    private void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        List<GuiMessage.Line> all = lines();
        if (all.isEmpty()) return;

        Font font = mc.font;
        float lineH = font.lineHeight;               // 原版行高（Rise: chatFont.height() = 9）
        // 消息用 **Rise 的布局**（用户要求）：x = 面板左 + 5，起点 = 面板底 - 1.5 - 行高，
        // 每行 -行高、自下而上。点击/悬停由我们自己按同一套坐标做命中测试
        // （见 {@link #pickStyle}），不依赖原版那套坐标。
        var chat = mc.gui.hud.getChat();
        boolean open = mc.gui.screen() instanceof ChatScreen;
        if (!open) wasOpen = false;
        ChatModule m = module();
        int fadeMs = m == null ? 5000 : m.fadeSpeed();
        boolean bg = m == null || m.background();

        // 每行的淡出 alpha（Rise: messageDisappearanceSpeed 到点后逐行消失；打开时常显）
        // ⚠ 必须是 HUD 的 tick 计数：GuiMessage.addedTime = mc.gui.hud.getGuiTicks()
        //   （26.2 ChatComponent L280）。之前用 System.currentTimeMillis()/50（~350 亿）
        //   去减它 → age 巨大 → alpha=0 → 关闭聊天时所有消息都被过滤掉，
        //   于是"收到新消息不弹出"（打开聊天界面时 alpha 恒 1，所以那时能看见）。
        int tickCount = mc.gui.hud.getGuiTicks();
        float maxAge = Math.max(1f, fadeMs / 50f);

        // 显示窗口：行数上限（打开 = Open Height，关闭 = Max Closed Height），
        // 打开时按聊天滚动位置（26.2 的 chatScrollbarPos）偏移起始行
        // 打开时用**原版每页行数**（getLinesPerPage）——滚动条与 chatScrollbarPos 的
        // 夹取范围都是按它算的，用别的值会导致"滚动条位置是错的/明明没几条却像有很多"。
        int cap = Math.max(1, m == null ? 130 : m.maxClosedHeight());
        int maxLines = open
                ? Math.max(1, chat.getLinesPerPage())
                : Math.max(1, (int) ((cap - 7f) / lineH));
        int start = 0;
        if (open) {
            start = ((ChatComponentAccessor) mc.gui.hud.getChat()).adapt_getChatScrollbarPos();
            start = Math.max(0, Math.min(start, Math.max(0, all.size() - 1)));
        }

        List<GuiMessage.Line> visible = new ArrayList<>();
        List<Float> alphas = new ArrayList<>();
        for (int i = start; i < all.size() && visible.size() < maxLines; i++) {
            GuiMessage.Line line = all.get(i);
            float age = Math.max(0, tickCount - line.addedTime());
            float a = open ? 1f : Math.max(0f, 1f - age / maxAge);
            if (a <= 0.02f) continue;
            visible.add(line);
            alphas.add(a);
        }
        if (visible.isEmpty()) {
            heightAnim = 0f;
            return;
        }

        // 面板高度：行高 × 行数 + 7（Rise: lineHeight*行数 + 7）
        float targetH = lineH * visible.size() + 7f;
        long now = System.nanoTime();
        float dtSec = lastFrameNs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;
        heightAnim += (targetH - heightAnim) * Math.min(1f, dtSec * 12f);

        float w = widthPx();
        float h = Math.max(heightAnim, minPanelHeight());
        float radius = Math.min(h / 5f, MAX_RADIUS);

        // 画布：面板贴顶；打开时底部再放输入框（底边 = 面板底 + (30 - 4.5)）
        float inputBoxH = open ? lineH + 7.5f : 0f;
        float inputBottom = open ? h + (BOTTOM_MARGIN - INPUT_MARGIN) : 0f;
        float totalH = open ? inputBottom : h;
        int dw = Math.max(1, Math.round(w)) + 2 * SHADOW_MARGIN;
        int dh = Math.max(1, Math.round(totalH)) + 2 * SHADOW_MARGIN;
        String key = dw + "x" + dh;
        float deviceScale = (float) mc.getWindow().getGuiScale();
        if (surf == null || !key.equals(surfaceKey)) {
            if (surf != null) {
                try { surf.dispose(); } catch (Exception ignored) { }
                surf = null;
            }
            SkijaNativeFix.preload();
            surf = new MinecraftSurfaceBackend("chat_overlay", dw, dh, deviceScale);
            surfaceKey = key;
        } else {
            surf.ensureDeviceScale(deviceScale);
        }

        Canvas canvas = surf.acquireCanvas();
        if (canvas == null) return;
        canvas.translate(SHADOW_MARGIN, SHADOW_MARGIN);   // 面板坐标不变，四周留给投影
        int panelColor = 0x96000000;                     // Rise: Themes.rK() = 黑 150（无模糊时）

        Image backdrop = null;
        if (bg) {
            backdrop = BackdropBlurCapture.getBlurredImage();
            if (backdrop == null && !loggedBlurUnavailable) {
                loggedBlurUnavailable = true;
                AdaptClient.LOGGER.warn("[Chat] backdrop blur unavailable");
            }
        }

        try {
            // 1) 投影（Rise 的 BLOOM 队列：Themes.rE() = 黑 alpha 190，模糊后是暗色阴影）
            try {
                ImageFilter blur = CachedBlur.of(6f, FilterTileMode.DECAL);
                Paint glowPaint = new Paint().setImageFilter(blur);
                canvas.saveLayer(Rect.makeXYWH(-24, -24, w + 48, h + 48), glowPaint);
                paint.setColor(0xC0000000);
                canvas.drawRRect(RRect.makeXYWH(1, 1, w - 2, h - 2, radius), paint);
                canvas.restore();
                glowPaint.close();
            } catch (Throwable ignored) {
            }

            // 2) 毛玻璃 + 底色，**按圆角裁切**（Rise 的 BLUR 遮罩就是圆角矩形）
            int save = canvas.save();
            canvas.clipRRect(RRect.makeXYWH(0, 0, w, h, radius), ClipMode.INTERSECT, true);
            if (backdrop != null) {
                drawBackdrop(canvas, backdrop, 0, 0, w, h);
                panelColor = 0x6E000000;                 // 有模糊时 Rise 用 backgroundAlpha 110
            }
            if (bg) {
                paint.setColor(panelColor);
                canvas.drawRRect(RRect.makeXYWH(0, 0, w, h, radius), paint);
            }
            canvas.restoreToCount(save);

            // 4) 输入框（钉在屏幕底部；开合动画从中心缩放）
            if (open) {
                float boxY = inputBottom - inputBoxH;
                float anim = openAnim();
                float cx = w / 2f, cy = boxY + inputBoxH / 2f;
                int save2 = canvas.save();
                canvas.translate(cx, cy);
                canvas.scale(anim, anim);
                canvas.translate(-cx, -cy);

                try {
                    ImageFilter blur = CachedBlur.of(6f, FilterTileMode.DECAL);
                    Paint glowPaint = new Paint().setImageFilter(blur);
                    canvas.saveLayer(Rect.makeXYWH(-24, boxY - 24, w + 48, inputBoxH + 48), glowPaint);
                    paint.setColor(0xC0000000);
                    canvas.drawRRect(RRect.makeXYWH(1, boxY + 1, w - 2, inputBoxH - 2, MAX_RADIUS), paint);
                    canvas.restore();
                    glowPaint.close();
                } catch (Throwable ignored) {
                }

                int save3 = canvas.save();
                canvas.clipRRect(RRect.makeXYWH(0, boxY, w, inputBoxH, MAX_RADIUS),
                        ClipMode.INTERSECT, true);
                if (backdrop != null) {
                    drawBackdrop(canvas, backdrop, 0, boxY, w, inputBoxH);
                }
                paint.setColor(bg ? 0x6E000000 : 0x96000000);
                canvas.drawRRect(RRect.makeXYWH(0, boxY, w, inputBoxH, MAX_RADIUS), paint);
                canvas.restoreToCount(save3);
                canvas.restoreToCount(save2);
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

        // 5) 文字：原版字体 + 1px 投影（Rise: chatFont.b(...)）——
        //    自下而上，起点 = 面板底 - 1.5 - 行高（Rise 布局）
        float ty = y + h - 1.5f - lineH;
        for (int i = 0; i < visible.size(); i++) {
            int alpha = Math.round(255 * alphas.get(i));
            int color = (alpha << 24) | 0xFFFFFF;
            g.text(font, visible.get(i).content(), Math.round(x + PAD), Math.round(ty), color, true);
            ty -= lineH;
        }

        // 5b) 布局快照 + 悬停反馈（坐标与绘制完全一致；点击见 onMouse）
        lastVisible.clear();
        lastVisible.addAll(visible);
        lastX = x; lastY = y; lastH = h; lastLineH = lineH; lastOpen = open;
        if (open) {
            double mx = mc.mouseHandler.getScaledXPos(mc.getWindow());
            double my = mc.mouseHandler.getScaledYPos(mc.getWindow());
            Style hovered = pickStyle(font, (int) mx, (int) my);
            if (hovered != null) {
                if (hovered.getClickEvent() != null || hovered.getInsertion() != null) {
                    g.requestCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.POINTING_HAND);
                }
                if (hovered.getHoverEvent()
                        instanceof net.minecraft.network.chat.HoverEvent.ShowText show) {
                    g.setTooltipForNextFrame(show.value(), (int) mx, (int) my);
                }
            }
        }

        // 6) 输入文字 + 光标（Rise: 文字白色；原版 EditBox 已取消渲染）
        if (open && mc.gui.screen() instanceof ChatScreen chatScreen) {
            var input = ((ChatScreenAccessor) chatScreen).adapt_getInput();
            if (input != null) {
                float boxY = y + h + (BOTTOM_MARGIN - INPUT_MARGIN) - inputBoxH;
                String value = input.getValue();
                float tx = x + PAD;
                if (!value.isEmpty()) {
                    g.text(font, value, Math.round(tx), Math.round(boxY + 3.5f), 0xFFFFFFFF, false);
                    tx += font.width(value);
                }
                if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
                    g.fill(Math.round(tx + 1), Math.round(boxY + 3.5f), Math.round(tx + 2.5f),
                            Math.round(boxY + inputBoxH - 3.5f), 0xFFFFFFFF);
                }
            }
        }
    }

    /** 毛玻璃底：与模块列表/积分榜同一套映射（本帧共享截帧 → 面板矩形）。 */
    private void drawBackdrop(Canvas canvas, Image img, float dx, float dy, float dw, float dh) {
        Minecraft mc = Minecraft.getInstance();
        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();
        float gx = hudX() + dx;
        float gy = hudY() + dy;
        float imgW = img.getWidth(), imgH = img.getHeight();
        try {
            canvas.drawImageRect(img,
                    Rect.makeXYWH(gx * imgW / guiW, gy * imgH / guiH,
                            dw * imgW / guiW, dh * imgH / guiH),
                    Rect.makeXYWH(dx, dy, dw, dh), SamplingMode.LINEAR, null, true);
        } catch (Throwable ignored) {
        }
    }
}
