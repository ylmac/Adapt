package com.ylmac.adapt.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.timer_err.qml4j.runtime.color.StyleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.ylmac.adapt.config.DesktopSettings;
import com.ylmac.adapt.features.music.PlayerController;
import com.ylmac.adapt.QplayerModClient;

/**
 * World-driven "Monet" seed sampling.
 *
 * Every {@link #SAMPLE_INTERVAL_MS} (while in a world and the monet toggle is on)
 * it grabs the whole frame buffer with {@link Screenshot#takeScreenshot} — this
 * only issues a GPU copy + async readback and never writes a PNG — then hands the
 * (small, downscaled) pixel buffer to a background thread that runs the
 * PixelPlayer seed-extraction pipeline (see {@link MonetQuantizer}). The chosen
 * seed is pushed into {@link StyleManager#setSeedColorHct} — which regenerates
 * the whole MD3 light/dark scheme — <b>immediately</b>, with no interpolation.
 *
 * <p><b>为什么没有颜色过渡（曾经有）。</b>旧实现在种子变化时把 HCT 在
 * {@code monetTransitionMs}（默认 1000 ms）内插值，每个 tick/渲染帧重建一次
 * scheme 来做出"渐变"观感。两个代价：一是用户看到打开界面前后颜色在慢慢爬
 * （"打开屏幕前一个颜色渐变到后一个颜色"），二是每次重建要重刷 QML 全树
 * （实测 7~8 ms），过渡那一秒里每帧都在烧。现在直接落到新色：一次重建，
 * 零过渡开销。
 *
 * Threading: sample request + style writes happen on the client/render thread
 * (driven from ClientTickEvents). The quantization runs on a per-sample daemon
 * thread and only publishes via a volatile array.
 */
public final class WorldColorSampler {

    private static final long SAMPLE_INTERVAL_MS = 1000;
    /** keep the sample small: max dimension of the read-back image */
    private static final int TARGET_MAX_DIM = 176;
    /** PixelPlayer extraction is tuned for high-fidelity art at accuracy=1 */
    private static final double ACCURACY = 0.5;

    private static WorldColorSampler instance;

    public static WorldColorSampler get() {
        if (instance == null) instance = new WorldColorSampler();
        return instance;
    }

    // ---- client-thread state ----
    private long lastSampleAt = 0;
    /** 打开 GUI 时设为 true → 下次 onTick 截一张图 */
    private boolean forceSnapshot = false;
    private long lastRequestAt = 0;
    private boolean inFlight = false;
    private boolean hasApplied = false;
    private boolean loggedEnabled = false;

    /** 当前已应用的种子（用于判断世界色变化是否够大，避免无意义重建） */
    private double curH, curC, curT;

    /** bg thread → client thread: {hue, chroma, tone}; null when consumed */
    private volatile double[] pendingSeed;

    /** 上次 QML 拖拽计数（诊断：确认进度条事件是否进入 QML 组件） */
    private int lastDragDebug = 0;

    private WorldColorSampler() {}

    /** Called every client tick (Minecraft game loop, 20 Hz). */
    public void onTick(Minecraft mc) {
        // 诊断：QML LinearProgress 拖拽事件计数变化即打印
        PlayerController pc = QplayerScreen.getPlayerController();
        if (pc != null && pc.qmlDragDebug != null) {
            // QML 侧 JS 数值写入后可能是 Long/Double，不能直接强转 Integer。
            try {
                Object dv = pc.qmlDragDebug.peek();
                int v = dv instanceof Number ? ((Number) dv).intValue() : 0;
                if (v != lastDragDebug) {
                    lastDragDebug = v;
                    QplayerModClient.LOGGER.info("[QPlayer] QML drag events: {}", v);
                }
            } catch (Throwable ignored) {
                // 诊断日志绝不能把整个游戏带崩
            }
        }
        DesktopSettings settings = QplayerScreen.getDesktopSettings();
        // 封面取色监听器（一次性挂载）：来源=封面时由它投种子，世界采样暂停。
        CoverSeedSampler.get().attachIfNeeded();
        if (settings == null || !Boolean.TRUE.equals(settings.monetEnabled.peek())) {
            // Sampling disabled: drop any in-flight state and reset theme to default.
            inFlight = false;
            pendingSeed = null;
            hasApplied = false;
            return;
        }
        int monetSource = settings.monetSource == null
            || settings.monetSource.peek() == null
            ? 0 : settings.monetSource.peek().intValue();
        // ⚠ 默认全部取色来自音乐封面；"世界取色"只在 Interface → Debug 打开时才允许
        //   （相当于废弃，仅留调试入口）
        if (!com.ylmac.adapt.features.module.impl.render.InterfaceModule.isDebug()) {
            monetSource = 0;
        }

        // ── 阶段 1+2：消费新种子 ──
        // 不依赖世界/渲染目标，必须放在下面的 in-world 早退之前：否则在标题界面
        // （mc.level == null）打开音乐播放器时，已经挂起的新种子永远落不了地。
        consumePendingSeed();

        // ── 阶段 3：世界采样（需要渲染目标，仅世界模式）──
        // 打开 GUI 时截一张图，之后不变；下次打开 GUI 时 forceSnapshot=true 重新截图。
        if (monetSource != 1) {
            if (!loggedEnabled) {
                loggedEnabled = true;
                QplayerModClient.LOGGER.info("[QPlayer] Monet cover sampler active");
            }
            return;
        }
        if (mc.level == null) return;                       // world sampling: in-world only
        if (mc.gameRenderer == null) return;
        RenderTarget rt = mc.gameRenderer.mainRenderTarget();
        if (rt == null || rt.width <= 0 || rt.height <= 0) return;
        if (!loggedEnabled) {
            loggedEnabled = true;
            QplayerModClient.LOGGER.info("[QPlayer] Monet world sampler active ({})",
                rt.width + "x" + rt.height);
        }
        if (forceSnapshot) {
            forceSnapshot = false;
            requestSample(rt);
            QplayerModClient.LOGGER.info("[QPlayer] Monet snapshot on GUI open");
        }
    }

