package com.ylmac.adapt.compose.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.config.DesktopSettings
import com.ylmac.adapt.compose.screens.music.AdaptScreen

@Composable
fun AdaptTheme(content: @Composable () -> Unit) {
    // 读当前 Monet 角色色；dark 跟随 DesktopSettings（每次重组重读，无 remember 冻结）
    val dark = try {
        val ds = AdaptScreen.getOrCreateDesktopSettings()
        ds.resolvedDark()
    } catch (_: Throwable) {
        true
    }
    val colors = if (dark) {
        darkColors(
            primary = MonetScheme.primary,
            onPrimary = MonetScheme.onPrimary,
            background = MonetScheme.background,
            surface = MonetScheme.surface,
            onBackground = MonetScheme.onSurface,
            onSurface = MonetScheme.onSurface,
            secondary = MonetScheme.secondaryContainer,
            onSecondary = MonetScheme.onSecondaryContainer,
        )
    } else {
        lightColors(
            primary = MonetScheme.primary,
            onPrimary = MonetScheme.onPrimary,
            background = MonetScheme.background,
            surface = MonetScheme.surface,
            onBackground = MonetScheme.onSurface,
            onSurface = MonetScheme.onSurface,
            secondary = MonetScheme.secondaryContainer,
            onSecondary = MonetScheme.onSecondaryContainer,
        )
    }
    val body = AdaptFonts.PingFang
    val typography = Typography(
        defaultFontFamily = body,
        h6 = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
        ),
        subtitle1 = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        ),
        body1 = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
        ),
        body2 = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
        ),
        button = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        ),
        caption = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
        ),
    )
    MaterialTheme(colors = colors, typography = typography, content = content)
}
