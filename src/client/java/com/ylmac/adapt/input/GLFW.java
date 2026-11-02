package com.ylmac.adapt.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

/**
 * 26.3 窗口走 SDL。这里保留原来的 GLFW 常量名和查询方法，键码对齐 {@link InputConstants}。
 */
public final class GLFW {

    private GLFW() {}

    public static final int GLFW_PRESS = InputConstants.PRESS;
    public static final int GLFW_RELEASE = InputConstants.RELEASE;

    public static final int GLFW_MOUSE_BUTTON_LEFT = InputConstants.MOUSE_BUTTON_LEFT;
    public static final int GLFW_MOUSE_BUTTON_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
    public static final int GLFW_MOUSE_BUTTON_MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE;

    public static final int GLFW_KEY_UNKNOWN = -1;

    public static final int GLFW_KEY_SPACE = InputConstants.KEY_SPACE;
    public static final int GLFW_KEY_APOSTROPHE = InputConstants.KEY_APOSTROPHE;
    public static final int GLFW_KEY_COMMA = InputConstants.KEY_COMMA;
    public static final int GLFW_KEY_MINUS = InputConstants.KEY_MINUS;
    public static final int GLFW_KEY_PERIOD = InputConstants.KEY_PERIOD;
    public static final int GLFW_KEY_SLASH = InputConstants.KEY_SLASH;
    public static final int GLFW_KEY_0 = InputConstants.KEY_0;
    public static final int GLFW_KEY_1 = InputConstants.KEY_1;
    public static final int GLFW_KEY_2 = InputConstants.KEY_2;
    public static final int GLFW_KEY_3 = InputConstants.KEY_3;
    public static final int GLFW_KEY_4 = InputConstants.KEY_4;
    public static final int GLFW_KEY_5 = InputConstants.KEY_5;
    public static final int GLFW_KEY_6 = InputConstants.KEY_6;
    public static final int GLFW_KEY_7 = InputConstants.KEY_7;
    public static final int GLFW_KEY_8 = InputConstants.KEY_8;
    public static final int GLFW_KEY_9 = InputConstants.KEY_9;
    public static final int GLFW_KEY_SEMICOLON = InputConstants.KEY_SEMICOLON;
    public static final int GLFW_KEY_EQUAL = InputConstants.KEY_EQUALS;
    public static final int GLFW_KEY_A = InputConstants.KEY_A;
    public static final int GLFW_KEY_B = InputConstants.KEY_B;
    public static final int GLFW_KEY_C = InputConstants.KEY_C;
    public static final int GLFW_KEY_D = InputConstants.KEY_D;
    public static final int GLFW_KEY_E = InputConstants.KEY_E;
    public static final int GLFW_KEY_F = InputConstants.KEY_F;
    public static final int GLFW_KEY_G = InputConstants.KEY_G;
    public static final int GLFW_KEY_H = InputConstants.KEY_H;
    public static final int GLFW_KEY_I = InputConstants.KEY_I;
    public static final int GLFW_KEY_J = InputConstants.KEY_J;
    public static final int GLFW_KEY_K = InputConstants.KEY_K;
    public static final int GLFW_KEY_L = InputConstants.KEY_L;
    public static final int GLFW_KEY_M = InputConstants.KEY_M;
    public static final int GLFW_KEY_N = InputConstants.KEY_N;
    public static final int GLFW_KEY_O = InputConstants.KEY_O;
    public static final int GLFW_KEY_P = InputConstants.KEY_P;
    public static final int GLFW_KEY_Q = InputConstants.KEY_Q;
    public static final int GLFW_KEY_R = InputConstants.KEY_R;
    public static final int GLFW_KEY_S = InputConstants.KEY_S;
    public static final int GLFW_KEY_T = InputConstants.KEY_T;
    public static final int GLFW_KEY_U = InputConstants.KEY_U;
    public static final int GLFW_KEY_V = InputConstants.KEY_V;
    public static final int GLFW_KEY_W = InputConstants.KEY_W;
    public static final int GLFW_KEY_X = InputConstants.KEY_X;
    public static final int GLFW_KEY_Y = InputConstants.KEY_Y;
    public static final int GLFW_KEY_Z = InputConstants.KEY_Z;
    public static final int GLFW_KEY_LEFT_BRACKET = InputConstants.KEY_LBRACKET;
    public static final int GLFW_KEY_BACKSLASH = InputConstants.KEY_BACKSLASH;
    public static final int GLFW_KEY_RIGHT_BRACKET = InputConstants.KEY_RBRACKET;
    public static final int GLFW_KEY_GRAVE_ACCENT = InputConstants.KEY_GRAVE;
    public static final int GLFW_KEY_ESCAPE = InputConstants.KEY_ESCAPE;
    public static final int GLFW_KEY_ENTER = InputConstants.KEY_RETURN;
    public static final int GLFW_KEY_TAB = InputConstants.KEY_TAB;
    public static final int GLFW_KEY_BACKSPACE = InputConstants.KEY_BACKSPACE;
    public static final int GLFW_KEY_INSERT = InputConstants.KEY_INSERT;
    public static final int GLFW_KEY_DELETE = InputConstants.KEY_DELETE;
    public static final int GLFW_KEY_RIGHT = InputConstants.KEY_RIGHT;
    public static final int GLFW_KEY_LEFT = InputConstants.KEY_LEFT;
    public static final int GLFW_KEY_DOWN = InputConstants.KEY_DOWN;
    public static final int GLFW_KEY_UP = InputConstants.KEY_UP;
    public static final int GLFW_KEY_PAGE_UP = InputConstants.KEY_PAGEUP;
    public static final int GLFW_KEY_PAGE_DOWN = InputConstants.KEY_PAGEDOWN;
    public static final int GLFW_KEY_HOME = InputConstants.KEY_HOME;
    public static final int GLFW_KEY_END = InputConstants.KEY_END;
    public static final int GLFW_KEY_CAPS_LOCK = InputConstants.KEY_CAPSLOCK;
    public static final int GLFW_KEY_SCROLL_LOCK = InputConstants.KEY_SCROLLLOCK;
    public static final int GLFW_KEY_NUM_LOCK = InputConstants.KEY_NUMLOCK;
    public static final int GLFW_KEY_F1 = InputConstants.KEY_F1;
    public static final int GLFW_KEY_F2 = InputConstants.KEY_F2;
    public static final int GLFW_KEY_F3 = InputConstants.KEY_F3;
    public static final int GLFW_KEY_F4 = InputConstants.KEY_F4;
    public static final int GLFW_KEY_F5 = InputConstants.KEY_F5;
    public static final int GLFW_KEY_F6 = InputConstants.KEY_F6;
    public static final int GLFW_KEY_F7 = InputConstants.KEY_F7;
    public static final int GLFW_KEY_F8 = InputConstants.KEY_F8;
    public static final int GLFW_KEY_F9 = InputConstants.KEY_F9;
    public static final int GLFW_KEY_F10 = InputConstants.KEY_F10;
    public static final int GLFW_KEY_F11 = InputConstants.KEY_F11;
    public static final int GLFW_KEY_F12 = InputConstants.KEY_F12;
    public static final int GLFW_KEY_F13 = InputConstants.KEY_F13;
    public static final int GLFW_KEY_F14 = InputConstants.KEY_F14;
    public static final int GLFW_KEY_F15 = InputConstants.KEY_F15;
    public static final int GLFW_KEY_F16 = InputConstants.KEY_F16;
    public static final int GLFW_KEY_F17 = InputConstants.KEY_F17;
    public static final int GLFW_KEY_F18 = InputConstants.KEY_F18;
    public static final int GLFW_KEY_F19 = InputConstants.KEY_F19;
    public static final int GLFW_KEY_F20 = InputConstants.KEY_F20;
    public static final int GLFW_KEY_F21 = InputConstants.KEY_F21;
    public static final int GLFW_KEY_F22 = InputConstants.KEY_F22;
    public static final int GLFW_KEY_F23 = InputConstants.KEY_F23;
    public static final int GLFW_KEY_F24 = InputConstants.KEY_F24;
    public static final int GLFW_KEY_LEFT_SHIFT = InputConstants.KEY_LSHIFT;
    public static final int GLFW_KEY_LEFT_CONTROL = InputConstants.KEY_LCONTROL;
    public static final int GLFW_KEY_LEFT_ALT = InputConstants.KEY_LALT;
    public static final int GLFW_KEY_RIGHT_SHIFT = InputConstants.KEY_RSHIFT;
    public static final int GLFW_KEY_RIGHT_CONTROL = InputConstants.KEY_RCONTROL;
    public static final int GLFW_KEY_RIGHT_ALT = InputConstants.KEY_RALT;

