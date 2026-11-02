package com.ylmac.adapt.compose.screens.music

import androidx.compose.runtime.Composable
import com.ylmac.adapt.compose.host.AdaptComposeScreen
import com.ylmac.adapt.compose.screens.music.MusicShellContent
import com.ylmac.adapt.config.DesktopSettings
import com.ylmac.adapt.module.misc.music.PlayerController
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import com.ylmac.adapt.render.color.CoverSeedSampler
import com.ylmac.adapt.render.screen.AdaptRuntime
import com.ylmac.adapt.render.surface.GpuContextHelper
import com.ylmac.adapt.render.surface.MinecraftSurfaceBackend

/**
 * Adapt 音乐播放器（Compose）+ 宿主歌词层（HumbleUI LyricCompositor）。
 */
class AdaptScreen : AdaptComposeScreen("Adapt Music Player", "adapt_main") {

    override val forceWorldSnapshotOnOpen: Boolean = true
    override val glassCornerRadius: Float = 16f

    private var lyricSurf: MinecraftSurfaceBackend? = null
    private var lyricLastOpen = false
    private var lyricDragActive = false

    init {
        AdaptRuntime.getOrCreatePlayerController()
        AdaptRuntime.ensureFonts()
    }

    @Composable
    override fun Content() {
        MusicShellContent()
    }

    override fun onComposeOpened() {
        AdaptRuntime.getOrCreatePlayerController().pump()
        try {
            CoverSeedSampler.get().attachIfNeeded()
        } catch (_: Throwable) {
        }
    }

    override fun beforeComposePresent(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        // 流体/歌词正文在 Compose 之下，便于 LyricChrome 浮在上面
        presentLyricLayer(g)
    }

    private fun presentLyricLayer(g: GuiGraphicsExtractor) {
        val pc = AdaptRuntime.getPlayerController() ?: return
        val settings = AdaptRuntime.getOrCreateDesktopSettings()
        val open = java.lang.Boolean.TRUE == pc.lyricsOpen.peek()
        val slide = try {
            pc.lyricSlide.peekDouble()
        } catch (_: Throwable) {
            0.0
        }
        if (!open && slide <= 0.001) {
            lyricLastOpen = false
            lyricDragActive = false
            return
        }
        lyricLastOpen = open || slide > 0.001

        try {
            val deviceScale = (panelScaleValue() * net.minecraft.client.Minecraft.getInstance().window.guiScale.toFloat())
                .coerceIn(0.05f, com.ylmac.adapt.compose.host.AdaptComposeSession.MAX_DEVICE_SCALE)
            var surf = lyricSurf
            if (surf == null) {
                surf = MinecraftSurfaceBackend(
                    "adapt_lyric",
                    designWidth,
                    designHeight,
                    deviceScale,
                )
                lyricSurf = surf
            }
            surf.ensureDeviceScale(deviceScale)

            val canvas = surf.acquireCanvas() ?: return
            val ctx = GpuContextHelper.getOrCreateContext()
            val compositor = AdaptRuntime.getOrCreateLyricCompositor()
            com.ylmac.adapt.render.profile.Probe.begin("lyric.draw")
            compositor.renderOverlay(
                canvas,
                pc,
                settings,
                ctx,
                deviceScale,
                surf.displayWidth,
                surf.displayHeight,
            )
            // 流体铺满矩形。毛玻璃角已经按 16 擦掉，这里不擦的话直角歌词会盖住面板圆角。
            // acquireCanvas 仍处于设计坐标缩放，半径用设计像素。
            eraseOutsideRoundRect(
                canvas,
                designWidth.toFloat(),
                designHeight.toFloat(),
                glassCornerRadius,
            )
            com.ylmac.adapt.render.profile.Probe.end("lyric.draw")
            com.ylmac.adapt.render.profile.Probe.begin("lyric.present")
            surf.present()
            com.ylmac.adapt.render.profile.Probe.end("lyric.present")

            val id = surf.textureId ?: return
            g.blit(
                surf.blitPipeline(),
                id,
                panelLeft(),
                panelTop(),
                0f,
                0f,
                panelDispW(),
                panelDispH(),
                surf.displayWidth,
                surf.displayHeight,
                surf.displayWidth,
                surf.displayHeight,
            )
        } catch (t: Throwable) {
            com.ylmac.adapt.AdaptClient.LOGGER.warn(
                "[AdaptScreen] lyric layer failed: {}",
                t.toString(),
            )
        }
    }

