package com.ylmac.adapt.features.module.impl.combat;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.event.impl.GameJoinedEvent;
import com.ylmac.adapt.features.event.impl.GameLeftEvent;
import com.ylmac.adapt.features.event.impl.PacketEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;

import com.ylmac.adapt.features.tickbase.TimerUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * TickBase 延迟缓冲抢首刀（移植自 Rise 6.9.5 combat/TickBase，1.8.9 → Fabric 26.2）。
 * <p>
 * 原版（Post 模式）原理：抢「第一个进入攻击距离的瞬间」——玩家接近目标时先把
 * 若干游戏 tick 缓冲住（timer 加速 + 跳过 tick，让服务端看不到玩家中途位置），
 * 到达攻击距离的瞬间一次性把积压 tick 补上，让服务端把「多个 tick 的位移 +
 * 一次攻击」压缩在极短时间处理，抢在对方反应/检测之前先打出第一刀。Legit 模式：
 * 只在检测到目标逼近时 Blink 一帧。
 * <p>
 * 26.2 移植（API 全部 javap /Users/ylmac/Documents/26.2 确证）：
 * <ol>
 *   <li>26.x 无 1.8.9 的 Minecraft.timer 字段：Timer 移到
 *       {@link net.minecraft.client.DeltaTracker.Timer} 且 deltaTicks 为 private ——
 *       加速经 {@link DeltaTrackerTimerMixin}（advanceGameTime 的 PUTFIELD AFTER 乘法）。</li>
 *   <li>26.2 无 TickEvent.setCancelled 型全局 tick 取消：Rise 的
 *       {@code var1.setCancelled() + timer.cancel()} 等价物 = 取消
 *       {@link PlayerTickEvent.Pre}（LocalPlayerEventMixin 在 LocalPlayer.tick HEAD
 *       派发，可取消 → 本地玩家本 tick 完全不更新，移动包也不发）。</li>
 *   <li>移动包字段 final：26.2 的 ServerboundMovePlayerPacket 只能读不能改
 *       （getX/getY/getZ + hasPosition/hasRotation，javap 确证）——
 *       位置追踪改读本地 {@code mc.player.getX()/getY()/getZ()}。</li>
 * </ol>
 * 设置：Mode（Post / Legit）、Max Skip Ticks（单次缓冲上限，原版固定 10）。
 */
public class TickBase extends Module {

    /** 模式选择（Rise ModeValue：Post / Legit） */
    private final com.ylmac.adapt.features.module.settings.impl.ChoiceSetting mode;
    /** 单次最多缓冲的 tick 数（原版 getTicksToReach 模拟上限 10） */
    private final com.ylmac.adapt.features.module.settings.impl.IntSetting maxSkipTicks;

    // 内部状态（Rise 同款字段名注释）
    private int cooldownTicks;   // 距上次缓冲的冷却（Rise cooldownTicks = 10）
    private int ticksToSkip;     // 剩余需要跳过的 tick 数
    private int skipDelayTicks;  // 跳过前空档（Rise skipDelayTicks = i + 5）
    private Vec3 trackedPosition = Vec3.ZERO; // 玩家最后发包位置（Rise trackedPosition）

    public TickBase() {
        super("tickbase", "TickBase", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        mode = choiceSetting("Mode", "Post", List.of("Post", "Legit"));
        maxSkipTicks = intSetting("Max Skip Ticks", 10, 1, 40, 1,
                () -> "Post".equals(mode.getValue()));
    }

    @Override
    protected void onEnable() {
        cooldownTicks = 0;
        ticksToSkip = 0;
        skipDelayTicks = 0;
        trackedPosition = mc.player == null ? Vec3.ZERO
                : new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    @Override
    protected void onDisable() {
        // 关闭：释放缓冲 + 恢复 timer 加速（否则玩家位置与服务端脱节）
        ticksToSkip = 0;
        skipDelayTicks = 0;
        TimerUtil.clearMultiplier(this);
    }

    /** 每客户端 tick：驱动 Post/Legit 逻辑（Rise onTick） */
    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if ("Post".equals(mode.getValue())) {
            handlePostMode();
        } else {
            handleLegitMode();
        }
    }

    /** 玩家 tick 前置钩子：跳过 tick（Rise var1.setCancelled + timer.cancel 的 26.2 等价） */
    @EventHandler(priority = com.ylmac.adapt.features.event.bus.EventPriority.HIGHEST)
    private void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (ticksToSkip > 0 && skipDelayTicks <= 0) {
            ticksToSkip--;
            event.cancel(); // 本地玩家本 tick 不更新 → 不产生移动包
        }
    }

