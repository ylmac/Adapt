package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.ghost.KeepSprint;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * KeepSprint 支持：在 Player.causeExtraKnockback（攻击命中后）等价实现
 * Rise 6.9.5 的 HitSlowDownEvent 处理（Rise KeepSprint.onHitSlowDown）。
 * <p>
 * 26.2 字节码（javap 确证）——causeExtraKnockback：
 * <pre>
 *  105: ldc2_w 0.6d      ← 水平减速因子（x 轴）
 *  109: ldc2_w 0.6d      ← 水平减速因子（z 轴）
 *  115: setDeltaMovement
 *  120: setSprinting(Z)  ← 疾跑复位
 * </pre>
 * 等价 Rise HitSlowDownEvent（在减速应用前回调，可改 slowDown/sprint）：
 * - @ModifyConstant 0.6d：按 hurtTime 返回 slowDownVelocity / slowDownNormal
 *   （Rise: hurtTime > 0 → velocity 因子，否则 normal 因子）。
 * - @Inject setSprinting：先跑 Rise onHitSlowDown（Buffer Abuse / Only In Air），
 *   决定是否处理；处理时按 hurtTime 的 sprint 设置强制疾跑（拦截复位）。
 */
@Mixin(Player.class)
public class KeepSprintMixin {

    /** 减速因子替换：causeExtraKnockback 里两处 0.6d（水平 x/z 减速）→ 按 hurtTime 两因子。
     *  @ModifyConstant 回调签名：常量参数在前（double original），随后是被注入方法
     *  causeExtraKnockback 的完整参数（Entity, float, Vec3, DamageSource, float, boolean）。
     *  玩家实例经 (Player)(Object)this 访问。 */
    @ModifyConstant(method = "causeExtraKnockback",
            constant = @Constant(doubleValue = 0.6))
    private double adapt_modifySlowDown(double original, net.minecraft.world.entity.Entity entity,
                                        float damage, net.minecraft.world.phys.Vec3 knockback,
                                        net.minecraft.world.damagesource.DamageSource damageSource,
                                        float f, boolean bl) {
        KeepSprint keepSprint = KeepSprint.get();
        if (keepSprint != null && keepSprint.isEnabled()) {
            Player player = (Player) (Object) this;
            boolean velocity = player.hurtTime > 0;
            return keepSprint.getSlowDownFactor(velocity);
        }
        return original;
    }

    /** 疾跑复位拦截：等价 Rise HitSlowDownEvent 的 setSprint 决策。
     *  @Inject 回调必须携带目标方法 causeExtraKnockback 的完整参数
     *  （Entity, float, Vec3, DamageSource, float, boolean）+ CallbackInfo，
     *  玩家实例经 (Player)(Object)this 访问。 */
    @Inject(method = "causeExtraKnockback",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;setSprinting(Z)V"),
            cancellable = true)
    private void adapt_keepSprint(net.minecraft.world.entity.Entity entity, float damage,
                                  net.minecraft.world.phys.Vec3 knockback,
                                  net.minecraft.world.damagesource.DamageSource damageSource,
                                  float f, boolean bl, CallbackInfo ci) {
        KeepSprint keepSprint = KeepSprint.get();
        if (keepSprint == null || !keepSprint.isEnabled()) return;
        Player player = (Player) (Object) this;
        // Rise onHitSlowDown：Buffer Abuse / Only In Air 决定是否处理
        boolean process = keepSprint.onHitSlowDown(player.onGround(), player.hurtTime);
        if (!process) return;
        // Rise: hurtTime > 0 → sprintSlowDownVelocity，否则 sprintSlowDownNormal
        boolean shouldSprint = player.hurtTime > 0
                ? keepSprint.shouldSprintVelocity()
                : keepSprint.shouldSprintNormal();
        if (shouldSprint) {
            // 强制保持疾跑（Rise setSprint(true)）→ 拦截复位
            ci.cancel();
        }
        // shouldSprint = false → 不拦截，允许正常复位（Rise setSprint(false)）
    }
}
