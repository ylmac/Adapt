package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.features.music.PlayerController
import kotlin.math.floor
import kotlin.math.max

/** HomePage.qml — greeting + recommend playlist grid + 每日推荐 SongRows. */
@Composable
fun HomePage(
    pc: PlayerController,
    loggedIn: Boolean,
    userName: String,
    tick: Int,
    onOpenPlaylist: (Any, String) -> Unit,
    mod: Modifier = Modifier,
) {
    val playlists = remember(tick) { peekMaps(pc.recommendPlaylists) }
    val daily = remember(tick) { peekMaps(pc.recommendations) }

    Box(mod.fillMaxSize()) {
        if (playlists.isEmpty() && daily.isEmpty()) {
            Text(
                text = "加载中…",
                color = MonetScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontFamily = MusicFonts.PingFang,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pad = 12.dp
            val gap = 12.dp
            val minTile = 200.dp
            val cols = max(2, floor((maxWidth - pad * 2 + gap) / (minTile + gap)).toInt())
            val tile = (maxWidth - pad * 2 - gap * (cols - 1)) / cols

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                flingBehavior = com.ylmac.adapt.compose.ui.rememberAdaptFlingBehavior(),
            ) {
                item {
                    Text(
                        text = greeting() + if (loggedIn) "，$userName" else "",
                        color = MonetScheme.onSurface,
                        fontSize = 26.sp,
                        fontFamily = MusicFonts.PingFang,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .height(64.dp)
                            .fillMaxWidth(),
                    )
                }
                items((playlists.size + cols - 1) / cols) { rowIdx ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = pad),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        repeat(cols) { col ->
                            val i = rowIdx * cols + col
                            if (i < playlists.size) {
                                val pl = playlists[i]
                                PlaylistCard(
                                    name = mapStr(pl, "name", "歌单"),
                                    count = mapInt(pl, "trackCount"),
                                    coverUrl = mapStr(pl, "coverUrl"),
                                    coverThumbPath = mapStr(pl, "coverThumbPath"),
                                    onClick = {
                                        onOpenPlaylist(mapId(pl), mapStr(pl, "name", "歌单"))
                                    },
                                    mod = Modifier.width(tile),
                                )
                            } else {
                                Spacer(Modifier.width(tile))
                            }
                        }
                    }
                    Spacer(Modifier.height(gap))
                }
                if (daily.isNotEmpty()) {
                    item {
                        Text(
                            text = "每日推荐",
                            color = MonetScheme.primary,
                            fontSize = 18.sp,
                            fontFamily = MusicFonts.PingFang,
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .height(40.dp),
                        )
                    }
                    itemsIndexed(daily) { i, song ->
                        SongRowFromMap(
                            song = song,
                            onClick = {
                                try {
                                    pc.playRecommendation(i)
                                } catch (_: Throwable) {
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
