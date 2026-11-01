package com.ylmac.adapt.compose.host

import androidx.compose.runtime.staticCompositionLocalOf

/** 当前 Compose 屏的文本输入桥（Md3TextField 获焦时 bind）。 */
val LocalAdaptTextInput = staticCompositionLocalOf<AdaptTextInput?> { null }
