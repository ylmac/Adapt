package com.ylmac.adapt.features.module.util;

import com.ylmac.adapt.features.event.impl.KeyboardInputEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 旋转 / 移动修正公共工具（Rise: RotationUtil + MoveUtil.fixMovement 的 26.2 版）。
 * <p>
 * 凡是通过 SendPositionEvent 把"服务器看到的偏航"改成别的值的模块（Scaffold 对准
 * 方块、KillAura 瞄准目标），都必须同时修正移动输入，否则玩家按 W 会朝服务器看到
 * 的方向走、而不是自己看的方向。
 */
public final class RotationUtil {

    private RotationUtil() {
    }

    /**
     * 移动修正（Rise {@code MoveUtil.fixMovement} 原版算法）。
     * <p>
     * 思路：先算出玩家**原本想走的世界方向**（用他自己的视角 + 原始输入），再在
     * 8 个输入组合（forward/strafe ∈ {-1,0,1}）里挑一个，使得**用 serverYaw 表达**
     * 时的世界方向最接近该方向，然后替换输入。
     *
     * @param serverYaw 服务器实际看到的偏航（即模块写入 SendPosition 的值）
     */
    public static void fixMovement(KeyboardInputEvent event, LocalPlayer player, float serverYaw) {
        if (player == null) return;
        float forward = event.getForward();
        float strafe = event.getStrafe();
        if (forward == 0.0F && strafe == 0.0F) return;

        // 玩家原本想走的世界方向
        double wanted = Math.toDegrees(direction(player.getYRot(), forward, strafe));

        float bestForward = 0.0F;
        float bestStrafe = 0.0F;
        float bestDiff = Float.MAX_VALUE;
        for (float f = -1.0F; f <= 1.0F; f++) {
            for (float s = -1.0F; s <= 1.0F; s++) {
                if (f == 0.0F && s == 0.0F) continue;
                double candidate = Math.toDegrees(direction(serverYaw, f, s));
                double diff = Math.abs(Mth.wrapDegrees((float) (wanted - candidate)));
                if (diff < bestDiff) {
                    bestDiff = (float) diff;
                    bestForward = f;
                    bestStrafe = s;
                }
            }
        }
        event.setForward(bestForward);
        event.setStrafe(bestStrafe);
    }

    /**
     * 给定视角偏航与输入，返回移动方向的世界角度（弧度，与 yaw 同向）。
     * 推导自 26.2 的移动向量换算：relative=(strafe,0,forward) 经 yaw 旋转后
     * worldX = strafe*cos - forward*sin，worldZ = forward*cos + strafe*sin。
     * wanted / candidate 用同一函数求，比较即自洽（不需要绝对角度正确）。
     */
    public static double direction(float yaw, float forward, float strafe) {
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double worldX = strafe * cos - forward * sin;
        double worldZ = forward * cos + strafe * sin;
        return Math.atan2(-worldX, worldZ);
    }

    /**
     * 瞄准实体包围盒中心的旋转（Rise RotationUtil.calculate）。
     *
     * @return {yaw, pitch}，yaw 已归一化到 [-180,180]，pitch 已 clamp
     */
    public static float[] getRotations(LivingEntity living, Vec3 eye) {
        Vec3 targetPos = living.getBoundingBox().getCenter();
        double dx = targetPos.x() - eye.x();
        double dy = targetPos.y() - eye.y();
        double dz = targetPos.z() - eye.z();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0F, 90.0F)};
    }

    /**
     * 瞄准世界坐标点的旋转。
     *
     * @return {yaw, pitch}
     */
    public static float[] getRotationsTo(Vec3 targetPos, Vec3 eye) {
        double dx = targetPos.x() - eye.x();
        double dy = targetPos.y() - eye.y();
        double dz = targetPos.z() - eye.z();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0F, 90.0F)};
    }

    /**
     * 平滑逼近目标旋转（vape AimAssist 同款数学）。
     * <p>
     * ⚠ 基准必须每 tick 取玩家的**当前**角度，不能用模块自己维护的累加变量：
     * 玩家中途用鼠标转头后，累加变量还停在旧角度，模块就会从旧角度一路把
     * 视角拽回去 —— 表现为"目标明明在前面却绕一大圈"。
     *
     * @return {newYaw, newPitch}
     */
    public static float[] smoothApproach(float currentYaw, float currentPitch,
                                         float targetYaw, float targetPitch, float maxStep) {
        float diffYaw = Mth.wrapDegrees(targetYaw - currentYaw);
        if (diffYaw > maxStep) diffYaw = maxStep;
        if (diffYaw < -maxStep) diffYaw = -maxStep;
        float diffPitch = targetPitch - currentPitch;
        if (diffPitch > maxStep) diffPitch = maxStep;
        if (diffPitch < -maxStep) diffPitch = -maxStep;
        return new float[]{
                Mth.wrapDegrees(currentYaw + diffYaw),
                Mth.clamp(currentPitch + diffPitch, -90.0F, 90.0F)
        };
    }
}
