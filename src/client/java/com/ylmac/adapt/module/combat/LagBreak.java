package com.ylmac.adapt.module.combat;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.AttackEntityEvent;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.GameJoinedEvent;
import com.ylmac.adapt.event.impl.GameLeftEvent;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LagBreak 伪延迟。
 * <p>
 * 原版原理：战斗期间短暂"卡住"自己 — 拦截（Blink）移动包，让服务器以为玩家
 * 停在原地（玩家在服务器视角"冻结"），同时本地仍在移动/寻路；到达释放条件
 * （目标靠近 / 攻击发出 / 超过最大延迟）后一次性补发全部移动包，服务器视角
 * 玩家瞬间"瞬移"到新位置 — 攻击判定/命中计算因此产生偏差（经典假延迟）。
 * <p>
 * <ol>
 * {@link ServerboundMovePlayerPacket}（StatusOnly/Pos/Rot/PosRot 子类），
 * 在 {@link PacketEvent.Send} 拦截暂存；补发走 {@code connection.send(packet, null)}
 * 静默直发。</li>
 * 26.2 攻击 = {@link ServerboundAttackPacket}，由 MultiPlayerGameMode.attack 发出，
 * 经 PlayerAttackMixin 派发 {@link AttackEntityEvent} — 直接监听攻击事件。</li>
 * 默认 {keyboard.menu}（Escape）不干扰操作，可改。</li>
 * </ol>
 * 设置：Max Lag Ticks、Range（触发距离 10）、
 * Minimum Range（目标过近立即释放 0）、Dispatch On Attack（攻击时释放，默认开）、
 * Dispatch On Hurtime（受伤时释放，默认关）、Cancel Key（取消键）。
 */
public class LagBreak extends Module {

 /** 释放动画 tick 数 */
    private static final int RELEASE_TICKS = 6;

    private final com.ylmac.adapt.module.settings.impl.IntSetting maxLagTicks;
    private final com.ylmac.adapt.module.settings.impl.DoubleSetting range;
    private final com.ylmac.adapt.module.settings.impl.DoubleSetting minimumRange;
    private final com.ylmac.adapt.module.settings.impl.BoolSetting dispatchOnAttack;
    private final com.ylmac.adapt.module.settings.impl.BoolSetting dispatchOnHurtime;
    private final com.ylmac.adapt.module.settings.impl.KeybindSetting cancelKey;

 // 内部状态
    private int blinkTicks;          // 当前已闪烁（拦截）的 tick 数
    private boolean pendingDispatch; // 攻击等触发后待释放标志
    private int releaseTicks;        // 释放防抖剩余 tick
    private int blinkStartTick;      // 本次闪烁开始的玩家 tickCount
    private final List<ServerboundMovePlayerPacket> heldPackets = new ArrayList<>();
    private boolean dispatching;     // 补发中（防静默重发被自己拦截）

    public LagBreak() {
        super("lagbreak", "LagBreak", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        maxLagTicks = intSetting("Max Lag Ticks", 9, 1, 40, 1);
        range = doubleSetting("Range", 10.0, 8.0, 15.0, 0.1);
        minimumRange = doubleSetting("Minimum Range", 0.0, 0.0, 3.0, 0.1);
        dispatchOnAttack = boolSetting("Dispatch On Attack", true, () -> true);
        dispatchOnHurtime = boolSetting("Dispatch On Hurtime", false, () -> true);
        cancelKey = keybindSetting("Cancel Key", com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.escape").getValue());
    }

    @Override
    protected void onEnable() {
        resetState();
        if (mc.player != null) {
            blinkStartTick = mc.player.tickCount;
        }
    }

    @Override
    protected void onDisable() {
        dispatchBlink(); // 关闭：立即补发全部暂存包（BlinkComponent.dispatch）
    }

 /** 客户端 tick：闪烁 tick 计数 */
    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (cancelKeyDown()) {
            cancelBlink();
            return;
        }
        blinkTicks = mc.player.tickCount - blinkStartTick - 1;
        if (blinkTicks > 0 && releaseTicks > 0) {
            releaseTicks = 0; // 释放防抖期间重新开始闪烁 → 复位防抖
        }
    }

 /** 玩家 tick 后：闪烁判定 + 释放判定 */
    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (cancelKeyDown()) {
            cancelBlink();
            return;
        }

        LivingEntity target = nearestTarget(range.getValue());
        boolean close = target != null
                && distanceTo(target) <= 3.0 + playerSpeed() / 2.0;   // bl = 3 + speed/2
        boolean looking = target != null
                && distanceTo(target) <= 5.0
                && isLookingAt(target, 12.0F, 20.0F);                 // bl2 = 5 + 朝向
        int randomTicks = target != null
                ? com.ylmac.adapt.module.combat.RandomUtil.nextInt(9, 16)
                : com.ylmac.adapt.module.combat.RandomUtil.nextInt(10, 18);

 // 强制释放条件
        boolean shouldDispatch = blinkTicks >= maxLagTicks.getValue()
                || !isMoving()
                || target == null
                || distanceTo(target) > range.getValue()
                || mc.player.getHealth() < 2.0F
                || cancelKeyDown();

        // dispatchOnAttack 关闭时：目标逼近/随机 tick 也触发释放
        if (!dispatchOnAttack.getValue()) {
            if (!shouldDispatch && close) {
                shouldDispatch = true;
            }
            if (!shouldDispatch && blinkTicks >= randomTicks && !looking) {
                shouldDispatch = true;
            }
        }

        if (shouldDispatch) {
            dispatchBlink();
        }

 // 目标进入 Minimum Range：立即释放
        if (target != null && distanceTo(target) < minimumRange.getValue()) {
            dispatchBlink();
        }

 // 受伤释放
        if (dispatchOnHurtime.getValue() && mc.player.hurtTime > 0) {
            dispatchBlink();
        }

        // 攻击触发的待释放标志（onAttack 置位，这里统一走 dispatchBlink 防抖）
        if (pendingDispatch) {
            pendingDispatch = false;
            dispatchBlink();
        }

 // 释放防抖递减
        if (releaseTicks > 0) {
            releaseTicks--;
        }
    }

