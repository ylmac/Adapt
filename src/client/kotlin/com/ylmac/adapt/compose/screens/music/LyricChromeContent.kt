package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3Icon
import com.ylmac.adapt.features.music.PlayerController
import kotlinx.coroutines.delay
/**
 * 对齐 qml-retired/LyricOverlay.qml：
 * 横屏左半封面+标题+进度+运输，右半留给宿主逐字歌词；
 * 竖屏标题在上、运输在下，中间透明。关闭是左上 expand_more。
 */
@Composable
fun LyricChromeContent(
    pc: PlayerController,
    title: String,
    artist: String,
    playing: Boolean,
    coverPath: String,
    coverBytes: ByteArray?,
    onClose: () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var posLabel by remember { mutableStateOf("0:00") }
    var durLabel by remember { mutableStateOf("0:00") }
    var liked by remember { mutableStateOf(false) }
    var likeable by remember { mutableStateOf(false) }
    var playMode by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                progress = floatPeek(pc.displayProgress)
                posLabel = fmtMs(longPeek(pc.positionMs))
                durLabel = fmtMs(longPeek(pc.durationMs))
                liked = boolPeek(pc.currentLiked)
                likeable = boolPeek(pc.currentLikeable)
                playMode = numPeek(pc.playMode)
            } catch (_: Throwable) {
            }
            delay(200)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Box(
            Modifier
                .padding(start = 6.dp, top = 6.dp)
                .size(40.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Md3Icon(name = "expand_more", size = 28.sp, color = Color.White)
        }

        if (landscape) {
            val region = maxWidth / 2
            val cover = minOf(region - 96.dp, maxHeight - 248.dp, 360.dp).coerceAtLeast(120.dp)
            Box(
                Modifier
                    .width(region)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.widthIn(max = cover).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverImage(
                        source = coverPath,
                        bytes = coverBytes,
                        radius = cover * 0.06f,
                        iconSize = 64,
                        mod = Modifier.size(cover),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        title.ifBlank { " " },
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = MusicFonts.PingFang,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        artist.ifBlank { " " },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontFamily = MusicFonts.PingFang,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(22.dp))
                    ThinProgress(progress, pc, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TimeLabel(posLabel)
                        TimeLabel(durLabel)
                    }
                    Spacer(Modifier.height(16.dp))
                    TransportRow(pc, playing, playMode, liked, likeable)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = 52.dp),
            ) {
                Text(
                    title.ifBlank { " " },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = MusicFonts.PingFang,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    artist.ifBlank { " " },
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontFamily = MusicFonts.PingFang,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, bottom = 26.dp),
            ) {
                ThinProgress(progress, pc, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeLabel(posLabel)
                    TimeLabel(durLabel)
                }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TransportRow(pc, playing, playMode, liked, likeable)
                }
            }
        }
    }
}

@Composable
private fun ThinProgress(progress: Float, pc: PlayerController, mod: Modifier) {
    // 对齐 LinearProgress.qml：按下/拖动都 seek；松手后按住显示值，直到播放进度追上。
    var override by remember { mutableStateOf<Float?>(null) }
    val shown = (override ?: progress).coerceIn(0f, 1f)
    LaunchedEffect(progress, override) {
        val held = override ?: return@LaunchedEffect
        if (kotlin.math.abs(progress - held) < 0.02f) override = null
    }
    Box(
        mod
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(pc) {
                fun valueAt(x: Float): Float {
                    val w = size.width.toFloat()
                    if (w <= 1f) return 0f
                    return (x / w).coerceIn(0f, 1f)
                }
                fun seekTo(v: Float) {
                    override = v
                    try {
                        val dur = longPeek(pc.durationMs)
                        if (dur > 0L) pc.seek((v * dur).toLong())
                    } catch (_: Throwable) {
                    }
                    com.ylmac.adapt.render.Probe.hit("lyric.seek")
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    seekTo(valueAt(down.position.x))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        seekTo(valueAt(change.position.x))
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.28f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = MusicFonts.PingFang)
}

@Composable
private fun TransportRow(
    pc: PlayerController,
    playing: Boolean,
    playMode: Int,
    liked: Boolean,
    likeable: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TransportBtn(
            size = 38.dp,
            icon = when (playMode) {
                1 -> "shuffle"
                2 -> "repeat_one"
                else -> "repeat"
            },
            iconSp = 18.sp,
            fill = MonetScheme.secondaryContainer,
            tint = if (playMode == 0) MonetScheme.onSecondaryContainer else MonetScheme.primary,
            onClick = { try { pc.cyclePlayMode() } catch (_: Throwable) {} },
        )
        TransportBtn(
            size = 44.dp,
            icon = "skip_previous",
            iconSp = 24.sp,
            fill = MonetScheme.secondaryContainer,
            tint = MonetScheme.onSecondaryContainer,
            onClick = { try { pc.prev() } catch (_: Throwable) {} },
        )
        TransportBtn(
            size = 64.dp,
            icon = if (playing) "pause" else "play_arrow",
            iconSp = 34.sp,
            fill = MonetScheme.tertiaryContainer,
            tint = MonetScheme.onTertiaryContainer,
            radius = 24.dp,
            onClick = { try { pc.toggle() } catch (_: Throwable) {} },
        )
        TransportBtn(
            size = 44.dp,
            icon = "skip_next",
            iconSp = 24.sp,
            fill = MonetScheme.secondaryContainer,
            tint = MonetScheme.onSecondaryContainer,
            onClick = { try { pc.next() } catch (_: Throwable) {} },
        )
        TransportBtn(
            size = 38.dp,
            icon = if (liked) "favorite" else "favorite_border",
            iconSp = 18.sp,
            fill = if (liked) Color(0xFFFF5277) else MonetScheme.secondaryContainer,
            tint = if (liked) Color.White else MonetScheme.onSecondaryContainer,
            enabled = likeable,
            onClick = { try { pc.toggleLike() } catch (_: Throwable) {} },
        )
    }
}

@Composable
private fun TransportBtn(
    size: Dp,
    icon: String,
    iconSp: androidx.compose.ui.unit.TextUnit,
    fill: Color,
    tint: Color,
    onClick: () -> Unit,
    radius: Dp = size / 2,
    enabled: Boolean = true,
) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(fill)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Md3Icon(name = icon, size = iconSp, color = tint)
    }
}

private fun fmtMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}
