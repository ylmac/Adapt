package com.ylmac.adapt.features.module.impl.player;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.event.impl.SendPositionEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import com.ylmac.adapt.features.module.util.EnumFacingOffset;
import com.ylmac.adapt.features.module.util.PlayerUtil;
import com.ylmac.adapt.features.module.util.RayCastUtil;
import com.ylmac.adapt.features.module.util.RotationUtil;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

/**
 * Scaffold 自动搭路 —— 对齐 Rise 6.9.5 Scaffold 主循环（Phase 2：Normal 模式正确搭路）。
 * <p>
 * Rise 主循环（PreUpdateEvent）：
 * 1. placeOffset 重置 + safeWalk 边缘判定 + 恢复方向键
 * 2. expand → blockAheadOfPlayer 前移 placeOffset
 * 3. ticksOnAir 累计 + readyToPlace = !BadPackets && ticksOnAir > placeDelay 随机值
 * 4. targetBlock = PlayerUtil.a(placeOffset, sameY) —— 3D 搜索最近可放点
 * 5. aib = PlayerUtil.a(targetBlock, y<0) —— 支撑面排序 → enumFacing
 * 6. blockFace = targetBlock + aib.offset
 * 7. calculateRotations + RotationComponent.setRotations（静默发包旋转）
 * 8. 手持方块判断 + raycast 校验（RayCastUtil.overBlock）→ readyToPlace → placeBlock()
 * <p>
 * 26.2 对应（javap 确证）：
 * - 放置：mc.gameMode.useItemOn(player, MAIN_HAND, BlockHitResult)（内部 startPrediction+发包）
 * - 旋转：SendPositionEvent（LocalPlayerSendPositionMixin 在 sendPosition 发布）改 yaw/pitch
 * - 搜索：PlayerUtil.findNearestPlaceable / findSupportFace（本项目实现，见 module/util/）
 * - raycast：RayCastUtil.rayCast / overBlock
 */
public class Scaffold extends Module {

    private static final Random RANDOM = new Random();

    // ---------- 设置（Rise 对齐：核心项） ----------
    private final ChoiceSetting mode;            // Normal / GodBridge（Rise: +Breesily/Snap/Telly/Eagle）
    private final ChoiceSetting rotationMode;    // Normal / Grim（Rise）
    private final ChoiceSetting rayCast;         // Off / Normal / Strict（Rise 默认 Strict）
    private final ChoiceSetting yawOffset;       // 0 / 45 / -45
    private final DoubleSetting rotationSpeed;   // 旋转速度（度/tick）
    private final DoubleSetting placeDelay;      // 放置延迟（tick，Rise Bounds 0-5）
    private final DoubleSetting expand;          // 向前扩展（Rise Number 0-4）
    private final BoolSetting safeWalk;          // 安全行走（Rise 默认 true）
    private final BoolSetting movementCorrection;// 移动修正（Rise）
    private final BoolSetting sprint;            // 疾跑（Rise sprint mode 简化）
    private final BoolSetting tower;             // 搭柱（Rise tower mode 简化）
    private final BoolSetting sneak;             // 潜行节奏（Rise sneak）

    // ---------- 内部状态（Rise 对应） ----------
    private BlockPos targetBlock;        // Rise: targetBlock
    private BlockPos blockFace;          // Rise: blockFace（支撑面方块）
    private EnumFacingOffset enumFacing; // Rise: enumFacing（支撑面方向+偏移）
    private BlockPos placeOffset;        // Rise: placeOffset
    private int startY;                  // Rise: startY（当前搭路层 Y，ticksOnAir 判定基准）
    private int ticksOnAir;              // Rise: ticksOnAir
    private boolean readyToPlace;        // Rise: readyToPlace
    private int placementIndex;          // Rise: placementIndex
    private int extraPlacements;         // Rise: extraPlacements（Watchdog Jump 用）
    private float targetYaw;
    private float targetPitch;
    private float visualYaw;
    private float visualPitch;
    private int targetSlot = -1;
    private int godBridgeSneakTicks;
    private int blocksPlaced;
    private boolean waitingForSneak;
    private boolean wasSneaking;
    private int badPacketsTicks;         // 本地 BadPackets 等价（最近发包延迟）

