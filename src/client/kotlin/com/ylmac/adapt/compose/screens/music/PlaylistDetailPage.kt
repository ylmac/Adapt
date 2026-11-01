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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3LoadingIndicator
import com.ylmac.adapt.features.music.PlayerController

/** PlaylistDetailPage.qml — back/title + 心动推荐/bookmark/cover/delete + tracks. */
@Composable
fun PlaylistDetailPage(
    pc: PlayerController,
    tick: Int,
    loggedIn: Boolean,
    onBack: () -> Unit,
    mod: Modifier = Modifier,
) {
    val tracks = remember(tick) { peekMaps(pc.playlistTracks) }
    val title = strPeek(pc.playlistTitle, "歌单")
    val loading = boolPeek(pc.playlistLoading)
    val owned = boolPeek(pc.playlistOwned)
    val subscribed = boolPeek(pc.playlistSubscribed)
    val intelligenceLoading = boolPeek(pc.intelligenceLoading)
    val openId = try {
        (pc.openPlaylistId.peek() as? Number)?.toLong() ?: 0L
    } catch (_: Throwable) {
        0L
    }
    // playlistDeletable not exposed on PC — approximate: owned and not first "liked".
    val deletable = owned && openId != 0L
    var deleteOpen by remember { mutableStateOf(false) }
    var coverOpen by remember { mutableStateOf(false) }
    var coverPath by remember { mutableStateOf("") }

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
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTap("arrow_back", onClick = onBack)
            Text(
                title,
                Modifier.weight(1f),
                color = MonetScheme.onSurface,
                fontSize = 22.sp,
                fontFamily = MusicFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (loggedIn && !loading && tracks.isNotEmpty()) {
                Text(
                    text = if (intelligenceLoading) "推荐中…" else "心动推荐",
                    color = if (intelligenceLoading) MonetScheme.onSurfaceVariant else MonetScheme.primary,
                    fontSize = 13.sp,
                    fontFamily = MusicFonts.PingFang,
                    modifier = Modifier
                        .clickable(
                            enabled = !intelligenceLoading,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {
                                try {
                                    pc.startIntelligenceMode(openId)
                                } catch (_: Throwable) {
                                }
                            },
                        )
                        .padding(horizontal = 8.dp),
                )
                Md3Icon("favorite", size = 18.sp, color = MonetScheme.primary)
                Spacer(Modifier.width(4.dp))
            }
            if (loggedIn && !loading && !owned) {
                IconTap(
                    if (subscribed) "bookmark" else "bookmark_border",
                    color = if (subscribed) MonetScheme.primary else MonetScheme.onSurface,
                ) {
                    try {
                        pc.togglePlaylistSubscribe()
                    } catch (_: Throwable) {
                    }
                }
            }
            if (loggedIn && !loading && owned) {
                IconTap("image") {
                    coverPath = ""
                    coverOpen = true
                }
            }
            if (loggedIn && !loading && deletable) {
                IconTap("delete") { deleteOpen = true }
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (loading) {
                Md3LoadingIndicator(Modifier.align(Alignment.Center).size(56.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(tracks) { i, song ->
                        SongRowFromMap(
                            song = song,
                            onClick = {
                                try {
                                    pc.playPlaylistTrack(i)
                                } catch (_: Throwable) {
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (deleteOpen) {
        Dialog(onDismissRequest = { deleteOpen = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MonetScheme.surfaceContainerHigh)
                    .padding(24.dp),
            ) {
                RowTitle(icon = "delete", title = "删除歌单")
                Spacer(Modifier.height(8.dp))
                Text(
                    "确定删除歌单「$title」吗？此操作无法撤销。",
                    color = MonetScheme.onSurfaceVariant,
                    fontFamily = MusicFonts.PingFang,
                )
                Spacer(Modifier.height(16.dp))
                RowActions(
                    accept = "删除",
                    reject = "取消",
                    onAccept = {
                        try {
                            pc.deletePlaylist(openId)
                        } catch (_: Throwable) {
                        }
                        deleteOpen = false
                        onBack()
                    },
                    onReject = { deleteOpen = false },
                )
            }
        }
    }

    if (coverOpen) {
        Dialog(onDismissRequest = { coverOpen = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MonetScheme.surfaceContainerHigh)
                    .padding(24.dp),
            ) {
                RowTitle(icon = "image", title = "更换歌单封面")
                Spacer(Modifier.height(12.dp))
                CoverImage(
                    source = coverPath.trim(),
                    radius = 12.dp,
                    icon = "image",
                    iconSize = 40,
                    mod = Modifier
                        .size(160.dp)
                        .align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = coverPath,
                    onValueChange = { coverPath = it },
                    label = { Text("图片文件路径", color = MonetScheme.onSurfaceVariant) },
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
                RowActions(
                    accept = "应用",
                    reject = "取消",
                    onAccept = {
                        if (coverPath.trim().isNotEmpty()) {
                            try {
                                pc.setPlaylistCover(openId, coverPath.trim())
                            } catch (_: Throwable) {
                            }
                        }
                        coverOpen = false
                    },
                    onReject = { coverOpen = false },
                )
            }
        }
    }
}

@Composable
internal fun IconTap(
    name: String,
    color: androidx.compose.ui.graphics.Color = MonetScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Md3Icon(name, size = 22.sp, color = color)
    }
}
