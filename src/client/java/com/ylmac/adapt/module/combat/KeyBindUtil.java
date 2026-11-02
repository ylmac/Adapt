package com.ylmac.adapt.module.combat;

import com.ylmac.adapt.mixins.input.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * 按键工具。
 * <p>
 * 26.2 点击模拟方案（已通过 javap 反汇编确证）：
 * click 给 keyAttack 的 clickCount+，同 tick 的 Minecraft.handleKeybinds
 * while (keyAttack.consumeClick) startAttack 消费，触发 gameMode.attack + swing。
 * - setKeyBindState(按下/松开) → KeyMapping.set(key, boolean)（按键状态，非瞬时点击）。
 * - updateKeyState（从物理状态恢复）→ 直接读 GLFW（26.2 的 KeyMapping.isDown 只由
 * 输入事件更新，不会每 tick 从物理状态刷新，必须手动恢复）。
 */
public final class KeyBindUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private KeyBindUtil() {}

    /** 26.2 瞬时按压一次（等价 1.8.9 KeyBinding.onTick；key 字段 protected，经访问器获取） */
    public static void pressKeyOnce(KeyMapping keyMapping) {
        KeyMapping.click(((KeyMappingAccessor) keyMapping).adapt_getKey());
    }

    /** 设置按键按下/松开状态（等价 1.8.9 KeyBinding.setKeyBindState） */
    public static void setKeyBindState(KeyMapping keyMapping, boolean pressed) {
        KeyMapping.set(((KeyMappingAccessor) keyMapping).adapt_getKey(), pressed);
    }

    /** 从物理状态恢复按键按下状态（等价 1.8.9 KeyBinding.isKeyDown → updateKeyState） */
    public static void updateKeyState(KeyMapping keyMapping) {
        InputConstants.Key key = ((KeyMappingAccessor) keyMapping).adapt_getKey();
        boolean down = key.getType() == InputConstants.Type.MOUSE
                ? com.ylmac.adapt.input.GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue())
                        == com.ylmac.adapt.input.GLFW.GLFW_PRESS
                : com.ylmac.adapt.input.GLFW.glfwGetKey(mc.getWindow().handle(), key.getValue())
                        == com.ylmac.adapt.input.GLFW.GLFW_PRESS;
        KeyMapping.set(key, down);
    }
}
