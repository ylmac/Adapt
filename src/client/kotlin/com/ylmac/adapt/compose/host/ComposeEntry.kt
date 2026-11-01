package com.ylmac.adapt.compose.host

import com.ylmac.adapt.AdaptClient
import com.ylmac.adapt.compose.screens.debug.ComposeSmokeScreen
import net.minecraft.client.Minecraft

/**
 * Java / 命令侧入口：打开或关闭 Compose Smoke 屏。
 *
 * 必须从聊天命令延迟到下一拍再 [Gui.setScreen]：
 * ChatScreen 在 `handleChatInput`（触发 `.compose`）之后立刻 `setScreen(null)`，
 * 同步打开会被冲掉，看起来就像「只有提示、没有界面」。
 */
object ComposeEntry {
    @JvmStatic
    fun openSmoke() {
        val mc = Minecraft.getInstance()
        mc.execute {
            AdaptClient.LOGGER.info("[ComposeEntry] opening ComposeSmokeScreen (deferred)")
            mc.gui.setScreen(ComposeSmokeScreen())
        }
    }

    /** 空闲 Compose 压测：不依赖世界，结果写 log `[ComposeIdleBench]`。 */
    @JvmStatic
    fun runIdleBench() {
        val mc = Minecraft.getInstance()
        mc.execute {
            val fps = ComposeIdleBench.run(50_000)
            AdaptClient.LOGGER.info(
                "[ComposeEntry] idle bench fps={} (target >= 5000)",
                String.format("%.0f", fps),
            )
        }
    }

    @JvmStatic
    fun closeSmoke() {
        val mc = Minecraft.getInstance()
        mc.execute {
            if (mc.gui.screen() is ComposeSmokeScreen) {
                AdaptClient.LOGGER.info("[ComposeEntry] closing ComposeSmokeScreen (deferred)")
                mc.gui.setScreen(null)
            }
        }
    }

    @JvmStatic
    fun toggleSmoke() {
        val mc = Minecraft.getInstance()
        // 聊天里发命令时当前屏是 ChatScreen，不是 Smoke；用挂起标志区分「想关」
        if (mc.gui.screen() is ComposeSmokeScreen || pendingClose) {
            pendingClose = false
            closeSmoke()
        } else {
            openSmoke()
        }
    }

    /** 若已知 Smoke 已开（例如 HUD 键），可先标关闭再 toggle。 */
    @JvmStatic
    @Volatile
    var pendingClose: Boolean = false

    @JvmStatic
    fun isSmokeOpen(): Boolean =
        Minecraft.getInstance().gui.screen() is ComposeSmokeScreen
}
