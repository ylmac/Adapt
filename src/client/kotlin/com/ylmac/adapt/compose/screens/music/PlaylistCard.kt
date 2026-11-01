package com.ylmac.adapt.compose.screens.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.retainedLayer

/** QML PlaylistCard.qml — cover (tile−8) r14 icon queue_music 44, name 14, count 12. */
@Composable
fun PlaylistCard(
    name: String,
    count: Int,
    coverUrl: String = "",
    coverThumbPath: String = "",
    onClick: () -> Unit,
    mod: Modifier = Modifier,
) {
    Column(
        mod
            .retainedLayer()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(4.dp),
    ) {
        CoverImage(
            source = coverUrl.ifBlank { coverThumbPath },
            radius = 14.dp,
            icon = "queue_music",
            iconSize = 44,
            mod = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            color = MonetScheme.onSurface,
            fontSize = 14.sp,
            fontFamily = MusicFonts.PingFang,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (count > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$count 首",
                color = MonetScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = MusicFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
