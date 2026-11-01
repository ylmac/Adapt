package com.ylmac.adapt.compose.screens.clickgui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.AdaptFonts
import com.ylmac.adapt.compose.theme.AdaptTheme
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.compose.ui.Md3FilledIconButton
import com.ylmac.adapt.compose.ui.Md3ScrollRail
import com.ylmac.adapt.compose.ui.Md3Icon
import com.ylmac.adapt.compose.ui.Md3NavigationRail
import com.ylmac.adapt.compose.ui.Md3OutlinedButton
import com.ylmac.adapt.compose.ui.Md3SegmentedButton
import com.ylmac.adapt.compose.ui.Md3SegmentedDarkMode
import com.ylmac.adapt.compose.ui.Md3Slider
import com.ylmac.adapt.compose.ui.Md3StandardIconButton
import com.ylmac.adapt.compose.ui.Md3Switch
import com.ylmac.adapt.compose.ui.retainedLayer
import com.ylmac.adapt.compose.ui.Md3TextField
import com.ylmac.adapt.compose.ui.NavItem
import com.ylmac.adapt.render.AdaptRuntime
import com.ylmac.adapt.render.clickgui.ClickGuiBridge
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ClickGuiContent(refreshToken: Int = 0) {
    AdaptTheme {
        val bridge = ClickGuiBridge.INSTANCE
        var categories by remember { mutableStateOf(ClickGuiData.loadCategories(bridge)) }
        var selectedCategory by remember { mutableIntStateOf(0) }
        var settingsOpen by remember { mutableStateOf(false) }
        var settingsModule by remember { mutableStateOf<ClickGuiModule?>(null) }
        var settings by remember { mutableStateOf<List<ClickGuiSetting>>(emptyList()) }
        var stateVersion by remember { mutableIntStateOf(0) }
        var titleAnimKey by remember { mutableIntStateOf(0) }
        val titleSlide = remember { Animatable(0f) }
        val titleAlpha = remember { Animatable(1f) }
        var pickerSetting by remember { mutableStateOf<ClickGuiSetting?>(null) }
        var pickerHue by remember { mutableFloatStateOf(0f) }
        var pickerSat by remember { mutableFloatStateOf(1f) }
        var pickerBright by remember { mutableFloatStateOf(1f) }

        LaunchedEffect(refreshToken) {
            categories = ClickGuiData.loadCategories(bridge)
            ClickGuiData.syncEnabled(categories, bridge)
            stateVersion++
        }

        LaunchedEffect(titleAnimKey) {
            if (titleAnimKey == 0) return@LaunchedEffect
            titleSlide.snapTo(22f)
            titleAlpha.snapTo(0.2f)
            kotlinx.coroutines.coroutineScope {
                launch { titleSlide.animateTo(0f, tween(260)) }
                launch { titleAlpha.animateTo(1f, tween(260)) }
            }
        }

        val cat = categories.getOrNull(selectedCategory)
        val catName = cat?.name.orEmpty()
        val catIcon = cat?.icon?.ifBlank { "extension" } ?: "extension"
        val overlayOffset = remember { Animatable(40f) }
        val overlayAlpha = remember { Animatable(0f) }
        LaunchedEffect(settingsOpen) {
            if (settingsOpen) {
                overlayOffset.snapTo(40f)
                overlayAlpha.snapTo(0f)
                kotlinx.coroutines.coroutineScope {
                    launch { overlayOffset.animateTo(0f, tween(260)) }
                    launch { overlayAlpha.animateTo(1f, tween(200)) }
                }
            } else {
                overlayAlpha.snapTo(0f)
                overlayOffset.snapTo(40f)
            }
        }

        fun openSettings(mod: ClickGuiModule) {
            settingsModule = mod
            settings = ClickGuiData.loadSettings(mod.name, bridge)
            settingsOpen = true
        }

        fun closeSettings() {
            settingsOpen = false
            settingsModule = null
            ClickGuiData.syncEnabled(categories, bridge)
            stateVersion++
        }

        fun toggle(mod: ClickGuiModule) {
            mod.enabled = bridge.toggleModule(mod.name)
            mod.tag = bridge.getModuleTag(mod.name) ?: ""
            stateVersion++
        }

        fun applySetting(s: ClickGuiSetting, value: Any?, bump: Boolean = true) {
            bridge.setModuleSetting(settingsModule?.name ?: "", s.name, value)
            when (s.type) {
                "bool" -> s.value = value == true
                "int" -> s.value = (value as? Number)?.toDouble()?.roundToInt()
                "double", "slider" -> s.value = (value as? Number)?.toDouble()
                "enum", "choice" -> s.value = (value as? Number)?.toInt()
                "string" -> s.value = value?.toString()
                "color" -> {
                    val hex = value?.toString() ?: return
                    s.value = hex
                    val rgb = hexToRgb(hex)
                    s.colorR = rgb[0]
                    s.colorG = rgb[1]
                    s.colorB = rgb[2]
                }
            }
            if (bump) stateVersion++
        }

        Box(Modifier.fillMaxSize().background(Color.Transparent)) {
            Row(Modifier.fillMaxSize()) {
                Md3NavigationRail(
                    items = categories.map { NavItem(it.icon.ifBlank { "extension" }, it.name) },
                    selectedIndex = selectedCategory.coerceIn(0, (categories.size - 1).coerceAtLeast(0)),
                    onSelect = {
                        if (it != selectedCategory) {
                            selectedCategory = it
                            titleAnimKey++
                            stateVersion++
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // Content panel — hidden while settings open (QML visible:!settingsOpen)
                    if (!settingsOpen) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(ClickGuiBridge.cornerRadius.dp))
                                .background(MonetScheme.surfaceContainer.copy(alpha = 0.62f)),
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .padding(start = 12.dp, end = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Md3FilledIconButton(
                                        onClick = {},
                                        icon = catIcon,
                                        container = MonetScheme.surfaceContainerLow,
                                        content = MonetScheme.onSurface,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        catName,
                                        Modifier
                                            .weight(1f)
                                            .offset(y = titleSlide.value.dp),
                                        color = MonetScheme.primary.copy(alpha = titleAlpha.value),
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AdaptFonts.PingFang,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Md3FilledIconButton(
                                        onClick = { bridge.openMusicPlayer() },
                                        icon = "music_note",
                                        container = MonetScheme.primaryContainer,
                                        content = MonetScheme.onPrimaryContainer,
                                    )
                                }

                                ModuleListPage(
                                    modules = cat?.modules.orEmpty(),
                                    showThemeSelector = catName == "渲染",
                                    stateVersion = stateVersion,
                                    onToggle = { toggle(it) },
                                    onOpenSettings = { openSettings(it) },
                                    mod = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                )
                            }
                        }
                    }

                    if (settingsOpen && settingsModule != null) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .offset(x = overlayOffset.value.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MonetScheme.surfaceContainer.copy(alpha = 0.62f * overlayAlpha.value)),
                        ) {
                            if (settingsModule!!.name == "饰品") {
                                CosmeticsPage(
                                    enabled = settingsModule!!.enabled,
                                    onBack = { closeSettings() },
                                    onToggle = { toggle(settingsModule!!) },
                                )
                            } else SettingsOverlay(
                                module = settingsModule!!,
                                enabled = settingsModule!!.enabled,
                                categoryName = catName,
                                categoryIcon = catIcon,
                                settings = settings,
                                stateVersion = stateVersion,
                                onBack = { closeSettings() },
                                onToggle = { toggle(settingsModule!!) },
                                onApply = { s, v, bump -> applySetting(s, v, bump) },
                                onOpenPicker = { s ->
                                    pickerSetting = s
                                    val hsv = rgbToHsv(s.colorR, s.colorG, s.colorB)
                                    pickerHue = hsv[0]
                                    pickerSat = hsv[1]
                                    pickerBright = hsv[2]
                                },
                            )
                        }
                    }
                }
            }
        }

        // CanvasLayers 无 Window：Dialog 不可用，改全屏遮罩
        pickerSetting?.let { target ->
            val rgb = hsvToRgb(pickerHue, pickerSat, pickerBright)
            val hex = hexStr(rgb[0], rgb[1], rgb[2])
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { pickerSetting = null },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MonetScheme.surfaceContainerHigh)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        )
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "选择颜色",
                        color = MonetScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = AdaptFonts.PingFang,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(rgb[0], rgb[1], rgb[2])),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(target.name, color = MonetScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    SatBrightCanvas(
                        hue = pickerHue,
                        sat = pickerSat,
                        bright = pickerBright,
                        onPick = { s, b ->
                            pickerSat = s
                            pickerBright = b
                        },
                        mod = Modifier.fillMaxWidth().height(140.dp),
                    )
                    HueCanvas(
                        hue = pickerHue,
                        onPick = { pickerHue = it },
                        mod = Modifier.fillMaxWidth().height(18.dp),
                    )
                    Text(hex, color = MonetScheme.onSurface, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Md3OutlinedButton(text = "取消", onClick = { pickerSetting = null })
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(MonetScheme.primary)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = {
                                        applySetting(target, hex)
                                        pickerSetting = null
                                    },
                                )
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text("确定", color = MonetScheme.onPrimary, fontFamily = AdaptFonts.PingFang)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleListPage(
    modules: List<ClickGuiModule>,
    showThemeSelector: Boolean,
    stateVersion: Int,
    onToggle: (ClickGuiModule) -> Unit,
    onOpenSettings: (ClickGuiModule) -> Unit,
    mod: Modifier = Modifier,
) {
    @Suppress("UNUSED_PARAMETER")
    val sv = stateVersion
    Box(
        mod
            .clip(RoundedCornerShape(16.dp))
            .background(MonetScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .border(1.dp, MonetScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        if (modules.isEmpty() && !showThemeSelector) {
            Text(
                "此分类暂无模块",
                Modifier.align(Alignment.Center),
                color = MonetScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontFamily = AdaptFonts.PingFang,
            )
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(end = 12.dp),
                state = listState,
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                flingBehavior = com.ylmac.adapt.compose.ui.rememberAdaptFlingBehavior(),
            ) {
                if (showThemeSelector) {
                    item {
                        ThemeModeCard()
                    }
                }
                itemsIndexed(modules, key = { _, m -> m.name }) { index, module ->
                    val isFirst = index == 0 && !showThemeSelector
                    val isLast = index == modules.lastIndex
                    // enabled/tick 必须是独立参数：原地改 module.enabled 时 data class 引用不变，
                    // LazyColumn 会 skip 掉行，主列表开关看起来没动。
                    ModuleRow(
                        module = module,
                        enabled = module.enabled,
                        tick = stateVersion,
                        isFirst = isFirst,
                        isLast = isLast,
                        onToggle = { onToggle(module) },
                        onOpenSettings = { onOpenSettings(module) },
                    )
                }
            }
            Md3ScrollRail(
                listState,
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(vertical = 16.dp),
                labelAt = { fraction ->
                    if (modules.isEmpty()) "#"
                    else fastScrollGlyph(modules[(fraction * (modules.size - 1)).toInt().coerceIn(0, modules.lastIndex)].name)
                },
            )
        }
    }
}

private fun fastScrollGlyph(name: String): String {
    for (ch in name) {
        if (ch.isDigit()) return "#"
        if (ch.isLetter()) return ch.uppercase()
    }
    return "#"
}

@Composable
private fun ThemeModeCard() {
    val settings = AdaptRuntime.getOrCreateDesktopSettings()
    var mode by remember {
        mutableIntStateOf((settings.darkMode.peek() as? Number)?.toInt() ?: 0)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 20.dp)
            .height(104.dp)
            .clip(
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 4.dp,
                ),
            )
            .background(MonetScheme.surfaceContainer)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "主题模式",
            color = MonetScheme.onSurface,
            fontSize = 14.sp,
            fontFamily = AdaptFonts.PingFang,
        )
        Spacer(Modifier.height(6.dp))
        Md3SegmentedDarkMode(
            selected = mode,
            onSelect = {
                mode = it
                settings.darkMode.set(it)
                try {
                    MonetScheme.refresh()
                } catch (_: Throwable) {
                }
            },
        )
    }
}

