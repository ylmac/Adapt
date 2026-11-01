// Rise 6.9.5 WurstInterface 完整翻译
// 条目：白色名称（无 tag），白色顶栏 + "v6 MC 1.8.9" 黑色文字
import QtQuick
import md3.Core

Item {
    id: wurst
    property var entries: []

    // 条目列表（Rise: 白色文字，无 tag，无 sidebar）
    Repeater {
        model: wurst.entries
        delegate: Item {
            x: modelData.posX
            y: modelData.posY
            width: modelData.nameWidth
            height: 19

            // 名称（Rise: Color.WHITE）
            Text {
                x: 0
                y: 0
                text: modelData.name
                color: "#FFFFFF"
                font.pixelSize: 18
                font.family: "system-ui"
            }
        }
    }

    // ── 白色顶栏（Rise: RenderUtil.d(0, 10, 185, 12, WHITE alpha 100)）──
    Rectangle {
        x: 0
        y: 10
        width: 185
        height: 12
        color: Qt.rgba(1, 1, 1, 0.4)
    }

    // ── "v6 MC 1.8.9" 黑色文字（Rise: arrayListFont.a("v6 MC 1.8.9", 95, 14, BLACK)）──
    Text {
        x: 95
        y: 14
        text: "v6 MC 1.8.9"
        color: "#000000"
        font.pixelSize: 18
        font.family: "system-ui"
    }
}