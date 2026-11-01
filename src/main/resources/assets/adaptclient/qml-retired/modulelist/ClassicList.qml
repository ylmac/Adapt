// Rise 6.9.5 ClassicInterface 完整翻译
// 条目：名称（旋转 pastel 色）+ tag（灰色）+ 可选 sidebar
// 左上 watermark "Rise"（首字 accent，余字 gray），左下 XYZ 三行坐标
import QtQuick
import md3.Core

Item {
    id: classic
    property var entries: []

    // Rise Classic 旋转色盘
    property var acolor: ["#5BCEFA", "#F5A9B8", "#FFFFFF", "#F5A9B8"]

    // 条目列表
    Repeater {
        model: classic.entries
        delegate: Item {
            x: modelData.posX
            y: modelData.posY
            width: modelData.nameWidth + modelData.tagWidth + 6
            height: 22

            // 名称：旋转色盘（Rise: acolor[j], j = index % 4）
            Text {
                x: 0
                y: 0
                text: modelData.name
                color: classic.acolor[index % 4]
                font.pixelSize: 18
                font.family: "system-ui"
            }
            // tag：灰色（Rise: -3355444 = #CCCCCC）
            Text {
                x: modelData.nameWidth + 3
                y: 0
                text: modelData.hasTag ? modelData.tag : ""
                visible: modelData.hasTag
                color: "#CCCCCC"
                font.pixelSize: 18
                font.family: "system-ui"
            }
            // sidebar 被禁用（Rise Classic: arraylistSideBar 默认 false）
        }
    }

    // ── 左上 watermark "Pixel"（Rise: 首字 accent 色，余字 gray §7）──
    Row {
        x: 3
        y: 3
        spacing: 0
        Text {
            text: "P"
            color: Theme.color.primary
            font.pixelSize: 18
            font.family: "system-ui"
        }
        Text {
            text: "ixel"
            color: "#AAAAAA"
            font.pixelSize: 18
            font.family: "system-ui"
        }
    }

    // ── 左下 XYZ 坐标（Rise: 三行 separate, d0 = parent.height - 10）──
    Column {
        x: 3
        y: 360 - 10 - 18 * 3
        spacing: 0
        Text {
            text: "X: " + moduleList.playerPos[0]
            color: Theme.color.primary
            font.pixelSize: 18
            font.family: "system-ui"
        }
        Text {
            text: "Y: " + moduleList.playerPos[1]
            color: Theme.color.primary
            font.pixelSize: 18
            font.family: "system-ui"
        }
        Text {
            text: "Z: " + moduleList.playerPos[2]
            color: Theme.color.primary
            font.pixelSize: 18
            font.family: "system-ui"
        }
    }
}