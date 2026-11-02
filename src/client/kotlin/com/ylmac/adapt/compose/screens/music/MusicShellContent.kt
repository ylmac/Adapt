package com.ylmac.adapt.compose.screens.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ylmac.adapt.compose.theme.AdaptTheme
import com.ylmac.adapt.compose.theme.MonetScheme
import com.ylmac.adapt.module.misc.music.PlayerController
import com.ylmac.adapt.render.screen.AdaptRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TITLES = listOf("推荐", "搜索", "我的", "最近", "本地")

private data class MusicNav(val icon: String, val label: String)

@Composable
fun MusicShellContent() {
    AdaptTheme {
        val pc = remember { AdaptRuntime.getOrCreatePlayerController() }
        val settings = remember { AdaptRuntime.getOrCreateDesktopSettings() }

        var page by remember { mutableIntStateOf(0) }
        var nextPage by remember { mutableIntStateOf(0) }
        var pageOpacity by remember { mutableFloatStateOf(1f) }
        var pageShift by remember { mutableFloatStateOf(0f) }
        var animating by remember { mutableStateOf(false) }

        var detailOpen by remember { mutableStateOf(false) }
        var loginOpen by remember { mutableStateOf(false) }
        var settingsOpen by remember { mutableStateOf(false) }
        var accountOpen by remember { mutableStateOf(false) }
        var listenTogetherOpen by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }
        var toastMsg by remember { mutableStateOf("") }

        var title by remember { mutableStateOf("") }
        var artist by remember { mutableStateOf("") }
        var playing by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var loggedIn by remember { mutableStateOf(false) }
        var userName by remember { mutableStateOf("") }
        var showLocal by remember { mutableStateOf(true) }
        var showLyrics by remember { mutableStateOf(true) }
        var queueOpen by remember { mutableStateOf(false) }
        var lyricsOpen by remember { mutableStateOf(false) }
        var lyricSlide by remember { mutableFloatStateOf(0f) }
        var coverPath by remember { mutableStateOf("") }
        var coverUrl by remember { mutableStateOf("") }
        var coverBytes by remember { mutableStateOf<ByteArray?>(null) }
        var listenInRoom by remember { mutableStateOf(false) }
        var backTick by remember { mutableIntStateOf(0) }
        var lastBackTick by remember { mutableIntStateOf(0) }
        var updateAvailable by remember { mutableStateOf(false) }
        var updateVersion by remember { mutableStateOf("") }
        var updateNotes by remember { mutableStateOf("") }
        var updateDialog by remember { mutableStateOf(false) }
        var topInset by remember { mutableFloatStateOf(0f) }
        var bottomInset by remember { mutableFloatStateOf(0f) }
        var tick by remember { mutableIntStateOf(0) }
        var logText by remember { mutableStateOf("") }

        // 歌词 slide：仅打开/过渡中 16ms；完全关闭时 250ms（别常驻挡 idle）
        LaunchedEffect(Unit) {
            while (true) {
                try {
                    val nOpen = boolPeek(pc.lyricsOpen)
                    val nSlide = try {
                        pc.lyricSlide.peekDouble().toFloat()
                    } catch (_: Throwable) {
                        0f
                    }
                    if (nOpen != lyricsOpen) lyricsOpen = nOpen
                    if (kotlin.math.abs(nSlide - lyricSlide) > 0.002f) lyricSlide = nSlide
                    val animating = nOpen || (nSlide > 0.001f && nSlide < 0.999f)
                    delay(if (animating) 16 else 250)
                } catch (_: Throwable) {
                    delay(250)
                }
            }
        }

        LaunchedEffect(Unit) {
            try {
                pc.loadHotSearches()
            } catch (_: Throwable) {
            }
            var lastSig = ""
            while (true) {
                try {
                    pc.pump()
                    val nTitle = strPeek(pc.title)
                    val nArtist = strPeek(pc.artist)
                    val nPlaying = boolPeek(pc.playing)
                    val nLoading = boolPeek(pc.loading)
                    val nLoggedIn = boolPeek(pc.loggedIn)
                    val nUser = strPeek(pc.userName)
                    val nQueue = boolPeek(pc.queueOpen)
                    val nLyrics = boolPeek(pc.lyricsOpen)
                    val nCoverPath = strPeek(pc.coverPath)
                    val nCoverUrl = strPeek(pc.coverUrl)
                    val nCoverBytes = try {
                        pc.coverBytes.peek()
                    } catch (_: Throwable) {
                        null
                    }
                    val nListen = boolPeek(pc.listenTogetherInRoom)
                    val nShowLocal = settings.showLocalTab.peek().let { v -> v == null || java.lang.Boolean.TRUE == v }
                    val nShowLyrics = boolPeek(settings.showLyrics)
                    val nBack = numPeek(pc.backTick)
                    val nUpd = boolPeek(pc.updateAvailable)
                    val nVer = strPeek(pc.updateVersion)
                    val nNotes = strPeek(pc.updateNotes)
                    val nTop = floatPeek(settings.topInset)
                    val nBot = floatPeek(settings.bottomInset)
                    val nLog = strPeek(pc.logText)
                    val nToast = strPeek(pc.toast)
                    // 列表指纹/加载态必须进签名，否则 Home/详情 forever 空或同长替换漏脏
                    val nRec = listStamp(pc.recommendPlaylists)
                    val nDaily = listStamp(pc.recommendations)
                    val nMine = listStamp(pc.myPlaylists)
                    val nPlTracks = listStamp(pc.playlistTracks)
                    val nPlLoad = boolPeek(pc.playlistLoading)
                    val nPlTitle = strPeek(pc.playlistTitle)
                    val nPlId = numPeek(pc.openPlaylistId)
                    val nPlOwned = boolPeek(pc.playlistOwned)
                    val nPlSub = boolPeek(pc.playlistSubscribed)
                    val nIntelLoad = boolPeek(pc.intelligenceLoading)
                    val nSearch = listStamp(pc.searchResults)
                    val nSearchRows = listStamp(pc.searchRows)
                    val nHot = listStamp(pc.hotSearches)
                    val nQueueN = listStamp(pc.queueTracks)
                    val nCustomQ = listStamp(pc.customPlaylistTracks)
                    val nRecent = listStamp(pc.recentSongs)
                    val nLocal = listStamp(pc.tracks)
                    val nSearchLoad = boolPeek(pc.searchLoading)
                    val nIndex = numPeek(pc.index, -1)
                    // 签名：仅真实变化才写 state → 避免无意义全树重组/重绘
                    val sig = buildString {
                        append(nTitle).append('|').append(nArtist).append('|')
                        append(nPlaying).append(nLoading).append(nLoggedIn).append(nQueue).append(nLyrics)
                        append('|').append(nCoverPath).append('|').append(nCoverUrl)
                        append('|').append(nCoverBytes?.size ?: -1)
                        append('|').append(nListen).append(nShowLocal).append(nShowLyrics)
                        append('|').append(nBack).append(nUpd).append(nVer)
                        append('|').append(nTop).append(nBot)
                        append('|').append(nToast).append('|').append(nLog.length)
                        append('|').append(nUser)
                        append('|').append(nRec).append('/').append(nDaily).append('/').append(nMine)
                        append('|').append(nPlTracks).append('/').append(nPlLoad).append('/').append(nPlId)
                        append('|').append(nPlTitle)
                        append('|').append(nPlOwned).append(nPlSub).append(nIntelLoad)
                        append('|').append(nSearch).append('/').append(nSearchRows).append('/').append(nHot)
                        append('|').append(nSearchLoad)
                        append('|').append(nQueueN).append('/').append(nCustomQ)
                        append('|').append(nRecent).append('/').append(nLocal)
                        append('|').append(nIndex)
                    }
                    if (sig != lastSig) {
                        lastSig = sig
                        title = nTitle
                        artist = nArtist
                        playing = nPlaying
                        loading = nLoading
                        loggedIn = nLoggedIn
                        userName = nUser
                        queueOpen = nQueue
                        lyricsOpen = nLyrics
                        coverPath = nCoverPath
                        coverUrl = nCoverUrl
                        coverBytes = nCoverBytes
                        listenInRoom = nListen
                        showLocal = nShowLocal
                        showLyrics = nShowLyrics
                        if (!nShowLocal && page == 4) page = 0
                        backTick = nBack
                        updateAvailable = nUpd
                        updateVersion = nVer
                        updateNotes = nNotes
                        topInset = nTop
                        bottomInset = nBot
                        logText = nLog
                        if (nToast.isNotBlank() && nToast != toastMsg) toastMsg = nToast
                        // 列表数据偶发变化：慢速 bump（最多 ~2Hz）
                        tick++
                    }
                } catch (_: Throwable) {
                }
                delay(250)
            }
        }

        LaunchedEffect(toastMsg) {
            if (toastMsg.isBlank()) return@LaunchedEffect
            delay(2800)
            toastMsg = ""
        }

        fun switchTo(idx: Int) {
            detailOpen = false
            settingsOpen = false
            accountOpen = false
            try {
                pc.setQueueOpen(false)
            } catch (_: Throwable) {
            }
            if (idx == page) return
            nextPage = idx
            try {
                when (idx) {
                    2 -> pc.loadMyPlaylists()
                    3 -> pc.loadRecent()
                }
            } catch (_: Throwable) {
            }
            animating = true
        }

        LaunchedEffect(backTick) {
            if (backTick != lastBackTick) {
                lastBackTick = backTick
                handleBack(
                    pc = pc,
                    lyricsOpen = lyricsOpen,
                    queueOpen = queueOpen,
                    showLog = showLog,
                    listenTogetherOpen = listenTogetherOpen,
                    loginOpen = loginOpen,
                    accountOpen = accountOpen,
                    settingsOpen = settingsOpen,
                    detailOpen = detailOpen,
                    page = page,
                    setShowLog = { showLog = it },
                    setListenTogether = { listenTogetherOpen = it },
                    setLogin = { loginOpen = it },
                    setAccount = { accountOpen = it },
                    setSettings = { settingsOpen = it },
                    setDetail = { detailOpen = it },
                    switchToHome = { switchTo(0) },
                )
            }
        }

        LaunchedEffect(updateAvailable) {
            if (updateAvailable) updateDialog = true
        }

        LaunchedEffect(animating, nextPage) {
            if (!animating) return@LaunchedEffect
            pageOpacity = 0f
            delay(90)
            page = nextPage
            pageShift = 28f
            pageOpacity = 1f
            pageShift = 0f
            animating = false
        }

        val navItems = remember(showLocal) {
            buildList {
                add(MusicNav("recommend", "推荐"))
                add(MusicNav("search", "搜索"))
                add(MusicNav("library_music", "我的"))
                add(MusicNav("history", "最近"))
                if (showLocal) add(MusicNav("folder", "本地"))
            }
        }

        val animatedOpacity by animateFloatAsState(
            targetValue = if (animating && pageOpacity < 0.5f) 0f else pageOpacity,
            animationSpec = tween(if (pageOpacity < 0.5f) 90 else 220),
            label = "pageOpacity",
        )
        val animatedShift by animateFloatAsState(
            targetValue = pageShift,
            animationSpec = tween(220),
            label = "pageShift",
        )

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Transparent), // 世界透过 + 宿主毛玻璃底（transparent root）
        ) {
            val wide = maxWidth >= 600.dp
            val expanded = maxWidth >= 840.dp
            val lyricEase = lyricSlide * lyricSlide * (3f - 2f * lyricSlide)
            val density = LocalDensity.current

            val shellAlpha = (1f - lyricEase).coerceIn(0f, 1f)
            Row(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (shellAlpha < 0.998f) {
                            Modifier.graphicsLayer { alpha = shellAlpha }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (wide) {
                    MusicNavRail(
                        items = navItems,
                        selectedIndex = page.coerceIn(0, navItems.lastIndex),
                        extended = expanded,
                        onSelect = { switchTo(it) },
                    )
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // TopAppBar — height 64, surface@0.75, titleLarge 22
                    Row(
                        Modifier
                            .graphicsLayer()
                            .fillMaxWidth()
                            .padding(top = topInset.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MonetScheme.surface.copy(alpha = 0.75f))
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            TITLES.getOrElse(page) { "Adapt" },
                            Modifier.weight(1f),
                            color = MonetScheme.onSurface,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = MusicFonts.PingFang,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconTap("queue_music") {
                            settingsOpen = false
                            accountOpen = false
                            try {
                                pc.setQueueOpen(true)
                            } catch (_: Throwable) {
                            }
                        }
                        IconTap(if (loggedIn) "account_circle" else "login") {
                            if (loggedIn) {
                                settingsOpen = false
                                try {
                                    pc.setQueueOpen(false)
                                } catch (_: Throwable) {
                                }
                                accountOpen = true
                            } else {
                                loginOpen = true
                            }
                        }
                        IconTap("settings") {
                            accountOpen = false
                            try {
                                pc.setQueueOpen(false)
                            } catch (_: Throwable) {
                            }
                            settingsOpen = true
                        }
                        IconTap("bug_report") {
                            showLog = !showLog
                            try {
                                pc.setLogVisible(showLog)
                            } catch (_: Throwable) {
                            }
                        }
                    }

                    // pageWrap — surfaceContainer r16
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MonetScheme.surfaceContainer),
                    ) {
                        val pageMoving = animatedOpacity < 0.998f || animatedShift > 0.4f
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(
                                    if (pageMoving) {
                                        Modifier.graphicsLayer {
                                            alpha = animatedOpacity
                                            translationY = animatedShift * density.density
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            when (page) {
                                0 -> HomePage(
                                    pc, loggedIn, userName, tick,
                                    onOpenPlaylist = { id, _ ->
                                        try {
                                            pc.openPlaylist(id)
                                        } catch (_: Throwable) {
                                        }
                                        settingsOpen = false
                                        accountOpen = false
                                        try {
                                            pc.setQueueOpen(false)
                                        } catch (_: Throwable) {
                                        }
                                        detailOpen = true
                                    },
                                )
                                1 -> SearchPage(pc, tick)
                                2 -> LibraryPage(
                                    pc, loggedIn, tick,
                                    onOpenPlaylist = { id, _ ->
                                        try {
                                            pc.openPlaylist(id)
                                        } catch (_: Throwable) {
                                        }
                                        settingsOpen = false
                                        accountOpen = false
                                        try {
                                            pc.setQueueOpen(false)
                                        } catch (_: Throwable) {
                                        }
                                        detailOpen = true
                                    },
                                    onRequestLogin = { loginOpen = true },
                                )
                                3 -> RecentPage(pc, loggedIn, tick, onRequestLogin = { loginOpen = true })
                                4 -> LocalPage(pc, tick)
                            }
                        }

                        OverlaySlide(
                            visible = detailOpen,
                            fromX = 36f,
                            content = {
                                PlaylistDetailPage(
                                    pc = pc,
                                    tick = tick,
                                    loggedIn = loggedIn,
                                    onBack = { detailOpen = false },
                                )
                            },
                        )
                        OverlaySlide(
                            visible = queueOpen,
                            fromY = 32f,
                            content = {
                                QueuePage(
                                    pc = pc,
                                    tick = tick,
                                    onBack = {
                                        try {
                                            pc.setQueueOpen(false)
                                        } catch (_: Throwable) {
                                        }
                                    },
                                )
                            },
                        )
                        OverlaySlide(
                            visible = settingsOpen,
                            fromY = 32f,
                            content = {
                                SettingsPage(
                                    pc = pc,
                                    settings = settings,
                                    tick = tick,
                                    onBack = { settingsOpen = false },
                                )
                            },
                        )
                        OverlaySlide(
                            visible = accountOpen,
                            fromY = 32f,
                            content = {
                                AccountPage(
                                    pc = pc,
                                    tick = tick,
                                    onBack = { accountOpen = false },
                                )
                            },
                        )
                    }

                    MiniPlayerBar(
                        mod = Modifier.graphicsLayer(),
                        title = title,
                        artist = artist,
                        playing = playing,
                        loading = loading,
                        loggedIn = loggedIn,
                        listenTogetherInRoom = listenInRoom,
                        coverPath = coverPath,
                        coverUrl = coverUrl,
                        coverBytes = coverBytes,
                        showLyrics = showLyrics,
                        onLyrics = {
                            try {
                                pc.setLyricsOpen(true)
                            } catch (_: Throwable) {
                            }
                        },
                        onListenTogether = { listenTogetherOpen = true },
                        onPrev = {
                            try {
                                pc.prev()
                            } catch (_: Throwable) {
                            }
                        },
                        onPlayPause = {
                            try {
                                pc.toggle()
                            } catch (_: Throwable) {
                            }
                        },
                        onNext = {
                            try {
                                pc.next()
                            } catch (_: Throwable) {
                            }
                        },
                    )

                    if (!wide) {
                        MusicBottomNav(
                            items = navItems,
                            selectedIndex = page.coerceIn(0, navItems.lastIndex),
                            bottomInset = bottomInset,
                            onSelect = { switchTo(it) },
                        )
                    }
                }
            }

            // Toast (ToastStack above mini player)
            if (toastMsg.isNotBlank()) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = (64 + if (wide) 0 else 76).dp + 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MonetScheme.surfaceContainerHighest)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(toastMsg, color = MonetScheme.onSurface, fontSize = 14.sp, fontFamily = MusicFonts.PingFang)
                }
            }

            // 与 一致：slide 可见时显示 chrome；Compose 在 fluid 之上（beforeComposePresent）
            if (lyricsOpen || lyricSlide > 0.001f) {
                val ease = lyricSlide * lyricSlide * (3f - 2f * lyricSlide)
                val sliding = ease < 0.998f
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (sliding) {
                                Modifier.graphicsLayer {
                                    translationY = (1f - ease) * 540f * density.density
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    LyricChromeContent(
                        pc = pc,
                        title = title,
                        artist = artist,
                        playing = playing,
                        coverPath = coverPath.ifBlank { coverUrl },
                        coverBytes = coverBytes,
                        onClose = {
                            try {
                                pc.setLyricsOpen(false)
                            } catch (_: Throwable) {
                            }
                        },
                    )
                }
            }

            LoginDialog(active = loginOpen, pc = pc, tick = tick, onClosed = { loginOpen = false })
            ListenTogetherDialog(
                open = listenTogetherOpen,
                pc = pc,
                tick = tick,
                onClosed = { listenTogetherOpen = false },
            )

            if (updateDialog) {
                Dialog(onDismissRequest = { updateDialog = false }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(MonetScheme.surfaceContainerHigh)
                            .padding(24.dp),
                    ) {
                        RowTitle(icon = "system_update", title = "发现新版本")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "新版本 $updateVersion 现已发布",
                            color = MonetScheme.onSurface,
                            fontFamily = MusicFonts.PingFang,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            updateNotes,
                            color = MonetScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontFamily = MusicFonts.PingFang,
                            modifier = Modifier.height(160.dp).verticalScroll(rememberScrollState()),
                        )
                        Spacer(Modifier.height(16.dp))
                        RowActions(
                            accept = "查看更新",
                            reject = "稍后",
                            onAccept = {
                                try {
                                    pc.startUpdateDownload()
                                } catch (_: Throwable) {
                                }
                                updateDialog = false
                            },
                            onReject = { updateDialog = false },
                        )
                    }
                }
            }

            if (showLog) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MonetScheme.surfaceContainerHighest)
                        .padding(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("日志", Modifier.weight(1f), color = MonetScheme.onSurface, fontSize = 18.sp, fontFamily = MusicFonts.PingFang)
                        IconTap("delete") {
                            try {
                                pc.clearLog()
                            } catch (_: Throwable) {
                            }
                        }
                        IconTap("close") { showLog = false }
                    }
                    Text(
                        logText,
                        Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        color = MonetScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = MusicFonts.PingFang,
                    )
                }
            }
        }
    }
}

