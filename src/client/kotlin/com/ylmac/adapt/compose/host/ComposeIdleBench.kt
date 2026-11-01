package com.ylmac.adapt.compose.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.AdaptClient
import com.ylmac.adapt.compose.theme.AdaptFonts
import com.ylmac.adapt.compose.theme.AdaptTheme
import com.ylmac.adapt.compose.theme.MonetScheme

/**
 * Compose 空闲压测（`.compose bench`）：
 * 首帧 draw → 循环 renderFrame；期望绝大多数 skip，等效 FPS ≫ 5000。
 * 需在已有 Minecraft 客户端线程跑（Vulkan/纹理管理器）。
 */
object ComposeIdleBench {
    @JvmStatic
    fun run(frames: Int = 50_000): Double {
        ComposePerf.enabled = true
        AdaptFonts.warm()
        MonetScheme.ensureAttached()
        MonetScheme.refresh()
        val session = AdaptComposeSession("idle_bench")
        session.setContent {
            AdaptTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("bench", color = MonetScheme.onSurface, fontSize = 16.sp)
                }
            }
        }
        session.ensureDesignScene(960, 540, 1f)
        session.renderFrame(System.nanoTime())
        val t0 = System.nanoTime()
        var drew = 0
        repeat(frames) {
            if (session.renderFrame(System.nanoTime() + it * 16_666_667L)) drew++
        }
        val elapsed = System.nanoTime() - t0
        session.close()
        ComposePerf.enabled = false
        val fps = frames * 1_000_000_000.0 / elapsed.coerceAtLeast(1L)
        AdaptClient.LOGGER.info(
            "[ComposeIdleBench] frames={} drew={} elapsedMs={} fps={} (target>=5000)",
            frames,
            drew,
            elapsed / 1_000_000,
            String.format("%.0f", fps),
        )
        return fps
    }
}
