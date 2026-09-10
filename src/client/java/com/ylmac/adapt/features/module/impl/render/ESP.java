package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.command.FriendManager;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.ColorSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * ESP —— 高亮所有目标，穿墙可见（移植自 LiquidBounce render/ESP，适配 Fabric 26.2）。
 * <p>
 * LB 原版模式：Box（3D 盒） / 2D（屏幕矩形+血量条） / Legacy2D（2D 标记） / Glow。
 * 本移植（26.2 无 glow shader 通道，Glow 模式以 LB 的 EspGlowMode 行为对齐——LB 中
 * Glow 依赖实体高亮管线 requiresTrueSight；本项目以 Legacy2D 顶替 Glow 的位置，
 * 与 LB 一样用颜色模式区分）：
 * - Box：vanilla Gizmos.cuboid 画 3D 包围盒（半透明填充 + 线框描边）
 * - 2D：投影到屏幕画矩形 + 血量条（GuiGraphicsExtractor 直接画，HudElement 挂载）
 * - Legacy2D：屏幕空间 2D 标记（顶部/底部角标，投影 + GuiGraphics 绘制）
 * <p>
 * 颜色逻辑对齐 LB ModuleESP.getColor：受伤红 → 隐身橙 → 好友绿 → 颜色模式。
 * 颜色模式：Static（静态色）/ Distance（距离 HSB）/ Health（血量）/ Rainbow。
 * <p>
 * 渲染接线：3D 部分（Box）经 EspRendererMixin（GameRenderer.renderLevel RETURN）
 * 回调 onRenderLevel 提交 Gizmos；2D 部分经 Fabric HudElement（extractRenderState）
 * 每帧绘制（与 ModuleListOverlay 同款）。
 */
public class ESP extends Module implements net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement {

    private static ESP instance;

    public static ESP get() {
        return instance;
    }

    private ChoiceSetting mode;
    private ChoiceSetting colorMode;
    private ColorSetting staticColor;
    private ColorSetting invisibleColor;
    private ColorSetting friendColor;
    private DoubleSetting maximumDistance;
    private DoubleSetting expand;

    // 2D 模式设置
    private BoolSetting fill;
    private BoolSetting outline;
    private DoubleSetting outlineThickness;
    private BoolSetting corner;
    private DoubleSetting cornerGap;
    private BoolSetting border;
    private DoubleSetting borderThickness;
    private BoolSetting healthBar;
    private DoubleSetting healthBarSpacing;

    // Legacy2D 设置
    private DoubleSetting markerScale;
    private DoubleSetting yOffset;
    private IntSetting backgroundAlpha;

    /** 每 tick 收集的可见目标（LB RenderedEntities 等价） */
    private final List<LivingEntity> targets = new ArrayList<>();

    // 投影缓存（LB WorldToScreen.setMatrices 等价：每帧由 EspRendererMixin 更新）
    private Vec3 cameraPos = Vec3.ZERO;

    public ESP() {
        super("esp", "ESP", Category.RENDER);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);

        mode = choiceSetting("模式", "Box", List.of("Box", "2D", "Legacy2D"));
        colorMode = choiceSetting("颜色模式", "Static", List.of("Static", "Distance", "Health", "Rainbow"));
        staticColor = colorSetting("颜色", new Color(255, 255, 255, 100),
                () -> colorMode.getValue().equals("Static"));
        invisibleColor = colorSetting("隐身颜色", new Color(255, 165, 0, 255));
        friendColor = colorSetting("好友颜色", new Color(0, 255, 0, 255));
        maximumDistance = doubleSetting("最大距离", 128.0, 1.0, 512.0, 1.0);
        expand = doubleSetting("扩展", 0.05, 0.0, 0.5, 0.01, () -> mode.getValue().equals("Box"));

