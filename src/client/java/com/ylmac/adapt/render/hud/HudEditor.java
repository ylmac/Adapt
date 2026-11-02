package com.ylmac.adapt.render.hud;

import com.ylmac.adapt.render.font.RiseFont;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLScancode;
import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.config.DesktopSettings;
import com.ylmac.adapt.event.bus.EventBus;
import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.KeyPressEvent;
import com.ylmac.adapt.event.impl.MousePressEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ylmac.adapt.compose.screens.music.AdaptScreen;
import com.ylmac.adapt.render.surface.MinecraftSurfaceBackend;
import com.ylmac.adapt.render.surface.SkijaNativeFix;

/**
 * HUD 位置编辑器：按 <b>右 Command</b> 进入，鼠标拖动元素，ESC 退出并保存。
 *
 * <h3>为什么用事件总线而不是 Screen</h3>
 * 编辑器要在<b>世界里</b>（没有 GUI 打开时）工作，而且要让 HUD 元素继续按
 * 正常路径渲染（它们本来就是 Fabric HudElement）。做成 Screen 的话会：
 * <ul>
 * <li>把游戏画面替换掉 — 用户看不到元素在真实场景里的样子；</li>
 * <li>HUD 元素在 Screen 打开时通常被跳过渲染（见 ESP2D 的 {@code screen!=null} 早退）。</li>
 * </ul>
 * 所以走键盘/鼠标事件（KeyboardHandlerMixin / MouseHandlerMixin → EventBus）：
 * 进入编辑态时吞掉按键与鼠标点击，退出时放行。
 *
 * <h3>拖动为什么靠每帧轮询鼠标</h3>
 * 26.2 的鼠标移动不经过 EventBus（只有按下/滚轮有事件），所以拖动期间在每帧的
 * {@link #renderFrames} 里读 {@code MouseHandler.getScaledXPos/YPos} 更新位置。
 * 这也顺便解决了"拖动时鼠标移出元素边界"的经典问题。
 *
 * <h3>位置持久化</h3>
 * 存进 {@link DesktopSettings#hudLayout}（JSON：id → [x, y]），走 settings.json
 * 自动保存。模块设置本身不持久化（只有启用状态/按键绑定进配置文件），所以位置
 * 不能塞进模块设置。
 */
public final class HudEditor {

    private static final Gson GSON = new Gson();

    private static final HudEditor INSTANCE = new HudEditor();

    public static HudEditor get() {
        return INSTANCE;
    }

    private final List<MovableHud> elements = new ArrayList<>();
    /** id → 位置（GUI 坐标）。null 表示用元素自己的默认位置。 */
    private final Map<String, float[]> positions = new HashMap<>();

    private boolean active;
    private MovableHud dragging;
    /** 正在拖右下角手柄调整大小（而不是移动）。 */
    private boolean resizing;
    /** 按下时鼠标相对元素左上角的偏移，拖动时保持它不变（不然元素会"跳"到鼠标下）。 */
    private float dragOffsetX;
    private float dragOffsetY;
    private boolean loaded;
    /** 拖动开始前的位置，最多留 32 步。 */
    private final List<Map<String, float[]>> undo = new ArrayList<>();
    private boolean pushedUndo;
    private int dragAx;
    private int dragAy;
    private float dragMx;
    private float dragMy;
    private String notice = "";
    private long noticeUntil;

    // ── 点击 vs 拖动 ──
    // 用户要的行为：按下后很快松开 = 点击（转发给元素，按钮照样能按），
    // 一直按着 = 拖动。所以按下时先"挂起"，超过时长或位移阈值才真正开始拖。
    private static final long DRAG_HOLD_NANOS = 200_000_000L;   // 200ms
    private static final float DRAG_MOVE_PX = 4.0f;
    /** 已按下但还没判定成拖动的元素（此时元素不动）。 */
    private MovableHud pending;
    private boolean pendingResize;
    /** 按下点是否落在元素本体上（手柄上的点击不转发给元素）。 */
    private boolean pendingInBody;
    private float pressX;
    private float pressY;
    private long pressNanos;

    private HudEditor() {
    }

    /** 由 AdaptClient 在 mod 初始化时调用一次：订阅键盘/鼠标事件。 */
    public void init() {
        EventBus.INSTANCE.subscribe(this);
        loadPositions();
    }

    /** 注册一个可拖拽元素（重复注册同一个对象会被忽略）。 */
    public void register(MovableHud element) {
        if (element == null || elements.contains(element)) return;
        elements.add(element);
        applyStoredPosition(element);
    }