@Composable
private fun ModuleRow(
    module: ClickGuiModule,
    enabled: Boolean,
    tick: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val revision = tick
    val on = enabled
    val top = if (isFirst) 16.dp else 4.dp
    val bottom = if (isLast) 16.dp else 4.dp
    val shape = RoundedCornerShape(
        topStart = top,
        topEnd = top,
        bottomStart = bottom,
        bottomEnd = bottom,
    )
    val bg = if (on) MonetScheme.primaryContainer else MonetScheme.surfaceContainer
    val titleColor = if (on) MonetScheme.onPrimaryContainer else MonetScheme.onSurface
    val subColor = if (on) MonetScheme.onPrimaryContainer else MonetScheme.onSurfaceVariant

    Row(
        Modifier
            .retainedLayer()
            .fillMaxWidth()
            .padding(start = 14.dp, end = 20.dp)
            .height(76.dp)
            .clip(shape)
            .background(bg)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onOpenSettings,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Md3Icon(
                name = "extension",
                size = 22.sp,
                color = titleColor.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                module.name,
                color = titleColor,
                fontSize = 16.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = AdaptFonts.PingFang,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (module.desc.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    module.desc,
                    color = subColor.copy(alpha = if (on) 0.9f else 0.75f),
                    fontSize = 14.sp,
                    fontFamily = AdaptFonts.PingFang,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onOpenSettings,
                )
                .padding(4.dp),
        ) {
            Md3Icon(
                name = "settings",
                size = 22.sp,
                color = titleColor.copy(alpha = 0.7f),
            )
        }
        Md3Switch(checked = on, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun SettingsOverlay(
    module: ClickGuiModule,
    enabled: Boolean,
    categoryName: String,
    categoryIcon: String,
    settings: List<ClickGuiSetting>,
    stateVersion: Int,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onApply: (ClickGuiSetting, Any?, Boolean) -> Unit,
    onOpenPicker: (ClickGuiSetting) -> Unit,
) {
    @Suppress("UNUSED_PARAMETER")
    val sv = stateVersion
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
                .height(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MonetScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MonetScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                Md3Icon(name = categoryIcon, size = 22.sp, color = MonetScheme.onSurface)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    module.name,
                    color = MonetScheme.primary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = AdaptFonts.PingFang,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$categoryName · ${if (enabled) "已启用" else "未启用"}",
                    color = MonetScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = AdaptFonts.PingFang,
                )
            }
        }

        val settingsScroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(settingsScroll)
                .padding(top = 4.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (settings.isEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MonetScheme.surfaceContainerLow)
                        .border(1.dp, MonetScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Md3Icon("tune", size = 20.sp, color = MonetScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "该模块没有可调设置",
                        color = MonetScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontFamily = AdaptFonts.PingFang,
                    )
                }
            } else {
                settings.forEach { s ->
                    val h = settingRowH(s)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height((h + 32).dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MonetScheme.surfaceContainerLow)
                            .border(1.dp, MonetScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // value/tick 必须是参数：原地改 s.value 时引用不变，SettingEditor 会被 skip
                        SettingEditor(s, s.value, stateVersion, onApply, onOpenPicker)
                    }
                }
            }
        }
        Md3ScrollRail(
            settingsScroll,
            Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 16.dp),
        )
        }
    }
}

