package com.ylmac.adapt.compose.screens.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.AdaptFonts

/** 兼容旧引用：正文用 AdaptFonts.PingFang，图标走共享 Md3Icon(liga)。 */
internal object MusicFonts {
    val MaterialSymbolsRounded: FontFamily get() = AdaptFonts.MaterialSymbolsRounded
    val PingFang: FontFamily get() = AdaptFonts.PingFang
}

@Composable
internal fun Md3Icon(
    name: String,
    size: TextUnit = 22.sp,
    color: Color,
    mod: Modifier = Modifier,
) {
    com.ylmac.adapt.compose.ui.Md3Icon(name = name, size = size, color = color, mod = mod)
}
