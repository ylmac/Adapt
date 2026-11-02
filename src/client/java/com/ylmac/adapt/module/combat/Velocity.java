package com.ylmac.adapt.module.combat;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Velocity 击退控制。
 * <p>
 * 26.2 的 ClientboundSetEntityMotionPacket 是 record（字段只读，javap 已确证），
 * 事件总线替换包又无效（ConnectionEventMixin 只消费取消标志），因此改为：
 * - 收到自己 id 的击退包 → 取消（拦截 lerpMotion 应用）
 * - 手动按 水平/垂直 百分比 setDeltaMovement 重放缩放后的速度（语义等价 1.8.9 改包字段）
 * <p>
 * 爆炸击退（ClientboundExplodePacket.playerKnockback，应用走
 * handleExplosion 里 Optional.ifPresent(Entity::addDeltaMovement)）：
 * explosionIgnore=true 时取消整个爆炸包。
 * <p>
 * 模式：
 * - Standard：默认，收到击退立刻按百分比重放（原实现）。
 * 并同步延迟确认包；26.2 无 ConfirmTransaction 可延迟，故只延迟击退本身。
 * 延迟期间玩家可自由移动，常用于绕过"击退立即结算"类反作弊）。
 * 完整击退；实现为收到击退包后标记 pending，玩家落地（onGround）时
 * 一次性按百分比重放）。
 * - Bounce：击退反弹到达指定
 * tick 时反向速度，向上/水平可独立开关，实现为反向重放速度并可选上抛）。
 */
public class Velocity extends Module {

 /** 模式 */
    private final ChoiceSetting mode;

    /** 水平击退百分比（0 = 完全无击退） */
    private final IntSetting horizontal;
    /** 垂直击退百分比（0 = 无垂直击退） */
    private final IntSetting vertical;
 /** 是否忽略爆炸击退 */
    private final BoolSetting explosionIgnore;
 /** 仅在挥动画时生效 */
    private final BoolSetting onSwing;

 /** Delay 模式：延迟 tick 数 */
    private final IntSetting delayTicks;
 /** Delay 模式：是否也延迟爆炸包 */
    private final BoolSetting delayExplosions;

 /** Ground 模式：落地延迟 */
    private final IntSetting groundDelay;

 /** Bounce 模式：触发 tick */
    private final IntSetting bounceTick;
 /** Bounce 模式：是否反弹水平速度 */
    private final BoolSetting bounceHorizontal;
 /** Bounce 模式：是否反弹垂直速度 */
    private final BoolSetting bounceVertical;

    // ---------- 内部状态 ----------
    /** Delay 模式：延迟队列（击退包原值 + 计划应用 tick） */
    private final Deque<PendingVelocity> delayQueue = new ArrayDeque<>();
    /** Ground 模式：等待落地时应用的击退 */
    private Vec3 groundPending;
 /** Ground 模式：落地连续 tick 计数 */
    private int groundLandTicks;
    /** Bounce 模式：受击跟踪（被击退的实体 id → 受击后 tick） */
    private int bounceTrackerId = -1;
    private int bounceHurtTicks;

