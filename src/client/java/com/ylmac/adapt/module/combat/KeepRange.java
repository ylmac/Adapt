package com.ylmac.adapt.module.combat;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * KeepRange — 保持攻击范围。
 * <p>
 * 1. onGame（GameEvent）：玩家在地面上时扫描周围方块，找边缘（下方连续 5 格
 * {edgeRange} 格内是空气）→ 记录 nearEdge；onMoveInput 里若 nearEdge 且
 * disableNearEdge 开启，则不拉距离（防止把玩家从边缘拉下去）。
 * 2. onMoveInput（MoveInputEvent）：取 10 格内目标（TargetComponent.e(10.0)），
 * comboToStart 命中计数后（目标 hurtTime 触发 combo，自己被击退清零），
 * 当与目标水平距离 &lt; range - 0.05 时自动后退（BackWards 模式：把输入强制
 * 改成最接近"远离目标"方向的 strafe/forward；Stop 模式：只把与后退方向
 * 相反的输入清 0 — 玩家继续按 W 不会贴脸，按 S 才能远离）。
 * 3. hurtTime：目标正在被打（hurtTime &gt; 0）累计 combo，自己被打清零；
 * combo &gt; comboToStart * 8 才开始拉距离（防止开局就自动后退）。
 * <p>
 * - MoveInputEvent → {@link KeyboardInputEvent}（KeyboardInput.tick TAIL 发布，
 * 可改 forward/strafe，改后写回 keyPresses + moveVector，见 KeyboardInputMixin）。
 * - GameEvent → {@code ClientTickEvent.Post}（玩家在地面扫描边缘）。
 * - 目标：遍历 {@code mc.level.entitiesForRendering} 取 10 格内最近可攻击实体
 * （含 AntiBot 过滤，与 KillAura 一致）。
 * - 距离：{@code player.distanceToSqr} 水平距离。
 * BlockAir）；碰撞盒用 {@code state.getCollisionShape(level, pos)} 判空。
 * - 移动方向：朝目标反向移动 = 目标 yaw + 180°；
 * 把当前 forward/strafe 在"远离方向"坐标系里重投影，取最接近的一个离散
 * 方向组合（f5/f6 ∈ {-1,0,1}）作为拉距离方向。
 * <p>
 * 参数：
 * - Range（3 格）、Disable Near Edge（开）、Edge Range（5）、
 * - Mode：BackWards / Stop（默认 Stop）、Combo To Start（2）。
 */
public class KeepRange extends Module {

 /** 保持的目标距离 */
    private DoubleSetting range;
 /** 靠近边缘时不拉距离 */
    private BoolSetting disableNearEdge;
 /** 边缘检测范围 */
    private DoubleSetting edgeRange;
    /** 模式：Stop=清掉贴近方向的输入 / BackWards=强制朝远离目标方向走 */
    private ChoiceSetting mode;
 /** 连击计数阈值 */
    private IntSetting comboToStart;

 // 内部状态
    private boolean nearEdge;
    private int comboTicks;

    public KeepRange() {
        super("keeprange", "KeepRange", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        range = doubleSetting("Range", 3.0, 0.0, 6.0, 0.1);
        disableNearEdge = boolSetting("Disable Near Edge", true);
        edgeRange = doubleSetting("Edge Range", 5.0, 0.0, 6.0, 1.0,
                () -> disableNearEdge.getValue());
        mode = choiceSetting("Mode", "Stop", java.util.List.of("BackWards", "Stop"));
        comboToStart = intSetting("Combo To Start", 2, 0, 6, 1);
    }

    @Override
    protected void onEnable() {
        nearEdge = false;
        comboTicks = 0;
    }

    @Override
    protected void onDisable() {
        nearEdge = false;
        comboTicks = 0;
    }

    /**
 * 玩家在地面时扫描边缘。
 * 以玩家所在列为中心，edgeRange 范围内找：脚下第 1~5 格都是空气 → 边缘。
 */
    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (nullCheck()) return;
        if (!mc.player.onGround()) {
            return;
        }
        nearEdge = false;
        int i = (int) Math.round(edgeRange.getValue());
        int px = Mth.floor(mc.player.getX());
        int py = Mth.floor(mc.player.getY());
        int pz = Mth.floor(mc.player.getZ());
        for (int dx = -i; dx <= i; dx++) {
            for (int dz = -i; dz <= i; dz++) {
                // 脚下 5 格（含脚下 1 格）全部是空气 → 下方是虚空/悬崖
                boolean allAir = true;
                for (int dy = 0; dy >= -5 && allAir; dy--) {
                    if (!isAirBlock(px + dx, py + dy, pz + dz)) {
                        allAir = false;
                    }
                }
                if (allAir) {
                    nearEdge = true;
                    return;
                }
            }
        }
    }

    /**
 * 改写移动输入保持距离。
 * 只在有目标、非边缘（或禁用边缘保护）时生效。
 */
    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (nullCheck() || mc.gui.screen() != null) {
            return;
        }
        double d = range.getValue();
        if (mc.player.getHealth() <= 7.0f) {
            d -= 0.2;
        }