    private fun toDesign(mx: Double, my: Double): FloatArray? {
        val px = panelLeft()
        val py = panelTop()
        val dw = panelDispW()
        val dh = panelDispH()
        if (mx < px || my < py || mx >= px + dw || my >= py + dh) return null
        val lx = ((mx - px) * designWidth / dw.toDouble()).toFloat()
        val ly = ((my - py) * designHeight / dh.toDouble()).toFloat()
        return floatArrayOf(lx, ly)
    }

    private fun lyricHit(lx: Float, ly: Float): Boolean {
        if (!lyricLastOpen) return false
        return try {
            val settings = AdaptRuntime.getOrCreateDesktopSettings()
            val top = try {
                settings.topInset.peek()?.toFloat() ?: 0f
            } catch (_: Throwable) {
                0f
            }
            AdaptRuntime.getOrCreateLyricCompositor().lyricsScrollable(
                lx, ly, designWidth.toFloat(), designHeight.toFloat(), top,
            )
        } catch (_: Throwable) {
            false
        }
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        val loc = toDesign(mouseX, mouseY)
        if (loc != null && lyricHit(loc[0], loc[1])) {
            try {
                val r = AdaptRuntime.getOrCreateLyricCompositor().lyricRenderer()
                // 滚轮：一次 notch ≈ 若干设计像素
                val dy = (-verticalAmount * 48.0).toFloat()
                r.scrollDown(loc[1])
                r.scrollMove(loc[1] + dy)
                r.scrollUp()
                return true
            } catch (_: Throwable) {
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(e: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val loc = toDesign(e.x(), e.y())
        if (loc != null && lyricHit(loc[0], loc[1])) {
            try {
                AdaptRuntime.getOrCreateLyricCompositor().lyricRenderer().scrollDown(loc[1])
                lyricDragActive = true
                return true
            } catch (_: Throwable) {
            }
        }
        return super.mouseClicked(e, doubleClick)
    }

    override fun mouseDragged(e: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (lyricDragActive) {
            val loc = toDesign(e.x(), e.y()) ?: return true
            try {
                AdaptRuntime.getOrCreateLyricCompositor().lyricRenderer().scrollMove(loc[1])
            } catch (_: Throwable) {
            }
            return true
        }
        return super.mouseDragged(e, dragX, dragY)
    }

    override fun mouseReleased(e: MouseButtonEvent): Boolean {
        if (lyricDragActive) {
            lyricDragActive = false
            try {
                AdaptRuntime.getOrCreateLyricCompositor().lyricRenderer().scrollUp()
            } catch (_: Throwable) {
            }
            return true
        }
        return super.mouseReleased(e)
    }

    override fun removed() {
        super.removed()
        lyricDragActive = false
        try {
            lyricSurf?.dispose()
        } catch (_: Throwable) {
        }
        lyricSurf = null
        try {
            val m = com.ylmac.adapt.module.ModuleManager.INSTANCE.findByName("MusicPlayer")
            if (m != null && m.isEnabled) {
                m.forceDisableForScreenClose()
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        @JvmStatic
        fun getPlayerController(): PlayerController? = AdaptRuntime.getPlayerController()

        @JvmStatic
        fun getDesktopSettings(): DesktopSettings? = AdaptRuntime.getDesktopSettings()

        @JvmStatic
        fun getOrCreateDesktopSettings(): DesktopSettings = AdaptRuntime.getOrCreateDesktopSettings()

        @JvmStatic
        fun warmup() = AdaptRuntime.warmup()

        @JvmStatic
        fun precreateGpuSurface() = AdaptRuntime.precreateGpuSurface()
    }
}
