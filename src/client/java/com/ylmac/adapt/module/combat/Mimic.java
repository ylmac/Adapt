package com.ylmac.adapt.module.combat;

import com.ylmac.adapt.module.combat.KeyBindUtil;
import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.command.ChatUtil;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mimic 模仿目标旋转/点击。
 * <p>
 * 原版由两个子模式组成（RotationsMimic / ClickerMimic），本实现合并为一个模块，
 * 两个开关分别控制：
 * <ol>
 * <li><b>Rotations 旋转模仿</b>：找 6 格内最近目标，把玩家视角反向（目标朝向
 * Animation(Easing.LINEAR, 150ms)），再按鼠标灵敏度换算的 turn 增量
 * 旋转玩家。</li>
 * <li><b>Clicker 点击模仿</b>：记录目标每次挥臂动画（ClientboundAnimatePacket
 * SWING_MAIN_HAND）的间隔（tick），按同样节奏回放左键点击 — 模仿目标
 * 的点击速度/节奏。记录按目标名分开，潜行（Shift）清空记录。</li>
 * </ol>
 * <ul>
 * <li>动画包：26.2 挥臂 = {@link ClientboundAnimatePacket}（getAction ==
 * <li>点击回放：26.2 瞬时点击 = {@code KeyMapping.click(Key)}（KeyBindUtil.pressKeyOnce，
 * 下个 handleKeybinds 的 while 循环消费 → startAttack，与 AutoClicker 同款）。</li>
 * <li>旋转：玩家旋转字段 yRot/xRot 公开可变（javap 确证）；鼠标灵敏度换算
 * 后用 {@code player.turn} 应用
 * （Entity.turn 已 javap 确证：参数 ×0.15 加到 xRot/yRot 并更新 xRotO/yRotO
 * 插值起点）。渲染帧钩子 = GameRendererMimicMixin（renderLevel RETURN
 * 回调本模块，与 AimBacktrack 的 GameRendererAimBacktrackMixin 同款方案；
 * Render2DEvent.Level 在 26.2 未被派发，不能用于逐帧驱动）。</li>
 * </ul>
 * 设置：Rotations（旋转模仿开关）、Clicker（点击模仿开关）、Max Delay（回放最大间隔）。
 */
public class Mimic extends Module {

    /** 单例引用（GameRendererMimicMixin 逐帧回调） */
    private static Mimic instance;

    public static Mimic get() {
        return instance;
    }

    private final com.ylmac.adapt.module.settings.impl.BoolSetting rotations;
    private final com.ylmac.adapt.module.settings.impl.BoolSetting clicker;
    private final com.ylmac.adapt.module.settings.impl.IntSetting maxDelay;

 // ── Rotations 状态──
    private float animYaw;
    private float animPitch;      // 动画当前 pitch
    private float deltaYaw;
    private float deltaPitch;
    private long lastRenderTime;

 // ── Clicker 状态──
 /** 目标名 → 点击间隔记录 */
    private final Map<String, Recorded> recorded = new HashMap<>();
    /** 当前回放队列（从记录目标取的间隔 tick 列表） */
    private final List<Integer> replayQueue = new ArrayList<>();
    private long nextClickTime;   // 下次回放点击时刻（ms）

    /** 单个目标的点击记录 */
    private static final class Recorded {
        final List<Integer> delays = new ArrayList<>();
        int lastTick;

        Recorded(int lastTick) {
            this.lastTick = lastTick;
        }
    }

    public Mimic() {
        super("mimic", "Mimic", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        rotations = boolSetting("Rotations", true, () -> true);
        clicker = boolSetting("Clicker", true, () -> true);
        maxDelay = intSetting("Max Delay", 450, 50, 2000, 50,
                () -> clicker.getValue());
    }

    @Override
    protected void onEnable() {
        animYaw = mc.player == null ? 0 : mc.player.getYRot();
        animPitch = mc.player == null ? 0 : mc.player.getXRot();
        deltaYaw = 0;
        deltaPitch = 0;
        nextClickTime = 0;
        lastRenderTime = 0;
    }

    @Override
    protected void onDisable() {
        recorded.clear();
        replayQueue.clear();
        deltaYaw = 0;
        deltaPitch = 0;
        lastRenderTime = 0;
    }

 /** 旋转模仿：目标反向视角 + 动画平滑 + 增量计算 */
    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (!rotations.getValue()) {
            animYaw = mc.player.getYRot();
            animPitch = mc.player.getXRot();
            deltaYaw = 0;
            deltaPitch = 0;
            return;
        }

        LivingEntity target = nearestTarget(6.0);
        if (target != null && mc.options.keyUse.isDown()) {
 // 目标反向视角
            float targetYaw = target.getYRot() - 180.0F;
            float targetPitch = -target.getXRot();

 // 动画平滑逼近
            animYaw = approach(animYaw, targetYaw, 0.2F);
            animPitch = approach(animPitch, targetPitch, 0.2F);

 // 待应用增量
            float yawDelta = net.minecraft.util.Mth.wrapDegrees(animYaw - mc.player.getYRot());
            float pitchDelta = animPitch - mc.player.getXRot();
            yawDelta += (float) ((Math.random() - 0.5) * yawDelta / 10.0);
            pitchDelta += (float) ((Math.random() - 0.5) * pitchDelta / 10.0);
            deltaYaw = yawDelta;
            deltaPitch = pitchDelta;
        } else {
 // 无目标/未按住右键：平滑回正
            animYaw = mc.player.getYRot();
            animPitch = mc.player.getXRot();
            deltaYaw = 0;
            deltaPitch = 0;
        }
    }

