package com.ylmac.adapt.compose.screens.debug

import androidx.compose.runtime.Composable
import com.ylmac.adapt.compose.host.AdaptComposeScreen

/**
 * Compose 接入验收屏：标题 + 可点 Button。
 * 打开方式：`.compose` / `.compose open`
 */
class ComposeSmokeScreen : AdaptComposeScreen(
    title = "ComposeSmoke",
    surfaceName = "compose_smoke",
) {
    @Composable
    override fun Content() {
        SmokeContent()
    }
}
