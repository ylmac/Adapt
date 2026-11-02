package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.Set;

/**
 * Clipper 垂直穿地/上升。
 * <p>
 * 原理（26.2 客户端 API，已 javap 确证）：
 * - 每 tick 向上（+Y）和向下（-Y）各扫描 clipHeight 格：沿方向逐格推进，
 * 一旦路径上出现过实心方块（wallBetween），之后再遇到能容纳玩家的空位
 * （玩家碰撞箱整体无碰撞）就记为可传送点。这样保证玩家与目标之间确实
 * "隔着墙"，不会传送到普通可走空间。
 * 的 isPressed，即按下瞬间触发一次；这里同样用按下边沿检测 — 每 tick
 * 记录按键状态，上升沿（false→true）触发一次，保证"按一下传一次"）。
 * - 传送后 cooldownTicks 冷却，期间不扫描，防止连发。
 * <p>
 * 该提示不属于核心穿墙功能，故省略。
 */
public class Clipper extends Module {

    /** 扫描距离（格）：向上/向下最多找这么远（实时读设置，不构造快照） */
    private final IntSetting clipHeightSetting;
 /** 传送冷却 tick 数 */
    private final IntSetting cooldownTicksSetting;

    /** 当前可传送的方向集合（本 tick 扫描结果） */
    private final Set<Direction> clippableFacings = EnumSet.noneOf(Direction.class);

    /** 冷却倒计时 */
    private int cooldown = 0;

    /** 上一 tick 的跳跃/潜行按键状态（边沿检测用） */
    private boolean prevJump = false;
    private boolean prevShift = false;

    public Clipper() {
        super("clipper", "Clipper", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        clipHeightSetting = intSetting("clip-height", 5, 1, 50, 1);
        cooldownTicksSetting = intSetting("cooldown-ticks", 5, 1, 20, 1);
    }

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck() || mc.player.isDeadOrDying()) {
            return;
        }

        // 边沿状态每 tick 都推进（即使处于冷却中也记录当前按键，避免
        // 冷却结束瞬间"按住不放"被误判为一次新的按下）
        boolean jumpDown = mc.player.input.keyPresses.jump();
        boolean shiftDown = mc.player.input.keyPresses.shift();
        boolean jumpPressed = jumpDown && !prevJump;
        boolean shiftPressed = shiftDown && !prevShift;
        prevJump = jumpDown;
        prevShift = shiftDown;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        int clipHeight = clipHeightSetting.getValue();
        clippableFacings.clear();

        // 扫描上、下两个方向的可传送点（findClip 会把可传送方向加入 clippableFacings）
        findClip(Direction.UP, clipHeight);
        findClip(Direction.DOWN, clipHeight);

 // 按键按下边沿触发一次
        Direction target = null;
        if (shiftPressed) {
            target = Direction.DOWN;
        } else if (jumpPressed) {
            target = Direction.UP;
        }

        if (target != null && clippableFacings.contains(target)) {
            BlockPos pos = findClip(target, clipHeight);
            if (pos != null) {
                mc.player.setPos(
                        mc.player.getX(),
                        pos.getY() + 0.5D,
                        mc.player.getZ()
                );
                cooldown = cooldownTicksSetting.getValue();
            }
        }
    }

    /** 沿指定方向逐格扫描：找到"墙后空位"就记录该方向为可传送；返回首个空位位置，无则 null */
    private BlockPos findClip(Direction facing, int length) {
        if (length <= 0) {
            return null;
        }
        BlockPos pos = mc.player.blockPosition();
        boolean wallBetween = false;

        for (int i = 0; i < length; i++) {
            pos = pos.offset(facing.getUnitVec3i());

            // 向下扫描时防止掉出世界底部（虚空中无意义）
            if (facing == Direction.DOWN && isOverVoid(pos, 50)) {
                return null;
            }

            if (wallBetween && canFit(pos)) {
                clippableFacings.add(facing);
                return pos;
            }
            if (!isPassable(pos)) {
                wallBetween = true;
            }
        }
        return null;
    }

    /** 该位置向下指定格数是否全是空气（虚空检查） */
    private boolean isOverVoid(BlockPos pos, int depth) {
        int airCount = 0;
        for (int i = 1; i <= depth; i++) {
            if (!mc.level.getBlockState(pos.below(i)).isAir()) {
                break;
            }
            airCount++;
        }
        return airCount >= depth;
    }

    /** 玩家碰撞箱（约 0.6 x 1.8）在该方块位置能否无碰撞容纳 */
    private boolean canFit(BlockPos pos) {
        AABB box = mc.player.getBoundingBox();
        double width = box.getXsize();
        double height = box.getYsize();
        double centerX = mc.player.getX();
        double centerZ = mc.player.getZ();
        AABB target = new AABB(
                centerX - width / 2.0D, pos.getY(), centerZ - width / 2.0D,
                centerX + width / 2.0D, pos.getY() + height, centerZ + width / 2.0D
        );
        return mc.level.noCollision(mc.player, target);
    }

    /** 该位置方块是否可穿过（空气或碰撞箱为空） */
    private boolean isPassable(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(mc.level, pos).isEmpty();
    }

    @Override
    protected void onDisable() {
        clippableFacings.clear();
        cooldown = 0;
        prevJump = false;
        prevShift = false;
    }
}
