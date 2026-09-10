package com.ylmac.adapt.features.module.impl.ghost;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/**
 * GuiClicker GUI 内自动点击（移植自 Rise GuiClicker）。
 * <p>
 * 原理（Rise 原版）：GUI（Screen）打开时，若鼠标左/右键物理按下，
 * 在按住计数超过阈值后周期性调用 screen.mouseClicked(x, y, button)，
 * 模拟在背包/合成界面里持续点击（等价 1.8.9 GuiContainer.mouseClicked）。
 * <p>
 * 26.2 适配（javap 已确证）：
 * - 当前界面 mc.gui.screen()（非 null = GUI 打开，Rise currentScreen）
 * - Screen.mouseClicked(MouseButtonEvent, boolean) 新签名（旧 x,y,button 已移除），
 *   MouseButtonEvent(x, y, MouseButtonInfo(button, modifiers))
 * - 坐标：鼠标缩放坐标 mc.mouseHandler.getScaledXPos/YPos(Window)
 *   （MouseHandler.onButton 里同款换算，见其字节码）
 * - 物理按键状态：GLFW.glfwGetMouseButton(window.handle(), button)
 *   （Rise Mouse.isButtonDown(0/1)，GUI 打开时鼠标仍捕获）
 * <p>
 * 触发节奏：tick 级（ClientTickEvent），与 Rise PreMotion 事件驱动一致；
 * 按住 tick 数 > hold-ticks 且概率判定通过才点击（Rise: >2 ticks && random > 0.1）。
 */
public class GuiClicker extends Module {

    /** 按住多少 tick 后开始点击（Rise 2） */
    private IntSetting holdTicks;
    /** 点击概率（Rise 0.1 跳过概率 → 90% 生效，这里直接给成功率） */
    private IntSetting chance;

    /** 当前按住左键的 tick 计数（Rise mouseDownTicks） */
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

        boolean leftDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if (!leftDown && !rightDown) {
            this.mouseDownTicks = 0;
            return;
        }

        // 按住计数累计（Rise mouseDownTicks++）
        this.mouseDownTicks++;
        if (this.mouseDownTicks <= holdTicks.getValue()) return;
        // 概率判定（Rise Math.random() > 0.1）
        if (Math.random() * 100.0 >= chance.getValue()) return;

        try {
            int button = leftDown ? GLFW.GLFW_MOUSE_BUTTON_LEFT : GLFW.GLFW_MOUSE_BUTTON_RIGHT;
            // 构造 26.2 的鼠标事件（坐标 + 按钮 + 无修饰键）
            MouseButtonEvent mouseButtonEvent = new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0));
            // 模拟点击（Rise GuiContainer.mouseClicked；第二参 false = 非双击）
            screen.mouseClicked(mouseButtonEvent, false);
        } catch (Exception exception) {
            // Rise 原版 try-catch，防止界面实现抛异常导致崩溃
            exception.printStackTrace();
        }
    }

    @Override
    public String getInfo() {
        return chance.getValue() + "%";
    }
}