private fun handleBack(
    pc: PlayerController,
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    showLog: Boolean,
    listenTogetherOpen: Boolean,
    loginOpen: Boolean,
    accountOpen: Boolean,
    settingsOpen: Boolean,
    detailOpen: Boolean,
    page: Int,
    setShowLog: (Boolean) -> Unit,
    setListenTogether: (Boolean) -> Unit,
    setLogin: (Boolean) -> Unit,
    setAccount: (Boolean) -> Unit,
    setSettings: (Boolean) -> Unit,
    setDetail: (Boolean) -> Unit,
    switchToHome: () -> Unit,
) {
    try {
        when {
            lyricsOpen -> pc.setLyricsOpen(false)
            queueOpen -> pc.setQueueOpen(false)
            showLog -> setShowLog(false)
            listenTogetherOpen -> setListenTogether(false)
            loginOpen -> setLogin(false)
            accountOpen -> setAccount(false)
            settingsOpen -> setSettings(false)
            detailOpen -> setDetail(false)
            page != 0 -> switchToHome()
            else -> pc.requestExit()
        }
    } catch (_: Throwable) {
    }
}

@Composable
private fun OverlaySlide(
    visible: Boolean,
    fromX: Float = 0f,
    fromY: Float = 0f,
    content: @Composable () -> Unit,
) {
    if (!visible) return
    val ox = remember { androidx.compose.animation.core.Animatable(fromX) }
    val oy = remember { androidx.compose.animation.core.Animatable(fromY) }
    LaunchedEffect(visible, fromX, fromY) {
        ox.snapTo(fromX)
        oy.snapTo(fromY)
        kotlinx.coroutines.coroutineScope {
            launch { ox.animateTo(0f, tween(260)) }
            launch { oy.animateTo(0f, tween(260)) }
        }
    }
    val x = ox.value
    val y = oy.value
    val sliding = x > 0.4f || y > 0.4f || x < -0.4f || y < -0.4f
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (sliding) {
                    Modifier.graphicsLayer {
                        translationX = x * density
                        translationY = y * density
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

/** — 76 + inset, secondaryContainer pill 64×32. */
@Composable
private fun MusicBottomNav(
    items: List<MusicNav>,
    selectedIndex: Int,
    bottomInset: Float,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height((76 + bottomInset).dp)
            .background(MonetScheme.surfaceContainer)
            .padding(top = 8.dp, bottom = (8 + bottomInset).dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEachIndexed { i, item ->
            val selected = i == selectedIndex
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { onSelect(i) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) MonetScheme.secondaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Md3Icon(
                        item.icon,
                        size = 22.sp,
                        color = if (selected) MonetScheme.onSecondaryContainer else MonetScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    item.label,
                    color = if (selected) MonetScheme.onSurface else MonetScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = MusicFonts.PingFang,
                    maxLines = 1,
                )
            }
        }
    }
}

/** — width 80, primary capsule 64×48. */
@Composable
private fun MusicNavRail(
    items: List<MusicNav>,
    selectedIndex: Int,
    extended: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(
        Modifier
            .graphicsLayer()
            .width(if (extended) 80.dp else 80.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(MonetScheme.surfaceContainerHighest.copy(alpha = 0.75f)),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.forEachIndexed { i, item ->
            val selected = i == selectedIndex
            val pillAlpha by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = tween(200),
                label = "railPill",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { onSelect(i) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MonetScheme.primary.copy(alpha = pillAlpha)),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Md3Icon(
                        item.icon,
                        size = 22.sp,
                        color = if (selected) MonetScheme.onPrimary else MonetScheme.onSurfaceVariant,
                    )
                    Text(
                        item.label,
                        color = if (selected) MonetScheme.onPrimary else MonetScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontFamily = MusicFonts.PingFang,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
