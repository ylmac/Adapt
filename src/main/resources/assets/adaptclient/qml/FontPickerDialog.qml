import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// The app's one font picker: the bundled font, the OS default, or any
// installed family. The selection lives in DesktopSettings (setFontSelection /
// fontSelection, "" = bundled, "system" = the OS default, else a family name).
//
// Virtualized the same way VirtualSongList.qml is (Repeater windowStart/
// windowCount over a fixed rowH): the family list can be 100+ entries long and
// qml4j's Repeater has no built-in positioner, so an un-windowed list either
// costs one live delegate per family or needs manual x/y bookkeeping anyway —
// windowing gets both cheap and simple at once.
Rectangle {
    id: dialog

    property bool active: false
    signal closed()

    anchors.fill: parent
    opacity: active ? 1 : 0
    visible: opacity > 0.01
    color: "#99000000"
    Behavior on opacity { NumberAnimation { duration: 150 } }

    onActiveChanged: if (active) searchField.text = ""

    MouseArea { anchors.fill: parent; onClicked: dialog.closed() }

    // Installed families, filtered in-place as the user types. The two built-in
    // sources (bundled / system default) are fixed rows above the list, so they
    // stay reachable no matter what's typed in the search box.
    property var filtered: {
        var q = searchField.text.toLowerCase();
        var src = (typeof settings.availableFontFamilies !== "undefined" && settings.availableFontFamilies) || [];
        var out = [];
        for (var i = 0; i < src.length; i++) {
            var name = src[i];
            if (q === "" || name.toLowerCase().indexOf(q) >= 0) out.push(name);
        }
        return out;
    }

    Rectangle {
        anchors.centerIn: parent
        width: 340
        height: 480
        radius: 24
        color: Theme.color.surfaceContainerHigh
        scale: dialog.active ? 1 : 0.9
        Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }

        // Swallow taps on the card itself so they don't fall through to the
        // scrim's dialog.closed().
        MouseArea { anchors.fill: parent }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 16
            spacing: 12

            Text {
                Layout.fillWidth: true
                text: "选择字体"
                color: Theme.color.onSurfaceColor
                fontSize: 18
            }

            TextField {
                id: searchField
                Layout.fillWidth: true
                type: "outlined"
                label: "搜索字体名称"
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 44
                radius: 8
                color: bundledMa.pressed ? Theme.color.surfaceContainerHighest : "transparent"
                Text {
                    anchors.left: parent.left
                    anchors.leftMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    text: "内置字体 PingFang SC"
                    color: settings.fontSelection === "" ? Theme.color.primary : Theme.color.onSurfaceColor
                    fontSize: 14
                }
                MouseArea {
                    id: bundledMa
                    anchors.fill: parent
                    onClicked: { settings.setFontSelection(""); dialog.closed() }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 44
                radius: 8
                color: systemMa.pressed ? Theme.color.surfaceContainerHighest : "transparent"
                Text {
                    anchors.left: parent.left
                    anchors.leftMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    text: "系统默认字体"
                    color: settings.fontSelection === "system" ? Theme.color.primary : Theme.color.onSurfaceColor
                    fontSize: 14
                }
                MouseArea {
                    id: systemMa
                    anchors.fill: parent
                    onClicked: { settings.setFontSelection("system"); dialog.closed() }
                }
            }

            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true

                Flickable {
                    id: listView
                    anchors.fill: parent
                    anchors.rightMargin: fontScroll.visible ? 10 : 0
                    clip: true
                    contentWidth: width
                    contentHeight: dialog.filtered.length * rowH

                    property int rowH: 44
                    property int buffer: 8
                    property int count: dialog.filtered.length
                    property int window: Math.min(count, Math.ceil(height / rowH) + 2 * buffer + 1)
                    property int first: {
                        var f = Math.floor(contentY / rowH) - buffer;
                        var maxFirst = count - window;
                        if (f > maxFirst) f = maxFirst;
                        if (f < 0) f = 0;
                        return f;
                    }

                    Item {
                        width: listView.width
                        height: listView.contentHeight

                        Repeater {
                            model: dialog.filtered
                            windowStart: listView.first
                            windowCount: listView.window

                            Rectangle {
                                width: listView.width
                                height: listView.rowH
                                y: index * listView.rowH
                                radius: 8
                                color: rowMa.pressed ? Theme.color.surfaceContainerHighest : "transparent"

                                Text {
                                    anchors.left: parent.left
                                    anchors.leftMargin: 12
                                    anchors.right: parent.right
                                    anchors.rightMargin: 12
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: modelData || ""
                                    elide: Text.ElideRight
                                    color: settings.fontSelection === modelData
                                           ? Theme.color.primary : Theme.color.onSurfaceColor
                                    fontSize: 14
                                }

                                MouseArea {
                                    id: rowMa
                                    anchors.fill: parent
                                    onClicked: { settings.setFontSelection(modelData); dialog.closed() }
                                }
                            }
                        }
                    }
                }

                ScrollBar {
                    id: fontScroll
                    anchors.top: parent.top
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    width: implicitWidth
                    target: listView
                }
            }

            Button {
                Layout.alignment: Qt.AlignHCenter
                type: "text"; text: "取消"
                onClicked: dialog.closed()
            }
        }
    }
}