    public boolean isActive() {
        return active;
    }

    // ───────────────────────── 事件 ─────────────────────────

    @EventHandler
    public void onKey(KeyPressEvent event) {
        int key = event.getKey();
        boolean press = event.getAction() == 1;   // GLFW_PRESS

        if (!active) {
            // 只有在世界里（没有打开任何 Screen）才允许进入编辑态，否则会和
            // ESC 关闭 GUI、聊天框输入等冲突。
            if (press && key == SDLScancode.SDL_SCANCODE_RGUI
                    && Minecraft.getInstance().gui.screen() == null) {
                setActive(true);
                event.cancel();
            }
            return;
        }

        if (press && key == SDLScancode.SDL_SCANCODE_ESCAPE) {
            setActive(false);          // 内部会保存
            event.cancel();  // 不让它顺带打开暂停菜单
            return;
        }
        if (press && key == SDLScancode.SDL_SCANCODE_Z && commandDown()) {
            undo();
            event.cancel();
            return;
        }
        if (press && key >= SDLScancode.SDL_SCANCODE_1 && key <= SDLScancode.SDL_SCANCODE_5 && commandDown()) {
            int slot = key - SDLScancode.SDL_SCANCODE_1 + 1;
            if (shiftDown()) saveProfile(slot);
            else loadProfile(slot);
            event.cancel();
            return;
        }
        // 编辑态吞掉其余按键的按下事件（释放放行，否则输入系统会认为键还按着）。
        // 这样拖动时玩家不会一边跑一边改位置。
        if (press) {
            event.cancel();
        }
    }

    @EventHandler
    public void onMouse(MousePressEvent event) {
        if (!active) return;
        boolean press = event.getAction() == 1;
        Minecraft mc = Minecraft.getInstance();
        float mx = (float) mc.mouseHandler.getScaledXPos(mc.getWindow());
        float my = (float) mc.mouseHandler.getScaledYPos(mc.getWindow());
        if (press) {
            // 从后往前找：后注册的元素画在上层，命中优先。
            // 右下角手柄 = 调整大小（可缩放的元素）；左上角手柄与本体 = 移动/点击。
            for (int i = elements.size() - 1; i >= 0 && pending == null; i--) {
                MovableHud e = elements.get(i);
                if (!e.hudVisible()) continue;
                boolean onResize = e.hudResizable() && inHandle(e, false, mx, my);
                boolean onBody = inHandle(e, true, mx, my) || inBody(e, mx, my);
                if (onResize || onBody) {
                    // 先挂起，等时长/位移判定 — 短按松手是点击，不是拖动。
                    pending = e;
                    pendingResize = onResize;
                    pendingInBody = onBody && !onResize;
                    dragOffsetX = mx - e.hudX();
                    dragOffsetY = my - e.hudY();
                    pressX = mx;
                    pressY = my;
                    pressNanos = System.nanoTime();
                }
            }
        } else {
            MovableHud finished = dragging;
            boolean moved = dragging != null && !resizing;
            if (pending != null && pendingInBody) {
                // 短按（没被判定成拖动）→ 当作点击转发给元素。
                pending.hudClick(mx, my);
            }
            pending = null;
            dragging = null;
            resizing = false;
            pushedUndo = false;
            if (moved && finished != null) {
                storeAnchor(finished, dragAx, dragAy, dragMx, dragMy);
                savePositions();
            }
        }
        // 编辑态吞掉鼠标点击：否则拖动会顺带挖方块/放置方块。
        event.cancel();
    }

    // ───────────────────────── 渲染 ─────────────────────────

