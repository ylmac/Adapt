package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * <p>
 * - 检测坠落（fallDistance 达阈值）且（可选）脚下是虚空（向下连续 N 格无方块）
 * PostStrafe 清零 motion）+ 锁输入（取消键盘移动/跳/潜行）
 * - 可选珍珠自救：背包里找末影珍珠 → 计算到最近安全落点的抛射角 → 切槽并右键投出
 * - 投出珍珠后复位（防虚空死亡，做"最接近"的防坠落）
 * <p>
 * 26.2 API 说明：
 * - 冻结 = player.setDeltaMovement(0,0,0) + 取消 ServerboundMovePlayerPacket（阻止服务端位置同步）
 * - 锁输入 = KeyboardInputEvent（由 KeyboardInputMixin 从 KeyboardInput.tick 发布，本模块消费）
 * - 丢珍珠 = gameMode.useItem（右键），旋转 = player.setYRot/setXRot
 * - 背包移动珍珠 = handleContainerInput(containerId, slot, button, ContainerInput.SWAP, player)
 * （等价 1.8 windowClick(..., 2) 交换到热键栏）
 */
public class AutoStuck extends Module {

 // 设置
    private final com.ylmac.adapt.module.settings.impl.IntSetting pulseTicks =
            intSetting("冻结脉冲", 1, 1, 10, 1);
    private final com.ylmac.adapt.module.settings.impl.BoolSetting usePearl =
            boolSetting("使用珍珠", true);
    private final com.ylmac.adapt.module.settings.impl.BoolSetting requireVoid =
            boolSetting("需要虚空", true);
    private final com.ylmac.adapt.module.settings.impl.BoolSetting lockMovement =
            boolSetting("锁定移动", true);
    private final com.ylmac.adapt.module.settings.impl.DoubleSetting minFallDistance =
            doubleSetting("最小坠落距离", 5.0, 0.0, 50.0, 0.5);
    private final com.ylmac.adapt.module.settings.impl.DoubleSetting minVoidDepth =
            doubleSetting("最小虚空深度", 5.0, 1.0, 200.0, 1.0);
    private final com.ylmac.adapt.module.settings.impl.DoubleSetting searchRadius =
            doubleSetting("搜索半径", 10.0, 2.0, 40.0, 1.0);

    /** 是否正在坠落自救中 */
    private boolean falling;
 /** 冻结脉冲计数 */
    private int pulseCounter;
    /** 珍珠目标是否已确定 */
    private boolean hasTarget;
    /** 目标旋转（投掷角度） */
    private float targetYaw, targetPitch;
    /** 珍珠所在槽位 */
    private int pearlSlot = -1;
    /** 切槽延迟（从背包移动珍珠需要 1 tick 同步） */
    private int slotDelay;
 /** 冻结时保存的位移 */
    private Vec3 savedMotion;
    /** 当前是否处于冻结状态 */
    private boolean frozen;

    private static final int MAX_ATTEMPTS = 3;
    private int searchAttempts;

    public AutoStuck() {
        super("autostuck", "AutoStuck", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    @Override
    protected void onEnable() {
        this.falling = false;
        this.hasTarget = false;
        this.searchAttempts = 0;
        this.pulseCounter = 0;
        this.pearlSlot = -1;
        this.slotDelay = 0;
        this.frozen = false;
        this.savedMotion = null;
    }

    @Override
    protected void onDisable() {
        this.release();
        this.falling = false;
        this.hasTarget = false;
        this.pearlSlot = -1;
        this.frozen = false;
        this.savedMotion = null;
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        boolean hasPearl = findPearlSlot() != -1;

        // 条件：需要虚空时必须是虚空坠落；否则只要坠落够深
        if (requireVoid.getValue() ? this.isOverVoid(player) : this.isFalling(player)) {
            if (!this.falling) {
                // 刚进入坠落状态
                this.falling = true;
                this.searchAttempts = 0;
            }

 // 冻结脉冲：按 tick 交替冻结/释放
            this.pulseCounter++;
            if (this.pulseCounter % this.pulseTicks.getValue() == 0) {
                this.release();
            } else {
                this.freeze();
            }

            // 有珍珠则尝试自救
            if (this.usePearl.getValue() && hasPearl) {
                this.startSearch(player);
            }
        } else if (this.falling) {
            // 已脱离危险：复位
            this.falling = false;
            this.hasTarget = false;
            this.pulseCounter = 0;
            this.pearlSlot = -1;
            this.slotDelay = 0;
            this.release();
        }

        // 珍珠没了 → 放弃目标
        if (this.usePearl.getValue() && !hasPearl) {
            this.hasTarget = false;
            this.pearlSlot = -1;
            this.slotDelay = 0;
        }

        // 目标已确定 → 转向并准备投掷
        if (this.falling && this.hasTarget) {
            player.setYRot(this.targetYaw);
            player.setXRot(this.targetPitch);
            this.hasTarget = false; // 只转向一次，下 tick 投掷
            this.preparePearl(player);
        }

        // 切槽延迟后投掷珍珠
        if (this.pearlSlot != -1) {
            if (this.slotDelay > 0) {
                this.slotDelay--;
            } else {
                this.throwPearl(player, this.pearlSlot);
                this.pearlSlot = -1;
            }
        }
    }

 /** 锁移动：坠落自救期间取消键盘输入 */
    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (this.lockMovement.getValue() && this.falling) {
            event.setForward(0.0F);
            event.setStrafe(0.0F);
            event.setJump(false);
            event.setSneak(false);
        }
    }

 /** 冻结期间取消位置同步包 */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (this.frozen && event.getPacket() instanceof ServerboundMovePlayerPacket) {
            event.cancel();
        }
    }

