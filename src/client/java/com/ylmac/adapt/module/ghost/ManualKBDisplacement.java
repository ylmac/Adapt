package com.ylmac.adapt.module.ghost;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.AttackEntityEvent;
import com.ylmac.adapt.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.event.impl.LevelUpdateEvent;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.event.impl.SendPositionEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ManualKBDisplacement 手动击退位移引导。
 * <p>
 * 完整逻辑：
 * - 攻击（AttackEntityEvent）命中后武装 2 tick，把被攻击者设为 target
 * - 被攻击者处于受击状态期间，扫描被攻击者
 * 周围 32 个方向 × 0.8~5.0 距离的 3D 采样点，找危险（岩浆/蛛网/火/虚空/深坑/水/仙人掌）
 * - 危险打分：越近越优先（score = 基准 - 距离 × 系数），取最高分方案
 * - 得到期望的"击退落点方向"（plan.yaw：从被攻击者指向危险方向）
 * - 旋转引导：服务器偏航（SendPosition 的 yaw）平滑转向 plan.yaw（rotationSpeed 度/tick），
 * 俯仰跟随目标（pitch 不变，仅 yaw 被替换）
 * - 移动引导：若 26.2 的速度水平分量仍把玩家推向危险，则向 plan.yaw 方向补 W 键
 * 输入（等同原版"击退后朝危险方向走"的静默操作）
 * - 限制：疾跑（有冲量）/ 手持击退附魔剑（有附魔击退）才视为有击退源；
 * 坠落中可暴击时不引导
 * - 安全：世界切换（LevelUpdateEvent）、开关模块都会 reset
 * - Debug：打印命中/拒绝/应用信息
 * <p>
 * 26.2 API 对照（javap 确证）：
 * - LivingEntity.hurtTime / hurtDuration 公开字段；Entity.getDeltaMovement（Vec3 可变）
 * - level.getBlockCollisions(entity, aabb)（CollisionGetter 默认方法）判断碰撞
 * - BlockState.isAir / state.getFluidState.isEmpty；Blocks.FIRE/CACTUS/COBWEB/LAVA
 * - WebBlock.class 判蜘蛛网；Fluids.WATER/LAVA 判流体（isSourceOfType）
 * - EnchantmentHelper.getItemEnchantmentLevel(Enchantments.KNOCKBACK, itemStack)
 * - Entity.isSprinting / isFallFlying / hasEffect(MobEffects.BLINDNESS) / onClimbable
 * - Mth.wrapDegrees / Mth.floor / Mth.clamp；Minecraft.startAttack（AttackEntityEventMixin 注入）
 * - 移动引导：KeyboardInputMixin 发布的 KeyboardInputEvent 里改写 forward/strafe
 */
public class ManualKBDisplacement extends Module {

    /** 旋转速度（度/tick，1-20，默认 8） */
    private final IntSetting rotationSpeed;
    /** 追踪范围（3.0-8.0，默认 6.0） */
    private final DoubleSetting trackingRange;
    /** Debug 输出 */
    private final BoolSetting debug;
    /** 移动引导开关（向危险方向补 W，默认开） */
    private final BoolSetting moveAssist;

    // ---------- 内部状态 ----------
    /** 当前被攻击的目标（受击者，即"会被击退"的实体） */
    private LivingEntity target;
    /** 当前引导方案（危险方向采样结果） */
    private DisplacementSample plan;
    /** 武装 tick：攻击后短时间内即使 hurtTime 尚未出现也保持目标 */
    private int armedTicks;
    /** 最后 debug 消息（去重用） */
    private String lastDebugMessage = "";
    private int lastDebugTick = -1;
    /** 移动引导的有效 tick 窗口 */
    private int moveAssistTicks;
    /** 模块开关前是否有玩家（onEnable 状态恢复用） */
    private boolean wasPlayerPresent;

