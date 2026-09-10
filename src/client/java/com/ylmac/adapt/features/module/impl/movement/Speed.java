package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * 速度模块（移植自 Rise 6.9.5 VanillaSpeed，适配 Fabric 26.2）。
 * <p>
 * 原理（已 javap 验证 26.2 API）：
 * - 每 tick 把玩家水平速度直接设为「速度」设置（m/tick），方向 = 当前输入
 *   方向（按 yaw 旋转的输入向量，换算与原版 Entity.moveRelative 一致）；
 * - 落地且正在移动时自动起跳（jumpFromGround，对应 Rise 的 onGround+jump），
 *   实现"跳着跑"的加速效果；
 * - 空中也保持水平速度（B-Hop 式），垂直分量（重力/跳高）不干预。
 */
public class Speed extends Module {

    /** 水平速度（m/tick，对应 Rise VanillaSpeed 的 speed 设置，默认 1） */
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting speed;
    /** 落地自动起跳（Rise 原版行为，默认开） */
    private com.ylmac.adapt.features.module.settings.impl.BoolSetting autoJump;

    public Speed() {
        super("speed", "Speed", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        speed = doubleSetting("速度", 1.0, 0.1, 9.5, 0.1);
        autoJump = boolSetting("自动跳跃", true);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck() || !isEnabled()) return;

        // 输入方向：ClientInput.getMoveVector = Vec2(左移冲量, 前进冲量)，已归一化。
        // 26.2 的 moveVector 语义（javap KeyboardInput.calculateImpulse 确证）：
        //   x = 按左(A) +1 / 按右(D) -1；y = 按前(W) +1 / 按后(S) -1。
        // 已含方向符号，勿再按 keyPresses 取反（否则 D 变左、S 变前）。
        Vec2 move = mc.player.input.getMoveVector();
        float forward = move.y;
        float strafe = move.x;

        boolean moving = forward != 0.0F || strafe != 0.0F;
        if (!moving) return;

        // 落地 + 正在移动 → 自动起跳（Rise VanillaSpeed：onGround 时 jump）
        if (autoJump.getValue() && mc.player.onGround()) {
            mc.player.jumpFromGround();
        }

        // 水平速度 = speed 设置，方向 = 输入方向按 yaw 旋转：
        // 角度 = atan2(strafe, forward) - yaw，sin/cos 映射到世界 x/z
        double yaw = Math.toRadians(mc.player.getYRot());
        double inputAngle = Math.atan2(strafe, forward);
        double angle = inputAngle - yaw;
        double vx = Math.sin(angle) * speed.getValue();
        double vz = Math.cos(angle) * speed.getValue();

        // 应用水平速度（保留原垂直速度：重力/跳高不受影响）
        Vec3 delta = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(vx, delta.y, vz);
    }

}
