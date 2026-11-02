package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
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
import com.ylmac.adapt.compose.ui.Md3TextField
import com.ylmac.adapt.module.misc.music.PlayerController

/** — create / join / leave room. */
@Composable
fun ListenTogetherDialog(
    open: Boolean,
    pc: PlayerController,
    tick: Int,
    onClosed: () -> Unit,
) {
    if (!open) return

    val inRoom = boolPeek(pc.listenTogetherInRoom)
    val busy = boolPeek(pc.listenTogetherBusy)
    val loggedIn = boolPeek(pc.loggedIn)
    val status = strPeek(pc.listenTogetherStatusText)
    val roomId = strPeek(pc.listenTogetherRoomId)
    val members = strPeek(pc.listenTogetherMembers)
    var invitation by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onClosed) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MonetScheme.surfaceContainerHigh)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowTitle(icon = "group", title = "网易云一起听")
            Text(
                text = when {
                    inRoom -> status
                    loggedIn -> "创建房间，或粘贴网易云一起听邀请链接加入。"
                    else -> "登录网易云账号后才能使用一起听。"
                },
                color = MonetScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontFamily = MusicFonts.PingFang,
            )
            if (inRoom) {
                Text("房间 $roomId", color = MonetScheme.onSurface, fontSize = 14.sp, fontFamily = MusicFonts.PingFang)
                Text(members, color = MonetScheme.onSurfaceVariant, fontSize = 13.sp, fontFamily = MusicFonts.PingFang)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Md3FilledButton(
                        text = "复制邀请",
                        onClick = {
                            try {
                                pc.copyListenTogetherInvitation()
                            } catch (_: Throwable) {
                            }
                        },
                        mod = Modifier.weight(1f),
                    )
                    Md3FilledButton(
                        text = "退出房间",
                        onClick = {
                            try {
                                pc.leaveListenTogether()
                            } catch (_: Throwable) {
                            }
                        },
                        mod = Modifier.weight(1f),
                    )
                }
            } else if (loggedIn) {
                Md3FilledButton(
                    text = if (busy) "正在创建…" else "创建房间",
                    onClick = {
                        if (!busy) {
                            try {
                                pc.createListenTogetherRoom()
                            } catch (_: Throwable) {
                            }
                        }
                    },
                    mod = Modifier.fillMaxWidth(),
                )
                Text(
                    "或",
                    Modifier.align(Alignment.CenterHorizontally),
                    color = MonetScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontFamily = MusicFonts.PingFang,
                )
                Md3TextField(value = invitation, onValueChange = { invitation = it }, label = "邀请链接")
                Md3FilledButton(
                    text = if (busy) "正在加入…" else "加入房间",
                    onClick = {
                        if (!busy && invitation.isNotBlank()) {
                            try {
                                pc.joinListenTogether(invitation)
                            } catch (_: Throwable) {
                            }
                        }
                    },
                    mod = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Md3FilledButton(text = "关闭", onClick = onClosed, mod = Modifier.align(Alignment.End))
        }
    }
}