    /**
 * 每帧由 HUD 元素调用：画边框 + 拖动时更新位置。
 * <p>
 * 必须在元素<b>之后</b>渲染（HudElementRegistry.addLast 的顺序），这样边框
 * 盖在元素上、拖动的元素能跟着鼠标走。
 */
    public void renderFrames(GuiGraphicsExtractor g) {
        applyAnchors(g.guiWidth(), g.guiHeight());
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();

        // 拖动更新：轮询鼠标（鼠标移动没有事件，见类注释）
        // ⚠ 这里**不能**提前 return — 下面还要画手柄/标签。之前缩放分支里
        // 写了 return，结果拖动过程中手柄整帧不画（实测"拖动的时候手柄会消失"）。
        float mx = (float) mc.mouseHandler.getScaledXPos(mc.getWindow());
        float my = (float) mc.mouseHandler.getScaledYPos(mc.getWindow());

        // 挂起态判定：按够久或移够远才算拖动（短按松手是点击）。
        if (pending != null) {
            long held = System.nanoTime() - pressNanos;
            float moved = Math.max(Math.abs(mx - pressX), Math.abs(my - pressY));
            if (held >= DRAG_HOLD_NANOS || moved > DRAG_MOVE_PX) {
                if (!pushedUndo) {
                    pushUndo();
                    pushedUndo = true;
                }
                dragging = pending;
                resizing = pendingResize;
                pending = null;
            }
        }

        if (dragging != null) {
            if (resizing) {
                // 缩放：以左上角为锚点，把鼠标位置换算成格子数（四舍五入）
                float cell = dragging.hudCellSize() * (float) hudScaleOf(dragging);
                int nc = Math.round((mx - dragging.hudX()) / cell);
                int nr = Math.round((my - dragging.hudY()) / cell);
                dragging.setHudGridSize(nc, nr);
            } else {
                float nx = mx - dragOffsetX;
                float ny = my - dragOffsetY;
                // 拉动限制：元素必须**整体留在屏幕内** — 和桌面小组件一样（安卓
                // 桌面上小组件不能被拖出屏幕）。之前允许压到屏幕外（只留手柄），
                // 元素容易被拖丢。元素比屏幕还大时贴左上角。
                float guiW = g.guiWidth(), guiH = g.guiHeight();
                nx = Math.max(0f, Math.min(Math.max(0f, guiW - dragging.hudW()), nx));
                ny = Math.max(0f, Math.min(Math.max(0f, guiH - dragging.hudH()), ny));
                float[] snapped = snap(nx, ny, dragging.hudW(), dragging.hudH(), guiW, guiH);
                dragging.setHudPos(snapped[0], snapped[1]);
                dragAx = Math.round(snapped[2]);
                dragAy = Math.round(snapped[3]);
                dragMx = snapped[4];
                dragMy = snapped[5];
            }
        }

        for (MovableHud e : elements) {
            if (!e.hudVisible()) continue;
            boolean hovered = dragging == e || pending == e;
            // 只画手柄，不画边框（用户要求"把那个边框线给去掉"），也不画名字
            // （元素可以用 hudShowLabel 关掉，音乐小组件就是这么做的）。
            if (e.hudShowLabel()) {
                drawLabel(g, e, hovered);
            }
            drawHandle(g, e, true);
            drawHandle(g, e, false);
        }
        String hint = noticeUntil > System.currentTimeMillis()
                ? notice
                : "拖到边缘或中间会吸住    ⌘Z 撤销    ⌘1–5 读取    ⇧⌘1–5 保存";
        int hintWidth = mc.font.width(hint);
        g.text(mc.font, hint, Math.max(4, (g.guiWidth() - hintWidth) / 2), 6, 0xFFFFFFFF, true);
    }

    /** 手柄边长（GUI 坐标）。比元素本身小一圈，但足够点中。 */
    private static final int HANDLE = 36;
    /**
 * 手柄盒子与元素的重叠量（正数 = 盒子往元素里面压多少）。
 * <p>
 * <b>为什么是"重叠"而不是"间距"。</b>手柄的形状是"单段大圆弧"，弧身位于
 * 盒子<b>靠外</b>的那一侧（弧心在盒子的内角上），弧的两个端点朝向元素。
 * 因此盒子离元素越远，弧看起来越远 — 之前用正向间距摆放，视觉距离约 42px，
 * 用户要求减到 1/4。把盒子往里压 18px 后，弧身到元素角的对角距离约 10px，
 * 弧的两端正好绕在角的外侧。
 */
    private static final int HANDLE_INSET = 18;

    /** 手柄盒子左上角在屏幕上的位置（左上角手柄压住元素的左上角）。 */
    private static int handleX(MovableHud e, boolean topLeft) {
        return topLeft ? Math.round(e.hudX()) - HANDLE + HANDLE_INSET
                       : Math.round(e.hudX() + e.hudW()) - HANDLE_INSET;
    }

    private static int handleY(MovableHud e, boolean topLeft) {
        return topLeft ? Math.round(e.hudY()) - HANDLE + HANDLE_INSET
                       : Math.round(e.hudY() + e.hudH()) - HANDLE_INSET;
    }

