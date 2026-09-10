package com.ylmac.adapt.features.module.impl.ghost;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * SafeWalk 边缘防掉落（移植 Rise 6.9.5 ghost/SafeWalk，适配 Fabric 26.2）。
 * <p>
 * 26.2 边缘防掉落链路（javap 确证）：Player.travel → 位移 x/z 保留
 * （0.05 容差内）依赖 isStayingOnGroundSurface()（原版 = isShiftKeyDown()）。
 * PlayerSafeWalkMixin 直接 OR 本模块状态，激活时玩家站在方块边缘不会掉落，
 * 等价原版潜行行为但不带潜行减速。
 * <p>
 * 与原版 Rise 对照：
 * - 原版 PreUpdateEvent 里判断 onGround + 按键 + 手持方块后置 player 字段；
 *   26.2 的按键（ClientInput.keyPresses）与"边缘判定"分离，本模块改为在
 *   PlayerTickEvent.Pre 中实时检查脚下方块并驱动 mixin（mixin 只做"是否激活"
 *   判断，具体逻辑全在本模块）。
 * - blocksOnly：仅在手持方块（BlockItem）时防掉落 —— 搭桥（bridge）场景
 *   配合瞄准脚下使用，与原版一致。
 * - backwardsOnly：仅在后退（S 键）时防掉落。
 */
public class SafeWalk extends Module {

    /** 单例引用（PlayerSafeWalkMixin 经此查询状态） */
    private static SafeWalk instance;

    public static SafeWalk get() {
        return instance;
    }

    /** 仅手持方块时生效（搭桥用） */
    private BoolSetting blocksOnly;
    /** 仅后退时生效 */
    private BoolSetting backwardsOnly;
    /** 边缘检测灵敏度：脚下的"虚空/无支撑"判定高度（越大越激进） */
    private com.ylmac.adapt.features.module.settings.impl.DoubleSetting edgeTolerance;

    public SafeWalk() {
        super("safewalk", "SafeWalk", Category.MOVEMENT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
        blocksOnly = boolSetting("仅方块", false);
        backwardsOnly = boolSetting("仅后退", false);
        edgeTolerance = doubleSetting("边缘容差", 0.1, 0.0, 0.5, 0.01);
    }

    /**
     * mixin 查询入口：模块启用、未开界面、且满足 blocksOnly/backwardsOnly 条件时
     * 返回 true（触发 isStayingOnGroundSurface → 边缘防掉落）。
     * 脚下是否有支撑的检测放在 tick 里做，避免 mixin 每帧重复计算。
     */
    public boolean shouldSafeWalk() {
        if (!this.isEnabled() || mc.gui.screen() != null) {
            return false;
        }
        if (mc.player == null || mc.level == null) {
            return false;
        }
        Player player = mc.player;
        // 空中/水中/飞行时不干预（Rise 原版仅 onGround 生效）
        if (!player.onGround() || player.isFallFlying() || player.getAbilities().flying) {
            return false;
        }
        if (this.blocksOnly.getValue() && !this.isHoldingBlock()) {
            return false;
        }
        if (this.backwardsOnly.getValue() && !this.isMovingBackwards()) {
            return false;
        }
        // 脚下无支撑（下方无实心方块）才需要防掉落
        return !this.hasSupportBelow(player);
    }

    /** 手持主手/副手物品是否为方块（Rise SlotComponent + ItemBlock 判断） */
    private boolean isHoldingBlock() {
        ItemStack main = mc.player.getMainHandItem();
        ItemStack off = mc.player.getOffhandItem();
        return main.getItem() instanceof BlockItem || off.getItem() instanceof BlockItem;
    }

    /** 是否正在后退（S 键按下；Rise keyBindForward 的 26.2 对应） */
    private boolean isMovingBackwards() {
        return mc.options.keyDown.isDown();
    }

    /**
     * 脚下是否有支撑：玩家包围盒底部下方 edgeTolerance 处水平截面的四个角
     * 检查碰撞（BlockStateBase.getCollisionShape(world, pos) 非空）。
     * 四个角都在空中 = 边缘 → 返回 false（需要防掉落）。
     */
    private boolean hasSupportBelow(Player player) {
        var box = player.getBoundingBox();
        double half = box.getXsize() * 0.4; // 略收窄，贴近方块边缘仍算有支撑
        double checkY = box.minY - this.edgeTolerance.getValue();
        double minX = box.minX + half;
        double maxX = box.maxX - half;
        double minZ = box.minZ + half;
        double maxZ = box.maxZ - half;
        Vec3[] corners = {
                new Vec3(minX, checkY, minZ),
                new Vec3(maxX, checkY, minZ),
                new Vec3(minX, checkY, maxZ),
                new Vec3(maxX, checkY, maxZ),
        };
        for (Vec3 corner : corners) {
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(corner);
            var state = mc.level.getBlockState(pos);
            if (!state.isAir() && !state.getCollisionShape(mc.level, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 诊断辅助（预留）：tick 里可打印状态，正式版不启用 */
    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        // 仅用于调试时观察 hasSupportBelow 结果；无输出逻辑，避免噪音
    }
}
