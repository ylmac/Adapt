package com.ylmac.adapt.render;

import com.ylmac.adapt.QplayerModClient;
import com.ylmac.adapt.features.music.KeyInputManager;
import com.ylmac.adapt.ui.QmlCacheManager;
import com.ylmac.adapt.ui.QplayerResourceLoader;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * QML 屏幕基类，封装 qml4j 引擎初始化、渲染管线、输入处理等公共逻辑。
 * <p>
 * 子类只需在构造器中调用 setter 配置即可快速创建屏幕：
 * <pre>
 * public class MyScreen extends QmlScreen {
 *     public MyScreen() {
 *         super("My Screen");
 *         setQmlPath("MyScreen.qml");
 *         setContext("myObj", myObj);
 *         setBackgroundColor(0xA0000000);
 *     }
 * }
 * </pre>
 */
public abstract class QmlScreen extends Screen {

    // === 子类通过 setter 配置 ===
    private String qmlPath;
    private String surfaceName;
    private final Map<String, Object> contextObjects = new HashMap<>();
    private int backgroundColor = 0x00000000; // 全透明
    private boolean useBackdropBlur = false;
    private int designWidth = 960;
    private int designHeight = 540;

    protected void setQmlPath(String path) { this.qmlPath = path; }
    protected void setSurfaceName(String name) { this.surfaceName = name; }
    protected void setContext(String key, Object value) { contextObjects.put(key, value); }
    protected void setBackgroundColor(int color) { this.backgroundColor = color; }
    protected void setUseBackdropBlur(boolean use) { this.useBackdropBlur = use; }
    protected void setDesignSize(int w, int h) { this.designWidth = w; this.designHeight = h; }

    // === 子类可覆盖的模板方法 ===
    protected void onBeforeRender(Canvas canvas, int mx, int my, float delta) {}
    protected void onAfterRender(Canvas canvas, int mx, int my, float delta) {}

    /** 预编译线程写入的静态 View，子类可通过此字段共享 */
    protected static volatile QmlView precompiledView;
    protected static volatile Item precompiledRoot;
    protected static volatile boolean precompiledLoaded = false;
    protected static volatile int precompiledW = 960;
    protected static volatile int precompiledH = 540;
    /** 预编译视图对应的 QML 路径（只有与当前屏幕一致时才复用，否则各屏幕加载自己的 QML） */
    protected static volatile String precompiledQmlPath = "";

    /** 按 qmlPath 缓存的 QmlView + Surface，使同一屏幕关闭再打开时复用（不重新编译 QML） */
    protected static final java.util.Map<String, QmlView> sCachedViews = new java.util.concurrent.ConcurrentHashMap<>();
    protected static final java.util.Map<String, Item> sCachedRoots = new java.util.concurrent.ConcurrentHashMap<>();
    protected static final java.util.Map<String, MinecraftSurfaceBackend> sCachedSurfaces = new java.util.concurrent.ConcurrentHashMap<>();
    protected static final java.util.Map<String, int[]> sCachedSizes = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 后台线程预编译指定 QML 文件，结果存入静态缓存 Map。
     * 主线程打开屏幕时直接从缓存复用，零编译耗时。
     */
    protected static void precompileToCache(String qmlPath, java.util.Map<String, Object> context) {
        try {
            QmlEngine engine = new QmlEngine();
            QmlView view = QmlView.withStockTypes(engine);
            view.resources(new QplayerResourceLoader());
            view.bytecodeCache(new QmlCacheManager());
            // 恢复预编译缓存类，避免运行时编译撞 Component$N 类名计数
            io.github.timer_err.qml4j.render.RestoreHelper.restoreAllFor(view);
            loadFonts(view);
            if (context != null) {
                for (java.util.Map.Entry<String, Object> e : context.entrySet()) {
                    view.context(e.getKey(), e.getValue());
                }
            }
            String qml = loadQml(qmlPath);
            if (qml == null) {
                QplayerModClient.LOGGER.warn("[QmlScreen] precompile: QML not found: {}", qmlPath);
                return;
            }
            // baseDir 必须为文档所在目录（而非完整文件路径），import "." 才能正确解析
            String baseDir = qmlPath.contains("/") ? qmlPath.substring(0, qmlPath.lastIndexOf('/')) : "";
            Item root = view.load(qml, baseDir);
            if (root == null) {
                QplayerModClient.LOGGER.warn("[QmlScreen] precompile: root is null for {}", qmlPath);
                return;
            }
            int w = 960, h = 540;
            try {
                w = ((Number) MemberAccess.readMember(root, "width")).intValue();
                h = ((Number) MemberAccess.readMember(root, "height")).intValue();
            } catch (Exception ignored) {}
            sCachedViews.put(qmlPath, view);
            sCachedRoots.put(qmlPath, root);
            sCachedSizes.put(qmlPath, new int[]{w, h});
            // 后台布局收敛 30 轮（公共方法）：Repeater 异步实例化需多轮 layout 才能完全收敛，
            // 缓存阶段做完 → 打开界面零收敛延迟。
            warmupLayout(view, root, 30);
            QplayerModClient.LOGGER.info("[QmlScreen] precompiled {} ({}x{}) cached ({} warmup rounds)", qmlPath, w, h, 30);
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[QmlScreen] precompile {} failed: {}", qmlPath, t.toString());
        }
    }

