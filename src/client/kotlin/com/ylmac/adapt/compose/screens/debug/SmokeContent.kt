package com.ylmac.adapt.compose.screens.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.foundation.AdaptScaffold
import com.ylmac.adapt.compose.skia.ComposeSkiaContext
import com.ylmac.adapt.compose.theme.AdaptColors
import com.ylmac.adapt.compose.theme.AdaptTheme

@Composable
fun SmokeContent() {
    AdaptTheme {
        AdaptScaffold {
            var clicks by remember { mutableIntStateOf(0) }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Adapt Compose Smoke",
                    color = AdaptColors.OnSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "If this button increments, host + input + blit work.",
                    color = AdaptColors.OnSurface.copy(alpha = 0.72f),
                    fontSize = 14.sp,
                )
                Text(
                    text = "clicks = $clicks",
                    color = AdaptColors.OnSurface,
                    fontSize = 18.sp,
                )
                Button(onClick = { clicks += 1 }) {
                    Text("Click me", color = AdaptColors.OnPrimary)
                }
                Text(
                    text = "path: ${ComposeSkiaContext.pathLabel()}",
                    color = AdaptColors.Outline,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
