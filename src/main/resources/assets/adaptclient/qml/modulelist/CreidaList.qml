// Rise 6.9.5 CreidaInterface 完整翻译
// 条目：黑底圆角矩形 + 右侧 1px accent 条 + 名称（accent 色）+ tag（灰色）
// 左上："Pixel Client | 6.9.5 | <player> | HH:mm:ss"（每个字符 accent 渐变色）
import QtQuick
import md3.Core

Item {
    id: creida
    property var entries: []
    property var rowHeight: 20

    // 条目列表
    Repeater {
        model: creida.entries
        delegate: Item {
            x: modelData.posX - 3
            y: modelData.posY - 1
            width: modelData.nameWidth + modelData.tagWidth + 2 + 3 + 4
            height: creida.rowHeight + 1

            // 黑底圆角矩形（Rise: RenderUtil.d with blur/bloom passes + regular black alpha 110）
            Rectangle {
                x: 0
                y: 0
                width: parent.width
                height: parent.height
                radius: 2
                color: Qt.rgba(0, 0, 0, 0.43) // 110/255 ≈ 0.43
            }

            // 右侧 1px accent 条（Rise: 1px wide, rowHeight+1 tall, accent color）
            Rectangle {
                x: parent.width - 1
                y: 0
                width: 1
                height: parent.height
                color: modelData.color
            }

            // 名称（Rise: accent color, at d0+1, d1）
            Text {
                x: 1
                y: 0
                text: modelData.name
                color: modelData.color
                font.pixelSize: 24
                font.family: "system-ui"
            }
            // tag（Rise: gray -3355444 = #CCCCCC, at x + nameWidth + 1 + 3）
            Text {
                x: modelData.nameWidth + 1 + 3
                y: 0
                text: modelData.hasTag ? modelData.tag : ""
                visible: modelData.hasTag
                color: "#CCCCCC"
                font.pixelSize: 24
                font.family: "system-ui"
            }
        }
    }

    // ── 左上状态栏（Rise: roundedRect + per-char accent text）
    // "Pixel Client | 6.9.5 | <player> | HH:mm:ss"
    Rectangle {
        x: 2
        y: 3
        width: statusText.width + 8
        height: 15
        radius: 4
        color: Qt.rgba(0, 0, 0, 0.39) // 100/255 ≈ 0.39
        Text {
            id: statusText
            x: 6
            y: 0
            text: "Pixel Client | 6.9.5 | " + moduleList.playerName + " | " + new Date().toLocaleTimeString("zh-CN", {hour: "2-digit", minute: "2-digit", second: "2-digit"})
            color: Theme.color.primary
            font.pixelSize: 11
            font.family: "system-ui"
        }
    }
}