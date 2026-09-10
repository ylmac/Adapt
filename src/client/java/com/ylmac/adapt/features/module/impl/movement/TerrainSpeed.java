package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec2;

/**
 * TerrainSpeed（地形加速）：在特殊方块（冰/灵魂沙/黏液块/藤蔓/梯子）上加速。
 * <p>
 * 移植自 Rise 6.9.5 TerrainSpeed（Watchdog 模式在台阶/楼梯上提速、Bloxd 模式在
 * 冰面上提速），按任务要求精简为"检测脚下方块类型 → 按类型给加速度/滑度"的
 * 通用实现（26.2 对应 LB nextgen IceSpeed 的做法——通过修改 deltaMovement 提速）：
 * <ul>
 *   <li>Watchdog 模式（半砖/楼梯提速，对应 Rise WatchdogTerrainSpeed）：
 *       脚踩半砖（SlabBlock）且玩家 y 恰在半砖高度（y == floor(y) + 0.5）时，
 *       取消原版半砖减速并把水平速度拉到目标速度；脚踩楼梯（StairBlock）时
 *       给出更大的目标速度（Rise 在楼梯上 friction * 3.98 的等价提速）。</li>
 *   <li>冰（含蓝冰/浮冰/霜冰）：冰面滑度大难控速，勾选后每 tick 把水平速度
 *       拉向目标速度（防止打滑，速度更快更可控）；目标方向 = 输入方向向量
 *       合成（方向键 + 视角 yaw），而不是直接按 yaw——斜走/侧移时方向正确。</li>
 *   <li>灵魂沙：走路被减速，勾选后取消减速并附加额外水平速度</li>
 *   <li>黏液块：弹跳方块，勾选后下落时加速（更快穿过黏液层）</li>
 *   <li>梯子/藤蔓：攀爬加速（类似 LB FastClimb 的 Motion 模式，水平碰撞
 *       且攀爬时直接给向上速度）</li>
 * </ul>
 * 方块检测：脚下方块 = blockPosition() 下移 1 格（getBlockState → getBlock()，
 * 26.2 API 已 javap 确认）。加速操作 = 修改 player.deltaMovement（26.2 速度字段）。
 */
public class TerrainSpeed extends Module {

    /** 模式：WATCHDOG = 半砖/楼梯提速；ICE = 冰面控速（原 Bloxd 语义） */
    public enum Mode {
        WATCHDOG,
        ICE
    }

    /** 模式（对应 Rise TerrainSpeed 的 Watchdog / Bloxd 两档） */
    private com.ylmac.adapt.features.module.settings.impl.EnumSetting<Mode> mode;
    /** 冰面加速开关（ICE 模式） */
    private BoolSetting iceSpeed;
    /** 冰面目标速度 */
    private DoubleSetting iceTargetSpeed;
    /** 灵魂沙加速开关 */
    private BoolSetting soulSandSpeed;
    /** 灵魂沙水平速度 */
    private DoubleSetting soulSandSpeedAmount;
    /** 黏液块加速开关 */
    private BoolSetting slimeSpeed;
    /** 梯子/藤蔓攀爬加速开关 */
    private BoolSetting climbSpeed;
    /** 攀爬向上速度 */
    private DoubleSetting climbSpeedAmount;
    /** Watchdog 半砖提速开关 */
    private BoolSetting slabSpeed;
    /** Watchdog 半砖目标速度 */
    private DoubleSetting slabTargetSpeed;
    /** Watchdog 楼梯提速开关 */
    private BoolSetting stairSpeed;
    /** Watchdog 楼梯目标速度 */
    private DoubleSetting stairTargetSpeed;

