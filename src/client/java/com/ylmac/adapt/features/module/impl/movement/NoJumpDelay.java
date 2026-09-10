package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import net.minecraft.world.entity.LivingEntity;

/**
 * 移除跳跃延迟（移植自 Rise 6.9.5 NoJumpDelay，适配 Fabric 26.2）。
 * <p>
 * 原理（26.2 跳跃系统，已 javap /Users/ylmac/Documents/26.2 确证）：
 * - 26.2 的跳跃延迟不在 Player.jumpTriggerTime（那是玩家实例字段），而在
 *   {@link LivingEntity#noJumpDelay}（private，aiStep 中递减，>=0 时阻止
 *   jumpFromGround）。LivingEntity.aiStep 落地分支：
 *   {@code if (onGround && this.noJumpDelay == 0) { jumpFromGround(); noJumpDelay = 10; }}
 *   —— 跳起后 10 tick 内无法再次起跳，这就是"跳跃延迟"。
 * - Rise 的写法是 {@code thePlayer.jumpTicks = 0}（1.8.9 字段名），26.2 对应物
 *   是 private 的 noJumpDelay，模块代码无法直接写字段，所以改为【直接起跳】：
 *   每 tick 落地 + 按跳 + noJumpDelay > 0 时手动 jumpFromGround()（起跳后 vanilla
 *   会重置 noJumpDelay = 10，下一 tick 本模块再次起跳）—— 效果等价于
 *   持续按住跳时每 tick 都跳（连跳），彻底移除 10 tick 冷却。
 * - 与 Rise 语义一致：不干预起跳物理（jumpFromGround 走原版跳跃力度）。
 * <p>
 * 实现细节：
 * - 用 PlayerTickEvent.Pre（LocalPlayer.tick HEAD）驱动，时机在 aiStep（含
 *   noJumpDelay 递减与落地起跳）之前 —— 因此本模块先起跳，vanilla 同 tick 内
 *   不会再跳一次（noJumpDelay 已被置 10，且 vanilla 分支检查同一字段）；
 * - 只在玩家按键按跳（keyJump.isDown()）时触发，不抢跳（不开模块不跳）；
 * - 起跳前等待玩家真正落地（onGround），不干扰跳跃中/飞行。
 */
public class NoJumpDelay extends Module {

    public NoJumpDelay() {
        super("nojumpdelay", "NoJumpDelay", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck() || !isEnabled()) return;

        // 仅当玩家自己按着跳跃键时生效（Rise 原版条件：keyBindJump.isKeyDown()）
        if (!mc.options.keyJump.isDown()) return;

        // 落地且跳跃冷却未结束 → 手动再跳（等价 Rise 的 jumpTicks = 0）
        if (mc.player.onGround() && jumpDelay() > 0) {
            mc.player.jumpFromGround();
        }
    }

    /** 当前跳跃冷却 tick（26.2: LivingEntity.noJumpDelay，private 字段） */
    private int jumpDelay() {
        try {
            java.lang.reflect.Field f = LivingEntity.class.getDeclaredField("noJumpDelay");
            f.setAccessible(true);
            return f.getInt(mc.player);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }
}
