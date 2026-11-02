package com.ylmac.adapt.module.ghost;

import com.ylmac.adapt.module.combat.RandomUtil;
import com.ylmac.adapt.command.FriendManager;
import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 平滑瞄准。Simple 按角差加速，Adaptive 按误差和提前量跟踪。
 * 每 tick 直接改视角（度），不经过鼠标灵敏度换算。
 */
public class AimAssist extends Module {

    // ---------- 模式（ModeValue Simple/Adaptive） ----------
    private final ChoiceSetting mode;
    /** 目标排序方式（Target mode） */
    private final ChoiceSetting targetMode;
    /** 最大距离（Distance，默认 5，范围 1~8） */
    private final DoubleSetting distance;
    /** 最大准星偏角（Max angle，默认 180，范围 1~360） */
    private final DoubleSetting maxAngle;
    /** 仅左键按住时瞄准（Require mouse down） */
    private final BoolSetting requireMouseDown;
    /** 忽略隐形目标（targetFilter invisibles） */
    private final BoolSetting ignoreInvisible;
    /** 忽略同队伍目标（targetFilter teammates） */
    private final BoolSetting ignoreTeammates;
    /** 水平转向速度（Horizontal speed） */
    private final DoubleSetting horizontalSpeed;
    /** 垂直转向速度（Vertical speed） */
    private final DoubleSetting verticalSpeed;
    /** 是否垂直瞄准（Aim vertically） */
    private final BoolSetting aimVertically;
    /** 背对目标横移时转向加速（Strafe increase） */
    private final BoolSetting strafeIncrease;
    /** 检查破方块（Check block break） */
    private final BoolSetting checkBlockBreak;
    /** 破方块白名单（Break blocks whitelist） */
    private final BoolSetting breakBlocksWhitelist;
    /** 限物品（Limit to items） */
    private final BoolSetting limitToItems;
    /** 瞄准点（Target area，Center / Closest） */
    private final ChoiceSetting targetArea;

    /** 破方块冷却（blockBreakCooldown 250 tick） */
    private int blockBreakCooldown = 0;

    // ---------- Simple 模式状态 ----------
    private LivingEntity target;
    private int randomOffsetX;
    private int randomOffsetY;
    private int driftX;
    private int driftY;
    private double driftTimer;
    private float horizontalMouseAccumulator;
    private float verticalMouseAccumulator;
    private float horizontalVelocity;
    private float verticalVelocity;
    private float horizontalVelocityBuffer;
    private float verticalVelocityBuffer;
    private int swapTickCounter;
    private int retargetCounter;
    private int sampleCounter;
    private boolean prevOnLeft;
    private boolean prevAbove;
    private double lastAngleDiff;
    private float yawBoost;
    private float pitchBoost;
    private double prevTargetX;
    private double prevTargetZ;

    // ---------- Adaptive 模式状态 ----------
    private static final float SNAP_ENTER_THRESHOLD = 5.0f;
    private static final float SNAP_EXIT_THRESHOLD = 7.0f;
    private float pitchVelocity;
    private float lastYawDiff;
    private double predictedZ;
    private float pendingPitch;
    private boolean aimPointInitialized;
    private float airFactor;
    private boolean predictionInitialized;
    private long lastFrameNanos;
    private double lastGroundEyeY;
    private float lastPlayerPitch;
    private float pitchBias;
    private float pendingYaw;
    private float overshoot;
    private float driftPos;
    private double leadY;
    private double leadX;
    private boolean yawSnapped;
    private long driftNextNanos;
    private double leadZ;
    private float pitchFlickTicks;
    private float lastPitchSign;
    private float aimStrength;
    private double aimX;
    private float driftVelocity;
    private float yawAccel;
    private double predictedY;
    private float verticalVelocityAdaptive;
    private float yawBias;
    private long noiseStartNanos;
    private float lastTargetPitch;
    private double lastEyeY;
    private float driftTarget;
    private float lastYawSign;
    private float driftNoise;
    private double predictedX;
    private float lastPlayerYaw;
    private float yawFlickTicks;
    private int targetSwitchTicks;
    private float pitchAccel;
    private boolean pitchSnapped;
    private float lastTargetYaw;
    private float yawVelocity;
    private double aimY;
    private float lastPitchDiff;
    private boolean initialized;
    private double aimZ;

    public AimAssist() {
        super("aimassist", "AimAssist", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);

        // Mode（Simple - Lightweight smooth aiming / Adaptive - Advanced tracking）
        mode = choiceSetting("模式", "Simple", List.of("Simple", "Adaptive"));
        // Target mode（Yaw / Distance / Armor / Threat / Health）
        targetMode = choiceSetting("目标模式", "Yaw",
                List.of("Yaw", "Distance", "Armor", "Threat", "Health"));
        // Distance 默认 5，范围 1~8
        distance = doubleSetting("距离", 5.0, 1.0, 8.0, 0.1);
        // Max angle 默认 180，范围 1~360
        maxAngle = doubleSetting("最大角度", 180.0, 1.0, 360.0, 1.0);
        // Require mouse down 默认开
        requireMouseDown = boolSetting("仅左键时瞄准", true);
        // targetFilter invisibles 默认开
        ignoreInvisible = boolSetting("忽略隐形", true);
        // targetFilter teammates 默认关
        ignoreTeammates = boolSetting("忽略队友", false);

        // 旋转设置（默认 1~10）
        horizontalSpeed = doubleSetting("水平速度", 5.0, 1.0, 10.0, 0.1);
        aimVertically = boolSetting("垂直瞄准", false);
        verticalSpeed = doubleSetting("垂直速度", 1.0, 1.0, 10.0, 0.1,
                () -> aimVertically.getValue());
        strafeIncrease = boolSetting("横移加速", false);
        // Check block break（破方块时不瞄准）
        checkBlockBreak = boolSetting("检查破方块", false);
        breakBlocksWhitelist = boolSetting("破方块白名单", false,
                () -> checkBlockBreak.getValue());
        // Limit to items（手持白名单物品才瞄准）
        limitToItems = boolSetting("限物品", false);
        // Target area（Center / Closest）
        targetArea = choiceSetting("瞄准点", "Center", List.of("Center", "Closest"));
    }

    @Override
    protected void onEnable() {
        resetAllState();
    }

    @Override
    protected void onDisable() {
        target = null;
        resetRotationState();
        resetAdaptiveState();
    }

    /** 模块信息 tag（当前模式 + 速度，: getDetailedSuffix） */
    @Override
    public String getInfo() {
        return mode.getValue() + " " + horizontalSpeed.getValue();
    }

    // ================= AimAssist.canAim =================

    /** 是否允许瞄准（canAim：破方块冷却 + 限物品） */
    private boolean canAim() {
        if (mc.player == null || mc.gameMode == null) return false;
        boolean checkCurrentBlock = checkBlockBreak.getValue();
        if (checkCurrentBlock && breakBlocksWhitelist.getValue()) {
            checkCurrentBlock = isBreakBlockItem(mc.player.getMainHandItem());
        }
        if (checkCurrentBlock) {
            // RayTraceUtil.o → mc.hitResult；aimingAtBlock = 准星指向方块
            boolean aimingAtBlock = mc.hitResult != null
                    && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
            if (aimingAtBlock) {
                blockBreakCooldown = 250;
                return false;
            }
            if (blockBreakCooldown > 0) {
                blockBreakCooldown--;
            }
            if (blockBreakCooldown > 0) {
                return false;
            }
        }
        return hasRequiredItem();
    }