private fun settingRowH(s: ClickGuiSetting): Int = when (s.type) {
    "enum", "choice" -> 72
    "int", "double", "slider" -> 66
    "string" -> 56
    else -> 44
}

@Composable
private fun SettingEditor(
    s: ClickGuiSetting,
    value: Any?,
    tick: Int,
    onApply: (ClickGuiSetting, Any?, Boolean) -> Unit,
    onOpenPicker: (ClickGuiSetting) -> Unit,
) {
    val revision = tick
    when (s.type) {
        "bool" -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                s.name,
                Modifier.weight(1f),
                color = MonetScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = AdaptFonts.PingFang,
            )
            Md3Switch(checked = value == true, onCheckedChange = { onApply(s, it, true) })
        }
        "int", "double", "slider" -> {
            val min = s.min.toFloat()
            val max = s.max.toFloat().coerceAtLeast(min + 0.0001f)
            var local by remember(s.name) {
                mutableFloatStateOf(((value as? Number)?.toFloat() ?: min).coerceIn(min, max))
            }
            // 外部 bump 时同步（非拖拽过程）
            androidx.compose.runtime.SideEffect {
                val v = ((value as? Number)?.toFloat() ?: min).coerceIn(min, max)
                if (kotlin.math.abs(v - local) > 0.0001f && revision >= 0) local = v
            }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.name,
                        Modifier.weight(1f),
                        color = MonetScheme.onSurface,
                        fontSize = 14.sp,
                        fontFamily = AdaptFonts.PingFang,
                    )
                    Text(
                        fmtNum(local.toDouble(), s.type == "int"),
                        color = MonetScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = AdaptFonts.PingFang,
                    )
                }
                Md3Slider(
                    value = local,
                    onValueChange = {
                        local = it
                        onApply(s, if (s.type == "int") it.roundToInt() else it.toDouble(), false)
                    },
                    valueRange = min..max,
                    stepSize = s.step.toFloat(),
                )
            }
        }
        "enum", "choice" -> {
            val idx = (value as? Number)?.toInt() ?: 0
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    s.name,
                    color = MonetScheme.onSurface,
                    fontSize = 14.sp,
                    fontFamily = AdaptFonts.PingFang,
                )
                if (s.options.isEmpty()) {
                    Text(
                        "无选项",
                        color = MonetScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = AdaptFonts.PingFang,
                    )
                } else {
                    Md3SegmentedButton(
                        options = s.options,
                        selectedIndex = idx.coerceIn(0, (s.options.size - 1).coerceAtLeast(0)),
                        onSelect = { onApply(s, it, true) },
                    )
                }
            }
        }
        "string" -> {
            var local by remember(s.name) { mutableStateOf(value?.toString() ?: "") }
            Md3TextField(
                value = local,
                onValueChange = {
                    local = it
                    onApply(s, it, false)
                },
                label = s.name,
                outlined = true,
            )
        }
        "color" -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                s.name,
                Modifier.weight(1f),
                color = MonetScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = AdaptFonts.PingFang,
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(s.colorR, s.colorG, s.colorB))
                    .border(1.dp, MonetScheme.outline, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Md3OutlinedButton(text = "选择", onClick = { onOpenPicker(s) })
        }
        "button" -> Md3OutlinedButton(
            text = s.name,
            onClick = { onApply(s, "click", true) },
            mod = Modifier.fillMaxWidth(),
        )
        else -> Text(
            "${s.name}: ${s.value}",
            color = MonetScheme.onSurface,
            fontSize = 14.sp,
            fontFamily = AdaptFonts.PingFang,
        )
    }
}

