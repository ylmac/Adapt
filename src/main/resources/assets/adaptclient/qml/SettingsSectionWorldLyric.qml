import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// WorldLyric section of the settings page (split out of SettingsPage.qml to keep each
// generated QML component under the JVM 64KB method limit).
Item {
    width: parent.width
    implicitHeight: col.implicitHeight
    ColumnLayout {
        id: col
        width: parent.width
        spacing: 14
                Text {
                    Layout.leftMargin: 20
                    Layout.topMargin: 6
                    text: "世界歌词"
                    color: Theme.color.primary
                    font.family: Theme.typography.titleSmall.family
                    font.pixelSize: Theme.typography.titleSmall.size
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    radius: 18
                    color: Theme.color.surfaceContainerHighest
                    implicitHeight: worldLyricCol.implicitHeight + 32

                    ColumnLayout {
                        id: worldLyricCol
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 16
                        spacing: 14

                        // Alignment: left / center / right (panel position follows).
                        Text {
                            text: "对齐"
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodyLarge.family
                            font.pixelSize: Theme.typography.bodyLarge.size
                        }
                        SegmentedButton {
                            Layout.fillWidth: true
                            buttons: [
                                { text: "左", selected: settings.worldLyricAlign === 0 },
                                { text: "中", selected: settings.worldLyricAlign === 1 },
                                { text: "右", selected: settings.worldLyricAlign === 2 }
                            ]
                            onClicked: settings.worldLyricAlign = index
                        }

                        // Font size stepper.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            Text {
                                Layout.fillWidth: true
                                text: "字号"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Button {
                                type: "outlined"; text: "−"
                                onClicked: settings.worldLyricFontSize = Math.max(14, settings.worldLyricFontSize - 1)
                            }
                            Text {
                                text: settings.worldLyricFontSize + " px"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Button {
                                type: "outlined"; text: "+"
                                onClicked: settings.worldLyricFontSize = Math.min(40, settings.worldLyricFontSize + 1)
                            }
                        }

                        // Font weight segment (thin / light / regular / medium).
                        Text {
                            text: "字重"
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodyLarge.family
                            font.pixelSize: Theme.typography.bodyLarge.size
                        }
                        SegmentedButton {
                            Layout.fillWidth: true
                            buttons: [
                                { text: "极细", selected: settings.worldLyricFontWeight === 0 },
                                { text: "细", selected: settings.worldLyricFontWeight === 1 },
                                { text: "常规", selected: settings.worldLyricFontWeight === 2 },
                                { text: "中等", selected: settings.worldLyricFontWeight === 3 }
                            ]
                            onClicked: settings.worldLyricFontWeight = index
                        }

                        // Outline color: none / black / white / orange / red / gold gradient.
                        Text {
                            text: "描边"
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodyLarge.family
                            font.pixelSize: Theme.typography.bodyLarge.size
                        }
                        SegmentedButton {
                            Layout.fillWidth: true
                            buttons: [
                                { text: "无", selected: settings.worldLyricBorderColor === 0 },
                                { text: "黑", selected: settings.worldLyricBorderColor === 1 },
                                { text: "白", selected: settings.worldLyricBorderColor === 2 },
                                { text: "橙", selected: settings.worldLyricBorderColor === 3 },
                                { text: "红", selected: settings.worldLyricBorderColor === 4 },
                                { text: "金渐变", selected: settings.worldLyricBorderColor === 5 }
                            ]
                            onClicked: settings.worldLyricBorderColor = index
                        }

                        // Live preview bound to the world lyric font size + weight.
                        Rectangle {
                            Layout.fillWidth: true
                            Layout.topMargin: 2
                            radius: 12
                            color: Theme.color.surfaceContainerLow
                            implicitHeight: 64
                            Text {
                                anchors.centerIn: parent
                                text: "夜空中最亮的星 能否听清"
                                color: Theme.color.onSurfaceColor
                                fontSize: settings.worldLyricFontSize
                                font.weight: [Font.Thin, Font.Light, Font.Normal, Font.DemiBold][settings.worldLyricFontWeight]
                                horizontalAlignment: Text.AlignHCenter
                            }
                        }
                    }
                }

    }
}
