package com.ylmac.adapt.features.module.impl.ghost;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.event.impl.SendPositionEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * LegitScaffold 合法搭路（移植 Rise 6.9.5 ghost/LegitScaffold，适配 Fabric 26.2）。
 * <p>
 * 行为（与 Rise 原版对应，另补足 Rise 未实现的"真正放方块"部分）：
 * - 行走时检测脚下为空气/可替换方块且下方有支撑 → 从快捷栏选方块并自动放置
 * - 自动换槽：只发 ServerboundSetCarriedItemPacket（Rise 换槽语义，客户端同步
 *   inventory.selectedSlot），26.2 已 javap 确证
 * - 放置：MultiPlayerGameMode.useItemOn(player, MAIN_HAND, BlockHitResult)
 *   （26.2 内部走 interaction.startPrediction 预测序列，等价 vanilla 右键）
 * - 旋转：看向放置点（可选手感 Smooth / Instant / Off），默认 Smooth
 * - 延时设置：放置延迟（tick）+ 换槽延迟（ms），防超速放置（反作弊友好）
 * - 仅行走时触发（站在地面才放；Rise onGround 判断）
 * - blocksOnly：仅手持方块时才放（Rise 同名设置）
 * - backwardsOnly：仅后退时放（Rise 同名设置，搭桥常用）
 * <p>
 * 26.2 API 对照（javap 确证）：
 * - gameMode.useItemOn(LocalPlayer, InteractionHand, BlockHitResult) → InteractionResult
 * - player.inventory.setSelectedSlot(int) + mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot))
 * - BlockState.isAir() / state.getFluidState().isEmpty()（isSolid 为 BlockStateBase 方法）
 * - player.getYRot()/setYRot、getXRot()/setXRot；Mth.wrapDegrees
 */
public class LegitScaffold extends Module {

    /** 放置延迟（tick，0-10）：每放一块后冷却再放下一块 */
    private final DoubleSetting placeDelay;
    /** 换槽延迟（ms，0-500）：换槽后的冷却，避免频繁切槽 */
    private final IntSetting switchDelay;
    /** 旋转模式: Smooth / Instant / Off */
    private final com.ylmac.adapt.features.module.settings.impl.ChoiceSetting rotationMode;
    /** 旋转速度（度/tick，Smooth 用） */
    private final DoubleSetting rotationSpeed;
    /** 仅手持方块时放（Rise blocksOnly） */
    private final BoolSetting blocksOnly;
    /** 仅后退时放（Rise backwardsOnly） */
    private final BoolSetting backwardsOnly;
    /** 仅站在地面时放（Rise groundOnly，默认 true 更稳） */
    private final BoolSetting groundOnly;
    /** 悬空（脚下为空气）达到该 tick 数后才放（延迟起手，Rise overAirTicks 语义） */
    private final IntSetting airTicks;

    // ---------- 内部状态 ----------
    /** 目标放置方块坐标 */
    private BlockPos targetBlock;
    /** 支撑面方块坐标（点击的面所在方块） */
    private BlockPos blockFace;
    /** 点击的面（指向目标方块） */
    private Direction enumFacing;
    /** 目标槽位（-1 = 未设置） */
    private int targetSlot = -1;
    /** 放置冷却倒计时（tick） */
    private int placeCooldown;
    /** 换槽冷却截止时间（ms） */
    private long switchCooldownUntil;
    /** 悬空 tick 计数 */
    private int airTickCount;
    /** 旋转目标（平滑插值用） */
    private float targetYaw;
    private float targetPitch;
    private float visualYaw;
    private float visualPitch;

