package com.ylmac.adapt.module.ghost;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import com.ylmac.adapt.input.GLFW;

/**
 * GuiClicker GUI 内自动点击。
 * <p>
 * 原理：GUI（Screen）打开时，若鼠标左/右键物理按下，
 * 在按住计数超过阈值后周期性调用 screen.mouseClicked(x, y, button)，
 * 模拟在背包/合成界面里持续点击（等价 1.8.9 GuiContainer.mouseClicked）。
 * <p>
 * 26.2 适配（javap 已确证）：
 * - 当前界面 mc.gui.screen
 * - Screen.mouseClicked(MouseButtonEvent, boolean) 新签名（旧 x,y,button 已移除），
 * MouseButtonEvent(x, y, MouseButtonInfo(button, modifiers))
 * - 坐标：鼠标缩放坐标 mc.mouseHandler.getScaledXPos/YPos(Window)
 * （MouseHandler.onButton 里同款换算，见其字节码）
 * - 物理按键状态：GLFW.glfwGetMouseButton(window.handle, button)
 * <p>
 * 按住 tick 数 > hold-ticks 且概率判定通过才点击。
 */
public class GuiClicker extends Module {

 /** 按住多少 tick 后开始点击 */
    private IntSetting holdTicks;
 /** 点击概率 */
    private IntSetting chance;

 /** 当前按住左键的 tick 计数 */
    private int mouseDownTicks;

    public GuiClicker() {
        super("guiclicker", "GuiClicker", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        holdTicks = intSetting("hold-ticks", 2, 1, 20, 1);
        chance = intSetting("chance", 90, 0, 100, 1);
    }

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        Screen screen = mc.gui.screen();
        if (screen == null) {
            // 界面关闭时重置计数
            this.mouseDownTicks = 0;
            return;
        }
        if (mc.player == null || mc.level == null) return;

        // 鼠标缩放坐标（等价 MouseHandler.onButton 的 getScaledXPos/YPos 换算）
        double mouseX = mc.mouseHandler.getScaledXPos(mc.getWindow());
        double mouseY = mc.mouseHandler.getScaledYPos(mc.getWindow());

        boolean leftDown = com.ylmac.adapt.input.GLFW.glfwGetMouseButton(
                mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightDown = com.ylmac.adapt.input.GLFW.glfwGetMouseButton(
                mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if (!leftDown && !rightDown) {
            this.mouseDownTicks = 0;
            return;
        }

 // 按住计数累计
        this.mouseDownTicks++;
        if (this.mouseDownTicks <= holdTicks.getValue()) return;
 // 概率判定
        if (Math.random() * 100.0 >= chance.getValue()) return;

        try {
            int button = leftDown ? GLFW.GLFW_MOUSE_BUTTON_LEFT : GLFW.GLFW_MOUSE_BUTTON_RIGHT;
            // 构造 26.2 的鼠标事件（坐标 + 按钮 + 无修饰键）
            MouseButtonEvent mouseButtonEvent = new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0));
 // 模拟点击
            screen.mouseClicked(mouseButtonEvent, false);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    @Override
    public String getInfo() {
        return chance.getValue() + "%";
    }
}
