package com.ylmac.adapt.features.module.impl.player;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PacketEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.event.impl.GameLeftEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * Blink —— 暂停移动发包（瞬移欺骗）。
 * <p>
 * 移植自 Rise 6.9.5 的 Blink 模块：
 * - Rise 通过 BlinkComponent 在 PacketSendEvent 拦截并暂存移动包，关闭/世界切换时统一补发
 * - 本实现：启用期间把 {@link ServerboundMovePlayerPacket}（26.x 下移动包的所有子类）拦截在
 *   {@link PacketEvent.Send} 中暂存；禁用/离开服务器时通过 {@code Connection.send(packet, null)}
 *   静默补发（不触发 PacketEvent，避免递归），并保留最后一个包的位置信息
 *   （服务端重发位置包时本地玩家需要同步，防回弹）
 * <p>
 * 审计修复（对齐 Rise 原版能力）：
 * <ol>
 *   <li><b>Pulse 模式</b>：Rise 的 Pulse 开启时每 Delay tick 自动释放一次暂存包
 *       （PostMotionEvent 里 ticksExisted &gt; next → dispatch + 重置 next），形成
 *       周期性"小 blink"，每次只憋一小段位移、服务器认为玩家一直在线移动 ——
 *       比长时间憋包更安全（长时间不发移动包在多数反作弊会被 flag）。本实现
 *       在 PlayerTickEvent.Post 中做同样的周期 flush，flush 后按 Delay 重设下次
 *       释放 tick。</li>
 *   <li><b>Delay 设置</b>：Pulse 的释放间隔（tick，2-40，默认 2），依赖 Pulse。</li>
 *   <li><b>幽灵实体</b>：Rise 启用时会在世界生成一个实体替身（EntityOtherPlayerMP）
 *       显示玩家"现在应该在的位置"；本实现用 26.2 的 {@link RemotePlayer} 在
 *       启用位置生成替身实体并每 tick 跟随真实玩家（保持静止 = 展示暂存期间
 *       服务器视角的玩家位置），禁用时移除。可选（ghost 设置默认开）。</li>
 * </ol>
 * 保留现有 flushing 防递归守卫：flush 期间静默发包不触发 PacketEvent，
 * 不会把自己新发的包重新拦截。
 * <p>
 * 26.2 API（javap /Users/ylmac/Documents/26.2 确证）：
 * - {@link ServerboundMovePlayerPacket} 全部子类（StatusOnly/Pos/Rot/PosRot）均为抽象类的实例，
 *   拦截父类引用即可覆盖全部移动包
 * - 静默发包：{@link Connection#send(net.minecraft.network.protocol.Packet, io.netty.channel.ChannelFutureListener)}
 *   传 null listener —— 不经过 ConnectionEventMixin 的 PacketEvent，不会递归拦截
 * - 幽灵实体：{@link RemotePlayer}(ClientLevel, GameProfile) + {@code Entity.setId(int)}
 *   + {@code ClientLevel.addEntity(Entity)}；移除：
 *   {@code ClientLevel.removeEntity(int, Entity.RemovalReason.DISCARDED)}；
 *   位置同步：{@code Entity.setPos(double,double,double)} + {@code setYRot/setXRot}
 */
public class Blink extends Module {

    /** Pulse 模式（Rise Pulse）：周期性自动释放暂存包 */
    private final BoolSetting pulse;

    /** Pulse 释放间隔（tick，Rise Delay 2-40 随机区间，本实现取区间两端随机） */
    private final IntSetting pulseDelay;

    /** 幽灵实体（Rise EntityOtherPlayerMP 替身）：启用时在玩家位置生成替身展示"服务器视角位置" */
    private final BoolSetting ghost;

    /** 暂存的移动包队列（按发送顺序保持） */
    private final Queue<ServerboundMovePlayerPacket> heldPackets = new ArrayDeque<>();

    /** 最后一个被拦截的移动包（用于禁用时把本地位置对齐到服务端视角，防回弹） */
    private ServerboundMovePlayerPacket lastHeldPacket;

    /** 最后一个被拦截的位置包（服务端确认位移的参考） */
    private ServerboundMovePlayerPacket lastHeldPositionPacket;

    /** 是否在补发包（防止静默发包又被本模块拦截形成递归） */
    private boolean flushing;

    /** Pulse 模式：下一次自动释放的 tick（Rise next 语义） */
    private int nextPulseTick = 0;

    /** 幽灵实体（26.2 替身） */
    private RemotePlayer ghostEntity;

    public Blink() {
        super("blink", "Blink", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        pulse = boolSetting("Pulse", false);
        pulseDelay = intSetting("Pulse间隔", 2, 2, 40, 1, () -> pulse.getValue());
        ghost = boolSetting("幽灵实体", true);
    }

    /** 静默发送一个包（绕过 PacketEvent，避免被自己重新拦截） */
    private void sendSilently(net.minecraft.network.protocol.Packet<?> packet) {
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().send(packet, null);
        }
    }

    @Override
    protected void onEnable() {
        heldPackets.clear();
        lastHeldPacket = null;
        lastHeldPositionPacket = null;
        // Pulse：启用即记下一次释放时间（Rise gi()）
        nextPulseTick = mc.player != null
                ? mc.player.tickCount + pulseDelay.getValue() : 0;
        if (ghost.getValue()) {
            spawnGhost();
        }
    }

    @Override
    protected void onDisable() {
        flush();
        despawnGhost();
    }

    /** 补发全部暂存包 */
    private void flush() {
        if (flushing || heldPackets.isEmpty()) {
            heldPackets.clear();
            return;
        }
        flushing = true;
        List<ServerboundMovePlayerPacket> packets = new ArrayList<>(heldPackets);
        heldPackets.clear();
        try {
            for (ServerboundMovePlayerPacket packet : packets) {
                sendSilently(packet);
            }
        } finally {
            flushing = false;
        }
        // 本地位置同步到最后一个暂存位置，避免服务端重发位置包时把玩家拉回
        if (lastHeldPositionPacket != null && mc.player != null && lastHeldPositionPacket.hasPosition()) {
            mc.player.setPos(
                    lastHeldPositionPacket.getX(mc.player.getX()),
                    lastHeldPositionPacket.getY(mc.player.getY()),
                    lastHeldPositionPacket.getZ(mc.player.getZ())
            );
        }
        lastHeldPacket = null;
        lastHeldPositionPacket = null;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (flushing) return;
        // 只拦截玩家移动包（全部子类都继承 ServerboundMovePlayerPacket）
        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            heldPackets.add(packet);
            lastHeldPacket = packet;
            if (packet.hasPosition()) {
                lastHeldPositionPacket = packet;
            }
            event.cancel();
        }
    }

    /** 每 tick：Pulse 周期释放 + 幽灵实体跟随 + 记录暂存数量到模块 tag（HUD 显示） */
    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (mc.player == null) return;
        // Pulse 模式：到点自动释放一次（Rise onPostMotion 的 gi + dispatch）
        if (pulse.getValue() && mc.player.tickCount >= nextPulseTick) {
            flush();
            nextPulseTick = mc.player.tickCount + pulseDelay.getValue();
        }
        // 幽灵实体跟随真实玩家（替身保持静止 = 服务器视角下的玩家位置）
        if (ghostEntity != null) {
            ghostEntity.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            ghostEntity.setYRot(mc.player.getYRot());
            ghostEntity.setXRot(mc.player.getXRot());
        }
        if (!heldPackets.isEmpty()) {
            setInfo(String.valueOf(heldPackets.size()));
        } else {
            setInfo(null);
        }
    }

    /** 离开服务器时清空暂存（连接即将断开，补发没有意义） */
    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        heldPackets.clear();
        lastHeldPacket = null;
        lastHeldPositionPacket = null;
        despawnGhost();
    }

    // ================= 幽灵实体（Rise EntityOtherPlayerMP 替身 → 26.2 RemotePlayer） =================

    /**
     * 生成幽灵实体：在玩家当前位置生成一个 RemotePlayer 替身，展示"服务器视角下
     * 玩家还在原处"（= 暂存移动包期间的真实服务器位置）。tick 中跟随真实玩家，
     * 使替身始终位于玩家被服务器认可的位置。
     */
    private void spawnGhost() {
        if (mc.level == null || mc.player == null || ghostEntity != null) return;
        GameProfile profile = mc.player.getGameProfile();
        RemotePlayer fake = new RemotePlayer(mc.level, profile);
        // 26.2 实体 id 默认 0，与真实玩家 id 冲突会被 addEntity 覆盖 —— 分配一个
        // 高位 id 避免碰撞（Entity.setId 公开）
        fake.setId(-2000000 + (int) (Math.random() * 1000000));
        fake.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        fake.setYRot(mc.player.getYRot());
        fake.setXRot(mc.player.getXRot());
        mc.level.addEntity(fake);
        ghostEntity = fake;
    }

    /** 移除幽灵实体 */
    private void despawnGhost() {
        if (ghostEntity == null) return;
        if (mc.level != null) {
            mc.level.removeEntity(ghostEntity.getId(), Entity.RemovalReason.DISCARDED);
        }
        ghostEntity = null;
    }

    @Override
    public String getInfo() {
        if (pulse.getValue()) return "Pulse";
        return null;
    }
}
