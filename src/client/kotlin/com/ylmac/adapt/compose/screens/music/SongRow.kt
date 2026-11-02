package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.retainedLayer

/**
 * — 64 tall, cover 48×48 r8, title 15 / artist 12.
 * Cover sits inset so pill radius 12 − 4 = cover radius 8.
 */
@Composable
fun SongRow(
    title: String,
    artist: String,
    onClick: () -> Unit,
    mod: Modifier = Modifier,
    coverThumbPath: String = "",
    coverUrl: String = "",
    tag: String = "",
    highlighted: Boolean = false,
    removable: Boolean = false,
    offlineReady: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    val coverSrc = coverThumbPath.ifBlank { coverUrl }
    Row(
        mod
            .retainedLayer()
            .fillMaxWidth()
            .height(64.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            CoverImage(
                source = coverSrc,
                radius = 8.dp,
                icon = if (highlighted) "equalizer" else "music_note",
                iconSize = 22,
                mod = Modifier.fillMaxSize(),
            )
            if (offlineReady) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MonetScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Md3Icon("check", size = 11.sp, color = MonetScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (highlighted) MonetScheme.primary else MonetScheme.onSurface,
                fontSize = 15.sp,
                fontFamily = MusicFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                color = MonetScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = MusicFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (tag.isNotBlank()) {
            Box(
                Modifier
                    .padding(start = 8.dp, end = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MonetScheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = tag,
                    color = MonetScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = MusicFonts.PingFang,
                    maxLines = 1,
                )
            }
        }
        if (removable && onRemove != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onRemove,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Md3Icon("close", size = 20.sp, color = MonetScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SongRowFromMap(
    song: Map<String, Any>,
    onClick: () -> Unit,
    mod: Modifier = Modifier,
    highlighted: Boolean = false,
    removable: Boolean = false,
    tag: String = "",
    onRemove: (() -> Unit)? = null,
    titleKey: String = "name",
) {
    SongRow(
        title = mapStr(song, titleKey, mapStr(song, "title")),
        artist = mapStr(song, "artist"),
        coverThumbPath = mapStr(song, "coverThumbPath"),
        coverUrl = mapStr(song, "coverUrl", mapStr(song, "coverLocalPath")),
        tag = tag,
        highlighted = highlighted,
        removable = removable,
        onRemove = onRemove,
        onClick = onClick,
        mod = mod,
    )
}
