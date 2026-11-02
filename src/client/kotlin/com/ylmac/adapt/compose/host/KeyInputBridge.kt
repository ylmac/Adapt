package com.ylmac.adapt.compose.host

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.Component
import java.awt.event.KeyEvent as AwtKeyEvent
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent as McKeyEvent
import com.ylmac.adapt.input.GLFW

/**
 * MC 键盘 → Compose [sendKeyEvent]。
 *
 * Desktop TextField 的 [isTypedEvent] 认 AWT native：可打印字符必须带
 * [AwtKeyEvent.KEY_TYPED]；导航/编辑键走 KEY_PRESSED / KEY_RELEASED。
 */
@OptIn(InternalComposeUiApi::class)
object KeyInputBridge {
    private val dummy: Component = object : Component() {}

    fun keyPressed(session: AdaptComposeSession?, event: McKeyEvent): Boolean {
        val s = session ?: return false
        com.ylmac.adapt.render.profile.Probe.hit("in.key")
        val awtCode = scancodeToAwt(event.key())
        if (awtCode == AwtKeyEvent.VK_UNDEFINED) return false
        // 可打印字符走 SDL text input → charTyped（26.3 与 keyPressed 是两条回调）。
        // 这里消费的话会和 charTyped 各写一遍。字符进不来时去打开 SDL_StartTextInput。
        if (isTextKey(awtCode) && !event.hasControlDown() && !hasMeta(event)) {
            return false
        }
        // 退格/删除直接写 EditCommand（新 TextField 不走 KEY_TYPED）
        if (awtCode == AwtKeyEvent.VK_BACK_SPACE || awtCode == AwtKeyEvent.VK_DELETE) {
            if (s.textBackspace()) return true
        }
        val mods = awtModifiers(event)
        val awt = AwtKeyEvent(
            dummy,
            AwtKeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            mods,
            awtCode,
            AwtKeyEvent.CHAR_UNDEFINED,
        )
        return s.dispatchKeyEvent(
            KeyEvent(
                key = Key(awtCode),
                type = KeyEventType.KeyDown,
                isCtrlPressed = event.hasControlDown(),
                isAltPressed = event.hasAltDown(),
                isShiftPressed = event.hasShiftDown(),
                isMetaPressed = hasMeta(event),
                nativeEvent = awt,
            ),
        )
    }

    fun keyReleased(session: AdaptComposeSession?, event: McKeyEvent): Boolean {
        val s = session ?: return false
        val awtCode = scancodeToAwt(event.key())
        if (awtCode == AwtKeyEvent.VK_UNDEFINED) return false
        val mods = awtModifiers(event)
        val awt = AwtKeyEvent(
            dummy,
            AwtKeyEvent.KEY_RELEASED,
            System.currentTimeMillis(),
            mods,
            awtCode,
            AwtKeyEvent.CHAR_UNDEFINED,
        )
        return s.dispatchKeyEvent(
            KeyEvent(
                key = Key(awtCode),
                type = KeyEventType.KeyUp,
                isCtrlPressed = event.hasControlDown(),
                isAltPressed = event.hasAltDown(),
                isShiftPressed = event.hasShiftDown(),
                isMetaPressed = hasMeta(event),
                nativeEvent = awt,
            ),
        )
    }

