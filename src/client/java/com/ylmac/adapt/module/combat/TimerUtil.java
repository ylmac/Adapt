package com.ylmac.adapt.module.combat;

/**
 * 游戏 tick 加速工具（Timer 加速基础设施，供 TickBase 等模块使用）。
 * <p>
 * 26.2 中旧 {@code Minecraft.timer} 字段已移除，Timer 移到
 * {@link net.minecraft.client.DeltaTracker.Timer}，且 deltaTicks 为 private
 * 字段（javap -p 确证），由 {@code advanceGameTime(J)I} 每次计算后写入。
 * <p>
 * 本类保存"当前生效的加速倍率"，由
 * {@link com.ylmac.adapt.mixins.player.DeltaTrackerTimerMixin} 在
 * advanceGameTime 写入 deltaTicks 之后读取并相乘，实现游戏 tick 加速
 * （等价 1.8.9 的 timer.timerSpeed 乘法）。
 * <p>
 * 对外接口（静态，owner 参数仅为调用方标识，便于模块各自申请/释放）：
 * - {@link #applyMultiplier(Object, float)}：设置并立即生效（如 TickBase 开局 0.5F）
 * - {@link #clearMultiplier(Object)}：清除加速，恢复原速（模块 onDisable 时调用）
 * multiplier 为 1.0F 时保持原速；模块未申请时不干预（避免影响其他模块）。
 */
public final class TimerUtil {

    /** 当前生效的加速倍率（1.0 = 原速）；无模块申请时为 1.0 */
    private static final ThreadLocal<Float> MULTIPLIER = ThreadLocal.withInitial(() -> 1.0F);

    private TimerUtil() {}

    /** 申请加速：设置倍率（用于 TickBase 开局低倍速缓冲等） */
    public static void applyMultiplier(Object owner, float multiplier) {
        MULTIPLIER.set(Math.max(0.05F, multiplier));
    }

    /** 清除加速：恢复原速（模块 onDisable 时调用，防止遗留加速） */
    public static void clearMultiplier(Object owner) {
        MULTIPLIER.set(1.0F);
    }

    /** 当前倍率（DeltaTrackerTimerMixin 在 advanceGameTime 后读取） */
    public static float getMultiplier() {
        return MULTIPLIER.get();
    }
}
