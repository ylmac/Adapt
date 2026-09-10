package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * AutoMLG 自动落地水 —— Rise 6.9.5 AutoMLG 的逐条复刻（MC 26.2 / Fabric）。
 * <p>
 * Rise 原文（com.alan.clients.module.impl.movement.AutoMLG）四状态机：
 * <pre>
 * IDLE     坠落中（fallDistance ≥ 阈值 / motionY&lt;0 / 离地 / 非水岩 / 非飞行）
 *          → 热键栏找水桶 → bucketSlot → state = ROTATING
 * ROTATING 每 tick：下视 90° → 落地预判（包围盒下移 max(motionY*2, -4.5) 查碰撞）
 *          → 以该旋转射线 → 命中"低于玩家"的方块 → swing + use-item（水桶）
 *          → waterPos = 命中点放置位 → state = PLACED（Rise 无条件视为成功）
 * PLACED   保持下视；落地或入水 → PICKUP（60 tick 超时）
 * PICKUP   主手已是**水桶** → 成功（说明放水被服务端回滚/没放出去）；
 *          主手是**空桶** → 在 7×5×7 内找静态水源 → 转向 → 等 pickupDelay
 *          → use-item 收水 → 成功；pickupDelay+40 tick 超时
 * </pre>
 * 26.2 API 对照：
 * <ul>
 *   <li>点击方向：{@code LocalPlayer.setYRot/setXRot}（本地预测 + use-item 包自带旋转，
 *       服务端 handleUseItem 会临时套用包内旋转做射线 → 水就放在我们看的方向）；</li>
 *   <li>用水桶：{@code gameMode.useItem(player, MAIN_HAND)}（= 1.8 的 C08 dir=255
 *       use-item 路径；**不能**用 useItemOn，那是方块放置路径）；</li>
 *   <li>挥手：{@code player.swing(MAIN_HAND)}（= C0A）；</li>
 *   <li>换槽：26.2 的 use-item 包不带物品（服务端按 selected 解析）→ 必须真实换槽 +
 *       {@code ServerboundSetCarriedItemPacket}；本模块结束时恢复原槽。</li>
 * </ul>
 * <p>
 * ⚠ 之前移植版的三处致命错误（用户实测"automlg 报废"）：
 * 1. 全背包找水桶却又只能切 0-8 号槽 → 水桶在主背包时 每 tick IDLE→ROTATING→reset 死循环；
 * 2. PICKUP 判定反了（把"空桶"当成功）→ 放完水立刻误判结束，从不收水；
 * 3. 回收分支要求空桶，但已被 2 短路 → 死代码。
 */
public class AutoMLG extends Module {

    /** 状态机（Rise ScaffoldState 枚举语义） */
    private enum State {
        IDLE, ROTATING, PLACED, PICKUP
    }

    private final com.ylmac.adapt.features.module.settings.impl.DoubleSetting fallDistance =
            doubleSetting("坠落距离", 4.0, 3.0, 20.0, 0.5);
    private final com.ylmac.adapt.features.module.settings.impl.IntSetting pickupDelay =
            intSetting("拾取延迟", 4, 1, 20, 1);
    private final com.ylmac.adapt.features.module.settings.impl.BoolSetting autoPickup =
            boolSetting("自动拾取", true);
    private final com.ylmac.adapt.features.module.settings.impl.BoolSetting autoDisable =
            boolSetting("自动关闭", false);

    private State state = State.IDLE;
    private int bucketSlot = -1;
    private int stateTicks;
    private BlockPos waterPos;

    /** 进入 MLG 前的槽位/视角（模块结束时恢复 —— Rise 原版不恢复手牌槽，这里补偿） */
    private int savedSlot = -1;
    private float savedYRot;
    private float savedXRot;

