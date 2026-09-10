package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 绕目标环绕（移植自 Rise 6.9.5 TargetStrafe 核心逻辑，适配 Fabric 26.2）。
 * <p>
 * 原理（Rise 原版 + LiquidBounce 半径维持 + 26.2 API 已 javap 确证）：
 * - Rise 逻辑：找最近有效玩家（TargetComponent，非本人/存活/未隐身），
 *   以目标为圆心、Range 为半径，把玩家的移动方向（yaw）改成「绕目标圆周切线」方向，
 *   使玩家在按住 W 冲向目标时自动绕圈（KillAura 环绕/刷怪塔走位）。
 * - 26.2 实现：
 *   1. 目标：遍历 {@code mc.level.players()}（Level.getEntitiesOfClass(Player) 等价，
 *      ClientLevel.players()），过滤本人/spectator/隐身/已移除/死亡，取最近；
 *   2. 绕向角度：先算「玩家→目标」的 yaw（atan2(-dx, dz)，与 26.2 视线方向一致），
 *      然后旋转 90°（方向切换 left/right 控制旋转正负）得到圆周切线 yaw；
 *   3. 距离维持（Rise Range 半径维持）：每 tick 计算玩家到目标的水平距离，
 *      与「距离」设置比较——太远（> range）则向圆心补速度（离心分量取反），
 *      太近（< range）则向离心方向补速度，始终把玩家拉回圆周上；
 *      绕行切线速度（speed 设置）与半径修正向量合成后写入水平 deltaMovement。
 *   4. 旋转：可选手动把视线转回目标（等价 Rise 的 yaw 托管），关闭则只改移动不改视角。
 * - 按键切换方向：按住左移键（keyLeft）绕逆时针、右移键（keyRight）绕顺时针（Rise 同款）。
 * <p>
 * 设置：距离（半径）、速度、方向切换、是否修改视角、是否贴地（仅地面生效）。
 * 说明：Rise 原版依赖 KillAura 存在才激活 + 渲染圆圈，本移植聚焦「圆周移动」核心，
 * 无条件激活（只要找到目标）。
 */
public class TargetStrafe extends Module {

    /** 绕行半径（格，对应 Rise Range 设置，默认 3） */
    private final DoubleSetting range;
    /** 绕行速度（m/tick，默认 0.26 ≈ 冲刺步行速度） */
    private final DoubleSetting speed;
    /** 方向切换：true = 按住左键逆时针、右键顺时针（Rise 默认） */
    private final BoolSetting swapDirection;
    /** 是否修改视角朝向目标（Rise 默认跟随，关闭则只改移动不改视角） */
    private final BoolSetting rotate;
    /** 仅在空中也生效（默认 true：空中也绕；false = 仅地面） */
    private final BoolSetting air;

    /** 当前绕行方向：true = 左(逆时针)，false = 右(顺时针)；由按键切换 */
    private boolean left = true;

    public TargetStrafe() {
        super("targetstrafe", "TargetStrafe", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        range = doubleSetting("距离", 3.0, 1.0, 8.0, 0.1);
        speed = doubleSetting("速度", 0.26, 0.05, 0.6, 0.01);
        swapDirection = boolSetting("方向切换", true);
        rotate = boolSetting("修改视角", true);
        air = boolSetting("空中生效", true);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck() || !isEnabled()) return;
        if (!air.getValue() && !mc.player.onGround()) {
            return;
        }

        Player target = findTarget();
        if (target == null) {
            return;
        }

        // 方向切换：按住左/右移动键翻转绕行方向（Rise：keyBindLeft / keyBindRight）
        if (swapDirection.getValue()) {
            boolean leftKey = mc.options.keyLeft.isDown();
            boolean rightKey = mc.options.keyRight.isDown();
            if (leftKey && !rightKey) {
                left = true;
            } else if (rightKey && !leftKey) {
                left = false;
            }
        }

        // 玩家 → 目标 的 yaw（与 26.2 视线计算一致：atan2(-dx, dz)）
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        float yawToTarget = (float) Math.toDegrees(Math.atan2(-dx, dz));

        // 圆周切线 yaw：绕向切线 = 目标方向 ± 90°（left=逆时针→+90，right=顺时针→-90）
        float tangentYaw = yawToTarget + (left ? 90.0F : -90.0F);
        tangentYaw = Mth.wrapDegrees(tangentYaw);

        double rad = Math.toRadians(tangentYaw);
        double radToTarget = Math.toRadians(yawToTarget);
        double vx = -Math.sin(rad) * speed.getValue();
        double vz = Math.cos(rad) * speed.getValue();

        // 距离维持（Rise Range 半径修正；向量合成参考 LiquidBounce 的
        // encirclement = clamp(distance - range, -speed, speed)）：
        // - 太远（distance > range）：向圆心方向补速度（encirclement 为负，
        //   方向 = 目标方向的反方向），把玩家拉回圆周；
        // - 太近（distance < range）：向离心方向补速度（encirclement 为正，
        //   方向 = 目标方向），把玩家推出圆周；
        // - 修正量不超过切线速度（clamp ±speed），避免半径修正喧宾夺主。
        double distance = Math.hypot(dx, dz);
        double rangeVal = range.getValue();
        double encirclement = Mth.clamp(distance - rangeVal, -speed.getValue(), speed.getValue());
        vx += -Math.sin(radToTarget) * encirclement;
        vz += Math.cos(radToTarget) * encirclement;

        Vec3 delta = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(vx, delta.y, vz);

        // 修改视角：视线始终对准目标（Rise 用托管 yaw 让攻击/移动朝目标）
        if (rotate.getValue()) {
            mc.player.setYRot(yawToTarget);
            mc.player.setXRot(0.0F);
            mc.player.setYHeadRot(yawToTarget);
        }
    }

    /** 找最近的合法目标（非本人、存活、非隐身、非旁观、未移除） */
    private Player findTarget() {
        Player nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (Player player : mc.level.players()) {
            if (player == mc.player) {
                continue;
            }
            if (!player.isAlive() || player.isInvisible() || player.isSpectator() || player.isRemoved()) {
                continue;
            }
            double dist = mc.player.distanceToSqr(player);
            if (dist < bestDist) {
                bestDist = dist;
                nearest = player;
            }
        }
        return nearest;
    }

}