        Entity target = findTarget(10.0);
        if (target == null || (nearEdge && disableNearEdge.getValue())) {
            comboTicks = 0;
            return;
        }

        if (target instanceof LivingEntity living && living.hurtTime > 0) {
            comboTicks++;
        }
        if (mc.player.hurtTime > 0) {
            comboTicks = 0;
        }

 // 连击门控
        int gate = comboToStart.getValue();
        if (gate > 0 && comboTicks <= gate * 8) {
            return;
        }

 // 水平距离 &lt; range - 0.05 才拉距离
        double dist = horizontalDistanceTo(target);
        if (dist >= d - 0.05) {
            return;
        }

        float forward = event.getForward();
        float strafe = event.getStrafe();
        if (forward == 0.0f && strafe == 0.0f) {
            return;
        }

 // 远离目标的方向 = 目标朝向 + 180°
        float awayYaw = Mth.wrapDegrees(getYawTo(target) - 180.0f);

        // 把当前输入的 8 个可能方向里最接近"远离方向"的离散组合找出来
        float bestForward = 0.0f;
        float bestStrafe = 0.0f;
        float bestDiff = Float.MAX_VALUE;
        float playerYaw = mc.player.getYRot();
        for (float f5 = -1.0f; f5 <= 1.0f; f5++) {
            for (float f6 = -1.0f; f6 <= 1.0f; f6++) {
                if (f5 == 0.0f && f6 == 0.0f) continue;
                // 26.2 世界移动方向 = getInputVector 旋转矩阵
                // (dx,dz) = (strafe·cos(yaw) - forward·sin(yaw), forward·cos(yaw) + strafe·sin(yaw))，
                // MC yaw = atan2(dz,dx) - 90（已 javap 反汇编 Entity.getInputVector 确证）
                double moveYaw = getMovementYaw(playerYaw, f5, f6);
                double diff = Math.abs(Mth.wrapDegrees(awayYaw - (float) moveYaw));
                if (diff < bestDiff) {
                    bestDiff = (float) diff;
                    bestForward = f6;
                    bestStrafe = f5;
                }
            }
        }

        switch (mode.getValue()) {
            case "Stop" -> {
 // Stop：把与后退方向相反的输入清 0
                if (bestForward == forward * -1.0f) {
                    event.setForward(0.0f);
                }
                if (bestStrafe == strafe * -1.0f) {
                    event.setStrafe(0.0f);
                }
            }
            case "BackWards" -> {
                // BackWards：强制往最接近"远离目标"方向移动
                event.setForward(bestForward);
                event.setStrafe(bestStrafe);
            }
            default -> {
            }
        }
    }

    // ================= 工具方法 =================

 /** 10 格内最近可攻击实体 */
    private Entity findTarget(double radius) {
        if (mc.level == null || mc.player == null) return null;
        Entity best = null;
        double bestDistSqr = radius * radius;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isValidTarget(living)) continue;
            double distSqr = mc.player.distanceToSqr(living.getX(), living.getY(), living.getZ());
            if (distSqr <= bestDistSqr) {
                bestDistSqr = distSqr;
                best = living;
            }
        }
        return best;
    }

    /** 目标有效性（与 KillAura 一致：不死 / 非自己 / 非坐骑 / 非 bot） */
    private boolean isValidTarget(LivingEntity living) {
        if (living == mc.player) return false;
        if (living == mc.player.getVehicle()) return false;
        if (mc.getCameraEntity() != null && living == mc.getCameraEntity()) return false;
        if (!living.isAlive() || living.isDeadOrDying()) return false;
        if (AntiBot.get() != null && AntiBot.get().isBot(living)) return false;
        return true;
    }

 /** 与目标的水平距离 */
    private double horizontalDistanceTo(Entity target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

 /** 目标相对玩家的 yaw */
    private float getYawTo(Entity entity) {
        double dx = entity.getX() - mc.player.getX();
        double dz = entity.getZ() - mc.player.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /**
 * 给定玩家 yaw 与 strafe/forward 输入，计算世界移动方向的 MC yaw。
 * 26.2 {@code Entity.getInputVector(relative, speed, yRot)}（已 javap 反汇编）：
 * {@code dx = strafe·cos(yaw) - forward·sin(yaw)}、
 * {@code dz = forward·cos(yaw) + strafe·sin(yaw)}，其中 yaw = getYRot 弧度。
 * 世界朝向 yaw_MC = atan2(dz, dx) - 90（与 getYawTo 同一坐标系）。
 */
    private double getMovementYaw(float playerYaw, float strafe, float forward) {
        double yawRad = Math.toRadians(playerYaw);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);
        double dx = strafe * cos - forward * sin;
        double dz = forward * cos + strafe * sin;
        return Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

 /** 某方块位置是否为空气 */
    private boolean isAirBlock(int x, int y, int z) {
        if (mc.level == null) return false;
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return true;
        // 非空气但碰撞盒为空（如草/空气类似物）也按可通过处理
        return state.getCollisionShape(mc.level, pos).isEmpty();
    }

    @Override
    public String getInfo() {
        return mode.getValue();
    }
}
