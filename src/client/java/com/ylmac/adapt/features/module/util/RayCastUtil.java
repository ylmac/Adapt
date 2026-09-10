package com.ylmac.adapt.features.module.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * RayCastUtil —— 移植 Rise RayCastUtil（按 yaw/pitch 射线检测放置面）。
 * <p>
 * Rise 原版（com.alan.clients.util.packet.RayCastUtil）：
 * - c(yaw, pitch, dist)：从眼睛沿方向射 dist，返回 BlockHitResult（Rise: entity.rayTraceCustom）
 * - overBlock(facing, pos, strict)：mc.objectMouseOver 是否命中指定方块面
 * <p>
 * 26.2 对应：level.clip(ClipContext)（javap 确证）。
 */
public final class RayCastUtil {

    private static final Minecraft mc = Minecraft.getInstance();

    private RayCastUtil() {
    }

    /** 从眼睛沿 yaw/pitch 射 dist，返回 BlockHitResult（Rise RayCastUtil.c） */
    public static BlockHitResult rayCast(float yaw, float pitch, double dist) {
        Entity entity = mc.player;
        if (entity == null || mc.level == null) return null;
        Vec3 from = entity.getEyePosition(1.0f);
        Vec3 look = entity.getViewVector(1.0f);
        // 用自定义 yaw/pitch 构造视线（Rise: getVectorForRotation）
        double radYaw = Math.toRadians(yaw);
        double radPitch = Math.toRadians(pitch);
        Vec3 customLook = new Vec3(
                -Math.cos(radPitch) * Math.sin(radYaw),
                -Math.sin(radPitch),
                Math.cos(radPitch) * Math.cos(radYaw));
        Vec3 to = from.add(customLook.scale(dist));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** mc.hitResult 是否命中指定方块（Rise: overBlock） */
    public static boolean overBlock(Direction facing, BlockPos pos, boolean strict) {
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return false;
        BlockHitResult hit = (BlockHitResult) mc.hitResult;
        if (!pos.equals(hit.getBlockPos())) return false;
        if (strict && hit.getDirection() != facing) return false;
        return true;
    }
}
