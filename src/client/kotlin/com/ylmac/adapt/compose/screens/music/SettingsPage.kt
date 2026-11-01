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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.window.Dialog
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.retainedLayer
import com.ylmac.adapt.compose.ui.Md3FilledButton
import com.ylmac.adapt.compose.ui.Md3SegmentedDarkMode
import com.ylmac.adapt.compose.ui.Md3Slider
import com.ylmac.adapt.compose.ui.Md3Switch
import com.ylmac.adapt.compose.ui.Md3TextField
import com.ylmac.adapt.config.DesktopSettings
import com.ylmac.adapt.features.music.PlayerController
import com.ylmac.adapt.features.music.customapi.CustomApiConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** SettingsPage.qml shell + every SettingsSection*.qml control. */
@Composable
fun SettingsPage(
    pc: PlayerController,
    settings: DesktopSettings,
    tick: Int,
    onBack: () -> Unit,
    mod: Modifier = Modifier,
) {
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
            Text(
                "设置",
                Modifier.weight(1f),
                color = MonetScheme.onSurface,
                fontSize = 22.sp,
                fontFamily = MusicFonts.PingFang,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsSectionAppearance(settings, tick)
            SettingsSectionPlayback(pc, settings, tick)
            SettingsSectionCustomApi(settings, tick)
            SettingsSectionDesktopLyric(settings, tick)
            SettingsSectionWorldLyric(settings, tick)
            SettingsSectionStorage(pc, settings, tick)
            SettingsSectionLocal(settings, tick)
            SettingsSectionAbout(pc, tick)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = MonetScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = MusicFonts.PingFang,
        modifier = Modifier.padding(start = 20.dp, top = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .retainedLayer()
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MonetScheme.surfaceContainerHighest)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = { content() },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
            }
        }
        Md3Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun StepperRow(
    title: String,
    valueLabel: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.weight(1f), color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        OutlineBtn("−", onMinus)
        Text(valueLabel, color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        OutlineBtn("+", onPlus)
    }
}

@Composable
private fun OutlineBtn(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MonetScheme.surfaceContainerLow)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = MonetScheme.onSurface, fontFamily = MusicFonts.PingFang)
    }
}

