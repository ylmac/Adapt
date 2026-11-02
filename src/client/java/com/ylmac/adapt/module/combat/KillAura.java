package com.ylmac.adapt.module.combat;

import com.ylmac.adapt.module.combat.KeyBindUtil;
import com.ylmac.adapt.module.combat.RandomUtil;
import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.AttackEntityEvent;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.event.impl.SendPositionEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.util.RotationUtil;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * KillAura — 自动攻击范围内最近实体。
 * <p>
 * 1. updateTargets：每 tick 找 range 内可攻击实体，按 距离/生命/hurtTime 排序，
 * FOV 过滤、穿墙过滤、玩家/怪物/动物/隐形 开关过滤、teammates 过滤
 * - Single：只打最近一个目标
 * - Switch：每次攻击切换目标
 * - Multiple：一 tick 内依次攻击所有目标
 * 3. 攻击前把玩家旋转转向目标（Rotation 模式）：
 * - Silent：SendPositionEvent 发包旋转（服务端看到瞄准目标，渲染不动）
 * - Legit：真实转头（平滑逼近）
 * 4. isAttackReady：攻击冷却（CPS 间隔）满足 + 目标在射程内 → doAttack：
 * spoofMouseOverTo 指向目标 + attack（swing 由 startAttack 自带）
 * <p>
 * - 点击模拟与 AutoClicker/Mimic 一致：{@code KeyMapping.click(keyAttack.key)}
 * （经 KeyMappingAccessor 访问器取 key），由本 tick 的 handleKeybinds 的
 * while (keyAttack.consumeClick) startAttack 消费 → 服务端发
 * ServerboundAttackPacket + 本地 Player.attack 结算（含暴击/横扫/冷却重置）。
 * - startAttack 需要 {@code mc.hitResult} 为 EntityHitResult 且非空（已反汇编
 * 确认：ENTITY 分支 gameMode.attack(player, hitResult.getEntity)）。
 * 因此攻击前把 mc.hitResult 临时替换成指向目标的 EntityHitResult
 *：在 ClientTickEvent.Pre（tick HEAD，先于
 * handleKeybinds）捏造 hitResult 并注入点击，在 ClientTickEvent.Post
 * （tick TAIL，晚于 handleKeybinds）恢复 — 保证本 tick 的消费读到目标。
 * - 旋转：Rotation Mode 决定瞄准方式—
 * - Silent：客户端视角不动，发包旋转（SendPositionEvent）让服务端看到
 * 瞄准目标
 * - Legit：真实转头（平滑逼近）
 * - 目标遍历：{@code mc.level.entitiesForRendering}（ClientLevel 公开迭代器）。
 * <p>
 * 依赖：AttackEntityEventMixin（startAttack HEAD 触发攻击事件，本模块监听
 * 维护 lastAttackTime；Criticals 模块监听同源事件）。
 */
public class KillAura extends Module {

 /** 目标排序方式 */
    private final ChoiceSetting sorting;

 /** Attack Mode */
    private final ChoiceSetting attackMode;
 /** Switch 模式：切换间隔 */
    private final IntSetting switchDelay;
 /** Switch 模式：最近打过的目标 */
    private final java.util.ArrayDeque<LivingEntity> switchHistory = new java.util.ArrayDeque<>();
 /** Switch 历史上限 */
    private static final int SWITCH_HISTORY_MAX = 5;

 /** Rotation 模式 */
    private final ChoiceSetting rotationMode;
    /** Legit 旋转速度（度/tick， AimAssist smooth 数学） */
    private final DoubleSetting rotationSpeed;

    /** 目标筛选 */
    private final DoubleSetting range;
    private final DoubleSetting fov;
    private final BoolSetting players;
    private final BoolSetting mobs;
    private final BoolSetting animals;
    private final BoolSetting invisibles;
    private final BoolSetting throughWalls;
    private final BoolSetting playerTeammates;

