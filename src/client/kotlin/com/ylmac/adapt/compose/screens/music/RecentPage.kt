package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3FilledButton
import com.ylmac.adapt.module.misc.music.PlayerController

/** — netease listen history when signed in. */
@Composable
fun RecentPage(
    pc: PlayerController,
    loggedIn: Boolean,
    tick: Int,
    onRequestLogin: () -> Unit,
    mod: Modifier = Modifier,
) {
    val songs = remember(tick) { peekMaps(pc.recentSongs) }

    Box(mod.fillMaxSize()) {
        if (!loggedIn) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "登录后查看最近播放",
                    color = MonetScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    fontFamily = MusicFonts.PingFang,
                )
                Md3FilledButton(text = "扫码登录", onClick = onRequestLogin)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(songs) { i, song ->
                    SongRowFromMap(
                        song = song,
                        onClick = {
                            try {
                                pc.playRecentSong(i)
                            } catch (_: Throwable) {
                            }
                        },
                    )
                }
            }
        }
    }
}
