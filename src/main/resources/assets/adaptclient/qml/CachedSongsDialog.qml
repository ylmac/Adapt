import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: "已缓存歌曲 (" + (player.cachedSongs ? player.cachedSongs.length : 0) + ")"
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                elide: Text.ElideRight
            }
        }

        VirtualSongList {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            list: page.visible ? player.cachedSongs : null
            isLocal: true
            highlightCurrent: false
            songMenu: true
            cacheList: true
            onActivated: player.playCachedSong(list.activatedIndex)
        }
    }
}