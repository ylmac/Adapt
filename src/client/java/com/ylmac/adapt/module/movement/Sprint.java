package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.ChoiceSetting;

import java.util.List;

/**
 * 自动疾跑。每 tick 有移动输入时 setSprinting(true)。
 * Legit 只在按住前进时疾跑，Omnidirectional 任意方向都疾跑。
 * 用物品或潜行时可以停掉。
 */
public class Sprint extends Module {

    /** 模式：Legit / Omnidirectional */
    private final ChoiceSetting mode;
    /** 潜行时停止疾跑 */
    private final BoolSetting stopOnSneak;
    /** 用物品时停止疾跑 */
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

        // 停止条件
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