    public ManualKBDisplacement() {
        super("manualkbdisplacement", "ManualKBDisplacement", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        rotationSpeed = intSetting("旋转速度", 8, 1, 20, 1);
        trackingRange = doubleSetting("追踪范围", 6.0, 3.0, 8.0, 0.1);
        debug = boolSetting("Debug", false);
        moveAssist = boolSetting("移动引导", true);
    }

    @Override
    protected void onEnable() {
        this.resetState();
        this.moveAssistTicks = 0;
        this.wasPlayerPresent = mc.player != null;
    }

    @Override
    protected void onDisable() {
        this.resetState();
        this.moveAssistTicks = 0;
    }

 /** 世界切换（LevelUpdateEvent）→ 重置 */
    @EventHandler
    private void onWorldChange(LevelUpdateEvent event) {
        this.resetState();
    }

    /**
 * 攻击事件：命中后武装 2 tick。
 * "killaura 未启用"过滤；26.2 无 KillAura 单例查询，这里同样不区分来源，
 * 统一以"被攻击者"为目标。
 */
    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (!this.isEnabled()) return;
        Entity entity = event.getEntity();
        if (event.isCancelled() || !(entity instanceof LivingEntity living)) return;
        this.target = living;
        this.armedTicks = 2;
        this.logDebug("armed", null, living);
    }

    /**
 * 每 tick 主循环：
 * 满足全部条件时计算危险方向方案并应用旋转/移动引导。
 */
    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (!this.isEnabled() || this.target == null) return;
        if (nullCheck()) return;
        if (mc.gui.screen() != null) return; // 打开界面不引导

        if (this.armedTicks > 0) {
            this.armedTicks--;
        }

        LivingEntity living = this.target;
        // 目标失效（离开追踪范围/死亡）
        if (!this.isTargetValid(living)) {
            this.logDebug("rejected:no-target", null, living);
            this.resetState();
            return;
        }
        // 没有击退源（不疾跑、武器无击退附魔）→ 不引导
        if (!this.hasKnockbackSource()) {
            this.logDebug("rejected:no-kb-source", null, living);
            return;
        }
        // 受击状态已结束（hurtTime 归零且武装期过）→ 目标失效
        if (living.hurtTime <= 0 && this.armedTicks <= 0) {
            this.logDebug("rejected:hurt-time-ended", null, living);
            this.resetState();
            return;
        }
        if (this.isCritFalling()) {
            this.logDebug("rejected:crit-priority", null, living);
            return;
        }

        // 计算危险方向方案（32 方向 × 距离采样）
        this.plan = this.buildPlan(living);
        if (this.plan == null) {
            this.logDebug("rejected:no-plan", null, living);
            return;
        }

