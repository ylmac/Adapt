package com.ylmac.adapt.compose.host

import com.ylmac.adapt.AdaptClient
import java.util.concurrent.atomic.AtomicLong

/**
 * Compose 渲染性能计数。
 * - idleSkip：无脏时跳过 Skia draw
 * - presentOnly：只 blit 缓存纹理
 */
object ComposePerf {
    private val frames = AtomicLong()
    private val draws = AtomicLong()
    private val skips = AtomicLong()
    private var windowStartNs = System.nanoTime()
    private var lastLogNs = 0L

    /** 默认开：进游戏看 `[ComposePerf] drawRatio`；idle 应接近 0%。 */
    @JvmStatic
    var enabled: Boolean = true

    fun onFrame(drew: Boolean) {
        if (!enabled) return
        frames.incrementAndGet()
        if (drew) draws.incrementAndGet() else skips.incrementAndGet()
        val now = System.nanoTime()
        if (now - lastLogNs >= 1_000_000_000L) {
            lastLogNs = now
            val elapsed = (now - windowStartNs).coerceAtLeast(1L)
            val f = frames.getAndSet(0)
            val d = draws.getAndSet(0)
            val s = skips.getAndSet(0)
            windowStartNs = now
            val fps = f * 1_000_000_000.0 / elapsed
            AdaptClient.LOGGER.info(
                "[ComposePerf] fps={} draws={} skips={} drawRatio={}%",
                String.format("%.0f", fps),
                d,
                s,
                if (f == 0L) 0 else (d * 100 / f),
            )
        }
    }
}