    /**
 * 渲染帧：把旋转增量按鼠标灵敏度换算成 turn 增量。
 * GameRendererMimicMixin 在 renderLevel RETURN 逐帧回调。
 */
    public void onRenderFrame() {
        if (!isEnabled()) return;
        if (mc.player == null) return;
        if (!rotations.getValue() || (deltaYaw == 0 && deltaPitch == 0)) {
            return;
        }

        double sens = mc.options.sensitivity().get();
        double d2 = Math.pow(sens * 0.6 + 0.2, 3.0) * 8.0;
        if (d2 <= 0.0) return;

 // 增量 → 灵敏度步数 → 每帧施加角度
        double perFrameYaw = (deltaYaw / d2) * (1.0 / d2) * 6.6666665F;
        double perFramePitch = (deltaPitch / d2) * (1.0 / d2) * 6.6666665F;

 // 帧间隔归一
        long now = System.currentTimeMillis();
        double dt = lastRenderTime == 0 ? 16.0 : Math.max(1.0, now - lastRenderTime);
        lastRenderTime = now;
        double frames = dt / 16.0;
        double applyYaw = perFrameYaw * frames;
        double applyPitch = perFramePitch * frames;

        // 应用旋转（Entity.turn 已 javap 确证：参数 ×0.15 加到 xRot/yRot 并更新插值起点；
        boolean invert = mc.options.invertMouseY().get();
        mc.player.turn(applyYaw, applyPitch * (invert ? 1.0 : -1.0));

        // 已消耗：按帧数衰减增量（下一帧继续剩余量，实现平滑旋转）
        deltaYaw -= (float) applyYaw;
        deltaPitch -= (float) applyPitch;
        if (Math.abs(deltaYaw) < 0.01F && Math.abs(deltaPitch) < 0.01F) {
            deltaYaw = 0;
            deltaPitch = 0;
        }
    }

 /** 点击节奏记录：监听目标挥臂动画包 */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!clicker.getValue() || mc.player == null || mc.level == null) return;
        if (!(event.getPacket() instanceof ClientboundSwingAnimationPacket packet)) return;
        if (packet.hand() != InteractionHand.MAIN_HAND) return;

 // 找发出动画的实体
        Entity entity = mc.level.getEntity(packet.entityId());
        if (entity == null || entity == mc.player) return;
        if (!(entity instanceof LivingEntity living)) return;
        String name = living.getName().getString();

 // 记录本目标上次挥臂至今的 tick 间隔
        int now = mc.player.tickCount;
        Recorded rec = recorded.computeIfAbsent(name, n -> new Recorded(now));
        if (now - rec.lastTick > 0) {
            rec.delays.add(now - rec.lastTick);
        }
        rec.lastTick = now;
    }

 /** 点击节奏回放：按记录的间隔节奏补发左键 */
    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (!clicker.getValue()) return;
        if (!mc.options.keyUse.isDown()) return;

 // 潜行清空记录
        if (mc.options.keyShift.isDown()) {
            recorded.clear();
            replayQueue.clear();
            return;
        }

        if (System.currentTimeMillis() < nextClickTime) {
            return;
        }

 // 取任意一个记录过的目标
        if (recorded.isEmpty()) {
            nextClickTime = System.currentTimeMillis() + 250;
            return;
        }
        if (replayQueue.isEmpty()) {
            Recorded rec = recorded.values().iterator().next();
            if (rec.delays.isEmpty()) {
                recorded.values().remove(rec);
                nextClickTime = System.currentTimeMillis() + 250;
                return;
            }
            replayQueue.addAll(rec.delays);
            rec.delays.clear();
        }

 // 弹出下一个间隔
        int delayTicks;
        do {
            if (replayQueue.isEmpty()) {
                nextClickTime = System.currentTimeMillis() + 250;
                return;
            }
            delayTicks = replayQueue.remove(0);
        } while (delayTicks <= 0 || delayTicks * 50L > maxDelay.getValue());

 // 回放：按一半间隔节奏点击
        nextClickTime = System.currentTimeMillis() + delayTicks * 50L / 2L;
        KeyBindUtil.pressKeyOnce(mc.options.keyAttack);
    }

 /** 找 range 内最近的目标 */
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

 /** 线性逼近 */
    private static float approach(float current, float target, float factor) {
        float diff = net.minecraft.util.Mth.wrapDegrees(target - current);
        return current + diff * factor;
    }

    @Override
    public String getInfo() {
        return rotations.getValue() && clicker.getValue() ? "Rot+Click"
                : rotations.getValue() ? "Rot" : clicker.getValue() ? "Click" : null;
    }
}
