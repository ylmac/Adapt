package com.ylmac.adapt.features.module.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** 键位编码工具：键盘键码原样使用，鼠标按钮编码为负数（MOUSE_OFFSET - button） */
public class KeybindUtils {

    public static final int NONE = -1;
    public static final int MOUSE_OFFSET = -2;

    private KeybindUtils() {
    }

    /** 判断编码后的绑定值是否表示鼠标按键 */
    public static boolean isMouseButton(int keyBind) {
        return keyBind <= MOUSE_OFFSET;
    }

    /** 将 GLFW 鼠标按键编号编码为键位值 */
    public static int encodeMouseButton(int button) {
        return MOUSE_OFFSET - button;
    }

    /** 从键位值解码 GLFW 鼠标按键编号 */
    public static int decodeMouseButton(int keyBind) {
        return MOUSE_OFFSET - keyBind;
    }

    /** 判断按键绑定当前是否按下 */
    public static boolean isPressed(int keyBind) {
        if (keyBind == NONE) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().handle();
        if (isMouseButton(keyBind)) {
            return GLFW.glfwGetMouseButton(handle, decodeMouseButton(keyBind)) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(keyBind);
    }

    /** 将键位值格式化为面向用户的名称 */
    public static String format(int keyBind) {
        if (keyBind == NONE) {
            return "None";
        }
        if (isMouseButton(keyBind)) {
            // 26.3：鼠标按钮已是 SDL 1-based（1=左），直接显示编号
            return "Mouse " + decodeMouseButton(keyBind);
        }
        return InputConstants.Type.KEYBOARD.getOrCreate(keyBind).getDisplayName().getString();
    }

}
