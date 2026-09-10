package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;

/**
 * NoClip 无碰撞飞行（移植自 Rise 6.9.5 NoClip，适配 Fabric 26.2）。
 * <p>
 * 原理（26.2 客户端 API，已 javap 确证）：
 * - {@code Entity.noPhysics} 公开字段 = 原版无碰撞标志（1.8.9 的 noClip），
 *   置 true 后实体碰撞箱不再与方块碰撞，可自由穿过方块。
 * - 启用期间把 noPhysics 恒置 true，同时按前进方向（相对视角 yaw 的 WASD）
 *   直接驱动位移（等价 Rise 的 BlockAABB 置空 + PreMotion 飞行的组合效果）。
 * - 按住 Shift / 空格垂直上下，无垂直输入时把垂直速度归零（防残留动能）。
 * <p>
 * 说明：Rise 原版用 BlockAABB 事件把方块碰撞箱改成巨大箱子来实现"穿过"，
 * 26.2 无对等事件；noPhysics 是 Minecraft 原生穿墙标志（LB 26.2 ModuleNoClip
 * 同样采用），效果一致且更干净。
 */
public class NoClip extends Module {

    /** 飞行速度设置（格/tick） */
    private final DoubleSetting speed;

    public NoClip() {
        super("noclip", "NoClip", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        speed = doubleSetting("speed", 0.32, 0.1, 0.5, 0.01);
    }

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck() || mc.player.isDeadOrDying()) {
            return;
        }
        // 核心：置无碰撞标志（26.2 原生穿墙）
        mc.player.noPhysics = true;
        mc.player.fallDistance = 0.0F;
        mc.player.setOnGround(false);

        // 垂直控制：空格上、Shift 下，无输入则垂直速度归零（防残留）
        double motionY = 0.0D;
        if (mc.options.keyJump.isDown()) {
            motionY = speed.getValue();
        } else if (mc.options.keyShift.isDown()) {
            motionY = -speed.getValue();
        }
        mc.player.setDeltaMovement(
                mc.player.getDeltaMovement().x,
                motionY,
                mc.player.getDeltaMovement().z
        );

        // 水平控制：以视角 yaw 为基准的 WASD 方向推进
        double yaw = Math.toRadians(mc.player.getYRot());
        double forward = 0.0D;
        double strafe = 0.0D;
        if (mc.options.keyUp.isDown()) {
            forward += 1.0D;
        }
        if (mc.options.keyDown.isDown()) {
            forward -= 1.0D;
        }
        if (mc.options.keyLeft.isDown()) {
            strafe += 1.0D;
        }
        if (mc.options.keyRight.isDown()) {
            strafe -= 1.0D;
        }
        if (forward != 0.0D || strafe != 0.0D) {
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            double moveX = (strafe * cos - forward * sin) * speed.getValue();
            double moveZ = (forward * cos + strafe * sin) * speed.getValue();
            mc.player.setPos(
                    mc.player.getX() + moveX,
                    mc.player.getY() + motionY,
                    mc.player.getZ() + moveZ
            );
        }
    }

    @Override
    protected void onDisable() {
        if (mc.player != null) {
            // 还原无碰撞标志，避免遗留穿墙状态
            mc.player.noPhysics = false;
        }
    }
}
