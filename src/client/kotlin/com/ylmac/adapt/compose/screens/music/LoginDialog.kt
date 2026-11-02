package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3LoadingIndicator
import com.ylmac.adapt.module.misc.music.PlayerController
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor

/** — QR matrix from player.qrImage, poll every 800ms. */
@Composable
fun LoginDialog(
    active: Boolean,
    pc: PlayerController,
    tick: Int,
    onClosed: () -> Unit,
) {
    if (!active) return

    var ready by remember { mutableStateOf(false) }
    val qrStatus = numPeek(pc.qrStatus)
    val qr = remember(tick) {
        try {
            @Suppress("UNCHECKED_CAST")
            pc.qrImage.peek() as? List<List<Boolean>>
        } catch (_: Throwable) {
            null
        }
    }

    LaunchedEffect(active) {
        if (active) {
            ready = false
            try {
                pc.startQrLogin()
            } catch (_: Throwable) {
            }
            delay(280)
            ready = true
        }
    }

    LaunchedEffect(active) {
        while (active) {
            try {
                pc.pollQrLogin()
            } catch (_: Throwable) {
            }
            delay(800)
        }
    }

    LaunchedEffect(qrStatus) {
        if (qrStatus == 803) onClosed()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(300.dp)
                .height(400.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MonetScheme.surfaceContainerHigh)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "扫码登录网易云",
                color = MonetScheme.onSurface,
                fontSize = 20.sp,
                fontFamily = MusicFonts.PingFang,
            )
            Box(
                Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (!ready || qr == null) {
                    Md3LoadingIndicator(Modifier.size(48.dp))
                }
                if (ready) {
                    QrCanvas(qr, Modifier.size(200.dp))
                }
            }
            Text(
                statusText(qrStatus),
                color = MonetScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontFamily = MusicFonts.PingFang,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "取消",
                Modifier
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onClosed,
                    )
                    .padding(8.dp),
                color = MonetScheme.primary,
                fontFamily = MusicFonts.PingFang,
            )
        }
    }
}

@Composable
private fun QrCanvas(matrix: List<List<Boolean>>?, mod: Modifier) {
    Canvas(mod) {
        drawRect(Color.White)
        val m = matrix ?: return@Canvas
        val n = m.size
        if (n <= 0) return@Canvas
        val cell = size.width / n
        for (y in 0 until n) {
            val row = m[y]
            for (x in 0 until row.size) {
                if (row[x]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(floor(x * cell), floor(y * cell)),
                        size = Size(ceil(cell), ceil(cell)),
                    )
                }
            }
        }
    }
}

private fun statusText(code: Int): String = when (code) {
    0 -> "正在获取二维码…"
    802 -> "已扫码，请在手机上确认"
    803 -> "登录成功"
    800 -> "二维码已过期，正在刷新…"
    else -> "请用网易云音乐 App 扫码"
}
