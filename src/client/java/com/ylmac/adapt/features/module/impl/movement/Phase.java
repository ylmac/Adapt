package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Phase 穿墙（移植自 Rise 6.9.5 Phase/NormalPhase，适配 Fabric 26.2）。
 * <p>
 * 原理（26.2 客户端 API，已 javap 确证）：
 * - 按住 Shift 时，向玩家当前朝向（yaw）前方逐格扫描，找到"玩家与目标之间
 *   夹着一面实心方块墙"的位置，然后把玩家直接 setPos 推进到墙后的空气处。
 *   （等价 Rise NormalPhase 的 "isCollidedHorizontally → 沿法线反推 0.005" +
 *    "C04 大距离传送" 组合，但用客户端坐标推进 + 位置包同步。）
 * - 被墙卡住（horizontalCollision）时，沿视线方向缓慢推进 0.05 格/tick，
 *   作为短距离"挤入"辅助，让紧贴墙时也能启动。
 * - 每次 setPos 后补发 ServerboundMovePlayerPacket.Pos（与 Teleport/VClip 相同
 *   的写法），确保服务端位置同步，避免纯客户端 setPos 导致服务器回弹。
 * <p>
 * 说明：Rise 原版依赖 BlockAABB 事件 + C04 发包实现；26.2 无对等事件，
 * 改用位置直接修改（setPos）+ 碰撞形状探测 + 位置包，效果等价且更稳定。
 */
public class Phase extends Module {

    /** 一次传送的推进距离（格，每 tick 实时读取设置） */
    private final DoubleSetting range;
    /** 贴墙挤入速度（格/tick，每 tick 实时读取设置） */
    private final DoubleSetting crawlSpeed;

    public Phase() {
        super("phase", "Phase", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        range = doubleSetting("range", 3.5, 1.0, 8.0, 0.1);
        crawlSpeed = doubleSetting("crawl-speed", 0.05, 0.01, 0.2, 0.01);
    }

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck() || mc.player.isDeadOrDying()) {
            return;
        }

        double crawl = crawlSpeed.getValue();

        // 挤入辅助：被墙卡住时沿视线方向缓慢推进（setPos + 位置包同步）
        if (mc.player.horizontalCollision) {
            double yaw = Math.toRadians(mc.player.getYRot());
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            movePlayer(
                    mc.player.getX() - sin * crawl,
                    mc.player.getY(),
                    mc.player.getZ() + cos * crawl
            );
        }

        // 按住 Shift 才触发传送（避免误触）
        if (!mc.player.isShiftKeyDown()) {
            return;
        }

        // 向视线前方扫描，找"墙后有空位"的传送点
        double yaw = Math.toRadians(mc.player.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        int steps = (int) Math.ceil(range.getValue());
        boolean wallBetween = false;

        for (int i = 1; i <= steps; i++) {
            double targetX = mc.player.getX() - sin * i;
            double targetZ = mc.player.getZ() + cos * i;
            BlockPos pos = BlockPos.containing(targetX, mc.player.getY(), targetZ);

            if (isFree(pos)) {
                // 玩家与目标之间必须隔着至少一面实心墙，否则只是普通位移
                if (wallBetween) {
                    movePlayer(targetX, mc.player.getY(), targetZ);
                    return;
                }
            } else {
                wallBetween = true;
            }
        }
    }

    /**
     * 移动玩家：本地 setPos 立即生效 + 补发位置包（与 Teleport.teleportRelative
     * 相同写法，26.2 的 ServerboundMovePlayerPacket.Pos(x, y, z, onGround,
     * horizontalCollision) 签名已 javap 确证）。
     */
    private void movePlayer(double x, double y, double z) {
        mc.player.setPos(x, y, z);
        mc.player.resetFallDistance();
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
                x, y, z, mc.player.onGround(), mc.player.horizontalCollision));
    }

    /** 该方块位置是否无碰撞（空气或碰撞箱为空） */
    private boolean isFree(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(mc.level, pos);
        return shape.isEmpty();
    }

    @Override
    protected void onDisable() {
        // 无需还原状态（每次 tick 按需置位）
    }
}
