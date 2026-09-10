package com.ylmac.adapt.features.module.impl.player;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * AntiVoid 防虚空坠落（移植自 Rise 6.9.5 的 AntiVoid 模块）。
 * <p>
 * Rise 原版是 PacketAntiVoid/PositionAntiVoid 等多模式组合：
 * - PacketAntiVoid：fallDistance 超过阈值且不在地面时，向服务器补发一个
 *   位置包（带"地面"标记），利用服务器回弹把玩家拉回安全高度；
 * - PositionAntiVoid：在运动事件里把 y 分量抬高 fallDistance 距离（占位，防止坠入虚空）。
 * <p>
 * 本移植提供两种模式（审计修复：补回 Rise 的 Packet 模式 + 模式选择）：
 * <ul>
 *   <li><b>Position（原实现）</b>：安全位置回退 + 缓降 —— 每 tick 检测玩家 y 低于
 *       阈值且下方无地面（位置不在世界边界内 / 下方无碰撞方块）→ 认定为"可能坠入
 *       虚空"；一旦触发，立即把玩家拉回最近记录的安全位置（安全位置 = 下方有地面
 *       时记录的位置），并清除垂直速度、重置 fallDistance，避免继续下坠；若玩家在
 *       y=0 以下（世界边界外，getBlockState 已不可用）则强制回拉。</li>
 *   <li><b>Packet（Rise PacketAntiVoid 移植）</b>：不直接传送，而是向服务器补发一个
 *       {@link ServerboundMovePlayerPacket.PosRot}（位置 = 安全位置上方、onGround=true）
 *       的移动包。服务器收到 onGround=true 的位置包后会把玩家"认可"为站在地面上，
 *       利用服务器回弹/位置同步把实体拉回安全高度 —— 对防反作弊更友好
 *       （Grim 等会把客户端直接 absSnapTo 传送判为强制移动/触发速度检测，补发
 *       移动包则走正常移动判定通道）。本地玩家位置同步到包内坐标，避免服务端
 *       重发位置包时把玩家拉回。</li>
 * </ul>
 * 26.2 API（已 javap 确证）：
 * - Entity: fallDistance(字段)、resetFallDistance()、setDeltaMovement(Vec3)、
 *   absSnapTo(x,y,z)、onGround()(方法)、getY()/blockPosition()/level()
 * - Level: isInWorldBounds(BlockPos)、getBlockState(BlockPos)
 * - BlockStateBase: isAir()/isCollisionShapeFullBlock(BlockGetter, BlockPos)
 * - ServerboundMovePlayerPacket.PosRot(Vec3, float, float, boolean, boolean)：
 *   构造器存在，走 Connection.send(packet, null) 静默发送
 */
public class AntiVoid extends Module {

    /** 模式：Position（直接回拉传送）/ Packet（补发 onGround 移动包回拉，防反作弊） */
    private final ChoiceSetting mode;

    /** 触发阈值：y 低于该值且下方无地面 → 判定为坠入虚空 */
    private final DoubleSetting threshold;

    /** 回拉时保留的垂直距离（避免卡进方块） */
    private final DoubleSetting pullDistance;

    /** 安全位置记录（下方有地面时每 tick 更新；限时有效） */
    private Vec3 safePos;
    private int safePosAge;

    /** 触发后重置回拉冷却，防止每 tick 反复传送 */
    private int pullCooldown;

    public AntiVoid() {
        super("antivoid", "AntiVoid", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        mode = choiceSetting("模式", "Position", java.util.List.of("Position", "Packet"));
        threshold = doubleSetting("触发高度", 10.0, 0.0, 256.0, 0.5);
        pullDistance = doubleSetting("回拉高度", 1.0, 0.1, 5.0, 0.1);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        // 飞行/创造/观察者模式下无需防坠落
        if (mc.player.getAbilities().flying || mc.player.isSpectator()) return;

        if (pullCooldown > 0) {
            pullCooldown--;
            return;
        }

        Vec3 pos = mc.player.position();
        BlockPos blockPos = BlockPos.containing(pos.x, pos.y - 0.01, pos.z);
        boolean hasGround = mc.level.isInWorldBounds(blockPos)
                && !mc.level.getBlockState(blockPos).isAir();

        // 记录安全位置（下方有地面，且玩家实体本身无碰撞阻塞）
        if (hasGround && mc.level.noCollision(mc.player, mc.player.getBoundingBox())) {
            safePos = pos;
            safePosAge = 0;
        } else if (safePos != null) {
            // 安全位置超过 2 秒未更新则作废，避免回拉到早已移动的旧坐标
            safePosAge++;
            if (safePosAge > 40) {
                safePos = null;
            }
        }

        // 判定：y 低于阈值 + 下方无地面 → 拉回安全位置（无可回位置则强制抬升 4 格缓降）
        boolean overVoid = mc.player.getY() < threshold.getValue() && !hasGround;
        if (overVoid && safePos != null) {
            if ("Packet".equals(mode.getValue())) {
                pullBackPacket();
            } else {
                pullBack();
            }
        } else if (mc.player.getY() < threshold.getValue() && !hasGround) {
            // 无安全记录：向上缓抬，避免无限坠落（配合 fallDistance 复位实现缓降）
            mc.player.absSnapTo(mc.player.getX(), mc.player.getY() + 4.0, mc.player.getZ());
            mc.player.resetFallDistance();
            pullCooldown = 5;
        }
    }

    /** Position 模式：拉回最近记录的安全位置（清除垂直速度 + 重置坠落距离） */
    private void pullBack() {
        if (safePos == null || mc.player == null) return;
        mc.player.absSnapTo(safePos.x, safePos.y + pullDistance.getValue(), safePos.z);
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.resetFallDistance();
        pullCooldown = 5;
        safePos = null;
    }

    /**
     * Packet 模式（Rise PacketAntiVoid 移植，防反作弊）：
     * 向服务器补发一个"站在安全位置上方、onGround=true"的移动包。服务器把玩家
     * 认可为站在地面上后，回弹/同步会自然把实体拉回安全高度 —— 不直接本地传送，
     * 移动判定走正常通道，Grim 等反作弊不会把玩家标记为强制移动/瞬移。
     */
    private void pullBackPacket() {
        if (safePos == null || mc.player == null) return;
        if (mc.getConnection() == null) return;
        double targetY = safePos.y + pullDistance.getValue();
        // 补发 onGround=true 的位置包（等价 1.8.9 C04PacketPlayerPosition 的
        // "服务器认为在地面"语义；26.2 构造器：
        // PosRot(Vec3 position, float yRot, float xRot, boolean onGround, boolean horizontalCollision)）
        ServerboundMovePlayerPacket packet = new ServerboundMovePlayerPacket.PosRot(
                new Vec3(mc.player.getX(), targetY, mc.player.getZ()),
                mc.player.getYRot(), mc.player.getXRot(), true, false);
        mc.getConnection().getConnection().send(packet, null);
        // 本地位置对齐到包内坐标（服务器回弹前先同步，避免服务端重发位置包时拉回）
        mc.player.setPos(mc.player.getX(), targetY, mc.player.getZ());
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.resetFallDistance();
        pullCooldown = 5;
        safePos = null;
    }

    @Override
    public String getInfo() {
        return mode.getValue();
    }
}
