package com.ylmac.adapt.mixins.combat;

import com.ylmac.adapt.module.combat.Piercing;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Piercing 穿墙打击：
 * 注入 Minecraft.startAttack HEAD — 原始准星不是实体（空挥/打墙）时，
 * 眼睛位置沿视线方向，只检测实体包围盒，取最近命中者），命中则替换
 * mc.hitResult / mc.crosshairPickEntity 为实体命中，原版攻击逻辑随后
 * 走 ENTITY 分支真实攻击墙后目标。
 */
@Mixin(Minecraft.class)
public class MinecraftPiercingMixin {

 /** 目标过滤 */
    private static final Predicate<Entity> TARGET_FILTER = entity -> {
        if (entity.isSpectator() || !entity.isPickable()) return false;
        if (entity instanceof LivingEntity living && living.isDeadOrDying()) return false;
        return entity != Minecraft.getInstance().player;
    };

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void adapt_onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        Piercing piercing = Piercing.get();
        Minecraft mc = Minecraft.getInstance();
        if (piercing == null || !piercing.isEnabled()) return;
        if (mc.player == null || mc.level == null) return;
        // 原始准星已经是实体（或游戏模式不允许攻击）时无需改写
        HitResult original = mc.hitResult;
        if (original != null && original.getType() == HitResult.Type.ENTITY) return;

        Entity target = findTarget(mc);
        if (target == null) return;

        // 替换命中结果：startAttack 的 ENTITY 分支用 getEntity 攻击
        Vec3 hitPoint = target.getBoundingBox().clip(mc.player.getEyePosition(),
                mc.player.getEyePosition().add(mc.player.getViewVector(1.0F)
                        .scale(piercing.getRange()))).orElse(target.getEyePosition());
        mc.hitResult = new EntityHitResult(target, hitPoint);
        mc.crosshairPickEntity = target;
    }

 /** 无视方块射线：遍历视线锥内实体，取包围盒沿视线最近者 */
    private Entity findTarget(Minecraft mc) {
        Player player = mc.player;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        double range = Piercing.get().getRange();

        // 包围盒候选：视线方向覆盖范围（起点 + 方向*距离，扩展 1 格 + 实体 pick 半径）
        AABB searchBox = new AABB(
                        eye.x + Math.min(0, look.x * range) - 1, eye.y + Math.min(0, look.y * range) - 1, eye.z + Math.min(0, look.z * range) - 1,
                        eye.x + Math.max(0, look.x * range) + 1, eye.y + Math.max(0, look.y * range) + 1, eye.z + Math.max(0, look.z * range) + 1)
                .inflate(2.0);

        List<Entity> candidates = mc.level.getEntities(player, searchBox, TARGET_FILTER);

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        Vec3 end = eye.add(look.scale(range));

        for (Entity entity : candidates) {
 // 实体包围盒按 pick 半径外扩
            AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
            // 视线与包围盒求交（AABB.clip 无视方块）
            Vec3 hit = box.clip(eye, end).orElse(null);
            if (hit == null) continue;
            double dist = eye.distanceToSqr(hit);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = entity;
            }
        }
        return nearest;
    }
}