        this.applySteering(living, this.plan);
        this.logDebug("applied", this.plan, living);
    }

    // ================= 核心逻辑 =================

 /** 目标是否有效：存活、世界有效、在追踪范围内 */
    private boolean isTargetValid(LivingEntity living) {
        return living != null
                && living.isAlive()
                && !living.isDeadOrDying()
                && mc.player != null
                && mc.level != null
                && mc.player.distanceTo(living) <= this.trackingRange.getValue().floatValue() + 1.0F;
    }

 /** 是否有击退源：疾跑中 或 主手有击退附魔 */
    private boolean hasKnockbackSource() {
        if (mc.player == null) return false;
        if (mc.player.isSprinting()) return true;
        // 26.2 附魔查询（javap 确证）：EnchantmentHelper.getItemEnchantmentLevel(holder, itemStack)
        // 需经 RegistryAccess 把 Enchantments.KNOCKBACK 的 ResourceKey 换成 Holder
        if (mc.level == null) return false;
        Holder<net.minecraft.world.item.enchantment.Enchantment> kbHolder = mc.level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.KNOCKBACK);
        int level = EnchantmentHelper.getItemEnchantmentLevel(kbHolder, mc.player.getMainHandItem());
        return level > 0;
    }

 /** 是否坠落暴击：下落中不引导 */
    private boolean isCritFalling() {
        Player player = mc.player;
        if (player == null) return false;
        boolean canCrit = player.fallDistance > 0.0F
                && !player.onGround()
                && !player.onClimbable()
                && !player.isInWater()
                && !player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                && player.getVehicle() == null;
        return canCrit && player.getDeltaMovement().y < 0.0;
    }

    /**
 * 扫描被攻击者周围 32 个方向、0.8~5.0 距离的采样点，
 * 找危险并打分（越近分越高），返回最高分方案。
 */
    private DisplacementSample buildPlan(LivingEntity living) {
        AABB box = living.getBoundingBox();
        DisplacementSample best = null;

        for (int i = 0; i < 32; i++) {
            double theta = Math.PI * 2.0 * i / 32.0;
            double dx = -Math.sin(theta);
            double dz = Math.cos(theta);

            for (double dist = 0.8; dist <= 5.0; dist += 0.35) {
                AABB sampleBox = box.move(dx * dist, 0.0, dz * dist);
                DisplacementSample sample = this.buildSample(living, sampleBox, dx, dz, dist);
                if (sample != null && (best == null || sample.score > best.score)) {
                    best = sample;
                }

 // 采样点撞到方块就停止向该方向延伸
                if (this.hasBlockCollision(living, sampleBox.contract(0.02, 0.0, 0.02))) {
                    break;
                }
            }
        }

        // 分数不够（>= 45）不引导
        return best != null && best.score >= 45.0 ? best : null;
    }

    /**
 * 对单个采样点检测危险并打分：
 * 岩浆 150 / 蛛网 125 / 火 100 / 虚空 145 / 深坑 108 / 仙人掌 96 / 沟壑 88 / 水 58，
 * 距离越近分越高（减去 距离 × 系数）。
 */
    private DisplacementSample buildSample(LivingEntity living, AABB box, double dirX, double dirZ, double dist) {
        AABB bodyBox = box.contract(0.05, 0.0, 0.05);
        AABB belowBox = bodyBox.move(0.0, -0.35, 0.0);
        String hazard = null;
        double score = Double.NEGATIVE_INFINITY;

        if (this.containsFluid(bodyBox, Fluids.LAVA) || this.containsFluid(belowBox, Fluids.LAVA)) {
            hazard = "Lava";
            score = 150.0 - dist * 8.0;
        } else if (this.containsBlockClass(bodyBox, WebBlock.class) || this.containsBlockClass(belowBox, WebBlock.class)) {
            hazard = "Web";
            score = 125.0 - dist * 7.0;
        } else if (this.containsFireOrLava(bodyBox) || this.containsFireOrLava(belowBox)) {
            hazard = "Fire";
            score = 100.0 - dist * 7.0;
        } else if (!this.containsBlock(bodyBox, Blocks.CACTUS) && !this.containsBlock(belowBox, Blocks.CACTUS)) {
            double drop = this.getDropDistance(belowBox, 24);
            if (drop < 0.0) {
                hazard = belowBox.minY <= 8.0 ? "Void" : "Deep Drop";
                score = (belowBox.minY <= 8.0 ? 145.0 : 108.0) - dist * 7.0;
            } else if (drop >= 4.0) {
                hazard = "Ditch";
                score = 88.0 + Math.min(drop, 10.0) * 3.5 - dist * 6.0;
            } else if (this.containsFluid(bodyBox, Fluids.WATER) || this.containsFluid(belowBox, Fluids.WATER)) {
                hazard = "Water";
                score = 58.0 + Math.max(0.0, drop) * 2.0 - dist * 5.0;
            }
        } else {
            hazard = "Cactus";
            score = 96.0 - dist * 6.0;
        }

        if (hazard == null) {
            return null;
        }

 // 期望 yaw：从被攻击者指向危险方向
        float yaw = (float) Math.toDegrees(Math.atan2(dirZ, dirX)) - 90.0F;
        float pitch = mc.player.getXRot();
        return new DisplacementSample(yaw, pitch, dist, score, hazard);
    }

    /**
 * 采样点下方到地面的下落距离：
 * 沿 XZ 范围逐列向下找第一个有碰撞盒的方块，返回 box.minY - 方块顶；
 * 任一列 24 格内无方块 → 虚空（返回 -1）。
 */
    private double getDropDistance(AABB box, int maxDepth) {
        int minX = Mth.floor(box.minX + 1.0E-4);
        int maxX = Mth.floor(box.maxX - 1.0E-4);
        int minZ = Mth.floor(box.minZ + 1.0E-4);
        int maxZ = Mth.floor(box.maxZ - 1.0E-4);
        int startY = Mth.floor(box.minY) - 1;
        int endY = Math.max(0, startY - maxDepth);
        double best = Double.POSITIVE_INFINITY;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean found = false;
                for (int y = startY; y >= endY; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    // 取方块碰撞盒（非空 = 有支撑）
                    var shape = state.getCollisionShape(mc.level, pos);
                    if (!shape.isEmpty()) {
                        double gap = Math.max(0.0, box.minY - shape.max(Direction.Axis.Y));
                        best = Math.min(best, gap);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return -1.0; // 该列是虚空
                }
            }
        }
        return best == Double.POSITIVE_INFINITY ? -1.0 : best;
    }

    // ================= 危险检测 =================

 /** AABB 内是否有指定流体 */
    private boolean containsFluid(AABB box, net.minecraft.world.level.material.Fluid fluid) {
        int minX = Mth.floor(box.minX + 1.0E-4);
        int maxX = Mth.floor(box.maxX - 1.0E-4);
        int minY = Mth.floor(box.minY + 1.0E-4);
        int maxY = Mth.floor(box.maxY - 1.0E-4);
        int minZ = Mth.floor(box.minZ + 1.0E-4);
        int maxZ = Mth.floor(box.maxZ - 1.0E-4);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    FluidState fluidState = mc.level.getBlockState(new BlockPos(x, y, z)).getFluidState();
                    if (!fluidState.isEmpty() && fluidState.isSourceOfType(fluid)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

 /** AABB 内是否含指定方块 */
    private boolean containsBlock(AABB box, Block block) {
        int minX = Mth.floor(box.minX + 1.0E-4);
        int maxX = Mth.floor(box.maxX - 1.0E-4);
        int minY = Mth.floor(box.minY + 1.0E-4);
        int maxY = Mth.floor(box.maxY - 1.0E-4);
        int minZ = Mth.floor(box.minZ + 1.0E-4);
        int maxZ = Mth.floor(box.maxZ - 1.0E-4);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir() && state.getBlock() == block) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

 /** AABB 内是否含指定方块类 */
    private boolean containsBlockClass(AABB box, Class<? extends Block> type) {
        int minX = Mth.floor(box.minX + 1.0E-4);
        int maxX = Mth.floor(box.maxX - 1.0E-4);
        int minY = Mth.floor(box.minY + 1.0E-4);
        int maxY = Mth.floor(box.maxY - 1.0E-4);
        int minZ = Mth.floor(box.minZ + 1.0E-4);
        int maxZ = Mth.floor(box.maxZ - 1.0E-4);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir() && type.isInstance(state.getBlock())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
 * AABB 内是否含火/岩浆。
 * 26.2 已无 FLOWING_LAVA 方块常量：流动岩浆统一用流体检测（isSourceOfType(Fluids.LAVA)），
 * 火则查 Blocks.FIRE。
 */
    private boolean containsFireOrLava(AABB box) {
        return this.containsBlock(box, Blocks.FIRE) || this.containsFluid(box, Fluids.LAVA);
    }

 /** AABB 是否与方块碰撞 */
    private boolean hasBlockCollision(Entity entity, AABB box) {
        if (mc.level == null) return false;
        var it = mc.level.getBlockCollisions(entity, box).iterator();
        return it.hasNext();
    }

    // ================= 引导应用 =================

    /**
 * 应用旋转引导：
 * yaw 平滑转向方案方向（rotationSpeed 度/tick），pitch 保持当前，
 * 渲染跟随（玩家视角），发包旋转由 SendPositionEvent 处理。
 */
    private void applySteering(LivingEntity living, DisplacementSample plan) {
        Player player = mc.player;
        if (player == null) return;

        // yaw 平滑逼近（Mth.wrapDegrees 处理 360° 环绕）
        float currentYaw = player.getYRot();
        float targetYaw = plan.yaw;
        float diff = Mth.wrapDegrees(targetYaw - currentYaw);
        float maxStep = this.rotationSpeed.getValue().floatValue();
        float newYaw = currentYaw + (diff > maxStep ? maxStep : diff < -maxStep ? -maxStep : diff);
        player.setYRot(newYaw);
        // pitch 保持当前（不改变玩家俯仰，避免视角跳动）
        this.visualYaw = newYaw;
        this.visualPitch = player.getXRot();

        // 移动引导窗口：接下来 3 tick 内朝危险方向补移动输入（向 plan.yaw 正方向走）
        this.moveAssistTicks = 3;
    }

    /** 渲染/发包用旋转 */
    private float visualYaw;
    private float visualPitch;

    /**
 * 发包旋转（静默）：把服务器看到的 yaw 设为引导方向。
 * 26.2 LocalPlayer.sendPosition 由 SendPositionEvent 拦截（mixin 已接入），
 */
    @EventHandler
    private void onSendPosition(SendPositionEvent event) {
        if (!this.isEnabled() || this.plan == null) return;
        if (this.target == null) return;
        event.setYaw(this.visualYaw);
        event.setPitch(this.visualPitch);
    }

    /**
 * 移动引导：向危险方向补 W（forward）输入。
 * 26.2 输入链路：KeyboardInput.tick → KeyboardInputMixin 发布 KeyboardInputEvent
 * → 本模块改写 forward，再写回 keyPresses + moveVector（已确证），
 */
    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (!this.isEnabled() || !this.moveAssist.getValue()) return;
        if (this.moveAssistTicks <= 0 || this.plan == null) return;
        this.moveAssistTicks--;
        // 只在不按前进键时补（避免与手动输入冲突）
        if (event.getForward() > 0.0F) return;
        // 判断玩家移动方向是否朝向危险（若已背向危险则不用补）
        Player player = mc.player;
        if (player == null) return;
        float playerYaw = player.getYRot();
        float yawDiff = Math.abs(Mth.wrapDegrees(this.plan.yaw - playerYaw));
        if (yawDiff > 100.0F) return; // 玩家正看向安全方向（背对危险），不补
        event.setForward(1.0F);
        event.setSprint(false); // 引导时不疾跑（保持受控）
    }

    // ================= 工具 =================

 /** 重置全部内部状态 */
    private void resetState() {
        this.target = null;
        this.plan = null;
        this.armedTicks = 0;
        this.lastDebugMessage = "";
        this.lastDebugTick = -1;
        this.moveAssistTicks = 0;
    }

 /** Debug 输出 */
    private void logDebug(String msg, DisplacementSample sample, LivingEntity living) {
        if (!this.debug.getValue() || mc.player == null) return;
        String s = sample == null
                ? String.format("%s hurt=%d", msg, living == null ? -1 : living.hurtTime)
                : String.format("%s %s score=%.1f yaw=%.1f pitch=%.1f dist=%.2f hurt=%d",
                msg, sample.hazard, sample.score, sample.yaw, sample.pitch,
                sample.distance, living == null ? -1 : living.hurtTime);
        if (!s.equals(this.lastDebugMessage) || mc.player.tickCount - this.lastDebugTick >= 8) {
            this.lastDebugMessage = s;
            this.lastDebugTick = mc.player.tickCount;
            com.ylmac.adapt.AdaptClient.LOGGER.info("[KB Displacement] {}", s);
        }
    }

    @Override
    public String getInfo() {
        return this.plan == null ? "idle" : this.plan.hazard;
    }

    // ================= 内部数据类 =================

 /** 引导方案 */
    private static final class DisplacementSample {
        final float yaw;
        final float pitch;
        final double distance;
        final double score;
        final String hazard;

        DisplacementSample(float yaw, float pitch, double distance, double score, String hazard) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.distance = distance;
            this.score = score;
            this.hazard = hazard;
        }
    }
}
