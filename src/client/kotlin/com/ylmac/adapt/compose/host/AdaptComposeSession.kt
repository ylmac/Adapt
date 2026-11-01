package com.ylmac.adapt.compose.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.ylmac.adapt.AdaptClient
import com.ylmac.adapt.compose.internal.ComposeDispatchers
import com.ylmac.adapt.compose.skia.ComposeGpuSurface
import com.ylmac.adapt.compose.skia.ComposeRenderTarget
import com.ylmac.adapt.compose.skia.ComposeSkiaContext
import net.minecraft.resources.Identifier

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

/**
 * Compose 会话：设计分辨率 scene + 无脏帧跳过（idle 只 blit 缓存纹理）。
 *
 * 性能约定（对齐 qml4j skipRender）：
 * - idle：performFrame 推进时钟，但跳过 clear/measure/draw/flush
 * - 动画：用 [requestAnimFrames] 短窗强制绘，而不是每次输入锁 24 帧
 * - 设备像素倍率硬顶 [MAX_DEVICE_SCALE]，避免 Retina×panel 炸出 4K UI RT
 */
class AdaptComposeSession(
    private val name: String,
) : AutoCloseable {
    private var gpuScene: ComposeScene? = null
    private var gpuRecomposer: FrameRecomposer? = null
    private var gpuWindowInfo: AdaptWindowInfo? = null
    private var gpuSurface: ComposeGpuSurface? = null

    private var rasterScene: ImageComposeScene? = null
    private val rasterTarget = ComposeRenderTarget(name)

    private var content: (@Composable () -> Unit)? = null
    private var lastPixelW = -1
    private var lastPixelH = -1
    private var lastDensity = Float.NaN
    private var useGpu = false
    private var firstGpuDraw = true
    private var inputDirty = false
    /** 动画补帧计数：仅 Switch/Segmented 等短 tween 需要，勿对每次 key/move 拉满。 */
    private var forceDrawFrames = 0

    val textInput = AdaptTextInput()

    companion object {
        /** UI RT 设备倍率硬顶。guiScale=3 × panel≈1.5 否则轻松 >4K。 */
        const val MAX_DEVICE_SCALE = 2.0f

        private val pool = java.util.concurrent.ConcurrentHashMap<String, AdaptComposeSession>()

        fun obtain(name: String): AdaptComposeSession {
            val existing = pool.remove(name)
            if (existing != null) {
                // 复用：纹理里可能还有上一帧内容，轻脏即可，不必当 firstGpuDraw 全清
                existing.inputDirty = true
                existing.forceDrawFrames = maxOf(existing.forceDrawFrames, 1)
                return existing
            }
            return AdaptComposeSession(name)
        }

        fun release(name: String, session: AdaptComposeSession) {
            // 保留 composition 树：下次 obtain 只 setContent 刷新，避免整树 remount
            session.textInput.stopInput()
            session.inputDirty = false
            session.forceDrawFrames = 0
            pool.put(name, session)?.close()
        }

        /** 后台预热：建 GPU scene + 空内容首帧，打开 ClickGUI 时几乎零初始化。 */
        @JvmStatic
        fun prewarm(name: String, designW: Int = 960, designH: Int = 540, deviceScale: Float = 1f) {
            try {
                val s = pool[name] ?: AdaptComposeSession(name).also { pool[name] = it }
                s.ensureDesignScene(designW, designH, deviceScale.coerceIn(0.05f, MAX_DEVICE_SCALE))
                s.setContent { }
                s.renderFrame(System.nanoTime())
                AdaptClient.LOGGER.info("[AdaptComposeSession] prewarm ok name={} gpu={}", name, s.useGpu)
            } catch (t: Throwable) {
                AdaptClient.LOGGER.warn("[AdaptComposeSession] prewarm failed: {}", t.toString())
            }
        }
    }

    var pixelScale: Float = 1f
        private set
    var designW: Int = 960
        private set
    var designH: Int = 540
        private set

    private var lastPointerX = Float.NaN
    private var lastPointerY = Float.NaN

    init {
        val ctx = ComposeSkiaContext.tryInit()
        useGpu = ctx != null
        if (!useGpu) {
            ComposeSkiaContext.markGpuUnavailable("vulkan_init_failed")
        }
        AdaptClient.LOGGER.info(
            "[AdaptComposeSession] created name={} path={} gpu={}",
            name,
            ComposeSkiaContext.pathLabel(),
            useGpu,
        )
    }

    fun isGpu(): Boolean = useGpu
    fun renderTarget(): ComposeRenderTarget = rasterTarget

    fun presentTextureId(): Identifier? =
        if (useGpu) gpuSurface?.textureId() else rasterTarget.texturePresent().textureId()

    fun presentWidth(): Int =
        if (useGpu) gpuSurface?.width() ?: 0 else rasterTarget.texturePresent().width()

    fun presentHeight(): Int =
        if (useGpu) gpuSurface?.height() ?: 0 else rasterTarget.texturePresent().height()

    fun presentBlitPipeline(): RenderPipeline =
        if (useGpu) {
            gpuSurface?.blitPipeline()
                ?: net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA
        } else {
            rasterTarget.texturePresent().blitPipeline()
        }

    fun setContent(content: @Composable () -> Unit) {
        this.content = content
        if (useGpu) {
            gpuScene?.setContent { content() }
        } else {
            rasterScene?.setContent(content)
        }
        inputDirty = true
        forceDrawFrames = maxOf(forceDrawFrames, 2)
    }

    fun ensureDesignScene(designW: Int, designH: Int, deviceScale: Float) {
        val dw = designW.coerceAtLeast(1)
        val dh = designH.coerceAtLeast(1)
        val scale = deviceScale.coerceIn(0.05f, MAX_DEVICE_SCALE)
        val pw = (dw * scale).toInt().coerceAtLeast(1)
        val ph = (dh * scale).toInt().coerceAtLeast(1)
        this.designW = dw
        this.designH = dh
        pixelScale = scale

        if (useGpu) {
            ensureGpuScene(dw, dh, pw, ph, scale)
        } else {
            ensureRasterScene(dw, dh, pw, ph, scale)
        }
    }

    fun ensureScene(logicalW: Int, logicalH: Int, guiScale: Double) {
        ensureDesignScene(logicalW, logicalH, guiScale.toFloat().coerceIn(1f, MAX_DEVICE_SCALE))
    }

    private fun ensureGpuScene(dw: Int, dh: Int, pw: Int, ph: Int, scale: Float) {
        val ctx = ComposeSkiaContext.gpuOrNull()
        if (ctx == null) {
            fallBackToRaster("context_lost")
            ensureRasterScene(dw, dh, pw, ph, scale)
            return
        }
        try {
            val surface = gpuSurface ?: ComposeGpuSurface(name, ctx).also { gpuSurface = it }
            surface.ensureSize(pw, ph)
        } catch (t: Throwable) {
            AdaptClient.LOGGER.warn(
                "[AdaptComposeSession] GPU surface failed, fallback raster: {}",
                t.toString(),
            )
            fallBackToRaster(t.javaClass.simpleName)
            ensureRasterScene(dw, dh, pw, ph, scale)
            return
        }

        if (gpuScene != null && lastPixelW == pw && lastPixelH == ph && lastDensity == scale) {
            return
        }

        closeGpuSceneOnly()
        lastPixelW = pw
        lastPixelH = ph
        lastDensity = scale
        firstGpuDraw = true
        inputDirty = true

        val density = Density(scale, 1f)
        val windowInfo = AdaptWindowInfo(pw, ph, density)
        gpuWindowInfo = windowInfo
        val textInputService = textInput
        val platform = object : PlatformContext by PlatformContext.Empty() {
            override val windowInfo: WindowInfo get() = windowInfo
            override val textInputService: PlatformTextInputService get() = textInputService
            override fun requestFocus(): Boolean = true
            override suspend fun startInputMethod(
                request: PlatformTextInputMethodRequest,
            ): Nothing = textInputService.startInputMethod(request)
        }

        registerSkikoComposeImplementation()
        val recomposer = FrameRecomposer(ComposeDispatchers.sceneContext()) {
            // 帧钟上还有 awaiter（开关 tween、切页）：下一游戏帧必须画
            inputDirty = true
        }
        gpuRecomposer = recomposer
        val body = content
        val size = IntSize(pw, ph)
        val scene = CanvasLayersComposeScene(
            frameRecomposer = recomposer,
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            size = size,
            platformContext = platform,
        )
        scene.setContent { body?.invoke() }
        gpuScene = scene

        AdaptClient.LOGGER.info(
            "[AdaptComposeSession] GPU design={}x{} pixel={}x{} density={} path={}",
            dw, dh, pw, ph, scale, ComposeSkiaContext.pathLabel(),
        )
    }

    private fun ensureRasterScene(dw: Int, dh: Int, pw: Int, ph: Int, scale: Float) {
        rasterTarget.resize(pw, ph)
        if (rasterScene != null && lastPixelW == pw && lastPixelH == ph && lastDensity == scale) {
            return
        }
        rasterScene?.close()
        lastPixelW = pw
        lastPixelH = ph
        lastDensity = scale
        inputDirty = true
        val body = content
        val density = Density(scale, 1f)
        rasterScene = ImageComposeScene(
            width = pw,
            height = ph,
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            coroutineContext = ComposeDispatchers.sceneContext(),
            content = { body?.invoke() },
        )
        AdaptClient.LOGGER.info(
            "[AdaptComposeSession] raster design={}x{} pixel={}x{} density={} path={}",
            dw, dh, pw, ph, scale, ComposeSkiaContext.pathLabel(),
        )
    }

    private fun fallBackToRaster(reason: String) {
        useGpu = false
        ComposeSkiaContext.markGpuUnavailable("fallback:$reason")
        closeGpuSceneOnly()
        try {
            gpuSurface?.close()
        } catch (_: Exception) {
        }
        gpuSurface = null
    }

    /** @return true if a Skia draw happened this frame */
    fun renderFrame(nanoTime: Long): Boolean {
        val drew = if (useGpu) {
            renderGpuFrame(nanoTime)
        } else {
            renderRasterFrame(nanoTime)
        }
        return drew
    }

    private fun renderRasterFrame(nanoTime: Long): Boolean {
        val s = rasterScene ?: run {
            ComposePerf.onFrame(false)
            return false
        }
        val needsUpload = firstGpuDraw || inputDirty || forceDrawFrames > 0 || s.hasInvalidations()
        if (!needsUpload) {
            try {
                s.render(nanoTime).close()
            } catch (_: Throwable) {
            }
            ComposePerf.onFrame(false)
            return false
        }
        val ok = rasterTarget.presentFrame(s, nanoTime)
        if (ok) {
            firstGpuDraw = false
            inputDirty = false
            if (forceDrawFrames > 0) forceDrawFrames--
        }
        ComposePerf.onFrame(ok)
        return ok
    }

    private fun renderGpuFrame(nanoTime: Long): Boolean {
        val scene = gpuScene ?: return false
        val recomposer = gpuRecomposer ?: return false
        val surface = gpuSurface ?: return false
        val skiaCanvas = surface.canvas() ?: return false

        return try {
            // 必须跑 performFrame：推进 FrameClock / 协程；否则 LaunchedEffect 停转
            com.ylmac.adapt.render.Probe.begin("compose.perform")
            recomposer.performFrame(nanoTime)
            com.ylmac.adapt.render.Probe.end("compose.perform")

            val whyFirst = firstGpuDraw
            val whyDirty = inputDirty
            val whyForce = forceDrawFrames > 0
            val whyPending = scene.hasPendingDraw || scene.hasPendingMeasureOrLayout
            // 开关/切页的 tween 停在 withFrameNanos 上。performFrame 会把下一帧登记好，
            // 但 hasPendingDraw 要等这次 measure 才亮。不看 hasPendingWork 的话，
            // 点下去只画出动画第 0 帧（样子还是旧的），后面整段被 idle skip 吃掉。
            val whyWork = recomposer.hasPendingWork()
            val needsDraw = whyFirst || whyDirty || whyForce || whyPending || whyWork
            if (!needsDraw) {
                ComposePerf.onFrame(false)
                return false
            }
            if (whyFirst) com.ylmac.adapt.render.Probe.hit("compose.whyFirst")
            if (whyDirty) com.ylmac.adapt.render.Probe.hit("compose.whyDirty")
            if (whyForce) com.ylmac.adapt.render.Probe.hit("compose.whyForce")
            if (whyPending) com.ylmac.adapt.render.Probe.hit("compose.whyPending")
            if (whyWork) com.ylmac.adapt.render.Probe.hit("compose.whyWork")

            com.ylmac.adapt.render.Probe.begin("compose.draw")
            skiaCanvas.clear(0x00000000)
            scene.measureAndLayout()
            scene.draw(skiaCanvas.asComposeCanvas())
            com.ylmac.adapt.render.Probe.end("compose.draw")
            com.ylmac.adapt.render.Probe.begin("compose.flush")
            surface.flush(syncCpu = false)
            com.ylmac.adapt.render.Probe.end("compose.flush")
            firstGpuDraw = false
            inputDirty = false
            if (forceDrawFrames > 0) forceDrawFrames--
            // tween 中间帧：只在仍有 pending 时续 1 帧，避免锁死 24 帧全绘
            if (scene.hasPendingDraw || scene.hasPendingMeasureOrLayout) {
                forceDrawFrames = maxOf(forceDrawFrames, 1)
            }
            ComposePerf.onFrame(true)
            true
        } catch (t: Throwable) {
            AdaptClient.LOGGER.warn(
                "[AdaptComposeSession] GPU present failed, fallback: {}",
                t.toString(),
            )
            fallBackToRaster(t.javaClass.simpleName)
            ComposePerf.onFrame(false)
            false
        }
    }

    /** 轻脏：内容变了，下一帧画一次。 */
    fun markDirty() {
        inputDirty = true
        forceDrawFrames = maxOf(forceDrawFrames, 1)
    }

    /**
     * 动画补帧窗。150ms tween @60fps ≈ 9 帧；默认 12 留余量。
     * 仅交互触发的 animate*AsState（开关/分段）应调用。
     */
    fun requestAnimFrames(frames: Int = 12) {
        inputDirty = true
        forceDrawFrames = maxOf(forceDrawFrames, frames.coerceIn(1, 24))
    }

    fun commitTextCodePoint(codePoint: Int): Boolean {
        markDirty()
        return textInput.commitCodePoint(codePoint)
    }

    fun textBackspace(): Boolean {
        markDirty()
        return textInput.backspace()
    }

    fun dispatchMoveDesign(x: Float, y: Float) {
        // 对齐 QmlScreen：位移² > 0.25 才派发，压掉 hover 抖动脉冲
        if (!lastPointerX.isNaN()) {
            val dx = x - lastPointerX
            val dy = y - lastPointerY
            if (dx * dx + dy * dy <= 0.25f) return
        }
        lastPointerX = x
        lastPointerY = y
        val sx = x * pixelScale
        val sy = y * pixelScale
        val gpu = gpuScene
        if (useGpu && gpu != null) {
            PointerInputBridge.move(gpu, sx.toDouble(), sy.toDouble())
        } else {
            rasterScene?.let { PointerInputBridge.move(it, sx.toDouble(), sy.toDouble()) }
        }
        // hover 若引发 pending，下一帧自然重绘；不强制 markDirty
    }

    fun dispatchPressDesign(x: Float, y: Float) {
        lastPointerX = x
        lastPointerY = y
        // 点一下只脏一帧。开关/切页的 tween 若还在走，renderGpuFrame 末尾会自己续上，
        // 不要预锁 12 帧全尺寸重绘。
        markDirty()
        val sx = (x * pixelScale).toDouble()
        val sy = (y * pixelScale).toDouble()
        val gpu = gpuScene
        if (useGpu && gpu != null) {
            PointerInputBridge.press(gpu, sx, sy)
        } else {
            rasterScene?.let { PointerInputBridge.press(it, sx, sy) }
        }
    }

    fun dispatchReleaseDesign(x: Float, y: Float) {
        markDirty()
        val sx = (x * pixelScale).toDouble()
        val sy = (y * pixelScale).toDouble()
        val gpu = gpuScene
        if (useGpu && gpu != null) {
            PointerInputBridge.release(gpu, sx, sy)
        } else {
            rasterScene?.let { PointerInputBridge.release(it, sx, sy) }
        }
    }

    fun dispatchScrollDesign(x: Float, y: Float, h: Double, v: Double) {
        // 滚动本身会让列表 pending；不要再锁一串强制帧，否则滚一下掉一截帧
        markDirty()
        val sx = (x * pixelScale).toDouble()
        val sy = (y * pixelScale).toDouble()
        val gpu = gpuScene
        if (useGpu && gpu != null) {
            PointerInputBridge.scroll(gpu, sx, sy, h, v)
        } else {
            rasterScene?.let { PointerInputBridge.scroll(it, sx, sy, h, v) }
        }
    }

    fun dispatchPress(x: Double, y: Double) = dispatchPressDesign(x.toFloat(), y.toFloat())
    fun dispatchRelease(x: Double, y: Double) = dispatchReleaseDesign(x.toFloat(), y.toFloat())
    fun dispatchMove(x: Double, y: Double) = dispatchMoveDesign(x.toFloat(), y.toFloat())
    fun dispatchScroll(x: Double, y: Double, h: Double, v: Double) =
        dispatchScrollDesign(x.toFloat(), y.toFloat(), h, v)

    fun dispatchKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        markDirty()
        val gpu = gpuScene
        return if (useGpu && gpu != null) {
            gpu.sendKeyEvent(event)
        } else {
            rasterScene?.sendKeyEvent(event) ?: false
        }
    }

    private fun closeGpuSceneOnly() {
        try {
            gpuScene?.close()
        } catch (_: Exception) {
        }
        try {
            gpuRecomposer?.close()
        } catch (_: Exception) {
        }
        gpuScene = null
        gpuRecomposer = null
        gpuWindowInfo = null
    }

    override fun close() {
        closeGpuSceneOnly()
        try {
            gpuSurface?.close()
        } catch (_: Exception) {
        }
        gpuSurface = null
        try {
            rasterScene?.close()
        } catch (_: Exception) {
        }
        rasterScene = null
        rasterTarget.close()
    }

    private class AdaptWindowInfo(
        private var pixelW: Int,
        private var pixelH: Int,
        private val density: Density,
    ) : WindowInfo {
        override val isWindowFocused: Boolean = true
        override val containerSize: IntSize
            get() = IntSize(pixelW, pixelH)
        override val containerDpSize: DpSize
            get() = DpSize(
                (pixelW / density.density).dp,
                (pixelH / density.density).dp,
            )
    }
}
