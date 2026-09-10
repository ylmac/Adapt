/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.github.humbleui.skija.BlendMode
 *  io.github.humbleui.skija.Canvas
 *  io.github.humbleui.skija.ColorFilter
 *  io.github.humbleui.skija.DirectContext
 *  io.github.humbleui.skija.Font
 *  io.github.humbleui.skija.ImageFilter
 *  io.github.humbleui.skija.Paint
 *  io.github.humbleui.skija.PaintMode
 *  io.github.humbleui.skija.Path
 *  io.github.humbleui.skija.PathOp
 *  io.github.humbleui.types.RRect
 *  io.github.humbleui.types.Rect
 */
package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathOp;
import io.github.humbleui.skija.SaveLayerRec;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import io.github.timer_err.qml4j.engine.DelegateFactory;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.ObservableList;
import io.github.timer_err.qml4j.render.AnchorLine;
import io.github.timer_err.qml4j.render.Anchors;
import io.github.timer_err.qml4j.render.ComponentFactory;
import io.github.timer_err.qml4j.render.FontResolver;
import io.github.timer_err.qml4j.render.IconResolver;
import io.github.timer_err.qml4j.render.Painter;
import io.github.timer_err.qml4j.render.ResourceLoader;
import io.github.timer_err.qml4j.render.TextLayout;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.Rectangle;
import io.github.timer_err.qml4j.render.items.core.TextWrap;
import io.github.timer_err.qml4j.render.items.effect.ColorOverlay;
import io.github.timer_err.qml4j.render.items.effect.DropShadow;
import io.github.timer_err.qml4j.render.items.effect.Glow;
import io.github.timer_err.qml4j.render.items.effect.MultiEffect;
import io.github.timer_err.qml4j.render.items.input.TextEdit;
import io.github.timer_err.qml4j.render.items.input.TextField;
import io.github.timer_err.qml4j.render.items.input.TextInput;
import io.github.timer_err.qml4j.render.items.transform.Rotation;
import io.github.timer_err.qml4j.render.items.transform.Scale;
import io.github.timer_err.qml4j.render.items.transform.Transform;
import io.github.timer_err.qml4j.render.items.transform.Translate;
import io.github.timer_err.qml4j.render.items.view.Component;
import io.github.timer_err.qml4j.render.items.view.Loader;
import io.github.timer_err.qml4j.render.items.window.ApplicationWindow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Renderer {
    private Paint paint;
    private ResourceLoader resources;
    private ComponentFactory factory;
    private final FontResolver fonts = new FontResolver();
    private final IconResolver icons = new IconResolver(this.fonts);
    private final TextLayout text = new TextLayout(this.fonts, this.icons);
    private final Painter painter = new Painter(this);
    private static final int MAX_LAYOUT_PASSES = 16;
    private DirectContext gpuContext;
    /** GPU 截取的模糊帧缓冲图像，由 QplayerScreen 在 QML 渲染前设置 */
    public static io.github.humbleui.skija.Image backdropImage;
    /** 面板在屏幕上的偏移和缩放（由 QplayerScreen 设置） */
    public static float panelX = 0f, panelY = 0f, panelScale = 1f;
    /** Minecraft Screen 的宽高（GUI 单位，由 QplayerScreen 设置） */
    public static float screenW = 960f, screenH = 540f;
    /** 累积 QML 坐标（手动跟踪，避免画布矩阵的缩放干扰） */
    private float cumX = 0, cumY = 0;
    private static final boolean FPS_OVERLAY = Boolean.getBoolean("qml4j.fps");
    private long settleId;
    private float clipL = -3.4028235E38f;
    private float clipT = -3.4028235E38f;
    private float clipR = Float.MAX_VALUE;
    private float clipB = Float.MAX_VALUE;
    private static final Map<String, Integer> NAMED_COLORS = Renderer.buildNamedColors();

    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resources = resourceLoader;
    }

    public void setUiTypefaces(byte[] byArray, byte[] byArray2) {
        this.fonts.setUiTypefaces(byArray, byArray2);
    }

    public void setCjkTypeface(byte[] byArray) {
        this.fonts.setCjkTypeface(byArray);
    }

    public void setIconTypeface(byte[] byArray) {
        this.fonts.setIconTypeface(byArray);
    }

    public void setComponentFactory(ComponentFactory componentFactory) {
        this.factory = componentFactory;
    }

    ResourceLoader resources() {
        return this.resources;
    }

    FontResolver fonts() {
        return this.fonts;
    }

    IconResolver icons() {
        return this.icons;
    }

    TextLayout textLayout() {
        return this.text;
    }

    Paint paint() {
        if (this.paint == null) {
            this.paint = new Paint();
        }
        return this.paint;
    }

    public void setGpuContext(DirectContext directContext) {
        this.gpuContext = directContext;
    }

    DirectContext gpuContext() {
        return this.gpuContext;
    }

    public void render(Canvas canvas, Item item) {
        this.render(canvas, item, false);
    }

    public void render(Canvas canvas, Item item, boolean bl) {
        if (item == null) {
            return;
        }
        this.painter.bind(canvas);
        if (!bl) {
            this.settleLayout(item);
        }
        this.clipL = -3.4028235E38f;
        this.clipT = -3.4028235E38f;
        this.clipR = Float.MAX_VALUE;
        this.clipB = Float.MAX_VALUE;
        this.draw(canvas, item, 1.0f);
    }

    public void renderSubtree(Canvas canvas, Item item, float f, float f2) {
        if (item == null) {
            return;
        }
        this.painter.bind(canvas);
        this.clipL = 0.0f;
        this.clipT = 0.0f;
        this.clipR = f;
        this.clipB = f2;
        this.drawForced(canvas, item, 1.0f);
    }

    public boolean fpsOverlayEnabled() {
        return FPS_OVERLAY;
    }

    public void drawFpsOverlay(Canvas canvas, float f, double d) {
        String string = Math.round(d) + " FPS";
        Font font = this.fonts.fontFor(14.0f, string, true);
        float f2 = font.measureTextWidth(string);
        float f3 = 6.0f;
        float f4 = f2 + 2.0f * f3;
        float f5 = 22.0f;
        float f6 = f - f4 - 8.0f;
        float f7 = 8.0f;
        Paint paint = this.paint();
        paint.setShader(null);
        paint.setMode(PaintMode.FILL);
        paint.setColor(-872415232);
        canvas.drawRRect(RRect.makeXYWH((float)f6, (float)f7, (float)f4, (float)f5, (float)6.0f), paint);
        paint.setColor(-16718218);
        canvas.drawString(string, f6 + f3, f7 + 16.0f, font, paint);
    }

    public void layoutOnly(Item item) {
        if (item != null) {
            this.settleLayout(item);
        }
    }

    private void settleLayout(Item item) {
        ++this.settleId;
        DirtyQueue dirtyQueue = DirtyQueue.current();
        for (int i = 0; i < 16; ++i) {
            this.measure(item);
            if (dirtyQueue == null || dirtyQueue.isEmpty()) break;
            dirtyQueue.flush();
        }
    }

    private void measure(Item item) {
        int n;
        int n2;
        if (item == null) {
            return;
        }
        if (item instanceof Loader) {
            this.resolveLoader((Loader)item);
        }
        item.measure(this.text);
        Renderer.followImplicitSize(item);
        // 回退到原始行为：只有 cachedLayout:true 的节点用缓存 fast path。
        // 对所有节点生效会导致子节点可见性/内容变化但尺寸没变时不重测 → 元素消失/错位。
        if (Boolean.TRUE.equals(item.cachedLayout.peek())) {
            float f = item.width.peekFloat();
            float f2 = item.height.peekFloat();
            n2 = item.children.size();
            long l = item.children instanceof ObservableList ? ((ObservableList)item.children).structuralVersion() : 0L;
            if (item.cachedLayoutValid && item.cachedLayoutSettleId != this.settleId && item.cachedLayoutW == f && item.cachedLayoutH == f2 && item.cachedLayoutCount == n2 && item.cachedLayoutChildVersion == l && item.cachedLayoutChildDims == Renderer.childDimsChecksum(item)) {
                Renderer.runLayout(item);
                Renderer.applyAnchors(item);
                return;
            }
            item.cachedLayoutValid = true;
            item.cachedLayoutW = f;
            item.cachedLayoutH = f2;
            item.cachedLayoutCount = n2;
            item.cachedLayoutChildVersion = l;
            item.cachedLayoutSettleId = this.settleId;
        }
        List<Item> list = item.children;
        n2 = list.size();
        for (n = 0; n < n2; ++n) {
            Item item2 = list.get(n);
            if (item2.isVisible()) {
                this.measure(item2);
                continue;
            }
            if (item2 instanceof Loader) {
                this.resolveLoader((Loader)item2);
            }
            item2.measure(this.text);
            Renderer.followImplicitSize(item2);
            Renderer.runLayout(item2);
            Renderer.applyAnchors(item2);
        }
        Renderer.runLayout(item);
        Renderer.applyAnchors(item);
        n2 = list.size();
        for (n = 0; n < n2; ++n) {
            Item item3 = list.get(n);
            if (!item3.isVisible()) continue;
            Renderer.applyAnchors(item3);
        }
        // 对所有节点计算 childDimsChecksum（不只 cachedLayout:true 的），
        // 这样下一帧的 fast path 能正确判断子节点尺寸是否变了。
        item.cachedLayoutChildDims = Renderer.childDimsChecksum(item);
        Renderer.updateChildrenRect(item);
        // 重测完成：清脏标记，后续帧可命中缓存跳过
        item.layoutDirty = false;
        item.childrenDirty = false;
    }

    private static long childDimsChecksum(Item item) {
        List<Item> list = item.children;
        long l = 1L;
        int n = list.size();
        for (int i = 0; i < n; ++i) {
            Item item2 = list.get(i);
            l = l * 31L + (long)Float.floatToRawIntBits(item2.width.peekFloat());
            l = l * 31L + (long)Float.floatToRawIntBits(item2.height.peekFloat());
        }
        return l;
    }

    private static void updateChildrenRect(Item item) {
        List<Item> list = item.children;
        if (list.isEmpty()) {
            return;
        }
        float f = Float.MAX_VALUE;
        float f2 = Float.MAX_VALUE;
        float f3 = -3.4028235E38f;
        float f4 = -3.4028235E38f;
        boolean bl = false;
        int n = list.size();
        for (int i = 0; i < n; ++i) {
            Item item2 = list.get(i);
            if (!item2.isVisible()) continue;
            float f5 = item2.x.peekFloat();
            float f6 = item2.y.peekFloat();
            float f7 = item2.width.peekFloat();
            float f8 = item2.height.peekFloat();
            f = Math.min(f, f5);
            f2 = Math.min(f2, f6);
            f3 = Math.max(f3, f5 + f7);
            f4 = Math.max(f4, f6 + f8);
            bl = true;
        }
        if (!bl) {
            return;
        }
        item.childrenRect.x.set(Float.valueOf(f));
        item.childrenRect.y.set(Float.valueOf(f2));
        item.childrenRect.width.set(Float.valueOf(f3 - f));
        item.childrenRect.height.set(Float.valueOf(f4 - f2));
    }

    private static void runLayout(Item item) {
        item.layout();
    }

    private void draw(Canvas canvas, Item item, float f) {
        if (!item.isVisible()) {
            return;
        }
        if (this.culled(item)) {
            return;
        }
        this.drawForced(canvas, item, f);
    }

    private boolean culled(Item item) {
        if (item.rotation.peekFloat() != 0.0f || item.scale.peekFloat() != 1.0f) {
            return false;
        }
        if (!item.transform.isEmpty()) {
            return false;
        }
        if (this.layerEffectPaint(item) != null) {
            return false;
        }
        float f = item.x.peekFloat();
        float f2 = item.y.peekFloat();
        float f3 = item.width.peekFloat();
        float f4 = item.height.peekFloat();
        float f5 = item.childrenRect.x.peekFloat();
        float f6 = item.childrenRect.y.peekFloat();
        float f7 = item.childrenRect.width.peekFloat();
        float f8 = item.childrenRect.height.peekFloat();
        float f9 = f + Math.min(0.0f, f5);
        float f10 = f2 + Math.min(0.0f, f6);
        float f11 = f + Math.max(f3, f5 + f7);
        float f12 = f2 + Math.max(f4, f6 + f8);
        if (f11 <= f9 || f12 <= f10) {
            return false;
        }
        return f11 <= this.clipL || f9 >= this.clipR || f12 <= this.clipT || f10 >= this.clipB;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    void drawForced(Canvas canvas, Item item, float f) {
        float f2 = item.x.peekFloat();
        float f3 = item.y.peekFloat();
        float f4 = item.width.peekFloat();
        float f5 = item.height.peekFloat();
        float f6 = f * item.opacity.peekFloat();
        if (f6 <= 0.0f) {
            return;
        }
        float f7 = item.rotation.peekFloat();
        float f8 = item.scale.peekFloat();
        boolean bl = Boolean.TRUE.equals(item.clip.peek());
        int n = canvas.save();
        Paint paint = this.layerEffectPaint(item);
        ImageFilter backdropFilter = null;
        float blurRadius = Boolean.TRUE.equals(item.layer.enabled.peek())
                ? item.layer.blurRadius.peekFloat() : 0.0f;
        try {
            int n2;
            float f9;
            float f10;
            float f11;
            float f12;
            float[] fArray;
            canvas.translate(f2, f3);
            Renderer.applyTransform(canvas, item, f4, f5, f7, f8);
            cumX += f2;
            cumY += f3;
            // 记录是否有圆角需要擦除（模糊图铺满矩形，最后用 eraseOutsideRoundRect 擦掉圆角外部）
            boolean hasClip = false;
            float clipR = 0f;
            if (blurRadius > 0.0f && item instanceof io.github.timer_err.qml4j.render.items.core.Rectangle) {
                io.github.timer_err.qml4j.render.items.core.Rectangle rc =
                    (io.github.timer_err.qml4j.render.items.core.Rectangle) item;
                float r = rc.radius.peekFloat();
                if (r <= 0.0f) r = rc.topLeftRadius.peekFloat();
                if (r > 0.0f) {
                    hasClip = true;
                    clipR = r;
                }
            }
            if (paint != null || blurRadius > 0.0f) {
                float f13 = Renderer.layerEffectMargin(item);
                Rect layerBounds = Rect.makeXYWH((float)(-f13), (float)(-f13), (float)Math.max(0.0f, f4 + 2.0f * f13), (float)Math.max(0.0f, f5 + 2.0f * f13));
                // 有 layer effect（如阴影）才用 saveLayer + paint；纯模糊用 save + clipRRect + drawImageRect
                if (paint != null) {
                    canvas.saveLayer(layerBounds, paint);
                } else {
                    // blurRadius > 0：保存 canvas 状态，稍后 clipRRect + drawImageRect 后 restore
                    canvas.save();
                }
            }
            if ((fArray = Renderer.maskClipRadii(item)) != null) {
                canvas.saveLayer(Rect.makeXYWH((float)0.0f, (float)0.0f, (float)Math.max(0.0f, f4), (float)Math.max(0.0f, f5)), null);
            } else if (bl) {
                canvas.clipRect(Rect.makeXYWH((float)0.0f, (float)0.0f, (float)Math.max(0.0f, f4), (float)Math.max(0.0f, f5)));
            }
            // 模糊帧缓冲：在 mask clip 之后画 → 添加圆角裁剪
            if (blurRadius > 0.0f && Renderer.backdropImage != null) {
                try {
                    // 用 panelScale/panelX/panelY 映射 QML 坐标 → GUI 单位 → 帧缓冲物理像素
                    int imgW = Renderer.backdropImage.getWidth();
                    int imgH = Renderer.backdropImage.getHeight();
                    float srcX = (Renderer.panelX + cumX * Renderer.panelScale) * (float)imgW / Renderer.screenW;
                    float srcY = (Renderer.panelY + cumY * Renderer.panelScale) * (float)imgH / Renderer.screenH;
                    float srcW = f4 * Renderer.panelScale * (float)imgW / Renderer.screenW;
                    float srcH = f5 * Renderer.panelScale * (float)imgH / Renderer.screenH;
                    // 圆角裁剪已在 drawForced 开头应用，这里只画模糊图
                    com.ylmac.adapt.render.FrameProfiler.begin(com.ylmac.adapt.render.FrameProfiler.BLUR_DRAW);
                    try {
                    if (blurRadius > 0.0f && item instanceof io.github.timer_err.qml4j.render.items.core.Rectangle) {
                        io.github.timer_err.qml4j.render.items.core.Rectangle rc =
                            (io.github.timer_err.qml4j.render.items.core.Rectangle) item;
                        float r = rc.radius.peekFloat();
                        if (r <= 0.0f) r = rc.topLeftRadius.peekFloat();
                        if (r > 0.0f) {
                            canvas.drawImageRect(Renderer.backdropImage,
                                Rect.makeXYWH(srcX, srcY, srcW, srcH),
                                Rect.makeXYWH(0, 0, f4, f5), null);
                        } else {
                            canvas.drawImageRect(Renderer.backdropImage,
                                Rect.makeXYWH(srcX, srcY, srcW, srcH),
                                Rect.makeXYWH(0, 0, f4, f5), null);
                        }
                    } else {
                        canvas.drawImageRect(Renderer.backdropImage,
                            Rect.makeXYWH(srcX, srcY, srcW, srcH),
                            Rect.makeXYWH(0, 0, f4, f5), null);
                    }
                    } finally {
                        com.ylmac.adapt.render.FrameProfiler.end(com.ylmac.adapt.render.FrameProfiler.BLUR_DRAW);
                    }
                } catch (Throwable ignored) {}
            }
            float f14 = this.clipL;
            float f15 = this.clipT;
            float f16 = this.clipR;
            float f17 = this.clipB;
            if (f7 != 0.0f || f8 != 1.0f || !item.transform.isEmpty()) {
                f12 = -3.4028235E38f;
                f11 = -3.4028235E38f;
                f10 = Float.MAX_VALUE;
                f9 = Float.MAX_VALUE;
            } else {
                f11 = this.clipL - f2;
                f12 = this.clipT - f3;
                f9 = this.clipR - f2;
                f10 = this.clipB - f3;
                if (bl || fArray != null) {
                    if (f11 < 0.0f) {
                        f11 = 0.0f;
                    }
                    if (f12 < 0.0f) {
                        f12 = 0.0f;
                    }
                    if (f9 > f4) {
                        f9 = f4;
                    }
                    if (f10 > f5) {
                        f10 = f5;
                    }
                }
            }
            List<Item> list = Renderer.zOrdered(item.children);
            this.clipL = f11;
            this.clipT = f12;
            this.clipR = f9;
            this.clipB = f10;
            int n3 = list.size();
            for (n2 = 0; n2 < n3; ++n2) {
                Item item2 = list.get(n2);
                if (!(item2.z.peekFloat() < 0.0f)) continue;
                this.draw(canvas, item2, f6);
            }
            item.paint(this.painter, f4, f5, f6);
            if (item instanceof Flickable) {
                Flickable flickable = (Flickable)item;
                canvas.clipRect(Rect.makeXYWH((float)0.0f, (float)0.0f, (float)Math.max(0.0f, f4), (float)Math.max(0.0f, f5)));
                float f18 = flickable.contentX.peekFloat();
                float f19 = flickable.contentY.peekFloat();
                float f20 = f11 < 0.0f ? 0.0f : f11;
                float f21 = f12 < 0.0f ? 0.0f : f12;
                float f22 = f9 > f4 ? f4 : f9;
                float f23 = f10 > f5 ? f5 : f10;
                canvas.translate(-f18, -f19);
                this.clipL = f20 + f18;
                this.clipT = f21 + f19;
                this.clipR = f22 + f18;
                this.clipB = f23 + f19;
            }
            if (item instanceof ApplicationWindow) {
                ApplicationWindow applicationWindow = (ApplicationWindow)item;
                float f24 = applicationWindow.contentTop();
                float f25 = applicationWindow.contentBottom(f5);
                int n4 = canvas.save();
                try {
                    canvas.clipRect(Rect.makeXYWH((float)0.0f, (float)f24, (float)Math.max(0.0f, f4), (float)Math.max(0.0f, f25 - f24)));
                    canvas.translate(0.0f, f24);
                    this.clipL = f11 < 0.0f ? 0.0f : f11;
                    this.clipR = f9 > f4 ? f4 : f9;
                    this.clipT = (f12 < f24 ? f24 : f12) - f24;
                    this.clipB = (f10 > f25 ? f25 : f10) - f24;
                    int n5 = list.size();
                    for (int i = 0; i < n5; ++i) {
                        Item item3 = list.get(i);
                        if (!(item3.z.peekFloat() >= 0.0f)) continue;
                        this.draw(canvas, item3, f6);
                    }
                }
                finally {
                    canvas.restoreToCount(n4);
                }
                this.drawChrome(canvas, applicationWindow, f4, f5, f6);
            } else {
                n3 = list.size();
                for (n2 = 0; n2 < n3; ++n2) {
                    Item item4 = list.get(n2);
                    if (!(item4.z.peekFloat() >= 0.0f)) continue;
                    this.draw(canvas, item4, f6);
                }
            }
            this.clipL = f14;
            this.clipT = f15;
            this.clipR = f16;
            this.clipB = f17;
            if (fArray != null) {
                this.eraseOutsideRoundRect(canvas, f4, f5, fArray);
            } else if (hasClip && clipR > 0.0f) {
                this.eraseOutsideRoundRect(canvas, f4, f5, new float[]{clipR, clipR, clipR, clipR});
            }
        }
        finally {
            canvas.restoreToCount(n);
            cumX -= f2;
            cumY -= f3;
            if (paint != null) {
                paint.close();
            }
            if (backdropFilter != null) {
                backdropFilter.close();
            }
        }
    }

    private void eraseOutsideRoundRect(Canvas canvas, float f, float f2, float[] fArray) {
        if (f <= 0.0f || f2 <= 0.0f) {
            return;
        }
        try (Path path = Path.makeRect((Rect)Rect.makeXYWH((float)0.0f, (float)0.0f, (float)f, (float)f2));
             Path path2 = Path.makeRRect((RRect)RRect.makeComplexXYWH((float)0.0f, (float)0.0f, (float)f, (float)f2, (float[])fArray));
             Path path3 = Path.makeCombining((Path)path, (Path)path2, (PathOp)PathOp.DIFFERENCE);
             Paint paint = new Paint().setBlendMode(BlendMode.CLEAR).setAntiAlias(true);){
            canvas.drawPath(path3, paint);
        }
    }

    private static void applyTransform(Canvas canvas, Item item, float f, float f2, float f3, float f4) {
        if (f3 != 0.0f || f4 != 1.0f) {
            int n = item.transformOrigin.peek().intValue();
            float f5 = Renderer.pivot(n % 3, f);
            float f6 = Renderer.pivot(n / 3, f2);
            canvas.translate(f5, f6);
            if (f3 != 0.0f) {
                canvas.rotate(f3);
            }
            if (f4 != 1.0f) {
                canvas.scale(f4, f4);
            }
            canvas.translate(-f5, -f6);
        }
        if (!item.transform.isEmpty()) {
            Renderer.applyTransformList(canvas, item);
        }
    }

    private static void applyTransformList(Canvas canvas, Item item) {
        for (Transform transform : item.transform) {
            Transform transform2;
            if (transform instanceof Translate) {
                Translate translate = (Translate)transform;
                canvas.translate(translate.x.peekFloat(), translate.y.peekFloat());
                continue;
            }
            if (transform instanceof Scale) {
                transform2 = (Scale)transform;
                float f = ((Scale)transform2).origin.x.peekFloat();
                float f2 = ((Scale)transform2).origin.y.peekFloat();
                canvas.translate(f, f2);
                canvas.scale(((Scale)transform2).xScale.peekFloat(), ((Scale)transform2).yScale.peekFloat());
                canvas.translate(-f, -f2);
                continue;
            }
            if (!(transform instanceof Rotation)) continue;
            Renderer.applyRotation(canvas, (Rotation)transform);
        }
    }

    private static void applyRotation(Canvas canvas, Rotation rotation) {
        float f = rotation.angle.peekFloat();
        if (f == 0.0f) {
            return;
        }
        float f2 = rotation.origin.x.peekFloat();
        float f3 = rotation.origin.y.peekFloat();
        float f4 = rotation.axis.x.peekFloat();
        float f5 = rotation.axis.y.peekFloat();
        float f6 = rotation.axis.z.peekFloat();
        canvas.translate(f2, f3);
        if (f4 == 0.0f && f5 == 0.0f) {
            canvas.rotate(f);
        } else {
            double d = Math.abs(Math.cos(Math.toRadians(f)));
            if (f5 != 0.0f) {
                canvas.scale((float)d, 1.0f);
            } else {
                canvas.scale(1.0f, (float)d);
            }
        }
        if (f6 != 0.0f && (f4 != 0.0f || f5 != 0.0f)) {
            canvas.rotate(f * f6);
        }
        canvas.translate(-f2, -f3);
    }

    private static float pivot(int n, float f) {
        return n == 0 ? 0.0f : (n == 1 ? f / 2.0f : f);
    }

    static List<Item> zOrdered(List<Item> list) {
        int n = list.size();
        if (n < 2) {
            return list;
        }
        boolean bl = false;
        for (int i = 0; i < n; ++i) {
            if (list.get((int)i).z.peekFloat() == 0.0f) continue;
            bl = true;
            break;
        }
        if (!bl) {
            return list;
        }
        ArrayList<Item> arrayList = new ArrayList<Item>(list);
        arrayList.sort(Comparator.comparingDouble(item -> item.z.peekDouble()));
        return arrayList;
    }

    private static void followImplicitSize(Item item) {
        double d;
        double d2 = item.implicitWidth.peekDouble();
        if (d2 > 0.0 && !item.width.isBound() && Renderer.ownsImplicitWidth(item)) {
            item.width.set(d2);
            item.lastImplicitWidth = d2;
        }
        if ((d = item.implicitHeight.peekDouble()) > 0.0 && !item.height.isBound() && Renderer.ownsImplicitHeight(item)) {
            item.height.set(d);
            item.lastImplicitHeight = d;
        }
    }

    private static boolean ownsImplicitWidth(Item item) {
        if (Double.isNaN(item.lastImplicitWidth)) {
            return item.width.peekDouble() == 0.0;
        }
        return item.width.peekDouble() == item.lastImplicitWidth;
    }

    private static boolean ownsImplicitHeight(Item item) {
        if (Double.isNaN(item.lastImplicitHeight)) {
            return item.height.peekDouble() == 0.0;
        }
        return item.height.peekDouble() == item.lastImplicitHeight;
    }

    static void applyAnchors(Item item) {
        Anchors anchors = item.anchors;
        float f = anchors.margins.peekFloat();
        float f2 = Renderer.marginOr(anchors.leftMargin.peek(), f);
        float f3 = Renderer.marginOr(anchors.rightMargin.peek(), f);
        float f4 = Renderer.marginOr(anchors.topMargin.peek(), f);
        float f5 = Renderer.marginOr(anchors.bottomMargin.peek(), f);
        Item item2 = item.parent.peek();
        Item item3 = anchors.fill.peek();
        if (item3 != null) {
            float f6 = item3 == item2 ? 0.0f : item3.x.peekFloat();
            float f7 = item3 == item2 ? 0.0f : item3.y.peekFloat();
            item.x.set(Float.valueOf(f6 + f2));
            item.y.set(Float.valueOf(f7 + f4));
            item.width.set(Float.valueOf(item3.width.peekFloat() - f2 - f3));
            item.height.set(Float.valueOf(item3.height.peekFloat() - f4 - f5));
            return;
        }
        Item item4 = anchors.centerIn.peek();
        if (item4 != null) {
            float f8 = item.width.peekFloat();
            float f9 = item.height.peekFloat();
            float f10 = item4 == item2 ? 0.0f : item4.x.peekFloat();
            float f11 = item4 == item2 ? 0.0f : item4.y.peekFloat();
            item.x.set(Float.valueOf(f10 + (item4.width.peekFloat() - f8) / 2.0f + anchors.horizontalCenterOffset.peekFloat()));
            item.y.set(Float.valueOf(f11 + (item4.height.peekFloat() - f9) / 2.0f + anchors.verticalCenterOffset.peekFloat()));
            return;
        }
        Renderer.applyHorizontalAnchors(item, f2, f3, anchors);
        Renderer.applyVerticalAnchors(item, f4, f5, anchors);
    }

    private static void applyHorizontalAnchors(Item item, float f, float f2, Anchors anchors) {
        AnchorLine anchorLine = anchors.left.peek();
        AnchorLine anchorLine2 = anchors.right.peek();
        AnchorLine anchorLine3 = anchors.horizontalCenter.peek();
        if (anchorLine != null && anchorLine2 != null) {
            float f3 = Renderer.resolveX(anchorLine, item) + f;
            float f4 = Renderer.resolveX(anchorLine2, item) - f2;
            item.x.set(Float.valueOf(f3));
            item.width.set(Float.valueOf(f4 - f3));
        } else if (anchorLine != null) {
            item.x.set(Float.valueOf(Renderer.resolveX(anchorLine, item) + f));
        } else if (anchorLine2 != null) {
            float f5 = item.width.peekFloat();
            item.x.set(Float.valueOf(Renderer.resolveX(anchorLine2, item) - f2 - f5));
        } else if (anchorLine3 != null) {
            float f6 = item.width.peekFloat();
            float f7 = anchors.horizontalCenterOffset.peekFloat();
            item.x.set(Float.valueOf(Renderer.resolveX(anchorLine3, item) - f6 / 2.0f + f7));
        }
    }

    private static void applyVerticalAnchors(Item item, float f, float f2, Anchors anchors) {
        AnchorLine anchorLine = anchors.top.peek();
        AnchorLine anchorLine2 = anchors.bottom.peek();
        AnchorLine anchorLine3 = anchors.verticalCenter.peek();
        if (anchorLine != null && anchorLine2 != null) {
            float f3 = Renderer.resolveY(anchorLine, item) + f;
            float f4 = Renderer.resolveY(anchorLine2, item) - f2;
            item.y.set(Float.valueOf(f3));
            item.height.set(Float.valueOf(f4 - f3));
        } else if (anchorLine != null) {
            item.y.set(Float.valueOf(Renderer.resolveY(anchorLine, item) + f));
        } else if (anchorLine2 != null) {
            float f5 = item.height.peekFloat();
            item.y.set(Float.valueOf(Renderer.resolveY(anchorLine2, item) - f2 - f5));
        } else if (anchorLine3 != null) {
            float f6 = item.height.peekFloat();
            float f7 = anchors.verticalCenterOffset.peekFloat();
            item.y.set(Float.valueOf(Renderer.resolveY(anchorLine3, item) - f6 / 2.0f + f7));
        }
    }

    private static float resolveX(AnchorLine anchorLine, Item item) {
        Item item2 = anchorLine.source;
        boolean bl = item2 == item.parent.peek();
        float f = bl ? 0.0f : item2.x.peekFloat();
        float f2 = item2.width.peekFloat();
        switch (anchorLine.edge) {
            case LEFT: {
                return f;
            }
            case RIGHT: {
                return f + f2;
            }
            case HORIZONTAL_CENTER: {
                return f + f2 / 2.0f;
            }
        }
        throw new IllegalStateException("not a horizontal edge: " + String.valueOf((Object)anchorLine.edge));
    }

    private static float resolveY(AnchorLine anchorLine, Item item) {
        Item item2 = anchorLine.source;
        boolean bl = item2 == item.parent.peek();
        float f = bl ? 0.0f : item2.y.peekFloat();
        float f2 = item2.height.peekFloat();
        switch (anchorLine.edge) {
            case TOP: {
                return f;
            }
            case BOTTOM: {
                return f + f2;
            }
            case VERTICAL_CENTER: {
                return f + f2 / 2.0f;
            }
        }
        throw new IllegalStateException("not a vertical edge: " + String.valueOf((Object)anchorLine.edge));
    }

    private static float marginOr(Number number, float f) {
        if (number == null) {
            return f;
        }
        double d = number.doubleValue();
        if (Double.isNaN(d)) {
            return f;
        }
        return (float)d;
    }

    private void drawChrome(Canvas canvas, ApplicationWindow applicationWindow, float f, float f2, float f3) {
        applicationWindow.layoutChrome(f, f2);
        Item item = applicationWindow.menuBar.peek();
        Item item2 = applicationWindow.header.peek();
        Item item3 = applicationWindow.footer.peek();
        if (item != null) {
            this.draw(canvas, item, f3);
        }
        if (item2 != null) {
            this.draw(canvas, item2, f3);
        }
        if (item3 != null) {
            this.draw(canvas, item3, f3);
        }
    }

    public int moveCaretVerticalForTextEdit(TextEdit textEdit, int n, int n2) {
        String string2 = textEdit.text.peek();
        if (string2 == null) {
            string2 = "";
        }
        float f = textEdit.fontSize.peekFloat();
        Font font = this.fonts.fontFor(f, string2);
        try {
            float f2 = textEdit.width.peekFloat();
            TextWrap.Result result = this.text.wrapFor(textEdit, string2, f2, f, font);
            return TextWrap.moveCaretVertical(result, n, n2, string -> font.measureTextWidth(string));
        }
        catch (Throwable throwable) {
            return n;
        }
    }

    public int caretIndexForTextEdit(TextEdit textEdit, float f, float f2) {
        String string2 = textEdit.text.peek();
        if (string2 == null) {
            string2 = "";
        }
        float f3 = textEdit.fontSize.peekFloat();
        Font font = this.fonts.fontFor(f3, string2);
        try {
            float f4 = textEdit.width.peekFloat();
            float f5 = textEdit.height.peekFloat();
            TextWrap.Result result = this.text.wrapFor(textEdit, string2, f4, f3, font);
            float f6 = TextLayout.lineHeight(font);
            float f7 = f6 * (float)result.lines.size();
            float f8 = this.text.topOffset(textEdit.verticalAlignment.peek(), f5, f7);
            int n = (int)Math.floor((f2 - f8) / f6);
            if (n < 0) {
                n = 0;
            }
            if (n >= result.lines.size()) {
                n = result.lines.size() - 1;
            }
            String string3 = result.lines.get(n);
            int n2 = TextWrap.caretInLine(string3, f, string -> font.measureTextWidth(string));
            return result.starts[n] + n2;
        }
        catch (Throwable throwable) {
            return string2.length();
        }
    }

    public int caretIndexFor(TextInput textInput, float f) {
        String string = textInput.text.peek();
        if (textInput instanceof TextField) {
            f -= ((TextField)textInput).padding.peekFloat();
        }
        if (string == null || string.isEmpty() || f <= 0.0f) {
            return 0;
        }
        float f2 = textInput.fontSize.peekFloat();
        Font font = this.fonts.fontFor(f2, string);
        try {
            float f3 = 0.0f;
            int n = string.length();
            for (int i = 1; i <= n; ++i) {
                float f4 = font.measureTextWidth(string.substring(0, i));
                if (f4 >= f) {
                    float f5 = (f3 + f4) / 2.0f;
                    return f < f5 ? i - 1 : i;
                }
                f3 = f4;
            }
            return n;
        }
        catch (Throwable throwable) {
            return string.length();
        }
    }

    void resolveLoader(Loader loader) {
        if (!Boolean.TRUE.equals(loader.active.peek())) {
            if (loader.loadedItem != null) {
                this.clearLoadedItem(loader);
            }
            loader.loadedComponent = null;
            loader.loadedSource = null;
            return;
        }
        Component component = loader.sourceComponent.peek();
        if (component != null) {
            this.resolveLoaderComponent(loader, component);
            return;
        }
        if (loader.loadedComponent != null) {
            this.clearLoadedItem(loader);
            loader.loadedComponent = null;
        }
        this.resolveLoaderSource(loader);
    }

    private void resolveLoaderSource(Loader loader) {
        Item item;
        String string = loader.source.peek();
        if (string == null || string.isEmpty()) {
            if (loader.loadedItem != null) {
                this.clearLoadedItem(loader);
                loader.loadedSource = null;
            }
            return;
        }
        if (string.equals(loader.loadedSource)) {
            return;
        }
        if (this.factory == null) {
            return;
        }
        try {
            item = this.factory.createFromSource(string);
        }
        catch (Throwable throwable) {
            return;
        }
        if (item == null) {
            return;
        }
        this.attachLoadedItem(loader, item);
        loader.loadedSource = string;
    }

    private void resolveLoaderComponent(Loader loader, Component component) {
        if (component == loader.loadedComponent && loader.loadedItem != null) {
            return;
        }
        DelegateFactory delegateFactory = component.factory();
        if (delegateFactory == null) {
            return;
        }
        QObject qObject = delegateFactory.create(0, null, loader);
        if (!(qObject instanceof Item)) {
            throw new IllegalStateException("Loader sourceComponent must produce an Item, got " + (qObject == null ? "null" : qObject.getClass().getName()));
        }
        this.attachLoadedItem(loader, (Item)qObject);
        loader.loadedComponent = component;
        loader.loadedSource = null;
    }

    private void attachLoadedItem(Loader loader, Item item) {
        if (loader.loadedItem != null) {
            loader.children.remove(loader.loadedItem);
        }
        loader.loadedItem = item;
        item.parent.set(loader);
        loader.children.add(item);
        loader.item.set(item);
        item.initStateBindingsTree();
        loader.loaded.emit();
    }

    private void clearLoadedItem(Loader loader) {
        if (loader.loadedItem != null) {
            loader.children.remove(loader.loadedItem);
            loader.loadedItem = null;
        }
        loader.item.set(null);
    }

    public void dispose() {
        if (this.paint != null) {
            this.paint.close();
            this.paint = null;
        }
        this.fonts.close();
    }

    private static Map<String, Integer> buildNamedColors() {
        HashMap<String, Integer> hashMap = new HashMap<String, Integer>();
        hashMap.put("transparent", 0);
        hashMap.put("black", -16777216);
        hashMap.put("white", -1);
        hashMap.put("red", -65536);
        hashMap.put("green", -16744448);
        hashMap.put("blue", -16776961);
        hashMap.put("gray", -8355712);
        hashMap.put("grey", -8355712);
        hashMap.put("yellow", -256);
        hashMap.put("orange", -23296);
        return hashMap;
    }

    public static int parseColor(String string) {
        long l;
        if (string == null) {
            return -16777216;
        }
        if ((string = string.trim()).isEmpty()) {
            return -16777216;
        }
        if (string.charAt(0) != '#') {
            Integer n = NAMED_COLORS.get(string.toLowerCase());
            return n != null ? n : -16777216;
        }
        String string2 = string.substring(1);
        try {
            l = Long.parseLong(string2, 16);
        }
        catch (NumberFormatException numberFormatException) {
            return -16777216;
        }
        switch (string2.length()) {
            case 3: {
                int n = (int)(l >> 8 & 0xFL);
                int n2 = (int)(l >> 4 & 0xFL);
                int n3 = (int)(l & 0xFL);
                return 0xFF000000 | n * 17 << 16 | n2 * 17 << 8 | n3 * 17;
            }
            case 6: {
                return 0xFF000000 | (int)(l & 0xFFFFFFL);
            }
            case 8: {
                return (int)l;
            }
        }
        return -16777216;
    }

    static int applyAlpha(int n, float f) {
        if (f >= 1.0f) {
            return n;
        }
        if (f <= 0.0f) {
            return n & 0xFFFFFF;
        }
        int n2 = n >>> 24 & 0xFF;
        int n3 = Math.round((float)n2 * f);
        return n3 << 24 | n & 0xFFFFFF;
    }

    static float sigma(float f) {
        return f <= 0.0f ? 0.0f : f / 2.0f;
    }

    private static float[] maskClipRadii(Item item) {
        if (!Boolean.TRUE.equals(item.layer.enabled.peek())) {
            return null;
        }
        Object object = item.layer.effect.peek();
        if (!(object instanceof MultiEffect)) {
            return null;
        }
        MultiEffect multiEffect = (MultiEffect)object;
        if (!Boolean.TRUE.equals(multiEffect.maskEnabled.peek())) {
            return null;
        }
        Rectangle rectangle = Renderer.firstRectangle(multiEffect.maskSource.peek());
        if (rectangle == null) {
            return null;
        }
        float f = rectangle.cornerRadius(rectangle.topLeftRadius.peekFloat());
        float f2 = rectangle.cornerRadius(rectangle.topRightRadius.peekFloat());
        float f3 = rectangle.cornerRadius(rectangle.bottomRightRadius.peekFloat());
        float f4 = rectangle.cornerRadius(rectangle.bottomLeftRadius.peekFloat());
        if (f <= 0.0f && f2 <= 0.0f && f3 <= 0.0f && f4 <= 0.0f) {
            return null;
        }
        return new float[]{f, f2, f3, f4};
    }

    private static Rectangle firstRectangle(Object object) {
        if (!(object instanceof Item)) {
            return null;
        }
        if (object instanceof Rectangle) {
            return (Rectangle)object;
        }
        for (Item item : ((Item)object).children) {
            Rectangle rectangle = Renderer.firstRectangle(item);
            if (rectangle == null) continue;
            return rectangle;
        }
        return null;
    }

    private Paint layerEffectPaint(Item item) {
        if (!Boolean.TRUE.equals(item.layer.enabled.peek())) {
            return null;
        }
        Object object = item.layer.effect.peek();
        if (object == null) {
            return null;
        }
        Paint paint = new Paint();
        if (object instanceof DropShadow) {
            DropShadow dropShadow = (DropShadow)object;
            paint.setImageFilter(ImageFilter.makeDropShadow((float)dropShadow.offsetX.peekFloat(), (float)dropShadow.offsetY.peekFloat(), (float)Renderer.sigma(dropShadow.radius.peekFloat()), (float)Renderer.sigma(dropShadow.radius.peekFloat()), (int)Renderer.parseColor(dropShadow.color.peek())));
        } else if (object instanceof Glow) {
            Glow glow = (Glow)object;
            paint.setImageFilter(ImageFilter.makeDropShadow((float)0.0f, (float)0.0f, (float)Renderer.sigma(glow.radius.peekFloat()), (float)Renderer.sigma(glow.radius.peekFloat()), (int)Renderer.parseColor(glow.color.peek())));
        } else if (object instanceof ColorOverlay) {
            ColorOverlay colorOverlay = (ColorOverlay)object;
            paint.setColorFilter(ColorFilter.makeBlend((int)Renderer.parseColor(colorOverlay.color.peek()), (BlendMode)BlendMode.SRC_IN));
        } else if (object instanceof MultiEffect && Boolean.TRUE.equals(((MultiEffect)object).shadowEnabled.peek())) {
            MultiEffect multiEffect = (MultiEffect)object;
            int n = Renderer.applyAlpha(Renderer.parseColor(multiEffect.shadowColor.peek()), (float)multiEffect.shadowOpacity.peekDouble());
            float f = Renderer.sigma(multiEffect.shadowBlur.peekFloat() * 32.0f);
            paint.setImageFilter(ImageFilter.makeDropShadow((float)multiEffect.shadowHorizontalOffset.peekFloat(), (float)multiEffect.shadowVerticalOffset.peekFloat(), (float)f, (float)f, (int)n));
        } else {
            paint.close();
            return null;
        }
        return paint;
    }

    private static float layerEffectMargin(Item item) {
        Object object = item.layer.effect.peek();
        if (object instanceof DropShadow) {
            DropShadow dropShadow = (DropShadow)object;
            float f = dropShadow.radius.peekFloat();
            float f2 = Math.abs(dropShadow.offsetX.peekFloat());
            float f3 = Math.abs(dropShadow.offsetY.peekFloat());
            return f + Math.max(f2, f3) + 4.0f;
        }
        if (object instanceof Glow) {
            return ((Glow)object).radius.peekFloat() + 4.0f;
        }
        if (object instanceof MultiEffect && Boolean.TRUE.equals(((MultiEffect)object).shadowEnabled.peek())) {
            MultiEffect multiEffect = (MultiEffect)object;
            float f = multiEffect.shadowBlur.peekFloat() * 32.0f;
            float f4 = Math.abs(multiEffect.shadowHorizontalOffset.peekFloat());
            float f5 = Math.abs(multiEffect.shadowVerticalOffset.peekFloat());
            return f + Math.max(f4, f5) + 4.0f;
        }
        return 0.0f;
    }
}