    /**
     * 后台线程产出的新种子到达 → 判断变化是否够大，够大就<b>直接应用</b>。
     * <p>
     * 没有插值过渡：新色一步落地（用户要求"直接是后一个颜色"）。旧实现把 HCT
     * 在 1 秒内逐帧插值，每个打点重建一次 scheme（7~8 ms 全树刷新），既让
     * 打开界面时颜色在慢慢爬，也在过渡那一秒里持续烧 CPU。
     */
    private void consumePendingSeed() {
        double[] seed = pendingSeed;
        if (seed == null) return;
        pendingSeed = null;
        if (!hasApplied) {
            // 首次采样直接落地：从默认紫渐变过去只会让人觉得"反应慢"。
            curH = seed[0]; curC = seed[1]; curT = seed[2];
            hasApplied = true;
            applySeed(curH, curC, curT);
            QplayerModClient.LOGGER.info("[QPlayer] Monet seed set: H={} C={} T={}",
                String.format("%.1f", curH), String.format("%.1f", curC), String.format("%.1f", curT));
            return;
        }
        double hueDiff = MonetQuantizer.differenceDegrees(curH, seed[0]);
        double chromaDiff = Math.abs(curC - seed[1]);
        double toneDiff = Math.abs(curT - seed[2]);
        // 阈值（hue≥10°/chroma≥4/tone≥4）：世界微变、指针/界面像素扰动不触发重染，
        // 只有场景级变化才换色，避免无意义的 scheme 重建。
        if (hueDiff < 10.0 && chromaDiff < 4.0 && toneDiff < 4.0) {
            QplayerModClient.LOGGER.debug("[QPlayer] Monet seed unchanged (Δh={} Δc={} Δt={})",
                String.format("%.1f", hueDiff), String.format("%.1f", chromaDiff), String.format("%.1f", toneDiff));
            return;
        }
        String before = currentSchemePrimary();
        curH = seed[0]; curC = seed[1]; curT = seed[2];
        applySeed(curH, curC, curT);
        QplayerModClient.LOGGER.info("[QPlayer] Monet seed applied: H={} C={} T={} (Δh={} Δc={} Δt={}), primary {} -> {}",
            String.format("%.1f", curH), String.format("%.1f", curC), String.format("%.1f", curT),
            String.format("%.1f", hueDiff), String.format("%.1f", chromaDiff), String.format("%.1f", toneDiff),
            before, currentSchemePrimary());
    }

    /**
     * 渲染帧钩子 —— <b>已停用</b>。
     * <p>
     * 它原本用来按渲染帧推进颜色过渡（client tick 只有 20 Hz，打点太稀、观感是
     * 跳变）。过渡取消后这里无事可做，保留方法是为了不让 QmlScreen 的调用点
     * 变成死代码判断 —— 调用它本身零开销（空方法）。
     * <p>
     * ⚠ 如果将来要做别的逐帧主题逻辑，注意<b>不要</b>用 QML 的
     * {@code Behavior on color}：实测 qml4j 的 ColorAnimation 一旦接管属性，
     * 后续 scheme 更新就再也推不动它（连续 4 帧停在旧值），表现是主界面已换色、
     * 控件却永久卡在打开界面那一刻的颜色。所以全部组件的 Behavior on color
     * 都已移除，主题色一律直接绑定 {@code StyleManager.currentScheme}。
     */
    public void onRenderFrame() {
    }

