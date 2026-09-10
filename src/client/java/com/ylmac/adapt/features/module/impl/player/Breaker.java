package com.ylmac.adapt.features.module.impl.player;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.event.impl.PacketEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.RegistryListSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaker —— 自动破坏周围指定的方块（默认床）。
 * <p>
 * 移植自 Rise 6.9.5 的 Breaker 模块（核心逻辑对应，去掉渲染/防眩晕等外围）：
 * - 遍历周围 11x11x11 范围找目标方块（默认床；可自选方块列表）
 * - 找到后按 "挖掘→暂停→换目标" 循环：START_DESTROY_BLOCK → 每 tick
 *   continueDestroyBlock 维持进度 → 检测到已消失则 STOP 并切换下一个目标
 * - 挖掘期间按住玩家移动包（与 Rise "破坏时 blink 防服务端回拉" 一致），
 *   破坏结束统一补发；破坏进度按 tick 推进，不依赖攻击键
 * - 可选 Instant 模式：直接 START+STOP 秒破（对床等快速方块）
 * <p>
 * 26.2 API（javap /Users/ylmac/Documents/26.2 确证）：
 * - {@link MultiPlayerGameMode#startDestroyBlock(BlockPos, Direction)} /
 *   {@code continueDestroyBlock} / {@code stopDestroyBlock} 客户端模拟破坏
 * - {@link ServerboundPlayerActionPacket} Action.START/STOP_DESTROY_BLOCK 手动发包
 * - {@link BlockState#isAir()} / {@link BlockState#getBlock()} / {@link BedBlock} 检测
 */
public class Breaker extends Module {

    /** 挖掘模式 */
    public enum Mode {
        Normal, Instant
    }

    /** 扫描范围（Rise 为 5，即 ±5 格） */
    private static final int SCAN_RADIUS = 5;

    private final RegistryListSetting<Block> targetBlocks;
    private final DoubleSetting range;
    private final BoolSetting instant;
    private final BoolSetting onlyBed;

    /** 当前目标（null = 无） */
    private BlockPos target;

    /** 挖掘进度 tick 计数（每 tick 推进，直到方块消失） */
    private int digTicks;

    /** 破坏间隔 tick（防止连续挖太快） */
    private int cooldownTicks;

    /** 上次目标（用于停止挖掘包） */
    private BlockPos lastTarget;

    /** 挖掘期间被按住（暂存）的移动包 */
    private final java.util.Queue<net.minecraft.network.protocol.game.ServerboundMovePlayerPacket> heldMovePackets = new java.util.ArrayDeque<>();

    /** 补发移动包期间的防递归标志（sendSilently 补发也会触发 PacketEvent.Send） */
    private boolean flushing;

    private boolean wasDigging;

    public Breaker() {
        super("breaker", "Breaker", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        targetBlocks = blockListSetting("目标方块", List.of(Blocks.BED.red()));
        range = doubleSetting("范围", 4.5, 1.0, 6.0, 0.1);
        instant = boolSetting("即时模式", false);
        onlyBed = boolSetting("仅床", true);
    }

    /** 目标方块是否匹配设置（默认仅床） */
    private boolean matchesTarget(BlockState state) {
        if (state.isAir()) return false;
        Block block = state.getBlock();
        if (onlyBed.getValue()) {
            return block instanceof BedBlock;
        }
        return targetBlocks.contains(block);
    }

    /** 目标是否还在（有效且未消失） */
    private boolean isValidTarget(BlockPos pos) {
        if (pos == null || mc.level == null) return false;
        if (!mc.level.isLoaded(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        // 距离检查（以玩家脚部为基准）
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dy = pos.getY() + 0.5 - mc.player.getY();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= range.getValue() + 2.0;
    }

    /** 扫描周围方块，返回最近的目标 */
    private BlockPos findTarget() {
        if (mc.player == null || mc.level == null) return null;
        BlockPos playerPos = mc.player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    if (!mc.level.isLoaded(pos)) continue;
                    BlockState state = mc.level.getBlockState(pos);
                    if (!matchesTarget(state)) continue;
                    double dx = pos.getX() + 0.5 - mc.player.getX();
                    double dy = pos.getY() + 0.5 - mc.player.getY();
                    double dz = pos.getZ() + 0.5 - mc.player.getZ();
                    double dist = dx * dx + dy * dy + dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /** 静默发包（绕过 PacketEvent） */
    private void sendSilently(net.minecraft.network.protocol.Packet<?> packet) {
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().send(packet, null);
        }
    }

    /** 补发挖掘期间被暂存的移动包 */
    private void releaseHeldMovePackets() {
        if (flushing) return; // 防重入
        flushing = true;
        try {
            while (!heldMovePackets.isEmpty()) {
                sendSilently(heldMovePackets.poll());
            }
            wasDigging = false;
        } finally {
            flushing = false;
        }
    }

    @Override
    protected void onEnable() {
        target = null;
        lastTarget = null;
        digTicks = 0;
        cooldownTicks = 0;
        heldMovePackets.clear();
        wasDigging = false;
    }

    @Override
    protected void onDisable() {
        stopDigging();
        releaseHeldMovePackets();
    }

    /** 停止当前挖掘（发 STOP 包并补发移动包） */
    private void stopDigging() {
        if (lastTarget != null && mc.getConnection() != null) {
            sendSilently(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, lastTarget, Direction.UP));
        }
        if (wasDigging) {
            releaseHeldMovePackets();
        }
        lastTarget = null;
        digTicks = 0;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        // 补发期间放行（防递归自锁：releaseHeldMovePackets 的 sendSilently 也会触发本事件）
        if (flushing) return;
        // 挖掘期间按住移动包（防服务端回拉/位置矛盾）
        if (wasDigging && event.getPacket() instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet) {
            heldMovePackets.add(packet);
            event.cancel();
        }
    }

    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // 1. 保持当前目标或重新选择
        if (!isValidTarget(target)) {
            stopDigging();
            target = findTarget();
            if (target == null) {
                return;
            }
        }

        // 2. 开始挖掘（发送 START 包 + 客户端模拟）
        if (lastTarget == null || !lastTarget.equals(target)) {
            if (lastTarget != null) {
                // 换目标：先 STOP 上一个
                sendSilently(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, lastTarget, Direction.UP));
            }
            lastTarget = target;
            digTicks = 0;
            wasDigging = true;
            mc.gameMode.startDestroyBlock(target, Direction.UP);
            sendSilently(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target, Direction.UP));
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        // 3. 挖掘推进：每 tick 维持进度，直到方块消失
        digTicks++;

        if (instant.getValue()) {
            // 即时模式：START 后立即 STOP（对床等瞬间破坏的方块有效）
            mc.gameMode.stopDestroyBlock();
            sendSilently(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target, Direction.UP));
            mc.player.swing(InteractionHand.MAIN_HAND);
            stopDigging();
            target = null;
            cooldownTicks = 2;
            return;
        }

        // 普通模式：每 tick continueDestroyBlock 推进破坏进度
        mc.gameMode.continueDestroyBlock(target, Direction.UP);

        // 检测方块是否已被破坏（服务端确认后本地同步）
        BlockState state = mc.level.getBlockState(target);
        if (state.isAir()) {
            // 已消失：发 STOP + 收尾
            mc.gameMode.stopDestroyBlock();
            sendSilently(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target, Direction.UP));
            stopDigging();
            target = null;
            cooldownTicks = 1;
        }
    }

    /** 剩余目标数量（HUD tag） */
    private int remainingTargets() {
        if (mc.player == null || mc.level == null) return 0;
        BlockPos playerPos = mc.player.blockPosition();
        int count = 0;
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    if (mc.level.isLoaded(pos) && matchesTarget(mc.level.getBlockState(pos))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @Override
    public String getInfo() {
        return "目标: " + remainingTargets();
    }

}