    /** 画一个角上的圆角手柄（左上 / 右下）。 */
    private void drawHandle(GuiGraphicsExtractor g, MovableHud e, boolean topLeft) {
        MinecraftSurfaceBackend tex = topLeft
                ? HudHandleTextures.topLeft() : HudHandleTextures.bottomRight();
        if (tex == null) return;
        int x = handleX(e, topLeft), y = handleY(e, topLeft);
        int texW = tex.getDisplayWidth(), texH = tex.getDisplayHeight();
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(HANDLE / (float) HudHandleTextures.SIZE, HANDLE / (float) HudHandleTextures.SIZE);
        g.blit(tex.blitPipeline(), tex.getTextureId(),
                0, 0, 0f, 0f, HudHandleTextures.SIZE, HudHandleTextures.SIZE,
                texW, texH, texW, texH);
        pose.popMatrix();
    }

    private final Map<String, MinecraftSurfaceBackend> labelCache = new HashMap<>();
    private final Map<String, int[]> labelSize = new HashMap<>();

    private void drawLabel(GuiGraphicsExtractor g, MovableHud e, boolean hovered) {
        String text = e.hudLabel();
        int color = hovered ? 0xFF4793FD : 0xE6FFFFFF;
        String key = text + "|" + Integer.toHexString(color);
        MinecraftSurfaceBackend tex = labelCache.get(key);
        int[] size = labelSize.get(key);
        if (tex == null) {
            try {
                SkijaNativeFix.preload();
                RiseFont font = RiseFont.main(26, RiseFont.Weight.MEDIUM);   // 13 显示像素 ×2
                // 宽度必须用 getVectorWidth（矢量度量）。用 getStringWidth 会拿到
                // 位图路径的"半宽"→ 画布只有一半宽 → 5 个汉字只显示 3 个（实测）。
                // 高度留足：drawVector 基线 = y + height，而 height 只是
                // boundsH/2，升部会跑到 y 上方、降部到基线下方。
                int w = Math.max(1, (int) Math.ceil(font.getVectorWidth(text)) + 8);
                int h = Math.max(1, (int) Math.ceil(font.height() * 2) + 12);
                // 标签在 GUI 里 1:1 显示 → 物理纹理 = 逻辑尺寸 × 窗口 GUI scale
                tex = new MinecraftSurfaceBackend("hud_label", w, h,
                        (float) Minecraft.getInstance().getWindow().getGuiScale());
                var canvas = tex.acquireCanvas();
                if (canvas != null) {
                    font.drawVector(canvas, text, 4, h / 2f - font.height() * 0.4f, color);
                    tex.present();
                }
                labelCache.put(key, tex);
                size = new int[]{w, h};
                labelSize.put(key, size);
            } catch (Throwable t) {
                return;
            }
        }
        int texW = tex.getDisplayWidth(), texH = tex.getDisplayHeight();
        // 标签画在元素**上方**、从元素左边缘开始，并夹进屏幕内。
        // 之前画在左上角手柄的左边（hudX - 38），元素贴左边时整条标签跑到
        // 屏幕外 — 实测"文字依旧被裁切"其实是屏幕裁的，不是纹理裁的。
        int x = Math.max(2, Math.round(e.hudX()));
        int y = Math.max(2, Math.round(e.hudY()) - size[1] - 6);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        g.blit(tex.blitPipeline(), tex.getTextureId(),
                0, 0, 0f, 0f, size[0], size[1], texW, texH, texW, texH);
        pose.popMatrix();
    }

    /** 命中测试：指定角的手柄盒子。 */
    private static boolean inHandle(MovableHud e, boolean topLeft, float mx, float my) {
        int hx = handleX(e, topLeft), hy = handleY(e, topLeft);
        return mx >= hx && mx <= hx + HANDLE && my >= hy && my <= hy + HANDLE;
    }

    /**
 * 元素的渲染倍数（只有音乐小组件有这个概念；用于把"格"换算成 GUI 像素）。
 * 拿不到时按 1.0 算。
 */
    private static double hudScaleOf(MovableHud e) {
        return com.ylmac.adapt.render.hud.MusicWidgetOverlay.get() == e
                ? com.ylmac.adapt.render.hud.MusicWidgetOverlay.get().hudScale()
                : 1.0;
    }

    /** 命中测试：元素本体。 */
    private static boolean inBody(MovableHud e, float mx, float my) {
        return mx >= e.hudX() && mx <= e.hudX() + e.hudW()
                && my >= e.hudY() && my <= e.hudY() + e.hudH();
    }

    // ───────────────────────── 状态与持久化 ─────────────────────────

