package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3SegmentedButton
import com.ylmac.adapt.module.misc.music.PlayerController
// Md3SegmentedButton: SegmentedButton (primary + check)

/** — segmented 播放队列 / 播放列表, removable rows. */
@Composable
fun QueuePage(
    pc: PlayerController,
    tick: Int,
    onBack: () -> Unit,
    mod: Modifier = Modifier,
) {
    var showCustom by remember { mutableStateOf(false) }
    val queue = remember(tick) { peekMaps(pc.queueTracks) }
    val custom = remember(tick) { peekMaps(pc.customPlaylistTracks) }
    val current = numPeek(pc.index, -1)
    val list = if (showCustom) custom else queue

    Column(
        mod
            .fillMaxSize()
            .background(MonetScheme.surface)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Md3Icon("arrow_back", size = 22.sp, color = MonetScheme.onSurfaceVariant)
            }
            Text(
                text = if (showCustom) "播放列表 (${custom.size})" else "播放队列 (${queue.size})",
                modifier = Modifier.weight(1f),
                color = MonetScheme.onSurface,
                fontSize = 22.sp,
                fontFamily = MusicFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        QueueSegmented(
            showCustom = showCustom,
            onSelect = { showCustom = it },
            mod = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            flingBehavior = com.ylmac.adapt.compose.ui.rememberAdaptFlingBehavior(),
        ) {
            itemsIndexed(list) { i, song ->
                SongRowFromMap(
                    song = song,
                    titleKey = "name",
                    highlighted = !showCustom && i == current,
                    removable = true,
                    onRemove = {
                        try {
                            if (showCustom) pc.removeFromCustomPlaylistIndex(i)
                            else pc.removeFromQueue(i)
                        } catch (_: Throwable) {
                        }
                    },
                    onClick = {
                        try {
                            if (showCustom) pc.playCustomPlaylistIndex(i)
                            else pc.playQueueIndex(i)
                        } catch (_: Throwable) {
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun QueueSegmented(
    showCustom: Boolean,
    onSelect: (Boolean) -> Unit,
    mod: Modifier = Modifier,
) {
    Md3SegmentedButton(
        options = listOf("播放队列", "播放列表"),
        selectedIndex = if (showCustom) 1 else 0,
        onSelect = { onSelect(it == 1) },
        mod = mod,
    )
}