    /** 攻击 */
    private final IntSetting minCps;
    private final IntSetting maxCps;
    private final BoolSetting keepSprint;
    /**
 * 移动修正：静默旋转把服务器看到的偏航改成了瞄准目标的角度，若不修正
 * RotationComponent 统一处理，这里与 Scaffold 共用 RotationUtil）。
 */
    private final BoolSetting movementCorrection;
 /** New Universal Keep Sprint */
    private final BoolSetting newUniversalKeepSprint;

 // 内部状态
    private LivingEntity target;
    private final List<LivingEntity> targets = new ArrayList<>();
    private long nextAttackTime = 0L;
    private long lastAttackTime = 0L;

    /** 攻击 tick 内被捏造的 hitResult 备份（Pre 捏造 → Post 恢复） */
    private HitResult hitResultBackup;
    private boolean hitResultSpoofed;

    /** 静默模式：攻击前的玩家真实旋转备份（SendPosition 事件使用） */
    private float lastYaw;
    private float lastPitch;

 /** Switch/Multiple 模式：目标轮换索引 */
    private int targetIndex;
    /** Switch 模式：切换冷却 tick */
    private int switchCooldown;

    /** Legit 模式：平滑插值中间值（smooth 数学） */
    private float smoothYaw;
    private float smoothPitch;
    private boolean smoothInitialized;

    public KillAura() {
        super("killaura", "KillAura", Category.COMBAT);
        // 模块列表可见（此前漏调 setDefaultHidden(false)，hidden 默认 true →
        // 开启后 HUD 模块列表里看不到，用户以为没生效）
        setDefaultHidden(false);
 // 设置
        sorting = choiceSetting("sorting", "Distance",
                List.of("Distance", "Health", "Hurt Time"));
        attackMode = choiceSetting("Attack Mode", "Single",
                List.of("Single", "Switch", "Multiple"));
        switchDelay = intSetting("Switch Delay", 2, 0, 10, 1,
                () -> "Switch".equals(attackMode.getValue()));
        rotationMode = choiceSetting("Rotation Mode", "Silent",
                List.of("Silent", "Legit"));
        rotationSpeed = doubleSetting("Rotation Speed", 10.0, 1.0, 30.0, 0.5,
                () -> "Legit".equals(rotationMode.getValue()));
        range = doubleSetting("range", 3.0, 1.0, 6.0, 0.1);
        fov = doubleSetting("fov", 360.0, 0.0, 360.0, 1.0);
        players = boolSetting("players", true, () -> true);
        mobs = boolSetting("mobs", true, () -> true);
        animals = boolSetting("animals", false, () -> true);
        invisibles = boolSetting("invisibles", false, () -> true);
        throughWalls = boolSetting("through-walls", false, () -> true);
        playerTeammates = boolSetting("player-teammates", true, () -> true);
        minCps = intSetting("min-cps", 8, 1, 20, 1);
        maxCps = intSetting("max-cps", 12, 1, 20, 1);
        keepSprint = boolSetting("keep-sprint", true, () -> true);
        newUniversalKeepSprint = boolSetting("new-universal-keep-sprint", false, () -> true);
        movementCorrection = boolSetting("movement-correction", true, () -> true);
    }

    @Override
    protected void onEnable() {
        target = null;
        targets.clear();
        nextAttackTime = 0L;
        hitResultSpoofed = false;
        targetIndex = 0;
        switchCooldown = 0;
        smoothInitialized = false;
    }

    @Override
    protected void onDisable() {
        target = null;
        targets.clear();
        switchHistory.clear();
        restoreHitResult();
    }