    private void setActive(boolean value) {
        if (active == value) return;
        active = value;
        Minecraft mc = Minecraft.getInstance();
        if (active) {
            // **必须释放鼠标**：世界里鼠标是抓取（锁定+隐藏）的，不释放的话
            // 光标不会动、也点不到元素 — 实测"根本没法拖动"。
            // 退出时再抓回去，否则回到游戏后视角不能转。
            if (mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.releaseMouse();
            }
        } else {
            dragging = null;
            pending = null;
            resizing = false;
            savePositions();
            if (!mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.grabMouse();
            }
        }
        AdaptClient.LOGGER.info("[HUD] editor {}", active ? "opened" : "closed (saved)");
    }

    private void loadPositions() {
        if (loaded) return;
        loaded = true;
        DesktopSettings s = AdaptScreen.getDesktopSettings();
        if (s == null) return;
        String json = s.hudLayout.peek();
        if (json == null || json.isBlank()) return;
        try {
            Map<String, List<Float>> parsed = GSON.fromJson(json,
                    new TypeToken<Map<String, List<Float>>>() { }.getType());
            if (parsed == null) return;
            for (Map.Entry<String, List<Float>> e : parsed.entrySet()) {
                List<Float> v = e.getValue();
                if (v == null || v.size() < 2 || v.get(0) == null || v.get(1) == null) continue;
                float[] entry = new float[]{v.get(0), v.get(1), 0, 0, 0, 0, 0, 0};
                if (v.size() >= 4 && v.get(2) != null && v.get(3) != null) {
                    entry[2] = v.get(2);
                    entry[3] = v.get(3);
                }
                for (int i = 4; i < 8 && i < v.size(); i++) {
                    if (v.get(i) != null) entry[i] = v.get(i);
                }
                positions.put(e.getKey(), entry);
            }
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[HUD] layout parse failed: {}", t.toString());
        }
    }

    /** 元素注册时套用已保存的位置与格子数（没有保存过就保留元素自己的默认值）。 */
    private void applyStoredPosition(MovableHud element) {
        loadPositions();
        float[] p = positions.get(element.hudId());
        if (p == null) return;
        element.setHudPos(p[0], p[1]);
        if (element.hudResizable() && p[2] > 0 && p[3] > 0) {
            element.setHudGridSize(Math.round(p[2]), Math.round(p[3]));
        }
    }

    /** 把当前位置（+ 可缩放元素的格子数）写回 DesktopSettings。 */
    private void savePositions() {
        Map<String, float[]> current = new LinkedHashMap<>();
        for (MovableHud e : elements) {
            float[] prev = positions.get(e.hudId());
            float[] row = new float[]{e.hudX(), e.hudY(), e.hudGridCols(), e.hudGridRows(), 0, 0, 0, 0};
            if (prev != null) {
                for (int i = 4; i < 8 && i < prev.length; i++) row[i] = prev[i];
            }
            current.put(e.hudId(), row);
        }
        positions.clear();
        positions.putAll(current);
        DesktopSettings s = AdaptScreen.getDesktopSettings();
        if (s == null) return;
        try {
            s.hudLayout.set(GSON.toJson(current));
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[HUD] layout save failed: {}", t.toString());
        }
    }

    /** 靠近屏幕边或正中时吸住，并记下锚点，窗口大小变了还贴着那一条边。 */
    private static float[] snap(float x, float y, float w, float h, float guiW, float guiH) {
        float limit = 8f;
        int ax = 0;
        int ay = 0;
        float mx = x;
        float my = y;
        if (x <= limit) {
            x = 0f;
            ax = 1;
            mx = 0f;
        } else if (guiW - (x + w) <= limit) {
            x = Math.max(0f, guiW - w);
            ax = 2;
            mx = Math.max(0f, guiW - (x + w));
        } else if (Math.abs(x - (guiW - w) / 2f) <= limit) {
            x = (guiW - w) / 2f;
            ax = 3;
            mx = 0f;
        }
        if (y <= limit) {
            y = 0f;
            ay = 1;
            my = 0f;
        } else if (guiH - (y + h) <= limit) {
            y = Math.max(0f, guiH - h);
            ay = 2;
            my = Math.max(0f, guiH - (y + h));
        } else if (Math.abs(y - (guiH - h) / 2f) <= limit) {
            y = (guiH - h) / 2f;
            ay = 3;
            my = 0f;
        }
        return new float[]{x, y, ax, ay, mx, my};
    }

