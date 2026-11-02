package com.ylmac.adapt.mixins.combat;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ylmac.adapt.module.ghost.HitBox;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * HitBox 扩大实体判定框 mixin（26.2 拾取半径链路，已 javap 反汇编确证）：
 * <p>
 * Entity.getPickRadius 是攻击/瞄准/交互时包围盒的膨胀系数：
 * - ProjectileUtil.getEntityHitResult 里每个候选实体的
 * boundingBox.inflate(entity.getPickRadius) 参与射线相交判定；
 * - RotationUtil/AutoClicker 的 isValidTarget 也用它膨胀包围盒。
 * 用 @ModifyReturnValue 加一个 expand 量，等于把准星判定框整体放大一圈，
 * <p>
 * 注意：这只影响客户端瞄准/攻击判定（mc.hitResult 命中更远/更偏的实体），
 */
@Mixin(Entity.class)
public abstract class EntityMarginMixin {

    @ModifyReturnValue(method = "getPickRadius", at = @At("RETURN"))
    private float adapt_hitbox_pickRadius(float original) {
        HitBox hitBox = HitBox.get();
        if (hitBox != null && hitBox.isActive()) {
            return original + hitBox.getExpand();
        }
        return original;
    }
}