    public TerrainSpeed() {
        super("terrainspeed", "TerrainSpeed", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        mode = enumSetting("模式", Mode.ICE);
        iceSpeed = boolSetting("冰面加速", true, () -> mode.getValue() == Mode.ICE);
        iceTargetSpeed = doubleSetting("冰面目标速度", 0.42, 0.1, 2.0, 0.01,
                () -> mode.getValue() == Mode.ICE && iceSpeed.getValue());
        soulSandSpeed = boolSetting("灵魂沙加速", true, () -> mode.getValue() == Mode.ICE);
        soulSandSpeedAmount = doubleSetting("灵魂沙速度", 0.25, 0.1, 2.0, 0.01,
                () -> mode.getValue() == Mode.ICE && soulSandSpeed.getValue());
        slimeSpeed = boolSetting("黏液块加速", false, () -> mode.getValue() == Mode.ICE);
        climbSpeed = boolSetting("攀爬加速", true, () -> true);
        climbSpeedAmount = doubleSetting("攀爬速度", 0.28, 0.1, 2.0, 0.01,
                () -> climbSpeed.getValue());
        slabSpeed = boolSetting("半砖提速", true, () -> mode.getValue() == Mode.WATCHDOG);
        slabTargetSpeed = doubleSetting("半砖速度", 0.33, 0.1, 2.0, 0.01,
                () -> mode.getValue() == Mode.WATCHDOG && slabSpeed.getValue());
        stairSpeed = boolSetting("楼梯提速", true, () -> mode.getValue() == Mode.WATCHDOG);
        stairTargetSpeed = doubleSetting("楼梯速度", 0.48, 0.1, 2.0, 0.01,
                () -> mode.getValue() == Mode.WATCHDOG && stairSpeed.getValue());
    }

    @Override
    public String getInfo() {
        return mode.getValue().name();
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!mc.player.onGround() && !this.climbSpeed.getValue()) {
            // 冰/灵魂沙/黏液只在地面生效；攀爬加速在空中也生效（梯子判定见下）
            return;
        }

        // 脚下方块：玩家所在方块坐标下移 1 格（BlockPos.below() 已 javap 确认存在）
        BlockPos feetPos = mc.player.blockPosition().below();
        Block block = mc.level.getBlockState(feetPos).getBlock();

        if (mode.getValue() == Mode.WATCHDOG) {
            applyWatchdog(block);
            // Watchdog 模式专注半砖/楼梯；攀爬加速仍可用（梯子判定独立）
        }

        if (this.iceSpeed.getValue() && mode.getValue() == Mode.ICE && isIce(block)) {
            // 冰面：把水平速度拉向目标速度（方向 = 输入方向合成，见 applyIceSpeed）
            applyIceSpeed();
        }

        if (this.soulSandSpeed.getValue() && mode.getValue() == Mode.ICE && block == Blocks.SOUL_SAND) {
            // 灵魂沙：取消原减速（原版 0.4 倍滑度）→ 附加水平速度（输入方向合成）
            net.minecraft.world.phys.Vec3 motion = mc.player.getDeltaMovement();
            double speed = this.soulSandSpeedAmount.getValue();
            double[] dir = inputDirection();
            mc.player.setDeltaMovement(dir[0] * speed, motion.y, dir[1] * speed);
        }

        if (this.slimeSpeed.getValue() && mode.getValue() == Mode.ICE && block == Blocks.SLIME_BLOCK && mc.player.getDeltaMovement().y < 0) {
            // 黏液块：下落时加速穿过（原版黏液会弹，这里加快下落）
            net.minecraft.world.phys.Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(motion.x, motion.y * 1.6, motion.z);
        }

        if (this.climbSpeed.getValue() && mc.player.onClimbable()) {
            // 梯子/藤蔓：攀爬加速（水平碰撞或攀爬中，直接给向上速度；
            // 26.2 Entity.onClimbable() 已 javap 确认）
            net.minecraft.world.phys.Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(motion.x, this.climbSpeedAmount.getValue(), motion.z);
            mc.player.resetFallDistance();
        }
    }

