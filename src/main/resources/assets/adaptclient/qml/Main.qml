import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Phone shell: TopAppBar + paged content + mini player + bottom navigation,
// with a playlist-detail overlay, QR login dialog, a Snackbar for transient
// messages and the debug log on top.
Rectangle {
    id: app
    color: Theme.color.surface
    width: 960
    height: 540
    // ⚠ 这里绝不能加 Behavior on color。qml4j 的 ColorAnimation 一旦接管属性，
    // 之后的 scheme 更新就再也推不动它（实测四组对照：带 Behavior 的元素连续
    // 4 帧停在旧值 #ffcfbdfd，不带的每帧同步更新）。主题色跟随一律靠 Java 侧
    // 高频打点（WorldColorSampler 按渲染帧推进、节流 ~30ms），不加任何颜色动画。

    property int page: 0
    property int nextPage: 0
    property bool detailOpen: false
    property bool loginOpen: false
    property bool settingsOpen: false
    property bool accountOpen: false
    property bool listenTogetherOpen: false
    property bool showLog: false

    property var titles: ["推荐", "搜索", "我的", "最近", "本地"]

    // Responsive breakpoints (MD3): compact < 600, medium 600–839, expanded ≥ 840.
    // The wide layout (a NavigationRail on the left instead of the bottom bar) is
    // driven purely by the available width, so a tablet, a desktop window, or even
    // a phone in landscape adopts it automatically once the width threshold is met.
    property bool wide: app.width >= 600
    property bool expanded: app.width >= 840

    // Shared nav model for both the bottom bar and the rail. 「本地」 is dropped
    // while settings.showLocalTab is off (LocalPage itself stays reachable by
    // index 4 until the page is switched away — see showLocalWatch).
    property var navItems: {
        var out = [
            { icon: "recommend",     text: "推荐" },
            { icon: "search",        text: "搜索" },
            { icon: "library_music", text: "我的" },
            { icon: "history",       text: "最近" }
        ]
        if (settings.showLocalTab !== false) out.push({ icon: "folder", text: "本地" })
        return out
    }

    // Hiding the tab while it's open would strand the user on an unreachable
    // page (no nav item selects it, title still says 本地): jump back to 推荐.
    property bool showLocalWatch: settings.showLocalTab
    onShowLocalWatchChanged: if (settings.showLocalTab === false && app.page === 4) app.switchTo(0)

    // Rebuild the debug log string only while it's actually shown (its set() forces a
    // full relayout, which periodically stuttered the scene when always rebuilt).
    onShowLogChanged: player.setLogVisible(app.showLog)

    // isDarkTheme follows the settings policy. seedColor (Monet) is driven from
    // PlayerController in Java -- a QML Binding on StyleManager.seedColor would not
    // re-fire when the cover seed changed.
    Binding {
        target: StyleManager; property: "isDarkTheme"
        value: settings.resolvedDark
    }

    // System back press (hardware + gesture): the host bumps player.backTick; pop the
    // topmost open overlay/page, and only ask the host to exit when nothing is open.
    property int backTick: player.backTick
    onBackTickChanged: app.handleBack()

    function handleBack() {
        // Order = top-most layer first. The lyric page (host-drawn) and the queue
        // sit above the QML overlays, so they must close before settings/login/log.
        if (player.lyricsOpen)      { player.setLyricsOpen(false); return; }
        if (player.queueOpen)       { player.setQueueOpen(false); return; }
        if (app.showLog)            { app.showLog = false; return; }
        if (app.listenTogetherOpen) { app.listenTogetherOpen = false; return; }
        if (app.loginOpen)          { app.loginOpen = false; return; }
        if (app.accountOpen)        { app.accountOpen = false; return; }
        if (app.settingsOpen)       { app.settingsOpen = false; return; }
        if (app.detailOpen)         { app.detailOpen = false; return; }
        if (app.page !== 0)         { app.switchTo(0); return; }
        player.requestExit();
    }

    // MD3 fade-through page switch: fade the content out, swap, fade it back in.
    function switchTo(idx) {
        app.detailOpen = false;          // dismiss any open playlist detail
        app.settingsOpen = false;        // and the settings overlay
        app.accountOpen = false;         // and the account overlay
        player.setQueueOpen(false);      // and the queue overlay
        if (idx === app.page) return;
        app.nextPage = idx;
        if (idx === 2) player.loadMyPlaylists();
        else if (idx === 3) player.loadRecent();
        pageAnim.restart();
    }

    // Driven by pageAnim; pageBody binds y + opacity to these.
    property real pageOpacity: 1
    property real pageShift: 0

    SequentialAnimation {
        id: pageAnim
        NumberAnimation {
            target: app; property: "pageOpacity"; to: 0
            duration: 90; easing.type: Easing.OutCubic
        }
        ScriptAction { onTrigger: { app.page = app.nextPage; app.pageShift = 28 } }
        ParallelAnimation {
            NumberAnimation {
                target: app; property: "pageOpacity"; from: 0; to: 1
                duration: 220; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: app; property: "pageShift"; from: 28; to: 0
                duration: 220; easing.type: Easing.OutCubic
            }
        }
    }

    // surface player toasts via ToastStack
    property string toastWatch: player.toast
    onToastWatchChanged: if (player.toast.length > 0) toastStack.show(player.toast)

    // Chrome is absolute/anchor-positioned, NOT a ColumnLayout. The play clock
    // sets player.positionMs ~5x/s; each set bumps the engine change version and
    // forces a whole-tree settleLayout that frame (and on coinciding scroll
    // frames). Layout containers in the always-visible chrome re-ran their
    // measure/fill passes every one of those ticks; anchors keep it cheap.
    // Wide-screen navigation rail (left), shown in place of the bottom bar once the
    // window is wide enough; collapses to width 0 (and hides) on compact widths so
    // the content reclaims the full width. Expands to a labelled rail at ≥ 840.
    NavigationRail {
        id: rail
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        visible: app.wide
        extended: app.expanded
        width: app.wide ? implicitWidth : 0
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        currentIndex: app.page
        model: app.navItems
        onItemClicked: app.switchTo(index)
    }

    TopAppBar {
        id: topBar
        anchors.top: parent.top
        anchors.topMargin: settings.topInset   // clear the status bar (edge-to-edge)
        anchors.left: rail.right
        anchors.right: parent.right
        height: 64
        title: app.titles[app.page]
        showNavigationIcon: false

        IconButton {
            type: "standard"
            icon: "queue_music"
            onClicked: { app.settingsOpen = false; app.accountOpen = false; player.setQueueOpen(true) }
        }
        IconButton {
            type: "standard"
            icon: player.loggedIn ? "account_circle" : "login"
            onClicked: { if (player.loggedIn) { app.settingsOpen = false; player.setQueueOpen(false); app.accountOpen = true; } else app.loginOpen = true }
        }
        IconButton {
            type: "standard"
            icon: "settings"
            onClicked: { app.accountOpen = false; player.setQueueOpen(false); app.settingsOpen = true }
        }
        IconButton {
            type: "standard"
            icon: "bug_report"
            onClicked: app.showLog = !app.showLog
        }
    }

    // content region. pageBody is positioned by y (not anchors) so the
    // switch can rise it up; pageWrap clips the overshoot.
    Rectangle {
        id: pageWrap
        anchors.top: topBar.bottom
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.bottom: mini.top
        radius: 16
        color: Theme.color.surfaceContainer
        clip: true
        // 主面板也应用毛玻璃 + 圆角裁剪
        layer.enabled: true
        layer.blurRadius: 24

        Item {
            id: pageBody
            width: parent.width
            height: parent.height
            y: app.pageShift
            opacity: app.pageOpacity

            // Pages stacked + toggled by visibility (was a StackLayout). The
            // engine doesn't recurse into an invisible child's subtree during
            // measure, so only the current page is laid out each frame.
            HomePage {
                id: home
                anchors.fill: parent
                visible: app.page === 0
                onOpenPlaylist: { player.openPlaylist(home.pendingPlaylist.id); app.settingsOpen = false; app.accountOpen = false; player.setQueueOpen(false); app.detailOpen = true }
            }
            SearchPage {
                anchors.fill: parent
                visible: app.page === 1
            }
            LibraryPage {
                id: library
                anchors.fill: parent
                visible: app.page === 2
                onOpenPlaylist: { player.openPlaylist(library.pendingPlaylist.id); app.settingsOpen = false; app.accountOpen = false; player.setQueueOpen(false); app.detailOpen = true }
                onRequestLogin: app.loginOpen = true
            }
            RecentPage {
                anchors.fill: parent
                visible: app.page === 3
                onRequestLogin: app.loginOpen = true
            }
            LocalPage {
                anchors.fill: parent
                visible: app.page === 4
            }

            // Overlays animate in: detail drills in from the right, queue and
            // settings rise from below. Kept laid out only while opacity > 0 so a
            // closed overlay costs nothing per frame.
            PlaylistDetailPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.detailOpen ? 1 : 0
                x: app.detailOpen ? 0 : 36
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on x { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.detailOpen = false
            }

            QueuePage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: player.queueOpen ? 1 : 0
                y: player.queueOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: player.setQueueOpen(false)
            }

            SettingsPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.settingsOpen ? 1 : 0
                y: app.settingsOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.settingsOpen = false
            }

            AccountPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.accountOpen ? 1 : 0
                y: app.accountOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.accountOpen = false
            }
        }
    }

    MiniPlayer {
        id: mini
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.bottom: bottomNav.top
        height: 64
        onLyricsRequested: player.setLyricsOpen(true)
        onListenTogetherRequested: app.listenTogetherOpen = true
    }

    // Bottom navigation (compact). On wide layouts the rail replaces it, so collapse
    // it to height 0 + hidden; the mini player (anchored to bottomNav.top) then sits
    // flush at the bottom without a conditional anchor.
    BottomNav {
        id: bottomNav
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        visible: !app.wide
        // Nav content sits in the top 76; the extra height is background that fills
        // behind the gesture/navigation bar (edge-to-edge).
        height: app.wide ? 0 : (76 + settings.bottomInset)
        currentIndex: app.page
        onNavigate: app.switchTo(bottomNav.pendingIndex)
    }

    // Lyric page chrome (title / wavy progress / transport), over the host-drawn
    // fluid backdrop + lyrics. Slides up from the bottom in lockstep with the host
    // layer -- same smoothstep(player.lyricSlide) offset the host applies.
    LyricOverlay {
        objectName: "lyricChrome"   // host renders this subtree in its own pass, over the fluid
        width: parent.width
        height: parent.height
        // 拖拽进行中（player.lyricDrag）保持可见：即使 slide 到底为 0，MouseArea 也要
        // 等到 onReleased/onCanceled 才真正关闭 —— 否则手势中途变 invisible，按压丢失，
        // 页面会弹簧式弹回而不是关闭。
        visible: player.lyricSlide > 0.001 || player.lyricDrag
        y: {
            var s = player.lyricSlide;
            return (1 - s * s * (3 - 2 * s)) * height;
        }
    }

    LoginDialog {
        active: app.loginOpen
        onClosed: app.loginOpen = false
    }

    ListenTogetherDialog {
        id: ltDialog
        anchors.fill: parent
        onClosed: app.listenTogetherOpen = false
    }
    property bool ltOpenWatch: app.listenTogetherOpen
    onLtOpenWatchChanged: {
        if (app.listenTogetherOpen) ltDialog.open()
        else ltDialog.close()
    }

    // New-version dialog: the host's startup check sets player.updateAvailable when a
    // newer GitHub release exists; the update button opens the release page in the
    // browser (mirror-aware when settings.mirrorEnabled).
    Dialog {
        id: updateDialog
        title: "发现新版本"
        icon: "system_update"
        text: "新版本 " + player.updateVersion + " 现已发布"
        acceptText: "查看更新"
        rejectText: "稍后"
        onAccepted: player.startUpdateDownload()

        Flickable {
            width: parent.width
            height: Math.min(notesText.height, 260)
            contentHeight: notesText.height
            clip: true
            Text {
                id: notesText
                width: parent.width
                text: player.updateNotes
                color: Theme.color.onSurfaceVariantColor
                fontSize: 13
                wrapMode: Text.Wrap
            }
        }
    }

    property bool updateWatch: player.updateAvailable
    onUpdateWatchChanged: if (player.updateAvailable) updateDialog.open()

    // Toast notifications: anchored bottom-center, above the mini player.
    // Uses player.toast as the source (watched via toastWatch above).
    ToastStack {
        id: toastStack
        anchors.bottom: mini.top
        anchors.bottomMargin: 8
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(parent.width - 32, 480)
    }

    // --- debug log overlay ---------------------------------------------
    Rectangle {
        visible: app.showLog
        anchors.fill: parent
        color: Theme.color.surfaceContainerHighest

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            RowLayout {
                Layout.fillWidth: true
                Layout.margins: 8
                spacing: 4
                Text {
                    Layout.fillWidth: true
                    text: "日志"
                    color: Theme.color.onSurfaceColor
                    fontSize: 18
                }
                IconButton { type: "standard"; icon: "delete"; onClicked: player.clearLog() }
                IconButton { type: "standard"; icon: "close"; onClicked: app.showLog = false }
            }

            Flickable {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.margins: 12
                clip: true
                contentHeight: logText.height
                Text {
                    id: logText
                    width: parent.width
                    text: player.logText
                    color: Theme.color.onSurfaceColor
                    fontSize: 12
                    wrapMode: Text.WrapAnywhere
                }
            }
        }
    }
}
