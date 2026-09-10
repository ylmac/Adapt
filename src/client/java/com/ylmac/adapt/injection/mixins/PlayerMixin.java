package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.combat.Fences;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fences safeWalk 兜底（等价 Rise 置 thePlayer.crd = true 的 SafeWalkEvent 处理）：
 * 26.2 的 vanilla safeWalk 机制在 {@code Player.isStayingOnGroundSurface()}（= isShiftKeyDown），
 * 由 Entity.move 的 maybeBackOffFromEdge 消费（已 javap 确证：
 * maybeBackOffFromEdge 内判断 isStayingOnGroundSurface() 决定是否回退位移）。
 * <p>
 * Fences 检测到玩家站在边缘时令其返回 true：即使输入反向来不及，
 * 位移阶段也会被 maybeBackOffFromEdge 拦回，双重防掉落。
 */
@Mixin(Player.class)
public abstract class PlayerMixin {

    @Inject(method = "isStayingOnGroundSurface", at = @At("RETURN"), cancellable = true)
    private void adapt_onIsStayingOnGroundSurface(CallbackInfoReturnable<Boolean> cir) {
        Fences fences = Fences.get();
        if (fences != null && fences.isOnEdge()) {
            cir.setReturnValue(true);
        }
    }
}
