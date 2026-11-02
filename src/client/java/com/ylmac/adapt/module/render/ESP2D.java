package com.ylmac.adapt.module.render;

import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.command.FriendManager;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.ColorSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.render.surface.MinecraftSurfaceBackend;
import com.ylmac.adapt.render.font.RiseFont;
import com.ylmac.adapt.render.surface.SkijaNativeFix;
import com.ylmac.adapt.render.hud.WorldToScreen;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.awt.*;
import com.ylmac.adapt.render.hud.ModuleListOverlay;
import com.ylmac.adapt.render.hud.WorldLyricOverlay;

/**
 * <p>
 * <p>
 * 原版行为：
 * - 实体过滤：Players / Friends / Creatures / Monsters / Ambients / Others（默认只开
 * Players + Friends；玩家默认橙色 0xFF9200、好友绿色 0x30FF00）。
 * - 实体 AABB 8 顶点投影到屏幕，取包围盒 → 2D 方框（黑描边 + 实体色 0.5px 内框）。
 * max(0, absorption)；fillY 按 healthRatio；条宽/间隙/描边按高度缩放
 * （distanceScale = height/45）。
 * - 血量数值：实体距离越远缩放越小。
 * <p>
 * 渲染接入：本项目 Render2DEvent 无派发点（26.2 不再 dispatch），世界 2D HUD 走
 * Fabric HudElement（ModuleListOverlay / WorldLyricOverlay 同款）—每帧在
 * GuiGraphicsExtractor 提取阶段用 MinecraftSurfaceBackend 渲染 Skia surface，
 * 再 blit 到屏幕。本模块注册独立 HudElement "adaptclient:esp2d"。
 * <p>
 * 26.2 差异（javap 验证）：
 * - CameraRenderState 公开 pos/viewRotationMatrix/projectionMatrix 字段。
 * - mc.options.hideGui 已移除 → 用 mc.gui.screen == null 作为 GUI 打开判断。
 */
public class ESP2D extends Module implements HudElement {

    public static ESP2D INSTANCE;

    private final BoolSetting players = boolSetting("Players", true);
    private final BoolSetting friends = boolSetting("Friends", true);
    private final BoolSetting creatures = boolSetting("Creatures", false);
    private final BoolSetting monsters = boolSetting("Monsters", false);
    private final BoolSetting ambients = boolSetting("Ambients", false);
    private final BoolSetting others = boolSetting("Others", false);
    private final BoolSetting renderHealth = boolSetting("Render Health", true);
    private final DoubleSetting healthBarWidth = doubleSetting("Health Bar Width", 2.0, 0.5, 6.0, 0.5, renderHealth::getValue);
    private final BoolSetting healthBarOutline = boolSetting("Health Bar Outline", true, renderHealth::getValue);
    private final DoubleSetting healthBarOutlineWidth = doubleSetting("Health Bar Outline Width", 1.0, 0.5, 3.0, 0.5, () -> renderHealth.getValue() && healthBarOutline.getValue());
    private final BoolSetting renderBox = boolSetting("Render Box", true);
    private final BoolSetting boxOutline = boolSetting("Box Outline", true, renderBox::getValue);
    private final BoolSetting renderName = boolSetting("Render Name", true);

    private final ColorSetting playersColor = colorSetting("Players Color", new Color(0xFF9200), false);
    private final ColorSetting friendsColor = colorSetting("Friends Color", new Color(0x30FF00), false);
    private final ColorSetting creaturesColor = colorSetting("Creatures Color", new Color(0xA0A4A6), false);
    private final ColorSetting monstersColor = colorSetting("Monsters Color", new Color(0xFF0000), false);
    private final ColorSetting ambientsColor = colorSetting("Ambients Color", new Color(0x7B00FF), false);
    private final ColorSetting othersColor = colorSetting("Others Color", new Color(0xFF0062), false);
    private final ColorSetting healthColor = colorSetting("Health Color", new Color(0x2FFF00), false, renderHealth::getValue);

    private final Paint paint = new Paint();
    private MinecraftSurfaceBackend surf;
    private boolean registered;

    public ESP2D() {
        super("esp2d", "ESP2D", Category.RENDER);
        INSTANCE = this;
    }

    // ── HudElement 注册 ──
    // 注册表项只在首次注册时 addLast 一次，绝不 removeElement：
    // 1) fabric HudElementRegistryImpl.addLast 要求 id 全局唯一，重复 add 抛
    // IllegalArgumentException（本类抛给上层=损坏的注册表，ESP2D 之后的新增模块
    // HudElement 全部无法渲染 — 用户侧"ESP2D 没有任何渲染"）。
    // 2) removeElement 后同 id 重新 addLast 同样违反唯一性，且旧 HudLayer 被标记
    // isRemoved 后不再渲染；26.2 的 HudElementRegistryImpl 也未提供可靠的
    // re-add 语义。
    // 3) 渲染与否由 extractRenderState 的 isEnabled 门控，无需动态注册/注销
    // （ModuleListOverlay / WorldLyricOverlay 同款：客户端初始化时注册一次）。
    @Override
    protected void onEnable() {
        registerHud();
    }

