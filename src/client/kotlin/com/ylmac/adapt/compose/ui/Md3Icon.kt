package com.ylmac.adapt.compose.ui

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.AdaptFonts

/** Material Symbols ligature — `fontFeatureSettings = "liga"` Theme.iconFont。 */
@Composable
fun Md3Icon(
    name: String,
    size: TextUnit = 22.sp,
    color: Color,
    mod: Modifier = Modifier,
) {
    Text(
        text = name,
        modifier = mod,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontFamily = AdaptFonts.MaterialSymbolsRounded,
            fontWeight = FontWeight.Normal,
            fontFeatureSettings = "\"liga\" 1, \"clig\" 1",
        ),
        maxLines = 1,
    )
}
