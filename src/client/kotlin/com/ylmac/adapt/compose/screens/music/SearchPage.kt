package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3FilledButton
import com.ylmac.adapt.compose.ui.Md3LoadingIndicator
import com.ylmac.adapt.compose.ui.Md3TextField
import com.ylmac.adapt.features.music.PlayerController
import kotlinx.coroutines.delay
import kotlin.math.min

/** SearchPage.qml — 350ms debounce, history expand levels, hot searches, searchRows. */
@Composable
fun SearchPage(
    pc: PlayerController,
    tick: Int,
    mod: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var historyExpandLevel by remember { mutableIntStateOf(0) }
    var skipDebounce by remember { mutableStateOf(false) }

    val history = remember(tick) { peekStrings(pc.searchHistory) }
    val hot = remember(tick) { peekStrings(pc.hotSearches) }
    val rows = remember(tick) { peekSearchRows(pc) }
    val searching = boolPeek(pc.searchLoading)
    val hasMore = boolPeek(pc.searchHasMore)

    fun runSearch(addHistory: Boolean) {
        val text = query
        if (text.isEmpty()) return
        try {
            pc.search(text)
            pc.searchLocal(text)
            pc.searchCustom(text)
            if (addHistory) pc.addSearchHistory(text)
        } catch (_: Throwable) {
        }
    }

    fun runSearchNow(addHistory: Boolean) {
        skipDebounce = true
        runSearch(addHistory)
    }

    LaunchedEffect(query) {
        if (query.isEmpty()) {
            historyExpandLevel = 0
            skipDebounce = false
            return@LaunchedEffect
        }
        if (skipDebounce) {
            skipDebounce = false
            return@LaunchedEffect
        }
        delay(350)
        runSearch(false)
    }

    Column(mod.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Md3TextField(
                    value = query,
                    onValueChange = {
                        query = it
                        skipDebounce = false
                        try {
                            pc.prepareSearch(it)
                        } catch (_: Throwable) {
                        }
                    },
                    label = "搜索歌曲",
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MonetScheme.primary)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { if (query.isNotEmpty()) runSearchNow(true) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Md3Icon("search", size = 22.sp, color = MonetScheme.onPrimary)
            }
        }

        if (query.isEmpty()) {
            HistoryHotArea(
                history = history,
                hot = hot,
                expandLevel = historyExpandLevel,
                onExpand = { historyExpandLevel = it },
                onClearHistory = {
                    try {
                        pc.clearSearchHistory()
                    } catch (_: Throwable) {
                    }
                },
                onRemoveHistory = { i ->
                    try {
                        pc.removeSearchHistory(i)
                    } catch (_: Throwable) {
                    }
                },
                onPick = { kw ->
                    query = kw
                    try {
                        pc.prepareSearch(kw)
                    } catch (_: Throwable) {
                    }
                    runSearchNow(true)
                },
            )
        } else {
            if (searching) {
                Row(
                    Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Md3LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("搜索中…", color = MonetScheme.onSurfaceVariant, fontFamily = MusicFonts.PingFang)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                flingBehavior = com.ylmac.adapt.compose.ui.rememberAdaptFlingBehavior(),
            ) {
                itemsIndexed(rows) { i, row ->
                    SongRow(
                        title = row.name ?: "",
                        artist = row.artist ?: "",
                        coverThumbPath = row.coverThumbPath ?: "",
                        coverUrl = row.coverUrl ?: "",
                        tag = row.kindLabel ?: "",
                        onClick = {
                            try {
                                pc.playSearchRow(i)
                            } catch (_: Throwable) {
                            }
                        },
                    )
                }
                if (hasMore && !searching) {
                    item {
                        Md3FilledButton(
                            text = "加载更多",
                            onClick = {
                                try {
                                    pc.loadMoreSearch()
                                } catch (_: Throwable) {
                                }
                            },
                            mod = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                }
                if (!searching && rows.isEmpty()) {
                    item {
                        Text(
                            "无结果",
                            color = MonetScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                            fontFamily = MusicFonts.PingFang,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryHotArea(
    history: List<String>,
    hot: List<String>,
    expandLevel: Int,
    onExpand: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistory: (Int) -> Unit,
    onPick: (String) -> Unit,
) {
    val collapsed = 5
    val first = 30
    val second = 70
    val full = 100
    val displayCount = when {
        history.isEmpty() -> 0
        expandLevel == 0 -> min(collapsed, history.size)
        expandLevel == 1 -> min(first, history.size)
        expandLevel == 2 -> min(second, history.size)
        else -> min(full, history.size)
    }
    val showToggle = history.size > collapsed
    val canExpandMore = when (expandLevel) {
        0 -> history.size > collapsed
        1 -> history.size > first
        2 -> history.size > second
        else -> false
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (history.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "搜索历史",
                        Modifier.weight(1f),
                        color = MonetScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = MusicFonts.PingFang,
                    )
                    Box(
                        Modifier
                            .size(40.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onClearHistory,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Md3Icon("delete_sweep", size = 22.sp, color = MonetScheme.onSurfaceVariant)
                    }
                }
            }
            items(displayCount) { index ->
                HistoryRow(
                    text = history[index],
                    onOpen = { onPick(history[index]) },
                    onRemove = { onRemoveHistory(index) },
                )
            }
            if (showToggle) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (expandLevel >= 1) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = { onExpand(0) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("收起", color = MonetScheme.primary, fontSize = 14.sp, fontFamily = MusicFonts.PingFang)
                            }
                        }
                        if (expandLevel <= 2 && canExpandMore) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = {
                                            onExpand(
                                                when (expandLevel) {
                                                    0 -> 1
                                                    1 -> 2
                                                    else -> 3
                                                },
                                            )
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("展开更多", color = MonetScheme.primary, fontSize = 14.sp, fontFamily = MusicFonts.PingFang)
                            }
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty() && hot.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MonetScheme.outlineVariant),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        if (hot.isNotEmpty()) {
            item {
                Text(
                    "热门搜索",
                    color = MonetScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = MusicFonts.PingFang,
                    modifier = Modifier.height(40.dp),
                )
            }
            itemsIndexed(hot) { index, kw ->
                HotRow(index = index, keyword = kw, onClick = { onPick(kw) })
            }
        }
    }
}

@Composable
private fun HistoryRow(text: String, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MonetScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onOpen,
                )
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MonetScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Md3Icon("history", size = 18.sp, color = MonetScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                color = MonetScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = MusicFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier
                .width(1.dp)
                .height(20.dp)
                .background(MonetScheme.outlineVariant.copy(alpha = 0.75f)),
        )
        Box(
            Modifier
                .size(44.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Md3Icon("close", size = 18.sp, color = MonetScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HotRow(index: Int, keyword: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MonetScheme.surfaceContainerLow)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (index < 3) MonetScheme.primaryContainer
                    else MonetScheme.surfaceContainerHighest,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${index + 1}",
                color = if (index < 3) MonetScheme.onPrimaryContainer else MonetScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = MusicFonts.PingFang,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            keyword,
            Modifier.weight(1f),
            color = MonetScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = MusicFonts.PingFang,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Md3Icon("arrow_outward", size = 18.sp, color = MonetScheme.onSurfaceVariant)
    }
}
