// Rise 6.9.5 ModernInterface 完整翻译
// 条目：名称 + tag + 右侧 2×9 圆角 sidebar（accent color）
// 左上 "Rise" logo，右下版本/用户行，左下 XYZ 坐标
import QtQuick
import md3.Core

Item {
    id: modern
    property var entries: []

    // 条目列表
    Repeater {
        model: modern.entries
        delegate: Item {
            x: modelData.posX
            y: modelData.posY
            width: modelData.nameWidth + modelData.tagWidth + 6
            height: 20

            // 名称（Rise: font.b with dropShadow，颜色 = entry.color Fade accent）
            Text {
                x: 0
                y: 0
                text: modelData.name
                color: modelData.color
                font.pixelSize: 18
                font.family: "system-ui"
                }
            // tag（Rise: font.b at x+nameWidth+3, gray -3355444 = #CCCCCC）
            Text {
                x: modelData.nameWidth + 3
                y: 0
                text: modelData.hasTag ? modelData.tag : ""
                visible: modelData.hasTag
                color: "#CCCCCC"
                font.pixelSize: 18
                font.family: "system-ui"
            }
            // sidebar（Rise: roundedRectangle 2×9, radius 1, accent color）
            Rectangle {
                x: modelData.nameWidth + modelData.tagWidth + 2
                y: -1.5
                width: 2
                height: 9
                radius: 1
                color: modelData.color
            }
        }
    }

    // ── 左上 logo "Pixel"（Rise: medium36Font + medium18Font, 6,6）──
    Text {
        x: 6
        y: 6
        text: "Pixel"
        color: Theme.color.primary
        font.pixelSize: 36
        font.weight: 500
        font.family: "system-ui"
    }

    // ── 右下版本/用户行（Rise: right-aligned, screenW - total - 6）──
    // 父项宽度 420，需要在行尾留边距 6
    Row {
        spacing: 0
        x: 420 - 6 - (regular18.contentWidth + bold18.contentWidth + regular20b.contentWidth + medium18b.contentWidth)
        y: 360 - 20 - 1
        Text {
            id: regular18
            text: "Version:\u2009\u2009\u2009\u2009\u2009\u2009"
            color: "#CCCCCC"
            font.pixelSize: 18
            font.family: "system-ui"
        }
        Text {
            id: bold18
            text: "6.9.5"
            color: "#CCCCCC"
            font.pixelSize: 18
            font.weight: 700
            font.family: "system-ui"
        }
        Rectangle { width: 5; height: 1; color: "transparent" }
        Text {
            id: regular20b
            text: "User:"
            color: "#CCCCCC"
            font.pixelSize: 20
            font.family: "system-ui"
        }
        Text {
            id: medium18b
            text: moduleList.playerName
            color: Theme.color.primary
            font.pixelSize: 18
            font.weight: 500
            font.family: "system-ui"
        }
    }

    // ── 左下 XYZ 坐标（Rise: regular18 "XYZ:" + medium18 coords, 5, bottom）──
    Row {
        spacing: 0
        x: 5
        y: 360 - 20 - 1
        Text {
            id: regular18c
            text: "XYZ:"
            color: "#CCCCCC"
            font.pixelSize: 18
            font.family: "system-ui"
        }
        Text {
            id: medium18c
            text: {
                var pos = moduleList.playerPos
                return pos[0] + ", " + pos[1] + ", " + pos[2]
            }
            color: Theme.color.primary
            font.pixelSize: 18
            font.weight: 500
            font.family: "system-ui"
        }
    }
}