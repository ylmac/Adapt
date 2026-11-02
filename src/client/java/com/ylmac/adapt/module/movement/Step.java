package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 自动跨步。
 * <p>
 * 原理（26.2 API 已 javap + 编译测试确证）：
 * - 26.2 中「可跨上的方块高度」由实体 {@code maxUpStep} 决定：
 * 基类 Entity 恒返回 0；LivingEntity 重写为
 * {@code getAttributeValue(Attributes.STEP_HEIGHT)}（玩家还强制下限 1.0，
 * 见 LivingEntity.maxUpStep 字节码：乘客为 Player 时 Math.max(value, 1f)）。
 * 原版跨步逻辑位于 {@code Entity.collide}：当水平位移被墙挡住且站在地上时，
 * 向上推 maxUpStep 尝试找到可跨越的高度（collectCandidateStepUpHeights）。
 * - 因此本实现：
 * 用 STEP_HEIGHT 属性「修改器」实现：
 * - 原始 STEP_HEIGHT 基值 = 0.6（1.8.9 的默认 stepHeight 语义）；
 * - 模块开启时把基值 setBaseValue(设置高度)，使原版跨步系统自动跨上
 * 对应高度的台阶/方块（走原版碰撞逻辑，绕开需要自己发包/改位移的 hack 手法）；
 * - 关闭时还原基值（保守还原到 0.6，即原版默认）。
 * - 优点：不造包、不手写跨步动画，完全走 1.8.9 客户端等价的原版跨步路径；
 * 高度设置实时生效，支持 0.6~3.0 格。
 * <p>
 * 本实现聚焦「跨上台阶」这一核心能力（任务要求：检测脚下方块高度→自动跨上、
 * 支持高度设置）。
 */
public class Step extends Module {

 /** 跨步高度 */
    private final DoubleSetting height;

    /** 原 STEP_HEIGHT 属性基值（关闭时还原；基类默认 0.6） */
    private double oldStepHeight = 0.6;

    public Step() {
        super("step", "Step", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        height = doubleSetting("高度", 1.0, 0.6, 3.0, 0.1);
    }

    @Override
    protected void onEnable() {
        if (nullCheck()) return;
        // 记住原基值，关闭时还原（避免污染其他系统）
        AttributeInstance inst = mc.player.getAttribute(Attributes.STEP_HEIGHT);
        if (inst != null) {
            oldStepHeight = inst.getBaseValue();
        }
    }

    @Override
    protected void onDisable() {
        if (mc.player == null) return;
        restoreStepHeight();
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck() || !isEnabled()) return;
        applyStepHeight();
    }

    /** 实时应用跨步高度（改 STEP_HEIGHT 属性基值，走原版跨步逻辑） */
    private void applyStepHeight() {
        AttributeInstance inst = mc.player.getAttribute(Attributes.STEP_HEIGHT);
        if (inst == null) return;
        double target = height.getValue();
        // 玩家 maxUpStep 有强制下限 1.0（LivingEntity 字节码），低于 1 的设置无效，
        // 因此 <= 1.0 直接设 1.0 即可获得完整的 1 格跨步
        if (target < 1.0) {
            target = 1.0;
        }
        // 仅当需要时才写（避免每 tick 无谓的 attribute 事件）
        if (Math.abs(inst.getBaseValue() - target) > 1.0E-4) {
            inst.setBaseValue(target);
        }
    }

    /** 还原 STEP_HEIGHT 基值到模块开启前的值 */
    private void restoreStepHeight() {
        AttributeInstance inst = mc.player.getAttribute(Attributes.STEP_HEIGHT);
        if (inst == null) return;
        inst.setBaseValue(oldStepHeight);
    }

}
