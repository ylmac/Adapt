package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
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
import com.ylmac.adapt.features.music.PlayerController

/** AccountPage.qml — profile / stats / logout. */
@Composable
fun AccountPage(
    pc: PlayerController,
    tick: Int,
    onBack: () -> Unit,
    mod: Modifier = Modifier,
) {
    val userName = strPeek(pc.userName)
    val avatar = strPeek(pc.userAvatar)
    val vip = numPeek(pc.userVipType)
    val level = numPeek(pc.userLevel)
    val signature = strPeek(pc.userSignature)
    val playlistCount = numPeek(pc.playlistCount)
    val likedCount = numPeek(pc.likedCount)
    var logoutOpen by remember { mutableStateOf(false) }

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
            IconTap("arrow_back", onClick = onBack)
            Text("账户", Modifier.weight(1f), color = MonetScheme.onSurface, fontSize = 22.sp, fontFamily = MusicFonts.PingFang)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MonetScheme.surfaceContainerHighest)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(72.dp)) {
                    CoverImage(
                        source = avatar,
                        radius = 36.dp,
                        icon = "account_circle",
                        iconSize = 44,
                        mod = Modifier.size(72.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        userName.ifBlank { "网易云用户" },
                        color = MonetScheme.onSurface,
                        fontSize = 16.sp,
                        fontFamily = MusicFonts.PingFang,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (vip > 0) {
                            Pill("黑胶 VIP", MonetScheme.tertiaryContainer, MonetScheme.onTertiaryContainer)
                        }
                        Pill("Lv.$level", MonetScheme.secondaryContainer, MonetScheme.onSecondaryContainer)
                    }
                    if (signature.isNotBlank()) {
                        Text(signature, color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MonetScheme.surfaceContainerHighest)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatCol(Modifier.weight(1f), "$playlistCount", "歌单")
                Box(
                    Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .background(MonetScheme.outlineVariant),
                )
                StatCol(Modifier.weight(1f), "$likedCount", "红心")
            }

            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MonetScheme.surfaceContainerLow)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { logoutOpen = true },
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Md3Icon("logout", size = 20.sp, color = MonetScheme.onSurface)
                    Text("退出登录", color = MonetScheme.onSurface, fontFamily = MusicFonts.PingFang)
                }
            }
        }
    }

    if (logoutOpen) {
        Dialog(onDismissRequest = { logoutOpen = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MonetScheme.surfaceContainerHigh)
                    .padding(24.dp),
            ) {
                RowTitle(icon = "logout", title = "退出登录")
                Spacer(Modifier.height(8.dp))
                Text("确定要退出当前账户吗?", color = MonetScheme.onSurfaceVariant, fontFamily = MusicFonts.PingFang)
                Spacer(Modifier.height(16.dp))
                RowActions(
                    accept = "退出",
                    reject = "取消",
                    onAccept = {
                        try {
                            pc.logout()
                        } catch (_: Throwable) {
                        }
                        logoutOpen = false
                        onBack()
                    },
                    onReject = { logoutOpen = false },
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, color = fg, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
    }
}

@Composable
private fun StatCol(mod: Modifier, value: String, label: String) {
    Column(mod, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = MonetScheme.onSurface, fontSize = 22.sp, fontFamily = MusicFonts.PingFang)
        Text(label, color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
    }
}