    fun charTyped(session: AdaptComposeSession?, event: CharacterEvent): Boolean {
        val s = session ?: return false
        com.ylmac.adapt.render.profile.Probe.hit("in.char")
        val cp = event.codepoint()
        if (cp < 32 || cp == 0x7F || cp > 0xFFFF) {
            com.ylmac.adapt.render.profile.Probe.hit("in.charReject")
            return false
        }
        // 优先走 TextInputService / startInputMethod（Compose 1.13 TextField 主路径）
        if (s.commitTextCodePoint(cp)) {
            com.ylmac.adapt.render.profile.Probe.hit("in.commitOk")
            return true
        }
        com.ylmac.adapt.render.profile.Probe.hit("in.commitMiss")
        val ch = cp.toChar()
        val awt = AwtKeyEvent(
            dummy,
            AwtKeyEvent.KEY_TYPED,
            System.currentTimeMillis(),
            0,
            AwtKeyEvent.VK_UNDEFINED,
            ch,
        )
        // 回退：AWT KEY_TYPED + isTypedEvent（旧 CoreTextField）
        return s.dispatchKeyEvent(
            KeyEvent(
                key = Key(AwtKeyEvent.VK_UNDEFINED),
                type = KeyEventType.Unknown,
                codePoint = cp,
                nativeEvent = awt,
            ),
        )
    }

    private fun isTextKey(awtCode: Int): Boolean = when (awtCode) {
        AwtKeyEvent.VK_SPACE,
        AwtKeyEvent.VK_QUOTE,
        AwtKeyEvent.VK_COMMA,
        AwtKeyEvent.VK_MINUS,
        AwtKeyEvent.VK_PERIOD,
        AwtKeyEvent.VK_SLASH,
        AwtKeyEvent.VK_SEMICOLON,
        AwtKeyEvent.VK_EQUALS,
        AwtKeyEvent.VK_OPEN_BRACKET,
        AwtKeyEvent.VK_BACK_SLASH,
        AwtKeyEvent.VK_CLOSE_BRACKET,
        AwtKeyEvent.VK_BACK_QUOTE,
        -> true
        else -> awtCode in AwtKeyEvent.VK_0..AwtKeyEvent.VK_9 ||
            awtCode in AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z ||
            awtCode in AwtKeyEvent.VK_NUMPAD0..AwtKeyEvent.VK_NUMPAD9
    }