 // ---------- 冻结/释放 ----------

    /** 冻结：保存当前位移并清零（下次移动前），阻止位移 */
    private void freeze() {
        LocalPlayer player = mc.player;
        if (player == null || this.frozen) {
            return;
        }
        this.savedMotion = player.getDeltaMovement();
        player.setDeltaMovement(Vec3.ZERO);
        this.frozen = true;
    }

    /** 释放：恢复保存的位移并停止冻结 */
    private void release() {
        LocalPlayer player = mc.player;
        if (this.frozen && player != null) {
            if (this.savedMotion != null) {
                player.setDeltaMovement(this.savedMotion);
            }
            this.savedMotion = null;
        }
        this.frozen = false;
    }

    // ---------- 坠落/虚空检测 ----------

    private boolean isFalling(LocalPlayer player) {
        return player != null && !player.onGround()
                && player.fallDistance >= this.minFallDistance.getValue().floatValue();
    }

 /** 脚下是否虚空：从脚下向下连续 minVoidDepth 格全为空气 */
    private boolean isOverVoid(LocalPlayer player) {
        if (!this.isFalling(player)) {
            return false;
        }
        BlockPos below = player.blockPosition().below();
        if (!mc.level.isEmptyBlock(below)) {
            return false;
        }
        int depth = 0;
        int minDepth = this.minVoidDepth.getValue().intValue();
        int y = below.getY();
        while (y > mc.level.getMinY() && depth < minDepth + 256) {
            if (!mc.level.isEmptyBlock(new BlockPos(below.getX(), y, below.getZ()))) {
                break;
            }
            depth++;
            if (depth >= minDepth) {
                return true;
            }
            y--;
        }
        return depth >= minDepth;
    }

    // ---------- 珍珠自救 ----------

    /** 在背包主物品栏（含热键栏，共 36 格）中找末影珍珠，返回槽位（0-35），-1 = 无 */
    private int findPearlSlot() {
        LocalPlayer player = mc.player;
        if (player == null) {
            return -1;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(Items.ENDER_PEARL)) {
                return i;
            }
        }
        return -1;
    }

    /** 启动搜索：主线程直接同步搜索（半径小、代价低） */
    private void startSearch(LocalPlayer player) {
        if (this.hasTarget || this.searchAttempts >= MAX_ATTEMPTS) {
            return;
        }
        this.searchAttempts++;
        Vec3 spot = this.findLandingSpot(player);
        if (spot != null) {
 // 计算抛射角
            Vec3 eye = player.getEyePosition();
            double dx = spot.x - eye.x;
            double dz = spot.z - eye.z;
            double dy = spot.y - eye.y;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz < 0.001) {
                return;
            }
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
 // 闭式解求 pitch
            double d4 = 2.25 - 0.03 * (0.03 * horiz * horiz + 2.0 * dy * 2.25) / 1.5;
            double pitch;
            if (d4 > 0) {
                pitch = -Math.toDegrees(Math.atan((2.25 - Math.sqrt(d4)) / (0.03 * horiz)));
            } else {
                pitch = -45.0;
            }
            this.targetYaw = yaw;
            this.targetPitch = (float) Mth.clamp(pitch - 4.0, -89.0, -1.0);
            this.hasTarget = true;
        }
    }

 /** 找最近安全落点：半径 searchRadius 内、方块上方两格均为空的方块顶 */
    private Vec3 findLandingSpot(LocalPlayer player) {
        BlockPos center = player.blockPosition();
        int radius = this.searchRadius.getValue().intValue();
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -5; dy <= 5; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!mc.level.isEmptyBlock(pos)
                            && mc.level.isEmptyBlock(pos.above())
                            && mc.level.isEmptyBlock(pos.above(2))) {
                        Vec3 spot = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                        double dist = spot.distanceToSqr(player.position());
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = spot;
                        }
                    }
                }
            }
        }
        return best;
    }

 /** 准备投掷：珍珠在热键栏直接切槽；否则从背包 SWAP 到热键栏 */
    private void preparePearl(LocalPlayer player) {
        int slot = this.findPearlSlot();
        if (slot == -1) {
            return;
        }
        if (slot <= 8) {
            this.pearlSlot = slot;
            this.slotDelay = 0;
        } else {
            int hotbar = this.findEmptyHotbarSlot();
            if (hotbar == -1) {
                hotbar = 0;
            }
            // 26.2：handleContainerInput(containerId, fromSlot, toHotbar, SWAP, player) 等价 1.8 windowClick(mode=2)
            // 容器槽映射：主物品栏 0-26 → 9-35，热键栏 27-35 → 36-44（javap InventoryMenu 布局确证）
            int containerSlot = slot < 27 ? slot + 9 : slot - 27 + 36;
            mc.gameMode.handleContainerInput(
                    player.containerMenu.containerId,
                    containerSlot,
                    hotbar,
                    ContainerInput.SWAP,
                    player);
            this.pearlSlot = hotbar;
            this.slotDelay = 1;
        }
    }

    /** 找热键栏空槽（0-8），没有则 -1 */
    private int findEmptyHotbarSlot() {
        LocalPlayer player = mc.player;
        if (player == null) {
            return -1;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

 /** 切到珍珠槽并右键投出，然后切回原槽 */
    private void throwPearl(LocalPlayer player, int slot) {
        Inventory inv = player.getInventory();
        int old = inv.getSelectedSlot();
        inv.setSelectedSlot(slot);
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        ItemStack stack = player.getMainHandItem();
        if (!stack.isEmpty() && stack.is(Items.ENDER_PEARL)) {
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        }
        inv.setSelectedSlot(old);
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(old));
        // 投出后复位
        this.falling = false;
        this.hasTarget = false;
        this.release();
    }

}
