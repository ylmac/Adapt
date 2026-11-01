package com.ylmac.adapt.compose.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.ylmac.adapt.AdaptClient
import com.ylmac.adapt.theme.StyleManager

/** StyleManager.currentScheme → Compose MD3 角色色（仅 scheme 真变时刷新）。 */
object MonetScheme {
    var primary by mutableStateOf(AdaptColors.Primary)
        private set
    var onPrimary by mutableStateOf(AdaptColors.OnPrimary)
        private set
    var primaryContainer by mutableStateOf(Color(0xFF4A3F63))
        private set
    var onPrimaryContainer by mutableStateOf(Color(0xFFE8DEF8))
        private set
    var surface by mutableStateOf(AdaptColors.Surface)
        private set
    var surfaceContainer by mutableStateOf(AdaptColors.Surface)
        private set
    var surfaceContainerHigh by mutableStateOf(AdaptColors.Surface)
        private set
    var surfaceContainerHighest by mutableStateOf(AdaptColors.Surface)
        private set
    var surfaceContainerLow by mutableStateOf(AdaptColors.Surface)
        private set
    var background by mutableStateOf(AdaptColors.Background)
        private set
    var onSurface by mutableStateOf(AdaptColors.OnSurface)
        private set
    var onSurfaceVariant by mutableStateOf(AdaptColors.OnSurface.copy(alpha = 0.72f))
        private set
    var outline by mutableStateOf(AdaptColors.Outline)
        private set
    var outlineVariant by mutableStateOf(AdaptColors.Outline.copy(alpha = 0.5f))
        private set
    var secondaryContainer by mutableStateOf(AdaptColors.Surface)
        private set
    var onSecondaryContainer by mutableStateOf(AdaptColors.OnSurface)
        private set
    var tertiary by mutableStateOf(Color(0xFF7D5260))
        private set
    var onTertiary by mutableStateOf(Color(0xFFFFFFFF))
        private set
    var tertiaryContainer by mutableStateOf(Color(0xFFFFD8E4))
        private set
    var onTertiaryContainer by mutableStateOf(Color(0xFF31111D))
        private set
    var error by mutableStateOf(Color(0xFFB3261E))
        private set
    var onError by mutableStateOf(Color(0xFFFFFFFF))
        private set

    @Volatile
    private var attached = false

    @Volatile
    private var schemeDirty = true

    @Volatile
    private var lastFingerprint: String = ""

    fun ensureAttached() {
        if (attached) return
        synchronized(this) {
            if (attached) return
            try {
                val sm = StyleManager.__instance() as? StyleManager ?: return
                sm.currentScheme.addListener { _ ->
                    schemeDirty = true
                }
                attached = true
                schemeDirty = true
                AdaptClient.LOGGER.info("[MonetScheme] attached to StyleManager.currentScheme")
            } catch (t: Throwable) {
                AdaptClient.LOGGER.warn("[MonetScheme] attach failed: {}", t.toString())
            }
        }
    }

    /** @return true 若角色色指纹变化（宿主可 markDirty） */
    fun refreshIfChanged(): Boolean {
        ensureAttached()
        if (!schemeDirty && lastFingerprint.isNotEmpty()) return false
        schemeDirty = false
        val before = lastFingerprint
        refresh()
        return lastFingerprint != before
    }

    @Suppress("UNCHECKED_CAST")
    fun refresh() {
        try {
            val sm = StyleManager.__instance() as? StyleManager ?: return
            val map = sm.currentScheme.peek() as? Map<*, *> ?: return
            // StyleManager Scheme 里 on* 角色键带 Color 后缀（onSurfaceColor），
            // 不带后缀会 miss → 浅色模式下表面变白但文字仍是深色主题浅灰 = 看不见。
            fun hex(key: String, fallback: Color): Color {
                val v = map[key] ?: map["${key}Color"] ?: return fallback
                return parseHex(v.toString()) ?: fallback
            }
            primary = hex("primary", primary)
            onPrimary = hex("onPrimary", onPrimary)
            primaryContainer = hex("primaryContainer", primaryContainer)
            onPrimaryContainer = hex("onPrimaryContainer", onPrimaryContainer)
            surface = hex("surface", surface)
            surfaceContainer = hex("surfaceContainer", surfaceContainer)
            surfaceContainerHigh = hex("surfaceContainerHigh", surfaceContainerHigh)
            surfaceContainerHighest = hex("surfaceContainerHighest", surfaceContainerHighest)
            surfaceContainerLow = hex("surfaceContainerLow", surfaceContainerLow)
            background = hex("background", background)
            onSurface = hex("onSurface", onSurface)
            onSurfaceVariant = hex("onSurfaceVariant", onSurfaceVariant)
            outline = hex("outline", outline)
            outlineVariant = hex("outlineVariant", outlineVariant)
            secondaryContainer = hex("secondaryContainer", secondaryContainer)
            onSecondaryContainer = hex("onSecondaryContainer", onSecondaryContainer)
            tertiary = hex("tertiary", tertiary)
            onTertiary = hex("onTertiary", onTertiary)
            tertiaryContainer = hex("tertiaryContainer", tertiaryContainer)
            onTertiaryContainer = hex("onTertiaryContainer", onTertiaryContainer)
            error = hex("error", error)
            onError = hex("onError", onError)
            lastFingerprint = buildString {
                append(map["primary"]).append('|')
                append(map["surface"]).append('|')
                append(map["onSurface"] ?: map["onSurfaceColor"]).append('|')
                append(map["primaryContainer"]).append('|')
                append(map["secondaryContainer"]).append('|')
                append(map["tertiary"])
            }
        } catch (_: Throwable) {
        }
    }

    private fun parseHex(s: String): Color? {
        var t = s.trim()
        if (t.startsWith("#")) t = t.substring(1)
        return try {
            when (t.length) {
                6 -> Color(0xFF000000L or t.toLong(16))
                8 -> Color(t.toLong(16))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