    @Override
    protected void onDisable() {
        // 仅释放 surface（注册表项保留；extractRenderState 以 isEnabled 门控）
        if (surf != null) {
            try { surf.dispose(); } catch (Exception ignored) {}
            surf = null;
        }
    }

    /** HudElement 注册（与 ModuleListOverlay/WorldLyricOverlay 同款 Fabric HUD 机制） */
    public void registerHud() {
        if (registered) return;
        registered = true;
        HudElementRegistry.addLast(Identifier.parse("adaptclient:esp2d"), this);
    }

    // ── HudElement 渲染：Skia surface → blit 到屏幕 ──
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.gui.screen() != null) return;

        int scrW = mc.getWindow().getGuiScaledWidth();
        int scrH = mc.getWindow().getGuiScaledHeight();
        if (scrW <= 0 || scrH <= 0) return;

        ensureSurface(scrW, scrH);
        if (surf == null) return;

        try {
            Canvas canvas = surf.acquireCanvas();
            if (canvas == null) return;
            render(canvas, scrW, scrH);
            surf.present();

            // 全屏 surface（设计分辨率 = GUI 分辨率，1:1 blit）
            int texW = surf.getDisplayWidth();
            int texH = surf.getDisplayHeight();
            var pose = g.pose();
            pose.pushMatrix();
            g.blit(surf.blitPipeline(), surf.getTextureId(),
                    0, 0, 0f, 0f,
                    scrW, scrH, texW, texH, texW, texH);
            pose.popMatrix();
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[ESP2D] render error: {}", t.toString());
        }
    }

    private void ensureSurface(int scrW, int scrH) {
        // 原生设备像素：物理纹理 = GUI 尺寸 × 窗口 GUI scale（贴图 1:1）
        float deviceScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        if (surf != null && surf.width() == scrW && surf.height() == scrH) {
            surf.ensureDeviceScale(deviceScale);   // GUI scale 变了才重建
            return;
        }
        try {
            if (surf != null) surf.dispose();
            SkijaNativeFix.preload();
            surf = new MinecraftSurfaceBackend("esp2d", scrW, scrH, deviceScale);
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[ESP2D] surface init failed: {}", t.toString());
            surf = null;
        }
    }

    private void render(Canvas canvas, float screenWidth, float screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity livingEntity) || !shouldRender(livingEntity)) continue;

            Vec3 renderPosition = livingEntity.getPosition(partialTick);
            AABB box = livingEntity.getBoundingBox().move(renderPosition.subtract(livingEntity.position()));
            float x = Float.POSITIVE_INFINITY;
            float y = Float.POSITIVE_INFINITY;
            float endX = Float.NEGATIVE_INFINITY;
            float endY = Float.NEGATIVE_INFINITY;

            for (int vertex = 0; vertex < 8; vertex++) {
                Vec3 worldVertex = new Vec3(
                        (vertex & 1) == 0 ? box.minX : box.maxX,
                        (vertex & 2) == 0 ? box.minY : box.maxY,
                        (vertex & 4) == 0 ? box.minZ : box.maxZ
                );
                Vector3f projected = WorldToScreen.calcWorld2Screen(worldVertex);
                if (projected == null || !Float.isFinite(projected.x) || !Float.isFinite(projected.y)) continue;

                x = Math.min(x, projected.x);
                y = Math.min(y, projected.y);
                endX = Math.max(endX, projected.x);
                endY = Math.max(endY, projected.y);
            }

            if (!Float.isFinite(x) || endX < 0.0f || endY < 0.0f || x > screenWidth || y > screenHeight) continue;

            if (renderBox.getValue()) {
                if (boxOutline.getValue()) {
                    drawRect(canvas, x - 1.0f, y, 1.5f, endY - y + 0.5f, Color.BLACK.getRGB());
                    drawRect(canvas, x - 1.0f, y - 0.5f, endX - x + 1.5f, 1.0f, Color.BLACK.getRGB());
                    drawRect(canvas, endX - 1.0f, y, 1.5f, endY - y + 0.5f, Color.BLACK.getRGB());
                    drawRect(canvas, x - 1.0f, endY - 1.0f, endX - x + 1.5f, 1.5f, Color.BLACK.getRGB());
                }
                drawSolidBox(canvas, x, y, endX, endY, getEntityColor(livingEntity));
            }

            if (renderHealth.getValue()) {
                drawHealthBar(canvas, livingEntity, x, y, endY);
            }

            if (renderName.getValue()) {
                drawName(canvas, livingEntity, x, endX, y);
            }
        }
    }

    private boolean shouldRender(Entity entity) {
        if (mc.player == null) return false;
        if (!entity.isAlive() || entity.isSpectator()) return false;

        if (entity instanceof Player player) {
            if (entity == mc.player) return false;
            if (FriendManager.get().isFriend(player.getScoreboardName())) return friends.getValue();
            return players.getValue();
        }

        MobCategory category = entity.getType().getCategory();
        return switch (category) {
            case CREATURE, WATER_CREATURE, AXOLOTLS, UNDERGROUND_WATER_CREATURE -> creatures.getValue();
            case MONSTER -> monsters.getValue();
            case AMBIENT, WATER_AMBIENT -> ambients.getValue();
            default -> others.getValue();
        };
    }

    private Color getEntityColor(LivingEntity entity) {
        if (entity instanceof Player player) {
            if (FriendManager.get().isFriend(player.getScoreboardName())) return friendsColor.getValue();
            return playersColor.getValue();
        }

        MobCategory category = entity.getType().getCategory();
        return switch (category) {
            case CREATURE, WATER_CREATURE, AXOLOTLS, UNDERGROUND_WATER_CREATURE -> creaturesColor.getValue();
            case MONSTER -> monstersColor.getValue();
            case AMBIENT, WATER_AMBIENT -> ambientsColor.getValue();
            default -> othersColor.getValue();
        };
    }

    private void drawSolidBox(Canvas canvas, float x, float y, float endX, float endY, Color color) {
        int rgb = color.getRGB();
        drawRect(canvas, x - 0.5f, y, 0.5f, endY - y, rgb);
        drawRect(canvas, x, endY - 0.5f, endX - x, 0.5f, rgb);
        drawRect(canvas, x - 0.5f, y, endX - x + 0.5f, 0.5f, rgb);
        drawRect(canvas, endX - 0.5f, y, 0.5f, endY - y, rgb);
    }

    private void drawHealthBar(Canvas canvas, LivingEntity entity, float x, float y, float endY) {
        float height = endY - y;
        if (height <= 0.0f) return;

        float health = entity.getHealth() + entity.getAbsorptionAmount();
        float maxHealth = Math.max(1.0f, entity.getMaxHealth() + Math.max(0.0f, entity.getAbsorptionAmount()));
        float healthRatio = Mth.clamp(health / maxHealth, 0.0f, 1.0f);
        float fillY = endY - height * healthRatio;

        float distanceScale = height / 45.0f;
        float width = healthBarWidth.getValue().floatValue() * distanceScale;
        float gap = 3.0f * distanceScale;
        float outlineWidth = healthBarOutline.getValue() ? healthBarOutlineWidth.getValue().floatValue() * distanceScale : 0.0f;
        float barX = x - gap - outlineWidth - width;

        if (healthBarOutline.getValue()) {
            drawRect(canvas, barX - outlineWidth, y - outlineWidth,
                    width + outlineWidth * 2.0f, height + outlineWidth * 2.0f, Color.BLACK.getRGB());
        } else {
            drawRect(canvas, barX, y, width, height, Color.BLACK.getRGB());
        }

        drawRect(canvas, barX, fillY, width, endY - fillY, healthColor.getValue().getRGB());
    }

 /** 名字：方框上方居中 */
    private void drawName(Canvas canvas, LivingEntity entity, float x, float endX, float boxY) {
        String name = entity.getDisplayName() != null
                ? entity.getDisplayName().getString() : entity.getName().getString();
        if (name.isEmpty()) return;
        RiseFont font = RiseFont.main(14, RiseFont.Weight.REGULAR);
        float w = font.getStringWidth(name);
        drawText(canvas, name, (x + endX) / 2 - w / 2, boxY - font.height() - 2, getEntityColor(entity).getRGB());
    }

    private void drawRect(Canvas canvas, float x, float y, float w, float h, int color) {
        if (w <= 0 || h <= 0) return;
        paint.setColor(color);
        canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint);
    }

    private void drawText(Canvas canvas, String text, float x, float y, int color) {
        RiseFont font = RiseFont.main(14, RiseFont.Weight.REGULAR);
 // 先画黑阴影再画实体色
        font.drawText(canvas, text, x + 0.5f, y + 0.5f, 0xFF000000, paint);
        font.drawText(canvas, text, x, y, color, paint);
    }
}
