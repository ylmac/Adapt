package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.features.music.PlayerController

private data class AlbumInfo(
    val title: String,
    val artist: String,
    val cover: String,
    val trackCount: Int,
)

/** LocalPage.qml — album grid (2-col, 3:2 art, r20) + album detail + sort/group chips. */
@Composable
fun LocalPage(
    pc: PlayerController,
    tick: Int,
    mod: Modifier = Modifier,
) {
    val tracks = remember(tick) { peekMaps(pc.tracks) }
    val libraryCount = numPeek(pc.libraryCount)
    var sortMode by remember { mutableIntStateOf(0) }
    var sortDesc by remember { mutableStateOf(false) }
    var groupMode by remember { mutableIntStateOf(0) }
    var groupValue by remember { mutableStateOf("") }
    var albumIndex by remember { mutableIntStateOf(-1) }
    var valuePickerOpen by remember { mutableStateOf(false) }

    val albums = remember(tracks) { buildAlbums(tracks) }

    Box(mod.fillMaxSize()) {
        if (libraryCount == 0 && tracks.isEmpty()) {
            Text(
                text = "未找到本地音乐\n可在设置 → 本地中修改音乐目录",
                color = MonetScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontFamily = MusicFonts.PingFang,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (albumIndex >= 0 && albumIndex < albums.size) {
            AlbumDetail(
                title = albums[albumIndex].title,
                tracks = tracks.filter {
                    mapStr(it, "album", "未知专辑") == albums[albumIndex].title &&
                        mapStr(it, "artist", "未知艺术家") == albums[albumIndex].artist
                },
                allTracks = tracks,
                pc = pc,
                onBack = { albumIndex = -1 },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                SortGroupBar(
                    sortMode = sortMode,
                    sortDesc = sortDesc,
                    groupMode = groupMode,
                    groupValue = groupValue,
                    onSortMode = { sortMode = it },
                    onToggleDesc = { sortDesc = !sortDesc },
                    onGroupMode = {
                        groupMode = it
                        groupValue = ""
                    },
                    onOpenPicker = { valuePickerOpen = true },
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(albums) { i, album ->
                        AlbumCard(
                            album = album,
                            onClick = { albumIndex = i },
                        )
                    }
                }
            }
        }

        if (valuePickerOpen) {
            ValuePickerOverlay(
                title = if (groupMode == 1) "选择艺术家" else "选择文件夹",
                values = groupValues(tracks, groupMode),
                onPick = {
                    groupValue = it
                    valuePickerOpen = false
                },
                onDismiss = { valuePickerOpen = false },
            )
        }
    }
}

@Composable
private fun SortGroupBar(
    sortMode: Int,
    sortDesc: Boolean,
    groupMode: Int,
    groupValue: String,
    onSortMode: (Int) -> Unit,
    onToggleDesc: () -> Unit,
    onGroupMode: (Int) -> Unit,
    onOpenPicker: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("排序", color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
            listOf("默认", "标题", "艺术家", "时长").forEachIndexed { i, label ->
                Chip(label, active = i == sortMode) { onSortMode(i) }
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clickable(
                        enabled = sortMode != 0,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onToggleDesc,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Md3Icon(
                    if (sortDesc) "arrow_downward" else "arrow_upward",
                    size = 20.sp,
                    color = if (sortMode != 0) MonetScheme.onSurfaceVariant else MonetScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("分组", color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
            listOf("全部", "按艺术家", "按文件夹").forEachIndexed { i, label ->
                Chip(label, active = i == groupMode) { onGroupMode(i) }
            }
            if (groupMode != 0) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MonetScheme.surfaceContainerHighest)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onOpenPicker,
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        groupValue.ifBlank { "全部" },
                        color = MonetScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = MusicFonts.PingFang,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) MonetScheme.primary else MonetScheme.surfaceContainerHighest)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) MonetScheme.onPrimary else MonetScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = MusicFonts.PingFang,
        )
    }
}

@Composable
private fun AlbumCard(album: AlbumInfo, onClick: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MonetScheme.surfaceContainerHighest)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MonetScheme.primaryContainer),
            ) {
                if (album.cover.isNotBlank()) {
                    CoverImage(
                        source = album.cover,
                        radius = 0.dp,
                        icon = "album",
                        iconSize = 32,
                        mod = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, MonetScheme.primaryContainer),
                                ),
                            ),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Md3Icon("album", size = 32.sp, color = MonetScheme.onPrimaryContainer)
                    }
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    album.title,
                    color = MonetScheme.onPrimaryContainer,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = MusicFonts.PingFang,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    album.artist,
                    color = MonetScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontFamily = MusicFonts.PingFang,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${album.trackCount} 首歌曲",
                    color = MonetScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontFamily = MusicFonts.PingFang,
                )
            }
        }
    }
}

