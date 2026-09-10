package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import net.minecraft.client.player.LocalPlayer;

/**
 * WallClimb 爬墙（蜘蛛）——移植自 Rise 6.9.5 WallClimb，适配 Fabric 26.2。
 * <p>
 * Rise 原版是 ModeValue 多模式（Vulcan / Verus / Grim / Watchdog / MineMenClub），
 * 本移植合并为「模式」设置，实现其中三个有效算法：
 * - VULCAN：水平撞墙时每 2 tick 起跳（jumpFromGround），并沿墙面向下推回
 *   0.1 格（等价原版 PreMotion 设 onGround + motionY=jumpMotion + 位置微调）
 * - VERUS：水平撞墙时每 2 tick 直接起跳（原版 jump()）
 * - MINEMENCLUB：水平撞墙时每 3 tick 起跳，撞头（垂直碰撞且非地面）时停跳
 * <p>
 * 26.2 API 说明：
 * - 撞墙判定 = player.horizontalCollision（public 字段，等价 1.8 isCollidedHorizontally）
 * - 起跳 = player.jumpFromGround()（public，LivingEntity）
 * - 撞头判定 = player.verticalCollision && !player.onGround()
 * - 模式 = EnumSetting（本模块 self 的模式值直接驱动分支）
 */
public class WallClimb extends Module {

    /** 爬墙模式（对应 Rise 的 Vulcan/Verus/MineMenClub 三个有效实现） */
    public enum Mode {
        VULCAN,
        VERUS,
        MINEMENCLUB
    }

    private final com.ylmac.adapt.features.module.settings.impl.EnumSetting<Mode> mode =
            enumSetting("模式", Mode.VULCAN);

    public WallClimb() {
        super("wallclimb", "WallClimb", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    @Override
    public String getInfo() {
        return mode.getValue().name();
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        // 贴墙判定：水平碰撞（撞墙）才爬
        if (!player.horizontalCollision) {
            return;
        }
        switch (mode.getValue()) {
            case VULCAN -> vulcanTick(player);
            case VERUS -> verusTick(player);
            case MINEMENCLUB -> mineMenClubTick(player);
        }
    }

    /** Vulcan：每 2 tick 起跳一次（Rise 原版偶数 tick 设 onGround+jumpMotion） */
    private void vulcanTick(LocalPlayer player) {
        if (player.tickCount % 2 == 0) {
            // 等价 MoveUtil.jumpMotion()：先清空垂直速度再起跳，保证完整跳高
            player.setDeltaMovement(player.getDeltaMovement().x, 0.0, player.getDeltaMovement().z);
            player.jumpFromGround();
        }
        // 沿墙面向下微推 0.1 格（等价原版 posX/posZ - sin/cos(dir)*0.1）
        // 26.2 无 PreMotion 位置覆写，改用 deltaMovement 水平分量小幅推向墙面方向
        // 让玩家保持贴墙（否则起跳瞬间水平碰撞消失，会从墙上弹开）
        pushIntoWall(player);
    }

    /** Verus：每 2 tick 直接起跳（原版 jump()） */
    private void verusTick(LocalPlayer player) {
        if (player.tickCount % 2 == 0) {
            player.jumpFromGround();
        }
    }

    /** MineMenClub：每 3 tick 起跳；撞头（垂直碰撞且非地面）时停跳，落地后恢复 */
    private void mineMenClubTick(LocalPlayer player) {
        boolean hitHead = player.verticalCollision && !player.onGround();
        if (!hitHead && player.tickCount % 3 == 0) {
            player.setDeltaMovement(player.getDeltaMovement().x, 0.0, player.getDeltaMovement().z);
            player.jumpFromGround();
        }
    }

    /** 贴墙保持：起跳瞬间水平碰撞会消失，把水平速度微推回墙面方向（Rise 0.1 格位移的 26.2 等价） */
    private void pushIntoWall(LocalPlayer player) {
        double yaw = Math.toRadians(player.getYRot());
        // 面向方向的 sin/cos（yaw 0 = +Z，负 yaw 转向 -X）
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // 水平分量推回墙面 0.1 格（前进方向 = 墙面方向，因玩家正对墙）
        double pushX = -sin * 0.1;
        double pushZ = cos * 0.1;
        var delta = player.getDeltaMovement();
        // 只保留朝向分量（朝墙），忽略玩家手动输入的水平速度，避免被弹开
        player.setDeltaMovement(pushX, delta.y, pushZ);
    }

    /** 锁水平输入（Rise Watchdog 模式：撞墙时取消 strafe，避免侧向移动破坏贴墙） */
    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (mode.getValue() == Mode.VULCAN
                && mc.player != null
                && mc.player.horizontalCollision) {
            event.setStrafe(0.0F);
        }
    }

}
