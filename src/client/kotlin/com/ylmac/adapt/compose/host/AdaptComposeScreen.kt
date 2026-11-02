package com.ylmac.adapt.compose.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.render.hud.BackdropBlurCapture
import com.ylmac.adapt.render.surface.MinecraftSurfaceBackend
import com.ylmac.adapt.render.color.WorldColorSampler
import io.github.humbleui.skija.BlendMode
import io.github.humbleui.skija.Paint
import io.github.humbleui.skija.Path
import io.github.humbleui.skija.PathOp
import io.github.humbleui.skija.SamplingMode
import io.github.humbleui.types.RRect
import io.github.humbleui.types.Rect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import com.ylmac.adapt.input.GLFW

/**
 * Compose 宿主。
 */
abstract class AdaptComposeScreen(
    title: String,
    private val surfaceName: String,
    protected val designWidth: Int = 960,
    protected val designHeight: Int = 540,
) : Screen(Component.literal(title)) {

    protected var session: AdaptComposeSession? = null
        private set

    protected open val forceWorldSnapshotOnOpen: Boolean = true
    protected open val useBackdropBlur: Boolean = true

    /** 这一帧会不会去采世界模糊。采集发生在界面绘制之前，靠这个判断要不要拷帧。 */
    fun wantsBackdropBlur(): Boolean = useBackdropBlur

    /** 宿主毛玻璃外轮廓圆角（设计坐标）。Compose 自身 clip 管不到这层。 */
    protected open val glassCornerRadius: Float = 16f

    private var panelX = 0
    private var panelY = 0
    private var dispW = 1
    private var dispH = 1
    private var panelScale = 1f
    private var lastGuiScale = Float.NaN
    private var lastW = -1
    private var lastH = -1
    private var blurSurf: MinecraftSurfaceBackend? = null
    private var lastBlurKey = 0
    private var glassEverDrawn = false
    private var lastGlassNs = 0L
    private val blurPaint = Paint()
    /** eraseOutsideRoundRect 复用，避免每帧 alloc Path/Paint。 */
    private var erasePaint: Paint? = null
    private var eraseRectPath: Path? = null
    private var eraseRoundPath: Path? = null
    private var eraseDiffPath: Path? = null
    private var eraseCachedW = -1f
    private var eraseCachedH = -1f
    private var eraseCachedR = -1f

    companion object {
        /** 毛玻璃 surface 按 screen 名缓存，避免开关界面反复建 Vulkan RT。 */
        private val glassPool = java.util.concurrent.ConcurrentHashMap<String, MinecraftSurfaceBackend>()
    }

    @Composable
    protected abstract fun Content()

    protected open fun onComposeOpened() {}

    /** 子类可在 Compose blit 之前叠加（如歌词流体底，避免盖住 Compose chrome）。 */
    protected open fun beforeComposePresent(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {}

    /** 子类可在 Compose blit 之后叠加。 */
    protected open fun afterComposePresent(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {}

    override fun init() {
        super.init()
        // MC resize 可能再次 init：复用已有 session，禁止孤儿泄漏 + 重复建 RT
        MonetScheme.ensureAttached()
        MonetScheme.refresh()
        val existing = session
        val s = if (existing != null) {
            existing
        } else {
            AdaptComposeSession.obtain(surfaceName)
        }
        s.setContent {
            CompositionLocalProvider(LocalAdaptTextInput provides s.textInput) {
                Content()
            }
        }
        session = s
        lastW = -1
        lastH = -1
        rebuildSceneIfNeeded(force = true)
        s.markDirty()
        if (existing == null) {
            onComposeOpened()
            if (forceWorldSnapshotOnOpen) {
                Minecraft.getInstance().execute {
                    try {
                        WorldColorSampler.get().forceRefresh()
                    } catch (_: Throwable) {
                        try {
                            WorldColorSampler.get().forceSnapshot()
                        } catch (_: Throwable) {
                        }
                    }
                    session?.markDirty()
                }
            }
        }
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        rebuildSceneIfNeeded(force = true)
    }

    private fun computePanelPos() {
        val margin = maxOf(20, minOf(this.width, this.height) / 6)
        val contentW = this.width - 2 * margin
        val contentH = this.height - 2 * margin
        if (contentW <= 0 || contentH <= 0) {
            panelScale = 0.01f
            panelX = 0
            panelY = 0
            dispW = 1
            dispH = 1
            return
        }
        val sx = contentW.toFloat() / designWidth
        val sy = contentH.toFloat() / designHeight
        panelScale = minOf(sx, sy).coerceAtLeast(0.01f)
        dispW = maxOf(1, Math.round(designWidth * panelScale))
        dispH = maxOf(1, Math.round(designHeight * panelScale))
        panelX = margin + (contentW - dispW) / 2
        panelY = margin + (contentH - dispH) / 2
    }

    private fun rebuildSceneIfNeeded(force: Boolean = false) {
        val s = session ?: return
        computePanelPos()
        val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
        if (!force &&
            this.width == lastW &&
            this.height == lastH &&
            guiScale == lastGuiScale
        ) {
            return
        }
        lastW = this.width
        lastH = this.height
        lastGuiScale = guiScale
        // 硬顶 2×：Retina 下 panelScale×guiScale 轻易 >3 → 3K/4K UI RT
        val deviceScale = (panelScale * guiScale).coerceIn(
            0.05f,
            AdaptComposeSession.MAX_DEVICE_SCALE,
        )
        s.ensureDesignScene(designWidth, designHeight, deviceScale)
        if (useBackdropBlur) {
            try {
                ensureGlassSurface(deviceScale, s.presentWidth(), s.presentHeight())
            } catch (_: Throwable) {
            }
        }
    }

    private fun ensureGlassSurface(deviceScale: Float, needW: Int, needH: Int) {
        val key = "${surfaceName}_glass"
        val pooled = glassPool[key]
        val old = blurSurf ?: pooled
        if (old != null &&
            old.displayWidth == needW &&
            old.displayHeight == needH
        ) {
            blurSurf = old
            glassPool.remove(key)
            return
        }
        try {
            old?.dispose()
        } catch (_: Throwable) {
        }
        glassPool.remove(key)
        blurSurf = MinecraftSurfaceBackend(key, designWidth, designHeight, deviceScale)
        lastBlurKey = -1
        glassEverDrawn = false
    }

    private fun toDesignOrNull(mx: Double, my: Double): FloatArray? {
        if (mx < panelX || my < panelY || mx >= panelX + dispW || my >= panelY + dispH) {
            return null
        }
        val lx = ((mx - panelX) * designWidth / dispW.toDouble()).toFloat()
        val ly = ((my - panelY) * designHeight / dispH.toDouble()).toFloat()
        return floatArrayOf(lx, ly)
    }

    override fun isPauseScreen(): Boolean = false

    override fun shouldCloseOnEsc(): Boolean = true

    override fun extractBackground(g: GuiGraphicsExtractor, mx: Int, my: Int, a: Float) {
        // 透明：世界可见（毛玻璃由 blur 层提供）
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        val s = session
        if (s == null) {
            super.extractRenderState(g, mx, my, delta)
            return
        }
        rebuildSceneIfNeeded(force = false)

        // Monet：仅 scheme 变化时刷新（内部有 version 去重）
        if (MonetScheme.refreshIfChanged()) {
            s.markDirty()
        }

        toDesignOrNull(mx.toDouble(), my.toDouble())?.let { loc ->
            s.dispatchMoveDesign(loc[0], loc[1])
        }

        com.ylmac.adapt.render.profile.Probe.begin("compose.session")
        val drew = s.renderFrame(System.nanoTime())
        com.ylmac.adapt.render.profile.Probe.end("compose.session")
        com.ylmac.adapt.render.profile.Probe.hit(if (drew) "compose.drew" else "compose.skip")
        // 毛玻璃跟世界走，每帧采样。降频会把玻璃停在旧帧上，看起来像糊掉一层。
        com.ylmac.adapt.render.profile.Probe.begin("glass.update")
        updateGlassBackdrop(s)
        com.ylmac.adapt.render.profile.Probe.end("glass.update")
        lastGlassNs = System.nanoTime()

        // 毛玻璃底 →（可选宿主层）→ Compose 面板
        blitGlass(g)
        beforeComposePresent(g, mx, my, delta)
        val id = s.presentTextureId()
        if (id != null) {
            val tw = s.presentWidth().coerceAtLeast(1)
            val th = s.presentHeight().coerceAtLeast(1)
            g.blit(
                s.presentBlitPipeline(),
                id,
                panelX,
                panelY,
                0f,
                0f,
                dispW,
                dispH,
                tw,
                th,
                tw,
                th,
            )
        }
        afterComposePresent(g, mx, my, delta)
        super.extractRenderState(g, mx, my, delta)
    }

    private fun updateGlassBackdrop(@Suppress("UNUSED_PARAMETER") session: AdaptComposeSession) {
        if (!useBackdropBlur) return
        val surf = blurSurf ?: return
        try {
            val img = BackdropBlurCapture.getBlurredImage() ?: return
            val screenW = this.width.coerceAtLeast(1)
            val screenH = this.height.coerceAtLeast(1)
            val radius = glassCornerRadius.coerceAtLeast(0f)
            // 仅几何/图身份变化才重画（已由「仅 drew 时调用」节流；去掉 frameBits 伪脏）
            val key = System.identityHashCode(img) xor
                (img.width shl 16) xor img.height xor
                (panelX shl 8) xor (panelY shl 4) xor
                (dispW * 31) xor (dispH * 17) xor
                java.lang.Float.floatToIntBits(radius)
            if (key == lastBlurKey && glassEverDrawn) return
            lastBlurKey = key

            val canvas = surf.acquireCanvas() ?: return
            canvas.clear(0x00000000)

            val imgW = img.width.toFloat()
            val imgH = img.height.toFloat()
            val dw = designWidth.toFloat()
            val dh = designHeight.toFloat()
            val src = Rect.makeXYWH(
                panelX * imgW / screenW,
                panelY * imgH / screenH,
                dispW * imgW / screenW,
                dispH * imgH / screenH,
            )
            val dst = Rect.makeWH(dw, dh)
            val r = radius.coerceAtMost(minOf(dw, dh) * 0.5f)
            canvas.drawImageRect(
                img,
                src,
                dst,
                SamplingMode.LINEAR,
                blurPaint,
                true,
            )
            if (r > 0.5f) {
                eraseOutsideRoundRect(canvas, dw, dh, r)
            }
            surf.present()
            glassEverDrawn = true
        } catch (_: Throwable) {
        }
    }

    private fun blitGlass(g: GuiGraphicsExtractor) {
        if (!useBackdropBlur) return
        val surf = blurSurf ?: return
        try {
            val id = surf.textureId ?: return
            val tw = surf.displayWidth.coerceAtLeast(1)
            val th = surf.displayHeight.coerceAtLeast(1)
            g.blit(
                surf.blitPipeline(),
                id,
                panelX,
                panelY,
                0f,
                0f,
                dispW,
                dispH,
                tw,
                th,
                tw,
                th,
            )
        } catch (_: Throwable) {
        }
    }

    override fun mouseClicked(e: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(e, doubleClick)
        }
        val s = session ?: return false
        computePanelPos()
        val loc = toDesignOrNull(e.x(), e.y()) ?: return false
        s.dispatchPressDesign(loc[0], loc[1])
        return true
    }

    override fun mouseReleased(e: MouseButtonEvent): Boolean {
        if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseReleased(e)
        }
        val s = session ?: return false
        computePanelPos()
        val loc = toDesignOrNull(e.x(), e.y()) ?: return false
        s.dispatchReleaseDesign(loc[0], loc[1])
        return true
    }

    override fun mouseDragged(e: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val s = session ?: return super.mouseDragged(e, dragX, dragY)
        computePanelPos()
        val loc = toDesignOrNull(e.x(), e.y()) ?: return true
        s.dispatchMoveDesign(loc[0], loc[1])
        return true
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        val s = session ?: return super.mouseScrolled(
            mouseX, mouseY, horizontalAmount, verticalAmount,
        )
        computePanelPos()
        val loc = toDesignOrNull(mouseX, mouseY) ?: return false
        s.dispatchScrollDesign(loc[0], loc[1], horizontalAmount, verticalAmount)
        return true
    }

    override fun keyPressed(e: KeyEvent): Boolean {
        if (KeyInputBridge.keyPressed(session, e)) return true
        return super.keyPressed(e)
    }

    override fun keyReleased(e: KeyEvent): Boolean {
        if (KeyInputBridge.keyReleased(session, e)) return true
        return super.keyReleased(e)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (KeyInputBridge.charTyped(session, event)) return true
        return super.charTyped(event)
    }

    override fun removed() {
        super.removed()
        session?.let { AdaptComposeSession.release(surfaceName, it) }
        session = null
        // 毛玻璃 RT 入池，下次打开免重建
        blurSurf?.let { surf ->
            val key = "${surfaceName}_glass"
            glassPool.put(key, surf)?.let { stale ->
                try {
                    stale.dispose()
                } catch (_: Throwable) {
                }
            }
        }
        blurSurf = null
        lastBlurKey = 0
        glassEverDrawn = false
    }

    protected fun eraseOutsideRoundRect(canvas: io.github.humbleui.skija.Canvas, w: Float, h: Float, r: Float) {
        if (w <= 0f || h <= 0f || r <= 0f) return
        try {
            if (eraseCachedW != w || eraseCachedH != h || eraseCachedR != r || eraseDiffPath == null) {
                try { eraseDiffPath?.close() } catch (_: Throwable) {}
                try { eraseRoundPath?.close() } catch (_: Throwable) {}
                try { eraseRectPath?.close() } catch (_: Throwable) {}
                val rect = Path.makeRect(Rect.makeXYWH(0f, 0f, w, h))
                val round = Path.makeRRect(RRect.makeXYWH(0f, 0f, w, h, r))
                val diff = Path.makeCombining(rect, round, PathOp.DIFFERENCE)
                eraseRectPath = rect
                eraseRoundPath = round
                eraseDiffPath = diff
                eraseCachedW = w
                eraseCachedH = h
                eraseCachedR = r
            }
            val diff = eraseDiffPath ?: return
            val paint = erasePaint ?: Paint().setBlendMode(BlendMode.CLEAR).setAntiAlias(true).also {
                erasePaint = it
            }
            canvas.drawPath(diff, paint)
        } catch (_: Throwable) {
        }
    }

    protected fun panelLeft(): Int = panelX
    protected fun panelTop(): Int = panelY
    protected fun panelDispW(): Int = dispW
    protected fun panelDispH(): Int = dispH
    protected fun panelScaleValue(): Float = panelScale
}