    /**
     * 后台布局收敛公共方法（QmlScreen.precompileToCache 与 QplayerScreen.precompileQmlBackground
     * 统一调用，避免两处重复实现）。
     * <p>
     * qml4j 的 Repeater 生成 delegate 是异步（rebuildTask 排入 DirtyQueue），一轮 layout
     * 只能实例化一层。30 轮让所有 Repeater/动态内容完全收敛，缓存阶段做完 → 打开界面
     * 零收敛延迟（编译多花 ~1 分钟，后续全部 QML 免收敛）。
     */
    protected static void warmupLayout(QmlView view, Item root, int rounds) {
        try {
            DirtyQueue dq = new DirtyQueue();
            dq.install();
            try {
                for (int i = 0; i < rounds; i++) {
                    dq.flush();
                    view.renderer().layoutOnly(root);
                    dq.flush();
                }
            } finally {
                dq.uninstall();
            }
        } catch (Throwable ignored) {}
    }

    // === 通用字段 ===
    protected QmlView qmlView;
    protected Item qmlRoot;
    protected boolean qmlLoaded;
    protected MinecraftSurfaceBackend surfaceBackend;
    /**
     * surface 的设备倍率（逻辑 QML 像素 → 物理像素）。
     * <p>
     * 不再固定 3× 超采样，而是取<b>原生</b>值：{@code panelScale × 窗口 GUI scale}，
     * 也就是面板在屏幕上真实占的设备像素数 —— 贴图 1:1，抗锯齿边缘不会被二次重采样
     * （SkikoBackend 的做法，见 MinecraftSurfaceBackend.ensureDeviceScale）。
     */
    protected float guiScale = 3.0f;
    protected float panelScale = 1.0f;
    protected int panelX, panelY;
    protected int uiW = 960, uiH = 540;
    protected KeyInputManager keyInputManager;
    private float lastLx = -1, lastLy = -1;
    private boolean wasDown = false;
    /** 首帧收敛计数：打开界面后前 N 帧强制 layout（Repeater 异步实例化补齐） */
    private int warmupFrames = 0;

    public QmlScreen(String title) {
        super(Component.literal(title));
    }

