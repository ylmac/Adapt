package com.ylmac.adapt.compose.screens.clickgui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.screens.music.decodeCover
import com.ylmac.adapt.compose.theme.AdaptFonts
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3ScrollRail
import com.ylmac.adapt.compose.ui.Md3StandardIconButton
import com.ylmac.adapt.compose.ui.Md3Switch
import com.ylmac.adapt.compose.ui.Md3TextField
import com.ylmac.adapt.features.cosmetic.CosmeticLibrary
import com.ylmac.adapt.features.module.impl.render.CosmeticsModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val KINDS = listOf("cape" to "披风", "wings" to "翅膀", "hat" to "帽子")

/**
 * 饰品单独一页：左边是包里的名字，右边是这一件的图。点一行就穿上，
 * 面板是透的，转到第三人称能看到身上的样子。
 */
@Composable
fun CosmeticsPage(
    enabled: Boolean,
    onBack: () -> Unit,
    onToggle: () -> Unit,
) {
    val module = CosmeticsModule.INSTANCE
    var kind by remember { mutableStateOf("cape") }
    var query by remember { mutableStateOf("") }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf(module?.cape().orEmpty().let { if (it == "none") "" else it }) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(kind) {
        names = withContext(Dispatchers.IO) { CosmeticLibrary.names(kind) }
        val equipped = when (kind) {
            "wings" -> module?.wings()
            "hat" -> module?.hat()
            else -> module?.cape()
        }.orEmpty()
        selected = if (equipped == "none") "" else equipped
        query = ""
    }
    LaunchedEffect(kind, selected) {
        // 解码必须留在 IO。performFrame 之前已经取过 Skia 画布，
        // 回到渲染线程再 makeFromEncoded 会重入同一把锁，整帧卡死。
        preview = withContext(Dispatchers.IO) {
            if (selected.isEmpty()) null
            else decodeCover(null, CosmeticLibrary.preview(kind, selected))
        }
    }

    fun equip(name: String) {
        val target = module ?: return
        when (kind) {
            "wings" -> target.equipWings(name)
            "hat" -> target.equipHat(name)
            else -> target.equipCape(name)
        }
        selected = name
        if (name.isNotEmpty() && !target.isEnabled) onToggle()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Md3StandardIconButton(onClick = onBack, icon = "arrow_back")
            Spacer(Modifier.width(8.dp))
            Text(
                "饰品",
                color = MonetScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = AdaptFonts.PingFang,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "启用模块",
                color = MonetScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontFamily = AdaptFonts.PingFang,
            )
            Spacer(Modifier.width(8.dp))
            Md3Switch(checked = enabled, onCheckedChange = { onToggle() })
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KINDS.forEach { (id, title) ->
                val on = kind == id
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) MonetScheme.primaryContainer else MonetScheme.surfaceContainerHigh)
                        .clickable { kind = id },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title,
                        color = if (on) MonetScheme.onPrimaryContainer else MonetScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontFamily = AdaptFonts.PingFang,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Md3TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索",
            mod = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "点一行就穿上。面板后面的人物是你自己，第三人称能看到效果。",
            color = MonetScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = AdaptFonts.PingFang,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shown = names.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            val listState = rememberLazyListState()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MonetScheme.surfaceContainerHigh.copy(alpha = 0.55f)),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 16.dp),
                ) {
                    item(key = "none") {
                        CosmeticRow("不装备", selected.isEmpty()) { equip("") }
                    }
                    items(shown, key = { it }) { name ->
                        CosmeticRow(name, name == selected) { equip(name) }
                    }
                }
                Md3ScrollRail(
                    listState,
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(vertical = 12.dp),
                    labelAt = { fraction ->
                        if (shown.isEmpty()) "#"
                        else fastGlyph(shown[(fraction * (shown.size - 1)).toInt().coerceIn(0, shown.lastIndex)])
                    },
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MonetScheme.surfaceContainer)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val title = if (selected.isEmpty()) "没有穿" else selected
                Text(
                    title,
                    color = MonetScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = AdaptFonts.PingFang,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MonetScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = preview
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            if (selected.isEmpty()) "选一件看看" else "没有预览图",
                            color = MonetScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontFamily = AdaptFonts.PingFang,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CosmeticRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MonetScheme.primaryContainer else MonetScheme.surfaceContainerLow)
            .then(
                if (selected) Modifier.border(1.dp, MonetScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = if (selected) MonetScheme.onPrimaryContainer else MonetScheme.onSurface,
            fontSize = 14.sp,
            fontFamily = AdaptFonts.PingFang,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun fastGlyph(name: String): String {
    for (ch in name) {
        if (ch.isDigit()) return "#"
        if (ch.isLetter()) return ch.uppercase()
    }
    return "#"
}