    /** 记录最近一次真实攻击（供 Criticals 等模块参考） */
    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.getEntity() == target) {
            lastAttackTime = System.currentTimeMillis();
        }
        // keep-sprint：攻击后保持疾跑（攻击结算会 setSprinting(false)，
        if (keepSprint.getValue() && event.getPlayer() == mc.player
                && mc.player.isSprinting() && mc.player.getVehicle() == null) {
            mc.player.setSprinting(true);
        }
    }

    @EventHandler
    private void onPreTick(ClientTickEvent.Pre event) {
        if (nullCheck() || mc.gui.screen() != null) {
            target = null;
            targets.clear();
            restoreHitResult();
            return;
        }

 // 1. 找目标
        updateTargets();
        if (targets.isEmpty()) {
            target = null;
            restoreHitResult();
            return;
        }

 // 2. 按 Attack Mode 锁定目标
        long now = System.currentTimeMillis();
        switch (attackMode.getValue()) {
            case "Switch" -> {
 // 每次冷却结束切换一个目标
                if (switchCooldown > 0) {
                    switchCooldown--;
                }
                if (switchCooldown <= 0 && now >= nextAttackTime) {
                    targetIndex = (targetIndex + 1) % targets.size();
                    switchCooldown = switchDelay.getValue();
                }
                if (targetIndex >= targets.size()) {
                    targetIndex = 0;
                }
                target = targets.get(targetIndex);
            }
            case "Multiple" -> {
                // 目标列表逐个攻击（本次 tick 内依次处理，见 doMultipleAttack）
                target = targets.get(0); // 冷却/旋转以最近目标为准
            }
            default -> {
 // Single：最近目标
                target = targets.get(0);
            }
        }
        if (target == null) {
            restoreHitResult();
            return;
        }

 // 3. 攻击冷却
        if (now < nextAttackTime) {
 // 冷却未到：仅保持瞄准
            rotateToTarget(target, now);
            return;
        }

 // 4. 攻击
        if ("Multiple".equals(attackMode.getValue())) {
            if (doMultipleAttack(now)) {
                nextAttackTime = now + getNextAttackDelay();
            } else {
                rotateToTarget(target, now);
            }
        } else {
            if (doAttack(target, now)) {
                nextAttackTime = now + getNextAttackDelay();
            } else {
                // 攻击未触发（目标出射程等）仍保持瞄准
                rotateToTarget(target, now);
            }
        }
    }

    /** tick 末尾恢复被捏造的 hitResult（handleKeybinds 已消费点击） */
    @EventHandler
    private void onPostTick(ClientTickEvent.Post event) {
        restoreHitResult();
    }

    /**
 * 移动修正：把 W/A/S/D 重新映射到"服务器看到的偏航"坐标系下，
 * 保证玩家按 W 时朝自己看的方向走（否则会朝目标方向走）。
 */
    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (!this.isEnabled() || !movementCorrection.getValue()) return;
        if (nullCheck() || this.target == null) return;
        RotationUtil.fixMovement(event, mc.player, this.smoothYaw);
    }

    /**
 * 发包旋转（Silent 模式核心）：服务器看到的旋转 = 瞄准目标的旋转，
 * 渲染视角不动。LocalPlayer.sendPosition 由 LocalPlayerSendPositionMixin
 * 发布本事件，改 yaw/pitch 后写回 player，sendPosition 构造的移动包即带新值
 */
    @EventHandler
    private void onSendPosition(SendPositionEvent event) {
        if (!this.isEnabled() || this.target == null) return;
        if (!"Silent".equals(rotationMode.getValue())) return;
        // 静默：发包旋转 = 目标方向（直接瞄准，避免攻击 miss）
        float[] rot = getRotations(this.target);
        event.setYaw(rot[0]);
        event.setPitch(rot[1]);
    }

        private long getNextAttackDelay() {
        long cps = RandomUtil.nextLong(minCps.getValue(), maxCps.getValue());
        return (long) (1000.0 / (cps * 1.5));
    }

 /** 更新目标列表 */
    private void updateTargets() {
        targets.clear();
        double r = range.getValue();
        if (mc.level == null) return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isValidTarget(living)) continue;
            double dist = mc.player.distanceToSqr(living.getX(), living.getY(), living.getZ());
            if (dist > r * r) continue;

 // FOV 过滤
            if (fov.getValue() < 360.0) {
                double angle = getAngleDifference(mc.player.getYRot(), getYawToEntity(living));
                if (Math.abs(angle) > fov.getValue() / 2.0) continue;
            }

 // 穿墙过滤
            if (!throughWalls.getValue() && !mc.player.hasLineOfSight(living)) continue;

            targets.add(living);
        }

 // 排序
        switch (sorting.getValue()) {
            case "Health" -> targets.sort(Comparator.comparingDouble(LivingEntity::getHealth));
            case "Hurt Time" -> targets.sort(Comparator.comparingInt(l -> l.hurtTime));
            default -> targets.sort(Comparator.comparingDouble(l ->
                    mc.player.distanceToSqr(l.getX(), l.getY(), l.getZ())));
        }

 // Switch 模式：排除最近打过的目标，
 // 空列表时回退全列表
        if ("Switch".equals(attackMode.getValue()) && !switchHistory.isEmpty()) {
            List<LivingEntity> filtered = new java.util.ArrayList<>(targets);
            filtered.removeIf(switchHistory::contains);
            if (!filtered.isEmpty()) {
                targets.clear();
                targets.addAll(filtered);
            }
        }
    }

 /** 目标有效性 */
    private boolean isValidTarget(LivingEntity living) {
        if (living == mc.player) return false;
        if (living == mc.player.getVehicle()) return false;
        if (mc.getCameraEntity() != null && living == mc.getCameraEntity()) return false;
        if (!living.isAlive() || living.isDeadOrDying()) return false;
        if (!invisibles.getValue() && living.isInvisible()) return false;
        // AntiBot 模块对 KillAura 无效，打假人/克隆时过滤不掉）
        AntiBot antiBot = AntiBot.get();
        if (antiBot != null && antiBot.isBot(living)) return false;

        if (living instanceof Player player) {
            if (!players.getValue()) return false;
            if (!playerTeammates.getValue() && isTeammate(player)) return false;
        } else if (living instanceof Enemy || living instanceof Mob) {
            if (!mobs.getValue()) return false;
        } else if (living instanceof Animal) {
            if (!animals.getValue()) return false;
        } else {
            // 其他活体（盔甲架等）默认按 mobs 开关
            if (!mobs.getValue()) return false;
        }
        return true;
    }

 /** 简单 teammate 判定：同队伍对象 */
    private boolean isTeammate(Player player) {
        if (player.getTeam() == null || mc.player.getTeam() == null) return false;
        return player.getTeam() == mc.player.getTeam();
    }

 /** 执行攻击 */
    private boolean doAttack(LivingEntity living, long now) {
 // 攻击距离再次确认
        if (mc.player.distanceToSqr(living.getX(), living.getY(), living.getZ())
                > range.getValue() * range.getValue()) {
            return false;
        }

 // 旋转到目标
        rotateToTarget(living, now);

        // spoofMouseOverTo：把 mc.hitResult 指向目标（startAttack 依赖，
        // tick 内 handleKeybinds 消费点击时读取；Post 事件里恢复）
        if (!hitResultSpoofed) {
            hitResultBackup = mc.hitResult;
            mc.hitResult = new EntityHitResult(living, living.getEyePosition());
            hitResultSpoofed = true;
        }

 // New Universal Keep Sprint
        if (newUniversalKeepSprint.getValue()) {
            mc.player.setSprinting(false);
        }
        // handleKeybinds 消费 → startAttack → gameMode.attack 发包 + 结算 + swing）
        KeyBindUtil.pressKeyOnce(mc.options.keyAttack);
        if (newUniversalKeepSprint.getValue()) {
            mc.player.setSprinting(true);
        }
 // Switch 历史记录
        switchHistory.addLast(living);
        while (switchHistory.size() > SWITCH_HISTORY_MAX) {
            switchHistory.removeFirst();
        }
        return true;
    }

 /** Multiple 模式：一 tick 内轮流攻击所有在射程内的目标 */
    private boolean doMultipleAttack(long now) {
        boolean attackedAny = false;
        for (LivingEntity living : targets) {
            if (mc.player.distanceToSqr(living.getX(), living.getY(), living.getZ())
                    > range.getValue() * range.getValue()) {
                continue;
            }
            // 每个目标单独捏造 hitResult（每次点击都指向对应目标）
            hitResultBackup = mc.hitResult;
            mc.hitResult = new EntityHitResult(living, living.getEyePosition());
            hitResultSpoofed = true;
            // 静默：发包旋转指向该目标（每击都准确命中）
            if (isSilentRotation()) {
                rotateToTarget(living, now);
            }
            KeyBindUtil.pressKeyOnce(mc.options.keyAttack);
            attackedAny = true;
        }
        return attackedAny;
    }

    /** 是否使用静默旋转（Rotation Mode = Silent） */
    private boolean isSilentRotation() {
        return "Silent".equals(rotationMode.getValue());
    }

 /** 旋转到目标 */
    private void rotateToTarget(LivingEntity living, long now) {
        float[] rotations = getRotations(living);
        if (isSilentRotation()) {
            // 静默：客户端视角不动，发包旋转在 onSendPosition（服务器看到瞄准）
            lastYaw = mc.player.getYRot();
            lastPitch = mc.player.getXRot();
            // 服务器本 tick 将看到的角度（移动修正的基准）
            smoothYaw = rotations[0];
            smoothPitch = rotations[1];
            // 备份后立即同步一次服务端旋转（攻击前先对准，确保命中判定一致）。
            // 这一发是必要的兜底：sendPosition 只在移动时调用，站着不动时
            // onSendPosition 不会触发，服务器看到的旋转会一直是旧的 → 攻击 miss。
            if (mc.player.connection != null) {
                mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        rotations[0], rotations[1],
                        mc.player.onGround(), mc.player.horizontalCollision));
            }
        } else {
            // Legit：真实转头（平滑逼近）。
            // ⚠ 基准每 tick 取玩家的**当前**角度，不能用模块自己维护的累加值：
            // 玩家中途用鼠标转头后累加值还停在旧角度，模块就会从旧角度一路把
            // 视角拽回去 — 表现正是"人明明在前面却绕一大圈才瞄上"。
            float[] smoothed = RotationUtil.smoothApproach(
                    mc.player.getYRot(), mc.player.getXRot(),
                    rotations[0], rotations[1],
                    rotationSpeed.getValue().floatValue());
            smoothYaw = smoothed[0];
            smoothPitch = smoothed[1];
            mc.player.setYRot(smoothed[0]);
            mc.player.setXRot(smoothed[1]);
        }
    }

    /** 恢复被捏造的 hitResult（Post tick / 模块关闭 / 无目标时） */
    private void restoreHitResult() {
        if (hitResultSpoofed) {
            mc.hitResult = hitResultBackup;
            hitResultSpoofed = false;
            hitResultBackup = null;
        }
    }

 /** 计算瞄准目标的旋转 */
    private float[] getRotations(LivingEntity living) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 targetPos = living.getBoundingBox().getCenter();
        double dx = targetPos.x() - eye.x();
        double dy = targetPos.y() - eye.y();
        double dz = targetPos.z() - eye.z();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0f, 90.0f)};
    }

    /** 目标相对玩家的偏航角（FOV 判定用） */
    private float getYawToEntity(Entity entity) {
        double dx = entity.getX() - mc.player.getX();
        double dz = entity.getZ() - mc.player.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

 /** 角度差 */
    private float getAngleDifference(float a, float b) {
        return Mth.wrapDegrees(b - a);
    }

    /** 模块信息 tag（CPS 区间，与 AutoClicker 一致） */
    @Override
    public String getInfo() {
        return minCps.getValue() == maxCps.getValue()
                ? String.valueOf(minCps.getValue())
                : String.format("%d-%d", minCps.getValue(), maxCps.getValue());
    }
}