    public static final int GLFW_KEY_KP_0 = InputConstants.KEY_NUMPAD0;
    public static final int GLFW_KEY_KP_1 = InputConstants.KEY_NUMPAD1;
    public static final int GLFW_KEY_KP_2 = InputConstants.KEY_NUMPAD2;
    public static final int GLFW_KEY_KP_3 = InputConstants.KEY_NUMPAD3;
    public static final int GLFW_KEY_KP_4 = InputConstants.KEY_NUMPAD4;
    public static final int GLFW_KEY_KP_5 = InputConstants.KEY_NUMPAD5;
    public static final int GLFW_KEY_KP_6 = InputConstants.KEY_NUMPAD6;
    public static final int GLFW_KEY_KP_7 = InputConstants.KEY_NUMPAD7;
    public static final int GLFW_KEY_KP_8 = InputConstants.KEY_NUMPAD8;
    public static final int GLFW_KEY_KP_9 = InputConstants.KEY_NUMPAD9;
    public static final int GLFW_KEY_KP_DECIMAL = 99;   // SDL_SCANCODE_KP_PERIOD
    public static final int GLFW_KEY_KP_DIVIDE = 84;    // SDL_SCANCODE_KP_DIVIDE
    public static final int GLFW_KEY_KP_MULTIPLY = InputConstants.KEY_MULTIPLY;
    public static final int GLFW_KEY_KP_SUBTRACT = 86;  // SDL_SCANCODE_KP_MINUS
    public static final int GLFW_KEY_KP_ADD = InputConstants.KEY_ADD;
    public static final int GLFW_KEY_KP_ENTER = InputConstants.KEY_NUMPADENTER;

