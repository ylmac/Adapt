package com.ylmac.adapt.mixins.combat;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ylmac.adapt.module.ghost.Reach;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reach 攻击距离增强的 Player 侧 mixin（26.2 攻击距离链路，已 javap 反汇编确证）：
 * <p>
 * 1. {@link Player#entityInteractionRange} — 右键交互实体 / 实体拾取判定
 * （Minecraft.startUseItem → LocalPlayer.isWithinEntityInteractionRange(entity, 0)）。
 * 用 @ModifyReturnValue 直接加距离加成。
 * 2. {@link Player#blockInteractionRange} — 方块交互 / 挖掘判定。
 * <p>
 * 攻击（startAttack）与瞄准射线（raycastHitResult）的放大见 AttackRangeMixin。
 */
@Mixin(Player.class)
public abstract class PlayerReachMixin {

 /** 实体交互距离加成 */
    @ModifyReturnValue(method = "entityInteractionRange", at = @At("RETURN"))
    private double adapt_reach_entityInteractionRange(double original) {
        Reach reach = Reach.get();
        if (reach != null && reach.isActive()) {
            return original + reach.getEntityRange();
        }
        return original;
    }

 /** 方块交互/挖掘距离加成 */
    @ModifyReturnValue(method = "blockInteractionRange", at = @At("RETURN"))
    private double adapt_reach_blockInteractionRange(double original) {
        Reach reach = Reach.get();
        if (reach != null && reach.isActive()) {
            return original + reach.getBlockRange();
        }
        return original;
    }
}
