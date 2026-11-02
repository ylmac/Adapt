package com.ylmac.adapt.module.combat;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * LegitReach — 合法距离优势。
 * <p>
 * 通过 BlinkComponent 挂起/释放自己的移动包，让服务端把攻击判定用到的
 * 保留其"追踪目标真实位置"骨架，但 26.2 的挂包（Blink）机制与 1.8.9 差异过大
 * （发包模型 / 序列号 / 预测全部重写），因此把"距离优势"落到 26.2 支持的
 * 攻击判定链上：用 mixin 放大攻击/交互距离（见 LegitReachMixin），并把
 * 目标真实位置追踪（离屏位置）合并为两种模式：
 * - 命中模式（hit）：直接用放大后的射线 + isInRange 判定攻击（瞄准真实位置）。
 * - 反延迟模式（backtrack）：追踪目标在 26.2 服务器侧的"真实/延迟补偿"位置
 * （用实体位移包 ClientboundMoveEntityPacket + 传送包 ClientboundTeleportEntityPacket
 * 引导到该真实位置 — 服务端把攻击判定回溯到该位置时命中概率更高。
 * <p>
 * - 攻击距离放大：{@code AttackRange.effectiveMaxRange}（瞄准射线长度）、
 * {@code Minecraft.startAttack} 里的 {@code AttackRange.isInRange} 距离闸门
 * （ModifyReceiver 换成放大副本）、{@code Player.entityInteractionRange}（右键
 * 交互实体）与 {@code Player.blockInteractionRange}（方块交互）—LegitReachMixin。
 * - 位移包：{@code ClientboundMoveEntityPacket}（相对位移，含 Pos/PosRot/自类）、
 * {@code ClientboundTeleportEntityPacket}（绝对传送），javap 确证存在。
 * - 目标排序：{@code ClientLevel.entitiesForRendering} 遍历（与 KillAura 一致）。
 * - 无敌过滤：{@code LivingEntity.isDeadOrDying}。
 * <p>
 * 依赖：LegitReachMixin（AttackRange + Player 两处，已随本模块实现并注册）。
 */
public class LegitReach extends Module {

    /** 单例引用（LegitReachMixin 经此读取距离值 / 判断启用） */
    private static LegitReach instance;

    public static LegitReach get() {
        return instance;
    }

 /** 攻击距离加成 */
    private DoubleSetting attackRange;
    /** 交互距离加成（右键实体） */
    private DoubleSetting interactionRange;
    /** 方块交互/挖掘距离加成 */
    private DoubleSetting blockRange;
    /** 距离模式：hit=命中优势（放大射线直接打） / backtrack=反延迟（瞄准真实位置） */
    private ChoiceSetting mode;
 /** 最大延迟回溯上限 tick */
    private IntSetting maxBacktrackTicks;
 /** 是否渲染目标真实位置 */
    private BoolSetting renderRealLocation;
 /** 是否过滤 bot 目标 */
    private BoolSetting filterBots;

 // 内部状态
    private Entity targetEntity;
    private Vec3 realTargetPosition;
    private boolean isActive;
    /** 目标上一次位移包的 tick（判定"还在动"用） */
    private int lastTrackTick = -1;
    /** 是否处于反延迟瞄准状态（HUD tag 用） */
    private boolean backtracking;