    public LegitScaffold() {
        super("legitscaffold", "LegitScaffold", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        placeDelay = doubleSetting("放置延迟", 1.0, 0.0, 10.0, 1.0);
        switchDelay = intSetting("换槽延迟", 80, 0, 500, 10);
        rotationMode = choiceSetting("旋转模式", "Smooth", java.util.List.of("Smooth", "Instant", "Off"));
        rotationSpeed = doubleSetting("旋转速度", 6.0, 1.0, 30.0, 0.5);
        blocksOnly = boolSetting("仅手持方块", false);
        backwardsOnly = boolSetting("仅后退", false);
        groundOnly = boolSetting("仅地面", true);
        airTicks = intSetting("悬空起手", 2, 0, 10, 1);
    }

    @Override
    protected void onEnable() {
        if (mc.player != null) {
            this.visualYaw = mc.player.getYRot();
            this.visualPitch = mc.player.getXRot();
            this.targetYaw = this.visualYaw;
            this.targetPitch = this.visualPitch;
        }
        this.targetBlock = null;
        this.blockFace = null;
        this.enumFacing = null;
        this.targetSlot = -1;
        this.placeCooldown = 0;
        this.switchCooldownUntil = 0;
        this.airTickCount = 0;
    }

    @Override
    protected void onDisable() {
        // 关闭时清掉目标，防止残留状态（不放方块也不转视角）
        this.targetBlock = null;
        this.blockFace = null;
        this.enumFacing = null;
        this.targetSlot = -1;
    }

    // ================= 主循环（每 tick） =================

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;
        LocalPlayer player = mc.player;
        if (mc.gui.screen() != null) return; // 打开界面不放

        if (this.placeCooldown > 0) this.placeCooldown--;

        // ---- 1. 是否满足触发条件（Rise: onGround + blocksOnly + backwardsOnly） ----
        if (this.groundOnly.getValue() && !player.onGround()) {
            this.airTickCount = 0;
            this.targetBlock = null;
            return;
        }
        if (this.blocksOnly.getValue() && !this.isHoldingBlock()) {
            this.airTickCount = 0;
            this.targetBlock = null;
            return;
        }
        if (this.backwardsOnly.getValue() && !this.isMovingBackwards()) {
            this.airTickCount = 0;
            this.targetBlock = null;
            return;
        }

        // ---- 2. 目标检测：脚下为空气/可替换且下方有支撑 ----
        BlockPos below = player.blockPosition().below();
        boolean belowReplaceable = this.isReplaceable(below);
        boolean belowSolid = !belowReplaceable;
        boolean hasSupport = belowSolid || this.isSolidBlock(below.below());
        if (!belowReplaceable || !hasSupport) {
            this.airTickCount = 0;
            this.targetBlock = null;
            return;
        }
        this.targetBlock = below;

        // 悬空计数（Rise overAirTicks）：连续悬空达到设定值才放
        this.airTickCount++;
        if (this.airTickCount <= this.airTicks.getValue()) {
            return;
        }

        // ---- 3. 选方块 + 换槽 ----
        int slot = this.findBlockSlot();
        if (slot < 0) {
            // 快捷栏没有方块（Rise SlotComponent.getItemStack 为空）
            return;
        }
        if (slot != player.getInventory().getSelectedSlot() && this.targetSlot != slot) {
            this.switchToSlot(slot);
        }
        ItemStack held = player.getInventory().getItem(player.getInventory().getSelectedSlot());
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem)) {
            // 换槽延迟未到或主手还没有方块
            return;
        }

        // ---- 4. 计算支撑面 ----
        BlockPos support = this.findSupport(this.targetBlock);
        if (support == null) {
            return; // 无可点支撑面：放弃
        }
        this.blockFace = support;
        this.enumFacing = this.faceFrom(support, this.targetBlock);

        // ---- 5. 旋转（可选手感） ----
        this.calculateRotations(this.targetBlock);
        if (this.rotationMode.getValue().equals("Smooth")) {
            this.visualYaw = Mth.approachDegrees(this.visualYaw, this.targetYaw, this.rotationSpeed.getValue().floatValue());
            this.visualPitch = Mth.approach(this.visualPitch, this.targetPitch, this.rotationSpeed.getValue().floatValue());
        } else if (this.rotationMode.getValue().equals("Instant")) {
            this.visualYaw = this.targetYaw;
            this.visualPitch = this.targetPitch;
        }
        // 渲染朝向直接跟随（发包旋转在 SendPositionEvent 里）
        player.setYRot(this.visualYaw);
        player.setXRot(this.visualPitch);

        // ---- 6. 执行放置 ----
        if (this.placeCooldown <= 0) {
            this.placeBlock();
            this.placeCooldown = (int) Math.round(this.placeDelay.getValue());
            this.switchCooldownUntil = 0; // 放置后允许重新选槽
        }
    }

    // ================= 放置 =================

    /** 执行一次右键放置（26.2: MultiPlayerGameMode.useItemOn，含 startPrediction 预测序列） */
    private void placeBlock() {
        LocalPlayer player = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (player == null || gm == null || this.blockFace == null || this.enumFacing == null) return;

        // 命中点以支撑面（blockFace）为准（Rise/vanilla 语义：点击的是支撑面方块，
        // 服务器按 hit location + direction 判定放置位置）。
        // 支撑面 = 被点击的方块；enumFacing = 支撑面 → 目标方块的方向（也是点击的面）。
        // 命中点取支撑面朝向目标那一面的中心点。
        BlockPos face = this.blockFace;
        Direction clickedSide = this.enumFacing;
        Vec3 location = new Vec3(
                face.getX() + 0.5 + clickedSide.getStepX() * 0.5,
                face.getY() + 0.5 + clickedSide.getStepY() * 0.5,
                face.getZ() + 0.5 + clickedSide.getStepZ() * 0.5);

        // 服务器要求 hit location 在玩家视线 reach 范围内（BlockPlaceContext 校验）；
        // 命中点向眼睛方向收缩 0.1 保证在支撑面外侧（vanilla 同款：面中心偏外 0.1）
        Vec3 eye = player.getEyePosition();
        double dx = location.x - eye.x;
        double dy = location.y - eye.y;
        double dz = location.z - eye.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= 0.001) return;
        Vec3 hitLocation = location.subtract(dx / dist * 0.1, dy / dist * 0.1, dz / dist * 0.1);

        // direction = 点击的面（enumFacing 本体，不是 opposite）：
        // BlockItem.useOn → BlockPlaceContext.getClickedPos() = blockPos.relative(direction)，
        // 服务器按该方向把方块放到支撑面的相邻格（= 目标方块位置）。
        BlockHitResult hit = new BlockHitResult(hitLocation, clickedSide, face, false);
        InteractionResult result = gm.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** 换到指定快捷栏槽位（26.2: setSelectedSlot + SetCarriedItem 包） */
    private void switchToSlot(int slot) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        long now = System.currentTimeMillis();
        if (now < this.switchCooldownUntil) return;
        this.switchCooldownUntil = now + this.switchDelay.getValue();
        this.targetSlot = slot;
        player.getInventory().setSelectedSlot(slot);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    // ================= 目标/支撑面检测 =================

    /** 是否手持主手/副手方块（Rise SlotComponent.getItemStack + ItemBlock） */
    private boolean isHoldingBlock() {
        ItemStack main = mc.player.getMainHandItem();
        ItemStack off = mc.player.getOffhandItem();
        return main.getItem() instanceof BlockItem || off.getItem() instanceof BlockItem;
    }

    /** 是否正在后退（S 键；Rise keyBindForward 反向） */
    private boolean isMovingBackwards() {
        return mc.options.keyDown.isDown();
    }

    /** 查找目标方块的可点击支撑面（优先正上方，其次水平，最后下方） */
    private BlockPos findSupport(BlockPos target) {
        BlockPos up = target.above();
        if (this.isSolidBlock(up)) return up;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = target.relative(dir);
            if (this.isSolidBlock(side)) return side;
        }
        BlockPos down = target.below();
        if (this.isSolidBlock(down)) return down;
        return null;
    }

    /** 支撑面 → 目标方块的方向（决定点击的面） */
    private Direction faceFrom(BlockPos support, BlockPos target) {
        int dx = target.getX() - support.getX();
        int dy = target.getY() - support.getY();
        int dz = target.getZ() - support.getZ();
        if (dx != 0) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (dy != 0) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (dz != 0) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        return Direction.UP;
    }

    /** 是否可替换（空气/可替换方块） */
    private boolean isReplaceable(BlockPos pos) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /** 是否实心方块（流体不算支撑） */
    private boolean isSolidBlock(BlockPos pos) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        return state.isSolid();
    }

    // ================= 方块选择 =================

    /** 从快捷栏找可放置方块（Rise SlotUtil 语义：非空气、非危险方块）；返回槽位或 -1 */
    private int findBlockSlot() {
        LocalPlayer player = mc.player;
        if (player == null) return -1;
        int bestSlot = -1;
        float bestQuality = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
            Block block = blockItem.getBlock();
            if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) continue;
            BlockState state = block.defaultBlockState();
            // 危险/不可搭方块排除（Rise blacklist 语义）
            if (state.is(net.minecraft.tags.BlockTags.FENCES) || state.is(net.minecraft.tags.BlockTags.FENCE_GATES)) continue;
            if (state.is(net.minecraft.tags.BlockTags.LEAVES) || block == Blocks.COBWEB
                    || block == Blocks.TORCH || block == Blocks.LILY_PAD) continue;

            float quality = this.blockQuality(block);
            if (quality > bestQuality) {
                bestQuality = quality;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /** 方块质量评分：完整方块 > 玻璃等 > 半砖等 */
    private float blockQuality(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)) return 1.0F;
        if (block == Blocks.GLASS || block == Blocks.TNT || block == Blocks.SEA_LANTERN) return 0.9F;
        if (state.canBeReplaced()) return 0.2F;
        return 0.5F;
    }

    // ================= 旋转 =================

    /** 计算看向目标方块中心（略偏上）的旋转 */
    private void calculateRotations(BlockPos target) {
        Vec3 eye = mc.player.getEyePosition();
        double tx = target.getX() + 0.5;
        double ty = target.getY() + 0.9;
        double tz = target.getZ() + 0.5;
        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        this.targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        this.targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        this.targetPitch = Mth.clamp(this.targetPitch, -89.5F, 89.5F);
    }

    // ================= 发包旋转（静默旋转） =================

    /**
     * 发包时应用目标旋转（静默：不直接影响玩家渲染，服务器看到的是我们的旋转）。
     * 26.2 的 LocalPlayer.sendPosition 由 SendPositionEvent 拦截（mixin 已接入），
     * 这里在事件里改写 yaw/pitch —— 等价 Rise RotationComponent 静默旋转。
     */
    @EventHandler
    private void onSendPosition(SendPositionEvent event) {
        if (!this.isEnabled() || this.rotationMode.getValue().equals("Off")) return;
        if (this.targetBlock == null) return;
        event.setYaw(this.visualYaw);
        event.setPitch(this.visualPitch);
    }

    @Override
    public String getInfo() {
        return this.rotationMode.getValue();
    }
}
