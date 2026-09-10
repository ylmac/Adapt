import QtQuick
import md3.Core
import "."

// 为我推荐: greeting + recommended-playlist grid + daily song picks, all in one
// Flickable with absolute positioning (the layout primitive that behaves here).
Item {
    id: page
    property var pendingPlaylist
    signal openPlaylist()

    property real pad: 12
    property real gap: 12
    property real greetH: 64
    property real rowH: 64
    // Responsive grid: ~200dp min card width → 2 cols on a phone, 3–4 on a wide
    // window. Width-driven, so it adapts on desktop and on Android large screens.
    property real minTile: 200
    property int cols: Math.max(2, Math.floor((width - 2 * pad + gap) / (minTile + gap)))
    property real tile: (width - 2 * pad - (cols - 1) * gap) / cols
    property real cardH: tile + 52

    property int recCount: player.recommendPlaylists ? player.recommendPlaylists.length : 0
    property int dailyCount: player.recommendations ? player.recommendations.length : 0
    property real gridH: Math.ceil(recCount / cols) * (cardH + gap)
    property real dailyHdrY: greetH + gridH + 4
    property real dailyTop: dailyHdrY + (dailyCount > 0 ? 40 : 0)

    function greeting() {
        var h = new Date().getHours();
        if (h < 6) return "夜深了";
        if (h < 12) return "早上好";
        if (h < 14) return "中午好";
        if (h < 18) return "下午好";
        return "晚上好";
    }

    Flickable {
        anchors.fill: parent
        clip: true
        contentWidth: width
        contentHeight: page.dailyTop + page.dailyCount * page.rowH + 12

        Item {
            width: page.width
            height: page.dailyTop + page.dailyCount * page.rowH + 12
            // Cards/rows have fixed index-derived positions; skip re-measuring the
            // whole page on unrelated version bumps (the play clock) once laid out.
            cachedLayout: true

            Text {
                x: 16; y: 0; height: page.greetH
                verticalAlignment: Text.AlignVCenter
                text: page.greeting() + (player.loggedIn ? "，" + player.userName : "")
                color: Theme.color.onSurfaceColor
                fontSize: 26
            }

            Repeater {
                model: player.recommendPlaylists
                PlaylistCard {
                    tile: page.tile
                    x: page.pad + (index % page.cols) * (page.tile + page.gap)
                    y: page.greetH + Math.floor(index / page.cols) * (page.cardH + page.gap)
                    playlistId: modelData.id
                    name: modelData.name
                    count: modelData.trackCount
                    coverUrl: modelData.coverUrl
                    coverThumbPath: modelData.coverThumbPath || ""
                    onClicked: { page.pendingPlaylist = modelData; page.openPlaylist() }
                }
            }

            Text {
                visible: page.dailyCount > 0
                x: 16; y: page.dailyHdrY; height: 40
                verticalAlignment: Text.AlignVCenter
                text: "每日推荐"
                color: Theme.color.primary
                fontSize: 18
            }

            Repeater {
                model: player.recommendations
                SongRow {
                    width: page.width
                    y: page.dailyTop + index * page.rowH
                    rowTitle: modelData.name
                    rowArtist: modelData.artist
                    coverThumbPath: modelData.coverThumbPath || ""
                    coverUrl: modelData.coverUrl || ""
                    song: player.loggedIn ? modelData : null
                    menuEnabled: player.loggedIn
                    onActivated: player.playRecommendation(index)
                }
            }
        }
    }

    Text {
        anchors.centerIn: parent
        visible: page.recCount === 0 && page.dailyCount === 0
        text: "加载中…"
        color: Theme.color.onSurfaceVariantColor
        fontSize: 14
    }
}
