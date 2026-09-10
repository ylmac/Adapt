package com.ylmac.adapt.features.module.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PlayerUtil —— 移植 Rise 6.9.5 Scaffold 依赖的 PlayerUtil（3D 最近可放点搜索 + 支撑面排序）。
 * <p>
 * Rise 原版（com.alan.clients.util.player.PlayerUtil）：
 * - a(Vec3 placeOffset, Integer sameYLayer)：以玩家位置为中心 3D 搜索最近可替换方块
 *   （半径 5+|dx|+|dz|），排除交互方块（chest/anvil/crafting_table 等），按距离排序，
 *   sameY 指定时限定 Y 层。
 * - a(BlockPos target, boolean yLess)：找 target 的可点击支撑面（按玩家朝向排序）。
 * - isReplaceable：state.isAir() || state.canBeReplaced()
 * <p>
 * 26.2 对应：level.getBlockState / BlockStateBase.canBeReplaced / Blocks.*（javap 确证）。
 */
public final class PlayerUtil {

    private static final Minecraft mc = Minecraft.getInstance();

    /** 交互方块黑名单（Rise: 放上去会打开 UI 或异常） */
    private static final BlockState[] BLACKLIST = {
            Blocks.CHEST.defaultBlockState(), Blocks.ENDER_CHEST.defaultBlockState(),
            Blocks.TRAPPED_CHEST.defaultBlockState(), Blocks.ANVIL.defaultBlockState(),
            Blocks.CRAFTING_TABLE.defaultBlockState(), Blocks.FURNACE.defaultBlockState(),
            Blocks.HOPPER.defaultBlockState(), Blocks.DISPENSER.defaultBlockState(),
            Blocks.DROPPER.defaultBlockState(), Blocks.NOTE_BLOCK.defaultBlockState(),
            Blocks.JUKEBOX.defaultBlockState(), Blocks.ENCHANTING_TABLE.defaultBlockState(),
            Blocks.BREWING_STAND.defaultBlockState(), Blocks.BEACON.defaultBlockState()
    };

    private PlayerUtil() {
    }

    /**
     * Rise PlayerUtil.a：3D 搜索最近可放置点。
     *
     * @param offset      放置偏移（玩家位置 + offset = 搜索中心）
     * @param sameYLayer  指定 Y 层（null = 任意层）
     * @return 最近可替换方块，或 null
     */
    public static BlockPos findNearestPlaceable(BlockPos offset, Integer sameYLayer) {
        if (mc.player == null || mc.level == null) return null;
        BlockPos center = mc.player.blockPosition().offset(offset);
        int radius = 5 + Math.abs(offset.getX()) + Math.abs(offset.getZ());

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (sameYLayer != null && center.getY() + dy != sameYLayer) continue;
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!isReplaceable(pos)) continue;
                    if (isBlacklisted(pos)) continue;
                    // 排除玩家身体占用的位置（脚 + 身体所在方块）
                    if (pos.equals(mc.player.blockPosition())
                            || pos.equals(mc.player.blockPosition().above())) continue;
                    // ⚠ Rise 语义（原版 PlayerUtil.a：先收集"非可替换方块"再把它们的
                    //   六邻域收进候选）：候选格**必须邻接至少一个实心块**。
                    //   少了这条约束，返回的会是玩家身侧/身后纯空气中的格子，
                    //   射线永远 miss → 搭路几乎不放方块（用户实测："scaffold 报废"）。
                    if (!hasSolidNeighbor(pos)) continue;
                    candidates.add(pos);
                }
            }
        }
        // 按距离排序（Rise: sort by distance）
        candidates.sort(Comparator.comparingDouble(p ->
                mc.player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** 六邻域里是否有实心（不可替换）方块 —— Rise 候选格的唯一来源。 */
    private static boolean hasSolidNeighbor(BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (!isReplaceable(pos.relative(d))) return true;
        }
        return false;
    }

    /**
     * Rise PlayerUtil.a(target, yLess)：找 target 的可点击支撑面（按玩家朝向排序）。
     * <p>
     * 返回的 facing = 被点击的面（支撑方块朝向 target 的面）：
     * - 支撑在 target 上方 → 点其底面（DOWN）
     * - 支撑在 target 下方 → 点其顶面（UP）
     * - 支撑在 target 侧方 → 点其朝向 target 的侧面（dir.getOpposite()）
     * blockFace = target + offset（支撑方块位置）。
     *
     * @param yLess 是否只考虑下方支撑（y < target.y）
     */
    public static EnumFacingOffset findSupportFace(BlockPos target, boolean yLess) {
        if (mc.level == null) return null;
        List<EnumFacingOffset> faces = new ArrayList<>();
        // 上方支撑（target 上方有实心）→ 点击其底面（DOWN）
        if (!yLess && isSolid(target.above())) {
            faces.add(new EnumFacingOffset(Direction.DOWN, new BlockPos(0, 1, 0)));
        }
        // 水平四方向：支撑在 dir 侧 → 点击支撑朝向 target 的面（dir.getOpposite()）
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = target.relative(dir);
            if (isSolid(side)) {
                faces.add(new EnumFacingOffset(dir.getOpposite(), new BlockPos(dir.getStepX(), 0, dir.getStepZ())));
            }
        }
        // 下方支撑（target 下方实心）→ 点击其顶面（UP）
        if (isSolid(target.below())) {
            faces.add(new EnumFacingOffset(Direction.UP, new BlockPos(0, -1, 0)));
        }
        if (faces.isEmpty()) return null;
        // 按玩家朝向排序（Rise: 朝向的方向优先 —— 用支撑相对 target 的偏移方向）
        faces.sort(Comparator.comparingDouble(f -> {
            double yaw = Math.toRadians(mc.player.getYRot());
            double dirX = -Math.sin(yaw);
            double dirZ = Math.cos(yaw);
            double faceX = f.offset.getX();
            double faceZ = f.offset.getZ();
            return -((faceX * dirX + faceZ * dirZ));  // 越大越朝向玩家前方
        }));
        return faces.get(0);
    }

    /** 方块可替换（Rise: isAir || canBeReplaced） */
    public static boolean isReplaceable(BlockPos pos) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /** 方块是实心支撑（Rise: 非空气 + 非流体 + 有碰撞） */
    public static boolean isSolid(BlockPos pos) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        return !state.getCollisionShape(mc.level, pos).isEmpty();
    }

    /** 交互方块黑名单（Rise: chest/anvil/crafting 等不可放置于其上） */
    private static boolean isBlacklisted(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        for (BlockState b : BLACKLIST) {
            if (b.getBlock() == state.getBlock()) return true;
        }
        return false;
    }
}
