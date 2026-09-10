package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;

import java.util.List;

/**
 * 自动疾跑（移植自 LiquidBounce ModuleSprint）。
 * <p>
 * LB 核心：SprintEvent 里把 event.sprint 置 true（疾跑决策被强制为"疾跑"）。
 * 26.2 无 SprintEvent，等价实现：每 tick 有前进输入时 setSprinting(true)。
 * <p>
 * 模式（LB SprintMode）：
 * - Legit：仅前进（W）时疾跑（原版行为，匹配原版 sprint 条件）
 * - Omnidirectional：任意方向移动都疾跑（含后/侧/静止转身）
 * <p>
 * 附加（LB StopOn 简化）：用物品时（isSlowDueToUsingItem）停止疾跑（原版吃/射箭
 * 会自然取消，这里防自动复位后不重开）；潜行时停止（LB StopOn.SNEAKING）。
 */
public class Sprint extends Module {

    /** 模式（LB SprintMode：Legit / Omnidirectional） */
    private final ChoiceSetting mode;
    /** 潜行时停止疾跑（LB StopOn.SNEAKING） */
    private final BoolSetting stopOnSneak;
    /** 用物品时停止疾跑（LB StopOn.USING_ITEM） */
    private final BoolSetting stopOnUse;

    public Sprint() {
        super("sprint", "Sprint", Category.MOVEMENT);
        setDefaultEnabled(true);
        setDefaultHidden(false);
        mode = choiceSetting("Mode", "Legit", List.of("Legit", "Omnidirectional"));
        stopOnSneak = boolSetting("停止-潜行", true);
        stopOnUse = boolSetting("停止-用物品", true);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck() || !isEnabled()) return;
        net.minecraft.client.player.LocalPlayer player = mc.player;

        // 停止条件（LB StopOn）
        if (stopOnSneak.getValue() && player.isShiftKeyDown()) {
            player.setSprinting(false);
            return;
        }
        if (stopOnUse.getValue() && player.isUsingItem()) {
            player.setSprinting(false);
            return;
        }

        // 前进输入判定：moveVector.y = 按 W +1 / 按 S -1（javap KeyboardInput 确证）
        float forward = player.input.getMoveVector().y;
        if (forward <= 0.0F && "Legit".equals(mode.getValue())) {
            // Legit：仅前进疾跑；无前进输入不强制（保持原版状态）
            return;
        }

        // 有输入（或全方向模式）→ 强制疾跑
        player.setSprinting(true);
    }

    @Override
    public String getInfo() {
        return mode.getValue();
    }
}
