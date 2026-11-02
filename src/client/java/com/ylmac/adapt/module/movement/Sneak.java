package com.ylmac.adapt.module.movement;

import com.mojang.blaze3d.platform.InputConstants;
import com.ylmac.adapt.module.combat.KeyBindUtil;
import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.mixins.input.KeyMappingAccessor;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.ChoiceSetting;
import net.minecraft.client.KeyMapping;

import java.util.List;

/**
 * Sneak 持续潜行。
 * <p>
 * 原理（26.2 客户端 API，已 javap 确证）：
 * - 26.2 的潜行输入链：KeyboardInput.tick 读 {@code options.keyShift.isDown} 写入
 * {@code keyPresses.shift}，LocalPlayer.aiStep / applyInput / travel 据此处理潜行
 * （缩碰撞箱、减速、下蹲动画，并随 ServerboundPlayerInputPacket 同步到服务器）。
 * - Standard：每 tick 强制潜行键按下 → 等效 1.8.9
 * {@code movementInput.sneak = doneLoadingTerrain}（持续潜行）。
 * - Hold：与 Standard 行为一致（26.2 无 setPressed/物理键分离概念，
 * - 关闭时用 KeyBindUtil.updateKeyState 从 GLFW 物理状态恢复按键，
 * 避免关闭模块后遗留"虚拟按下"的潜行状态。
 */
public class Sneak extends Module {

    /** 潜行模式：Standard（持续潜行）/ Hold（强制按住潜行键） */
    private final ChoiceSetting mode;

    public Sneak() {
        super("sneak", "Sneak", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        mode = choiceSetting("模式", "Standard", List.of("Standard", "Hold"));
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (mc.player == null) {
            return;
        }
        // 每 tick 强制潜行键为按下（isDown → 本 tick 的 KeyboardInput.tick 写入 keyPresses.shift）
        setShiftDown(true);
    }

    @Override
    protected void onDisable() {
        if (mc.player != null) {
            // 从物理状态恢复潜行键（若用户正按住 Shift 则保持潜行）
            setShiftDown(false);
        }
    }

    /** 设置潜行键按下/恢复（KeyMapping.set 按 key 设置所有绑定该键的映射） */
    private void setShiftDown(boolean pressed) {
        if (pressed) {
            InputConstants.Key key = ((KeyMappingAccessor) mc.options.keyShift).adapt_getKey();
            KeyMapping.set(key, true);
        } else {
            KeyBindUtil.updateKeyState(mc.options.keyShift);
        }
    }
}
