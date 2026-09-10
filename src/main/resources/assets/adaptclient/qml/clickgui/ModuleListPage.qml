import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 模块列表页（每个分类一个实例，visible 切换 —— Main.qml 页面堆叠模式，
// 不可见页面不参与 measure）。
// 列表 = 全量 Repeater（模块数少 ~20/分类，无需窗口化）。每行是圆角卡片，
// 对齐 PixelPlayer 的 QueuePlaylistSongItem（去掉封面）。
//
// Switch 状态：qml4j 里 Switch 内部写 checked 会清掉外部绑定
// （Property.set→clearBinding），所以不用 `checked: modelData.enabled` 绑定，
// 而是 onStateVersionChanged 手动同步 —— stateVersion++ 时遍历写回真实状态。
Item {
    id: page

    property var moduleList: []
    property int stateVersion: 0
    signal toggleModule(var mod)
    signal openSettings(var mod)

    /**
     * 是否在列表顶部显示主题模式选择器（从 ClickGui 右侧面板移入）。
     * 只有「渲染」分类的那一个实例为 true —— 主题模式是世界渲染外观的一部分，
     * 放在渲染分类里与 Shaders / Halo3D 同页，顶栏不再被 272px 的分段控件占宽。
     */
    property bool showThemeSelector: false

    // 大卡片的顶部圆角半径。
    // 必须与外层 contentPanel 的 radius(16) 一致：卡片是紧贴面板左右/底部铺开的，
    // 卡片的圆角比面板大就会出现"两个圆角套不在一起"的错位（卡片 32 时很明显）。
    property real cardCorner: 16

    // 分组列表的圆角：首尾项 16dp，中间项 4dp（PixelPlayer GenreDetailScreen.kt:1016）
    property real groupCorner: 16
    property real innerCorner: 4

    // stateVersion 变化 → 手动同步行样式 + Switch
    onStateVersionChanged: page.syncRows()

    // 行卡片 / Switch 的引用（Repeater 的 model 只在 categories 首次加载时
    // 由 [] 变为真实数组，之后不再重建 → 引用稳定，不会累积泄漏）
    property var rowCards: []
    property var switches: []

    /**
     * 同步一行的启用态。
     * <p>
     * ⚠ 这里**只能**写布尔属性 turnedOn，绝不能写颜色。color / textColor /
     * radius 在声明处都是绑定，命令式赋值会清掉绑定 → 世界取色更新 scheme 时
     * 这几个属性再也收不到通知，行卡片就永久冻结在打开界面那一刻的颜色。
     * 这正是"主菜单控件一直停在旧色、子菜单却正常"的原因。
     */
    function applyRowStyle(card) {
        // 局部变量不能叫 on —— QML 语法里 on 是 on<Signal> 处理器的关键字
        card.turnedOn = !!(card.modRef && card.modRef.enabled === true)
    }

    /**
     * 取首字母用于滚动条气泡 —— 移植 PixelPlayer 的 extractFastScrollGlyph
     * （ExpressiveScrollBarMetrics.kt:120）：跳过开头标点，取第一个字母或数字；
     * 数字统一返回 "#"，字母转大写；中文直接返回该字。
     * <p>
     * ⚠ 不要用正则字面量（/[A-Za-z]/）—— qml4j 解析器不支持，会把 `/` 当除号
     * 报 `extraneous input '/'`，且行号会指向调用方文件。
     */
    function fastScrollGlyph(s) {
        if (s == null) return ""
        var t = String(s).trim()
        for (var i = 0; i < t.length; i++) {
            var c = t.charAt(i)
            if (c >= "0" && c <= "9") return "#"
            if ((c >= "a" && c <= "z") || (c >= "A" && c <= "Z")) return c.toUpperCase()
            if (c.charCodeAt(0) > 127) return c      // 中日韩等直接取该字
        }
        return ""
    }

    function syncRows() {
        for (var i = 0; i < rowCards.length; i++) {
            page.applyRowStyle(rowCards[i])
        }
        for (var j = 0; j < switches.length; j++) {
            var sw = switches[j]
            if (sw.modRef) {
                sw.checked = sw.modRef.enabled === true
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 大卡片 —— 对齐 PixelPlayer 音乐库的列表容器
    // （PlaylistDetailScreen.kt:684：顶部左右 32dp 大圆角 + surfaceContainerHigh）
    //
    // 圆角裁切用 qml4j 官方的 `layer.effect = MultiEffect(maskEnabled+maskSource)`。
    // 依据 Renderer.java:1079 maskClipRadii()：当 item.layer.enabled 且 layer.effect
    // 是带 mask 的 MultiEffect 时，会 saveLayer 并在末尾调用 eraseOutsideRoundRect()
    // 用 CLEAR 混合擦掉圆角外部 → **真正的圆角裁切**。
    //
    // 与"把 MultiEffect 当独立 Item 渲染内容"的区别（后者我试过，失败）：
    //   - 独立 Item：内容必须 visible:false，qml4j 会跳过布局 → 元素错位，
    //     且收不到鼠标事件。
    //   - layer.effect：内容保持**完全可见**，布局与交互正常，圆角由 layer 擦除。
    // ══════════════════════════════════════════════════════════════════
    Rectangle {
        id: cardBg
        anchors.fill: parent
        radius: page.cardCorner
        // 半透明：让外层 contentPanel 的毛玻璃透出来（积分榜同款观感）；
        // 不透明的话内卡会把模糊完全盖住，只剩面板 10px 边距能看见
        // 跟随主题的半透明内卡（浅色模式不能发黑）
        color: Qt.rgba(Theme.color.surfaceContainerHigh.r, Theme.color.surfaceContainerHigh.g,
                       Theme.color.surfaceContainerHigh.b, 0.45)
        border.width: 1
        border.color: Theme.color.outlineVariant
        clip: true

        // 圆角裁切开关
        layer.enabled: true
        layer.effect: MultiEffect {
            maskEnabled: true
            maskSource: cardMask
        }

        // 圆角 mask：只提供形状，不显示
        Rectangle {
            id: cardMask
            anchors.fill: parent
            radius: page.cardCorner
            color: "black"
            visible: false
        }

        Flickable {
            id: flick
            anchors.fill: parent
            clip: true
            contentWidth: width
            contentHeight: contentCol.implicitHeight + 12

            ColumnLayout {
                id: contentCol
                y: 12               // PixelPlayer 的 contentPadding(top = 12.dp)
                width: flick.width
                spacing: 8          // MD3：卡片间距走 4dp 网格

                // ── 主题模式选择卡片（从 ClickGui 右侧面板移入，仅渲染分类）──
                // 与模块行同宽同圆角规则：它是分组里的第一张卡，所以上圆角 16、
                // 下圆角 4，下面紧接的模块行首项因此改用 4/4（见 isFirst 判断）。
                Rectangle {
                    id: themeCard
                    visible: page.showThemeSelector
                    Layout.fillWidth: true
                    Layout.leftMargin: 14
                    Layout.rightMargin: 20
                    Layout.preferredHeight: 104
                    radius: page.innerCorner
                    color: Theme.color.surfaceContainer

                    layer.enabled: true
                    layer.effect: MultiEffect {
                        maskEnabled: true
                        maskSource: themeMask
                    }
                    Rectangle {
                        id: themeMask
                        anchors.fill: parent
                        radius: page.innerCorner
                        topLeftRadius: page.groupCorner
                        topRightRadius: page.groupCorner
                        bottomLeftRadius: page.innerCorner
                        bottomRightRadius: page.innerCorner
                        color: "black"
                        visible: false
                    }

                    ColumnLayout {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 6

                        Text {
                            Layout.fillWidth: true
                            text: "主题模式"
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodyMedium.family
                            font.pixelSize: Theme.typography.bodyMedium.size
                        }
                        ThemeModeSelector {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 40
                        }
                    }
                }

                Repeater {
                    model: page.moduleList

                    // 模块行 —— 对齐 PixelPlayer 的 SwitchSettingItem
                    // （SettingsComponents.kt:152）：
                    //   底色     surfaceContainer（比大卡片 surfaceContainerHigh 暗一档）
                    //   内边距   16dp
                    //   左图标   24dp，与文字间隔 12dp
                    //   标题     titleMedium / onSurface
                    //   副标题   bodyMedium / onSurfaceVariant，与标题间隔 4dp
                    //   右侧     Switch
                    //
                    // 圆角用 PixelPlayer 的**分组列表**规则（GenreDetailScreen.kt:1016）：
                    //   首项 顶部 16 / 底部 4 ；末项 顶部 4 / 底部 16 ；中间 全部 4 ；
                    //   只有一项时四角都是 16。
                    Rectangle {
                        id: rowCard
                        Layout.fillWidth: true
                        Layout.leftMargin: 14
                        // 右侧多留给滚动条（PixelPlayer 有滚动条时 end padding 24dp）
                        Layout.rightMargin: 20
                        Layout.preferredHeight: 76          // 两行文本 + 上下 16dp padding
                        radius: page.innerCorner            // 背景统一圆角（矩形渲染只用这个）
                        color: turnedOn ? Theme.color.primaryContainer : Theme.color.surfaceContainer

                        // 有主题卡片时它是分组的第一张卡 → 首个模块行不再是"首项"，
                        // 否则两张相邻卡片都会带 16 的上圆角，看着像两个分组。
                        readonly property bool isFirst: index === 0 && !page.showThemeSelector
                        readonly property bool isLast: index === page.moduleList.length - 1

                        // 唯一被命令式写入的属性（page.applyRowStyle / syncRows）。
                        // 用布尔而不是颜色：颜色属性一旦被赋值就会丢掉 Theme.color 绑定。
                        property bool turnedOn: false

                        // 圆角裁切走官方路径：layer.effect 的 MultiEffect + 分角 mask。
                        // Renderer.maskClipRadii() 直接读 maskSource 的 topLeftRadius...，
                        // 这路读取独立于矩形背景渲染（后者只用统一的 radius），所以
                        // 分角半径在 mask 上能生效：背景圆角仍是 4，但裁剪用 16/4。
                        // （与 ClickGui 大卡片的 layer.effect 同一套机制，已验证可用。）
                        layer.enabled: true
                        layer.effect: MultiEffect {
                            maskEnabled: true
                            maskSource: rowMask
                        }

                        // 圆角 mask：只提供形状，不显示。分角半径在这里设置才有意义。
                        Rectangle {
                            id: rowMask
                            anchors.fill: parent
                            radius: page.innerCorner
                            topLeftRadius: rowCard.isFirst ? page.groupCorner : page.innerCorner
                            topRightRadius: rowCard.isFirst ? page.groupCorner : page.innerCorner
                            bottomLeftRadius: rowCard.isLast ? page.groupCorner : page.innerCorner
                            bottomRightRadius: rowCard.isLast ? page.groupCorner : page.innerCorner
                            color: "black"
                            visible: false
                        }

                        property var modRef: modelData
                        property color titleColor: turnedOn ? Theme.color.onPrimaryContainerColor
                                                            : Theme.color.onSurfaceColor
                        property color subtitleColor: turnedOn ? Theme.color.onPrimaryContainerColor
                                                               : Theme.color.onSurfaceVariantColor
                        property bool emphasize: turnedOn

                        // 不加 Behavior on color —— ColorAnimation 一旦接管属性就不再
                        // 响应 scheme 更新，行卡片会卡在创建时的颜色（详见 Main.qml 注释）。

                        Component.onCompleted: {
                            var arr = page.rowCards
                            arr.push(this)
                            page.rowCards = arr
                            page.applyRowStyle(this)
                        }

                        // 整行 Ripple → 打开设置
                        Ripple {
                            anchors.fill: parent
                            rippleColor: Theme.color.onSurfaceColor
                            // Ripple 只支持统一圆角，用中间项的小圆角（首尾的
                            // 大圆角处涟漪会略微溢出，但它是半透明效果，看不出）
                            clipRadius: page.innerCorner
                            onClicked: page.openSettings(modelData)
                        }

                        RowLayout {
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.leftMargin: 16
                            anchors.rightMargin: 16
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 12

                            // 左图标（24dp，PixelPlayer 的 leadingIcon）
                            Text {
                                Layout.preferredWidth: 24
                                Layout.alignment: Qt.AlignVCenter
                                text: "extension"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 22
                                color: rowCard.titleColor
                                opacity: 0.85
                                horizontalAlignment: Text.AlignHCenter
                            }

                            // 标题 + 副标题（垂直间隔 4dp）
                            ColumnLayout {
                                Layout.fillWidth: true
                                Layout.alignment: Qt.AlignVCenter
                                spacing: 4

                                Text {
                                    Layout.fillWidth: true
                                    text: modelData.name
                                    color: rowCard.titleColor
                                    font.family: Theme.typography.titleMedium.family
                                    font.pixelSize: Theme.typography.titleMedium.size
                                    font.weight: rowCard.emphasize ? Font.DemiBold : Font.Normal
                                    elide: Text.ElideRight
                                }

                                Text {
                                    Layout.fillWidth: true
                                    text: modelData.desc || ""
                                    color: rowCard.subtitleColor
                                    opacity: rowCard.turnedOn ? 0.9 : 0.75
                                    font.family: Theme.typography.bodyMedium.family
                                    font.pixelSize: Theme.typography.bodyMedium.size
                                    elide: Text.ElideRight
                                }
                            }

                            // 设置入口：轻量 glyph + MouseArea
                            Text {
                                Layout.alignment: Qt.AlignVCenter
                                text: "settings"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 22
                                color: rowCard.titleColor
                                opacity: 0.7
                                MouseArea {
                                    anchors.fill: parent
                                    onClicked: page.openSettings(modelData)
                                }
                            }

                            Switch {
                                // checked 不用绑定（会被 Switch 内部写清），由 syncRows 手动设置
                                property var modRef: modelData
                                checked: false
                                onClicked: page.toggleModule(modelData)

                                Component.onCompleted: {
                                    var arr = page.switches
                                    arr.push(this)
                                    page.switches = arr
                                    checked = modelData.enabled === true
                                }
                            }
                        }
                    }
                }
            }
        }

        // MD3 滚动条（PixelPlayer ExpressiveScrollBar）—— 作为 Flickable 的兄弟
        // （放进 Flickable 内部会被当成 contentItem 的子项，跟着内容一起滚走）
        ScrollBar {
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            anchors.right: parent.right
            // 底部内缩 = contentPanel 圆角半径(16)：滚动条原本贴着面板底边，
            // 末端被圆角裁掉一截（实测"拖动条被圆角挡住"）。顶部同样内缩，
            // 保持两端对称。
            anchors.topMargin: 16
            anchors.bottomMargin: 16
            target: flick

            // 拖动时显示当前位置模块名的首字母（对齐 PixelPlayer 的
            // extractFastScrollGlyph：跳过开头标点取首个字母/数字，数字统一
            // 显示 "#"，字母转大写）
            dragLabelProvider: (progress) => {
                var mods = page.moduleList
                if (!mods || mods.length === 0) return ""
                var idx = Math.floor(progress * mods.length)
                if (idx < 0) idx = 0
                if (idx >= mods.length) idx = mods.length - 1
                return page.fastScrollGlyph(mods[idx].name)
            }
        }
    }

    // 空分类占位。原来写成 page.count —— Item 上并没有这个属性，求值恒为
    // undefined，`undefined === 0` 为 false → 空分类时这行提示永远不显示。
    Text {
        anchors.centerIn: parent
        visible: page.moduleList.length === 0
        text: "此分类暂无模块"
        color: Theme.color.onSurfaceVariantColor
        font.family: Theme.typography.bodyMedium.family
        font.pixelSize: Theme.typography.bodyMedium.size
    }
}