    public Scaffold() {
        super("scaffold", "Scaffold", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        mode = choiceSetting("模式", "Normal", List.of("Normal", "GodBridge"));
        rotationMode = choiceSetting("旋转模式", "Normal", List.of("Normal", "Grim"));
        rayCast = choiceSetting("射线检测", "Strict", List.of("Off", "Normal", "Strict"));
        yawOffset = choiceSetting("偏航偏移", "0", List.of("0", "45", "-45"));
        rotationSpeed = doubleSetting("旋转速度", 6.0, 1.0, 30.0, 0.5);
        placeDelay = doubleSetting("放置延迟", 0.0, 0.0, 5.0, 1.0);
        expand = doubleSetting("向前扩展", 0.0, 0.0, 4.0, 1.0);
        safeWalk = boolSetting("安全行走", true);
        // 默认开：不开时 Scaffold 改了服务器看到的偏航却不修正输入，玩家按
        // W/S 会朝方块方向走而不是自己看的方向（Rise 默认关，但其 RotationComponent
        // 在 Scaffold 的多数分支里仍主动传 MovementFix.NORMAL）。
        movementCorrection = boolSetting("移动修正", true);
        sprint = boolSetting("疾跑", true);
        tower = boolSetting("搭柱", true);
        sneak = boolSetting("潜行节奏", false);
    }

    @Override
    protected void onEnable() {
        if (mc.player != null) {
            this.wasSneaking = mc.player.isShiftKeyDown();
            this.visualYaw = mc.player.getYRot();
            this.visualPitch = mc.player.getXRot();
            this.targetYaw = mc.player.getYRot();
            this.targetPitch = mc.player.getXRot();
        }
        this.ticksOnAir = 0;
        this.placementIndex = 0;
        this.extraPlacements = 0;
        this.targetSlot = -1;
        this.startY = mc.player != null ? Mth.floor(mc.player.getY()) : 0;
        this.placeOffset = BlockPos.ZERO;
        this.godBridgeSneakTicks = 0;
        this.blocksPlaced = 0;
        this.waitingForSneak = false;
        this.badPacketsTicks = 0;
    }

    @Override
    protected void onDisable() {
        if (mc.player != null && !this.wasSneaking) {
            mc.options.keyShift.setDown(false);
        }
        this.targetSlot = -1;
    }

    // ================= 主循环（Rise PreUpdateEvent） =================
    // 注意挂在 PlayerTickEvent.Pre（LocalPlayer.tick HEAD）而不是 ClientTickEvent.Pre
    // （Minecraft.tick HEAD）：后者早于 LocalPlayer.tick，player.input.keyPresses
    // 还是**上一 tick** 的值，移动方向判断会整体错一拍。

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;
        LocalPlayer player = mc.player;
        // 换槽确认 + BadPackets 递减（原来单独挂在第二个 Pre 处理器上，合并到这里，
        // 避免同优先级下两方法的执行顺序由 JVM 方法返回顺序决定、不可控）
        if (this.targetSlot >= 0 && player.getInventory().getSelectedSlot() == this.targetSlot) {
            this.targetSlot = -1;
        }
        if (this.badPacketsTicks > 0) this.badPacketsTicks--;

        if (mc.gui.screen() != null) return;

        // Rise: placementIndex 循环（extraPlacements 时同 tick 多次放置）。
        // ⚠ Rise 是 `while (placementIndex <= extraPlacements)`：extraPlacements=0 时
        //   只跑 1 遍。之前写成 `1 + extraPlacements` → 每 tick 跑 2 遍 11³ 扫描 + 2 次放置。
        updateStartY(player, isMoving());
        this.placementIndex = 0;
        while (this.placementIndex <= this.extraPlacements) {
            runPlacementPass(player);
            this.placementIndex++;
            if (this.targetBlock == null) break;
        }
    }

    /**
     * Rise 的 startY 维护（搭路层的 Y）：搭桥时"脚下这层"的判定基准。
     * Rise Scaffold L1248-1258：跳跃上升/落地未移动/cqL>2 且脚下非空气 → 更新。
     */
    private void updateStartY(LocalPlayer player, boolean moving) {
        if (player.getY() % 1.0 > 0.5 && mc.options.keyJump.isDown()) {
            this.startY = Mth.floor(player.getY());
            return;
        }
        if ((player.getY() < this.startY || player.onGround()) && !moving) {
            this.startY = Mth.floor(player.getY());
            return;
        }
        BlockPos below = player.blockPosition().below();
        if (Mth.floor(player.getY()) != this.startY && !PlayerUtil.isReplaceable(below)) {
            this.startY = Mth.floor(player.getY());
        }
    }