@Composable
private fun AlbumDetail(
    title: String,
    tracks: List<Map<String, Any>>,
    allTracks: List<Map<String, Any>>,
    pc: PlayerController,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
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
                title,
                Modifier.weight(1f),
                color = MonetScheme.onSurface,
                fontSize = 22.sp,
                fontFamily = MusicFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks) { _, song ->
                SongRowFromMap(
                    song = song,
                    titleKey = "title",
                    onClick = {
                        val path = mapStr(song, "filePath")
                        val idx = allTracks.indexOfFirst { mapStr(it, "filePath") == path }
                        if (idx >= 0) {
                            try {
                                pc.play(idx)
                            } catch (_: Throwable) {
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ValuePickerOverlay(
    title: String,
    values: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(q, values) {
        val needle = q.lowercase()
        if (needle.isEmpty()) values else values.filter { it.lowercase().contains(needle) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(340.dp)
                .height(420.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MonetScheme.surfaceContainerHigh)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(16.dp),
        ) {
            Text(title, color = MonetScheme.onSurface, fontSize = 18.sp, fontFamily = MusicFonts.PingFang)
            Spacer(Modifier.height(12.dp))
            com.ylmac.adapt.compose.ui.Md3TextField(value = q, onValueChange = { q = it }, label = "搜索")
            Spacer(Modifier.height(8.dp))
            PickerRow("全部") { onPick("") }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(filtered) { _, v ->
                    PickerRow(v) { onPick(v) }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "取消",
                    Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onDismiss,
                        )
                        .padding(8.dp),
                    color = MonetScheme.primary,
                    fontFamily = MusicFonts.PingFang,
                )
            }
        }
    }
}

@Composable
private fun PickerRow(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, color = MonetScheme.onSurface, fontSize = 14.sp, fontFamily = MusicFonts.PingFang, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun buildAlbums(tracks: List<Map<String, Any>>): List<AlbumInfo> {
    val map = linkedMapOf<String, AlbumInfo>()
    for (t in tracks) {
        val title = mapStr(t, "album", "未知专辑")
        val artist = mapStr(t, "artist", "未知艺术家")
        val key = "$title|$artist"
        val existing = map[key]
        if (existing == null) {
            val cover = mapStr(t, "coverLocalPath")
                .ifBlank { mapStr(t, "coverThumbPath") }
                .ifBlank { mapStr(t, "coverUrl") }
            map[key] = AlbumInfo(title, artist, cover, 1)
        } else {
            map[key] = existing.copy(trackCount = existing.trackCount + 1)
        }
    }
    // fill covers for albums that started empty
    for ((key, album) in map.toList()) {
        if (album.cover.isNotBlank()) continue
        val parts = key.split("|", limit = 2)
        val t = tracks.firstOrNull {
            mapStr(it, "album", "未知专辑") == parts[0] &&
                mapStr(it, "artist", "未知艺术家") == parts.getOrElse(1) { "" }
        } ?: continue
        val cover = mapStr(t, "coverLocalPath")
            .ifBlank { mapStr(t, "coverThumbPath") }
            .ifBlank { mapStr(t, "coverUrl") }
        if (cover.isNotBlank()) map[key] = album.copy(cover = cover)
    }
    return map.values.sortedWith(
        compareBy({ it.title.lowercase() }, { it.artist.lowercase() }),
    )
}

private fun groupValues(tracks: List<Map<String, Any>>, groupMode: Int): List<String> {
    if (groupMode == 0) return emptyList()
    val out = linkedSetOf<String>()
    for (t in tracks) {
        val v = if (groupMode == 1) {
            mapStr(t, "artist", "未知艺术家")
        } else {
            folderOf(mapStr(t, "filePath"))
        }
        if (v.isNotBlank()) out.add(v)
    }
    return out.sorted()
}

private fun folderOf(path: String): String {
    if (path.isBlank()) return ""
    val p = path.replace('\\', '/')
    val idx = p.lastIndexOf('/')
    return if (idx >= 0) p.substring(0, idx) else ""
}
