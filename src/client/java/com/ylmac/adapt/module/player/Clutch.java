package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Clutch — 坠落自救：快落地时在脚下放方块垫住。
 * <p>
 * - 检测：垂直速度 > 15 格/tick（约 19 格高度自由落体）且按住潜行键才触发
 * - 放方块：锁定脚下可替换位置 → 切到背包里第一个方块 → 每 tick 用
 * {@code MultiPlayerGameMode.useItemOn} 客户端模拟右键 + 发送 {@link ServerboundUseItemOnPacket}
 * - 防重放：右键放方块期间把玩家移动包在 {link PacketEvent.Send} 中按住，
 * 位置超前而拒绝放置）
 * - 放方块方向取"从下往上看"（面朝脚下可落点所在面），命中位置取目标方块中心
 * <p>
 * 26.2 API（javap /Users/ylmac/Documents/26.2 确证）：
 * - {@code MultiPlayerGameMode.useItemOn(LocalPlayer, InteractionHand, BlockHitResult)}
 * 返回 InteractionResult，成功（CONSUME/SUCCESS）后手动补发 ServerboundUseItemOnPacket
 * - {@link BlockPos#below} / {@link BlockState#canBeReplaced} / {@link BlockItem#getBlock}
 * - 静默发包：{@code Connection.send(packet, null)}
 */
public class Clutch extends Module {

 /** 触发坠落速度阈值 */
    private static final double FALL_SPEED_THRESHOLD = 15.0;

    /** 自动放置的起始方块数量 */
    private static final int PLACE_AMOUNT = 5;

    /** 放置前等待的 tick（让潜行触发检测稳定） */
    private static final int ACTIVE_TICKS = 4;

    /** 上次是否在放方块（用于放完补发移动包） */
    private boolean wasPlacing;

    /** 放方块期间被按住（暂存）的移动包，放完统一补发 */
    private final Queue<ServerboundMovePlayerPacket> heldMovePackets = new ArrayDeque<>();

    /** 补发移动包期间的防递归标志（sendSilently 补发也会触发 PacketEvent.Send） */
    private boolean flushing;

    private int placingTicks;

    /** 当前正在放置的方块坐标 */
    private BlockPos placePos;

    /** 放置方向 */
    private Direction placeDir;

    private int placedCount;

    public Clutch() {
        super("clutch", "Clutch", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        // 最多连续放置的方块数（放多了会把自己埋进去）
        intSetting("最大放置数", PLACE_AMOUNT, 1, 16, 1);
    }

    private int maxPlaceCount() {
        return ((com.ylmac.adapt.module.settings.impl.IntSetting) settings.get(0)).getValue();
    }

    /** 静默发包（绕过 PacketEvent，避免被本模块自己拦截） */
    private void sendSilently(net.minecraft.network.protocol.Packet<?> packet) {
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().send(packet, null);
        }
    }

    /** 找背包里第一个可放置方块（方块物品），选中它 */
    private boolean selectBlockSlot() {
        if (mc.player == null) return false;
        var inventory = mc.player.getInventory();
        int selected = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                selected = i;
                break;
            }
        }
        if (selected == -1) return false;
        if (inventory.getSelectedSlot() != selected) {
            inventory.setSelectedSlot(selected);
            // 通知服务端换槽
            sendSilently(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(selected));
        }
        return true;
    }

    /** 当前手持是否为方块 */
    private boolean isHoldingBlock() {
        if (mc.player == null) return false;
        ItemStack stack = mc.player.getMainHandItem();
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    @Override
    protected void onEnable() {
        placingTicks = 0;
        placedCount = 0;
        placePos = null;
        placeDir = null;
        heldMovePackets.clear();
        wasPlacing = false;
    }

    @Override
    protected void onDisable() {
        releaseHeldMovePackets();
    }

    /** 放完/取消时补发被按住的所有移动包 */
    private void releaseHeldMovePackets() {
        if (heldMovePackets.isEmpty()) return;
        if (flushing) return; // 防重入
        flushing = true;
        try {
            while (!heldMovePackets.isEmpty()) {
                sendSilently(heldMovePackets.poll());
            }
            wasPlacing = false;
        } finally {
            flushing = false;
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        // 补发期间放行（防递归自锁）
        if (flushing) return;
        // 放方块期间按住移动包，防止服务端把玩家位置与放置动作分离导致放置失败
        if (wasPlacing && event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            heldMovePackets.add(packet);
            event.cancel();
        }
    }

    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        // 上升/飞行时不处理。
        // 不是下落速度。26.2 自由落体终端速度只有 ≈3.9 格/tick，用速度判定永远
        // 到不了 15 → 模块完全不触发（用户实测"报废"）。
        boolean falling = mc.player.fallDistance > FALL_SPEED_THRESHOLD
                && mc.player.getDeltaMovement().y < 0;
        boolean sneaking = mc.options.keyShift.isDown();

        if (!falling || !sneaking || mc.player.isInLiquid()) {
            // 条件不满足：若正在放方块则收尾（补发包）
            if (wasPlacing) {
                releaseHeldMovePackets();
            }
            placingTicks = 0;
            placedCount = 0;
            placePos = null;
            placeDir = null;
            return;
        }

        if (placingTicks++ < ACTIVE_TICKS) {
            return; // 等待几 tick 让条件稳定
        }

        // 检查脚下方块（替换目标 = 玩家脚下）
        BlockPos below = mc.player.blockPosition().below();
        BlockState state = mc.level.getBlockState(below);
        if (!state.canBeReplaced()) {
            // 脚下已是实体方块：自救完成
            if (wasPlacing) releaseHeldMovePackets();
            placePos = null;
            placedCount = 0;
            return;
        }

        // 超过最大放置数则停手
        if (placedCount >= maxPlaceCount()) {
            if (wasPlacing) releaseHeldMovePackets();
            placingTicks = 0;
            placedCount = 0;
            return;
        }

        // 找方块并选中
        if (!isHoldingBlock() && !selectBlockSlot()) {
            if (wasPlacing) releaseHeldMovePackets();
            return;
        }

        // 确定放置目标：脚下方块下方的支持面（从下往上放置）
        BlockPos support = below.below();
        BlockState supportState = mc.level.getBlockState(support);
        if (supportState.canBeReplaced()) {
            // 脚下正下方没有可贴的面 → 尝试找玩家周围最近的可贴面
            BlockPos found = findNearestSupport(below);
            if (found == null) {
                if (wasPlacing) releaseHeldMovePackets();
                return;
            }
            support = found;
            placeDir = Direction.UP;
        } else {
            placeDir = Direction.UP;
        }
        placePos = support.above();

        // 计算命中位置：贴面方块中心
        Vec3 hitLoc = new Vec3(
                placePos.getX() + 0.5,
                placePos.getY(),
                placePos.getZ() + 0.5
        );
        BlockHitResult hit = new BlockHitResult(hitLoc, placeDir, support, false);

        // 客户端模拟放置（含动画/音效/方块放置预测）
        wasPlacing = true;
        var result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            // 补发真正的放置包（绕过 PacketEvent，防止被自己拦截）
            sendSilently(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
            placedCount++;
        }
    }

    /** 找周围（3x3x3）最近的可贴实心面 */
    private BlockPos findNearestSupport(BlockPos center) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    if (!state.canBeReplaced() && state.isSolid()) {
                        double dist = pos.distToCenterSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }

}