 /** 攻击时待释放 */
    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.getPlayer() != mc.player) return;
        if (!dispatchOnAttack.getValue()) return;
        if (blinkTicks <= 0) return;
        pendingDispatch = true;
    }

 /** 移动包拦截：闪烁期间暂存 */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (dispatching) return;
        if (blinkTicks <= 0) return; // 未在闪烁 → 放行
        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            heldPackets.add(packet);
            event.cancel();
        }
    }

 /** 世界切换：清空暂存并复位 */
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        resetState();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        resetState();
    }

 /** 释放：补发全部暂存移动包，进入防抖期 */
    private void dispatchBlink() {
        if (blinkTicks > 0) {
            releaseTicks = RELEASE_TICKS;
        }
        flushHeld();
        if (mc.player != null) {
            blinkStartTick = mc.player.tickCount;
        }
        blinkTicks = 0;
        pendingDispatch = false;
    }

 /** 取消闪烁（用户按取消键）：补发 + 复位防抖 */
    private void cancelBlink() {
        if (blinkTicks > 0) {
            flushHeld();
        }
        releaseTicks = 0;
        blinkTicks = 0;
        pendingDispatch = false;
        if (mc.player != null) {
            blinkStartTick = mc.player.tickCount;
        }
    }

 /** 静默补发全部暂存移动包 */
    private void flushHeld() {
        if (heldPackets.isEmpty()) {
            return;
        }
        dispatching = true;
        try {
            Connection connection = getConnection();
            if (connection == null) {
                heldPackets.clear();
                return;
            }
            for (ServerboundMovePlayerPacket packet : heldPackets) {
                connection.send(packet, null);
            }
        } finally {
            dispatching = false;
            heldPackets.clear();
        }
    }

 /** 取消键是否按下 */
    private boolean cancelKeyDown() {
        if (mc.player == null) return false;
        int key = cancelKey.getValue();
        if (key <= 0) return false;
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(key);
    }

 /** 玩家当前水平速度 */
    private double playerSpeed() {
        Vec3 m = mc.player.getDeltaMovement();
        return Math.hypot(m.x(), m.z());
    }

    private boolean isMoving() {
        return playerSpeed() > 0.02;
    }

 /** 找 range 内最近目标并按距离排序 */
    private LivingEntity nearestTarget(double range) {
        List<LivingEntity> list = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living
                    && living != mc.player
                    && living.isAlive()
                    && !living.isInvisible()
                    && living.isPickable()) {
                list.add(living);
            }
        }
        if (list.isEmpty()) {
            return null;
        }
        list.sort(Comparator.comparingDouble(this::distanceTo));
        return distanceTo(list.get(0)) <= range ? list.get(0) : null;
    }

 /** 玩家眼睛到目标包围盒的距离 */
    private double distanceTo(LivingEntity target) {
        return com.ylmac.adapt.module.combat.ClickRotation.distanceToEntity(target);
    }

 /** 玩家是否面向目标 */
    private boolean isLookingAt(LivingEntity target, float yawLimit, float pitchLimit) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 targetEye = target.getEyePosition();
        double dx = eye.x - targetEye.x;
        double dz = eye.z - targetEye.z;
        double dy = eye.y - targetEye.y;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yawTo = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitchTo = (float) (-Math.toDegrees(Math.atan2(dy, dist)));
        float yawDiff = Math.abs(net.minecraft.util.Mth.wrapDegrees(target.getYRot() - yawTo));
        float pitchDiff = Math.abs(net.minecraft.util.Mth.wrapDegrees(target.getXRot() - pitchTo));
        return yawDiff <= yawLimit && pitchDiff <= pitchLimit;
    }

    private Connection getConnection() {
        return mc.getConnection() == null ? null : mc.getConnection().getConnection();
    }

    private void resetState() {
        heldPackets.clear();
        blinkTicks = 0;
        pendingDispatch = false;
        releaseTicks = 0;
        if (mc.player != null) {
            blinkStartTick = mc.player.tickCount;
        }
    }

    /** 是否正在闪烁（供其他模块/命令查询） */
    public boolean isBlinking() {
        return blinkTicks > 0;
    }

    @Override
    public String getInfo() {
        return isBlinking() ? blinkTicks + "t" : null;
    }
}