    @Override
    protected void init() {
        super.init();
        SkijaNativeFix.preload();

        // 复用缓存的 QmlView + Surface（同一 qmlPath 的屏幕关闭再打开时不重新创建）
        if (qmlView == null) {
            QmlView cached = sCachedViews.get(qmlPath);
            if (cached != null) {
                qmlView = cached;
                qmlRoot = sCachedRoots.get(qmlPath);
                qmlLoaded = true;
                int[] sz = sCachedSizes.get(qmlPath);
                if (sz != null) { uiW = sz[0]; uiH = sz[1]; }
            }
        }
        if (surfaceBackend == null) {
            MinecraftSurfaceBackend cached = sCachedSurfaces.get(qmlPath);
            if (cached != null) surfaceBackend = cached;
        }

        computePanelPos();

        if (qmlView == null || surfaceBackend == null) {
            initQmlEngine();
        }

        // 保存到静态缓存（下次打开同一 qmlPath 的屏幕时复用）
        if (qmlView != null) {
            sCachedViews.put(qmlPath, qmlView);
            sCachedRoots.put(qmlPath, qmlRoot);
            sCachedSizes.put(qmlPath, new int[]{uiW, uiH});
            // 复用的 QmlView 需要重新设置上下文（bridge 等可能是新实例）
            for (java.util.Map.Entry<String, Object> e : contextObjects.entrySet()) {
                qmlView.context(e.getKey(), e.getValue());
            }
        }
        if (surfaceBackend != null) {
            sCachedSurfaces.put(qmlPath, surfaceBackend);
        }


        keyInputManager = new KeyInputManager(qmlView);
        lastLx = -1; lastLy = -1; wasDown = false;
        prewarmLayout();

        // 每次打开界面都调用（复用缓存 QmlView 时 Component.onCompleted 不会重跑，
        // 需要子类在这里刷新动态数据 —— 如 ClickGUI 的模块开关状态）
        onScreenOpened();

        // 世界取色：任何自家 QML 界面（ClickGUI/音乐播放器等）打开时截一张世界图
        // 作为莫奈主题色种子（之前只有 ClickGUI 的"打开音乐播放器"按钮触发，
        // 按钮移除后取色就断了）。QmlScreen.init 是所有 QML 屏幕的统一打开入口。
        WorldColorSampler.get().forceSnapshot();
    }

    /** 每次界面打开时调用（子类可覆写刷新动态数据；复用缓存 QML 时 onCompleted 不重跑） */
    protected void onScreenOpened() {
    }

    protected void initQmlEngine() {
        try {
            // 仅在尚未加载 QML 时加载（QplayerScreen 可能在 init() 里先复用了预编译视图）
            if (qmlView == null) {
                // 后台预编译视图：仅当它与本屏幕的 QML 路径一致时才复用
                boolean canReuse = precompiledView != null && precompiledRoot != null
                        && precompiledLoaded && precompiledQmlPath.equals(qmlPath);
                if (canReuse) {
                    qmlView = precompiledView;
                    qmlRoot = precompiledRoot;
                    qmlLoaded = true;
                    uiW = precompiledW;
                    uiH = precompiledH;
                } else {
                    QmlEngine engine = new QmlEngine();
                    qmlView = QmlView.withStockTypes(engine);
                    qmlView.resources(new QplayerResourceLoader());

                    // 共享缓存（已在 Mod 启动时预加载到内存）
                    qmlView.bytecodeCache(new QmlCacheManager());

                    // 恢复预编译缓存类（defineClass + 推进 componentCounter）。
                    // 必须在首次 load() 前调用：否则运行时编译根文档时子组件类
                    // 未 define、类名计数错位 → NoClassDefFoundError: Component$N
                    io.github.timer_err.qml4j.render.RestoreHelper.restoreAllFor(qmlView);

                    // 加载字体
                    loadFonts(qmlView);

                    // 设置上下文
                    for (Map.Entry<String, Object> e : contextObjects.entrySet()) {
                        qmlView.context(e.getKey(), e.getValue());
                    }

                    // 加载 QML
                    String qml = loadQml(qmlPath);
                    if (qml == null) {
                        QplayerModClient.LOGGER.error("[{}] QML not found: {}", surfaceName, qmlPath);
                        return;
                    }
                    try {
                        // baseDir 必须为文档所在目录，import "." 才能正确解析
                        String baseDir = qmlPath.contains("/") ? qmlPath.substring(0, qmlPath.lastIndexOf('/')) : "";
                        qmlRoot = qmlView.load(qml, baseDir);
                        qmlLoaded = qmlRoot != null;
                    } catch (Throwable t) {
                        QplayerModClient.LOGGER.error("[{}] QML load FAILED: {}", surfaceName, t.toString(), t);
                        throw t;
                    }

                    // 获取设计尺寸
                    uiW = designWidth;
                    uiH = designHeight;
                    if (qmlLoaded) {
                        try {
                            uiW = ((Number) MemberAccess.readMember(qmlRoot, "width")).intValue();
                            uiH = ((Number) MemberAccess.readMember(qmlRoot, "height")).intValue();
                        } catch (Exception ignored) {}
                        if (uiW <= 0) uiW = designWidth;
                        if (uiH <= 0) uiH = designHeight;
                    }
                }
            }

            // 创建 Surface（无论复用还是新建 QML，都必须有 surfaceBackend 才能渲染）
            if (surfaceBackend == null
                || surfaceBackend.width() != uiW
                || surfaceBackend.height() != uiH) {
                if (surfaceBackend != null) surfaceBackend.dispose();
                // 初始倍率按当前面板缩放算（之后每帧 ensureDeviceScale 对齐）
                float initScale = Math.max(1.0f, panelScale)
                        * (float) Minecraft.getInstance().getWindow().getGuiScale();
                guiScale = initScale;
                surfaceBackend = new MinecraftSurfaceBackend(surfaceName, uiW, uiH, initScale);
                QplayerModClient.LOGGER.info("[{}] Surface created: {}x{} (device scale {})",
                        surfaceName, uiW, uiH, initScale);
            }
        } catch (Exception e) {
            QplayerModClient.LOGGER.error("[{}] init FAILED", surfaceName, e);
        }
    }