    private fun hasMeta(event: McKeyEvent): Boolean {
        return try {
            val m = event.javaClass.methods.firstOrNull {
                it.name == "hasMetaDown" || it.name == "hasSuperDown"
            }
            m != null && m.invoke(event) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun awtModifiers(event: McKeyEvent): Int {
        var m = 0
        if (event.hasShiftDown()) m = m or AwtKeyEvent.SHIFT_DOWN_MASK
        if (event.hasControlDown()) m = m or AwtKeyEvent.CTRL_DOWN_MASK
        if (event.hasAltDown()) m = m or AwtKeyEvent.ALT_DOWN_MASK
        if (hasMeta(event)) m = m or AwtKeyEvent.META_DOWN_MASK
        return m
    }

    /** InputConstants / 兼容层 scancode → AWT VK（非经典 GLFW 码）。 */
    private fun scancodeToAwt(code: Int): Int = when (code) {
        GLFW.GLFW_KEY_BACKSPACE -> AwtKeyEvent.VK_BACK_SPACE
        GLFW.GLFW_KEY_TAB -> AwtKeyEvent.VK_TAB
        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> AwtKeyEvent.VK_ENTER
        GLFW.GLFW_KEY_ESCAPE -> AwtKeyEvent.VK_ESCAPE
        GLFW.GLFW_KEY_INSERT -> AwtKeyEvent.VK_INSERT
        GLFW.GLFW_KEY_DELETE -> AwtKeyEvent.VK_DELETE
        GLFW.GLFW_KEY_HOME -> AwtKeyEvent.VK_HOME
        GLFW.GLFW_KEY_END -> AwtKeyEvent.VK_END
        GLFW.GLFW_KEY_PAGE_UP -> AwtKeyEvent.VK_PAGE_UP
        GLFW.GLFW_KEY_PAGE_DOWN -> AwtKeyEvent.VK_PAGE_DOWN
        GLFW.GLFW_KEY_LEFT -> AwtKeyEvent.VK_LEFT
        GLFW.GLFW_KEY_RIGHT -> AwtKeyEvent.VK_RIGHT
        GLFW.GLFW_KEY_UP -> AwtKeyEvent.VK_UP
        GLFW.GLFW_KEY_DOWN -> AwtKeyEvent.VK_DOWN
        GLFW.GLFW_KEY_CAPS_LOCK -> AwtKeyEvent.VK_CAPS_LOCK
        GLFW.GLFW_KEY_SCROLL_LOCK -> AwtKeyEvent.VK_SCROLL_LOCK
        GLFW.GLFW_KEY_NUM_LOCK -> AwtKeyEvent.VK_NUM_LOCK
        GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> AwtKeyEvent.VK_SHIFT
        GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> AwtKeyEvent.VK_CONTROL
        GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> AwtKeyEvent.VK_ALT
        GLFW.GLFW_KEY_SPACE -> AwtKeyEvent.VK_SPACE
        GLFW.GLFW_KEY_APOSTROPHE -> AwtKeyEvent.VK_QUOTE
        GLFW.GLFW_KEY_COMMA -> AwtKeyEvent.VK_COMMA
        GLFW.GLFW_KEY_MINUS -> AwtKeyEvent.VK_MINUS
        GLFW.GLFW_KEY_PERIOD -> AwtKeyEvent.VK_PERIOD
        GLFW.GLFW_KEY_SLASH -> AwtKeyEvent.VK_SLASH
        GLFW.GLFW_KEY_SEMICOLON -> AwtKeyEvent.VK_SEMICOLON
        GLFW.GLFW_KEY_EQUAL -> AwtKeyEvent.VK_EQUALS
        GLFW.GLFW_KEY_LEFT_BRACKET -> AwtKeyEvent.VK_OPEN_BRACKET
        GLFW.GLFW_KEY_BACKSLASH -> AwtKeyEvent.VK_BACK_SLASH
        GLFW.GLFW_KEY_RIGHT_BRACKET -> AwtKeyEvent.VK_CLOSE_BRACKET
        GLFW.GLFW_KEY_GRAVE_ACCENT -> AwtKeyEvent.VK_BACK_QUOTE
        GLFW.GLFW_KEY_0 -> AwtKeyEvent.VK_0
        GLFW.GLFW_KEY_1 -> AwtKeyEvent.VK_1
        GLFW.GLFW_KEY_2 -> AwtKeyEvent.VK_2
        GLFW.GLFW_KEY_3 -> AwtKeyEvent.VK_3
        GLFW.GLFW_KEY_4 -> AwtKeyEvent.VK_4
        GLFW.GLFW_KEY_5 -> AwtKeyEvent.VK_5
        GLFW.GLFW_KEY_6 -> AwtKeyEvent.VK_6
        GLFW.GLFW_KEY_7 -> AwtKeyEvent.VK_7
        GLFW.GLFW_KEY_8 -> AwtKeyEvent.VK_8
        GLFW.GLFW_KEY_9 -> AwtKeyEvent.VK_9
        GLFW.GLFW_KEY_A -> AwtKeyEvent.VK_A
        GLFW.GLFW_KEY_B -> AwtKeyEvent.VK_B
        GLFW.GLFW_KEY_C -> AwtKeyEvent.VK_C
        GLFW.GLFW_KEY_D -> AwtKeyEvent.VK_D
        GLFW.GLFW_KEY_E -> AwtKeyEvent.VK_E
        GLFW.GLFW_KEY_F -> AwtKeyEvent.VK_F
        GLFW.GLFW_KEY_G -> AwtKeyEvent.VK_G
        GLFW.GLFW_KEY_H -> AwtKeyEvent.VK_H
        GLFW.GLFW_KEY_I -> AwtKeyEvent.VK_I
        GLFW.GLFW_KEY_J -> AwtKeyEvent.VK_J
        GLFW.GLFW_KEY_K -> AwtKeyEvent.VK_K
        GLFW.GLFW_KEY_L -> AwtKeyEvent.VK_L
        GLFW.GLFW_KEY_M -> AwtKeyEvent.VK_M
        GLFW.GLFW_KEY_N -> AwtKeyEvent.VK_N
        GLFW.GLFW_KEY_O -> AwtKeyEvent.VK_O
        GLFW.GLFW_KEY_P -> AwtKeyEvent.VK_P
        GLFW.GLFW_KEY_Q -> AwtKeyEvent.VK_Q
        GLFW.GLFW_KEY_R -> AwtKeyEvent.VK_R
        GLFW.GLFW_KEY_S -> AwtKeyEvent.VK_S
        GLFW.GLFW_KEY_T -> AwtKeyEvent.VK_T
        GLFW.GLFW_KEY_U -> AwtKeyEvent.VK_U
        GLFW.GLFW_KEY_V -> AwtKeyEvent.VK_V
        GLFW.GLFW_KEY_W -> AwtKeyEvent.VK_W
        GLFW.GLFW_KEY_X -> AwtKeyEvent.VK_X
        GLFW.GLFW_KEY_Y -> AwtKeyEvent.VK_Y
        GLFW.GLFW_KEY_Z -> AwtKeyEvent.VK_Z
        GLFW.GLFW_KEY_F1 -> AwtKeyEvent.VK_F1
        GLFW.GLFW_KEY_F2 -> AwtKeyEvent.VK_F2
        GLFW.GLFW_KEY_F3 -> AwtKeyEvent.VK_F3
        GLFW.GLFW_KEY_F4 -> AwtKeyEvent.VK_F4
        GLFW.GLFW_KEY_F5 -> AwtKeyEvent.VK_F5
        GLFW.GLFW_KEY_F6 -> AwtKeyEvent.VK_F6
        GLFW.GLFW_KEY_F7 -> AwtKeyEvent.VK_F7
        GLFW.GLFW_KEY_F8 -> AwtKeyEvent.VK_F8
        GLFW.GLFW_KEY_F9 -> AwtKeyEvent.VK_F9
        GLFW.GLFW_KEY_F10 -> AwtKeyEvent.VK_F10
        GLFW.GLFW_KEY_F11 -> AwtKeyEvent.VK_F11
        GLFW.GLFW_KEY_F12 -> AwtKeyEvent.VK_F12
        GLFW.GLFW_KEY_KP_0 -> AwtKeyEvent.VK_NUMPAD0
        GLFW.GLFW_KEY_KP_1 -> AwtKeyEvent.VK_NUMPAD1
        GLFW.GLFW_KEY_KP_2 -> AwtKeyEvent.VK_NUMPAD2
        GLFW.GLFW_KEY_KP_3 -> AwtKeyEvent.VK_NUMPAD3
        GLFW.GLFW_KEY_KP_4 -> AwtKeyEvent.VK_NUMPAD4
        GLFW.GLFW_KEY_KP_5 -> AwtKeyEvent.VK_NUMPAD5
        GLFW.GLFW_KEY_KP_6 -> AwtKeyEvent.VK_NUMPAD6
        GLFW.GLFW_KEY_KP_7 -> AwtKeyEvent.VK_NUMPAD7
        GLFW.GLFW_KEY_KP_8 -> AwtKeyEvent.VK_NUMPAD8
        GLFW.GLFW_KEY_KP_9 -> AwtKeyEvent.VK_NUMPAD9
        GLFW.GLFW_KEY_KP_DECIMAL -> AwtKeyEvent.VK_DECIMAL
        GLFW.GLFW_KEY_KP_DIVIDE -> AwtKeyEvent.VK_DIVIDE
        GLFW.GLFW_KEY_KP_MULTIPLY -> AwtKeyEvent.VK_MULTIPLY
        GLFW.GLFW_KEY_KP_SUBTRACT -> AwtKeyEvent.VK_SUBTRACT
        GLFW.GLFW_KEY_KP_ADD -> AwtKeyEvent.VK_ADD
        else -> AwtKeyEvent.VK_UNDEFINED
    }
}
