package com.ylmac.adapt.render.hud;

import com.ylmac.adapt.render.font.RiseFont;

import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.module.ModuleRegistry;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import com.ylmac.adapt.theme.StyleManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import java.text.SimpleDateFormat;
import java.util.*;
import com.ylmac.adapt.render.surface.MinecraftSurfaceBackend;
import com.ylmac.adapt.render.profile.Probe;
import com.ylmac.adapt.render.surface.SkijaNativeFix;

/** 右上角模块列表。模式：Modern、Classic、Wurst、Creida。 */
public final class ModuleListOverlay implements HudElement {

    public enum Mode { MODERN, CLASSIC, WURST, CREIDA }
    public static final String INTERFACE_MODULE = "Interface";

    // surface 物理倍率 = 2.0（2x 超采样，WorldLyric 同款）：
    // 位图 18px → 9 GUI → 18px surface（1:1 位图数据完整保留）
    // → blit 1280→640 GUI→854 屏幕 = 18px 数据 → 12px 屏幕（缩小 1.5，超采样缩小清晰）
    // 任何 GUI scale 下都清晰（数据密度 2x，blit 缩小不丢主体）
    private float surfGs = 2.0f;   // 当前设备倍率（bloom 的 margin 换算用）
 /** HUD 画布的设计宽度— 坐标换算基准。 */
    private static final int DESIGN_WIDTH = 854;
    private static ModuleListOverlay instance;

    private MinecraftSurfaceBackend surf;
    private boolean registered;

    private final Paint paint = new Paint();

    private Image wurstLogo;
    private boolean assetsReady;
    /** BLOOM 的模糊核是常数 σ=7，按帧新建再 close 只是在反复建同一个 SkImageFilter。 */
    private io.github.humbleui.skija.ImageFilter bloomFilter;
    private Paint bloomPaint;

    private static final class Entry {
        String name, tag;
        String lastName = "", lastTag = "", lastFont = "", lastTagFont = "";  // 宽度缓存 key（tag/name/字体变化才重算）
        String tagRule = "";  // tag 宽度规则 key（模式不同：+3/+4/0）
        boolean enabled, hasTag;
        float animTime;           // 0..10
        float posX, posY;         // position（初始 5000,0 屏幕外）
        float nameWidth, tagWidth;
        int color = 0xFFFFFFFF;   // zc.color（默认 WHITE）
    }
    private final Map<String, Entry> allEntries = new LinkedHashMap<>();
    private final java.util.List<Entry> activeEntriesForLog = new ArrayList<>();
    private java.util.List<ModuleRegistry.ModuleEntry> cachedModules;
    private long lastFrameTime;

    private int hudBackgroundColor() {
        return withAlpha(0x000000, backgroundMode == 1 ? backgroundAlpha : 150);
    }

    public boolean normalBackGround = true;
 /** Modern: 0=Static 1=Fade 2=Breathe */
    public int modernColorMode = 1;
 /** Modern Shader Effect: 0=Glow 1=Shadow
 * Glow → 背景栏 = 行色不透明（底下的栏变色）
 * Shadow → 文字下方先画黑字作阴影 */
    public int shadowEffectMode = 1;
 /** Classic: 0=Fade 1=Rainbow 2=Trans */
    public int classicColorMode = 1;
 /** 列表背景— Normal = 磨砂玻璃底 */
    public int backgroundMode = 1;
    /**
 * 背景不透明度。
 * <p>
 * {@code Themes.c(new Color(0,0,0, shaders ? backgroundAlpha: 150))} —
 * <b>开了模糊（Shaders）时用 110，没开时用 150</b>。之前我们固定 150，
 */
    public int backgroundAlpha = 110;
    /** 本帧截取+模糊的帧缓冲（磨砂玻璃用；extractRenderState 里赋值并在渲染后释放） */
    private Image frameBackdrop;
    /** 模糊不可用只警告一次（避免刷屏） */
    private boolean loggedBlurUnavailable;

 /** 渲染模块类别 */
    // 显示全部启用模块（含渲染分类）。用户要求音乐播放器（RENDER）在 HUD 显示；
    // 之前 "Exclude render" 把渲染分类过滤掉 → 音乐播放器永远不出现。
    public String modulesToShow = "All";

    public volatile Mode mode = Mode.MODERN;
    /** 模块列表 HUD 可见性（真实"模块列表"模块控制；默认可见） */
    private volatile boolean overlayVisible = true;

    public void setOverlayVisible(boolean visible) { this.overlayVisible = visible; }

    /** Modern 的玻璃 “a” 每帧都要世界颜色。Creida 只有磨砂底开着才采。 */
    public static boolean wantsBackdrop() {
        ModuleListOverlay overlay = get();
        if (!overlay.overlayVisible) return false;
        if (overlay.mode == Mode.MODERN) return true;
        return overlay.backgroundMode == 1 && overlay.mode == Mode.CREIDA;
    }

    private ModuleListOverlay() {}
    public static ModuleListOverlay get() {
        if (instance == null) instance = new ModuleListOverlay();
        return instance;
    }
    public void register() {
        if (registered) return;
        registered = true;
        HudElementRegistry.addLast(Identifier.parse("adaptclient:module_list"), this);
    }

