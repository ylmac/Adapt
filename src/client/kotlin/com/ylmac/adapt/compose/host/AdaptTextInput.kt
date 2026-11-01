package com.ylmac.adapt.compose.host

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.awaitCancellation
import net.minecraft.client.Minecraft

/**
 * 无 AWT 组件时的文本输入桥。
 *
 * Material TextField 在无桌面 IME 时经常不调 [startInput]；因此额外提供
 * [bindField]：Md3TextField 获焦时直接挂接 append/backspace，绕过 IME 会话。
 */
class AdaptTextInput : PlatformTextInputService {
    @Volatile
    private var legacyEdit: ((List<EditCommand>) -> Unit)? = null

    @Volatile
    private var legacyIme: ((ImeAction) -> Unit)? = null

    @Volatile
    private var methodRequest: PlatformTextInputMethodRequest? = null

    /** Compose 文本框获焦时注册的直接写入钩子（优先于 IME）。 */
    @Volatile
    private var fieldAppend: ((String) -> Unit)? = null

    @Volatile
    private var fieldBackspace: (() -> Unit)? = null

    /** SDL_StartTextInput 是否由本实例打开。26.3 不打开就永远没有 charTyped。 */
    @Volatile
    private var sdlTextOn = false

    override fun startInput(
        value: TextFieldValue,
        imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit,
        onImeActionPerformed: (ImeAction) -> Unit,
    ) {
        legacyEdit = onEditCommand
        legacyIme = onImeActionPerformed
    }

    override fun stopInput() {
        legacyEdit = null
        legacyIme = null
        methodRequest = null
        fieldAppend = null
        fieldBackspace = null
        setSdlTextInput(false)
    }

    override fun showSoftwareKeyboard() {}
    override fun hideSoftwareKeyboard() {}
    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {}
    override fun notifyFocusedRect(rect: Rect) {}

    suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        methodRequest = request
        try {
            awaitCancellation()
        } finally {
            if (methodRequest === request) methodRequest = null
        }
    }

    fun bindField(append: (String) -> Unit, backspace: () -> Unit) {
        fieldAppend = append
        fieldBackspace = backspace
        // 聊天框靠 EditBox.setFocused → SDL_StartTextInput。Compose 没有 EditBox，
        // 不主动打开的话只有 keyPressed，charTyped / IME 都不会来。
        setSdlTextInput(true)
    }

    fun unbindField() {
        fieldAppend = null
        fieldBackspace = null
        setSdlTextInput(false)
    }

    fun commitCodePoint(codePoint: Int): Boolean {
        if (codePoint < 32 || codePoint == 0x7F) return false
        val text = String(Character.toChars(codePoint))
        val direct = fieldAppend
        if (direct != null) {
            try {
                direct(text)
                return true
            } catch (_: Throwable) {
            }
        }
        return apply(listOf(CommitTextCommand(text, 1)))
    }

    fun backspace(): Boolean {
        val direct = fieldBackspace
        if (direct != null) {
            try {
                direct()
                return true
            } catch (_: Throwable) {
            }
        }
        return apply(listOf(BackspaceCommand()))
    }

    fun hasSession(): Boolean =
        fieldAppend != null || methodRequest != null || legacyEdit != null

    private fun setSdlTextInput(on: Boolean) {
        if (sdlTextOn == on) return
        try {
            val mc = Minecraft.getInstance()
            val mgr = mc.textInputManager()
            if (on) {
                val w = mc.window.guiScaledWidth.coerceAtLeast(1)
                val h = mc.window.guiScaledHeight.coerceAtLeast(1)
                val top = (h * 0.42f).toInt()
                // (x1, y1, x2, y2)，给 IME 候选窗一个落点
                mgr.setTextInputArea(w / 8, top, w * 7 / 8, top + 36)
                mgr.onTextInputFocusChange(this, true)
                com.ylmac.adapt.render.Probe.hit("in.sdlOn")
            } else {
                mgr.stopTextInput(this)
                com.ylmac.adapt.render.Probe.hit("in.sdlOff")
            }
            sdlTextOn = on
        } catch (_: Throwable) {
            sdlTextOn = false
        }
    }

    private fun apply(commands: List<EditCommand>): Boolean {
        val req = methodRequest
        if (req != null) {
            try {
                req.onEditCommand(commands)
                return true
            } catch (_: Throwable) {
            }
        }
        val legacy = legacyEdit
        if (legacy != null) {
            try {
                legacy(commands)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }
}
