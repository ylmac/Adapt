package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import net.minecraft.world.entity.player.Abilities;

/**
 * 飞行模块（移植自 Rise 6.9.5 VanillaFlight，适配 Fabric 26.2）。
 * <p>
 * 26.2 实现原理（已 javap 验证 Abilities / LocalPlayer.aiStep /
 * Player.travel / LivingEntity.travelInAir / Player.getFlyingSpeed）：
 * <ul>
 *   <li>置 {@code abilities.mayfly = true; abilities.flying = true} 走原版飞行物理：</li>
 *   <li>水平速度：Player.getFlyingSpeed() 在飞行时返回
 *       {@code abilities.getFlyingSpeed() * (sprint ? 2 : 1)}，
 *       经 travelInAir → getFrictionInfluencedSpeed → moveRelative 加速；
 *       原版 flyingSpeed=0.05 水平约 0.05/0.91 ≈ 0.055 m/tick 太慢，
 *       故 flyingSpeed = 期望水平速度 / 0.91（0.91 为空气阻力系数）；</li>
 *   <li>垂直速度：LocalPlayer.aiStep 在 flying=true 时每 tick
 *       {@code delta.y += (shift ? -1 : jump ? 1 : 0) * flyingSpeed * 3}，
 *       即空格上升、Shift 下降 —— 「垂直速度」作为 flyingSpeed 的乘数放大垂直分量。</li>
 * </ul>
 * 事件走 {@link PlayerTickEvent.Post}（tick 末，aiStep 已跑完），
 * 每 tick 保持飞行状态 + 应用 flyingSpeed，即可获得完整飞行。
 */
public class Flight extends Module {

    /** 飞行水平速度（m/tick，对应 Rise VanillaFlight 的 speed 设置，默认 1） */
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting speed;
    /** 飞行垂直速度乘数（flyingSpeed 倍率，空格上升 / Shift 下降的强度） */
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting verticalSpeed;

    /** 玩家之前的飞行速度（禁用时还原） */
    private float oldFlyingSpeed = 1.0F;
    /** 之前是否可飞行（禁用时还原） */
    private boolean oldMayfly;
    /** 之前是否在飞行（禁用时还原） */
    private boolean oldFlying;

    public Flight() {
        super("flight", "Flight", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        speed = doubleSetting("速度", 1.0, 0.1, 10.0, 0.1);
        verticalSpeed = doubleSetting("垂直速度", 1.0, 0.1, 5.0, 0.1);
    }

    @Override
    protected void onEnable() {
        if (nullCheck()) return;
        Abilities abilities = mc.player.getAbilities();
        // 保存原状态，禁用时还原
        oldFlyingSpeed = abilities.getFlyingSpeed();
        oldMayfly = abilities.mayfly;
        oldFlying = abilities.flying;
        // 开启飞行：允许飞行 + 正在飞行
        abilities.mayfly = true;
        abilities.flying = true;
        applySpeed(abilities);
        mc.player.onUpdateAbilities();
    }

    @Override
    protected void onDisable() {
        if (mc.player == null) return;
        Abilities abilities = mc.player.getAbilities();
        // 还原原状态（不强制关掉创造模式的正常飞行）
        abilities.setFlyingSpeed(oldFlyingSpeed);
        abilities.mayfly = oldMayfly;
        abilities.flying = oldFlying;
        mc.player.onUpdateAbilities();
    }

    /** 换算并应用 flyingSpeed（水平 = speed/0.91，垂直按「垂直速度」倍率放大） */
    private void applySpeed(Abilities abilities) {
        abilities.setFlyingSpeed((float) (speed.getValue() / 0.91 * verticalSpeed.getValue()));
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck() || !isEnabled()) return;
        Abilities abilities = mc.player.getAbilities();
        // 保持飞行状态（防其他逻辑改回），并实时应用速度设置
        abilities.mayfly = true;
        abilities.flying = true;
        applySpeed(abilities);
        mc.player.onUpdateAbilities();
    }

}
