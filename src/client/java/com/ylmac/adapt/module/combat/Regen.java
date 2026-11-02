package com.ylmac.adapt.module.combat;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;

/**
 * Regen 加速回血。
 * <p>
 * 靠 1.8.9 服务器"每个位置包都跑一次玩家 tick"的机制骗多次自然回血判定。
 * <p>
 * 26.2 已 javap 确证此机制已不存在：服务器玩家 tick 只由服务器主循环驱动
 * （handleMovePlayer 只做移动校验/更新位置，绝不触发 tick；tickRegeneration
 * 在 Player.aiStep 中调用，而 aiStep 仅由服务器 tick 驱动）。客户端发再多
 * 移动包也无法加速服务端回血。因此按 26.2 合理实现：
 * <p>
 * 1. 集成服务器（单人/LAN）：ServerPlayerRegenMixin 注入 ServerPlayer
 * .tickRegeneration HEAD — 在本进程内的服务端实体上直接 heal，真实加速
 * 回血（服务端每 tick 一次，血量同步包随之发给客户端，效果真实）。
 * 2. 远程服务器：服务端不受控，走客户端本地模拟（PlayerTickEvent.Post 直接
 * heal 本地血量），视觉上立即回血（等价原版"发包骗判定"的本地观感）。
 * <p>
 * 两路共用同一阈值/速度设置；本地路径仅当非集成服务器时启用（避免双份回血）。
 */
public class Regen extends Module {

    /** 单例引用（ServerPlayerRegenMixin 经此调用 onServerTickRegen） */
    private static Regen instance;

    public static Regen get() {
        return instance;
    }

 /** 触发回血的血量阈值 */
    private final IntSetting minHealth;
 /** 回血速度：每秒恢复的血量点数 */
    private final DoubleSetting speed;

    public Regen() {
        super("regen", "Regen", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        minHealth = intSetting("最小血量", 15, 1, 20, 1);
        speed = doubleSetting("回血速度", 2.0, 0.5, 10.0, 0.5);
    }

    /** 模块开启且本地玩家血量低于阈值（mixin 与本地模拟共用条件） */
    public boolean shouldRegen() {
        return isEnabled() && mc.player != null
                && mc.player.getHealth() < minHealth.getValue();
    }

    /** 每秒回血点数 */
    public float getSpeed() {
        return speed.getValue().floatValue();
    }

    /**
 * 集成服务器路径：ServerPlayerRegenMixin 在服务端线程每 tick 调用一次。
 * 直接对服务端玩家实体 heal — 真实回血，经血量同步包反映到客户端。
 * 每 tick 回 speed/20 点（speed 为每秒点数，20 tick/s）。
 */
    public void onServerTickRegen(net.minecraft.server.level.ServerPlayer serverPlayer) {
        if (isEnabled() && serverPlayer != null
                && serverPlayer.getHealth() < minHealth.getValue()) {
            serverPlayer.heal(getSpeed() / 20.0F);
        }
    }

    /** 远程服务器路径：客户端本地模拟回血（每 tick speed/20 点，与集成路径同速率） */
    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (shouldRegen() && !isIntegratedServer()) {
            mc.player.heal(getSpeed() / 20.0F);
        }
    }

    /** 是否为集成服务器（单人/LAN）：集成时服务端路径已生效，本地不再重复回血 */
    private boolean isIntegratedServer() {
        return mc.getCurrentServer() == null && mc.hasSingleplayerServer();
    }

    @Override
    public String getInfo() {
        return "HP<" + minHealth.getValue() + " " + speed.getValue() + "/s";
    }
}