    protected static void loadFonts(QmlView view) {
        byte[] regular = loadFont("fonts/PingFangSC-Regular.otf");
        byte[] medium = loadFont("fonts/PingFangSC-Medium.otf");
        byte[] light = loadFont("fonts/PingFangSC-Light.otf");
        byte[] thin = loadFont("fonts/PingFangSC-Thin.otf");
        if (regular != null && medium != null) view.uiTypefaces(regular, medium);
        byte[] icon = loadFont("fonts/MaterialSymbolsRounded.ttf");
        if (icon != null) view.iconTypeface(icon);
    }

    private void computePanelPos() {
        int margin = Math.max(20, Math.min(this.width, this.height) / 6);
        int contentW = this.width - 2 * margin;
        int contentH = this.height - 2 * margin;
        if (contentW <= 0 || contentH <= 0) {
            panelScale = 0.01f;
            panelX = 0; panelY = 0;
            return;
        }
        float sx = (float) contentW / uiW;
        float sy = (float) contentH / uiH;
        panelScale = Math.min(sx, sy);
        if (panelScale < 0.01f) panelScale = 0.01f;
        int dispW = (int)(uiW * panelScale);
        int dispH = (int)(uiH * panelScale);
        this.panelX = margin + (contentW - dispW) / 2;
        this.panelY = margin + (contentH - dispH) / 2;
    }

    protected void prewarmLayout() {
        if (qmlView == null || qmlRoot == null) return;
        try {
            DirtyQueue dq = new DirtyQueue();
            dq.install();
            try {
                dq.flush();
                qmlView.renderer().layoutOnly(qmlRoot);
                dq.flush();
            } finally {
                dq.uninstall();
            }
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[{}] layoutOnly prewarm failed: {}", surfaceName, t.toString());
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return true; }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (qmlView == null || keyInputManager == null) return super.keyPressed(e);
        if (keyInputManager.handleKeyPressed(e)) return true;
        return super.keyPressed(e);
    }

