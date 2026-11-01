package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3FilledButton
import com.ylmac.adapt.compose.ui.Md3LoadingIndicator
import com.ylmac.adapt.features.music.PlayerController
import kotlin.math.floor
import kotlin.math.max

/** LibraryPage.qml — my playlists grid + FAB create + login CTA. */
@Composable
fun LibraryPage(
    pc: PlayerController,
    loggedIn: Boolean,
    tick: Int,
    onOpenPlaylist: (Any, String) -> Unit,
    onRequestLogin: () -> Unit,
    mod: Modifier = Modifier,
) {
    val lists = remember(tick) { peekMaps(pc.myPlaylists) }
    val loading = boolPeek(pc.playlistLoading)
    var createOpen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Box(mod.fillMaxSize()) {
        if (!loggedIn) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "登录后查看你的歌单",
                    color = MonetScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    fontFamily = MusicFonts.PingFang,
                )
                Md3FilledButton(text = "扫码登录", onClick = onRequestLogin)
            }
        } else {
            if (loading && lists.isEmpty()) {
                RowCenterLoading()
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pad = 12.dp
                val gap = 12.dp
                val minTile = 160.dp
                val cols = max(2, floor((maxWidth - pad * 2 + gap) / (minTile + gap)).toInt())
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(pad),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    itemsIndexed(lists) { _, pl ->
                        PlaylistCard(
                            name = mapStr(pl, "name", "歌单"),
                            count = mapInt(pl, "trackCount"),
                            coverUrl = mapStr(pl, "coverUrl"),
                            coverThumbPath = mapStr(pl, "coverThumbPath"),
                            onClick = { onOpenPlaylist(mapId(pl), mapStr(pl, "name", "歌单")) },
                        )
                    }
                }
            }

            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MonetScheme.primaryContainer)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            name = ""
                            createOpen = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Md3Icon("add", size = 24.sp, color = MonetScheme.onPrimaryContainer)
            }
        }
    }

    if (createOpen) {
        Dialog(onDismissRequest = { createOpen = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                    .background(MonetScheme.surfaceContainerHigh)
                    .padding(24.dp),
            ) {
                RowTitle(icon = "playlist_add", title = "新建歌单")
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("歌单名称", color = MonetScheme.onSurfaceVariant) },
                    singleLine = true,
                    colors = TextFieldDefaults.textFieldColors(
                        textColor = MonetScheme.onSurface,
                        backgroundColor = MonetScheme.surfaceContainerLow,
                        cursorColor = MonetScheme.primary,
                        focusedIndicatorColor = MonetScheme.primary,
                        unfocusedIndicatorColor = MonetScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    RowActions(
                        accept = "创建",
                        reject = "取消",
                        onAccept = {
                            try {
                                pc.createPlaylist(name)
                            } catch (_: Throwable) {
                            }
                            createOpen = false
                        },
                        onReject = { createOpen = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowCenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Md3LoadingIndicator()
    }
}

@Composable
internal fun RowTitle(icon: String, title: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Md3Icon(icon, size = 24.sp, color = MonetScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(title, color = MonetScheme.onSurface, fontSize = 20.sp, fontFamily = MusicFonts.PingFang)
    }
}

@Composable
internal fun RowActions(
    accept: String,
    reject: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Md3FilledButton(text = reject, onClick = onReject)
        Md3FilledButton(text = accept, onClick = onAccept)
    }
}