    /** 当前 scheme 里的 primary 十六进制值（诊断用） */
    private static String currentSchemePrimary() {
        try {
            Object v = ((StyleManager) StyleManager.__instance()).currentScheme.peek();
            if (v instanceof java.util.Map<?, ?> m) {
                Object p = m.get("primary");
                return p == null ? "null" : String.valueOf(p);
            }
            return "not-a-map";
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /** 封面取色路径提交种子（CoverSeedSampler 后台线程调用；volatile 交接给主线程）。 */
    public void submitSeed(double h, double c, double t) {
        pendingSeed = new double[] { h, c, t };
    }

    /**
     * 由 DesktopSettings 监听器调用：切换颜色模式（莫奈开关/来源/主题）时
     * 强制立即采样（世界模式）或重分析当前封面（封面模式），消除"等 1 秒"的延迟。
     */
    /** 打开 GUI 时调用：下次 onTick 截一张世界图 */
    public void forceSnapshot() {
        forceSnapshot = true;
    }

    public void forceRefresh() {
        forceSnapshot = true;
        inFlight = false;
        lastRequestAt = 0;
        DesktopSettings s = QplayerScreen.getDesktopSettings();
        if (s == null) return;
        int src = s.monetSource == null || s.monetSource.peek() == null
            ? 1 : s.monetSource.peek().intValue();
        if (src == 0) {
            PlayerController pc = QplayerScreen.getPlayerController();
            if (pc != null) {
                byte[] bytes = pc.coverBytes.peek();
                if (bytes != null) CoverSeedSampler.get().forceNow(bytes);
            }
        }
    }

    private void applySeed(double h, double c, double t) {
        try {
            // __instance() is typed java.lang.Object in the runtime; cast before use.
            ((StyleManager) StyleManager.__instance()).setSeedColorHct(h, c, t);
        } catch (Throwable ex) {
            QplayerModClient.LOGGER.warn("[QPlayer] Monet seed apply failed: {} ({})",
                ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private void requestSample(RenderTarget rt) {
        int w = rt.width, h = rt.height;
        int down = pickDownscale(w, h);
        inFlight = true;
        lastRequestAt = System.currentTimeMillis();
        try {
            Screenshot.takeScreenshot(rt, down, this::onImageReady);
            QplayerModClient.LOGGER.debug("[QPlayer] Monet screenshot {}x{} ×{}", w, h, down);
        } catch (Throwable t) {
            inFlight = false;
            QplayerModClient.LOGGER.warn("[QPlayer] Monet screenshot failed: {} ({})",
                t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /**
     * Largest common divisor of both dimensions. Unlike the old version that
     * tried to also keep the long side ≤ TARGET_MAX_DIM, this always returns
     * the GCD so the GPU→CPU copy is at most ~1/16 of the original. The
     * callback then does a CPU stride-downscale to TARGET_MAX_DIM if needed.
     */
    private static int pickDownscale(int w, int h) {
        int a = w, b = h;
        while (b != 0) { int t = b; b = a % b; a = t; }
        int gcd = a;
        return gcd > 0 ? gcd : 1;
    }

    /** GPU readback callback — fires on the client thread after the copy completes. */
    private void onImageReady(NativeImage img) {
        try {
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return;
            int iw = img.getWidth(), ih = img.getHeight();
            int[] abgr = img.getPixelsABGR();
            if (abgr == null || abgr.length == 0) return;

            // CPU stride-downscale: the GPU readback may be larger than our target
            // (e.g. 427×240 when the GCD is 4), so we pick every Nth pixel to stay
            // under TARGET_MAX_DIM before giving the pixels to the quantizer.
            int stride = (int) Math.max(1, Math.ceil(Math.max(iw, ih) / (double) TARGET_MAX_DIM));
            int sw = (iw + stride - 1) / stride;
            int sh = (ih + stride - 1) / stride;
            int[] src = abgr;
            if (stride > 1) {
                int[] down = new int[sw * sh];
                int k = 0;
                for (int y = 0; y < ih; y += stride) {
                    for (int x = 0; x < iw; x += stride) {
                        down[k++] = src[y * iw + x];
                    }
                }
                src = down;
            }

            QplayerModClient.LOGGER.info("[QPlayer] Monet readback {}x{} → {}x{} ({} px)",
                iw, ih, sw, sh, src.length);

            // Detach the pixels (ABGR → ARGB) and quantize off-thread
            final int[] argb = new int[src.length];
            for (int i = 0; i < src.length; i++) {
                int p = src[i];
                argb[i] = (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >>> 16) & 0xFF);
            }
            Thread worker = new Thread(() -> {
                try {
                    double[] hct = MonetQuantizer.extractSeedColorHct(argb, ACCURACY);
                    pendingSeed = hct;
                } catch (Throwable ex) {
                    QplayerModClient.LOGGER.warn("[QPlayer] Monet quantize failed: {} ({})",
                        ex.getClass().getSimpleName(), ex.getMessage());
                }
            }, "qplayer-monet-quantize");
            worker.setDaemon(true);
            worker.start();
        } finally {
            if (img != null) img.close();
            inFlight = false;
        }
    }
}