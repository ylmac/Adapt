package com.ylmac.adapt.features.command;

import org.lwjgl.glfw.GLFW;

/**
 * 按键绑定工具（替代 myau 的 KeyBindUtil + 1.8.9 Keyboard/Mouse）。
 * <p>
 * 26.2 用 GLFW 键码：键盘 = GLFW_KEY_* 常量（正数），
 * 鼠标 = -100 - 按钮号（沿用 myau 的负数编码，与 setKey/getKey 约定一致）。
 */
public final class KeyBindUtil {

    private KeyBindUtil() {}

    /** 根据名字解析键位，返回 GLFW 键码或鼠标编码；无法识别返回 0 */
    public static int getKeyIndex(String keyInput) {
        String input = keyInput.toUpperCase();
        // 鼠标按钮（MOUSE0/MOUSE1/... 或别名）
        if (input.startsWith("MOUSE")) {
            try {
                int num = Integer.parseInt(input.substring(5));
                if (num >= 0) return mouseKey(num);
            } catch (NumberFormatException ignored) {}
        }
        switch (input) {
            case "LBUTTON": case "LMB": case "LEFTCLICK": return mouseKey(0);
            case "RBUTTON": case "RMB": case "RIGHTCLICK": return mouseKey(1);
            case "MBUTTON": case "MMB": case "MIDDLECLICK": case "SCROLLCLICK": return mouseKey(2);
            case "MOUSE3": case "XBUTTON1": case "SIDEBUTTON1": case "BOTTOMSIDE": return mouseKey(3);
            case "MOUSE4": case "XBUTTON2": case "SIDEBUTTON2": case "TOPSIDE": return mouseKey(4);
            case "MOUSE5": return mouseKey(5);
            case "MOUSE6": return mouseKey(6);
            case "MOUSE7": return mouseKey(7);
            default:
                // GLFW 键名（A-Z / F1-F12 / 数字 0-9 / 其它），找不到返回 0
                return glfwKeyByName(input);
        }
    }

    /** 鼠标按钮 → 负数编码（-100 - button） */
    private static int mouseKey(int button) {
        return -100 - button;
    }