    public LegitReach() {
        super("legitreach", "LegitReach", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        attackRange = doubleSetting("攻击距离", 0.5, 0.0, 3.0, 0.05);
        interactionRange = doubleSetting("交互距离", 0.5, 0.0, 3.0, 0.05);
        blockRange = doubleSetting("方块距离", 0.5, 0.0, 3.0, 0.05);
        mode = choiceSetting("距离模式", "hit", List.of("hit", "backtrack"));
        maxBacktrackTicks = intSetting("回溯tick上限", 20, 0, 200, 1,
                () -> mode.getValue().equals("backtrack"));
        renderRealLocation = boolSetting("渲染真实位置", true,
                () -> mode.getValue().equals("backtrack"));
        filterBots = boolSetting("过滤Bot", true);
    }

    @Override
    protected void onEnable() {
        targetEntity = null;
        realTargetPosition = null;
        isActive = false;
        backtracking = false;
        lastTrackTick = -1;
    }

    @Override
    protected void onDisable() {
        targetEntity = null;
        realTargetPosition = null;
        isActive = false;
        backtracking = false;
    }

    /** 模块启用且未开界面时生效（供 mixin 判断） */
    public boolean isActive() {
        return this.isEnabled() && mc.gui.screen() == null;
    }

    /** 攻击距离加成（LegitReachMixin effectiveMaxRange / isInRange 用） */
    public double getAttackRange() {
        return attackRange.getValue();
    }

    /** 交互距离加成（LegitReachMixin entityInteractionRange 用） */
    public double getInteractionRange() {
        return interactionRange.getValue();
    }

    /** 方块交互/挖掘距离加成（LegitReachMixin blockInteractionRange 用） */
    public double getBlockRange() {
        return blockRange.getValue();
    }

    /** 当前瞄准是否处于反延迟状态（供其它模块参考） */
    public boolean isBacktracking() {
        return backtracking;
    }

    /**
 * 每 tick 扫描目标并更新真实位置。
 * <p>
 * 取最近的可攻击 LivingEntity（含 AntiBot 过滤 / 无敌过滤），并维护
 * realTargetPosition（用位移包追踪，见 onPacketReceive）。
 */
    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (nullCheck() || mc.gui.screen() != null) {
            resetState();
            return;
        }

 // 找最近目标
        Entity best = null;
        double bestDistSqr = 9.0 * 9.0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isValidTarget(living)) continue;
            double distSqr = mc.player.distanceToSqr(living.getX(), living.getY(), living.getZ());
            if (distSqr <= bestDistSqr) {
                bestDistSqr = distSqr;
                best = living;
            }
        }

        if (best == null) {
            resetState();
            return;
        }

 // 目标切换时重新锚定真实位置
        if (best != targetEntity) {
            targetEntity = best;
            realTargetPosition = best.position();
            lastTrackTick = mc.player.tickCount;
        }

        // hit 模式：实时用客户端看到的位置（射线放大后直接命中）
        if (mode.getValue().equals("hit")) {
            realTargetPosition = best.position();
        }
        backtracking = mode.getValue().equals("backtrack")
                && realTargetPosition != null
                && mc.player.tickCount - lastTrackTick <= maxBacktrackTicks.getValue();
        isActive = backtracking;
    }

 /** 目标有效性 */
    private boolean isValidTarget(LivingEntity living) {
        if (living == mc.player) return false;
        if (living == mc.player.getVehicle()) return false;
        if (!living.isAlive() || living.isDeadOrDying()) return false;
        if (filterBots.getValue() && AntiBot.get() != null && AntiBot.get().isBot(living)) return false;
        return true;
    }

    /**
 * 位移包追踪目标真实位置：
 * - ClientboundMoveEntityPacket（相对位移）→ realTargetPosition += delta/4096
 * - ClientboundTeleportEntityPacket（绝对传送）→ realTargetPosition = 新位置
 * 这样即使目标离屏/延迟补偿，也能还原其服务器真实位置用于瞄准。
 */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (targetEntity == null || realTargetPosition == null) return;
        if (mc.player == null) return;
        if (event.getPacket() instanceof ClientboundMoveEntityPacket packet) {
            // ClientboundMoveEntityPacket 无公开 id getter（字段 protected），
            // 用 getEntity(level) 反查实体，判空即不是目标实体
            Entity moved = packet.getEntity(mc.level);
            if (moved != targetEntity) return;
            VecDelta delta = packet.getPositionDelta();
            if (delta instanceof VecDelta.Linear linear) {
                realTargetPosition = realTargetPosition.add(
                        linear.xa() / 4096.0,
                        linear.ya() / 4096.0,
                        linear.za() / 4096.0);
            } else {
                realTargetPosition = delta.decode(moved.getPositionCodec()).endPosition();
            }
            lastTrackTick = mc.player.tickCount;
        } else if (event.getPacket() instanceof ClientboundTeleportEntityPacket teleport) {
            if (teleport.id() != targetEntity.getId()) return;
            realTargetPosition = teleport.change().position();
            lastTrackTick = mc.player.tickCount;
        }
    }

 /** 渲染目标真实位置。
 * 26.2 无旧式 Tesselator，改用 vanilla Gizmos 调试框通道：
 * LegitReachRendererMixin 在 GameRenderer.renderLevel RETURN（per-frame
 * Gizmos 收集器激活窗口内）回调本方法，提交真实位置包围盒线框。 */
    public void onRenderLevel() {
        if (!this.isEnabled()) return;
        if (nullCheck()) return;
        if (!renderRealLocation.getValue()) return;
        if (targetEntity == null || realTargetPosition == null) return;
        // 用追踪到的真实位置重建包围盒（保持目标当前尺寸）
        AABB box = targetEntity.getBoundingBox();
        double dx = realTargetPosition.x - box.getCenter().x;
        double dy = realTargetPosition.y - box.getCenter().y;
        double dz = realTargetPosition.z - box.getCenter().z;
        AABB renderBox = box.move(dx, dy, dz).inflate(targetEntity.getPickRadius());
        net.minecraft.gizmos.Gizmos.cuboid(renderBox, net.minecraft.gizmos.GizmoStyle.stroke(0x55FFFF55));
    }

 /** 重置内部状态 */
    private void resetState() {
        targetEntity = null;
        realTargetPosition = null;
        isActive = false;
        backtracking = false;
    }

    @Override
    public String getInfo() {
        return mode.getValue() + (backtracking ? " (tracking)" : "");
    }
}
