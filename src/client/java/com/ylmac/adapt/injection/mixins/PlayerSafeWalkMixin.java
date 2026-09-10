package com.ylmac.adapt.injection.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ylmac.adapt.features.module.impl.ghost.SafeWalk;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SafeWalk 边缘防掉落 mixin（26.2 移动链路，已 javap 反汇编确证）：
 * <p>
 * Player.travel 的边缘防掉落判定 = isStayingOnGroundSurface()（原版实现只是
 * isShiftKeyDown()）+ isAboveGround(0.05)。用 @ModifyReturnValue 把返回值
 * OR 上 SafeWalk 模块的状态 —— 模块激活时，走到方块边缘不会掉落，等价原版
 * 潜行，且不修改移动速度（潜行的减速来自 modifyInput，本模块不碰）。
 * <p>
 * 与原版 Rise 的 PreUpdateEvent 直接置 player 字段不同：26.2 的"边缘防掉落"
 * 已是 Player 方法，改返回值即可，天然兼容客户端预测。
 */
@Mixin(Player.class)
public abstract class PlayerSafeWalkMixin {

    @ModifyReturnValue(method = "isStayingOnGroundSurface", at = @At("RETURN"))
    private boolean adapt_safewalk(boolean original) {
        SafeWalk safeWalk = SafeWalk.get();
        if (safeWalk != null && safeWalk.shouldSafeWalk()) {
            return true;
        }
        return original;
    }
}