    @Override
    public boolean keyReleased(KeyEvent e) {
        if (qmlView == null || keyInputManager == null) return super.keyReleased(e);
        return keyInputManager.handleKeyReleased(e) || super.keyReleased(e);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (qmlView == null || keyInputManager == null) return super.charTyped(event);
        return keyInputManager.handleCharTyped(event) || super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        if (qmlView == null || !qmlLoaded) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        float lx = (float)((mouseX - panelX) / panelScale);
        float ly = (float)((mouseY - panelY) / panelScale);
        if (lx < 0) lx = 0; else if (lx > uiW) lx = uiW;
        if (ly < 0) ly = 0; else if (ly > uiH) ly = uiH;
        boolean handled = qmlView.dispatchWheel(lx, ly,
                (float) horizontalAmount, (float) verticalAmount);
        return handled || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
        // 不绘制任何背景 → 游戏世界透过透明 canvas 显示
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // 世界取色过渡按渲染帧推进：client tick 只有 20 Hz，打点太稀、观感是跳变。
        // 实测一次 scheme 全树刷新 ≈ 5ms，按帧打点完全扛得住（内部节流 ~30ms）。
        // 注意：平滑度只能靠提高打点频率，绝不能加 Behavior on color —— qml4j 的
        // ColorAnimation 接管属性后就不再响应 scheme 更新（控件会永久卡在创建时
        // 的颜色），详见 WorldColorSampler 的注释。
        com.ylmac.adapt.render.WorldColorSampler.get().onRenderFrame();

        if (!qmlLoaded || qmlView == null || surfaceBackend == null) {
            super.extractRenderState(g, mx, my, delta);
            return;
        }

        computePanelPos();
        // 原生设备倍率：面板显示缩放 × 窗口 GUI scale（窗口/GUI scale 变了才重建）
        float nativeScale = panelScale * (float) Minecraft.getInstance().getWindow().getGuiScale();
        if (Math.abs(nativeScale - guiScale) > 1e-4f) {
            guiScale = nativeScale;
        }
        surfaceBackend.ensureDeviceScale(guiScale);

        // 首帧收敛：qml4j 的 Repeater 生成 delegate 是异步（rebuildTask 排入 DirtyQueue），
        // 打开界面首帧只有部分内容，需连续几帧强制 layout 才能完全收敛（否则要等点击触发）。
        if (warmupFrames < 12) {
            warmupFrames++;
            prewarmLayout();
        }

        float lx = (mx - panelX) / panelScale;
        float ly = (my - panelY) / panelScale;
        if (lx < 0) lx = 0; else if (lx > uiW) lx = uiW;
        if (ly < 0) ly = 0; else if (ly > uiH) ly = uiH;

        float dx = lx - lastLx, dy = ly - lastLy;
        if (dx * dx + dy * dy > 0.25f) {
            qmlView.dispatchPointerMove(lx, ly);
            lastLx = lx; lastLy = ly;
        }

        long win = Minecraft.getInstance().getWindow().handle();
        boolean down = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down && !wasDown) {
            qmlView.dispatchPointerDown(lx, ly);
        }
        if (!down && wasDown) {
            qmlView.dispatchPointerUp(lx, ly);
            prewarmLayout();
        }
        wasDown = down;

        // renderFrame 内部已调用 tickAnimations（qml4j 每帧一次），这里再调是
        // 双重递归全树（~1800 节点 × 动画 tick），静止帧纯浪费 → 移除。

        // ── 渲染耗时插桩（诊断 ClickGUI 卡顿：分段计时）──
        long tRender0 = System.nanoTime();
        long tAfterClear = tRender0, tAfterBlur = tRender0, tAfterBefore = tRender0, tAfterFrame = tRender0;

