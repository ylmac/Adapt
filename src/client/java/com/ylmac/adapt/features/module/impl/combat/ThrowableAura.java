package com.ylmac.adapt.features.module.impl.combat;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * ThrowableAura —— 自动投掷鸡蛋/雪球（移植自 Rise 6.9.5 ThrowableAura）。
 * <p>
 * Rise 原版流程（逐项对应）：
 * 1. PreUpdateEvent 每 tick：延迟满 → getTarget() 找最近目标（距离 / 视角 /
 *    穿墙 / 反bot / 玩家-怪物分类筛选）
 * 2. findThrowableSlot() 优先雪球后鸡蛋，切到投掷物槽位（autoSwitch）
 * 3. isFacing() 视角对准 → h() 把玩家旋转指向目标预测位置（含运动预测）
 * 4. throwAt()：swingItem + sendUseItem + C08PacketPlayerBlockPlacement（投掷）
 *    → 恢复原旋转
 * <p>
 * 26.2 移植（API 经 javap /Users/ylmac/Documents/26.2 确证）：
 * - 1.8.9 C02/swingItem → 26.2 服务端生成投掷物走 use 链：
 *   {@code mc.gameMode.useItem(player, hand)}（内部 startPrediction →
 *   ServerboundUseItemPacket(hand, seq, yaw, pitch) → ItemStack.use →
 *   服务端 Projectile.spawnProjectileFromRotation 按玩家旋转发射，SnowballItem.use
 *   已反汇编确认）。投掷前临时改玩家 yRot/xRot（Rise 同款做法，用后还原），
 *   使服务端按瞄准目标的旋转发射；useItem 的包内旋转也取自 player.getYRot/XRot
 *   （MultiPlayerGameMode.lambda$useItem$0 已反汇编确认）。
 * - 换槽：{@code player.inventory.setSelectedSlot(slot)} + 26.2 换槽协议
 *   {@code ServerboundSetCarriedItemPacket}（javap 确认存在）经 Connection 静默发送。
 * - 目标遍历：{@code mc.level.entitiesForRendering()}（ClientLevel 公开迭代器）。
 * - 投掷冷却：Rise 的 throwDelay（tick）。
 * <p>
 * 注意：本模块会真实改动玩家视角（旋转到目标后恢复），多模块共用时可能冲突，
 * 因此保留 Rise 的 Module Check（AimBacktrack 在 26.2 无对应模块，此处保留为
 * 自定义的"旋转期间禁投"窗口：rotationActive 由其他旋转模块置位）。
 */
public class ThrowableAura extends Module {

    /** 是否允许投掷（其他旋转模块可置 false 避免旋转冲突） */
    private boolean canThrow;

    /** 投掷冷却 tick */
    private int delayTicks;
    private final IntSetting throwDelay;

    /** 目标筛选 */
    private final DoubleSetting range;
    private final DoubleSetting minimumRange;
    private final DoubleSetting rotationRange;
    private final DoubleSetting fov;
    private final BoolSetting players;
    private final BoolSetting mobs;
    private final BoolSetting playerTeammates;
    private final BoolSetting throughWalls;
    private final BoolSetting antiBot;
    private final BoolSetting autoSwitch;
    private final BoolSetting prediction;
    private final BoolSetting snowballs;
    private final BoolSetting eggs;

    /** 上次使用的槽位（onDisable 还原） */
    private int previousSlot = -1;

    private boolean throwing;