    /** 单次放置流程（Rise PreUpdate 主循环体） */
    private void runPlacementPass(LocalPlayer player) {
        // Rise: placeOffset 重置（默认脚下）
        this.placeOffset = new BlockPos(0, 0, 0);

        // Rise: expand → blockAheadOfPlayer 前移 placeOffset
        int expandAmount = (int) Math.round(this.expand.getValue());
        if (expandAmount > 0 && isMoving()) {
            BlockPos ahead = findAheadOffset(player, expandAmount);
            if (ahead != null) this.placeOffset = ahead;
        }

        // Rise ticksOnAir（L1100-1119 逐条）：
        //   移动中 → 基准是"startY-1 层（搭路层下方）是否可替换"；
        //   静止中 → 基准是"脚下（playerY-1）是否可替换"。
        //   = "我已经悬空了几 tick"，readyToPlace 只有 > placeDelay 才为真。
        // ⚠ 之前实现成"targetBlock 可替换就 ++"，而 targetBlock 按定义必然可替换
        //   → 计数单调上涨 → 站在平地上也会一直放方块，语义完全丢失。
        boolean belowReplaceable = PlayerUtil.isReplaceable(player.blockPosition().below());
        boolean atStartYReplaceable = PlayerUtil.isReplaceable(
                new BlockPos(Mth.floor(player.getX()), this.startY - 1, Mth.floor(player.getZ())));
        boolean airFlag = isMoving() ? atStartYReplaceable : belowReplaceable;
        if (airFlag) {
            this.ticksOnAir++;
        } else {
            this.ticksOnAir = 0;
        }

        // Rise: targetBlock = PlayerUtil.a(placeOffset, sameY null)
        this.targetBlock = PlayerUtil.findNearestPlaceable(this.placeOffset, null);
        if (this.targetBlock == null) {
            return;
        }

        // Rise: readyToPlace = !BadPackets && ticksOnAir > placeDelay 随机值
        boolean badPackets = this.badPacketsTicks > 0;
        long delay = Math.round(this.placeDelay.getValue());
        this.readyToPlace = !badPackets && this.ticksOnAir > delay;

        // Rise: aib = PlayerUtil.a(targetBlock, y<0) → enumFacing（支撑面排序）
        this.enumFacing = PlayerUtil.findSupportFace(this.targetBlock, false);
        if (this.enumFacing != null) {
            this.blockFace = this.targetBlock.offset(this.enumFacing.getOffset());
        } else {
            this.blockFace = null;
            return;
        }

        // GodBridge 潜行节奏
        if (mode.getValue().equals("GodBridge")) {
            updateGodBridgeSneak();
        }

        // Rise: calculateRotations + 应用旋转（SendPositionEvent 发包）
        calculateRotations(this.targetBlock, this.enumFacing.facing);
        this.visualYaw = approach(this.visualYaw, this.targetYaw, this.rotationSpeed.getValue().floatValue());
        this.visualPitch = approach(this.visualPitch, this.targetPitch, this.rotationSpeed.getValue().floatValue());

        // Rise: 手持方块判断
        int slot = findBlockSlot();
        if (slot < 0) {
            this.ticksOnAir = 0;
            return;
        }
        if (slot != player.getInventory().getSelectedSlot() && this.targetSlot != slot) {
            switchToSlot(slot);
            return;  // Rise: 换槽后本 tick 不放置
        }
        ItemStack held = player.getInventory().getItem(player.getInventory().getSelectedSlot());
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem)) {
            return;
        }

        // Rise: raycast 校验（Off 时跳过，Normal/Strict 时需命中放置面）
        if (!"Off".equals(rayCast.getValue())) {
            boolean strict = "Strict".equals(rayCast.getValue());
            // 用目标旋转（targetYaw/targetPitch）射线检测 —— 目标旋转精确指向
            // blockFace（calculateRotations 算的），且服务器本 tick 的 sendPosition
            // 会看到它（SendPositionEvent 写 visualYaw，visualYaw 逼近 targetYaw）。
            // 用 visualYaw（平滑逼近中）会导致旋转未到位时射线 miss → 永不放置。
            BlockHitResult hit = RayCastUtil.rayCast(this.targetYaw, this.targetPitch,
                    player.blockInteractionRange());
            if (hit == null || !this.blockFace.equals(hit.getBlockPos())
                    || (strict && hit.getDirection() != this.enumFacing.facing)) {
                return;  // 射线未命中 → 不放置（Rise: 服务器拒绝）
            }
        }

        // Rise: readyToPlace → placeBlock
        if (this.readyToPlace) {
            placeBlock();
            this.blocksPlaced++;
        }
    }

    // ================= 放置（Rise placeBlock / useItemOn / getHitVec） =================

    private void placeBlock() {
        LocalPlayer player = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (player == null || gm == null || this.blockFace == null || this.enumFacing == null) return;

        // Grim 旋转模式：发 Rot 包 → 放置 → 回真实旋转（Rise C06 语义）
        if ("Grim".equals(rotationMode.getValue())) {
            sendRotationPacket(this.targetYaw, this.targetPitch);
            doUseItemOn(player, gm);
            sendRotationPacket(player.getYRot(), player.getXRot());
        } else {
            doUseItemOn(player, gm);
        }
    }

    /** 执行 useItemOn（26.2: 内部 startPrediction + 自动发包 ServerboundUseItemOnPacket） */
    private void doUseItemOn(LocalPlayer player, MultiPlayerGameMode gm) {
        Vec3 hitVec = getHitVec();
        if (hitVec == null) return;
        BlockHitResult hit = new BlockHitResult(hitVec, this.enumFacing.facing, this.blockFace, false);
        InteractionResult result = gm.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            // 本地 BadPackets 等价：放置后延迟一 tick（Rise BadPacketsComponent）
            this.badPacketsTicks = 1;
        }
    }

    /**
     * 命中点（Rise getHitVec 逐条）：
     * 1. 先取 blockFace 内随机点、按 enumFacing 把对应轴钉到该面上；
     * 2. 若"目标旋转的射线"恰好命中同一 blockFace 且面一致 → 直接用真实 hitVec
     *    （服务端必须能验证这个点，否则放置被拒）。
     */
    private Vec3 getHitVec() {
        BlockPos face = this.blockFace;
        if (face == null) return null;
        Direction facing = this.enumFacing.facing;
        double x = face.getX() + RANDOM.nextDouble();
        double y = face.getY() + RANDOM.nextDouble();
        double z = face.getZ() + RANDOM.nextDouble();
        switch (facing) {
            case DOWN -> y = face.getY();
            case UP -> y = face.getY() + 1.0;
            case NORTH -> z = face.getZ();
            case SOUTH -> z = face.getZ() + 1.0;
            case WEST -> x = face.getX();
            case EAST -> x = face.getX() + 1.0;
        }
        BlockHitResult hit = RayCastUtil.rayCast(this.targetYaw, this.targetPitch,
                mc.player.blockInteractionRange());
        if (hit != null && face.equals(hit.getBlockPos()) && hit.getDirection() == facing) {
            return hit.getLocation();
        }
        return new Vec3(x, y, z);
    }

    /** 发旋转包（Rise C06 / Grim 分支） */
    private void sendRotationPacket(float yaw, float pitch) {
        if (mc.player == null || mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
    }

    /** 换到指定快捷栏槽位（26.2: setSelectedSlot + SetCarriedItem 包） */
    private void switchToSlot(int slot) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        this.targetSlot = slot;
        player.getInventory().setSelectedSlot(slot);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    // ================= 目标/支撑面（Rise PlayerUtil 调用） =================

    private boolean isMoving() {
        LocalPlayer p = mc.player;
        if (p == null) return false;
        return p.input.keyPresses.forward() || p.input.keyPresses.backward()
                || p.input.keyPresses.left() || p.input.keyPresses.right();
    }

    /** 按移动方向返回 placeOffset（Rise blockAheadOfPlayer 等价） */
    private BlockPos findAheadOffset(LocalPlayer player, int amount) {
        double yaw = Math.toRadians(player.getYRot());
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        BlockPos feet = player.blockPosition();
        // 找前方 air 且下方有支撑的落点（最多 amount 格）
        for (int i = 1; i <= amount; i++) {
            BlockPos cand = feet.offset((int) Math.round(dx * i), 0, (int) Math.round(dz * i));
            if (cand.equals(feet)) continue;
            if (PlayerUtil.isReplaceable(cand) && PlayerUtil.isSolid(cand.below())) {
                return new BlockPos((int) Math.round(dx * i), 0, (int) Math.round(dz * i));
            }
        }
        return null;
    }

    // ================= 方块选择（Rise SlotUtil.vx 移植） =================

    /** 从快捷栏找可放置方块；返回槽位或 -1 */
    private int findBlockSlot() {
        LocalPlayer player = mc.player;
        if (player == null) return -1;
        int bestSlot = -1;
        float bestQuality = -1;
        for (int pass = 0; pass < 3 && bestSlot < 0; pass++) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
                Block block = blockItem.getBlock();
                if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) continue;
                if (block instanceof FallingBlock) continue;
                if (block.defaultBlockState().is(BlockTags.FENCES) || block.defaultBlockState().is(BlockTags.FENCE_GATES)) continue;
                BlockBehaviour.BlockStateBase state = block.defaultBlockState();
                if (state.is(BlockTags.LEAVES) || block == Blocks.COBWEB || block == Blocks.TORCH
                        || block == Blocks.LILY_PAD) continue;

                float quality = blockQuality(block);
                boolean acceptable = pass == 0 ? quality >= 0.9F
                        : pass == 1 ? quality >= 0.5F
                        : true;
                if (acceptable && quality > bestQuality) {
                    bestQuality = quality;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private float blockQuality(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)) return 1.0F;
        if (block == Blocks.GLASS || block == Blocks.TNT || block == Blocks.SEA_LANTERN) return 0.9F;
        if (state.canBeReplaced()) return 0.2F;
        return 0.5F;
    }

    // ================= 旋转 =================

    private void calculateRotations(BlockPos target, Direction facing) {
        Vec3 eye = mc.player.getEyePosition();
        double tx = target.getX() + 0.5;
        double ty;
        double tz = target.getZ() + 0.5;
        if (mode.getValue().equals("GodBridge")) {
            ty = target.getY() + 0.1;
        } else {
            ty = target.getY() + 0.9;
        }
        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        this.targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        this.targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        this.targetPitch = Mth.clamp(this.targetPitch, -89.5F, 89.5F);
    }

    private float approach(float current, float target, float maxStep) {
        float diff = Mth.wrapDegrees(target - current);
        if (diff > maxStep) diff = maxStep;
        if (diff < -maxStep) diff = -maxStep;
        return current + diff;
    }

    // ================= GodBridge 潜行（Rise: godBridgeSneakTicks 节奏） =================

    private void updateGodBridgeSneak() {
        this.godBridgeSneakTicks++;
        boolean keyDown = mc.options.keyShift.isDown();
        if (this.blocksPlaced >= 3 + RANDOM.nextInt(3) && !keyDown) {
            mc.options.keyShift.setDown(true);
            this.waitingForSneak = true;
            this.blocksPlaced = 0;
        } else if (this.waitingForSneak && this.godBridgeSneakTicks % 4 == 0) {
            mc.options.keyShift.setDown(false);
            this.waitingForSneak = false;
        }
    }

    // ================= 移动修正（Rise: RotationComponent.onMove → MoveUtil.fixMovement） =================

    /**
     * 为什么需要它：Scaffold 通过 SendPositionEvent 把服务器看到的偏航改成了
     * 对准方块的 targetYaw，但玩家客户端的 getYRot() 仍是他自己控制的角度。
     * 服务器按 targetYaw 计算移动时，玩家按 W 就会朝方块方向走、而不是自己
     * 看的方向 —— 表现就是"往前走/往后走不对"。
     * <p>
     * 修正方式（Rise 原版算法）：在 8 个输入组合里挑一个，使得**用 targetYaw
     * 表达**时的世界方向，最接近玩家原本想走的方向。
     */
    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (!this.isEnabled() || !movementCorrection.getValue()) return;
        if (nullCheck() || this.targetBlock == null) return;
        RotationUtil.fixMovement(event, mc.player, this.targetYaw);
    }

    // ================= 发包旋转（SendPositionEvent 静默） =================

    @EventHandler
    private void onSendPosition(SendPositionEvent event) {
        if (!this.isEnabled()) return;
        if (this.targetBlock == null) {
            // Rise: RotationComponent 未激活时回真实旋转（防止服务器看到残留旋转）
            return;
        }
        // 写目标旋转（不是平滑中的 visualYaw）：服务器本 tick 看到的就是
        // raycast 校验用的旋转 → 放置必然命中。Rise RotationComponent 同理
        // （PreMotionEvent.setYaw 用目标旋转）。
        event.setYaw(this.targetYaw);
        event.setPitch(this.targetPitch);
    }

    @Override
    public String getInfo() {
        return mode.getValue();
    }
}
