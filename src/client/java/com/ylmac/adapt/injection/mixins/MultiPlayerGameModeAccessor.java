package com.ylmac.adapt.injection.mixins;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 MultiPlayerGameMode 的私有破坏进度字段（26.2 中 destroyProgress /
 * destroyTicks / destroyDelay / destroyBlockPos / isDestroying 全部私有，
 * 1.8 时代的 curBlockDamageMP / blockHitDelay 公开字段在 26.2 已不存在）。
 * <p>
 * FastBreak 需要读/写 destroyProgress 实现"进度加速"，
 * AutoTool 需要读 destroyBlockPos 判断正在挖掘的目标方块。
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {

    @Accessor("destroyProgress")
    float adapt_getDestroyProgress();

    @Accessor("destroyProgress")
    void adapt_setDestroyProgress(float value);

    @Accessor("destroyTicks")
    float adapt_getDestroyTicks();

    @Accessor("destroyTicks")
    void adapt_setDestroyTicks(float value);

    @Accessor("destroyDelay")
    int adapt_getDestroyDelay();

    @Accessor("destroyDelay")
    void adapt_setDestroyDelay(int value);

    @Accessor("destroyBlockPos")
    BlockPos adapt_getDestroyBlockPos();

    @Accessor("isDestroying")
    boolean adapt_getIsDestroying();
}