    /**
     * Watchdog 模式（Rise WatchdogTerrainSpeed 语义）：
     * - 半砖（SlabBlock）：玩家脚底 y 恰在半砖高度（floor(y) + 0.5，即踩在半砖
     *   上沿）且按住前进时，把水平速度拉到"半砖速度"设置（Rise 靠 friction 加速，
     *   26.2 无 friction 事件，等价为直接施加目标水平速度）；
     * - 楼梯（StairBlock）：脚底 y 在半砖高度（正在爬楼梯的中间位置）或整数
     *   高度（已踩上楼梯顶面）且按住前进时，把水平速度拉到"楼梯速度"设置
     *   （Rise friction * 3.98 的等价提速；Rise 的判定是 posY == floor+0.5 或
     *   刚离开楼梯的 offStairTicks==0）。
     */
    private void applyWatchdog(Block block) {
        double y = mc.player.getY();
        boolean onSlabTop = Math.abs(y - (Math.floor(y) + 0.5)) < 0.01;
        boolean onStair = Math.abs(y - Math.floor(y)) < 0.01 || onSlabTop;

        if (this.slabSpeed.getValue() && block instanceof SlabBlock
                && onSlabTop && mc.player.input.keyPresses.forward()) {
            double[] dir = inputDirection();
            double speed = this.slabTargetSpeed.getValue();
            net.minecraft.world.phys.Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(dir[0] * speed, motion.y, dir[1] * speed);
            return;
        }

        if (this.stairSpeed.getValue() && block instanceof StairBlock
                && onStair && mc.player.input.keyPresses.forward()) {
            double[] dir = inputDirection();
            double speed = this.stairTargetSpeed.getValue();
            net.minecraft.world.phys.Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(dir[0] * speed, motion.y, dir[1] * speed);
        }
    }

    /** 是否冰类方块（含蓝冰/浮冰/霜冰） */
    private static boolean isIce(Block block) {
        return block == Blocks.ICE
                || block == Blocks.BLUE_ICE
                || block == Blocks.PACKED_ICE
                || block == Blocks.FROSTED_ICE;
    }

    /**
     * 输入方向水平单位向量（x, z）：按视角 yaw 与移动输入（moveVector）合成，
     * 与 vanilla {@code Entity.getInputVector} 的方向计算完全一致（26.2 字节码
     * 已验证）：moveVector = Vec2(左+1/右-1, 前+1/后-1).normalized()（KeyboardInput
     * 的 calculateImpulse 语义），旋转后 worldX = strafe*cos - forward*sin、
     * worldZ = forward*cos + strafe*sin。
     */
    private double[] inputDirection() {
        Vec2 move = mc.player.input.getMoveVector();
        float forward = move.y; // +1 前进 / -1 后退（26.2 有符号约定）
        float strafe = move.x;  // +1 左移 / -1 右移

        double yaw = Math.toRadians(mc.player.getYRot());
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        // 与 vanilla Entity.getInputVector 相同的世界方向合成
        double vx = cosYaw * strafe - sinYaw * forward;
        double vz = sinYaw * strafe + cosYaw * forward;
        double len = Math.hypot(vx, vz);
        if (len < 1.0E-4) {
            // 无输入：保持当前水平方向（不改变玩家已有速度方向）
            net.minecraft.world.phys.Vec3 delta = mc.player.getDeltaMovement();
            double cur = Math.hypot(delta.x, delta.z);
            if (cur < 1.0E-4) {
                return new double[]{0.0, 0.0};
            }
            return new double[]{delta.x / cur, delta.z / cur};
        }
        return new double[]{vx / len, vz / len};
    }

    /** 冰面加速：沿输入方向合成目标速度（保留垂直分量） */
    private void applyIceSpeed() {
        net.minecraft.world.phys.Vec3 motion = mc.player.getDeltaMovement();
        double speed = this.iceTargetSpeed.getValue();
        double[] dir = inputDirection();
        mc.player.setDeltaMovement(dir[0] * speed, motion.y, dir[1] * speed);
    }

}
