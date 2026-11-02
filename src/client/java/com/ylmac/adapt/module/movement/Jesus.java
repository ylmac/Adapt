package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/**
 * 水上行走。
 * <p>
 * 原理（26.2 API 已 javap + 编译测试确证）：
 * 26.2 无 BlockAABB 事件；但原版 {@code Entity.travel → LivingEntity.travelInWater}
 * 的行为是：只要「玩家脚所在方块是水」就走游泳物理（水平减速 + 垂直悬浮 0.04/tick）。
 * 本实现走更稳定的「位移修正」路径：
 * 1. 检测：脚下方块（getBlockPosBelowThatAffectsMyMovement，即脚底偏下 0.5 的方块）
 * 是水（FluidTags.WATER）且玩家未完全浸入水中（!isUnderWater）；
 * 2. 悬浮：把垂直速度收敛到 0（deltaMovement.y 向 0 逼近，抵消水的下沉），
 * 令玩家「浮」在水面上行走；
 * 3. 水平加速：水会严重减速水平移动（getWaterSlowDown = 0.8 + 0.546→0.2 修正），
 * 这里把水平速度按地面步行速度补偿，让水面行走手感接近陆地；
 * 4. 起跳：按住跳跃时给一次向上冲量（jumpFromGround 语义等价），可以跳出水。
 * 才改碰撞箱），此时玩家正常沉入水中，可用来随时下潜。
 * - 模式：
 * - VANILLA：纯浮起 + 水平补偿（最稳，任意服务器）；
 * 用 setPosY(-0.015625) 下沉），本实现用 setOnGround + 垂直速度微调模拟。
 * <p>
 * 设置：模式、浮力（垂直收敛速度，越大浮得越稳）、速度补偿比例、是否允许起跳。
 */
public class Jesus extends Module {

    /** 模式：VANILLA = 稳定浮起；NCP = 额外贴水面判定 */
    public enum Mode {
        VANILLA,
        NCP
    }

 /** 模式 */
    private final com.ylmac.adapt.module.settings.impl.EnumSetting<Mode> mode;
    /** 浮力：垂直速度向 0 收敛的强度（0~1，越大浮得越稳，默认 1） */
    private final DoubleSetting buoyancy;
    /** 水平速度补偿比例（%：把水减速补回来多少，默认 100） */
    private final DoubleSetting speedCompensation;
 /** 允许按住空格起跳 */
    private final BoolSetting allowJump;

    public Jesus() {
        super("jesus", "Jesus", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        mode = enumSetting("模式", Mode.VANILLA);
        buoyancy = doubleSetting("浮力", 1.0, 0.0, 1.0, 0.05);
        speedCompensation = doubleSetting("速度补偿", 100.0, 0.0, 200.0, 5.0);
        allowJump = boolSetting("允许跳跃", true);
    }

    @Override
    public String getInfo() {
        return mode.getValue().name();
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck() || !isEnabled()) return;

        // 水中判定：完全浸没（isUnderWater = 眼睛进水）时不再浮起，恢复游泳
        if (mc.player.isUnderWater()) {
            return;
        }

        // 脚下方块是否水（getBlockPosBelowThatAffectsMyMovement = 脚底 -0.5 的方块）
        BlockPos foot = mc.player.getBlockPosBelowThatAffectsMyMovement();
        boolean onWater = mc.level.getFluidState(foot).getType().is(FluidTags.WATER);
        // 也允许「身体部分在水中但脚未到底」的情况（身体中心附近有水）
        BlockPos body = BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        boolean inWater = mc.player.isInWater()
                || mc.level.getFluidState(body).getType().is(FluidTags.WATER)
                || mc.level.getFluidState(body.above()).getType().is(FluidTags.WATER);

        if (!onWater && !inWater) {
            return;
        }

        // !keyBindSneak.isKeyDown 才把水方块碰撞箱改成整格）：
        // 潜行 = 不浮起、不下沉修正，让玩家正常沉入水中。
        if (mc.player.isShiftKeyDown()) {
            return;
        }

        Vec3 delta = mc.player.getDeltaMovement();

 // 1. 悬浮：垂直速度向 0 收敛
        double y = delta.y;
        if (y > 0) {
            // 上升时不干预（起跳/波峰），避免卡住跳跃
        } else {
            // 下沉时按浮力拉回 0
            y *= (1.0 - buoyancy.getValue());
        }
        // 保持微小的正浮力（抵消每 tick 的 0.04 水浮力与重力的净下沉）
        if (y < 0.02 && buoyancy.getValue() > 0) {
            y = 0.02;
        }

        // 2. 水平速度补偿：把水减速补回来
        double factor = 1.0 + (speedCompensation.getValue() / 100.0) * 0.3;
        double x = delta.x * factor;
        double z = delta.z * factor;

        mc.player.setDeltaMovement(x, y, z);

        // 3. 起跳：按住空格时给向上冲量（等价 jumpInLiquid 的语义，可跳出水面）
        if (allowJump.getValue() && mc.player.input.keyPresses.jump()) {
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.42, mc.player.getDeltaMovement().z);
        }

 // 4. NCP 模式：额外置 onGround，模拟「站在水上」
        if (mode.getValue() == Mode.NCP) {
            mc.player.setOnGround(true);
        }
    }

}