    public Velocity() {
        super("velocity", "Velocity", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        mode = choiceSetting("Mode", "Standard",
                java.util.List.of("Standard", "Delay", "Ground", "Bounce"));
        horizontal = intSetting("水平", 0, 0, 100, 1);
        vertical = intSetting("垂直", 0, 0, 100, 1);
        explosionIgnore = boolSetting("爆炸忽略", false);
        onSwing = boolSetting("挥动时才生效", false);

 // Delay 模式参数
        delayTicks = intSetting("延迟Ticks", 5, 0, 40, 1,
                () -> "Delay".equals(mode.getValue()));
        delayExplosions = boolSetting("延迟爆炸", true,
                () -> "Delay".equals(mode.getValue()));

 // Ground 模式参数
        groundDelay = intSetting("落地延迟", 1, 0, 20, 1,
                () -> "Ground".equals(mode.getValue()));

 // Bounce 模式参数
        bounceTick = intSetting("反弹Tick", 0, 0, 6, 1,
                () -> "Bounce".equals(mode.getValue()));
        bounceHorizontal = boolSetting("反弹水平", true,
                () -> "Bounce".equals(mode.getValue()));
        bounceVertical = boolSetting("反弹垂直", false,
                () -> "Bounce".equals(mode.getValue()));
    }

    @Override
    protected void onEnable() {
        delayQueue.clear();
        groundPending = null;
        groundLandTicks = 0;
        bounceTrackerId = -1;
        bounceHurtTicks = 0;
    }

    @Override
    protected void onDisable() {
 // 关闭时把延迟/挂起的击退立即应用，避免丢失击退
        flushPending();
        onEnable();
    }

 /** 是否当前处于挥动窗口 */
    private boolean inSwingWindow() {
        return mc.player != null && mc.player.isSwinging();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;
        if (onSwing.getValue() && !inSwingWindow()) return;

        if (event.getPacket() instanceof ClientboundSetEntityMotionPacket packet) {
            // 只处理自己的击退
            if (packet.id() != mc.player.getId()) return;

            switch (mode.getValue()) {
                case "Delay" -> {
 // 取消原包并延迟应用
                    event.cancel();
                    delayQueue.addLast(new PendingVelocity(
                            packet.movement(),
                            mc.player.tickCount + delayTicks.getValue()));
                }
                case "Ground" -> {
 // 取消原包，落地后应用
                    event.cancel();
                    if (mc.player.onGround() && groundDelay.getValue() <= 0) {
                        applyScaled(packet.movement());
                    } else {
                        groundPending = packet.movement();
                    }
                }
                case "Bounce" -> {
 // 取消原包，受击跟踪
                    event.cancel();
                    bounceTrackerId = mc.player.getId();
                    bounceHurtTicks = 0;
                    // 仍应用缩放后的基础击退（否则完全无击退，反弹无从谈起）
                    applyScaled(packet.movement());
                }
                default -> {
                    // Standard：取消原包，立刻按百分比重放（原实现）
                    event.cancel();
                    applyScaled(packet.movement());
                }
            }
        } else if (event.getPacket() instanceof ClientboundExplodePacket) {
            if (explosionIgnore.getValue()) {
 // 爆炸击退忽略：取消整个爆炸包
                event.cancel();
            } else if ("Delay".equals(mode.getValue()) && delayExplosions.getValue()) {
                event.cancel();
                delayQueue.addLast(new PendingVelocity(
                        Vec3.ZERO, // 占位：爆炸包击退单独从包内读取
                        mc.player.tickCount + delayTicks.getValue(),
                        event.getPacket()));
            }
        }
    }

 /** 每 tick：处理延迟队列 */
    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (mc.player == null) return;

        // Delay 模式：到期应用
        if ("Delay".equals(mode.getValue()) && !delayQueue.isEmpty()) {
            while (!delayQueue.isEmpty()
                    && delayQueue.peekFirst().applyTick <= mc.player.tickCount) {
                PendingVelocity pending = delayQueue.removeFirst();
                if (pending.explosionPacket != null) {
                    // 延迟的爆炸包直接重新走原处理（此时不再次入队）
                    handleExplosionNow(pending.explosionPacket);
                } else {
                    applyScaled(pending.movement);
                }
            }
        }

 // Ground 模式：落地应用
        if ("Ground".equals(mode.getValue()) && groundPending != null) {
            if (mc.player.onGround()) {
                groundLandTicks++;
                if (groundLandTicks >= groundDelay.getValue()) {
                    applyScaled(groundPending);
                    groundPending = null;
                    groundLandTicks = 0;
                }
            } else {
                groundLandTicks = 0;
            }
        }

 // Bounce 模式：受击跟踪，到达指定 hurtTime 时反弹
        if ("Bounce".equals(mode.getValue()) && bounceTrackerId != -1) {
            if (mc.player.hurtTime > 0) {
                bounceHurtTicks++;
                int target = 9 - bounceTick.getValue();
                if (mc.player.hurtTime == target) {
                    Vec3 motion = mc.player.getDeltaMovement();
                    if (bounceHorizontal.getValue()) {
                        motion = motion.multiply(-1.0, 1.0, -1.0);
                    }
                    if (bounceVertical.getValue()) {
                        motion = motion.multiply(1.0, -1.0, 1.0);
                    }
                    mc.player.setDeltaMovement(motion);
                    bounceTrackerId = -1; // 已反弹，本次受击结束
                }
            } else {
                bounceTrackerId = -1; // 受击结束，跟踪复位
            }
        }
    }

    /** 延迟队列里的爆炸包到期：按百分比重放爆炸击退（语义等价原包 + 缩放） */
    private void handleExplosionNow(net.minecraft.network.protocol.Packet<?> explosionPacket) {
        if (mc.player == null || mc.level == null) return;
        // 26.2 爆炸击退在 handleExplosion 中经
        // playerKnockback.ifPresent(player -> player.addDeltaMovement(knockback)) 应用
        // （javap 确证，BootstrapMethod #6 → Entity.addDeltaMovement(Vec3)）。
        // 这里等价重放：把爆炸击退按百分比叠加到当前速度。
        if (explosionPacket instanceof ClientboundExplodePacket explode) {
            explode.playerKnockback().ifPresent(knockback -> {
                float h = horizontal.getValue() / 100.0F;
                float v = vertical.getValue() / 100.0F;
                mc.player.addDeltaMovement(new Vec3(
                        knockback.x * h, knockback.y * v, knockback.z * h));
            });
        }
    }

    /** 按百分比应用击退（水平/垂直缩放；Standard 主路径） */
    private void applyScaled(Vec3 motion) {
        if (mc.player == null) return;
        float h = horizontal.getValue() / 100.0F;
        float v = vertical.getValue() / 100.0F;
        mc.player.setDeltaMovement(motion.x * h, motion.y * v, motion.z * h);
    }

 /** 关闭模块时把挂起的击退全部应用 */
    private void flushPending() {
        if (mc.player == null) return;
        while (!delayQueue.isEmpty()) {
            PendingVelocity pending = delayQueue.removeFirst();
            if (pending.explosionPacket == null) {
                applyScaled(pending.movement);
            }
        }
        if (groundPending != null) {
            applyScaled(groundPending);
            groundPending = null;
        }
    }

    @Override
    public String getInfo() {
        return mode.getValue() + " " + horizontal.getValue() + "%/" + vertical.getValue() + "%";
    }

 /** 延迟队列条目 */
    private static final class PendingVelocity {
        final Vec3 movement;
        final int applyTick;
        final net.minecraft.network.protocol.Packet<?> explosionPacket; // 非空 = 延迟的爆炸包

        PendingVelocity(Vec3 movement, int applyTick) {
            this(movement, applyTick, null);
        }

        PendingVelocity(Vec3 movement, int applyTick, net.minecraft.network.protocol.Packet<?> explosionPacket) {
            this.movement = movement;
            this.applyTick = applyTick;
            this.explosionPacket = explosionPacket;
        }
    }
}