    public ThrowableAura() {
        super("throwableaura", "ThrowableAura", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        range = doubleSetting("范围", 6.0, 1.0, 12.0, 0.1);
        minimumRange = doubleSetting("最小范围", 3.0, 1.0, 10.0, 0.1);
        throwDelay = intSetting("投掷延迟", 5, 0, 100, 1);
        fov = doubleSetting("FOV", 180.0, 30.0, 360.0, 1.0);
        rotationRange = doubleSetting("旋转范围", 8.0, 1.0, 15.0, 0.1);
        playerTeammates = boolSetting("队友", true);
        players = boolSetting("玩家", true);
        mobs = boolSetting("怪物", true);
        autoSwitch = boolSetting("自动切换", true);
        prediction = boolSetting("预测", true);
        antiBot = boolSetting("反Bot", true);
        snowballs = boolSetting("雪球", true);
        eggs = boolSetting("鸡蛋", true);
        throughWalls = boolSetting("穿墙", true);
        canThrow = true;
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (mc.gui.screen() != null) return;
        if (!canThrow) return;

        if (delayTicks < throwDelay.getValue()) {
            delayTicks++;
            return;
        }
        delayTicks = 0;

        LivingEntity target = getTarget();
        if (target == null) return;

        double dist = mc.player.distanceToSqr(target);
        if (dist > range.getValue() * range.getValue()
                || dist <= minimumRange.getValue() * minimumRange.getValue()) {
            return;
        }

        int slot = findThrowableSlot();
        if (slot == -1) return;

        if (!isFacing(target) || !canHit(target)) return;

        int current = mc.player.getInventory().getSelectedSlot();
        if (autoSwitch.getValue() && current != slot) {
            if (previousSlot == -1) {
                previousSlot = current;
            }
            setSelectedSlot(slot);
        }

        ItemStack held = mc.player.getMainHandItem();
        if (!isThrowable(held)) {
            restorePreviousSlot();
            return;
        }

        throwing = true;
        throwAt(target);
        throwing = false;
        restorePreviousSlot();
    }

    @Override
    protected void onEnable() {
        delayTicks = 0;
        previousSlot = -1;
        throwing = false;
        canThrow = true;
    }

    @Override
    protected void onDisable() {
        restorePreviousSlot();
        throwing = false;
        canThrow = true;
    }

    /** 切到投掷物槽位（26.2 客户端 selectedSlot + 服务端 ServerboundSetCarriedItemPacket） */
    private void setSelectedSlot(int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().send(
                    new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot), null);
        }
    }

    /** 还原上次槽位（onDisable/投掷完成） */
    private void restorePreviousSlot() {
        if (autoSwitch.getValue() && previousSlot != -1 && mc.player != null) {
            setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    /**
     * 投掷：临时把玩家旋转指向目标（预测位置）→ useItem（服务端按旋转发射投掷物）
     * → 还原旋转。等价 Rise throwAt：h() 旋转 + swing + sendUseItem + C08。
     */
    private void throwAt(Entity entity) {
        float oldYaw = mc.player.getYRot();
        float oldPitch = mc.player.getXRot();
        rotateTo(entity);
        ItemStack held = mc.player.getMainHandItem();
        if (held != null && !held.isEmpty()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
        mc.player.setYRot(oldYaw);
        mc.player.setXRot(oldPitch);
    }

    /** 把玩家旋转指向实体（Rise h()：atan2 计算 yaw/pitch，含预测） */
    private void rotateTo(Entity entity) {
        Vec3 target = prediction.getValue()
                ? getPredictedPosition(entity)
                : entity.position().add(0.0, entity.getEyeHeight() * 0.5, 0.0);
        Vec3 eye = mc.player.getEyePosition(1.0F);
        double dx = target.x() - eye.x();
        double dy = target.y() - eye.y();
        double dz = target.z() - eye.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI - 90.0);
        float pitch = (float) -(Math.atan2(dy, horizontal) * 180.0 / Math.PI);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
    }

    /** 目标预测位置（Rise getPredictedPosition：距离/1.5 乘速度外推） */
    private Vec3 getPredictedPosition(Entity entity) {
        Vec3 pos = entity.position().add(0.0, entity.getEyeHeight() * 0.5, 0.0);
        double factor = mc.player.distanceTo(entity) / 1.5;
        return pos.add(entity.getDeltaMovement().scale(factor));
    }

    /** 视角是否对准目标（Rise isFacing：视线与目标方向点积 > 0.9） */
    private boolean isFacing(Entity entity) {
        Vec3 target = prediction.getValue()
                ? getPredictedPosition(entity)
                : entity.position().add(0.0, entity.getEyeHeight() * 0.5, 0.0);
        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 look = mc.player.getLookAngle().normalize();
        Vec3 toTarget = target.subtract(eye).normalize();
        return look.dot(toTarget) > 0.9;
    }

    /** 目标是否可命中（Rise canHit：准星实体 或 射线命中实体） */
    private boolean canHit(Entity entity) {
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            return ((net.minecraft.world.phys.EntityHitResult) mc.hitResult).getEntity() == entity;
        }
        // 用当前玩家旋转做射线（Rise RayCastUtil.rayCast(yaw, pitch, range)）
        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 look = mc.player.getLookAngle();
        Vec3 end = eye.add(look.scale(range.getValue()));
        AABB box = entity.getBoundingBox().inflate(entity.getPickRadius() + 0.1);
        return box.clip(eye, end).isPresent();
    }

    /** 找最近的目标（Rise getTarget 逻辑：距离/视角/穿墙/反Bot/玩家/怪物） */
    private LivingEntity getTarget() {
        Iterable<Entity> entities = mc.level.entitiesForRendering();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isValidTarget(living)) continue;
            double dist = mc.player.distanceToSqr(living);
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }
        return best;
    }

    /** 目标筛选（Rise getTarget 的 filter 链） */
    private boolean isValidTarget(LivingEntity entity) {
        if (entity == mc.player || !entity.isAlive()) return false;
        double dist = mc.player.distanceToSqr(entity);
        if (dist > rotationRange.getValue() * rotationRange.getValue()
                || dist <= minimumRange.getValue() * minimumRange.getValue()) {
            return false;
        }
        if (!isInFOV(entity)) return false;
        // 穿墙开 → 无视视线；穿墙关 → 要求视线（Rise 语义）
        if (!throughWalls.getValue() && !mc.player.hasLineOfSight(entity)) return false;
        if (antiBot.getValue() && entity.getType().getDescriptionId().contains("bot")) return false;
        if (entity instanceof Player player) {
            if (!players.getValue()) return false;
            if (playerTeammates.getValue() && isTeammate(player)) return false;
            return true;
        }
        return entity instanceof Monster && mobs.getValue();
    }

    /** 是否同队（Rise PlayerUtil.sameTeam：Team 判定） */
    private boolean isTeammate(Player other) {
        if (mc.player.getTeam() == null || other.getTeam() == null) return false;
        return mc.player.getTeam().isAlliedTo(other.getTeam());
    }

    /** 目标是否在视角内（Rise isInFOV：视线夹角 ≤ FOV/2） */
    private boolean isInFOV(Entity entity) {
        if (fov.getValue() >= 360.0) return true;
        Vec3 look = mc.player.getLookAngle().normalize();
        Vec3 toTarget = entity.position().add(0.0, entity.getEyeHeight() * 0.5, 0.0)
                .subtract(mc.player.getEyePosition(1.0F)).normalize();
        double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(toTarget), -1.0, 1.0)));
        return angle <= fov.getValue() / 2.0;
    }

    /** 找投掷物槽位（Rise findThrowableSlot：雪球优先、鸡蛋次之，仅热栏） */
    private int findThrowableSlot() {
        if (snowballs.getValue()) {
            int slot = findItem(Items.SNOWBALL);
            if (slot != -1) return slot;
        }
        if (eggs.getValue()) {
            int slot = findItem(Items.EGG);
            if (slot != -1) return slot;
        }
        return -1;
    }

    /** 在热栏找指定物品（Rise SlotUtil.findItem） */
    private int findItem(net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    /** 手上是否投掷物（Rise isThrowable） */
    private boolean isThrowable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return snowballs.getValue() && stack.getItem() == Items.SNOWBALL
                || eggs.getValue() && stack.getItem() == Items.EGG;
    }

    @Override
    public String getInfo() {
        return throwing ? "投掷中" : null;
    }
}
