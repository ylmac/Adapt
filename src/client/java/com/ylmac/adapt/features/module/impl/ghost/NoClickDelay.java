package com.ylmac.adapt.features.module.impl.ghost;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.injection.mixins.MinecraftAccessor;

/**
 * NoClickDelay 移除未命中点击延迟（移植自 Rise 6.9.5 NoClickDelay）。
 * <p>
 * Rise 原版：每 tick 把 Minecraft.leftClickCounter 置 0 —— 1.8.9 里空挥（未命中）
 * 会设置 leftClickCounter = 10，期间点击被吞；置 0 后连点不再被间隔阻挡。
 * <p>
 * 26.2 对应物（已 javap 确证）：
 * - Minecraft.missTime（protected）：startAttack() 开头 if (missTime > 0) return false
 *   直接吞掉攻击；空挥（hitResult 为 MISS）时若 gameMode.hasMissTime() 则置 10，
 *   tick() 里每 tick 递减。语义与 1.8.9 leftClickCounter 完全一致。
 * - LocalPlayer.resetAttackStrengthTicker()：把 attackStrengthTicker / itemSwapTicker
 *   归零（public），重置 1.8 时代的攻击强度条冷却（getAttackStrengthScale 依赖它）。
 * <p>
 * 实现：PlayerTickEvent.Post 里把 missTime 清 0 并重置攻击强度 ticker。
 * 访问走 MinecraftAccessor mixin（missTime 是 protected，模块包不可直读）。
 * <p>
 * 注意：missTime 每 tick 递减，本模块每 tick 清零恰好抵消 —— 空挥后再点
 * 击不再需要等 10 tick（500ms），实现"移除未命中点击延迟"。
 */
public class NoClickDelay extends Module {

    public NoClickDelay() {
        super("noclickdelay", "NoClickDelay", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (nullCheck()) return;
        if (!this.isEnabled()) return;

        // 移除未命中攻击间隔：missTime 恒为 0 → startAttack 永不被间隔吞点击
        if (mc instanceof MinecraftAccessor accessor) {
            accessor.adapt_setMissTime(0);
        }
        // 重置攻击强度冷却（1.8 攻击强度条，Rise NoClickDelay 同时清 leftClickCounter
        // 与 attackStrengthTicker；26.2 用 public 重置方法）
        mc.player.resetAttackStrengthTicker();
    }
}