        try {
            Canvas canvas = surfaceBackend.getRawCanvas();
            if (canvas != null) {
                canvas.save();
                canvas.clear(backgroundColor); // 透明或半透明背景
                tAfterClear = System.nanoTime();

                // 背景模糊（如果启用）
                Image blurredBg = null;
                if (useBackdropBlur) {
                    blurredBg = BackdropBlurCapture.getBlurredImage(8);
                    io.github.timer_err.qml4j.render.Renderer.backdropImage = blurredBg;
                }
                tAfterBlur = System.nanoTime();

                // 子类渲染前钩子
                onBeforeRender(canvas, mx, my, delta);
                tAfterBefore = System.nanoTime();

                io.github.timer_err.qml4j.render.Renderer.panelX = this.panelX;
                io.github.timer_err.qml4j.render.Renderer.panelY = this.panelY;
                io.github.timer_err.qml4j.render.Renderer.panelScale = this.panelScale;
                io.github.timer_err.qml4j.render.Renderer.screenW = this.width;
                io.github.timer_err.qml4j.render.Renderer.screenH = this.height;

                qmlView.renderFrame(surfaceBackend);
                tAfterFrame = System.nanoTime();

                // 子类渲染后钩子
                onAfterRender(canvas, mx, my, delta);

                // 清理模糊图像
                if (blurredBg != null) {
                    io.github.timer_err.qml4j.render.Renderer.backdropImage = null;
                }

                canvas.restore();
            } else {
                surfaceBackend.clearSurface();
                qmlView.renderFrame(surfaceBackend);
                tAfterFrame = System.nanoTime();
            }
            surfaceBackend.present();
            long tEnd = System.nanoTime();
            // 插桩日志：clear/blur/before/renderFrame/present 各段毫秒耗时（诊断用）
            // debug 级别：每帧 info 写日志本身有开销，诊断时开 debug 即可
            QplayerModClient.LOGGER.debug("[{}] render: clear={}ms blur={}ms before={}ms frame={}ms present={}ms total={}ms",
                surfaceName,
                String.format("%.2f", (tAfterClear - tRender0) / 1_000_000.0),
                String.format("%.2f", (tAfterBlur - tAfterClear) / 1_000_000.0),
                String.format("%.2f", (tAfterBefore - tAfterBlur) / 1_000_000.0),
                String.format("%.2f", (tAfterFrame - tAfterBefore) / 1_000_000.0),
                String.format("%.2f", (tEnd - tAfterFrame) / 1_000_000.0),
                String.format("%.2f", (tEnd - tRender0) / 1_000_000.0));
        } catch (Exception e) {
            QplayerModClient.LOGGER.error("[{}] render error", surfaceName, e);
            super.extractRenderState(g, mx, my, delta);
            return;
        }

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(panelX, panelY);
        pose.scale(panelScale / guiScale, panelScale / guiScale);
        g.blit(surfaceBackend.blitPipeline(), surfaceBackend.getTextureId(),
            0, 0, 0f, 0f,
            surfaceBackend.getDisplayWidth(), surfaceBackend.getDisplayHeight(),
            surfaceBackend.getDisplayWidth(), surfaceBackend.getDisplayHeight());
        pose.popMatrix();

        super.extractRenderState(g, mx, my, delta);
    }

    @Override
    public void removed() {
        super.removed();
        // 状态同步：ESC/返回关闭自家 QML 界面时，若对应模块仍处于启用状态则关闭它，
        // 让 HUD/ClickGUI 的开关状态与真实界面一致（之前 ESC 关闭后模块状态残留，
        // HUD 显示开、实际界面已关，用户需再按一次才同步）。
        // 注意：不能调 m.setEnabled(false)（会触发 MusicPlayerModule.onDisable 再调
        // setScreen，此时 screen 已为 null → ScreenEvents.remove(null) NPE 崩溃）。
        // 直接关闭模块状态本身（setEnabled 的 onDisable 里对 null screen 有 instanceof 保护，
        // 但 setScreen(null) 路径会被 fabric ScreenEvents.remove 拒绝 —— 所以这里用
        // 内部禁用，不走 onDisable 的关屏逻辑）。
        if (this instanceof QplayerScreen) {
            com.ylmac.adapt.features.module.Module m =
                    com.ylmac.adapt.features.module.ModuleManager.INSTANCE.findByName("MusicPlayer");
            if (m != null && m.isEnabled()) {
                m.forceDisableForScreenClose();
            }
        }
    }

    // === 静态资源加载方法 ===

    protected static String loadQml(String p) {
        byte[] d = new QplayerResourceLoader().load(p);
        return d != null ? new String(d, java.nio.charset.StandardCharsets.UTF_8) : null;
    }

    protected static byte[] loadFont(String fp) {
        try (InputStream in = QmlScreen.class.getResourceAsStream("/assets/adaptclient/qml/" + fp)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }
}