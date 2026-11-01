import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// DesktopLyric section of the settings page (split out of SettingsPage.qml to keep each
// generated QML component under the JVM 64KB method limit).
Item {
    property bool fontPickerOpen: false
    property string fontLabel: {
        var s = settings.fontSelection || ""
        if (s === "") return "内置字体 PingFang SC"
        if (s === "system") return "系统默认字体"
        return s
    }
    width: parent.width
    implicitHeight: col.implicitHeight
    ColumnLayout {
        id: col
        width: parent.width
        spacing: 14
                Text {
                    Layout.leftMargin: 20
                    Layout.topMargin: 6
                    text: "桌面歌词"
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
                    implicitHeight: lyricCol.implicitHeight + 32

                    ColumnLayout {
                        id: lyricCol
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 16
                        spacing: 14

                        // Desktop lyrics master toggle — gates the lyric page entirely
                        // (host skips drawing + the cover tap does nothing).
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: "桌面歌词"
                                    color: Theme.color.onSurfaceColor
                                    font.family: Theme.typography.bodyLarge.family
                                    font.pixelSize: Theme.typography.bodyLarge.size
                                }
                                Text {
                                    text: "关闭后不再显示歌词页"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                }
                            }
                            Switch {
                                checked: settings.showLyrics
                                onClicked: settings.showLyrics = checked
                            }
                        }

                        // Live preview: sample lyric line rendered with the desktop lyric
                        // font size + weight (LyricConfig, same values as the real lyric page).
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
                                fontSize: settings.lyricFontSize
                                font.weight: [Font.Thin, Font.Light, Font.Normal, Font.DemiBold][settings.lyricFontWeight]
                                horizontalAlignment: Text.AlignHCenter
                            }
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
                                onClicked: settings.lyricFontSize = Math.max(14, settings.lyricFontSize - 1)
                            }
                            Text {
                                text: settings.lyricFontSize + " px"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Button {
                                type: "outlined"; text: "+"
                                onClicked: settings.lyricFontSize = Math.min(40, settings.lyricFontSize + 1)
                            }
                        }

                        // Font weight segment.
                        Text {
                            text: "字重"
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodyLarge.family
                            font.pixelSize: Theme.typography.bodyLarge.size
                        }
                        SegmentedButton {
                            Layout.fillWidth: true
                            buttons: [
                                { text: "极细", selected: settings.lyricFontWeight === 0 },
                                { text: "细", selected: settings.lyricFontWeight === 1 },
                                { text: "常规", selected: settings.lyricFontWeight === 2 },
                                { text: "中等", selected: settings.lyricFontWeight === 3 }
                            ]
                            onClicked: settings.lyricFontWeight = index
                        }

                        // Font picker: bundled / system default / any installed
                        // family. Selection lives in settings.fontSelection (the
                        // picker dialog reads + writes it, not a row widget).
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            Text {
                                Layout.alignment: Qt.AlignVCenter
                                text: "字体"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Text {
                                Layout.fillWidth: true
                                Layout.alignment: Qt.AlignVCenter
                                text: fontLabel
                                elide: Text.ElideRight
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                            Button {
                                type: "outlined"; text: "选择"
                                onClicked: fontPickerOpen = true
                            }
                        }

                        // Line-spacing stepper (percent of font size, shown as ×).
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            Text {
                                Layout.fillWidth: true
                                text: "行间距"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Button {
                                type: "outlined"; text: "−"
                                onClicked: settings.lyricLineSpacing = Math.max(100, settings.lyricLineSpacing - 5)
                            }
                            Text {
                                text: (settings.lyricLineSpacing / 100).toFixed(2) + "×"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Button {
                                type: "outlined"; text: "+"
                                onClicked: settings.lyricLineSpacing = Math.min(250, settings.lyricLineSpacing + 5)
                            }
                        }

                        // Apple-style spring physics toggle (scroll + per-字 lift).
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: "弹簧动效"
                                    color: Theme.color.onSurfaceColor
                                    font.family: Theme.typography.bodyLarge.family
                                    font.pixelSize: Theme.typography.bodyLarge.size
                                }
                                Text {
                                    text: "滚动与逐字上抬使用弹簧物理"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                }
                            }
                            Switch {
                                checked: settings.lyricSpring
                                onClicked: settings.lyricSpring = checked
                            }
                        }

                        // Active-line emphasis zoom toggle.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: "放大缩放"
                                    color: Theme.color.onSurfaceColor
                                    font.family: Theme.typography.bodyLarge.family
                                    font.pixelSize: Theme.typography.bodyLarge.size
                                }
                                Text {
                                    text: "当前行放大、其余行略缩"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                }
                            }
                            Switch {
                                checked: settings.lyricScale
                                onClicked: settings.lyricScale = checked
                            }
                        }

                        // White glow behind sung syllables toggle.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: "演唱发光"
                                    color: Theme.color.onSurfaceColor
                                    font.family: Theme.typography.bodyLarge.family
                                    font.pixelSize: Theme.typography.bodyLarge.size
                                }
                                Text {
                                    text: "已唱字词白色辉光(较耗电)"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                }
                            }
                            Switch {
                                checked: settings.lyricGlow
                                onClicked: settings.lyricGlow = checked
                            }
                        }

                        // Apple-Music edge blur: unfocused lines blur toward the edges.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: "边缘模糊"
                                    color: Theme.color.onSurfaceColor
                                    font.family: Theme.typography.bodyLarge.family
                                    font.pixelSize: Theme.typography.bodyLarge.size
                                }
                                Text {
                                    text: "未聚焦歌词按远近渐进高斯模糊(较耗电)"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                }
                            }
                            Switch {
                                checked: settings.lyricEdgeBlur
                                onClicked: settings.lyricEdgeBlur = checked
                            }
                        }

                        // Soft shadow under lyric text toggle.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: "歌词阴影"
                                    color: Theme.color.onSurfaceColor
                                    font.family: Theme.typography.bodyLarge.family
                                    font.pixelSize: Theme.typography.bodyLarge.size
                                }
                                Text {
                                    text: "歌词文字下方柔和投影,更易读"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                }
                            }
                            Switch {
                                checked: settings.lyricShadow
                                onClicked: settings.lyricShadow = checked
                            }
                        }

                        // Linear animation for lyrics without per-word timing: off →
                        // whole line lights at once instead of sweeping through it.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: "非逐字线性动画"
                                    color: Theme.color.onSurfaceColor
                                    font.family: Theme.typography.bodyLarge.family
                                    font.pixelSize: Theme.typography.bodyLarge.size
                                }
                                Text {
                                    text: "关闭后非逐字歌词整行一次性点亮"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                }
                            }
                            Switch {
                                checked: settings.lyricLinearAnim
                                onClicked: settings.lyricLinearAnim = checked
                            }
                        }

                        // Lyric page progress-bar style: wavy vs straight.
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            Text {
                                Layout.fillWidth: true
                                text: "进度条样式"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            SegmentedButton {
                                Layout.alignment: Qt.AlignVCenter
                                width: 180
                                buttons: [
                                    { text: "波浪", selected: settings.lyricProgressStyle !== "straight" },
                                    { text: "直线", selected: settings.lyricProgressStyle === "straight" }
                                ]
                                onClicked: settings.lyricProgressStyle = (index === 0 ? "wavy" : "straight")
                            }
                        }

                        // Fluid background mode: dynamic (animated) or static
                        // (rendered once + cached, lighter on the GPU). Label + desc
                        // stacked, radios on their own row so the desc has full width.
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text {
                                text: "背景动效"
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.bodyLarge.family
                                font.pixelSize: Theme.typography.bodyLarge.size
                            }
                            Text {
                                Layout.fillWidth: true
                                text: "动态流动 / 静态(渲染一次,更省电)"
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                                wrapMode: Text.WordWrap
                            }
                            RowLayout {
                                Layout.fillWidth: true
                                Layout.topMargin: 4
                                spacing: 16
                                RadioButton {
                                    text: "动态"
                                    checked: !settings.lyricBgStatic
                                    onClicked: settings.lyricBgStatic = false
                                }
                                RadioButton {
                                    text: "静态"
                                    checked: settings.lyricBgStatic
                                    onClicked: settings.lyricBgStatic = true
                                }
                                Item { Layout.fillWidth: true }
                            }
                        }
                    }
                }

    }
}