    private void applyAnchors(float guiW, float guiH) {
        for (MovableHud e : elements) {
            if (e == dragging || e == pending) continue;
            float[] p = positions.get(e.hudId());
            if (p == null || p.length < 6) continue;
            int ax = Math.round(p[4]);
            int ay = Math.round(p[5]);
            if (ax == 0 && ay == 0) continue;
            float mx = p.length > 6 ? p[6] : 0f;
            float my = p.length > 7 ? p[7] : 0f;
            float x = e.hudX();
            float y = e.hudY();
            if (ax == 1) x = mx;
            else if (ax == 2) x = guiW - e.hudW() - mx;
            else if (ax == 3) x = (guiW - e.hudW()) / 2f;
            if (ay == 1) y = my;
            else if (ay == 2) y = guiH - e.hudH() - my;
            else if (ay == 3) y = (guiH - e.hudH()) / 2f;
            if (Math.abs(x - e.hudX()) > 0.5f || Math.abs(y - e.hudY()) > 0.5f) e.setHudPos(x, y);
        }
    }

    private void storeAnchor(MovableHud element, int ax, int ay, float mx, float my) {
        float[] row = positions.get(element.hudId());
        if (row == null || row.length < 8) {
            float[] grown = new float[8];
            if (row != null) System.arraycopy(row, 0, grown, 0, Math.min(row.length, 8));
            row = grown;
            positions.put(element.hudId(), row);
        }
        row[0] = element.hudX();
        row[1] = element.hudY();
        row[2] = element.hudGridCols();
        row[3] = element.hudGridRows();
        row[4] = ax;
        row[5] = ay;
        row[6] = mx;
        row[7] = my;
    }

    private void pushUndo() {
        Map<String, float[]> snap = new LinkedHashMap<>();
        for (MovableHud e : elements) {
            float[] prev = positions.get(e.hudId());
            float[] row = new float[]{e.hudX(), e.hudY(), e.hudGridCols(), e.hudGridRows(), 0, 0, 0, 0};
            if (prev != null) {
                for (int i = 4; i < 8 && i < prev.length; i++) row[i] = prev[i];
            }
            snap.put(e.hudId(), row);
        }
        undo.add(snap);
        if (undo.size() > 32) undo.remove(0);
    }

    private void undo() {
        if (undo.isEmpty()) {
            showNotice("没有可撤销的移动");
            return;
        }
        Map<String, float[]> snap = undo.remove(undo.size() - 1);
        positions.clear();
        for (Map.Entry<String, float[]> entry : snap.entrySet()) {
            positions.put(entry.getKey(), entry.getValue().clone());
        }
        for (MovableHud e : elements) applyStoredPosition(e);
        savePositions();
        showNotice("已撤销");
    }

    private void saveProfile(int slot) {
        DesktopSettings settings = AdaptScreen.getDesktopSettings();
        if (settings == null) return;
        savePositions();
        Map<String, String> all = readProfiles(settings);
        String layout = settings.hudLayout.peek();
        all.put(Integer.toString(slot), layout == null ? "" : layout);
        settings.hudProfiles.set(GSON.toJson(all));
        showNotice("已保存配置 " + slot);
    }

    private void loadProfile(int slot) {
        DesktopSettings settings = AdaptScreen.getDesktopSettings();
        if (settings == null) return;
        String layout = readProfiles(settings).get(Integer.toString(slot));
        if (layout == null || layout.isBlank()) {
            showNotice("配置 " + slot + " 是空的");
            return;
        }
        pushUndo();
        settings.hudLayout.set(layout);
        positions.clear();
        loaded = false;
        loadPositions();
        for (MovableHud e : elements) applyStoredPosition(e);
        showNotice("已读取配置 " + slot);
    }

    private Map<String, String> readProfiles(DesktopSettings settings) {
        String json = settings.hudProfiles.peek();
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, String> parsed = GSON.fromJson(json, new TypeToken<Map<String, String>>() { }.getType());
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Throwable t) {
            return new LinkedHashMap<>();
        }
    }

    private void showNotice(String text) {
        notice = text;
        noticeUntil = System.currentTimeMillis() + 1600L;
    }

    private static boolean commandDown() {
        return InputConstants.isKeyDown(SDLScancode.SDL_SCANCODE_LGUI)
                || InputConstants.isKeyDown(SDLScancode.SDL_SCANCODE_RGUI);
    }

    private static boolean shiftDown() {
        return InputConstants.isKeyDown(SDLScancode.SDL_SCANCODE_LSHIFT)
                || InputConstants.isKeyDown(SDLScancode.SDL_SCANCODE_RSHIFT);
    }
}
