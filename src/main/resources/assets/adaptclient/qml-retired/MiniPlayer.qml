import QtQuick
import md3.Core
import "."

// PixelPlayer MiniPlayerContentInternal — ported 1:1.
// A transparent 64-tall bar (the window's rounded clip provides the surface
// look): [44-circle cover][12][title/artist column, weight 1][8][36 一起听][8]
// [36 prev][8][36 play][8][36 next]. Title 15 SemiBold, artist 13 @70%
// onPrimaryContainer. The 一起听 pill sits at the left of the transport cluster.
// Loading → cover gets a wavy progress ring + "准备播放…" text, like the
// original's Preparing-playback state.
Rectangle {
    id: mini

    signal lyricsRequested()
    signal listenTogetherRequested()

    implicitHeight: 64
    radius: 16
    property color bgColor: Theme.color.surfaceContainerHighest
    color: Qt.rgba(bgColor.r, bgColor.g, bgColor.b, 0.75)
    layer.enabled: true
    layer.blurRadius: 24
    // 世界/封面取色过渡时的颜色补间（Java 侧 ~20Hz 打点 → 这里补成连续渐变）

    // Cover: 44-circle. Tap opens the lyric page (original: tapping the mini
    // player expands the sheet).
    Rectangle {
        id: coverBox
        x: 10
        anchors.verticalCenter: parent.verticalCenter
        width: 44
        height: 44
        radius: width / 2
        color: "transparent"
        clip: false

        CoverImage {
            anchors.fill: parent
            radius: width / 2
            iconSize: 22
            fadeIn: true
            source: player.coverPath !== "" ? player.coverPath : player.coverUrl
        }

        // Wavy progress ring while preparing playback (original: circular wavy
        // progress over the art).
        CircularProgress {
            anchors.fill: parent
            visible: player.loading
            indeterminate: true
        }

        MouseArea {
            anchors.fill: parent
            onClicked: { if (settings.showLyrics) mini.lyricsRequested() }
        }
    }

    // Title / artist column: left = cover.right + 12, right = prev button - 8.
    Column {
        anchors.left: coverBox.right
        anchors.leftMargin: 12
        anchors.right: ltBtn.left
        anchors.rightMargin: 8
        anchors.verticalCenter: parent.verticalCenter
        spacing: 1

        Text {
            width: parent.width
            text: player.loading ? "准备播放…"
                  : (player.title.length > 0 ? player.title : "未播放")
            color: Theme.color.onSurfaceColor
            font.pixelSize: 15
            font.weight: Font.DemiBold
            elide: Text.ElideRight
        }
        Text {
            width: parent.width
            text: player.loading ? "加载音频…" : player.artist
            color: Theme.color.onSurfaceVariantColor
            font.pixelSize: 13
            elide: Text.ElideRight
        }
    }

    // Tap the title area (not the buttons) also opens the lyric page.
    MouseArea {
        anchors.left: coverBox.right
        anchors.leftMargin: 12
        anchors.right: ltBtn.left
        anchors.rightMargin: 8
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        onClicked: { if (settings.showLyrics) mini.lyricsRequested() }
    }

    // ---- 一起听按钮 ----
    // Sits at the left edge of the transport cluster. It must anchor to
    // playBtn.left, NOT prevBtn.left: prev/play/next are chained (each anchors
    // right to the next one's left), so anchoring here to prevBtn.left would put
    // it at the exact same spot as the play key and be rendered underneath it
    // (the later-declared playBtn wins), which is why the button never showed.
    // Uses a colored pill (secondaryContainer) so it never blends into the bar;
    // highlighted with tertiary once inside a room. Hidden until logged in.
    PlaybackButton {
        id: ltBtn
        anchors.right: playBtn.left
        anchors.rightMargin: 8
        anchors.verticalCenter: parent.verticalCenter
        width: 36; height: 36
        visible: player.loggedIn
        iconName: "group"
        iconSizePx: 20
        fillColor: player.listenTogetherInRoom ? Theme.color.tertiary : Theme.color.secondaryContainer
        iconColor: player.listenTogetherInRoom ? Theme.color.onTertiaryColor : Theme.color.onSecondaryContainerColor
        onClicked: mini.listenTogetherRequested()
    }

    // ---- 36px circular transport (right cluster) ----
    PlaybackButton {
        id: prevBtn
        anchors.right: nextBtn.left
        anchors.rightMargin: 8
        anchors.verticalCenter: parent.verticalCenter
        width: 36; height: 36
        iconName: "skip_previous"
        iconSizePx: 22
        fillColor: Theme.color.onPrimaryColor
        iconColor: Theme.color.primary
        onClicked: player.prev()
    }
    PlaybackButton {
        id: playBtn
        anchors.right: prevBtn.left
        anchors.rightMargin: 8
        anchors.verticalCenter: parent.verticalCenter
        width: 36; height: 36
        iconName: player.playing ? "pause" : "play_arrow"
        iconSizePx: 22
        fillColor: Theme.color.primary
        iconColor: Theme.color.onPrimaryColor
        onClicked: player.toggle()
    }
    PlaybackButton {
        id: nextBtn
        anchors.right: parent.right
        anchors.rightMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        width: 36; height: 36
        iconName: "skip_next"
        iconSizePx: 22
        fillColor: Theme.color.onPrimaryColor
        iconColor: Theme.color.primary
        onClicked: player.next()
    }
}