    /** Post 模式：加速 + 缓冲 tick + 逼近目标时一次性补发（抢首刀） */
    private void handlePostMode() {
        LocalPlayer player = mc.player;

        // 进世界前 20 tick：低倍速（Rise timer = 0.5F，等服务器把玩家拉到正确位置）
        if (player.tickCount <= 20) {
            TimerUtil.applyMultiplier(this, 0.5F);
        }

        // 冷却递减（Rise cooldownTicks--）
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        // 积压跳过的 tick：先等空档（skipDelayTicks--），实际跳过在 onPlayerTickPre
        if (ticksToSkip > 0 && skipDelayTicks > 0) {
            skipDelayTicks--;
        }

        // 6 格内最近目标（Rise TargetComponent.f(6.0)）
        LivingEntity target = nearestTarget(6.0);
        if (target == null) {
            return;
        }

        // 预测目标位置（Rise predictPosition：按 ping/50 步外推目标位移）
        Vec3 predicted = predictPosition(target);

        // 距预测位置 < 8 格、目标速度 > 3、冷却已过 → 触发缓冲
        if (player.distanceToSqr(predicted) < 64.0
                && targetSpeedSqr(target) > 9.0
                && cooldownTicks <= 0) {
            int ticks = getTicksToReach(player, target);
            if (ticks <= 0) {
                return; // -1 = 走不到（Rise 原版语义）
            }
            // 本地推进到"抢刀点"（等价 Rise elapsedTicks += i 的效果：
            // 服务器将收到连续位移，本地先到位准备出刀）
            Vec3 motion = player.getDeltaMovement();
            player.setPos(player.getX() + motion.x() * ticks,
                    player.getY(),
                    player.getZ() + motion.z() * ticks);
            // 缓冲对应 tick（跳过期间不发包）＋空档
            ticksToSkip += ticks;
            skipDelayTicks += ticks + 5;
            cooldownTicks = 10;
        }
    }

    /** Legit 模式：目标在逼近时 Blink 一帧（Rise Legit 分支） */
    private void handleLegitMode() {
        LivingEntity target = nearestTarget(8.0);
        if (target == null) return;

        // 目标（预测位）比玩家更靠近「玩家最后发包位置」→ 目标逼近 → 本 tick Blink
        double distToTracked = distanceSqr(playerPos(), trackedPosition);
        double targetDist = distanceSqr(predictPosition(target), trackedPosition);
        if (targetDist >= distToTracked) {
            pendingBlink = true; // 下一移动包拦截一帧（onPacketSend 消费）
        }
    }

    /** 本 tick 是否应拦截移动包（Legit 模式一帧 Blink，Rise BlinkComponent.blink()） */
    private boolean pendingBlink;

    /** 发包钩子：记录玩家发包位置 + 消费一帧 Blink（Rise onPacketSend） */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            trackedPosition = new Vec3(
                    packet.getX(mc.player.getX()),
                    packet.getY(mc.player.getY()),
                    packet.getZ(mc.player.getZ()));
            if (pendingBlink) {
                pendingBlink = false;
                event.cancel(); // 拦截本 tick 的移动包（等价 BlinkComponent.blink()）
            }
        }
    }

    /** 世界切换：清空缓冲/Blink 状态（Rise WorldChangeEvent 同款） */
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        resetState();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        resetState();
    }

    private void resetState() {
        ticksToSkip = 0;
        skipDelayTicks = 0;
        pendingBlink = false;
        TimerUtil.clearMultiplier(this);
    }

    /** 找 range 内最近的存活目标（Rise TargetComponent.f(range) → 最近一个） */
    private LivingEntity nearestTarget(double range) {
        LivingEntity best = null;
        double bestDist = range * range;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living
                    && living != mc.player
                    && living.isAlive()
                    && !living.isInvisible()
                    && living.isPickable()) {
                double d = mc.player.distanceToSqr(living);
                if (d <= bestDist) {
                    bestDist = d;
                    best = living;
                }
            }
        }
        return best;
    }

    /** 目标速度平方（Rise PlayerUtil.v(实体) > 3.0 的速度判断） */
    private double targetSpeedSqr(LivingEntity target) {
        Vec3 delta = target.getDeltaMovement();
        return delta.x() * delta.x() + delta.z() * delta.z();
    }

    /** 预测目标位置（Rise predictPosition：26.2 无 PingSpoofComponent，用当前速度外推 1 tick） */
    private Vec3 predictPosition(LivingEntity target) {
        Vec3 delta = target.getDeltaMovement();
        return new Vec3(target.getX() + delta.x(), target.getY(), target.getZ() + delta.z());
    }

    private Vec3 playerPos() {
        return new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    private double distanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 计算走到攻击距离（4.4 格）所需的 tick 数（Rise getTicksToReach 同款模拟）：
     * 从玩家当前位置开始，每次按当前速度朝前进方向推进一步，直到进入 4.4 格。
     * 返回 -1 表示推满上限仍进不去（走不到 → 不触发，原版语义）。
     */
    private int getTicksToReach(LocalPlayer player, Entity target) {
        Vec3 pos = playerPos();
        Vec3 motion = player.getDeltaMovement();
        float yawRad = (float) Math.toRadians(player.getYRot());
        double forward = Math.max(0, Math.hypot(motion.x(), motion.z()));
        Vec3 step = new Vec3(-Math.sin(yawRad) * forward, motion.y(), Math.cos(yawRad) * forward);

        int ticks = 0;
        while (ticks < maxSkipTicks.getValue()) {
            if (pos.distanceToSqr(target.getX(), target.getY(), target.getZ()) <= 4.4 * 4.4) {
                break;
            }
            pos = pos.add(step);
            ticks++;
        }
        return ticks >= maxSkipTicks.getValue() ? -1 : ticks;
    }
}
