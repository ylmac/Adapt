package com.ylmac.adapt.features.module.impl.ghost;

import com.ylmac.adapt.features.autoclick.KeyBindUtil;
import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.AttackEntityEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;

/**
 * WTap 攻击后松开W增击退（移植自 Rise WTap / LegitWTap）。
 * <p>
 * 原理：攻击命中后短暂松开前进键（W），客户端 sprint 被中断，
 * 被击者受击退时少了"疾跑抵消" → 击退增大（1.8.9 经典 W-tap）。
 * <p>
 * 26.2 前进键为 Options.keyUp（keyJump 才是空格，javap 确证）；
 * KeyMapping.set(key, false) 松开 → 下一 tick LocalPlayer.aiStep 里
 * canStartSprinting() 不满足 → sprint 复位，同 tick 服务端收到
 * 非疾跑移动 → 击退加大。release-ticks 后恢复 keyUp 按下状态
 * （KeyMapping.set + updateKeyState 从物理状态恢复，同 AutoClicker 方案）。
 * <p>
 * 参数（对齐 Rise）：
 * - chance：触发概率（0-100，Rise WTap Chance）
 * - release-ticks：松开 W 的 tick 数（默认 2，等价 Rise PreMotion 恢复时机）
 * - delay-ticks：攻击后的最小间隔（避免连续攻击重复触发）
 */
public class WTap extends Module {

    /** 触发概率（Rise chance，0-100） */
    private IntSetting chance;
    /** 松开 W 的 tick 数（Rise 在下一个 PreMotion 恢复 = 1 tick，默认 2 更稳） */
    private IntSetting releaseTicks;
    /** 攻击后恢复 W 的最小间隔 tick */
    private IntSetting delayTicks;

    /** 当前是否处于松开 W 状态 */
    private boolean releasing;
    /** 松开剩余 tick 计数 */
    private int releaseTimer;
    /** 距上次触发的 tick 计数 */
    private int lastTapTicks;

    public WTap() {
        super("wtap", "WTap", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        chance = intSetting("chance", 100, 0, 100, 1);
        releaseTicks = intSetting("release-ticks", 2, 1, 10, 1);
        delayTicks = intSetting("delay-ticks", 0, 0, 20, 1);
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (event.getPlayer() != mc.player) return;
        // 概率判定（Rise: Math.random()*100 < chance）
        if (Math.random() * 100.0 >= chance.getValue()) return;
        // 冷却判定：距上次触发过近则跳过
        if (lastTapTicks < delayTicks.getValue()) return;
        // 仅当玩家正在疾跑/按住前进键时 W-tap 才有意义
        if (!mc.player.isSprinting() && !mc.options.keyUp.isDown()) return;
        // 正在松开中则不重复触发
        if (this.releasing) return;

        // 松开前进键（W）：KeyMapping.set(false) 复位按键状态
        KeyBindUtil.setKeyBindState(mc.options.keyUp, false);
        this.releasing = true;
        this.releaseTimer = Math.max(1, releaseTicks.getValue());
        this.lastTapTicks = 0;
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (this.lastTapTicks < 1000) this.lastTapTicks++;
        if (!this.releasing) return;
        if (--this.releaseTimer > 0) return;

        // 恢复前进键：KeyMapping.set(true) + updateKeyState 从物理状态恢复
        this.releasing = false;
        KeyBindUtil.setKeyBindState(mc.options.keyUp, true);
        KeyBindUtil.updateKeyState(mc.options.keyUp);
    }

    @Override
    protected void onDisable() {
        // 关闭时若 W 被松开，立即恢复
        if (this.releasing && mc.player != null) {
            this.releasing = false;
            this.releaseTimer = 0;
            KeyBindUtil.setKeyBindState(mc.options.keyUp, true);
            KeyBindUtil.updateKeyState(mc.options.keyUp);
        }
    }

    @Override
    public String getInfo() {
        return chance.getValue() + "%";
    }
}