@Composable
private fun SatBrightCanvas(
    hue: Float,
    sat: Float,
    bright: Float,
    onPick: (Float, Float) -> Unit,
    mod: Modifier = Modifier,
) {
    Canvas(
        mod
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(hue) {
                detectTapGestures { off ->
                    onPick(
                        (off.x / size.width).coerceIn(0f, 1f),
                        1f - (off.y / size.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        val steps = 32
        val cw = size.width / steps
        val ch = size.height / steps
        for (y in 0 until steps) {
            val b = 1f - y / (steps - 1f)
            for (x in 0 until steps) {
                val s = x / (steps - 1f)
                val rgb = hsvToRgb(hue, s, 1f)
                drawRect(
                    color = Color(
                        (rgb[0] * b).roundToInt(),
                        (rgb[1] * b).roundToInt(),
                        (rgb[2] * b).roundToInt(),
                    ),
                    topLeft = Offset(x * cw, y * ch),
                    size = Size(cw + 1f, ch + 1f),
                )
            }
        }
        val cx = sat * size.width
        val cy = (1f - bright) * size.height
        drawRect(Color.White, Offset(cx - 1f, cy - 5f), Size(2f, 10f))
        drawRect(Color.White, Offset(cx - 5f, cy - 1f), Size(10f, 2f))
    }
}

@Composable
private fun HueCanvas(
    hue: Float,
    onPick: (Float) -> Unit,
    mod: Modifier = Modifier,
) {
    Canvas(
        mod
            .clip(RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    onPick((off.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val steps = 64
        val cw = size.width / steps
        for (i in 0 until steps) {
            val h = i / (steps - 1f)
            val rgb = hsvToRgb(h, 1f, 1f)
            drawRect(
                color = Color(rgb[0], rgb[1], rgb[2]),
                topLeft = Offset(i * cw, 0f),
                size = Size(cw + 1f, size.height),
            )
        }
        val hx = hue * size.width
        drawRect(Color.White, Offset(hx - 1f, 0f), Size(2f, size.height))
    }
}

private fun fmtNum(v: Double, asInt: Boolean): String {
    if (asInt) return v.roundToInt().toString()
    val r = (v * 100.0).roundToInt() / 100.0
    return if (kotlin.math.abs(r - r.roundToInt()) < 0.0001) r.roundToInt().toString() else r.toString()
}

private fun hexStr(r: Int, g: Int, b: Int): String =
    "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

private fun hexToRgb(hex: String): IntArray {
    var t = hex.removePrefix("#")
    if (t.length == 8) t = t.substring(2)
    if (t.length != 6) return intArrayOf(255, 0, 0)
    return intArrayOf(
        t.substring(0, 2).toInt(16),
        t.substring(2, 4).toInt(16),
        t.substring(4, 6).toInt(16),
    )
}

private fun hsvToRgb(h: Float, s: Float, v: Float): IntArray {
    val i = floor(h * 6f).toInt()
    val f = h * 6f - i
    val p = v * (1 - s)
    val q = v * (1 - f * s)
    val t = v * (1 - (1 - f) * s)
    val (r, g, b) = when (i % 6) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
    return intArrayOf((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())
}

private fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
    val rn = r / 255f
    val gn = g / 255f
    val bn = b / 255f
    val mx = max(rn, max(gn, bn))
    val mn = min(rn, min(gn, bn))
    val d = mx - mn
    var h = 0f
    if (d != 0f) {
        h = when (mx) {
            rn -> ((gn - bn) / d) % 6f
            gn -> (bn - rn) / d + 2f
            else -> (rn - gn) / d + 4f
        } / 6f
        if (h < 0f) h += 1f
    }
    val s = if (mx == 0f) 0f else d / mx
    return floatArrayOf(h, s, mx)
}
