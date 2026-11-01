package com.ylmac.adapt.compose.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ylmac.adapt.compose.theme.AdaptColors

/** 全屏底：壳期最小布局容器。 */
@Composable
fun AdaptScaffold(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AdaptColors.Background),
        content = content,
    )
}
