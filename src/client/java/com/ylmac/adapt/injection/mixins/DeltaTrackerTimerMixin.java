package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.tickbase.TimerUtil;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DeltaTracker.Timer 加速注入（Timer 加速基础设施，供 TickBase 等模块使用）。
 * <p>
 * 26.2 中旧 {@code Minecraft.timer} 字段已移除，Timer 移到
 * {@link DeltaTracker.Timer}，且 deltaTicks 为 private 字段
 * （javap -p 确证：{@code private float deltaTicks}，由
 * {@code advanceGameTime(J)I} 每次计算后写入）。
 * <p>
 * 注入点（API 参考第 5 节）：在 {@code advanceGameTime(J)I} 的
 * FIELD PUTFIELD deltaTicks AFTER 处把 deltaTicks 乘以当前 multiplier
 * （倍率存于 {@link TimerUtil}，模块经 applyMultiplier/clearMultiplier 设置），
 * 实现游戏 tick 加速（等价 1.8.9 的 timer.timerSpeed 乘法）。
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {

    @Shadow
    private float deltaTicks;

    /**
     * 在 advanceGameTime 写入 deltaTicks 之后乘上倍率。
     * 只作用于"本线程/本 Timer 实例"——TimerUtil 的 ThreadLocal 在游戏主线程
     * 单线程渲染循环里即等价于全局，且不与其他混用 Timer 的场景冲突。
     */
    @Inject(method = "advanceGameTime", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/DeltaTracker$Timer;deltaTicks:F",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void adapt_onAdvanceGameTime(CallbackInfoReturnable<Integer> cir) {
        float multiplier = TimerUtil.getMultiplier();
        if (multiplier != 1.0F) {
            this.deltaTicks *= multiplier;
        }
    }
}