        // —— 2D ——
        fill = boolSetting("填充", true, () -> mode.getValue().equals("2D"));
        outline = boolSetting("描边", true, () -> mode.getValue().equals("2D"));
        outlineThickness = doubleSetting("描边厚度", 1.0, 1.0, 9.0, 1.0, () -> mode.getValue().equals("2D") && outline.getValue());
        corner = boolSetting("角落模式", false, () -> mode.getValue().equals("2D") && outline.getValue());
        cornerGap = doubleSetting("角落间隙", 50.0, 1.0, 100.0, 1.0, () -> mode.getValue().equals("2D") && outline.getValue() && corner.getValue());
        border = boolSetting("边框", true, () -> mode.getValue().equals("2D"));
        borderThickness = doubleSetting("边框厚度", 1.0, 1.0, 9.0, 1.0, () -> mode.getValue().equals("2D") && border.getValue());
        healthBar = boolSetting("血量条", true, () -> mode.getValue().equals("2D"));
        healthBarSpacing = doubleSetting("血量条间距", 2.0, 0.0, 32.0, 1.0, () -> mode.getValue().equals("2D") && healthBar.getValue());

        // —— Legacy2D ——
        markerScale = doubleSetting("标记缩放", 0.1, 0.02, 0.3, 0.01, () -> mode.getValue().equals("Legacy2D"));
        yOffset = doubleSetting("Y偏移", 0.0, -1.0, 1.0, 0.05, () -> mode.getValue().equals("Legacy2D"));
        backgroundAlpha = intSetting("背景透明度", 150, 0, 255, 1, () -> mode.getValue().equals("Legacy2D"));
    }

    @Override
    public String getInfo() {
        return mode.getValue();
    }

    /** 每 tick 收集可见 LivingEntity（LB RenderedEntities 等价） */
    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (nullCheck()) {
            targets.clear();
            return;
        }
        targets.clear();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && shouldShow(living)) {
                targets.add(living);
            }
        }
    }

    /** 是否展示（LB shouldBeShown 默认 visual targets 等价：玩家 + 敌对 + 可激怒 + 水生） */
    private boolean shouldShow(LivingEntity entity) {
        if (entity == mc.player) return false;
        if (!entity.isAlive() || entity.isDeadOrDying()) return false;
        // LB Targets 默认：PLAYERS / HOSTILE / ANGERABLE / WATER_CREATURE / INVISIBLE
        if (entity instanceof Player) return true;
        if (entity instanceof Enemy) return true;
        if (entity instanceof net.minecraft.world.entity.NeutralMob) return true;
        if (entity instanceof net.minecraft.world.entity.animal.fish.WaterAnimal) return true;
        // INVISIBLE 默认开启：隐身生物也显示（隐身色分支在 getColor 处理）
        return entity.isInvisible() && entity.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER;
    }

    /** 获取 ESP 颜色（LB ModuleESP.getColor 等价） */
    public Color getColor(LivingEntity entity) {
        if (entity.hurtTime > 0) return new Color(255, 0, 0, 255);
        if (entity.isInvisible()) return invisibleColor.getValue();
        if (entity instanceof Player p && FriendManager.get().isFriend(p.getName().getString())
                && friendColor.getValue().getAlpha() > 0) {
            return friendColor.getValue();
        }
        switch (colorMode.getValue()) {
            case "Distance" -> {
                float dist = (float) (mc.player == null ? 0.0 : mc.player.distanceTo(entity));
                float hue = (dist / 200f) % 1f;
                return Color.getHSBColor(hue, 1f, 1f);
            }
            case "Health" -> {
                float max = Math.max(entity.getMaxHealth(), 1f);
                float pct = Math.min(entity.getHealth(), max) / max;
                int red = (int) (255 * (1 - pct));
                int green = (int) (255 * pct);
                return new Color(red, green, 0, 255);
            }
            case "Rainbow" -> {
                float hue = (System.currentTimeMillis() % 3600L) / 3600f;
                return Color.getHSBColor(hue, 0.6f, 1f);
            }
            default -> {
                return staticColor.getValue();
            }
        }
    }

    private double distanceSqToCamera(Entity entity) {
        return entity.position().distanceToSqr(cameraPos);
    }

    // ───────────────────────── 3D（Box） ─────────────────────────

    /** 每帧渲染 3D 部分（EspRendererMixin 回调，Gizmos 收集窗口内） */
    public void onRenderLevel(float partialTicks, Vec3 camPos) {
        if (!isEnabled() || nullCheck()) return;
        this.cameraPos = camPos;
        if (!mode.getValue().equals("Box")) return;

        double maxDistSq = maximumDistance.getValue() * maximumDistance.getValue();
        for (LivingEntity entity : targets) {
            if (distanceSqToCamera(entity) > maxDistSq) continue;
            if (entity.isRemoved()) continue;

            Vec3 pos = entity.position();
            Vec3 oldPos = entity.oldPosition();
            Vec3 renderPos = new Vec3(
                oldPos.x + (pos.x - oldPos.x) * partialTicks,
                oldPos.y + (pos.y - oldPos.y) * partialTicks,
                oldPos.z + (pos.z - oldPos.z) * partialTicks);

            float w = entity.getDimensions(entity.getPose()).width();
            float h = entity.getDimensions(entity.getPose()).height();
            double half = w / 2.0;
            double ex = expand.getValue();
            AABB box = new AABB(
                renderPos.x - half - ex, renderPos.y - ex, renderPos.z - half - ex,
                renderPos.x + half + ex, renderPos.y + h + ex, renderPos.z + half + ex);

            Color c = getColor(entity);
            int argb = (c.getAlpha() << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
            net.minecraft.gizmos.Gizmos.cuboid(box, net.minecraft.gizmos.GizmoStyle.strokeAndFill(argb, 0.05f, argb));
        }
    }

    // ───────────────────────── 2D（HudElement） ─────────────────────────

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker dt) {
        if (!isEnabled() || nullCheck()) return;
        String m = mode.getValue();
        if (!m.equals("2D") && !m.equals("Legacy2D")) return;
        if (mc.player == null) return;

        float partialTicks = dt.getGameTimeDeltaPartialTick(false);
        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();

        try {
            for (LivingEntity entity : targets) {
                if (entity.isRemoved()) continue;
                double maxDistSq = maximumDistance.getValue() * maximumDistance.getValue();
                if (distanceSqToCamera(entity) > maxDistSq) continue;

                Vec3 pos = entity.position();
                Vec3 oldPos = entity.oldPosition();
                Vec3 renderPos = new Vec3(
                    oldPos.x + (pos.x - oldPos.x) * partialTicks,
                    oldPos.y + (pos.y - oldPos.y) * partialTicks,
                    oldPos.z + (pos.z - oldPos.z) * partialTicks);

                float w = entity.getDimensions(entity.getPose()).width();
                float h = entity.getDimensions(entity.getPose()).height();
                double half = w / 2.0;
                AABB box = new AABB(
                    renderPos.x - half, renderPos.y, renderPos.z - half,
                    renderPos.x + half, renderPos.y + h, renderPos.z + half);

                if (m.equals("2D")) {
                    draw2DBox(g, entity, box);
                } else {
                    drawLegacy2D(g, entity, renderPos, h);
                }
            }
        } catch (Throwable t) {
            // 投影/渲染异常忽略（不崩溃 HUD）
        }
    }

    /** 投影世界坐标 → 屏幕坐标（复用项目 WorldToScreen：GUI 缩放坐标，近裁面剔除） */
    private Vec3 projectToScreen(Vec3 pos) {
        org.joml.Vector3f v = com.ylmac.adapt.render.WorldToScreen.calcWorld2Screen(pos);
        if (v == null) return null;
        return new Vec3(v.x, v.y, v.z);
    }

    private void draw2DBox(net.minecraft.client.gui.GuiGraphicsExtractor g, LivingEntity entity, AABB box) {
        // LB calculateScreenRect：8 顶点投影 + min/max 聚合（顶点被近裁面剔除时
        // continue 跳过，而不是整框消失 —— 旧版只投影 2 对角顶点，任一被剔除
        // 就 return，实体贴屏幕边缘/近裁面时 2D 框整框消失）
        double sx = Double.POSITIVE_INFINITY, sy = Double.POSITIVE_INFINITY;
        double ex = Double.NEGATIVE_INFINITY, ey = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            Vec3 v = new Vec3((i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ);
            Vec3 p = projectToScreen(v);
            if (p == null || !Double.isFinite(p.x) || !Double.isFinite(p.y)) continue;
            sx = Math.min(sx, p.x);
            sy = Math.min(sy, p.y);
            ex = Math.max(ex, p.x);
            ey = Math.max(ey, p.y);
        }
        // 全部顶点被剔除（实体完全在相机后方）才不画
        if (!Double.isFinite(sx) || ex <= sx || ey <= sy) return;

        int x0 = (int) Math.round(sx);
        int y0 = (int) Math.round(sy);
        int x1 = (int) Math.round(ex);
        int y1 = (int) Math.round(ey);

        Color c = getColor(entity);
        int argb = (c.getAlpha() << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();

        if (fill.getValue()) {
            int fillColor = (50 << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
            g.fill(x0, y0, x1, y1, fillColor);
        }

        int guiScale = mc.getWindow().getGuiScale();
        double outlineTh = outlineThickness.getValue() / guiScale;
        int oW = Math.max(1, (int) Math.round(outlineTh));
        int bW = border.getValue() ? Math.max(1, (int) Math.round(borderThickness.getValue() / guiScale)) : 0;

        if (outline.getValue()) {
            if (corner.getValue()) {
                // LB Esp2DMode Corner：四角各 2 条短线段（水平 + 垂直），间隙按 cornerGap
                double gapPercent = Math.max(0.0, Math.min(1.0, cornerGap.getValue() / 100.0));
                int cw = (int) Math.round((x1 - x0) * (1.0 - gapPercent) / 2.0);
                int ch = (int) Math.round((y1 - y0) * (1.0 - gapPercent) / 2.0);
                if (border.getValue()) {
                    // 黑色粗线段垫底
                    int bw = oW + bW;
                    // 左上
                    g.fill(x0 - bw, y0 - bw, x0 + cw, y0 - oW, 0xFF000000);
                    g.fill(x0 - bw, y0 - bw, x0 - oW, y0 + ch, 0xFF000000);
                    // 右上
                    g.fill(x1 - cw, y0 - bw, x1 + bw, y0 - oW, 0xFF000000);
                    g.fill(x1 + oW, y0 - bw, x1 + bw, y0 + ch, 0xFF000000);
                    // 左下
                    g.fill(x0 - bw, y1 + oW, x0 + cw, y1 + bw, 0xFF000000);
                    g.fill(x0 - bw, y1 - ch, x0 - oW, y1 + bw, 0xFF000000);
                    // 右下
                    g.fill(x1 - cw, y1 + oW, x1 + bw, y1 + bw, 0xFF000000);
                    g.fill(x1 + oW, y1 - ch, x1 + bw, y1 + bw, 0xFF000000);
                }
                // 彩色角线段（4 角 × 2 段）
                // 左上
                g.fill(x0 - oW, y0 - oW, x0 + cw, y0, argb);
                g.fill(x0 - oW, y0 - oW, x0, y0 + ch, argb);
                // 右上
                g.fill(x1 - cw, y0 - oW, x1 + oW, y0, argb);
                g.fill(x1, y0 - oW, x1 + oW, y0 + ch, argb);
                // 左下
                g.fill(x0 - oW, y1, x0 + cw, y1 + oW, argb);
                g.fill(x0 - oW, y1 - ch, x0, y1 + oW, argb);
                // 右下
                g.fill(x1 - cw, y1, x1 + oW, y1 + oW, argb);
                g.fill(x1, y1 - ch, x1 + oW, y1 + oW, argb);
            } else {
                if (border.getValue()) {
                    // 黑色外框
                    g.fill(x0 - oW - bW, y0 - oW - bW, x1 + oW + bW, y0 - oW, 0xFF000000);
                    g.fill(x0 - oW - bW, y1 + oW, x1 + oW + bW, y1 + oW + bW, 0xFF000000);
                    g.fill(x0 - oW - bW, y0 - oW, x0 - oW, y1 + oW, 0xFF000000);
                    g.fill(x1 + oW, y0 - oW, x1 + oW + bW, y1 + oW, 0xFF000000);
                }
                // 描边线
                g.fill(x0 - oW, y0 - oW, x1 + oW, y0, argb);
                g.fill(x0 - oW, y1, x1 + oW, y1 + oW, argb);
                g.fill(x0 - oW, y0 - oW, x0, y1 + oW, argb);
                g.fill(x1, y0 - oW, x1 + oW, y1 + oW, argb);
            }
        }

        if (healthBar.getValue()) {
            float hpMax = Math.max(entity.getMaxHealth(), 1f);
            float pct = Math.min(entity.getHealth(), hpMax) / hpMax;
            int hh = Math.max(1, (int) Math.round((y1 - y0) * pct));
            // LB 对齐：血条画在框内左侧（框左缘内缩一个线宽），贴左缘时不越界
            int hx = x0 - Math.max(1, (int) Math.round(borderThickness.getValue() / guiScale)) - 3;
            hx = Math.max(0, hx);
            int hc = (255 << 24) | ((int) (255 * (1 - pct)) << 16) | ((int) (255 * pct) << 8);
            g.fill(hx, y1 - hh, hx + 1, y1, hc);
            if (border.getValue()) {
                g.fill(hx - 1, y0 - 1, hx + 2, y1 + 1, 0xFF000000);
            }
        }
    }

    private void drawLegacy2D(net.minecraft.client.gui.GuiGraphicsExtractor g, LivingEntity entity,
                              Vec3 renderPos, double entityHeight) {
        double yOff = yOffset.getValue();
        Vec3 top = projectToScreen(new Vec3(renderPos.x, renderPos.y + entityHeight + yOff, renderPos.z));
        Vec3 bottom = projectToScreen(new Vec3(renderPos.x, renderPos.y + yOff, renderPos.z));
        if (top == null || bottom == null) return;

        // 固定像素尺寸（不受世界 scale 影响；旧版 14*scale 默认 0.1 → 1.4px 几乎不可见）
        double scale = Math.max(0.5, markerScale.getValue() * 10.0);
        Color c = getColor(entity);
        int fg = (c.getAlpha() << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
        int bg = (backgroundAlpha.getValue() << 24);

        int tx = (int) Math.round(top.x);
        int ty = (int) Math.round(top.y);
        int bx = (int) Math.round(bottom.x);
        int by = (int) Math.round(bottom.y);

        int w = (int) Math.round(14 * scale);
        int h = (int) Math.round(2 * scale);
        // 顶部两条横杠 + 底部两条横杠（LB drawLegacy2DMarker 简化为角标）
        g.fill(tx - w, ty, tx - w / 3, ty + h, bg);
        g.fill(tx + w / 3, ty, tx + w, ty + h, bg);
        g.fill(tx - w, ty, tx - w + h, ty + h, fg);
        g.fill(tx + w - h, ty, tx + w, ty + h, fg);
        g.fill(bx - w, by - h, bx - w / 3, by, bg);
        g.fill(bx + w / 3, by - h, bx + w, by, bg);
        g.fill(bx - w, by - h, bx - w + h, by, fg);
        g.fill(bx + w - h, by - h, bx + w, by, fg);
    }

    // 未使用辅助（避免误用：projectToScreen 的 z 为深度，未用）
}
