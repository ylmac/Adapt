package com.ylmac.adapt.render.clickgui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.ylmac.adapt.compose.host.AdaptComposeScreen
import com.ylmac.adapt.compose.screens.clickgui.ClickGuiContent
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.render.CoverSeedSampler

/**
 * Adapt 风格 ClickGUI（Compose）。入口不变：Right Shift / `.clickgui open`。
 */
class ClickGuiScreen : AdaptComposeScreen("ClickGUI", "clickgui") {
    private var refreshToken by mutableIntStateOf(0)

    override val forceWorldSnapshotOnOpen: Boolean = true
    override val glassCornerRadius: Float
        get() = ClickGuiBridge.cornerRadius.coerceAtLeast(0f)

    @Composable
    override fun Content() {
        ClickGuiContent(refreshToken = refreshToken)
    }

    override fun onComposeOpened() {
        MonetScheme.ensureAttached()
        refreshToken++
        // CoverSeed 延后，别和首帧 GPU ensure 叠卡
        net.minecraft.client.Minecraft.getInstance().execute {
            try {
                CoverSeedSampler.get().attachIfNeeded()
            } catch (_: Throwable) {
            }
        }
    }
}
