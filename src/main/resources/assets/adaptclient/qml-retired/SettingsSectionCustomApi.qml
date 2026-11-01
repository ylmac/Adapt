import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// CustomApi section of the settings page (split out of SettingsPage.qml to keep each
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
                    text: "自定义 API"
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
                    implicitHeight: customApiCol.implicitHeight + 32

                    ColumnLayout {
                        id: customApiCol
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 16
                        spacing: 10

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: "启用自定义 API 音源"
                                    color: Theme.color.onSurfaceColor
                                    font.family: Theme.typography.bodyLarge.family
                                    font.pixelSize: Theme.typography.bodyLarge.size
                                }
                                Text {
                                    text: "搜索结果将加入第三方音乐 API 的歌曲，可独立搜索与播放"
                                    color: Theme.color.onSurfaceVariantColor
                                    font.family: Theme.typography.bodySmall.family
                                    font.pixelSize: Theme.typography.bodySmall.size
                                }
                            }
                            Switch {
                                checked: settings.customApiConfig.enabled
                                onClicked: {
                                    settings.customApiConfig.enabled = checked
                                    settings.saveCustomApiConfig()
                                }
                            }
                        }

                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "音源名称"
                            placeholderText: "如 歌曲宝/QQ 音乐 API"
                            text: settings.customApiConfig.name
                            onTextChanged: {
                                settings.customApiConfig.name = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "搜索接口 URL（含 {keyword}）"
                            placeholderText: "https://host/search?key={keyword}"
                            text: settings.customApiConfig.searchUrl
                            onTextChanged: {
                                settings.customApiConfig.searchUrl = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "播放地址 URL（含 {id}）"
                            placeholderText: "https://host/url?id={id}"
                            text: settings.customApiConfig.urlUrl
                            onTextChanged: {
                                settings.customApiConfig.urlUrl = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "歌词接口 URL（含 {id}，可选）"
                            placeholderText: "https://host/lyric?id={id}"
                            text: settings.customApiConfig.lyricUrl
                            onTextChanged: {
                                settings.customApiConfig.lyricUrl = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "结果列表 JSON 路径（搜索响应根 → 数组）"
                            placeholderText: "如 data.list"
                            text: settings.customApiConfig.searchListPath
                            onTextChanged: {
                                settings.customApiConfig.searchListPath = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "歌名 JSON 路径"
                            placeholderText: "如 name"
                            text: settings.customApiConfig.namePath
                            onTextChanged: {
                                settings.customApiConfig.namePath = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "id JSON 路径"
                            placeholderText: "如 id"
                            text: settings.customApiConfig.idPath
                            onTextChanged: {
                                settings.customApiConfig.idPath = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "歌手 JSON 路径（可选，支持 a[].b 合并）"
                            placeholderText: "如 artists[].name"
                            text: settings.customApiConfig.artistPath
                            onTextChanged: {
                                settings.customApiConfig.artistPath = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "封面 JSON 路径（可选）"
                            placeholderText: "如 pic"
                            text: settings.customApiConfig.coverPath
                            onTextChanged: {
                                settings.customApiConfig.coverPath = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "播放地址结果 JSON 路径（响应根 → url）"
                            placeholderText: "如 data.url"
                            text: settings.customApiConfig.urlResultPath
                            onTextChanged: {
                                settings.customApiConfig.urlResultPath = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "歌词结果 JSON 路径（可选，纯 LRC 文本）"
                            placeholderText: "如 data.lyric"
                            text: settings.customApiConfig.lyricResultPath
                            onTextChanged: {
                                settings.customApiConfig.lyricResultPath = text
                                settings.saveCustomApiConfig()
                            }
                        }
                        TextField {
                            Layout.fillWidth: true
                            type: "outlined"
                            label: "请求头（可选；Key: Value 用 ; 分隔）"
                            placeholderText: "如 Cookie: sid=xxx; Referer: https://host/"
                            text: settings.customApiConfig.extraHeaders
                            onTextChanged: {
                                settings.customApiConfig.extraHeaders = text
                                settings.saveCustomApiConfig()
                            }
                        }
                    }
                }

    }
}
