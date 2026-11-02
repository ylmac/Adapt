package com.ylmac.adapt.compose.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Compose 在 Adapt 里的线程约定。
 *
 * Minecraft 的屏幕渲染与输入都在**渲染线程**上。Compose Desktop 默认倾向
 * AWT `MainUIDispatcher`，与游戏线程混用容易死锁（见 CMP #4788）。
 *
 * 壳阶段规则：
 * - [AdaptComposeSession] / ImageComposeScene 的 `render`、`sendPointerEvent`、
 * `setContent` **只**在 MC 渲染线程调用。
 * - UI 状态（如 Smoke 计数）只在同一线程写入。
 * - [FrameRecomposer] 要求 coroutineContext 含 [ContinuationInterceptor]；
 * 这里用立即执行的 dispatcher，避免 hop 到 AWT 队列。
 */
object ComposeDispatchers {
    const val POLICY = "mc-render-thread-immediate"

    /**
 * 在调用线程同步跑完 block（MC 渲染线程亲和）。
 * 满足 FrameRecomposer 对 ContinuationInterceptor 的硬性要求。
 */
    val Immediate: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }

        override fun toString(): String = "AdaptCompose-Immediate"
    }

    /** ImageComposeScene / FrameRecomposer 用的完整上下文。 */
    fun sceneContext(): CoroutineContext = Immediate + SupervisorJob()
}