    /** 破方块白名单物品（blockBreakItems 默认 pickaxes/shovels）。
 * 26.2 无 PickaxeItem/ShovelItem 类（工具直接继承 Item），用 descriptionId
 * （item.minecraft.iron_pickaxe）判断物品类型。 */
    private boolean isBreakBlockItem(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String id = stack.getItem().getDescriptionId();
        return id.contains("_pickaxe") || id.contains("_shovel");
    }

    /** 限物品检查（hasRequiredItem，默认 swords）。26.2 无 SwordItem 类，
 * 用 descriptionId（item.minecraft.iron_sword）判断。 */
    private boolean hasRequiredItem() {
        if (!limitToItems.getValue()) return true;
        if (mc.player == null) return false;
        net.minecraft.world.item.ItemStack stack = mc.player.getMainHandItem();
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem().getDescriptionId().contains("_sword");
    }

    // ================= 目标选择（AimAssist.findBestTarget + isValidTarget） =================

    /** 找出最佳目标（按 targetMode 排序后取第一个） */
    private LivingEntity findBestTarget() {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isValidTarget(living)) continue;
            targets.add(living);
        }
        if (targets.isEmpty()) {
            return null;
        }
        
        // / EntityHealthComparator
        switch (targetMode.getValue()) {
            case "Yaw" -> targets.sort(Comparator.comparingDouble(this::getAngleToTarget));
            case "Armor" -> targets.sort(Comparator.comparingDouble(this::getArmorValue));
            case "Threat" -> targets.sort(Comparator.comparingDouble(this::getThreatValue));
            case "Health" -> targets.sort(Comparator.comparingDouble(l -> l.getHealth()));
            default -> targets.sort(Comparator.comparingDouble(this::getDistanceToTarget));
        }
        return targets.get(0);
    }

    /** 目标有效性过滤（AimAssist.isValidTarget 一一对应） */
    private boolean isValidTarget(LivingEntity target) {
        if (target == null) return false;
        if (target == mc.player) return false;
        // 玩家自己的坐骑
        if (mc.player.getVehicle() != null && target == mc.player.getVehicle()) return false;
        // 摄像机实体
        if (mc.getCameraEntity() != null && target == mc.getCameraEntity()) return false;
        // 死亡/已移除（w$src$F$15l9epb <= 0 / M$src$Z$ff28xj）
        if (target.isDeadOrDying() || !target.isAlive() || target.isRemoved()) return false;
        // targetFilter invisibles
        if (target.isInvisible() && ignoreInvisible.getValue()) return false;
        // 距离（getDistanceToEntity >= distance）
        if (getDistanceToTarget(target) >= distance.getValue()) return false;
        // 角度（RotationUtil.a > maxAngle / 2）
        if (getAngleToTarget(target) > maxAngle.getValue() / 2.0) return false;
        // FriendManager.isFriend
        if (FriendManager.get().isFriend(target.getName().getString())) return false;
        // targetFilter teammates
        if (ignoreTeammates.getValue() && target instanceof Player other
                && other.getTeam() != null && other.getTeam() == mc.player.getTeam()) return false;
        return true;
    }

    // ================= 每 tick 驱动（worker 线程 runWorkerTick） =================

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;
        if (!this.isEnabled()) return;
        if (mc.gui.screen() != null) return;

        // 模式分发（mode == simpleRotation / adaptiveTargeting）
        if ("Simple".equals(mode.getValue())) {
            tickSimple();
        } else {
            tickAdaptive();
        }
    }

    // ================= Simple 模式 =================

    private void tickSimple() {
        if (mc.level == null || mc.player == null) return;
        if (!canAim()) {
            resetRotationState();
            return;
        }
        if (requireMouseDown.getValue() && !mc.options.keyAttack.isDown()) {
            target = null;
            resetRotationState();
            return;
        }
        // 目标距离超限/死亡重置
        if (target != null && (getDistanceToTarget(target) > distance.getValue())) {
            resetRotationState();
            target = null;
        }
        // 目标选择（限时重选 700 tick 防抖）
        if (requireMouseDown.getValue()) {
            target = findBestTarget();
        } else {
            retargetCounter++;
            LivingEntity candidate = findBestTarget();
            if (retargetCounter > 700 || target == null || !isValidTarget(target)) {
                target = candidate;
                retargetCounter = 0;
            }
        }
        if (target == null) return;
        // 屏幕/输入检查后应用旋转
        updateVelocityBuffers();
        applyRotationSimple();
    }

    /** 速度缓冲交换（updateVelocityBuffers，每 10 tick swap） */
    private void updateVelocityBuffers() {
        swapTickCounter++;
        if (swapTickCounter > 10) {
            verticalVelocityBuffer = verticalVelocity;
            horizontalVelocity = horizontalVelocityBuffer;
            horizontalVelocityBuffer = 0.0f;
            verticalVelocity = 0.0f;
            swapTickCounter = 0;
        }
    }

    /** 随机漂移（updateDrift，含 accumulator 反向清零） */
    private void updateDrift() {
        driftTimer += 1.0;
        if (driftTimer >= 250 + RandomUtil.nextInt(0, 50)) {
            driftTimer = RandomUtil.nextLong(-100, -50);
            randomOffsetX = RandomUtil.nextInt(-1, 2);
            randomOffsetY = RandomUtil.nextInt(-1, 2);
        }
        int horizontalStep = randomOffsetX;
        int verticalStep = randomOffsetY;
        if (RandomUtil.nextInt(0, 10) < 2) horizontalStep = 0;
        if (RandomUtil.nextInt(0, 10) < 2) verticalStep = 0;
        if (driftTimer < 0.0) {
            horizontalStep = 0;
            verticalStep = 0;
        }
        if (RandomUtil.nextInt(0, 20) == 1) {
            driftX += horizontalStep;
            driftY += verticalStep;
        }
        // 漂移与转向反向时清零（防漂移抵消转向）
        if (horizontalMouseAccumulator > 0.0f && driftX < 0 || horizontalMouseAccumulator < 0.0f && driftX > 0) {
            driftX = 0;
        }
    }

    /** Simple 旋转应用（applyRotation，26.2 直接按度） */
    private void applyRotationSimple() {
        updateDrift();
        if (target == null) return;
        Player player = mc.player;

        // 瞄准点（computeTargetOffset Closest / 实体中心）
        double tx = target.getX();
        double ty = target.getY() + target.getEyeHeight() * 0.9;
        double tz = target.getZ();
        if ("Closest".equals(targetArea.getValue())) {
            Vec3 closest = closestPointOnBox(player.getEyePosition(),
                    target.getBoundingBox());
            tx = closest.x();
            ty = closest.y();
            tz = closest.z();
        }

        // 目标运动预测（predictionFactor 1.7）
        double targetMotionX = tx - prevTargetX;
        double targetMotionZ = tz - prevTargetZ;
        prevTargetX = tx;
        prevTargetZ = tz;
        double predictedTargetX = tx + targetMotionX * 1.7;
        double predictedTargetZ = tz + targetMotionZ * 1.7;

        // RotationUtil.C（水平角差）+ RotationUtil.p（目标在左）
        double targetYaw = Math.toDegrees(Math.atan2(predictedTargetZ - player.getZ(),
                predictedTargetX - player.getX())) - 90.0;
        double horizontalAngleDifference = Math.abs(wrapDegrees((float) (targetYaw - player.getYRot())));
        boolean targetOnLeft = wrapDegrees((float) (targetYaw - player.getYRot())) < 0;

        // RotationUtil.H（垂直角差）+ targetAbove
        double dx = predictedTargetX - player.getX();
        double dz = predictedTargetZ - player.getZ();
        double dy = ty - player.getEyeY();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double targetPitch = -Math.toDegrees(Math.atan2(dy, Math.max(horizontalDist, 1.0E-4)));
        int verticalAngleDifference = (int) wrapDegrees((float) (targetPitch - player.getXRot()));
        boolean targetAbove = verticalAngleDifference < 0;
        int verticalDeadZoneDistance = Math.abs(verticalAngleDifference) - 10;

        // 力（1 + random(0,2) + 角差/50 + 近距助推）
        float horizontalForce = 1.0f;
        float verticalForce = 1.0f;
        horizontalForce += (float) RandomUtil.nextDouble(0.0, 2.0);
        horizontalForce += (float) (horizontalAngleDifference / 50.0);
        verticalForce += (float) RandomUtil.nextDouble(0.0, 2.0);
        verticalForce += Math.abs(verticalDeadZoneDistance) / 50.0f;
        // 角差突变加速（> 6° 时 /35）
        if (Math.abs(horizontalAngleDifference - lastAngleDiff) > 6.0) {
            horizontalForce += (float) (horizontalAngleDifference / 35.0);
        }
        // 近距离助推（(9-dist)/2.5 - 2）
        double proximityBoost = Math.max(0.0, (9.0 - player.distanceTo(target)) / 2.5 - 2.0);
        horizontalForce += (float) proximityBoost;

        // 横移加速（背对目标 ×1.6）
        float strafeInput = getStrafeInput();
        boolean strafingAway = targetOnLeft ? strafeInput < 0.0f : strafeInput > 0.0f;
        if (strafeIncrease.getValue() && strafingAway) {
            horizontalForce *= 1.6f;
        }
        // 近距衰减（< 0.5 时 /5）
        if (player.distanceTo(target) < 0.5f) {
            horizontalForce /= 5.0f;
        }

        // 加速度（26.2 直接度：去掉 /90 的 mouse 单位语义，用度/帧）
        float horizontalAcceleration = horizontalForce * (targetOnLeft ? -1.0f : 1.0f);
        float verticalAcceleration = verticalForce * (targetAbove ? 1.0f : -1.0f);
        // 小角差减速（< 5° 时加速度归零 + 速度衰减）
        if (horizontalAngleDifference < 5.0) {
            horizontalAcceleration = 0.0f;
            horizontalVelocity *= 0.7f;
            boolean strafingToward = targetOnLeft ? strafeInput > 0.0f : strafeInput < 0.0f;
            if (strafingToward) {
                horizontalVelocity *= 0.5f;
            }
        }
        // 方向反转时清速度/accumulator
        if (targetOnLeft != prevOnLeft) {
            horizontalVelocity = -horizontalVelocity;
            horizontalVelocityBuffer = -horizontalVelocityBuffer;
            horizontalMouseAccumulator = 0.0f;
        }
        if (targetAbove != prevAbove) {
            verticalVelocityBuffer = -verticalVelocityBuffer;
            verticalVelocity = -verticalVelocity;
            verticalMouseAccumulator = 0.0f;
        }
        // 垂直死区（< 5 时垂直加速度归零）
        if (verticalDeadZoneDistance < 5) {
            verticalAcceleration = 0.0f;
            verticalVelocityBuffer *= 0.7f;
        }

        // 速度累积（swap 后 horizontalVelocity = buffer）
        horizontalVelocityBuffer += horizontalAcceleration;
        verticalVelocity += verticalAcceleration;
        float smoothedHorizontalVelocity = horizontalVelocity;
        float smoothedVerticalVelocity = verticalVelocityBuffer;
        if (Math.abs(smoothedHorizontalVelocity) > 10.0f) {
            horizontalVelocityBuffer = 0.0f;
            horizontalVelocity = 0.0f;
            return;
        }

        // 水平调整量（×0.15 是 mouse 单位 → 26.2 直接度，保留比例）
        float horizontalAdjustment = smoothedHorizontalVelocity * 0.15f;
        if (horizontalAngleDifference <= 9.0) {
            horizontalAdjustment = (float) (horizontalAdjustment / (10.0 - horizontalAngleDifference));
        }
        if (Float.isNaN(horizontalAdjustment)) {
            horizontalVelocityBuffer = 0.0f;
            horizontalVelocity = 0.0f;
            return;
        }
        // queueHorizontalAdjustment（近角 boost + accumulator 累积）
        queueHorizontalAdjustment(horizontalAdjustment, (float) horizontalAngleDifference);

        if (aimVertically.getValue()) {
            float verticalAdjustment = smoothedVerticalVelocity * 0.15f;
            if (Float.isNaN(verticalAdjustment)) {
                verticalVelocity = 0.0f;
                verticalVelocityBuffer = 0.0f;
                return;
            }
            queueVerticalAdjustment(verticalAdjustment, verticalAngleDifference);
        }
        prevAbove = targetAbove;
        prevOnLeft = targetOnLeft;
        sampleCounter++;
        if (sampleCounter > 10) {
            lastAngleDiff = horizontalAngleDifference;
            sampleCounter = 0;
        }
    }

    /**queueHorizontalAdjustment（角差 ≤10° 时 boost，accumulator 累积） */
    private void queueHorizontalAdjustment(float adjustment, float angleDifference) {
        if (adjustment != 0.0f) {
            adjustment *= 5.0f;
            float speed = horizontalSpeed.getValue().floatValue();
            if (angleDifference <= 10.0f) {
                pitchBoost = speed;
            }
            if (pitchBoost > 0.0f) {
                speed -= pitchBoost / 3.0f;
                pitchBoost -= angleDifference / 200.0f;
            }
            horizontalMouseAccumulator += speed * adjustment;
        } else {
            horizontalMouseAccumulator = 0.0f;
        }
        // 26.2 直接按度应用（的 onPreRenderTick 用 sensitivityScale 换算鼠标增量，
        // 26.2 无 PlayerMouseRotationApplier → accumulator 即度）
        float horizontalDelta = horizontalMouseAccumulator;
        int horizontalSteps = (int) horizontalDelta;
        if (Math.abs(horizontalSteps) == 0) horizontalSteps = 0;
        mc.player.setYRot(mc.player.getYRot() + horizontalSteps);
        horizontalMouseAccumulator = horizontalDelta - horizontalSteps;
    }

    /**queueVerticalAdjustment（垂直同款 boost + 累积应用） */
    private void queueVerticalAdjustment(float adjustment, float angleDifference) {
        if (adjustment != 0.0f) {
            adjustment *= 5.0f;
            float speed = verticalSpeed.getValue().floatValue();
            float absoluteAngleDifference = Math.abs(angleDifference);
            if (absoluteAngleDifference <= 10.0f) {
                yawBoost = speed;
            }
            if (yawBoost > 0.0f) {
                speed -= yawBoost / 3.0f;
                yawBoost -= absoluteAngleDifference / 200.0f;
            }
            verticalMouseAccumulator += speed * adjustment;
        } else {
            verticalMouseAccumulator = 0.0f;
        }
        float verticalDelta = verticalMouseAccumulator;
        int verticalSteps = (int) verticalDelta;
        if (Math.abs(verticalSteps) == 0) verticalSteps = 0;
        mc.player.setXRot(Mth.clamp(mc.player.getXRot() + verticalSteps, -90.0f, 90.0f));
        verticalMouseAccumulator = verticalDelta - verticalSteps;
    }

    /**resetRotationState */
    private void resetRotationState() {
        horizontalMouseAccumulator = 0.0f;
        verticalMouseAccumulator = 0.0f;
        randomOffsetX = 0;
        randomOffsetY = 0;
        driftX = 0;
        driftY = 0;
        yawBoost = 0.0f;
        pitchBoost = 0.0f;
    }

    // ================= Adaptive 模式 =================

    private void tickAdaptive() {
        if (mc.level == null || mc.player == null) return;
        if (!canAim()) {
            resetAdaptiveState();
            target = null;
            return;
        }
        if (requireMouseDown.getValue() && !mc.options.keyAttack.isDown()) {
            target = null;
            resetAdaptiveState();
            return;
        }
        // 目标距离超限重置
        if (target != null && getDistanceToTarget(target) > distance.getValue()) {
            resetAdaptiveState();
            target = null;
        }
        // 目标选择（targetSwitchTicks 700 防抖 + 换目标 reset）
        if (requireMouseDown.getValue()) {
            LivingEntity candidate = findBestTarget();
            if (target == null || !target.equals(candidate)) {
                resetAdaptiveState();
            }
            target = candidate;
        } else {
            targetSwitchTicks++;
            if (targetSwitchTicks > 700 || target == null) {
                LivingEntity candidate = findBestTarget();
                if (target == null || !target.equals(candidate)) {
                    resetAdaptiveState();
                }
                target = candidate;
                targetSwitchTicks = 0;
            }
        }
        if (target == null) return;
        updateAim();
    }

    /**updateAim（Adaptive 主循环，PID 风格） */
    private void updateAim() {
        if (target == null || mc.player == null) {
            resetAdaptiveState();
            return;
        }
        Player player = mc.player;
        boolean yawSnapEnabled = horizontalSpeed.getValue() > 20.0;
        boolean pitchSnapEnabled = aimVertically.getValue() && verticalSpeed.getValue() > 20.0;
        boolean snapYaw = yawSnapEnabled && yawSnapped;
        boolean snapPitch = pitchSnapEnabled && pitchSnapped;

        long nowNanos = System.nanoTime();
        float deltaTime;
        if (!initialized || lastFrameNanos == 0L) {
            deltaTime = 0.016666668f;
        } else {
            deltaTime = (float) (nowNanos - lastFrameNanos) / 1.0E9f;
            deltaTime = Math.max(0.008333334f, Math.min(0.12f, deltaTime));
        }
        lastFrameNanos = nowNanos;

        // resolveAimTarget（瞄准点：eye-aligned + airFactor 混合 / Closest 预测平滑）
        double[] resolvedAimPoint = resolveAimTarget(player, target);
        double resolvedAimX = resolvedAimPoint[0];
        double resolvedAimY = resolvedAimPoint[1];
        double resolvedAimZ = resolvedAimPoint[2];

        // 目标运动（velocity）+ 相对运动 + lead
        double targetMotionX = target.getDeltaMovement().x();
        double targetMotionY = target.getDeltaMovement().y();
        double targetMotionZ = target.getDeltaMovement().z();
        double relativeMotionX = targetMotionX - player.getDeltaMovement().x();
        double relativeMotionY = targetMotionY - player.getDeltaMovement().y();
        double relativeMotionZ = targetMotionZ - player.getDeltaMovement().z();
        double targetLeadX = targetMotionX + relativeMotionX * 0.08;
        double targetLeadY = targetMotionY + relativeMotionY * 0.1;
        double targetLeadZ = targetMotionZ + relativeMotionZ * 0.08;
        if (!aimPointInitialized) {
            aimX = resolvedAimX;
            aimY = resolvedAimY;
            aimZ = resolvedAimZ;
            leadX = targetLeadX;
            leadY = targetLeadY;
            leadZ = targetLeadZ;
            aimPointInitialized = true;
        }

        // 平滑混合（aimSmoothing / leadSmoothing，帧率无关）
        double targetHorizontalSpeed = Math.sqrt(targetMotionX * targetMotionX + targetMotionZ * targetMotionZ);
        double relativeHorizontalSpeed = Math.sqrt(relativeMotionX * relativeMotionX + relativeMotionZ * relativeMotionZ);
        double targetDistance = player.distanceTo(target);
        double baseAimSmoothing = "Closest".equals(targetArea.getValue()) ? 0.3 : 0.28;
        double aimSmoothing = Math.max(0.08, Math.min(0.75, baseAimSmoothing + Math.min(0.35, relativeHorizontalSpeed * 0.5)));
        double leadMotionScale = targetHorizontalSpeed + relativeHorizontalSpeed * 0.25;
        double leadSmoothing = Math.max(0.12, Math.min(0.68, 0.18 + Math.min(0.42, leadMotionScale * 0.72)));
        double frameScale = Math.max(0.45, Math.min(2.4, deltaTime * 60.0));
        double aimBlend = 1.0 - Math.pow(1.0 - aimSmoothing, frameScale);
        double leadBlend = 1.0 - Math.pow(1.0 - leadSmoothing, frameScale);
        aimX += (resolvedAimX - aimX) * aimBlend;
        aimY += (resolvedAimY - aimY) * aimBlend;
        aimZ += (resolvedAimZ - aimZ) * aimBlend;
        leadX += (targetLeadX - leadX) * leadBlend;
        leadY += (targetLeadY - leadY) * leadBlend;
        leadZ += (targetLeadZ - leadZ) * leadBlend;

        // 瞄准滞后上限（目标移动太快时直接跟随）
        double aimLagX = resolvedAimX - aimX;
        double aimLagZ = resolvedAimZ - aimZ;
        double horizontalAimLag = Math.sqrt(aimLagX * aimLagX + aimLagZ * aimLagZ);
        double maximumAimLag = 0.3 + targetHorizontalSpeed * 1.3 + relativeHorizontalSpeed * 0.4;
        if (horizontalAimLag > maximumAimLag) {
            aimX = resolvedAimX;
            aimY = resolvedAimY;
            aimZ = resolvedAimZ;
            leadX = targetLeadX;
            leadY = targetLeadY;
            leadZ = targetLeadZ;
        }

        // leadAmount（距离/速度缩放）
        double leadAmount = 0.35 + targetDistance * 0.045 + targetHorizontalSpeed * 0.95 + Math.min(0.18, relativeHorizontalSpeed * 0.12);
        leadAmount = Math.max(0.1, Math.min(1.05, leadAmount));
        double distanceLeadScale = Math.max(0.0, Math.min(1.0, (targetDistance - 0.8) / 2.5));
        leadAmount *= 0.3 + 0.7 * distanceLeadScale;
        leadAmount = Math.max(0.05, leadAmount);

        // 垂直速度测量 + airFactor（空中因子）
        double playerEyeY = player.getEyeY();
        float measuredVerticalVelocity = 0.0f;
        if (initialized && lastEyeY != 0.0) {
            measuredVerticalVelocity = (float) ((playerEyeY - lastEyeY) / deltaTime);
        }
        verticalVelocityAdaptive += (measuredVerticalVelocity - verticalVelocityAdaptive) * 0.65f;
        lastEyeY = playerEyeY;
        if (!player.onGround()) {
            if (lastGroundEyeY == 0.0) {
                lastGroundEyeY = playerEyeY;
            }
            double airborneHeight = Math.max(0.0, playerEyeY - lastGroundEyeY);
            float velocityAirFactor = (float) Math.min(0.7, Math.max(0.0, verticalVelocityAdaptive) * 0.34);
            float heightAirFactor = (float) Math.min(0.82, airborneHeight * 0.58);
            airFactor = Math.max(airFactor * 0.92f, Math.max(velocityAirFactor, heightAirFactor));
        } else {
            lastGroundEyeY = playerEyeY;
            airFactor *= 0.35f;
        }

        // 垂直 lead（受限）
        double adjustedAimX = aimX;
        double adjustedAimZ = aimZ;
        double verticalAimDelta = aimY - playerEyeY;
        double verticalOffsetScale = Math.max(0.0, Math.min(1.0, Math.abs(verticalAimDelta) / 0.7));
        double verticalLeadAmount = Math.min(leadAmount, 0.7 + targetDistance * 0.04);
        double verticalLeadScale = 0.28 + 0.52 * verticalOffsetScale;
        double verticalLead = leadY * verticalLeadAmount * verticalLeadScale;
        double verticalLeadLimit = 0.16 + targetDistance * 0.055;
        verticalLead = Math.max(-verticalLeadLimit, Math.min(verticalLeadLimit, verticalLead));
        double adjustedAimY = aimY + verticalLead;
        if (snapYaw) {
            adjustedAimX = resolvedAimX;
            adjustedAimZ = resolvedAimZ;
        }
        if (snapPitch) {
            adjustedAimY = resolvedAimY;
        }

        // 目标角度（yaw/pitch）
        double aimDeltaX = adjustedAimX - player.getX();
        double aimDeltaZ = adjustedAimZ - player.getZ();
        double aimDeltaY = adjustedAimY - playerEyeY;
        double horizontalAimDistance = Math.sqrt(aimDeltaX * aimDeltaX + aimDeltaZ * aimDeltaZ);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(aimDeltaZ, aimDeltaX)) - 90.0);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(aimDeltaY, Math.max(horizontalAimDistance, 1.0E-4))));

        // 目标角度限速（最大速率）
        if (initialized) {
            float yawTargetChange = Math.abs(wrapDegrees(targetYaw - lastTargetYaw));
            float pitchTargetChange = Math.abs(wrapDegrees(targetPitch - lastTargetPitch));
            float abruptChangeThreshold = "Closest".equals(targetArea.getValue()) ? 20.0f : 12.0f;
            float maximumTargetChange = Math.max(yawTargetChange, pitchTargetChange);
            float abruptChangeFactor = Math.max(0.0f, Math.min(1.0f, (maximumTargetChange - abruptChangeThreshold) / 50.0f));
            overshoot = Math.max(overshoot, abruptChangeFactor);
            if (abruptChangeFactor > 0.3f) {
                aimStrength *= 0.3f;
                aimX = resolvedAimX;
                aimY = resolvedAimY;
                aimZ = resolvedAimZ;
            }
            float yawRateScale = 1.0f + Math.min(2.0f, yawTargetChange / 60.0f);
            float pitchRateScale = 1.0f + Math.min(2.0f, pitchTargetChange / 60.0f);
            float maximumYawRate = (120.0f + (float) (relativeHorizontalSpeed * 700.0)) * yawRateScale;
            float maximumPitchRate = (95.0f + (float) (Math.abs(leadY) * 550.0)) * pitchRateScale;
            float viewYawDifference = Math.abs(wrapDegrees(targetYaw - player.getYRot()));
            float wideTurnScale = smoothStep(10.0f, 35.0f, viewYawDifference);
            maximumYawRate *= 1.0f + wideTurnScale * 1.5f;
            maximumYawRate = Math.max(90.0f, Math.min(1080.0f, maximumYawRate));
            maximumPitchRate = Math.max(70.0f, Math.min(500.0f, maximumPitchRate));
            float yawStep = wrapDegrees(targetYaw - lastTargetYaw);
            float pitchStep = wrapDegrees(targetPitch - lastTargetPitch);
            if (!snapYaw) {
                yawStep = Math.max(-maximumYawRate * deltaTime, Math.min(maximumYawRate * deltaTime, yawStep));
            }
            if (!snapPitch) {
                pitchStep = Math.max(-maximumPitchRate * deltaTime, Math.min(maximumPitchRate * deltaTime, pitchStep));
            }
            targetYaw = lastTargetYaw + yawStep;
            targetPitch = lastTargetPitch + pitchStep;
        }

        // overshoot 衰减
        float overshootDecay = (float) Math.pow(0.02, deltaTime);
        overshoot *= overshootDecay;
        if (overshoot < 0.01f) {
            overshoot = 0.0f;
        }
        float overshootMultiplier = 1.0f + 3.0f * overshoot;

        // 误差（yawError/pitchError）+ snap 状态
        float playerYaw = player.getYRot();
        float playerPitch = player.getXRot();
        float yawError = wrapDegrees(targetYaw - playerYaw);
        float pitchError = wrapDegrees(targetPitch - playerPitch);
        if (!aimVertically.getValue()) {
            pitchError = 0.0f;
        }
        float absoluteYawError = Math.abs(yawError);
        float absolutePitchError = Math.abs(pitchError);
        float combinedAngleError = (float) Math.sqrt(absoluteYawError * absoluteYawError + absolutePitchError * absolutePitchError);
        yawSnapped = shouldSnap(yawSnapEnabled, yawSnapped, absoluteYawError);
        pitchSnapped = shouldSnap(pitchSnapEnabled, pitchSnapped, absolutePitchError);
        snapYaw = yawSnapEnabled && yawSnapped;
        snapPitch = pitchSnapEnabled && pitchSnapped;

        // aimStrength（误差驱动）
        float desiredAimStrength = 1.0f - smoothStep(1.5f, 8.0f, combinedAngleError);
        if (initialized) {
            float yawClosingSpeed = -(absoluteYawError - Math.abs(lastYawDiff)) / deltaTime;
            float closingAdjustment = Math.max(-0.3f, Math.min(0.3f, yawClosingSpeed / 20.0f));
            desiredAimStrength += closingAdjustment;
            desiredAimStrength = Math.max(0.0f, Math.min(1.0f, desiredAimStrength));
        }
        float aimStrengthBlend = desiredAimStrength > aimStrength
                ? Math.max(0.01f, Math.min(0.25f, deltaTime * 3.0f))
                : Math.max(0.05f, Math.min(0.8f, deltaTime * 20.0f));
        aimStrength += (desiredAimStrength - aimStrength) * aimStrengthBlend;
        float currentAimStrength = aimStrength;

        // 目标/玩家角速度（accel 平滑）
        float smoothedTargetYawRate = 0.0f;
        float smoothedTargetPitchRate = 0.0f;
        float playerYawRate = 0.0f;
        float playerPitchRate = 0.0f;
        if (initialized) {
            float targetYawRate = wrapDegrees(targetYaw - lastTargetYaw) / deltaTime;
            float targetPitchRate = wrapDegrees(targetPitch - lastTargetPitch) / deltaTime;
            playerYawRate = wrapDegrees(playerYaw - lastPlayerYaw) / deltaTime;
            playerPitchRate = wrapDegrees(playerPitch - lastPlayerPitch) / deltaTime;
            float accelerationBlend = Math.max(0.05f, Math.min(0.45f, deltaTime * 12.0f));
            yawAccel += (targetYawRate - yawAccel) * accelerationBlend;
            pitchAccel += (targetPitchRate - pitchAccel) * accelerationBlend;
            smoothedTargetYawRate = yawAccel;
            smoothedTargetPitchRate = pitchAccel;
        }

        // 速度因子（>10 时增益）
        float horizontalSpeedF = horizontalSpeed.getValue().floatValue() * 0.75f;
        float verticalSpeedF = verticalSpeed.getValue().floatValue() * 0.75f;
        float horizontalSpeedFactor = Math.max(0.0f, Math.min(1.0f, (horizontalSpeedF - 10.0f) / 90.0f));
        float verticalSpeedFactor = Math.max(0.0f, Math.min(1.0f, (verticalSpeedF - 10.0f) / 90.0f));

        // bias（符号反转时 ×0.3）
        if (Math.signum(yawError) != Math.signum(lastYawDiff) && Math.abs(yawError) > 0.1f && Math.abs(lastYawDiff) > 0.1f) {
            yawBias *= 0.3f;
        }
        if (Math.signum(pitchError) != Math.signum(lastPitchDiff) && Math.abs(pitchError) > 0.1f && Math.abs(lastPitchDiff) > 0.1f) {
            pitchBias *= 0.3f;
        }
        float squaredAimStrength = currentAimStrength * currentAimStrength;
        yawBias += yawError * deltaTime * squaredAimStrength * (1.0f - horizontalSpeedFactor);
        pitchBias += pitchError * deltaTime * squaredAimStrength * (1.0f - verticalSpeedFactor);
        float biasRetention = 1.0f - (1.0f - currentAimStrength) * Math.max(0.0f, Math.min(0.5f, deltaTime * 5.0f));
        yawBias *= biasRetention;
        pitchBias *= biasRetention;
        float yawBiasLimit = 15.0f * (1.0f - horizontalSpeedFactor * 0.9f);
        float pitchBiasLimit = 10.0f * (1.0f - verticalSpeedFactor * 0.9f);
        yawBias = Math.max(-yawBiasLimit, Math.min(yawBiasLimit, yawBias));
        pitchBias = Math.max(-pitchBiasLimit, Math.min(pitchBiasLimit, pitchBias));

        // 误差速度（velocity blend）
        float yawErrorVelocity = initialized ? (yawError - lastYawDiff) / deltaTime : 0.0f;
        float pitchErrorVelocity = initialized ? (pitchError - lastPitchDiff) / deltaTime : 0.0f;
        float velocityBlend = 0.15f;
        yawVelocity = yawVelocity * (1.0f - velocityBlend) + yawErrorVelocity * velocityBlend;
        pitchVelocity = pitchVelocity * (1.0f - velocityBlend) + pitchErrorVelocity * velocityBlend;

        // flick 检测（符号频繁反转时）
        float flickThreshold = "Closest".equals(targetArea.getValue()) ? 1.5f : 0.5f;
        float yawSign = Math.signum(yawError);
        yawFlickTicks = yawSign != lastYawSign && Math.abs(yawError) > flickThreshold
                ? Math.min(yawFlickTicks + 1.0f, 8.0f)
                : Math.max(0.0f, yawFlickTicks - deltaTime * 3.0f);
        lastYawSign = yawSign;
        float pitchSign = Math.signum(pitchError);
        pitchFlickTicks = pitchSign != lastPitchSign && Math.abs(pitchError) > flickThreshold
                ? Math.min(pitchFlickTicks + 1.0f, 8.0f)
                : Math.max(0.0f, pitchFlickTicks - deltaTime * 3.0f);
        lastPitchSign = pitchSign;
        float yawFlickFactor = Math.max(0.0f, Math.min(1.0f, yawFlickTicks / 5.0f));
        float pitchFlickFactor = Math.max(0.0f, Math.min(1.0f, pitchFlickTicks / 5.0f));

        // 增益（error/bias/velocity/targetRate/playerCompensation）
        float limitedHorizontalSpeed = Math.min(horizontalSpeedF, 10.0f);
        float limitedVerticalSpeed = Math.min(verticalSpeedF, 10.0f);
        float horizontalGainInput = (limitedHorizontalSpeed - 1.0f) / 9.0f;
        float verticalGainInput = (limitedVerticalSpeed - 1.0f) / 9.0f;
        horizontalGainInput *= horizontalGainInput;
        verticalGainInput *= verticalGainInput;
        float horizontalGainScale = 0.15f + 0.85f * Math.max(0.0f, Math.min(1.0f, horizontalGainInput));
        float verticalGainScale = 0.15f + 0.85f * Math.max(0.0f, Math.min(1.0f, verticalGainInput));
        float yawErrorGain = lerp(currentAimStrength, 8.0f, 2.5f + limitedHorizontalSpeed * 0.15f) * horizontalGainScale;
        float pitchErrorGain = lerp(currentAimStrength, 7.0f, 2.2f + limitedVerticalSpeed * 0.13f) * verticalGainScale;
        float yawBiasGain = lerp(currentAimStrength, 0.15f, 0.8f + limitedHorizontalSpeed * 0.04f) * horizontalGainScale;
        float pitchBiasGain = lerp(currentAimStrength, 0.12f, 0.65f + limitedVerticalSpeed * 0.035f) * verticalGainScale;
        float yawVelocityGain = lerp(currentAimStrength, 0.08f, 0.25f) * horizontalGainScale;
        float pitchVelocityGain = lerp(currentAimStrength, 0.06f, 0.2f) * verticalGainScale;
        float targetYawRateGain = (0.85f + limitedHorizontalSpeed * 0.015f) * horizontalGainScale;
        float targetPitchRateGain = (0.82f + limitedVerticalSpeed * 0.013f) * verticalGainScale;
        float playerYawCompensation = lerp(currentAimStrength, 0.1f, 0.3f);
        float playerPitchCompensation = lerp(currentAimStrength, 0.08f, 0.25f);
        yawErrorGain *= 1.0f - 0.6f * yawFlickFactor;
        yawVelocityGain *= 1.0f + 2.0f * yawFlickFactor;
        pitchErrorGain *= 1.0f - 0.6f * pitchFlickFactor;
        pitchVelocityGain *= 1.0f + 2.0f * pitchFlickFactor;

        // yawRate/pitchRate（PID 合成）
        float yawRate = yawErrorGain * yawError + yawBiasGain * yawBias + yawVelocityGain * yawVelocity
                + targetYawRateGain * smoothedTargetYawRate - playerYawCompensation * playerYawRate;
        float pitchRate = 0.0f;
        if (aimVertically.getValue()) {
            pitchRate = pitchErrorGain * pitchError + pitchBiasGain * pitchBias + pitchVelocityGain * pitchVelocity
                    + targetPitchRateGain * smoothedTargetPitchRate - playerPitchCompensation * playerPitchRate;
        } else {
            pitchBias = 0.0f;
            pitchVelocity = 0.0f;
        }
        if (!snapPitch) {
            pitchRate += computePitchDrift(playerPitch, deltaTime, nowNanos);
        }

        // strafe 倍率
        float strafeMultiplier = 1.0f;
        float strafeInput = getStrafeInput();
        if (strafeIncrease.getValue() && Math.abs(strafeInput) > 0.01f) {
            boolean targetToRight = yawError > 0.0f;
            boolean strafingAway = targetToRight && strafeInput < 0.0f || !targetToRight && strafeInput > 0.0f;
            if (strafingAway) {
                strafeMultiplier = 1.15f;
            }
        }
        yawRate *= strafeMultiplier;

        // 输出限幅（base/motion/wide turn/overshoot）
        float wideYawScale = smoothStep(8.0f, 40.0f, absoluteYawError);
        float wideTurnMultiplier = 1.0f + wideYawScale * 2.5f;
        float yawBaseLimit = (22.0f + limitedHorizontalSpeed * 15.0f) * horizontalGainScale * overshootMultiplier * wideTurnMultiplier;
        float pitchBaseLimit = (18.0f + limitedVerticalSpeed * 13.0f) * verticalGainScale * overshootMultiplier;
        float yawMotionLimit = (Math.abs(smoothedTargetYawRate) * 0.4f + 18.0f) * (0.35f + horizontalGainScale * 0.65f) * wideTurnMultiplier;
        float pitchMotionLimit = (Math.abs(smoothedTargetPitchRate) * 0.38f + 14.0f) * (0.35f + verticalGainScale * 0.65f);
        float maximumYawOutput = Math.min(400.0f * overshootMultiplier * wideTurnMultiplier, Math.max(yawBaseLimit, yawMotionLimit));
        float maximumPitchOutput = Math.min(300.0f * overshootMultiplier, Math.max(pitchBaseLimit, pitchMotionLimit));
        yawRate = Math.max(-maximumYawOutput, Math.min(maximumYawOutput, yawRate));
        pitchRate = Math.max(-maximumPitchOutput, Math.min(maximumPitchOutput, pitchRate));

        // 距离倍率
        float distanceScale = smoothStep(0.5f, 3.0f, (float) targetDistance);
        float wideTurnBaseScale = lerp(wideYawScale, 0.15f, 0.65f);
        float yawDistanceMultiplier = lerp(currentAimStrength,
                wideTurnBaseScale + (1.0f - wideTurnBaseScale) * distanceScale, 0.4f + 0.6f * distanceScale);
        float pitchDistanceMultiplier = lerp(currentAimStrength,
                0.2f + 0.8f * distanceScale, 0.45f + 0.55f * distanceScale);
        yawRate *= yawDistanceMultiplier;
        pitchRate *= pitchDistanceMultiplier;

        // 多频噪声（高/中/低频正弦）
        float noiseElapsedSeconds = (float) (nowNanos - noiseStartNanos) / 1.0E9f;
        float highFrequencyYawNoise = (float) (Math.sin(noiseElapsedSeconds * 62.83) * 0.4
                + Math.sin(noiseElapsedSeconds * 47.12) * 0.25
                + Math.sin(noiseElapsedSeconds * 78.54) * 0.15);
        float highFrequencyPitchNoise = (float) (Math.sin(noiseElapsedSeconds * 56.55 + 1.3) * 0.35
                + Math.sin(noiseElapsedSeconds * 43.98 + 0.7) * 0.2
                + Math.sin(noiseElapsedSeconds * 72.26 + 2.1) * 0.12);
        float mediumFrequencyYawNoise = (float) (Math.sin(noiseElapsedSeconds * 12.57) * 0.8
                + Math.sin(noiseElapsedSeconds * 7.85) * 0.5);
        float mediumFrequencyPitchNoise = (float) (Math.sin(noiseElapsedSeconds * 10.47 + 0.9) * 0.6
                + Math.sin(noiseElapsedSeconds * 5.65 + 1.8) * 0.4);
        float lowFrequencyYawNoise = (float) (Math.sin(noiseElapsedSeconds * 1.26) * 0.3);
        float lowFrequencyPitchNoise = (float) (Math.sin(noiseElapsedSeconds * 0.94 + 0.5) * 0.2);
        float noiseStrength = 0.15f + 0.85f * currentAimStrength;
        float speedNoiseScale = 0.5f + 0.5f * (1.0f - horizontalGainInput);
        float yawNoise = (highFrequencyYawNoise + mediumFrequencyYawNoise + lowFrequencyYawNoise)
                * noiseStrength * speedNoiseScale * 1.5f;
        float pitchNoise = (highFrequencyPitchNoise + mediumFrequencyPitchNoise + lowFrequencyPitchNoise)
                * noiseStrength * speedNoiseScale;
        if (!snapYaw) {
            yawRate += yawNoise;
        }
        if (aimVertically.getValue() && !snapPitch) {
            pitchRate += pitchNoise;
        }

        // 应用（26.2 直接度/帧：yawFrameDelta = yawRate * deltaTime 加到 yRot）
        float yawFrameDelta = yawRate * deltaTime;
        float pitchFrameDelta = pitchRate * deltaTime;
        if (snapYaw) {
            // snap 时直接对准目标（resolveSnapPoint 的角度差）
            yawFrameDelta = wrapDegrees(targetYaw - player.getYRot());
        }
        if (snapPitch) {
            pitchFrameDelta = wrapDegrees(targetPitch - player.getXRot());
        }
        mc.player.setYRot(player.getYRot() + yawFrameDelta);
        if (aimVertically.getValue()) {
            mc.player.setXRot(Mth.clamp(player.getXRot() + pitchFrameDelta, -90.0f, 90.0f));
        }

        if (!initialized) {
            initialized = true;
        }
        lastYawDiff = yawError;
        lastPitchDiff = pitchError;
        lastTargetYaw = targetYaw;
        lastTargetPitch = targetPitch;
        lastPlayerYaw = playerYaw;
        lastPlayerPitch = playerPitch;
    }

    /**resolveAimTarget（瞄准点：eye-aligned + airFactor 混合 / Closest 预测平滑） */
    private double[] resolveAimTarget(Player player, LivingEntity target) {
        double playerEyeY = player.getEyeY();
        AABB box = target.getBoundingBox();
        double targetMinY = box.minY;
        double targetMaxY = box.maxY;
        double targetHeight = targetMaxY - targetMinY;
        double targetCenterY = targetMinY + targetHeight * 0.65;
        double airborneOffset = Math.min(0.85, airFactor);
        double eyeOffset = 0.1 + airborneOffset;
        double eyeAlignedY = Math.max(targetMinY + 0.01, Math.min(targetMaxY - 0.01, playerEyeY - eyeOffset));
        double centerBlend = Math.max(0.0, Math.min(1.0, airborneOffset / 0.55));
        double targetY = eyeAlignedY + (targetCenterY - eyeAlignedY) * centerBlend;
        targetY = Math.max(targetMinY + 0.01, Math.min(targetMaxY - 0.01, targetY));
        if ("Closest".equals(targetArea.getValue())) {
            Vec3 closestPoint = closestPointOnBox(player.getEyePosition(), box);
            double closestY = Math.max(targetMinY + 0.01, Math.min(targetMaxY - 0.01, closestPoint.y() - eyeOffset));
            closestY += (targetCenterY - closestY) * centerBlend;
            closestY = Math.max(targetMinY + 0.01, Math.min(targetMaxY - 0.01, closestY));
            double resolvedX = closestPoint.x();
            double resolvedY = closestY;
            double resolvedZ = closestPoint.z();
            if (predictionInitialized) {
                double smoothingFactor = 0.35;
                resolvedX = predictedX + (resolvedX - predictedX) * smoothingFactor;
                resolvedY = predictedY + (resolvedY - predictedY) * smoothingFactor;
                resolvedZ = predictedZ + (resolvedZ - predictedZ) * smoothingFactor;
            }
            predictedX = resolvedX;
            predictedY = resolvedY;
            predictedZ = resolvedZ;
            predictionInitialized = true;
            return new double[]{resolvedX, resolvedY, resolvedZ};
        }
        return new double[]{target.getX(), targetY, target.getZ()};
    }

    /**computePitchDrift（弹簧漂移 + 随机噪声） */
    private float computePitchDrift(float playerPitch, float deltaTime, long nowNanos) {
        float strength = 0.65f + 0.35f * aimStrength;
        if (driftNextNanos == 0L || nowNanos >= driftNextNanos) {
            float randomAmplitude = lerp(strength, 0.05f, 0.15f);
            float pitchCorrection = playerPitch > 22.0f ? -0.18f : (playerPitch < -22.0f ? 0.18f : 0.0f);
            driftTarget = (RandomUtil.nextFloat(0.0f, 1.0f) * 2.0f - 1.0f) * randomAmplitude + pitchCorrection;
            driftTarget = Math.max(-0.5f, Math.min(0.5f, driftTarget));
            long intervalMillis = 300L + RandomUtil.nextInt(0, 420);
            driftNextNanos = nowNanos + intervalMillis * 1000000L;
        }
        float springStrength = lerp(strength, 2.0f, 5.0f);
        float damping = (float) Math.pow(0.04f, deltaTime);
        driftVelocity += (driftTarget - driftPos) * springStrength * deltaTime;
        driftVelocity *= damping;
        driftPos += driftVelocity * deltaTime;
        float positionLimit = lerp(strength, 0.45f, 0.8f);
        if (driftPos > positionLimit) {
            driftPos = positionLimit;
            driftVelocity = Math.min(0.0f, driftVelocity);
        } else if (driftPos < -positionLimit) {
            driftPos = -positionLimit;
            driftVelocity = Math.max(0.0f, driftVelocity);
        }
        driftNoise += (RandomUtil.nextFloat(0.0f, 1.0f) * 2.0f - 1.0f - driftNoise) * Math.max(0.02f, Math.min(0.18f, deltaTime * 5.0f));
        float elapsedSeconds = (float) (nowNanos - noiseStartNanos) / 1.0E9f;
        float noise = (float) (Math.sin(elapsedSeconds * 8.7 + 0.4) * 0.35f
                + Math.sin(elapsedSeconds * 13.1 + 2.2) * 0.22f
                + Math.sin(elapsedSeconds * 19.6 + 1.1) * 0.12f
                + driftNoise * 0.3f);
        float restoringForce = -driftPos * lerp(strength, 1.4f, 3.0f);
        float output = driftVelocity * 0.25f + restoringForce + noise * lerp(strength, 0.35f, 0.9f);
        float pitchLimitScale = 1.0f - 0.85f * smoothStep(72.0f, 88.0f, Math.abs(playerPitch));
        float outputLimit = lerp(strength, 0.25f, 0.55f) * pitchLimitScale;
        return Math.max(-outputLimit, Math.min(outputLimit, output));
    }

    /**shouldSnap（5° 进入 / 7° 退出） */
    private static boolean shouldSnap(boolean enabled, boolean currentlySnapped, float angleDifference) {
        if (!enabled) {
            return false;
        }
        return currentlySnapped ? angleDifference <= SNAP_EXIT_THRESHOLD : angleDifference <= SNAP_ENTER_THRESHOLD;
    }

    /**smoothStep（Hermite 插值） */
    private static float smoothStep(float edgeStart, float edgeEnd, float value) {
        float normalized = Math.max(0.0f, Math.min(1.0f, (value - edgeStart) / (edgeEnd - edgeStart)));
        return normalized * normalized * (3.0f - 2.0f * normalized);
    }

    /**lerp */
    private static float lerp(float factor, float start, float end) {
        return start + factor * (end - start);
    }

    /**resetAdaptiveState（= reset） */
    private void resetAdaptiveState() {
        pendingYaw = 0.0f;
        pendingPitch = 0.0f;
        yawSnapped = false;
        pitchSnapped = false;
        yawBias = 0.0f;
        pitchBias = 0.0f;
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
        lastYawDiff = 0.0f;
        lastPitchDiff = 0.0f;
        lastTargetYaw = 0.0f;
        lastTargetPitch = 0.0f;
        yawAccel = 0.0f;
        pitchAccel = 0.0f;
        lastPlayerYaw = 0.0f;
        lastPlayerPitch = 0.0f;
        initialized = false;
        lastFrameNanos = 0L;
        aimPointInitialized = false;
        aimX = 0.0;
        aimY = 0.0;
        aimZ = 0.0;
        leadX = 0.0;
        leadY = 0.0;
        leadZ = 0.0;
        aimStrength = 0.0f;
        yawFlickTicks = 0.0f;
        pitchFlickTicks = 0.0f;
        lastYawSign = 0.0f;
        lastPitchSign = 0.0f;
        overshoot = 0.0f;
        lastEyeY = 0.0;
        lastGroundEyeY = 0.0;
        verticalVelocityAdaptive = 0.0f;
        airFactor = 0.0f;
        predictionInitialized = false;
        predictedX = 0.0;
        predictedY = 0.0;
        predictedZ = 0.0;
        noiseStartNanos = System.nanoTime();
        driftPos = 0.0f;
        driftVelocity = 0.0f;
        driftTarget = 0.0f;
        driftNoise = 0.0f;
        driftNextNanos = 0L;
    }

    /** 全部状态清零（onEnable） */
    private void resetAllState() {
        target = null;
        resetRotationState();
        resetAdaptiveState();
        prevOnLeft = false;
        prevAbove = false;
        lastAngleDiff = 0.0;
        prevTargetX = Double.NaN;
        prevTargetZ = Double.NaN;
        swapTickCounter = 0;
        retargetCounter = 0;
        sampleCounter = 0;
        blockBreakCooldown = 0;
    }

    // ================= 辅助方法 =================

    /** 包围盒最近点（RotationUtil.T） */
    private Vec3 closestPointOnBox(Vec3 point, AABB box) {
        double x = Mth.clamp(point.x(), box.minX, box.maxX);
        double y = Mth.clamp(point.y(), box.minY, box.maxY);
        double z = Mth.clamp(point.z(), box.minZ, box.maxZ);
        return new Vec3(x, y, z);
    }

    /** 玩家到目标的距离（getDistanceToEntity） */
    private double getDistanceToTarget(LivingEntity target) {
        if (mc.player == null) return Double.MAX_VALUE;
        return mc.player.distanceTo(target);
    }

    /** 玩家视线到目标的准星偏角（RotationUtil.a，0~180） */
    private double getAngleToTarget(LivingEntity target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        double targetYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        return Math.abs(wrapDegrees((float) (targetYaw - mc.player.getYRot())));
    }

    /** 目标护甲值 */
    private double getArmorValue(LivingEntity target) {
        return target.getAttributeValue(Attributes.ARMOR);
    }

    /** 目标威胁值（，手持物品价值） */
    private double getThreatValue(LivingEntity target) {
        if (!(target instanceof Player player)) {
            return getDistanceToTarget(target);
        }
        net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
        if (stack == null || stack.isEmpty()) return 0.0;
        String id = stack.getItem().getDescriptionId();
        if (id.contains("_sword")) return 2.0;
        if (id.contains("_axe")) return 1.5;
        return 1.0;
    }

    /** 玩家当前横移输入（player.movementInput.T；>0 向左，<0 向右） */
    private float getStrafeInput() {
        if (mc.player == null) return 0.0f;
        if (mc.player.input instanceof net.minecraft.client.player.ClientInput clientInput) {
            float left = clientInput.keyPresses.left() ? 1.0f : 0.0f;
            float right = clientInput.keyPresses.right() ? 1.0f : 0.0f;
            return left - right;
        }
        return 0.0f;
    }

    /** 角度归一（MathUtil.wrapAngleTo180） */
    private static float wrapDegrees(float angle) {
        return Mth.wrapDegrees(angle);
    }

    /** 当前目标（供 HUD 渲染 / 外部模块查询） */
    public Entity getTarget() {
        return this.target;
    }
}