    public static int glfwGetMouseButton(long window, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.mouseHandler == null) return GLFW_RELEASE;
        MouseHandler mouse = mc.mouseHandler;
        if (button == GLFW_MOUSE_BUTTON_LEFT) return mouse.isLeftPressed() ? GLFW_PRESS : GLFW_RELEASE;
        if (button == GLFW_MOUSE_BUTTON_RIGHT) return mouse.isRightPressed() ? GLFW_PRESS : GLFW_RELEASE;
        if (button == GLFW_MOUSE_BUTTON_MIDDLE) return mouse.isMiddlePressed() ? GLFW_PRESS : GLFW_RELEASE;
        return GLFW_RELEASE;
    }

    public static int glfwGetKey(long window, int key) {
        if (key == GLFW_KEY_UNKNOWN) return GLFW_RELEASE;
        return InputConstants.isKeyDown(key) ? GLFW_PRESS : GLFW_RELEASE;
    }

    public static String glfwGetKeyName(int key, int scancode) {
        try {
            InputConstants.Key k = InputConstants.Type.KEYBOARD.getOrCreate(key);
            String name = k.getDisplayName().getString();
            return name == null || name.isEmpty() ? null : name;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void glfwSetClipboardString(long window, CharSequence text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.keyboardHandler != null) {
            mc.keyboardHandler.setClipboard(text == null ? "" : text.toString());
        }
    }

    public static String glfwGetClipboardString(long window) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.keyboardHandler != null) {
            return mc.keyboardHandler.getClipboard();
        }
        return null;
    }

    /** 26.3 无 GLFW cursor；CustomCursor 走 SDL 时再接。此处 no-op 保编译。 */
    public static long glfwCreateCursor(GLFWImage image, int xhot, int yhot) {
        return 0L;
    }

    public static void glfwDestroyCursor(long cursor) {}

    public static void glfwSetCursor(long window, long cursor) {}
}