    /** ColorUtil.a(Color a, Color b, double t) = a*t + b*(1-t)（alpha 恒 255） */
    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar * t + br * (1 - t));
        int g = Math.round(ag * t + bg * (1 - t));
        int bl = Math.round(ab * t + bb * (1 - t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

 /** ColorUtil.withAlpha(color, a)：alpha clamp 0..255 */
    private static int withAlpha(int rgb, int a) {
        int alpha = Math.max(0, Math.min(255, a));
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    /** Themes.getBlendFactor = sin(now/600 + x*0.005 + y*0.06)*0.5+0.5 */
    private static float blendFactor(float x, float y) {
        return (float)(Math.sin(System.currentTimeMillis() / 600.0 + x * 0.005 + y * 0.06) * 0.5 + 0.5);
    }

    /** 当前主题 rA/rB/rC（默认 BLEND：(71,148,253)/(71,253,160)；本客户端由 StyleManager 提供） */
    // 主题色缓存（每帧反射 StyleManager 慢；scheme 引用变化时更新）
    private int[] cachedTheme;
    private Object cachedScheme;

    private int[] getThemeColors() {
        try {
            Object inst = StyleManager.__instance();
            if (inst instanceof StyleManager) {
                Object scheme = ((StyleManager) inst).currentScheme.peek();
                if (scheme == cachedScheme && cachedTheme != null) return cachedTheme;
                cachedScheme = scheme;
                int rA = 0xFF4794FD, rB = 0xFF47FDA0, rC = rA;
                if (scheme instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> m = (Map<String, String>) scheme;
                    rA = parseHex(m.get("primary"), rA);
                    rB = parseHex(m.get("secondary"), rB);
                    String t = m.get("tertiary");
                    if (t != null) rC = parseHex(t, rA);
                }
                cachedTheme = new int[]{rA, rB, rC};
                return cachedTheme;
            }
        } catch (Exception ignored) {}
        return new int[]{0xFF4794FD, 0xFF47FDA0, 0xFF4794FD};
    }
    private static int parseHex(String hex, int fallback) {
        if (hex == null) return fallback;
        try {
            String s = hex.startsWith("#") ? hex.substring(1) : hex;
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (Exception e) { return fallback; }
    }

    /** Themes.getAccentColor(Vector2d)：
 * 双色: lerp(rA, rB, blendFactor)
 * 三色: d<=0.5 ? lerp(rB,rA,d*2): lerp(rC,rB,(d-0.5)*2) */
    private int accentColor(float x, float y, int[] tc) {
        if (tc[2] == tc[0]) { // 双色（rC==rA 表示非三色）
            return lerpColor(tc[0], tc[1], blendFactor(x, y));
        }
        float d = blendFactor(x, y);
        return d <= 0.5f ? lerpColor(tc[1], tc[0], d * 2f) : lerpColor(tc[2], tc[1], (d - 0.5f) * 2f);
    }

    /** ColorUtil.aB(offset)：HSB rainbow，饱和 0.6 亮度 1.0，色相每 10ms 步进 1 度 */
    private static int getRainbow(int offset) {
        float hue = (float)(Math.ceil((System.currentTimeMillis() + offset) / 10.0) % 360.0 / 360.0);
        return java.awt.Color.HSBtoRGB(hue, 0.6f, 1.0f) | 0xFF000000;
    }

    /** MathUtil.m(current, target, factor) = current + factor * (target - current) */
    private static float lerp(float cur, float target, float factor) {
        return cur + factor * (target - cur);
    }

    // 绘图原语

    /** = 实心矩形 */
    private void drawRect(Canvas canvas, float x, float y, float w, float h, int color) {
        paint.setColor(color);
        canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint);
    }

    private void drawRoundedRect(Canvas canvas, float x, float y, float w, float h, float r, int color) {
        paint.setColor(color);
        canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r), paint);
    }

    // 主渲染

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        // 模块列表可见性由真实"模块列表"模块（InterfaceModule）控制
        if (!overlayVisible) return;
        Probe.begin("hud.moduleList");
        try {
            extractModuleList(g, dt);
        } finally {
            Probe.end("hud.moduleList");
        }
    }

    private void extractModuleList(GuiGraphicsExtractor g, DeltaTracker dt) {
        // 之前"GUI 打开跳过"是旧修复，导致设置界面里看不到列表/光晕（用户要求移除）。
        if (Minecraft.getInstance().gui.screen() != null) {
            lastFrameTime = 0;  // GUI 期间不推进动画；关闭后首帧 elapsed=16 平滑过渡
        }
        ensureAssets();

        try {
            Minecraft mc = Minecraft.getInstance();
            int guiW = mc.getWindow().getGuiScaledWidth();
            int guiH = mc.getWindow().getGuiScaledHeight();
 // surface 固定设计分辨率 854x480×2 超采样：
            // （WorldLyric 同款：固定逻辑 + 显示时 pose.scale 适配，避免 MC 26.2 Mac
            // 非整数 GUI scale（如 2.67）导致文字被放大 + 亚像素错位）
            final int DESIGN_W = DESIGN_WIDTH;
            // 画布高度动态 = max(480, guiH/scale)：窗口 GUI 300 高时画布 534 高，
            // 版本行（贴底）才能画在画布内（等比缩放后 = GUI 底部）。全屏时 = 480。
            float sc = Math.min(guiW / (float) DESIGN_W, guiH / 480f);
            int effH = Math.max(480, (int) Math.ceil(guiH / sc));
            // 原生设备像素：物理纹理 = 设计尺寸 × 显示缩放 × 窗口 GUI scale
            // → 屏幕上一个设计像素正好一个设备像素（SkikoBackend 同款：不超采样、
            // 贴图 1:1，抗锯齿边缘不会被二次重采样）。原来固定 2× 在大窗口下
            // 只有屏幕的 ~0.6 倍（放大发虚），小窗口下又白超采样。
            float deviceScale = sc * mc.getWindow().getGuiScale();
            // 物理像素不超过帧缓冲。比屏幕更大的纹理 blit 时会被缩小，多出来的像素看不见。
            int fbW = mc.getWindow().getWidth();
            int fbH = mc.getWindow().getHeight();
            int wantW = Math.max(1, Math.round(DESIGN_W * deviceScale));
            int wantH = Math.max(1, Math.round(effH * deviceScale));
            if (fbW > 0 && fbH > 0 && (wantW > fbW || wantH > fbH)) {
                deviceScale *= Math.min(fbW / (float) wantW, fbH / (float) wantH);
            }
            if (surf == null || surf.width() != DESIGN_W || surf.height() != effH) {
                if (surf != null) surf.dispose();
                surf = new MinecraftSurfaceBackend("module_list", DESIGN_W, effH, deviceScale);
            } else {
                surf.ensureDeviceScale(deviceScale);
            }
            surfGs = deviceScale;

            Canvas canvas = surf.acquireCanvas();
            if (canvas == null) return;
            long t0 = System.nanoTime();

            // 磨砂玻璃底：用**本帧的共享截帧**做模糊。
            // ⚠ 不要再调 captureFrame：截帧每帧只有一次，由 GameRendererMixin 在
            // renderLevel 返回时（世界画完、GUI 之前）写进 BackdropBlurCapture 的
            // getBlurredImage，不自己截）就会拿到被 GUI 污染的帧，表现为"开了列表
            // 模糊之后 的模糊失效"（用户实测）。
            frameBackdrop = null;
            if (backgroundMode == 1 && (mode == Mode.MODERN || mode == Mode.CREIDA)) {
                frameBackdrop = BackdropBlurCapture.getBlurredImage();
                if (frameBackdrop == null && !loggedBlurUnavailable) {
                    loggedBlurUnavailable = true;
                    AdaptClient.LOGGER.warn(
                            "[ModuleList] backdrop blur unavailable (capture/GPU wrap failed)");
                }
            }

            int[] tc = getThemeColors();
            try {
                render(canvas, DESIGN_W, effH, tc, guiW, guiH);
            } finally {
                // ⚠ 不要 close：frameBackdrop 由 BackdropBlurCapture 每帧缓存持有，
                // 关掉它会让后面渲染的 HUD 元素（聊天/积分榜/小组件）拿到已关闭的图
                // → 表现为"只有功能列表有模糊"。只需清引用。
                frameBackdrop = null;
            }
            surf.present();

            long t1 = System.nanoTime();
            if ((t1 - t0) > 8_000_000L) {  // >8ms 记录（临时性能日志）
                AdaptClient.LOGGER.warn("[ModuleList] frame {}ms", (t1 - t0) / 1_000_000.0);
            }

            int texW = surf.getDisplayWidth();
            int texH = surf.getDisplayHeight();
            var pose = g.pose();
            pose.pushMatrix();
            // 设计分辨率 → 当前 GUI：等比缩放（GUI 宽高比 ≠ 设计宽高比时不等比会压扁文字）
            // scale = min(guiW/DESIGN_W, guiH/DESIGN_H) → 宽高同比例，不压扁；
 // 贴顶部，
            // 不做垂直居中 — 居中会让全屏/窗口切换时内容整体上下移动。
            float scale = Math.min(guiW / (float) DESIGN_W, guiH / 480f);
            pose.translate((guiW - DESIGN_W * scale) / 2, 0);
            pose.scale(scale, scale);
            g.blit(surf.blitPipeline(), surf.getTextureId(),
                    0, 0, 0f, 0f, DESIGN_W, effH, texW, texH, texW, texH);
            pose.popMatrix();
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[ModuleList] render error: {}", t.toString(), t);
        }
    }

    /** 只加载字体/logo。表面放到知道设计尺寸和设备倍率之后再建，避免先按 GUI scale 撑出一块比屏幕还大的纹理再扔掉。 */
    private void ensureAssets() {
        if (assetsReady) return;
        assetsReady = true;
        try {
            SkijaNativeFix.preload();
            byte[] logoBytes = loadBytes("fonts/wurst.png");
            if (logoBytes != null) {
                try { wurstLogo = Image.makeFromEncoded(logoBytes); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[ModuleList] init failed: {}", t.toString());
        }
    }

    private Paint bloomLayerPaint() {
        if (bloomPaint == null) {
            bloomFilter = io.github.humbleui.skija.ImageFilter.makeBlur(
                    7f, 7f, io.github.humbleui.skija.FilterTileMode.DECAL);
            bloomPaint = new Paint().setImageFilter(bloomFilter);
        }
        return bloomPaint;
    }

 // 布局算法

 /** 渲染模块类别过滤
 * Only bound 依赖按键绑定（本客户端无绑定概念 → 降级为 All） */
    private boolean shouldShow(ModuleRegistry.ModuleEntry m) {
        if (m.name.equals(INTERFACE_MODULE)) return false;
        if (m.isHidden()) return false; // .hide/.show 命令：HUD 隐藏
        switch (modulesToShow) {
            case "All": return true;
            case "Only bound": return true;
            case "Exclude render":
            default: return !"渲染".equals(m.category);
        }
    }

    private void render(Canvas canvas, int scrW, int scrH, int[] tc, int guiW, int guiH) {
        long now = System.currentTimeMillis();
        long elapsed = lastFrameTime == 0 ? 16 : now - lastFrameTime;
        // StopWatch 每帧重置，正常 16ms；这里上限 50ms 防过冲）
        if (elapsed > 50) elapsed = 50;
        lastFrameTime = now;

 // 各模式设置 moduleSpacing / edgeOffset / 字体
        RiseFont font = getModeFont();
        float moduleSpacing = getModuleSpacing(font);
        float edgeOffset = getEdgeOffset();
        float f = (mode == Mode.CLASSIC) ? 10f : 100f;
        String tagRuleKey = mode.name();  // tag 宽度规则（模式切换强制重算）

 // ── 条目数据更新──
        if (cachedModules == null) cachedModules = ModuleRegistry.getAllModules(); // 模块表静态，缓存
        for (ModuleRegistry.ModuleEntry m : cachedModules) {
            Entry e = allEntries.computeIfAbsent(m.name, k -> {
                Entry ne = new Entry();
                ne.posX = 5000; ne.posY = 0;
                return ne;
            });
            e.name = m.name;
            e.enabled = m.isEnabled();
            e.tag = m.tag != null ? m.tag : "";
            e.hasTag = e.tag.length() > 0;
            // 宽度缓存：name/tag/字体 不变时不重算（getStringWidth 逐字符遍历）
            // 注意：tag 分支必须独立检查字体（不能复用 lastFont，否则 name 先跑后 tag 永不重算）
            String fk = font.cacheKey();
            if (!e.name.equals(e.lastName) || !fk.equals(e.lastFont)) {
                e.lastName = e.name;
                e.lastFont = fk;
                e.nameWidth = font.getStringWidth(m.name);
            }
            if (!e.tag.equals(e.lastTag) || !fk.equals(e.lastTagFont) || !e.tagRule.equals(tagRuleKey)) {
                e.lastTag = e.tag;
                e.lastTagFont = fk;
                e.tagRule = tagRuleKey;
                switch (mode) {
                    case CREIDA:  e.tagWidth = e.hasTag ? font.getStringWidth(e.tag) + 4f : 1f; break;
                    case WURST:   e.tagWidth = 0f; break;
                    default:      e.tagWidth = e.hasTag ? font.getStringWidth(e.tag) + 3f : 0f; break; // Modern/Classic: +3 / 0
                }
            }
        }

 // ── animationTime 更新──
        for (ModuleRegistry.ModuleEntry m : cachedModules) {
            Entry e = allEntries.get(m.name);
            if (e == null) continue;
            if (m.isEnabled()) e.animTime = Math.min(e.animTime + elapsed / f, 10f);
            else               e.animTime = Math.max(e.animTime - elapsed / f, 0f);
        }

 // ── createArrayList：过滤 + 排序──
        List<Entry> active = new ArrayList<>();
        for (ModuleRegistry.ModuleEntry m : cachedModules) {
            Entry e = allEntries.get(m.name);
            if (e != null && e.animTime != 0 && shouldShow(m)) {
                active.add(e);
            }
        }
        active.sort((a, b) -> Float.compare(-(a.nameWidth + a.tagWidth), -(b.nameWidth + b.tagWidth)));
        activeEntriesForLog.clear();
        activeEntriesForLog.addAll(active);

 // ── 位置计算 + lerp──
        float stackY = 0;
        for (Entry e : active) {
            float tx = scrW - e.nameWidth - e.tagWidth; // targetPosition.x
            float ty = stackY;                          // targetPosition.y
            if (!e.enabled && e.animTime < 10f) {
                tx = scrW + e.nameWidth + e.tagWidth;   // 禁用滑出
            } else {
                stackY += moduleSpacing;
            }
            tx -= edgeOffset;
            ty += edgeOffset;

 // snap 或 lerp
            if (Math.abs(e.posX - tx) <= 0.5f && Math.abs(e.posY - ty) <= 0.5f
                    && (e.animTime == 0f || e.animTime == 10f)) {
                e.posX = tx;
                e.posY = ty;
            } else {
                float factor = 0.015f * elapsed; // MathUtil.m(pos, target, 0.015*elapsed)
                e.posX = lerp(e.posX, tx, factor);
                e.posY = lerp(e.posY, ty, factor);
            }

            if (mode == Mode.MODERN) {
                if (modernColorMode == 2) {
                    // Breathe: color = ColorUtil.a(rA, rB, blendFactor(0,0))
                    e.color = lerpColor(tc[0], tc[1], blendFactor(0, 0));
                } else {
                    // Fade（default）: color = getAccentColor(0, posY)
                    e.color = accentColor(0, e.posY, tc);
                }
                // Static: color = rA（不设，用默认 WHITE → 由 getThemeColors 的 rA 提供）
                if (modernColorMode == 0) e.color = tc[0];
            }
        }

        // ── 模式分发渲染 ──
        switch (mode) {
            case MODERN:  renderModern(canvas, active, scrW, scrH, tc, font, moduleSpacing, guiW, guiH); break;
            case CLASSIC: renderClassic(canvas, active, scrW, scrH, tc, font); break;
            case WURST:   renderWurst(canvas, active, scrW, scrH, font); break;
            case CREIDA:  renderCreida(canvas, active, scrW, scrH, tc, guiW, guiH); break;
        }
    }

    // ── 各模式字体/布局参数 ──
    private RiseFont getModeFont() {
        switch (mode) {
            case MODERN:  return RiseFont.main(18, RiseFont.Weight.REGULAR);  
            case CLASSIC: return RiseFont.main(18, RiseFont.Weight.REGULAR);  // ZH:
            case WURST:   return RiseFont.main(18, RiseFont.Weight.REGULAR);  // ZH:
            case CREIDA:  return RiseFont.main(24, RiseFont.Weight.REGULAR);  // getFont font=true → 24
        }
        return RiseFont.main(18, RiseFont.Weight.REGULAR);
    }
    private float getModuleSpacing(RiseFont font) {
        switch (mode) {
            case MODERN:  return font.height();        // n(height + 0)
            case CLASSIC: return font.height() + 2;    // n(height + 2)
            case WURST:   return font.height() + 1;    // n(height + 1)
            case CREIDA:  return 12f;                  // n(MC 9 + 3)
        }
        return font.height();
    }
    private float getEdgeOffset() {
        switch (mode) {
            case MODERN:  return 10f;      // setEdgeOffset(10)
            case CLASSIC: return 3f;       // setEdgeOffset(3)
            case WURST:   return 4f;       // setEdgeOffset(4)
            case CREIDA:  return 3f;       // setEdgeOffset(3)
        }
        return 10f;
    }

 // Modern 模式

    private void renderModern(Canvas canvas, List<Entry> active, int scrW, int scrH, int[] tc, RiseFont font, float moduleSpacing, int guiW, int guiH) {
        float d2 = 2.0f; // bl=false → d2=2.0

 // ══ BLOOM 队列：内容离屏图层 → 高斯模糊 → 混合回主帧 ══
        // ONE_MINUS_SRC_ALPHA) 混合回主帧 = 光晕。
        // Skia 等价：canvas.saveLayer(带 blur ImageFilter 的 paint) → 画内容 → restore，
 // 模糊结果用普通 alpha 混合回主 canvas。
        // ⚠ 这是用户唯一验证过可见的方案（"之前列表有光晕"）；FBO snapshot 方案在 MoltenVK 上
        // 内容丢失/不可见（诊断确认内容未写进离屏 FBO）。
        float cMaxX = 6 + 60, cMaxY = 6 + 60;
        for (Entry e : active) {
            cMaxX = Math.max(cMaxX, e.posX + e.nameWidth + e.tagWidth + 20);
            cMaxY = Math.max(cMaxY, e.posY + moduleSpacing + 20);
        }
        cMaxX = Math.min(scrW, cMaxX); cMaxY = Math.min(scrH, cMaxY);
        // BLOOM 区域固定覆盖"logo + 全列表"（不随动画逐帧变，避免 saveLayer 每帧重建）
        float bloomMaxX = Math.min(scrW, (float) Math.max(cMaxX, 6 + 60 + 12));
        float bloomMaxY = Math.min(scrH, (float) Math.max(6 + 40 + 12, cMaxY));
        if (bloomMaxX > 1 && bloomMaxY > 1) {
            try {
                // ⚠ 实测（BlurScale 探针）：Skia 的 ImageFilter sigma 是**逻辑单位**，
                // 会被 canvas CTM 再乘一次设备倍率。所以这里给设计空间的 7，而不是
                // 原来的 7×surfGs（那会把光晕放大 surfGs 倍，换成原生倍率后会糊成一片）。
                float sigma = 7f;
                // ⚠ 必须用 FilterTileMode.DECAL（Skia 源码 SkBlurImageFilter.cpp:90-93,180-187）：
                // makeBlur 默认 CLAMP 走 legacy 分支，fLegacyTileMode!=kDecal 时把模糊输入
                // 裁剪回 layer 边界再做 clamp tiling — 文字位图（小且贴边）的光晕被裁没；
 // DECAL = 透明边缘正常衰减，光晕完整扩散。
                Paint bloomPaint = bloomLayerPaint();
                // ⚠ saveLayer 的 margin 必须 ≥ 3σ（Skia 源码 SkBlurImageFilter.cpp:64-69：
                // kernelBounds = 3*sigma 外扩）。blur 需要在内容四周有 3σ 的透明边缘空间，
                // 否则光晕被 layer surface 边界裁剪。logo 在左上角 (6,6)，若 margin 只有
                // 1σ（26px < 3σ=42px），logo 光晕向左/向上被裁没 — 这就是"列表栏有光晕
                // （中间空间足）、logo 文字没有（贴左上角）"的根因。
                float margin = 3f * sigma + 2f;   // 3σ（逻辑单位）— sigma 已是逻辑值
                float slX = -margin, slY = -margin;
                float slR = Math.min(scrW, bloomMaxX + margin), slB = Math.min(scrH, bloomMaxY + margin);
                canvas.saveLayer(Rect.makeXYWH(slX, slY, slR - slX, slB - slY), bloomPaint);
                // 玻璃 “a” 不进这层。字本身不发光，叠完再按字形把漫过来的光晕抠掉。
                // normalBackGround=true 时颜色 = glow ? 行色: Themes.rE（黑 190）。
                // 所以 Shadow 模式下是"每条背后一圈黑色投影"，不是黑字！
                // 之前我们 Shadow 模式只在 bloom 层画黑字、不画栏 → 列表没有阴影
                // （用户："右上角的功能列表其实也应该有阴影的"）。
                if (normalBackGround) {
                    for (Entry e : active) {
                        float x = e.posX, y = e.posY;
                        int color = shadowEffectMode == 0 ? withAlpha(e.color, 255) : 0xC0000000;
                        drawRect(canvas, x - d2, y - 3, e.nameWidth + e.tagWidth + 3 + d2, moduleSpacing, color);
                    }
                } else if (shadowEffectMode == 0) {
                    for (Entry e : active) {
                        float x = e.posX, y = e.posY;
                        drawRect(canvas, x - d2, y - 3, e.nameWidth + e.tagWidth + 3 + d2, moduleSpacing, withAlpha(e.color, 255));
                    }
                }
                for (Entry e : active) {
                    float x = e.posX, y = e.posY;
                    int color = e.color;
                    if (shadowEffectMode == 1) {
                        font.drawText(canvas, e.name, x, y - 0.5f, 0xFF000000, paint);
                        if (e.hasTag) font.drawText(canvas, e.tag, x + e.nameWidth + 3, y - 0.5f, 0xFF000000, paint);
                    } else {
                        font.drawText(canvas, e.name, x + 0.5f, y, color, paint);
                        if (e.hasTag) font.drawText(canvas, e.tag, x + e.nameWidth + 3 + 0.5f, y, 0xFFCCCCCC, paint);
                    }
                }
                canvas.restore();
                GlassMark.punchBloom(canvas, surfGs);
            } catch (Throwable t) {
                AdaptClient.LOGGER.warn("[ModuleList] bloom render failed: {}", t.toString());
            }
        }

 // ══ REGULAR 队列：每帧清晰绘制 ══
        // 再注册所有文字（L186）+ sidebar（L188）。文字必须盖在栏上 — 若 per-entry
        // 画"栏+文字"，下一行的栏会盖住上一行文字（中文 ink 高 ~11px > 行距 10.9，
        // 文字延伸到下一行栏区，被后画的栏覆盖 = 用户看到的"汉字 1/3 被覆盖"）。
        // 分两遍：先全部栏 + sidebar，再全部文字。
        for (Entry e : active) {
            float x = e.posX, y = e.posY;
            int color = e.color;
            // — 原版把这块矩形当模糊遮罩，在 OVERLAY 阶段把屏内容模糊后合成回来）
            if (backgroundMode == 1) {
                drawBackdropBlur(canvas, x - d2, y - 3,
                        e.nameWidth + e.tagWidth + 3 + d2, moduleSpacing, guiW, guiH);
            }
 // 背景栏：Themes.rK
            drawRect(canvas, x - d2, y - 3, e.nameWidth + e.tagWidth + 3 + d2, moduleSpacing,
                    hudBackgroundColor());
 // sidebar: roundedRect(x+w+2, y-1.5, 2, 9, 1, color)
            drawRoundedRect(canvas, x + e.nameWidth + e.tagWidth + 2, y - 1.5f, 2, 9, 1, color);
        }
        for (Entry e : active) {
            float x = e.posX, y = e.posY;
            int color = e.color;
 // 文字: drawEntry(zc, x, y-0.5, color) — 后画，盖在栏上
            font.drawText(canvas, e.name, x, y - 0.5f, color, paint);
            if (e.hasTag) font.drawText(canvas, e.tag, x + e.nameWidth + 3, y - 0.5f, 0xFFCCCCCC, paint);
        }

 // 右下版本行
        // f3 = 画布高 - fontHeight - 1（画布高 = effH，等比缩放后 = GUI 底部）
        // 窗口时画布 534 高 → 版本行 y=522 → 缩放 0.562 → GUI 293 = 距底 7px；
        // 全屏时画布 480 → y=468 → GUI 263 = 距底 7px。恒定贴底。
        float f3 = scrH - font.height() - 1;
        RiseFont reg18 = RiseFont.main(18, RiseFont.Weight.REGULAR);
        RiseFont med18 = RiseFont.main(18, RiseFont.Weight.MEDIUM);
        RiseFont bold18 = RiseFont.main(18, RiseFont.Weight.BOLD);
        RiseFont reg20 = RiseFont.main(20, RiseFont.Weight.REGULAR);

        String versionLabel = "Version:\u2009\u2009\u2009\u2009\u2009\u2009";
        String versionNum = "6.9.5";
        String userLabel = "User:";
        String userName = getPlayerName();

        float f4 = med18.getStringWidth(versionLabel);   
        float f5 = bold18.getStringWidth(versionNum);    
        float f6 = reg20.getStringWidth(userLabel);      // regular20Font
        float f7 = reg20.getStringWidth(userName);       // regular20Font
        float f8 = scrW - (f4 + f5 + f6 + f7) - 6.0f;    // f9 = f2 - sum - 6

        reg18.draw(canvas, versionLabel, f8, f3, 0xFFCCCCCC, paint);
        bold18.draw(canvas, versionNum, f8 + f4, f3, 0xFFCCCCCC, paint);
        float f9 = f8 + f4 + f5 + 5.0f;
        reg18.draw(canvas, userLabel, f9, f3, 0xFFCCCCCC, paint);
        med18.draw(canvas, userName, f9 + f6, f3, 0xFFCCCCCC, paint);

 // 左下 XYZ
        double[] pos = getPlayerPos();
        String coords = (int) Math.floor(pos[0]) + ", " + (int) Math.floor(pos[1]) + ", " + (int) Math.floor(pos[2]);
        float xyzWidth = med18.getStringWidth("XYZ:") + 2; // xyzWidth = medium18Font.getStringWidth("XYZ:") + 2
        reg18.draw(canvas, "XYZ:", 5, f3, 0xFFCCCCCC, paint);
        med18.draw(canvas, coords, 5 + xyzWidth, f3, 0xFFCCCCCC, paint);

        GlassMark.draw(canvas, BackdropBlurCapture.getFrameImage(), guiW, guiH, surfGs);
    }

 // Classic 模式

    private static final int[] CLASSIC_COLORS = {0xFF5BCEFA, 0xFFF5A9B8, 0xFFFFFFFF, 0xFFF5A9B8};

    private void renderClassic(Canvas canvas, List<Entry> active, int scrW, int scrH, int[] tc, RiseFont font) {
        int i = 0;   // rainbow 索引
        int j = 0;   // 色盘索引

        for (Entry e : active) {
            float x = e.posX, y = e.posY;

 // 颜色
            int color1;
            switch (classicColorMode) {
                case 0: // Fade: color1 = getAccentColor(0, posY)
                    color1 = accentColor(0, e.posY, tc);
                    break;
                case 2: // Trans: color1 = rA（仅 Trans 分支）
                    color1 = tc[0];
                    break;
                case 1: // Rainbow（default）: color1 = getRainbow(500*i/6)
                default:
                    color1 = getRainbow(500 * i / 6);
                    break;
            }

 // 文字: agc.b(displayName, x, y, zc.getColor.getRGB)
            // — 注意：画的是上一帧的 zc.getColor（L71: color = zc.getColor）
            font.drawText(canvas, e.name, x, y, e.color, paint);
            i++;
            j = (j + 1) % CLASSIC_COLORS.length;

 // tag: font.b(displayTag, x+nameWidth+3, y, 0xFFCCCCCC)
            if (e.hasTag) font.drawText(canvas, e.tag, x + e.nameWidth + 3, y, 0xFFCCCCCC, paint);

            e.color = color1;
        }

 // watermark: getWatermark = "R" 首字符主题色 + "§7ise" 灰
        int k = classicColorMode == 1 ? getRainbow(1000) : tc[0];
        font.draw(canvas, "R", 3, 3, k, paint);
        font.draw(canvas, "ise", 3 + font.getStringWidth("R"), 3, 0xFFAAAAAA, paint);

 // XYZ 三行: d0 = scrH - 10
        double[] pos = getPlayerPos();
        float d0 = scrH - 10;
        float lineH = font.height();
        int gray = 0xFFAAAAAA;
        float lx = font.getStringWidth("X:");
        font.draw(canvas, "X:", 3, d0 - lineH * 2, k, paint);
        font.draw(canvas, " " + (int) pos[0], 3 + lx, d0 - lineH * 2, gray, paint);
        font.draw(canvas, "Y:", 3, d0 - lineH, k, paint);
        font.draw(canvas, " " + (int) pos[1], 3 + lx, d0 - lineH, gray, paint);
        font.draw(canvas, "Z:", 3, d0, k, paint);
        font.draw(canvas, " " + (int) pos[2], 3 + lx, d0, gray, paint);
    }

 // Wurst 模式

    private void renderWurst(Canvas canvas, List<Entry> active, int scrW, int scrH, RiseFont font) {
        // 条目：（ZH →，无阴影）
        for (Entry e : active) {
            font.drawText(canvas, e.name, e.posX, e.posY, 0xFFFFFFFF, paint);
        }

 // 白条:)
        drawRect(canvas, 0, 10, 185, 12, withAlpha(0xFFFFFFFF, 100));

        if (wurstLogo != null) {
            paint.setColor(0xFFFFFFFF);
            canvas.drawImageRect(wurstLogo,
                Rect.makeXYWH(2, 5.5f, 89.17647f, 22.588236f),
                paint);
        }

 // "v6 MC 1.8.9": 无阴影
        font.draw(canvas, "v6 MC 1.8.9", 95, 14, 0xFF000000, paint);
    }

 // Creida 模式

    private void renderCreida(Canvas canvas, List<Entry> active, int scrW, int scrH, int[] tc,
                              int guiW, int guiH) {
        RiseFont font24 = RiseFont.main(24, RiseFont.Weight.REGULAR); // getFont font=true → 24
        float rowHeight = 11f; // minecraftFont.height+2 = 9+2 = 11

        for (Entry e : active) {
            float d0 = e.posX - 3;        // pos.x - 1 - 2
            float d1 = e.posY + 1;        // pos.y + 1
            float d2 = e.nameWidth + e.tagWidth + 5; // nameWidth + tagWidth + 2 + 3

 // 磨砂玻璃底
            if (backgroundMode == 1) {
                drawBackdropBlur(canvas, d0 - 1, d1 - 2, d2, rowHeight + 1, guiW, guiH);
            }
 // 黑底: drawRect(d0-1, d1-2, d2, rowHeight+1, Color(0,0,0,110))
            drawRect(canvas, d0 - 1, d1 - 2, d2, rowHeight + 1, 0x6E000000);

 // 右侧 accent 条: drawRect(d0+nameWidth+tagWidth+4, d1-2, 1, rowHeight+1, accent(pos.x, pos.y/1.5))
            int accent = accentColor(e.posX, e.posY / 1.5f, tc);
            drawRect(canvas, d0 + e.nameWidth + e.tagWidth + 4, d1 - 2, 1, rowHeight + 1, accent);

 // 文字: font.b(name, d0+1, d1, accent)
            font24.drawText(canvas, e.name, d0 + 1, d1, accent, paint);
            if (e.hasTag) font24.drawText(canvas, e.tag, d0 + 1 + e.nameWidth + 4, d1, 0xFFCCCCCC, paint);
        }

 // 左上状态栏
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String s1 = "Rise Client | 6.9.5 | " + getPlayerName() + " | " + time;

        // 背景: roundedRectangle(2, 3, stringWidth, 15, 4, Color(0,0,0,100))
        float sw = font24.getStringWidth(s1);
        drawRoundedRect(canvas, 2, 3, sw, 15, 4, 0x64000000);

        // 逐字符: font.b(char, 6+j, 8, getAccentColor(k, k))
        float fx = 6;
        for (int k = 0; k < s1.length(); k++) {
            String ch = String.valueOf(s1.charAt(k));
            int c = accentColor(k, k, tc);
            font24.draw(canvas, ch, fx, 8, c, paint);
            fx += font24.getStringWidth(ch);
        }
    }

    // 辅助

    /**
 * 磨砂玻璃底：把本帧截取+模糊的帧缓冲画进指定矩形。
 * <p>
 * 映射与 的 layer 模糊同一条公式（{@code Renderer.drawForced}：
 * {@code srcX = (panelX + cumX*panelScale) * imgW / screenW}），区别只是
 * "面板"换成了整块 HUD 画布：blit 时 {@code scale = min(guiW/854, guiH/480)}、
 * 水平居中，所以设计坐标 → GUI 是 {@code offX + d*scale}。
 */
    private void drawBackdropBlur(Canvas canvas, float dx, float dy, float dw, float dh,
                                  int guiW, int guiH) {
        Image img = frameBackdrop;
        if (img == null || dw <= 0 || dh <= 0 || guiW <= 0 || guiH <= 0) return;
        float sc = Math.min(guiW / (float) DESIGN_WIDTH, guiH / 480f);
        float offX = (guiW - DESIGN_WIDTH * sc) / 2f;
        float imgW = img.getWidth(), imgH = img.getHeight();
        float gx = offX + dx * sc, gy = dy * sc;
        float gw = dw * sc, gh = dh * sc;
        try {
            // 模糊图是全分辨率，仍用线性采样，面板裁切时不会出硬边。
            canvas.drawImageRect(img,
                    Rect.makeXYWH(gx * imgW / guiW, gy * imgH / guiH,
                            gw * imgW / guiW, gh * imgH / guiH),
                    Rect.makeXYWH(dx, dy, dw, dh),
                    io.github.humbleui.skija.SamplingMode.LINEAR, null, true);
        } catch (Throwable ignored) {
        }
    }

 /** ClickGUI 调用：循环切换模式 */
    public void cycleMode() {
        Mode[] modes = Mode.values();
        mode = modes[(mode.ordinal() + 1) % modes.length];
        String n = mode.name();
        ModuleRegistry.ModuleEntry e = ModuleRegistry.get(INTERFACE_MODULE);
        if (e != null) e.tag = n.charAt(0) + n.substring(1).toLowerCase();
    }

    private String getPlayerName() {
        try { return Minecraft.getInstance().getUser().getName(); }
        catch (Exception e) { return ""; }
    }

    private double[] getPlayerPos() {
        try {
            var p = Minecraft.getInstance().player;
            if (p != null) return new double[]{Math.floor(p.getX()), Math.floor(p.getY()), Math.floor(p.getZ())};
        } catch (Exception ignored) {}
        return new double[]{0, 0, 0};
    }

    private static byte[] loadBytes(String fp) {
        try (java.io.InputStream in = ModuleListOverlay.class.getResourceAsStream("/assets/adaptclient/" + fp)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }
}
