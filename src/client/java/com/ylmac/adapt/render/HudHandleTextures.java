package com.ylmac.adapt.render;

import com.ylmac.adapt.QplayerModClient;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.types.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 编辑器的圆角手柄纹理（左上角 / 右下角各一张，静态烘焙一次）。
 * <p>
 * 形状与 QML 组件 {@code md3/Core/CornerHandle.qml} 完全一致：
 * 一条 90° 圆弧 + 圆头线帽（"带圆角正方形的描边取四分之一、两端加圆角"）。
 * 两处各画一遍是因为渲染路径不同 —— QML 侧用 Canvas 的 {@code arc()}，
 * HUD 侧只有 {@code GuiGraphicsExtractor}（画不了弧），所以这里用 Skia 把
 * 弧烘焙成一张小纹理，再由编辑器 blit 到元素角上。
 * <p>
 * 白色 + 高不透明度：在任何背景（亮雪地/暗洞穴）上都看得见，且不依赖主题色 ——
 * 手柄是编辑器 chrome，不是界面配色的一部分。
 */
final class HudHandleTextures {

    /** 设计尺寸（会乘 MinecraftSurfaceBackend 的 2× 超采样）。 */
    static final int SIZE = 36;
    /** 描边宽度（设计坐标）。 */
    private static final float THICKNESS = 3.5f;
    /**
     * 弧半径 = 整格 - 描边宽（两端各留 half 边距）。
     * <p>
     * 形状就是"带圆角正方形的描边取四分之一" —— **只有那一段弧，没有直臂**。
     * 曾试过加直臂的 L 形括号，用户判定"形状才是错的、之前（单弧）是对的"，
     * 已回退。手柄离元素多远不靠改形状，而靠摆放时的重叠量
     * {@code HudEditor.HANDLE_INSET} —— 弧身本来就在盒子靠外的一侧，
     * 把盒子往里压才能让弧贴住元素的角。
     */
    private static final float ARC_RADIUS = SIZE - THICKNESS;
    /**
     * 弧的折线段数。12 段时每段弦长约 7px，在 2× 超采样下肉眼能看出折角 ——
     * 用户实测"看着跟三角形一样"。48 段弦长约 1.8px，视觉上就是圆。
     */
    private static final int ARC_SEGMENTS = 48;
    private static final int COLOR = 0xE6FFFFFF;

    private static MinecraftSurfaceBackend topLeft;
    private static MinecraftSurfaceBackend bottomRight;

    private HudHandleTextures() {
    }

    static MinecraftSurfaceBackend topLeft() {
        ensure();
        return topLeft;
    }

    static MinecraftSurfaceBackend bottomRight() {
        ensure();
        return bottomRight;
    }

    private static synchronized void ensure() {
        if (topLeft != null) return;
        try {
            SkijaNativeFix.preload();
            topLeft = bake(true);
            bottomRight = bake(false);
        } catch (Throwable t) {
            QplayerModClient.LOGGER.warn("[HUD] handle bake failed: {}", t.toString());
        }
    }

    private static MinecraftSurfaceBackend bake(boolean isTopLeft) {
        // 手柄在 GUI 里 1:1 显示（36 GUI px）→ 物理纹理 = 36 × 窗口 GUI scale
        MinecraftSurfaceBackend surf =
                new MinecraftSurfaceBackend(isTopLeft ? "hud_handle_tl" : "hud_handle_br",
                        SIZE, SIZE, (float) net.minecraft.client.Minecraft.getInstance()
                                .getWindow().getGuiScale());
        Canvas canvas = surf.acquireCanvas();
        if (canvas == null) return surf;

        float half = THICKNESS / 2f;
        float r = ARC_RADIUS;
        // 弧心：左上角在盒子的右下角一侧 (SIZE-half, SIZE-half)，右下角在 (half, half)。
        // 这样弧身鼓向盒子的外角（远离元素那一侧），弧的两个端点朝向元素。
        float cx = isTopLeft ? SIZE - half : half;
        float cy = isTopLeft ? SIZE - half : half;
        double a0 = isTopLeft ? Math.PI : 0.0;      // 左上：180°→270°；右下：0°→90°
        double a1 = isTopLeft ? Math.PI * 1.5 : Math.PI * 0.5;

        // 单段弧（Skija 0.143 的 Path 没有 arc，makePolygon + STROKE + ROUND 端帽最稳）
        Point[] pts = new Point[ARC_SEGMENTS + 1];
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            double a = a0 + (a1 - a0) * i / (double) ARC_SEGMENTS;
            pts[i] = new Point((float) (cx + Math.cos(a) * r), (float) (cy + Math.sin(a) * r));
        }
        Path path = Path.makePolygon(pts, false);

        Paint paint = new Paint()
                .setColor(COLOR)
                .setMode(PaintMode.STROKE)
                .setStrokeWidth(THICKNESS)
                .setStrokeCap(PaintStrokeCap.ROUND)
                .setAntiAlias(true);
        canvas.drawPath(path, paint);
        paint.close();
        path.close();

        surf.present();
        return surf;
    }

    static void close() {
        if (topLeft != null) {
            try { topLeft.dispose(); } catch (Exception ignored) { }
            topLeft = null;
        }
        if (bottomRight != null) {
            try { bottomRight.dispose(); } catch (Exception ignored) { }
            bottomRight = null;
        }
    }
}
