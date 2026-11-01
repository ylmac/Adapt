import QtQuick
import QtQuick.Layouts
import md3.Core

/**
 * MD3 NavigationRail —— 位置保持侧边栏，选中态的设计语言对齐 PixelPlayer 的
 * Tab（presentation/screens/TabAnimation.kt + StatsScreen 的 PrimaryScrollableTabRow）：
 *
 *   选中   ：填充 primary 的胶囊 + onPrimary 图标/文字 + Bold
 *   未选中 ：透明背景 + onSurfaceVariant + Medium
 *   切换时 ：胶囊淡入 + 轻微缩放（对应 TabAnimation 的 1 → 1.05 → 1 弹跳）
 *
 * 尺寸按 MD3 规范：rail 宽 80dp，item 高 56dp，内容左右留 12dp。
 */
Item {
    id: root

    property var model: []
    property int currentIndex: 0
    property bool extended: false

    implicitWidth: 80

    property Component header: null
    property Component footer: null

    signal itemClicked(int index, var itemData)

    // 背景：16px 圆角 + 毛玻璃。
    /** 毛玻璃底色覆盖（null = MD3 默认）。ClickGUI 传 "#96000000" 与右侧面板一致。 */
    property var glassTint: null

    // 外层 Item 负责圆角裁剪，内层 Rectangle 负责模糊 —— 两者必须分开：
    // blur layer 是按元素 bounding box 合成的，直接给带圆角的 Rectangle 开 blur
    // 会把四个圆角一起糊成直角方块的底色（ClickGUI 曾表现为"圆角外不透明"）。
    // 由外层 clip 把 blur 的结果裁回圆角，毛玻璃和圆角就都能保住。
    Item {
        anchors.fill: parent
        clip: true
        Rectangle {
            anchors.fill: parent
            radius: 16
            property color bgColor: Theme.color.surfaceContainerHighest
            // glassTint 非空 → 用调用方指定的毛玻璃底色（例如积分榜式 #96000000）；
            // 为空 → 维持 MD3 默认（surfaceContainerHighest @ 0.75），其它界面不变
            // glassTint 非空时用它，否则用主题色半透明（浅色/深色都跟随主题）
            color: root.glassTint !== null && root.glassTint !== undefined
                ? root.glassTint
                : Qt.rgba(bgColor.r, bgColor.g, bgColor.b, 0.75)
            layer.enabled: true
            layer.blurRadius: 24
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        Loader {
            Layout.fillWidth: true
            sourceComponent: root.header
            visible: root.header !== null
        }

        // items 平分垂直空间
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            Repeater {
                model: root.model

                delegate: Item {
                    id: slot
                    Layout.fillWidth: true
                    Layout.fillHeight: true

                    property bool selected: index === root.currentIndex
                    property var itemData: modelData

                    // ── 切换栏目时选中项的缩放弹跳 ──
                    // 对齐 PixelPlayer 的 TabAnimation（TabAnimation.kt:74）：
                    //   scale 1 → 1.05 → 1，250ms FastOutSlowIn，且首次组合不播
                    // 动画作用在 slot 上而不是 pill 上：pill 的 scale 已经被
                    // Behavior 接管（选中 1 / 未选中 0.86），再叠一层会互相打断。
                    // PixelPlayer 用的是 1.05f，但那是横向的小文字标签；这里是
                    // 64px 宽的胶囊，5% 只有 3px 变化，基本看不出来 —— 放大到 1.14。
                    SequentialAnimation {
                        id: bounceAnim
                        running: false
                        NumberAnimation {
                            target: slot; property: "scale"; to: 1.14
                            duration: 110; easing.type: Easing.OutCubic
                        }
                        NumberAnimation {
                            target: slot; property: "scale"; to: 1.0
                            duration: 160; easing.type: Easing.OutBack
                        }
                    }

                    property bool hasAnimatedSelection: false
                    onSelectedChanged: {
                        // 首次组合不播（对应 TabAnimation 的 hasAnimatedSelectionChange）
                        if (!slot.hasAnimatedSelection) {
                            slot.hasAnimatedSelection = true
                            return
                        }
                        if (slot.selected) bounceAnim.restart()
                    }

                    // ── 选中态胶囊（PixelPlayer Tab 的填充药丸）──
                    Rectangle {
                        id: pill
                        anchors.centerIn: parent
                        width: 64
                        height: 48
                        radius: 24                       // 胶囊
                        color: Theme.color.primary
                        opacity: slot.selected ? 1 : 0
                        scale: slot.selected ? 1 : 0.86
                        Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                        Behavior on scale { NumberAnimation { duration: 250; easing.type: Easing.OutCubic } }
                    }

                    // 图标
                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.top: pill.top
                        anchors.topMargin: 6
                        text: slot.itemData.icon
                        font.family: Theme.iconFont.name
                        font.pixelSize: 22
                        color: slot.selected ? Theme.color.onPrimaryColor
                                             : Theme.color.onSurfaceVariantColor
                        Behavior on color { ColorAnimation { duration: 200 } }
                    }

                    // 标签（MD3: labelLarge；选中 Bold / 未选中 Medium）
                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.bottom: pill.bottom
                        anchors.bottomMargin: 5
                        text: slot.itemData.text
                        font.family: Theme.typography.labelLarge.family
                        font.pixelSize: 12
                        font.weight: slot.selected ? Font.DemiBold : Font.Medium
                        color: slot.selected ? Theme.color.onPrimaryColor
                                             : Theme.color.onSurfaceVariantColor
                        Behavior on color { ColorAnimation { duration: 200 } }
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            root.currentIndex = index
                            root.itemClicked(index, slot.itemData)
                        }
                    }
                }
            }
        }

        Loader {
            Layout.fillWidth: true
            sourceComponent: root.footer
            visible: root.footer !== null
        }
    }
}