@Composable
private fun Segmented(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MonetScheme.surfaceContainerLow),
    ) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (sel) MonetScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { onSelect(i) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (sel) MonetScheme.onSecondaryContainer else MonetScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = MusicFonts.PingFang,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionAppearance(settings: DesktopSettings, tick: Int) {
    var darkMode by remember(tick) { mutableIntStateOf(numPeek(settings.darkMode)) }
    var monet by remember(tick) { mutableStateOf(boolPeek(settings.monetEnabled)) }
    var debug by remember(tick) { mutableStateOf(boolPeek(settings.debugMode)) }
    var monetSource by remember(tick) { mutableIntStateOf(numPeek(settings.monetSource)) }

    SectionTitle("外观")
    SettingsCard {
        Text("深色模式", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        Md3SegmentedDarkMode(
            selected = darkMode,
            onSelect = {
                darkMode = it
                settings.darkMode.set(it)
                try {
                    MonetScheme.refresh()
                } catch (_: Throwable) {
                }
                MonetScheme.refresh()
            },
        )
        SwitchRow(
            title = "莫奈取色",
            subtitle = "按音乐封面取色（主题跟随歌曲封面）",
            checked = monet,
            onChecked = {
                monet = it
                settings.monetEnabled.set(it)
            },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Debug", color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
            Spacer(Modifier.width(12.dp))
            Text(
                if (debug) "调试模式：已显示详细选项" else "",
                Modifier.weight(1f),
                color = MonetScheme.outline,
                fontSize = 12.sp,
                fontFamily = MusicFonts.PingFang,
            )
            Md3Switch(
                checked = debug,
                onCheckedChange = {
                    debug = it
                    settings.debugMode.set(it)
                },
            )
        }
        if (debug) {
            Text("取色来源", color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
            Segmented(listOf("封面", "世界"), monetSource) {
                monetSource = it
                settings.monetSource.set(it)
            }
        }
    }
}

@Composable
private fun SettingsSectionPlayback(pc: PlayerController, settings: DesktopSettings, tick: Int) {
    var unblock by remember(tick) { mutableStateOf(boolPeek(settings.unblockEnabled)) }
    var hq by remember(tick) { mutableStateOf(boolPeek(settings.highQuality)) }
    var mirror by remember(tick) { mutableStateOf(boolPeek(settings.mirrorEnabled)) }
    var volume by remember(tick) {
        mutableFloatStateOf(
            try {
                (pc.volume.peek() as? Number)?.toFloat() ?: 0.8f
            } catch (_: Throwable) {
                0.8f
            },
        )
    }

    SectionTitle("播放")
    SettingsCard {
        SwitchRow("音源解锁", "灰色/VIP/试听歌曲自动尝试其他音源", unblock) {
            unblock = it
            settings.unblockEnabled.set(it)
        }
    }
    SettingsCard {
        SwitchRow("高音质播放", "歌曲链接请求高音质(exhigh)音源", hq) {
            hq = it
            settings.highQuality.set(it)
        }
    }
    SettingsCard {
        SwitchRow("下载加速镜像", "通过 gh-proxy 镜像下载应用更新", mirror) {
            mirror = it
            settings.mirrorEnabled.set(it)
        }
    }
    SettingsCard {
        Row(Modifier.fillMaxWidth()) {
            Text("音量", Modifier.weight(1f), color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
            Text("${(volume * 100).roundToInt()}%", color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
        }
        Md3Slider(
            value = volume,
            onValueChange = {
                volume = it
                try {
                    pc.setVolume(it)
                } catch (_: Throwable) {
                }
            },
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun SettingsSectionCustomApi(settings: DesktopSettings, tick: Int) {
    val cfg = remember(tick) {
        try {
            settings.customApiConfig.peek() ?: CustomApiConfig()
        } catch (_: Throwable) {
            CustomApiConfig()
        }
    }
    var enabled by remember(cfg) { mutableStateOf(cfg.enabled) }
    var name by remember(cfg) { mutableStateOf(cfg.name ?: "") }
    var searchUrl by remember(cfg) { mutableStateOf(cfg.searchUrl ?: "") }
    var urlUrl by remember(cfg) { mutableStateOf(cfg.urlUrl ?: "") }
    var lyricUrl by remember(cfg) { mutableStateOf(cfg.lyricUrl ?: "") }
    var searchListPath by remember(cfg) { mutableStateOf(cfg.searchListPath ?: "") }
    var namePath by remember(cfg) { mutableStateOf(cfg.namePath ?: "") }
    var idPath by remember(cfg) { mutableStateOf(cfg.idPath ?: "") }
    var artistPath by remember(cfg) { mutableStateOf(cfg.artistPath ?: "") }
    var coverPath by remember(cfg) { mutableStateOf(cfg.coverPath ?: "") }
    var urlResultPath by remember(cfg) { mutableStateOf(cfg.urlResultPath ?: "") }
    var lyricResultPath by remember(cfg) { mutableStateOf(cfg.lyricResultPath ?: "") }
    var extraHeaders by remember(cfg) { mutableStateOf(cfg.extraHeaders ?: "") }

    fun commit(block: CustomApiConfig.() -> Unit) {
        try {
            val c = settings.customApiConfig.peek() ?: CustomApiConfig()
            c.block()
            settings.customApiConfig.set(c)
            settings.saveCustomApiConfig()
        } catch (_: Throwable) {
        }
    }

    SectionTitle("自定义 API")
    SettingsCard {
        SwitchRow("启用自定义 API 音源", "搜索结果将加入第三方音乐 API 的歌曲，可独立搜索与播放", enabled) {
            enabled = it
            commit { this.enabled = it }
        }
        ApiField("音源名称", name) { name = it; commit { this.name = it } }
        ApiField("搜索接口 URL（含 {keyword}）", searchUrl) { searchUrl = it; commit { this.searchUrl = it } }
        ApiField("播放地址 URL（含 {id}）", urlUrl) { urlUrl = it; commit { this.urlUrl = it } }
        ApiField("歌词接口 URL（含 {id}，可选）", lyricUrl) { lyricUrl = it; commit { this.lyricUrl = it } }
        ApiField("结果列表 JSON 路径（搜索响应根 → 数组）", searchListPath) { searchListPath = it; commit { this.searchListPath = it } }
        ApiField("歌名 JSON 路径", namePath) { namePath = it; commit { this.namePath = it } }
        ApiField("id JSON 路径", idPath) { idPath = it; commit { this.idPath = it } }
        ApiField("歌手 JSON 路径（可选，支持 a[].b 合并）", artistPath) { artistPath = it; commit { this.artistPath = it } }
        ApiField("封面 JSON 路径（可选）", coverPath) { coverPath = it; commit { this.coverPath = it } }
        ApiField("播放地址结果 JSON 路径（响应根 → url）", urlResultPath) { urlResultPath = it; commit { this.urlResultPath = it } }
        ApiField("歌词结果 JSON 路径（可选，纯 LRC 文本）", lyricResultPath) { lyricResultPath = it; commit { this.lyricResultPath = it } }
        ApiField("请求头（可选；Key: Value 用 ; 分隔）", extraHeaders) { extraHeaders = it; commit { this.extraHeaders = it } }
    }
}

@Composable
private fun ApiField(label: String, value: String, onChange: (String) -> Unit) {
    Md3TextField(value = value, onValueChange = onChange, label = label)
}

@Composable
private fun SettingsSectionDesktopLyric(settings: DesktopSettings, tick: Int) {
    var showLyrics by remember(tick) { mutableStateOf(boolPeek(settings.showLyrics)) }
    var fontSize by remember(tick) { mutableIntStateOf(numPeek(settings.lyricFontSize, 28)) }
    var fontWeight by remember(tick) { mutableIntStateOf(numPeek(settings.lyricFontWeight, 2)) }
    var fontSel by remember(tick) { mutableStateOf(strPeek(settings.fontSelection)) }
    var lineSpacing by remember(tick) { mutableIntStateOf(numPeek(settings.lyricLineSpacing, 200)) }
    var spring by remember(tick) { mutableStateOf(boolPeek(settings.lyricSpring)) }
    var scale by remember(tick) { mutableStateOf(boolPeek(settings.lyricScale)) }
    var glow by remember(tick) { mutableStateOf(boolPeek(settings.lyricGlow)) }
    var edgeBlur by remember(tick) { mutableStateOf(boolPeek(settings.lyricEdgeBlur)) }
    var shadow by remember(tick) { mutableStateOf(boolPeek(settings.lyricShadow)) }
    var linearAnim by remember(tick) { mutableStateOf(boolPeek(settings.lyricLinearAnim)) }
    var progressStyle by remember(tick) { mutableStateOf(strPeek(settings.lyricProgressStyle, "wavy")) }
    var bgStatic by remember(tick) { mutableStateOf(boolPeek(settings.lyricBgStatic)) }
    var fontPicker by remember { mutableStateOf(false) }

    val fontLabel = when (fontSel) {
        "" -> "内置字体 PingFang SC"
        "system" -> "系统默认字体"
        else -> fontSel
    }

    SectionTitle("桌面歌词")
    SettingsCard {
        SwitchRow("桌面歌词", "关闭后不再显示歌词页", showLyrics) {
            showLyrics = it
            settings.showLyrics.set(it)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MonetScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "夜空中最亮的星 能否听清",
                color = MonetScheme.onSurface,
                fontSize = fontSize.sp,
                fontWeight = when (fontWeight) {
                    0 -> FontWeight.Thin
                    1 -> FontWeight.Light
                    3 -> FontWeight.SemiBold
                    else -> FontWeight.Normal
                },
                fontFamily = MusicFonts.PingFang,
            )
        }
        StepperRow("字号", "$fontSize px", {
            fontSize = max(14, fontSize - 1)
            settings.lyricFontSize.set(fontSize)
        }, {
            fontSize = min(40, fontSize + 1)
            settings.lyricFontSize.set(fontSize)
        })
        Text("字重", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        Segmented(listOf("极细", "细", "常规", "中等"), fontWeight) {
            fontWeight = it
            settings.lyricFontWeight.set(it)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("字体", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
            Spacer(Modifier.width(12.dp))
            Text(fontLabel, Modifier.weight(1f), color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlineBtn("选择") { fontPicker = true }
        }
        StepperRow("行间距", String.format("%.2f×", lineSpacing / 100.0), {
            lineSpacing = max(100, lineSpacing - 5)
            settings.lyricLineSpacing.set(lineSpacing)
        }, {
            lineSpacing = min(250, lineSpacing + 5)
            settings.lyricLineSpacing.set(lineSpacing)
        })
        SwitchRow("弹簧动效", "滚动与逐字上抬使用弹簧物理", spring) { spring = it; settings.lyricSpring.set(it) }
        SwitchRow("放大缩放", "当前行放大、其余行略缩", scale) { scale = it; settings.lyricScale.set(it) }
        SwitchRow("演唱发光", "已唱字词白色辉光(较耗电)", glow) { glow = it; settings.lyricGlow.set(it) }
        SwitchRow("边缘模糊", "未聚焦歌词按远近渐进高斯模糊(较耗电)", edgeBlur) { edgeBlur = it; settings.lyricEdgeBlur.set(it) }
        SwitchRow("歌词阴影", "歌词文字下方柔和投影,更易读", shadow) { shadow = it; settings.lyricShadow.set(it) }
        SwitchRow("非逐字线性动画", "关闭后非逐字歌词整行一次性点亮", linearAnim) { linearAnim = it; settings.lyricLinearAnim.set(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("进度条样式", Modifier.weight(1f), color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
            Segmented(
                listOf("波浪", "直线"),
                if (progressStyle == "straight") 1 else 0,
            ) {
                progressStyle = if (it == 0) "wavy" else "straight"
                settings.lyricProgressStyle.set(progressStyle)
            }
        }
        Text("背景动效", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        Text("动态流动 / 静态(渲染一次,更省电)", color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RadioChip("动态", !bgStatic) { bgStatic = false; settings.lyricBgStatic.set(false) }
            RadioChip("静态", bgStatic) { bgStatic = true; settings.lyricBgStatic.set(true) }
        }
    }

    if (fontPicker) {
        FontPickerDialog(
            settings = settings,
            current = fontSel,
            onPick = {
                fontSel = it
                settings.setFontSelection(it)
                fontPicker = false
            },
            onDismiss = { fontPicker = false },
        )
    }
}

@Composable
private fun RadioChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (selected) MonetScheme.primary else MonetScheme.outline),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = MonetScheme.onSurface, fontFamily = MusicFonts.PingFang)
    }
}

@Composable
private fun FontPickerDialog(
    settings: DesktopSettings,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val families = remember {
        try {
            @Suppress("UNCHECKED_CAST")
            (settings.availableFontFamilies.peek() as? List<String>) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }
    val filtered = remember(q, families) {
        val n = q.lowercase()
        if (n.isEmpty()) families else families.filter { it.lowercase().contains(n) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .height(480.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MonetScheme.surfaceContainerHigh)
                .padding(16.dp),
        ) {
            Text("选择字体", color = MonetScheme.onSurface, fontSize = 18.sp, fontFamily = MusicFonts.PingFang)
            Spacer(Modifier.height(12.dp))
            Md3TextField(value = q, onValueChange = { q = it }, label = "搜索字体名称")
            Spacer(Modifier.height(8.dp))
            PickerLine("内置字体 PingFang SC", current.isEmpty()) { onPick("") }
            PickerLine("系统默认字体", current == "system") { onPick("system") }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                items(filtered.size) { i ->
                    val name = filtered[i]
                    PickerLine(name, current == name) { onPick(name) }
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
private fun PickerLine(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MonetScheme.secondaryContainer.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent)
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

@Composable
private fun SettingsSectionWorldLyric(settings: DesktopSettings, tick: Int) {
    var align by remember(tick) { mutableIntStateOf(numPeek(settings.worldLyricAlign, 2)) }
    var fontSize by remember(tick) { mutableIntStateOf(numPeek(settings.worldLyricFontSize, 28)) }
    var fontWeight by remember(tick) { mutableIntStateOf(numPeek(settings.worldLyricFontWeight, 2)) }
    var border by remember(tick) { mutableIntStateOf(numPeek(settings.worldLyricBorderColor)) }

    SectionTitle("世界歌词")
    SettingsCard {
        Text("对齐", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        Segmented(listOf("左", "中", "右"), align) {
            align = it
            settings.worldLyricAlign.set(it)
        }
        StepperRow("字号", "$fontSize px", {
            fontSize = max(14, fontSize - 1)
            settings.worldLyricFontSize.set(fontSize)
        }, {
            fontSize = min(40, fontSize + 1)
            settings.worldLyricFontSize.set(fontSize)
        })
        Text("字重", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        Segmented(listOf("极细", "细", "常规", "中等"), fontWeight) {
            fontWeight = it
            settings.worldLyricFontWeight.set(it)
        }
        Text("描边", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        Segmented(listOf("无", "黑", "白", "橙", "红", "金渐变"), border) {
            border = it
            settings.worldLyricBorderColor.set(it)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MonetScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "夜空中最亮的星 能否听清",
                color = MonetScheme.onSurface,
                fontSize = fontSize.sp,
                fontWeight = when (fontWeight) {
                    0 -> FontWeight.Thin
                    1 -> FontWeight.Light
                    3 -> FontWeight.SemiBold
                    else -> FontWeight.Normal
                },
                fontFamily = MusicFonts.PingFang,
            )
        }
    }
}

@Composable
private fun SettingsSectionStorage(pc: PlayerController, settings: DesktopSettings, tick: Int) {
    var maxCache by remember(tick) { mutableIntStateOf(numPeek(settings.maxCacheSizeMB, 500)) }
    var preload by remember(tick) { mutableStateOf(boolPeek(settings.qmlCachePreload)) }
    var cacheFolder by remember(tick) { mutableStateOf(strPeek(settings.cacheFolder)) }
    val cacheSize = numPeek(pc.cacheSizeMB)

    SectionTitle("存储")
    SettingsCard {
        StepperRow("最大缓存", "$maxCache MB", {
            maxCache = max(50, maxCache - 50)
            settings.maxCacheSizeMB.set(maxCache)
        }, {
            maxCache = min(1024, maxCache + 50)
            settings.maxCacheSizeMB.set(maxCache)
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("当前占用", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
                Text("$cacheSize MB", color = MonetScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
            }
            OutlineBtn("清除缓存") {
                try {
                    pc.clearDiskCache()
                } catch (_: Throwable) {
                }
            }
        }
        SwitchRow(
            "QML 缓存预加载",
            "启动时把构建期预编译的 QML 缓存从 JAR 读入内存，打开界面时零磁盘 I/O，加载 <1ms",
            preload,
        ) {
            preload = it
            settings.qmlCachePreload.set(it)
        }
        Text("缓存目录", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        Text(
            "本地音乐库封面/歌词缓存与网易云缓存都存在这里；修改后不会自动搬运旧文件，会重新扫描并在新目录下重建缓存",
            color = MonetScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = MusicFonts.PingFang,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Md3TextField(value = cacheFolder, onValueChange = { cacheFolder = it }, label = "目录路径")
            }
            Spacer(Modifier.width(8.dp))
            Md3FilledButton(text = "应用", onClick = { settings.cacheFolder.set(cacheFolder) })
        }
    }
}

@Composable
private fun SettingsSectionLocal(settings: DesktopSettings, tick: Int) {
    var showLocal by remember(tick) {
        mutableStateOf(settings.showLocalTab.peek().let { v -> v == null || java.lang.Boolean.TRUE == v })
    }
    var musicFolder by remember(tick) { mutableStateOf(strPeek(settings.musicFolder)) }

    SectionTitle("本地")
    SettingsCard {
        SwitchRow("显示本地标签", "关闭后隐藏导航栏的「本地」入口", showLocal) {
            showLocal = it
            settings.showLocalTab.set(it)
        }
        Text("本地音乐目录", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
        Text(
            "修改后将自动重新扫描该目录中的音乐文件",
            color = MonetScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = MusicFonts.PingFang,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Md3TextField(value = musicFolder, onValueChange = { musicFolder = it }, label = "目录路径")
            }
            Spacer(Modifier.width(8.dp))
            Md3FilledButton(text = "应用", onClick = { settings.musicFolder.set(musicFolder) })
        }
    }
}

@Composable
private fun SettingsSectionAbout(pc: PlayerController, tick: Int) {
    val version = remember(tick) { strPeek(pc.appVersion, "1.0.0") }
    SectionTitle("关于")
    SettingsCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("QPlayer", color = MonetScheme.onSurface, fontSize = 16.sp, fontFamily = MusicFonts.PingFang)
                    Spacer(Modifier.width(8.dp))
                    if (version.isNotBlank()) {
                        Text("v$version", color = MonetScheme.primary, fontSize = 12.sp, fontFamily = MusicFonts.PingFang)
                    }
                }
                Text(
                    "网易云音乐第三方客户端\nMaterial You 风格 · Apple Music 风逐字歌词\n由 Compose 驱动 · Skia 渲染后端",
                    color = MonetScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = MusicFonts.PingFang,
                )
            }
            IconTap("link") {
                try {
                    pc.openExternalUrl("https://github.com/TIMER-err/qplayer")
                } catch (_: Throwable) {
                }
            }
        }
    }
}
