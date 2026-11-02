package com.ylmac.adapt.mixins.combat;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ylmac.adapt.module.combat.LegitReach;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.AttackRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * LegitReach 的 26.2 攻击/交互距离 mixin（API 已 javap 反汇编确证，与
 * AttackRangeMixin / PlayerReachMixin（ghost.Reach）同一套攻击判定链）：
 * <p>
 * 1. {@link AttackRange#effectiveMaxRange(Entity)}：瞄准射线长度唯一来源
 * （LocalPlayer.raycastHitResult → AttackRange.getClosesetHit）。放大后
 * 准星能"够到"更远的实体，mc.hitResult 会变成远距离 EntityHitResult。
 * 2. {@link AttackRange#isInRange(LivingEntity, Vec3)}：Minecraft.startAttack
 * 实体分支的最后一道攻击距离闸门，其私有实现内部调用 effectiveMaxRange，
 * 因此 effectiveMaxRange 的放大已同时覆盖射线与闸门（勿再改 receiver，会双重放大）。
 * 3. {@link Player#entityInteractionRange}：右键交互实体 / 开船 / 牵绳等
 * 的判定距离（startUseItem → isWithinEntityInteractionRange）。
 * 4. {@link Player#blockInteractionRange}：挖方块 / 放方块 / 与方块交互距离。
 * <p>
 * 只有 LegitReach 模块启用时才生效（{@link LegitReach#isActive}），否则
 * 完全返回原值 — 与 ghost.Reach 互不冲突（各管各的模块）。
 */
@Mixin(AttackRange.class)
public abstract class LegitReachMixin {

    /** 瞄准射线长度加成（raycastHitResult → getClosesetHit → effectiveMaxRange） */
    @ModifyReturnValue(method = "effectiveMaxRange", at = @At("RETURN"))
    private float adapt_legitreach_effectiveMaxRange(float original, Entity entity) {
        LegitReach reach = LegitReach.get();
        if (reach != null && reach.isActive()) {
            return original + (float) reach.getAttackRange();
        }
        return original;
    }

    /** 实体交互距离（右键交互实体判定，Player.entityInteractionRange） */
    @Mixin(Player.class)
    public abstract static class PlayerInteractionReachMixin {
        @ModifyReturnValue(method = "entityInteractionRange", at = @At("RETURN"))
        private double adapt_legitreach_entityInteractionRange(double original) {
            LegitReach reach = LegitReach.get();
            if (reach != null && reach.isActive()) {
                return original + reach.getInteractionRange();
            }
            return original;
        }
    }

    /** 方块交互/挖掘距离（Player.blockInteractionRange） */
    @Mixin(Player.class)
    public abstract static class PlayerBlockReachMixin {
        @ModifyReturnValue(method = "blockInteractionRange", at = @At("RETURN"))
        private double adapt_legitreach_blockInteractionRange(double original) {
            LegitReach reach = LegitReach.get();
            if (reach != null && reach.isActive()) {
                return original + reach.getBlockRange();
            }
            return original;
        }
    }
}