    /** 键名 → GLFW 键码（键盘别名表） */
    private static int glfwKeyByName(String name) {
        if (name.length() == 1) {
            char c = name.charAt(0);
            // SDL scancode：A..Z 连续；数字是 1..9,0（非 0..9）
            if (c >= 'A' && c <= 'Z') return GLFW.GLFW_KEY_A + (c - 'A');
            if (c >= '1' && c <= '9') return GLFW.GLFW_KEY_1 + (c - '1');
            if (c == '0') return GLFW.GLFW_KEY_0;
        }
        switch (name) {
            case "ESCAPE": case "ESC": return GLFW.GLFW_KEY_ESCAPE;
            case "ENTER": case "RETURN": return GLFW.GLFW_KEY_ENTER;
            case "TAB": return GLFW.GLFW_KEY_TAB;
            case "BACKSPACE": return GLFW.GLFW_KEY_BACKSPACE;
            case "INSERT": return GLFW.GLFW_KEY_INSERT;
            case "DELETE": case "DEL": return GLFW.GLFW_KEY_DELETE;
            case "HOME": return GLFW.GLFW_KEY_HOME;
            case "END": return GLFW.GLFW_KEY_END;
            case "PAGEUP": return GLFW.GLFW_KEY_PAGE_UP;
            case "PAGEDOWN": return GLFW.GLFW_KEY_PAGE_DOWN;
            case "SPACE": return GLFW.GLFW_KEY_SPACE;
            case "UP": case "UPARROW": return GLFW.GLFW_KEY_UP;
            case "DOWN": case "DOWNARROW": return GLFW.GLFW_KEY_DOWN;
            case "LEFT": case "LEFTARROW": return GLFW.GLFW_KEY_LEFT;
            case "RIGHT": case "RIGHTARROW": return GLFW.GLFW_KEY_RIGHT;
            case "LSHIFT": return GLFW.GLFW_KEY_LEFT_SHIFT;
            case "RSHIFT": return GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "LCONTROL": case "LCTRL": case "CTRL": return GLFW.GLFW_KEY_LEFT_CONTROL;
            case "RCONTROL": case "RCTRL": return GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "LALT": case "LMENU": return GLFW.GLFW_KEY_LEFT_ALT;
            case "RALT": case "RMENU": return GLFW.GLFW_KEY_RIGHT_ALT;
            case "CAPSLOCK": return GLFW.GLFW_KEY_CAPS_LOCK;
            case "NUMLOCK": return GLFW.GLFW_KEY_NUM_LOCK;
            case "SCROLLLOCK": return GLFW.GLFW_KEY_SCROLL_LOCK;
            case "MINUS": return GLFW.GLFW_KEY_MINUS;
            case "EQUALS": return GLFW.GLFW_KEY_EQUAL;
            case "LBRACKET": return GLFW.GLFW_KEY_LEFT_BRACKET;
            case "RBRACKET": return GLFW.GLFW_KEY_RIGHT_BRACKET;
            case "BACKSLASH": return GLFW.GLFW_KEY_BACKSLASH;
            case "SEMICOLON": return GLFW.GLFW_KEY_SEMICOLON;
            case "APOSTROPHE": return GLFW.GLFW_KEY_APOSTROPHE;
            case "GRAVE": case "TILDE": return GLFW.GLFW_KEY_GRAVE_ACCENT;
            case "COMMA": return GLFW.GLFW_KEY_COMMA;
            case "PERIOD": return GLFW.GLFW_KEY_PERIOD;
            case "SLASH": return GLFW.GLFW_KEY_SLASH;
            case "KP0": return GLFW.GLFW_KEY_KP_0;
            case "KP1": return GLFW.GLFW_KEY_KP_1;
            case "KP2": return GLFW.GLFW_KEY_KP_2;
            case "KP3": return GLFW.GLFW_KEY_KP_3;
            case "KP4": return GLFW.GLFW_KEY_KP_4;
            case "KP5": return GLFW.GLFW_KEY_KP_5;
            case "KP6": return GLFW.GLFW_KEY_KP_6;
            case "KP7": return GLFW.GLFW_KEY_KP_7;
            case "KP8": return GLFW.GLFW_KEY_KP_8;
            case "KP9": return GLFW.GLFW_KEY_KP_9;
            case "KPADD": return GLFW.GLFW_KEY_KP_ADD;
            case "KPSUBTRACT": return GLFW.GLFW_KEY_KP_SUBTRACT;
            case "KPMULTIPLY": return GLFW.GLFW_KEY_KP_MULTIPLY;
            case "KPDIVIDE": return GLFW.GLFW_KEY_KP_DIVIDE;
            case "KPDECIMAL": return GLFW.GLFW_KEY_KP_DECIMAL;
            case "KPENTER": return GLFW.GLFW_KEY_KP_ENTER;
            default:
                // F1-F24（SDL：F1-F12 连续，F13-F24 另起一段，不能用 F1+(n-1)）
                if (name.startsWith("F") && name.length() > 1) {
                    try {
                        int n = Integer.parseInt(name.substring(1));
                        if (n >= 1 && n <= 24) {
                            return switch (n) {
                                case 1 -> GLFW.GLFW_KEY_F1; case 2 -> GLFW.GLFW_KEY_F2;
                                case 3 -> GLFW.GLFW_KEY_F3; case 4 -> GLFW.GLFW_KEY_F4;
                                case 5 -> GLFW.GLFW_KEY_F5; case 6 -> GLFW.GLFW_KEY_F6;
                                case 7 -> GLFW.GLFW_KEY_F7; case 8 -> GLFW.GLFW_KEY_F8;
                                case 9 -> GLFW.GLFW_KEY_F9; case 10 -> GLFW.GLFW_KEY_F10;
                                case 11 -> GLFW.GLFW_KEY_F11; case 12 -> GLFW.GLFW_KEY_F12;
                                case 13 -> GLFW.GLFW_KEY_F13; case 14 -> GLFW.GLFW_KEY_F14;
                                case 15 -> GLFW.GLFW_KEY_F15; case 16 -> GLFW.GLFW_KEY_F16;
                                case 17 -> GLFW.GLFW_KEY_F17; case 18 -> GLFW.GLFW_KEY_F18;
                                case 19 -> GLFW.GLFW_KEY_F19; case 20 -> GLFW.GLFW_KEY_F20;
                                case 21 -> GLFW.GLFW_KEY_F21; case 22 -> GLFW.GLFW_KEY_F22;
                                case 23 -> GLFW.GLFW_KEY_F23; default -> GLFW.GLFW_KEY_F24;
                            };
                        }
                    } catch (NumberFormatException ignored) {}
                }
                return 0;
        }
    }

    /** 键码 → 显示名（GLFW 键名或 MOUSE 按钮名） */
    public static String getKeyName(int key) {
        if (key == 0) return "None";
        if (key < 0) return "MOUSE" + (-key - 100);
        return GLFW.glfwGetKeyName(key, 0) == null ? String.valueOf(key) : GLFW.glfwGetKeyName(key, 0);
    }
}
