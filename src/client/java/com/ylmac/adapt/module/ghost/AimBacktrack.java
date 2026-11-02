package com.ylmac.adapt.module.ghost;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * AimBacktrack 瞄准回溯。
 * <p>
 * PacketSendEvent 里当攻击包发出且 objectMouseOver 为 MISS 时，用列表里的
 * 历史旋转逐一 raycast，命中实体即 attackEntity（在准星没对准时仍能打中
 * 刚刚瞄准过的位置）。onAttack 里 attacked 标记防同一 tick 重复攻击。
 * <p>
 * 26.2 适配（已 javap 确证，语义一一对应）：
 * - 旋转记录：ClientTickEvent.Pre 记录玩家 yaw/pitch 进环形缓冲（等价 EvictingList，
 * 容量由 backtrack 设置控制，1~20）
 * - 触发：PlayerTickEvent.Post 里检测"攻击意图"（keyAttack.isDown 且
 * mc.hitResult 为 MISS — 原版是 PacketSendEvent 的 ServerboundAttackPacket，
 * 26.2 攻击统一走 Minecraft.startAttack → gameMode.attack，空挥（MISS）时
 * startAttack 只 swing 不发攻击包，故等价触发点 = 玩家按下攻击且准星 MISS）
 * - 回溯攻击：用最新历史旋转计算视线向量（Entity.calculateViewVector(xRot, yRot)
 * 公开 final，与 getViewVector 同实现），对世界内 LivingEntity 做 AABB 射线
 * 求交（AABB.clip 返回 Optional<Vec3>），命中且存活 → mc.gameMode.attack
 * （26.2 attack 发 ServerboundAttackPacket，与服务端判定攻击）
 * - 渲染：GameRendererAimBacktrackMixin 在 renderLevel RETURN 时回调本模块，
 * 用 vanilla Gizmos（LevelExtractor.collectPerFrameMainThreadGizmos 激活期间
 * 调 Gizmos.cuboid）画出回溯目标的包围盒线框 — 26.2 无旧式 Tesselator，
 * Gizmos 是官方调试框渲染通道（Mojang 调试渲染器同款）
 */
public class AimBacktrack extends Module {

    /** 单例引用（GameRendererAimBacktrackMixin 经此调用 onRenderLevel） */
    private static AimBacktrack instance;

    public static AimBacktrack get() {
        return instance;
    }

 /** 回溯帧数 */
    private IntSetting backtrack;
    /** 是否渲染回溯目标框（Gizmos 线框） */
    private BoolSetting render;

    /** 历史旋转（环形缓冲，容量=backtrack，最新在后） */
    private final List<float[]> previousRotations = new ArrayList<>();
    /** 回溯攻击命中的目标（渲染用，每 tick 更新） */
    private Entity backtrackTarget;

    public AimBacktrack() {
        super("aimbacktrack", "AimBacktrack", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        backtrack = intSetting("backtrack", 1, 1, 20, 1);
        render = boolSetting("render", true);
    }

    @Override
    protected void onEnable() {
        previousRotations.clear();
        backtrackTarget = null;
    }

    @Override
    protected void onDisable() {
        previousRotations.clear();
        backtrackTarget = null;
    }

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;
        if (!this.isEnabled()) return;
 // 记录本 tick 的旋转
        float[] rot = new float[]{mc.player.getYRot(), mc.player.getXRot()};
        previousRotations.add(rot);
        int cap = Math.max(1, backtrack.getValue());
        while (previousRotations.size() > cap) {
            previousRotations.remove(0);
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (nullCheck()) return;
        if (!this.isEnabled()) return;
        if (mc.gui.screen() != null) return;
 // 攻击意图：左键按下 且 当前准星未命中
        if (!mc.options.keyAttack.isDown()) return;
        HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() != HitResult.Type.MISS) return;
        backtrackTarget = this.findBacktrackTarget();
        if (backtrackTarget != null) {
 // 直接对旧位目标发起攻击
            mc.gameMode.attack(mc.player, backtrackTarget);
            mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        }
    }

    /**
 * 用历史旋转射线找目标：
 * 从最新的历史旋转开始（最接近当前瞄准），命中最近的存活 LivingEntity。
 * 26.2 射线 = Entity.calculateViewVector(xRot, yRot)（与 getViewVector 同实现，
 * 已 javap 确证），实体求交 = AABB.clip(from, to)（Optional<Vec3>）。
 */
    private Entity findBacktrackTarget() {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double reach = 4.5; // 26.2 基础攻击距离（不含武器攻击范围组件加成）
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = previousRotations.size() - 1; i >= 0; i--) {
            float[] rot = previousRotations.get(i);
            Vec3 look = mc.player.calculateViewVector(rot[1], rot[0]);
            Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity living)) continue;
                if (entity == mc.player || !entity.isAlive() || entity.isRemoved()) continue;
                float border = entity.getPickRadius();
                AABB box = entity.getBoundingBox().inflate(border, border, border);
                Vec3 hitVec = box.clip(eye, end).orElse(null);
                if (hitVec == null && box.contains(eye)) hitVec = eye;
                if (hitVec == null) continue;
                double dist = eye.distanceTo(hitVec);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = entity;
                }
            }
 // 命中即返回
            if (best != null) return best;
        }
        return best;
    }

    /**
 * GameRendererAimBacktrackMixin 回调（renderLevel RETURN，渲染线程/主线程帧内）：
 * 在 per-frame Gizmos 收集器激活期间画回溯目标框。
 * 只在有回溯目标时提交一个 cuboid（红色线框，ARGB 0xFFFF5555）。
 */
    public void onRenderLevel() {
        if (!this.isEnabled()) return;
        if (nullCheck()) return;
        Entity target = this.backtrackTarget;
        if (target == null || target.isRemoved()) return;
        AABB box = target.getBoundingBox().inflate(target.getPickRadius());
        Gizmos.cuboid(box, GizmoStyle.stroke(0xFFFF5555));
    }

    @Override
    public String getInfo() {
        return String.valueOf(backtrack.getValue());
    }
}
