package com.ylmac.adapt.module.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 旋转/射线工具。
 * 26.2 无 AxisAlignedBB.calculateIntercept，改用 AABB.clip(from, to)（返回 Optional<Vec3>）。
 */
public final class ClickRotation {
    private static final Minecraft mc = Minecraft.getInstance();

    private ClickRotation() {}

    /**
 * 射线是否与包围盒相交。
 * 用玩家当前视角向量代替 1.8.9 的 getVectorForRotation。
 */
    public static Vec3 rayTrace(AABB boundingBox, float yaw, float pitch, double distance) {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 lookVec = mc.player.getViewVector(1.0f);
        Vec3 targetPos = eyePos.add(lookVec.scale(distance));
        return boundingBox.clip(eyePos, targetPos).orElse(null);
    }

 /** 玩家到实体的距离 */
    public static double distanceToEntity(Entity entity) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 closest = closestPointOnAabb(entity.getBoundingBox().inflate(entity.getPickRadius()), eye);
        return eye.distanceTo(closest);
    }

    private static Vec3 closestPointOnAabb(AABB box, Vec3 point) {
        double x = Math.max(box.minX, Math.min(box.maxX, point.x()));
        double y = Math.max(box.minY, Math.min(box.maxY, point.y()));
        double z = Math.max(box.minZ, Math.min(box.maxZ, point.z()));
        return new Vec3(x, y, z);
    }
}
