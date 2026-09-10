package com.ylmac.adapt.features.music;

import io.github.timer_err.qml4j.render.QmlView;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

public final class KeyInputManager {

    private final QmlView qmlView;

    public KeyInputManager(QmlView qmlView) {
        this.qmlView = qmlView;
    }

    public boolean handleKeyPressed(KeyEvent e) {
        int glfwKey = e.key();
        boolean shift = e.hasShiftDown();
        boolean ctrl = e.hasControlDown();
        boolean alt = e.hasAltDown();

        if (ctrl) {
            if (glfwKey == GLFW.GLFW_KEY_C) {
                return qmlView.copy();
            }
            if (glfwKey == GLFW.GLFW_KEY_V) {
                return qmlView.paste();
            }
            if (glfwKey == GLFW.GLFW_KEY_X) {
                return qmlView.cut();
            }
        }

        int qmlKey = mapKey(glfwKey);
        if (qmlKey != 0) {
            if (alt && glfwKey == GLFW.GLFW_KEY_TAB) {
                return false;
            }
            boolean modifiers;
            if (glfwKey == GLFW.GLFW_KEY_TAB && shift) {
                qmlKey = QmlView.KEY_BACKTAB;
                modifiers = ctrl || alt;
            } else {
                modifiers = shift || ctrl || alt;
            }
            boolean handled = qmlView.dispatchKey(qmlKey, null, true, modifiers);
            return handled;
        }

        return false;
    }

    public boolean handleKeyReleased(KeyEvent e) {
        int glfwKey = e.key();
        boolean shift = e.hasShiftDown();
        boolean ctrl = e.hasControlDown();
        boolean alt = e.hasAltDown();

        int qmlKey = mapKey(glfwKey);
        if (qmlKey != 0) {
            if (alt && glfwKey == GLFW.GLFW_KEY_TAB) {
                return false;
            }
            boolean modifiers;
            if (glfwKey == GLFW.GLFW_KEY_TAB && shift) {
                qmlKey = QmlView.KEY_BACKTAB;
                modifiers = ctrl || alt;
            } else {
                modifiers = shift || ctrl || alt;
            }
            return qmlView.dispatchKey(qmlKey, null, false, modifiers);
        }
        return false;
    }

    public boolean handleCharTyped(CharacterEvent e) {
        int codepoint = e.codepoint();

        if (codepoint < 32) {
            return false;
        }

        String text = new String(Character.toChars(codepoint));
        return qmlView.dispatchKey(codepoint, text, true, false);
    }

    static int mapKey(int glfwKey) {
        switch (glfwKey) {
            case GLFW.GLFW_KEY_BACKSPACE:  return QmlView.KEY_BACKSPACE;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:   return QmlView.KEY_ENTER;
            case GLFW.GLFW_KEY_LEFT:       return QmlView.KEY_LEFT;
            case GLFW.GLFW_KEY_RIGHT:      return QmlView.KEY_RIGHT;
            case GLFW.GLFW_KEY_UP:         return QmlView.KEY_UP;
            case GLFW.GLFW_KEY_DOWN:       return QmlView.KEY_DOWN;
            case GLFW.GLFW_KEY_HOME:       return QmlView.KEY_HOME;
            case GLFW.GLFW_KEY_END:        return QmlView.KEY_END;
            case GLFW.GLFW_KEY_TAB:        return QmlView.KEY_TAB;
            case GLFW.GLFW_KEY_ESCAPE:     return QmlView.KEY_ESCAPE;
            default:
                return 0;
        }
    }
}