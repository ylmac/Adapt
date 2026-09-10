package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import net.minecraft.world.phys.Vec2;

/**
 * 跳远模块（移植自 Rise 6.9.5 VanillaLongJump，适配 Fabric 26.2）。
 * <p>
 * 原理（已 javap 验证 26.2 API）：
 * - 蓄力：按住空格（jump 键）蓄力，松开瞬间起跳（检测 jump 键按下边沿）；
 * - 起跳：jumpFromGround() 触发原版跳跃，同时把水平速度设为「速度」设置
 *   （m/tick），方向 = 当前输入方向按 yaw 旋转（与原版 moveRelative 一致）；
 * - 空中保持水平速度直到落地（B-Hop 式，落地自动停止加速），
 *   可选落地后自动禁用模块（Rise 的 autoDisable，默认开）；
 * - 垂直：起跳瞬间把 delta.y 设为「高度」设置（0.42 ≈ 原版起跳速度），
 *   之后交给重力自然回落。
 * - 方向：起跳/空中加速的方向 = 输入方向按 yaw 旋转（与原版 getInputVector
 *   一致：moveVector 有符号值 x=左+1/右-1、y=前+1/后-1，26.2 字节码已验证）。
 */
public class LongJump extends Module {

    /** 跳跃高度（起跳瞬间 y 速度，0.42 = 原版跳跃初速） */
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting height;
    /** 水平速度（m/tick，对应 Rise VanillaLongJump 的 speed 设置，默认 1） */
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting speed;
    /** 落地后自动禁用（Rise 的 autoDisable，默认开） */
    private com.ylmac.adapt.features.module.settings.impl.BoolSetting autoDisable;

    /** 是否正在蓄力（按住空格） */
    private boolean charging = false;
    /** 是否已在空中（已起跳，等待落地） */
    private boolean inAir = false;
    /** 上一 tick 的跳跃按键状态（边沿检测用） */
    private boolean prevJump = false;

    public LongJump() {
        super("longjump", "LongJump", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        height = doubleSetting("高度", 0.42, 0.1, 1.0, 0.01);
        speed = doubleSetting("速度", 1.0, 0.1, 9.5, 0.1);
        autoDisable = boolSetting("自动禁用", true);
    }

    @Override
    protected void onDisable() {
        charging = false;
        inAir = false;
        prevJump = false;
        // 速度清零（等价 Rise MoveUtil.stop：关闭模块时把水平速度归零，
        // 避免残留的跳跃冲量让玩家继续滑行）
        if (mc.player != null) {
            net.minecraft.world.phys.Vec3 delta = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0.0, delta.y, 0.0);
        }
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck() || !isEnabled()) return;

        // 输入方向（moveVector = Vec2(strafe, forward)，已归一化；
        // 26.2 字节码已验证：x=+1 左/-1 右，y=+1 前/-1 后，有符号值直接可用）
        Vec2 move = mc.player.input.getMoveVector();
        float forward = move.y;
        float strafe = move.x;
        boolean moving = forward != 0.0F || strafe != 0.0F;

        boolean jumpDown = mc.player.input.keyPresses.jump();

        // 蓄力：按住空格（未起跳时）
        if (jumpDown && !inAir && mc.player.onGround()) {
            charging = true;
        }

        // 起跳：空格按下边沿（按住 → 松开再按，或首次按下）且在地面
        boolean jumpPressed = jumpDown && !prevJump;
        if (jumpPressed && mc.player.onGround() && charging) {
            // 触发原版跳跃
            mc.player.jumpFromGround();
            // 起跳瞬间设垂直初速（高度设置，0.42 ≈ 原版）
            net.minecraft.world.phys.Vec3 delta = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(delta.x, height.getValue(), delta.z);
            // 施加水平速度（方向 = 输入方向按 yaw 旋转）
            if (moving) {
                double yaw = Math.toRadians(mc.player.getYRot());
                double angle = Math.atan2(strafe, forward) - yaw;
                mc.player.setDeltaMovement(
                        Math.sin(angle) * speed.getValue(),
                        height.getValue(),
                        Math.cos(angle) * speed.getValue()
                );
            }
            charging = false;
            inAir = true;
        } else if (jumpPressed && !mc.player.onGround()) {
            // 空中再按空格：重新蓄力（准备下一跳）
            charging = true;
        }

        // 空中保持水平速度（B-Hop 式，水平速度每 tick 重设为 speed）
        if (inAir && !mc.player.onGround()) {
            if (moving) {
                double yaw = Math.toRadians(mc.player.getYRot());
                double angle = Math.atan2(strafe, forward) - yaw;
                net.minecraft.world.phys.Vec3 delta = mc.player.getDeltaMovement();
                mc.player.setDeltaMovement(
                        Math.sin(angle) * speed.getValue(),
                        delta.y,
                        Math.cos(angle) * speed.getValue()
                );
            }
        }

        // 落地：空中阶段结束；若开自动禁用则关模块
        if (inAir && mc.player.onGround()) {
            inAir = false;
            charging = false;
            if (autoDisable.getValue()) {
                setEnabled(false);
                return;
            }
        }

        prevJump = jumpDown;
    }

}