    public AutoMLG() {
        super("automlg", "AutoMLG", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        restorePlayer();
        resetState();
    }

    private void resetState() {
        this.state = State.IDLE;
        this.bucketSlot = -1;
        this.stateTicks = 0;
        this.waterPos = null;
    }

    /** 恢复进入 MLG 前的槽位与视角（Rise 原版不恢复，这里作为补偿）。 */
    private void restorePlayer() {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (savedSlot >= 0 && savedSlot <= 8) {
            player.getInventory().setSelectedSlot(savedSlot);
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(savedSlot));
            }
        }
        // 只在"视角还停在我们强制的位置"时恢复，避免覆盖玩家自己的操作
        if (state != State.IDLE) {
            player.setYRot(savedYRot);
            player.setXRot(savedXRot);
        }
        savedSlot = -1;
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.isDeadOrDying()) return;

        switch (this.state) {
            case IDLE -> handleIdle(player);
            case ROTATING -> handleRotating(player);
            case PLACED -> handlePlaced(player);
            case PICKUP -> handlePickup(player);
        }
    }

    // ───────────────────────── 状态处理 ─────────────────────────

    /** IDLE：检测坠落并开始 MLG（Rise handleIdle 逐条）。 */
    private void handleIdle(LocalPlayer player) {
        if (player.fallDistance < this.fallDistance.getValue().floatValue()
                || player.getDeltaMovement().y >= 0.0
                || player.isInWater()
                || player.isInLava()
                || player.onGround()
                || player.getAbilities().flying) {
            return;
        }
        int slot = findItem(Items.WATER_BUCKET);
        if (slot != -1) {
            this.bucketSlot = slot;
            this.savedSlot = player.getInventory().getSelectedSlot();
            this.savedYRot = player.getYRot();
            this.savedXRot = player.getXRot();
            selectSlot(slot);
            this.state = State.ROTATING;
            this.stateTicks = 0;
        }
    }

    /** ROTATING：下落中下视并朝脚下放水（Rise handleRotating 逐条）。 */
    private void handleRotating(LocalPlayer player) {
        this.stateTicks++;
        // ① 失败：已落地/入水/死亡/超时 100 tick
        if (player.onGround() || player.isInWater() || player.isDeadOrDying() || this.stateTicks > 100) {
            restorePlayer();
            resetState();
            return;
        }
        // ② 放弃：开始上升（被弹起/末影珍珠等）
        if (player.getDeltaMovement().y > 0.5) {
            restorePlayer();
            resetState();
            return;
        }
        // ③ 保持水桶在手上（Rise: 每 tick SlotComponent.b(bucketSlot, true)）
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(Items.WATER_BUCKET)) {
            // 可能被其它模块换走了 —— 重新钉住一次
            if (this.bucketSlot >= 0) selectSlot(this.bucketSlot);
            if (!player.getMainHandItem().is(Items.WATER_BUCKET)) {
                resetState();
                return;
            }
            stack = player.getMainHandItem();
        }

        // 下视 90°：本地视角（use-item 包会带上它；服务端 handleUseItem 临时套用）
        player.setXRot(90.0F);

        // 落地预判（Rise collidesAtOffset）：把包围盒按 max(motionY*2, -4.5) 下移后查碰撞
        double offset = Math.max(player.getDeltaMovement().y * 2.0, -4.5);
        if (!collidesAtOffset(player, offset)) {
            return;
        }

        // 以当前旋转射线（pitch=90 → 正下方），命中点必须严格低于玩家脚部
        HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (hit == null
                || hit.getType() != HitResult.Type.BLOCK
                || ((BlockHitResult) hit).getBlockPos() == null
                || ((BlockHitResult) hit).getBlockPos().getY() >= player.getY()) {
            return;
        }
        BlockHitResult bhr = (BlockHitResult) hit;

        // 放水：swing（C0A）+ use-item（C08 dir=255 等价）
        player.swing(InteractionHand.MAIN_HAND);
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);

        this.waterPos = getPlacedPos(bhr);
        this.state = State.PLACED;
        this.stateTicks = 0;
    }

    /** PLACED：等待落地/入水 → PICKUP（Rise handlePlaced）。 */
    private void handlePlaced(LocalPlayer player) {
        this.stateTicks++;
        player.setXRot(90.0F);
        if (!player.onGround() && !player.isInWater()) {
            // 仍在空中下落：60 tick 还没落地 = 放水失败
            if (this.stateTicks > 60) {
                resetState();
            }
            return;
        }
        // 落地或入水（= 水确实放出来了）
        if (this.autoPickup.getValue()) {
            this.state = State.PICKUP;
            this.stateTicks = 0;
        } else {
            finish(player);
        }
    }

    /** PICKUP：找最近静态水源并用空桶回收（Rise handlePickup，含判定方向修正）。 */
    private void handlePickup(LocalPlayer player) {
        this.stateTicks++;
        ItemStack stack = player.getMainHandItem();
        if (stack.is(Items.WATER_BUCKET)) {
            // ⚠ Rise 判据是**水桶**（说明放水没生效/被回滚，桶还是满的）→ 直接成功。
            //   之前移植版误判成"空桶=成功"，放完水（手上正是空桶）立刻结束 → 永不收水。
            finish(player);
            return;
        }
        BlockPos pos = getWaterPos(player);
        if (pos != null) {
            lookAt(player, pos);
        } else {
            player.setXRot(90.0F);
        }
        if (this.stateTicks >= this.pickupDelay.getValue()) {
            ItemStack hand = player.getMainHandItem();
            if (hand.is(Items.BUCKET) && pos != null && pickupWater(player)) {
                finish(player);
                return;
            }
        }
        if (this.stateTicks > this.pickupDelay.getValue() + 40) {
            resetState();
        }
    }

    /** 成功收尾（Rise: 提示 + reset + 可选自动关闭）。 */
    private void finish(LocalPlayer player) {
        restorePlayer();
        resetState();
        if (this.autoDisable.getValue()) {
            this.toggle();
        }
    }

    // ───────────────────────── 工具方法 ─────────────────────────

    /**
     * 热键栏（0-8）查找物品 —— Rise SlotUtil.findItem 语义。
     * <p>
     * ⚠ 不能搜主背包：26.2 的 {@code Inventory.setSelectedSlot} 只接受 0-8，
     * 主背包槽无法成为"选中槽"，搜到了也切不了手 → 每 tick 空转。
     */
    private int findItem(net.minecraft.world.item.Item item) {
        LocalPlayer player = mc.player;
        if (player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        return -1;
    }

    /** 切换选中槽位 + 同步服务端（26.2: use-item 包按 selected 解析物品）。 */
    private void selectSlot(int slot) {
        LocalPlayer player = mc.player;
        if (player == null || slot < 0 || slot > 8) return;
        player.getInventory().setSelectedSlot(slot);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    /**
     * Rise collidesAtOffset：把玩家包围盒下移 offset 后是否与方块碰撞
     * （= "再落一两个 tick 就要着地了"）。
     */
    private boolean collidesAtOffset(LocalPlayer player, double offset) {
        if (mc.level == null) return false;
        AABB box = player.getBoundingBox().move(0.0, offset, 0.0);
        return !mc.level.noCollision(player, box);
    }

    /** 计算放置后水方块所在位置（Rise getPlacedPos：目标不可替换则放在命中方块上）。 */
    private BlockPos getPlacedPos(BlockHitResult hit) {
        if (hit == null || hit.getBlockPos() == null || hit.getDirection() == null) {
            return null;
        }
        BlockPos target = hit.getBlockPos().relative(hit.getDirection());
        BlockState state = mc.level.getBlockState(target);
        if (state.canBeReplaced()) {
            return target;
        }
        return hit.getBlockPos();
    }

    /** 找最近的静态水源（Rise findNearestWater：7×5×7，仅 LEVEL==0 的源水）。 */
    private BlockPos findNearestWater(LocalPlayer player) {
        BlockPos center = player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = -3; i <= 3; i++) {
            for (int j = -2; j <= 2; j++) {
                for (int k = -3; k <= 3; k++) {
                    BlockPos pos = center.offset(i, j, k);
                    if (isSourceWater(pos)) {
                        double dist = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
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

    /** 是否静态水源（Rise e()：water 且 level==0 ⇔ FluidState.isSourceOfType(WATER)）。 */
    private boolean isSourceWater(BlockPos pos) {
        if (mc.level == null) return false;
        FluidState fluid = mc.level.getFluidState(pos);
        return fluid.isSourceOfType(Fluids.WATER);
    }

    /** 当前记录或最近的水源位置（Rise getWaterPos：缓存仍有效就用缓存）。 */
    private BlockPos getWaterPos(LocalPlayer player) {
        if (this.waterPos != null && isSourceWater(this.waterPos)) {
            return this.waterPos;
        }
        BlockPos pos = findNearestWater(player);
        if (pos != null) {
            this.waterPos = pos;
        }
        return this.waterPos;
    }

    /** 空桶右键水源回收（Rise pickupWater：对准 → use-item）。 */
    private boolean pickupWater(LocalPlayer player) {
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty() || !hand.is(Items.BUCKET)) return false;
        // 本地预测与包内旋转都取当前视角 → lookAt 已对齐水源
        return mc.gameMode.useItem(player, InteractionHand.MAIN_HAND).consumesAction();
    }

    /** 视角对准方块中心（Rise RotationUtil.h 公式）。 */
    private void lookAt(LocalPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3 diff = target.subtract(eye);
        float yaw = (float) (Math.toDegrees(Mth.atan2(diff.z, diff.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Mth.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));
        player.setYRot(yaw);
        player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
    }
}
