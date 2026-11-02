package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3LoadingIndicator

/**
 * Exact:
 * 64-tall, r16, surfaceContainerHighest@0.75
 * [44-circle cover][12][title/artist][8][36 一起听][8][36 play][8][36 prev][8][36 next +12]
 */
@Composable
fun MiniPlayerBar(
    title: String,
    artist: String,
    playing: Boolean,
    loading: Boolean,
    loggedIn: Boolean,
    listenTogetherInRoom: Boolean,
    coverPath: String,
    coverUrl: String,
    coverBytes: ByteArray?,
    showLyrics: Boolean,
    onLyrics: () -> Unit,
    onListenTogether: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    mod: Modifier = Modifier,
) {
    Row(
        mod
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MonetScheme.surfaceContainerHighest.copy(alpha = 0.75f))
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { if (showLyrics) onLyrics() },
                ),
        ) {
            CoverImage(
                source = coverPath.ifBlank { coverUrl },
                bytes = coverBytes,
                radius = 22.dp,
                icon = "music_note",
                iconSize = 22,
                mod = Modifier.size(44.dp),
            )
            if (loading) {
                Box(
                    Modifier.size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Md3LoadingIndicator(Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { if (showLyrics) onLyrics() },
                ),
        ) {
            Text(
                text = when {
                    loading -> "准备播放…"
                    title.isNotBlank() -> title
                    else -> "未播放"
                },
                color = MonetScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = MusicFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (loading) "加载音频…" else artist,
                color = MonetScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontFamily = MusicFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (loggedIn) {
            PlaybackCircle(
                icon = "group",
                iconSize = 20,
                fill = if (listenTogetherInRoom) MonetScheme.tertiary else MonetScheme.secondaryContainer,
                tint = if (listenTogetherInRoom) MonetScheme.onTertiary else MonetScheme.onSecondaryContainer,
                onClick = onListenTogether,
            )
            Spacer(Modifier.width(8.dp))
        }
        // Transport order in: play left of prev left of next (anchored right).
        PlaybackCircle(
            icon = if (playing) "pause" else "play_arrow",
            iconSize = 22,
            fill = MonetScheme.primary,
            tint = MonetScheme.onPrimary,
            onClick = onPlayPause,
        )
        Spacer(Modifier.width(8.dp))
        PlaybackCircle(
            icon = "skip_previous",
            iconSize = 22,
            fill = MonetScheme.onPrimary,
            tint = MonetScheme.primary,
            onClick = onPrev,
        )
        Spacer(Modifier.width(8.dp))
        PlaybackCircle(
            icon = "skip_next",
            iconSize = 22,
            fill = MonetScheme.onPrimary,
            tint = MonetScheme.primary,
            onClick = onNext,
        )
    }
}

@Composable
private fun PlaybackCircle(
    icon: String,
    iconSize: Int,
    fill: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Md3Icon(icon, size = iconSize.sp, color = tint)
    }
}
