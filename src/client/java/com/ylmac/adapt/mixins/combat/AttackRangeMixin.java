package com.ylmac.adapt.mixins.combat;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ylmac.adapt.module.ghost.HitBox;
import com.ylmac.adapt.module.ghost.Reach;
import net.minecraft.world.item.component.AttackRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reach / HitBox 的 AttackRange 侧 mixin（26.2 攻击判定链，已 javap 反汇编确证）：
 * <p>
 * - {@link AttackRange#effectiveMaxRange(Entity)}：瞄准射线长度唯一来源。
 * LocalPlayer.raycastHitResult → AttackRange.getClosesetHit →
 * ProjectileUtil.getHitEntitiesAlong 里用 effectiveMinRange/effectiveMaxRange
 * 决定射线起止，放大后准星能"够到"更远的实体（mc.hitResult 正确）。
 * - {@link AttackRange#isInRange(LivingEntity, Vec3)}：Minecraft.startAttack
 * 实体分支的攻击前最后一道距离闸门，其私有实现内部调用 effectiveMaxRange，
 * 因此 effectiveMaxRange 的放大已同时覆盖射线与闸门（勿再改 receiver，会双重放大）。
 * - HitBox 侧：isInRange 的私有实现用 effectiveMaxRange + hitboxMargin 判定，
 * 放大 hitboxMargin 让扩大后的判定框通过距离校验（MixinAttackRange 同款）。
 */
@Mixin(AttackRange.class)
public abstract class AttackRangeMixin {

    /** 瞄准射线长度加成（raycastHitResult → getClosesetHit → effectiveMaxRange） */
    @ModifyReturnValue(method = "effectiveMaxRange", at = @At("RETURN"))
    private float adapt_reach_effectiveMaxRange(float original, net.minecraft.world.entity.Entity entity) {
        Reach reach = Reach.get();
        if (reach != null && reach.isActive() && reach.shouldAffectAttackRange()) {
            return original + (float) reach.getEntityRange();
        }
        return original;
    }

    /** hitboxMargin 加成（HitBox：让扩大后的判定框通过 isInRange 距离校验） */
    @ModifyReturnValue(method = "hitboxMargin", at = @At("RETURN"))
    private float adapt_hitbox_margin(float original) {
        HitBox hitBox = HitBox.get();
        if (hitBox != null && hitBox.isActive() && hitBox.shouldEffectRange()) {
            return original + hitBox.getExpand();
        }
        return original;
    }
}
