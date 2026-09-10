package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PacketEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Stuck（卡住不动）：原地冻结，禁止一切位移与位置同步发包。
 * <p>
 * 移植自 Rise 6.9.5 Stuck，精简为 26.2 可用的完整实现：
 * - 冻结：每 tick 清零速度（deltaMovement）并把玩家位置拉回冻结点（本地 setPos，
 *   不发任何位置包）；
 * - 拦截位置包：Stuck 开启时取消所有 ServerboundMovePlayerPacket（26.2 中位置同步
 *   包 = ServerboundMovePlayerPacket 家族，经 ConnectionEventMixin 以
 *   PacketEvent.Send 分发），服务器收不到位置更新 → 视角上完全冻结；
 * - 脉冲模式（Pulse Ticks > 0）：每 N tick 短暂"解冻"放行位置包，然后重新冻结
 *   （Rise 的 pulse/release 语义，用于绕过"冻结检测"）；
 * - 旋转放行（Rotate）：默认连旋转一并拦截；开启后放行纯旋转包
 *   （hasPosition() == false 的变体，等价 1.8 的 C05PacketPlayerLook）。
 */
public class Stuck extends Module {

    /** 冻结点（启用时记录） */
    private double frozenX;
    private double frozenY;
    private double frozenZ;

    /** 是否处于冻结状态 */
    private boolean stuck;

    /** 冻结累计 tick / 解冻剩余 tick（脉冲模式用） */
    private int stuckTicks;
    private int refreezeTicks;

    /** 冻结时保存的速度（Rise 原版在解冻时恢复） */
    private net.minecraft.world.phys.Vec3 savedMotion;

    /** 设置引用（对应 Rise 的 rotations / pulseTicks BooleanValue/NumberValue） */
    private BoolSetting keepRotation;
    private IntSetting pulseTicks;

    public Stuck() {
        super("stuck", "Stuck", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        // 放行纯旋转包（false=位置/旋转包全拦；true=保留转头）
        keepRotation = boolSetting("旋转放行", false, () -> true);
        // 脉冲冻结：0=持续冻结；>0=每 N tick 放行一个 tick 再冻结（绕过冻结检测）
        pulseTicks = intSetting("脉冲间隔", 0, 0, 30, 1, () -> true);
    }

    @Override
    protected void onEnable() {
        this.stuck = false;
        this.stuckTicks = 0;
        this.refreezeTicks = 0;
        freeze();
    }

    @Override
    protected void onDisable() {
        this.stuckTicks = 0;
        this.refreezeTicks = 0;
        release();
    }

    /** 进入冻结：保存当前速度与位置，置冻结标记（26.2 速度字段 = deltaMovement） */
    private void freeze() {
        if (this.stuck || mc.player == null) {
            return;
        }
        this.savedMotion = mc.player.getDeltaMovement();
        this.frozenX = mc.player.getX();
        this.frozenY = mc.player.getY();
        this.frozenZ = mc.player.getZ();
        this.stuck = true;
        this.stuckTicks = 0;
        this.refreezeTicks = 0;
    }

    /** 解除冻结：恢复保存的速度 */
    private void release() {
        if (!this.stuck) {
            return;
        }
        if (this.savedMotion != null && mc.player != null) {
            mc.player.setDeltaMovement(this.savedMotion);
        }
        this.stuck = false;
    }

    /** 脉冲模式下放行一个 tick（Rise 的 pulse：release 后下一 tick 重新冻结） */
    private void pulse() {
        release();
        this.stuckTicks = 0;
        this.refreezeTicks = 1;
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        int interval = this.pulseTicks.getValue();
        if (interval <= 0) {
            // 持续冻结模式：确保已冻结
            if (!this.stuck) {
                freeze();
            }
            this.stuckTicks = 0;
            this.refreezeTicks = 0;
        } else if (this.stuck) {
            // 冻结中：累计 tick，达到脉冲间隔则放行一个 tick
            if (++this.stuckTicks >= interval) {
                pulse();
            }
        } else if (this.refreezeTicks > 0 && --this.refreezeTicks <= 0) {
            // 放行期结束 → 重新冻结
            freeze();
        }

        // 冻结状态下每 tick 清零速度并拉回冻结点（原地不动）
        if (this.stuck) {
            mc.player.setDeltaMovement(0.0, 0.0, 0.0);
            mc.player.setPos(this.frozenX, this.frozenY, this.frozenZ);
            mc.player.resetFallDistance();
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        // 26.2 位置同步包 = ServerboundMovePlayerPacket 家族（Pos/PosRot/Rot/StatusOnly），
        // 由 ConnectionEventMixin 以 PacketEvent.Send 分发（确认过只放行 MovePlayer 等四类）。
        if (this.stuck && event.getPacket() instanceof ServerboundMovePlayerPacket movePacket) {
            // 放行纯旋转包（hasPosition() == false 的变体，等价 1.8 的 C05PacketPlayerLook）
            if (this.keepRotation.getValue() && !movePacket.hasPosition()) {
                return;
            }
            // 其余（带位置或默认全拦）一律取消 → 服务器认为玩家原地不动
            event.cancel();
        }
    }

}
