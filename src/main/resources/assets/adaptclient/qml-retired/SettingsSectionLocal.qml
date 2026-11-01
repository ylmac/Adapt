import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Local section of the settings page (split out of SettingsPage.qml to keep each
// generated QML component under the JVM 64KB method limit).
Item {
    width: parent.width
    implicitHeight: col.implicitHeight
    ColumnLayout {
        id: col
        width: parent.width
        spacing: 14
                Text {
                    visible: typeof settings.musicFolder !== "undefined"
                    Layout.leftMargin: 20
                    Layout.topMargin: 6
                    text: "本地"
                    color: Theme.color.primary
                    font.family: Theme.typography.titleSmall.family
                    font.pixelSize: Theme.typography.titleSmall.size
                }
                Rectangle {
                    visible: typeof settings.musicFolder !== "undefined"
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    radius: 18
                    color: Theme.color.surfaceContainerHighest
                    implicitHeight: localCol.implicitHeight + 32

                    ColumnLayout {
                        id: localCol
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 16
                        spacing: 12

                        // Hide/show the 「本地」 nav entry (bottom bar + rail).
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: "显示本地标签"
                                    color: Theme.color.onSurfaceColor
                                    font.family: Theme.typography.bodyLarge.family
                                    font.pixelSize: Theme.typography.bodyLarge.size
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: "关闭后隐藏导航栏的「本地」入口"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                    wrapMode: Text.WordWrap
                                }
                            }
                            Switch {
                                checked: settings.showLocalTab
                                onClicked: settings.showLocalTab = checked
                            }
                        }

                        Text {
                            text: "本地音乐目录"
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodyLarge.family
                            font.pixelSize: Theme.typography.bodyLarge.size
                        }
                        Text {
                            Layout.fillWidth: true
                            text: "修改后将自动重新扫描该目录中的音乐文件"
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: Theme.typography.bodySmall.size
                            wrapMode: Text.WordWrap
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8
                            TextField {
                                id: musicFolderField
                                Layout.fillWidth: true
                                type: "outlined"
                                label: "目录路径"
                                text: typeof settings.musicFolder !== "undefined" ? settings.musicFolder : ""
                                onAccepted: settings.musicFolder = text
                            }
                            Button {
                                type: "filledTonal"; text: "应用"
                                onClicked: settings.musicFolder = musicFolderField.text
                            }
                        }
                    }
                }

    }
}
