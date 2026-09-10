import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Appearance section of the settings page (split out of SettingsPage.qml to keep each
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
                    text: "外观"
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
                    implicitHeight: darkCol.implicitHeight + 32

                    ColumnLayout {
                        id: darkCol
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 16
                        spacing: 12

                        Text {
                            text: "深色模式"
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodyLarge.family
                            font.pixelSize: Theme.typography.bodyLarge.size
                        }
                        SegmentedButton {
                            Layout.fillWidth: true
                            buttons: [
                                { text: "跟随系统", selected: settings.darkMode === 0 },
                                { text: "浅色", selected: settings.darkMode === 1 },
                                { text: "深色", selected: settings.darkMode === 2 },
                                { text: "主题色", selected: settings.darkMode === 3 }
                            ]
                            onClicked: settings.darkMode = index
                        }

                        // Monet dynamic color: sits right under the dark-mode policy,
                        // same card — off = fixed default seed, on = world sampling.
                        RowLayout {
                            Layout.fillWidth: true
                            Layout.topMargin: 4
                            Layout.preferredHeight: 44
                            spacing: 12
                            Text {
                                Layout.fillWidth: true
                                Layout.alignment: Qt.AlignVCenter
                                text: "莫奈取色"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyMedium.family
                                font.pixelSize: Theme.typography.bodyMedium.size
                            }
                            Text {
                                Layout.alignment: Qt.AlignVCenter
                                text: "按音乐封面取色（主题跟随歌曲封面）"
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                            Switch {
                                Layout.alignment: Qt.AlignVCenter
                                checked: settings.monetEnabled
                                onClicked: settings.monetEnabled = checked
                            }
                        }

                        // ── Debug：详细/危险选项总开关 ──
                        // 关闭时下面的"取色来源（世界取色）"等选项**不显示**；
                        // 打开后才出现完整调试项（世界取色已废弃，仅调试可用）。
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            Text {
                                Layout.alignment: Qt.AlignVCenter
                                text: "Debug"
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                            Text {
                                Layout.alignment: Qt.AlignVCenter
                                Layout.fillWidth: true
                                text: settings.debugMode ? "调试模式：已显示详细选项" : ""
                                color: Theme.color.outlineColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                            Switch {
                                Layout.alignment: Qt.AlignVCenter
                                checked: settings.debugMode
                                onClicked: settings.debugMode = checked
                            }
                        }

                        // ── 详细选项（仅 Debug 打开时可见）──
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            visible: settings.debugMode === true
                            Text {
                                Layout.alignment: Qt.AlignVCenter
                                text: "取色来源"
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                            SegmentedButton {
                                Layout.fillWidth: true
                                Layout.alignment: Qt.AlignVCenter
                                buttons: [
                                    { text: "封面", selected: settings.monetSource === 0 },
                                    { text: "世界", selected: settings.monetSource === 1 }
                                ]
                                onClicked: settings.monetSource = index
                            }
                        }

                        }
                }

    }
}
