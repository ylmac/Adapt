package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.mixins.world.MultiPlayerGameModeAccessor;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <p>
 * 26.2 的破坏进度 destroyProgress 是 MultiPlayerGameMode 的私有字段
 * （javap 确证：destroyProgress/destroyTicks/destroyDelay 私有），
 * 通过 {@link MultiPlayerGameModeAccessor} 访问器读写（已注册到 adapt.mixins.json）。
 * <p>
 * 模式：
 *；
 * - Percentage：每 tick 直接注入固定百分比进度。
 * 26.2 直接移除玩家身上的 MINING_FATIGUE 药水效果实例）；忽略破坏延迟（blockHitDelay 等价 destroyDelay 清零）。
 */
public class FastBreak extends Module {

    public FastBreak() {
        super("fastbreak", "FastBreak", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        // 模式：TICKS = 每 tick 补 ticks 倍进度；PERCENTAGE = 每 tick 注入固定百分比
        enumSetting("模式", Mode.TICKS);
 // Ticks 模式：每次破坏 tick 额外累加的相对硬度倍数
        intSetting("补进度倍数", 1, 1, 5, 1);
 // Percentage 模式：每 tick 注入的百分比
        intSetting("百分比", 50, 1, 100, 1);
        boolSetting("忽略挖掘疲劳", false);
        // 清除破坏延迟（26.2 的 destroyDelay，1.8 的 blockHitDelay 等价物）
        boolSetting("忽略破坏延迟", true);
    }

    public enum Mode {
        TICKS,
        PERCENTAGE
    }

    private Mode mode() {
        return (Mode) settings.get(0).getValue();
    }

    private int ticksMultiplier() {
        return (int) settings.get(1).getValue();
    }

    private int percentage() {
        return (int) settings.get(2).getValue();
    }

    private boolean ignoreFatigue() {
        return (boolean) settings.get(3).getValue();
    }

    private boolean ignoreDelay() {
        return (boolean) settings.get(4).getValue();
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck()) return;
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (gameMode == null) return;
        MultiPlayerGameModeAccessor accessor = (MultiPlayerGameModeAccessor) gameMode;

        // 忽略挖掘疲劳：直接移除玩家身上的 MINING_FATIGUE 效果
        if (ignoreFatigue()) {
            mc.player.removeEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE);
        }

        // 忽略破坏延迟（destroyDelay > 0 时 continueDestroyBlock 会跳过进度累加）
        if (ignoreDelay()) {
            accessor.adapt_setDestroyDelay(0);
        }

        // 没有正在破坏的方块就不加速
        if (!accessor.adapt_getIsDestroying()) {
            return;
        }

        float progress = accessor.adapt_getDestroyProgress();
        if (progress <= 0.0f) {
            return;
        }

        if (mode() == Mode.PERCENTAGE) {
            // 每 tick 注入固定百分比进度（0~1 区间）
            float add = Math.max(percentage(), 1) / 100.0f;
            accessor.adapt_setDestroyProgress(Math.min(progress + add, 0.99f));
        } else {
            // Ticks 模式：按目标方块相对硬度 × 倍数累加
            BlockPos pos = accessor.adapt_getDestroyBlockPos();
            if (pos != null && mc.level != null) {
                BlockState state = mc.level.getBlockState(pos);
                // 单 tick 破坏进度增量 = 玩家对当前方块的单次相对破坏速度
                float perTick = state.getDestroyProgress(mc.player, mc.level, pos);
                float add = perTick * Math.max(ticksMultiplier(), 1);
                accessor.adapt_setDestroyProgress(Math.min(progress + add, 0.99f));
            }
        }
    }
}
