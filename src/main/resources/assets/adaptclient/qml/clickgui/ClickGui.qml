import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// ClickGUI —— 架构完全照搬音乐播放器 Main.qml 的已验证模式：
//  1. 分类 = 页面堆叠 + visible 切换（引擎不 measure 不可见子树）→ 切分类零重建、
//     每帧只 layout 当前分类。旧版用单个 Repeater 换 model（qml4j 全量
//     dispose+rebuild ≈150ms 卡顿 + 缓存错位），已彻底删除。
//  2. 模块列表 = 窗口化 Repeater（VirtualSongList 模式）：固定行高 48，
//     windowStart/windowCount 滑动窗口，滚动只平移、跨行才原位改写 index。
//     列表外层 cachedLayout:true —— 行高恒定，滚动时其余子树跳过重测。
//  3. 设置 = 独立覆盖层（PlaylistDetailPage 模式）：点模块行右侧设置按钮滑入
//     覆盖层，列表行高永远固定 → 绝不错位、绝不触发列表重建。
//  4. 行内只用 Switch（开关）+ 设置入口按钮，设置控件全部放覆盖层。
Rectangle {
    id: root
    width: 960
    height: 540
    color: "transparent"

    property int selectedCategory: 0
    property int stateVersion: 0
    property int refreshTick: 0
    onRefreshTickChanged: {
        if (root.refreshTick > 0) root.syncState()
    }
    // 计数摘要用的刷新令牌：qml4j 绑定订阅不到 JS 对象属性（模块 enabled 是
    // 普通 JS 字段），必须靠一个自增的 int 属性把依赖接进来 —— stateVersion
    // 每次变化都推它，标题里的"x / y 已启用"才会跟着重算。
    property int enabledVersion: 0
    onStateVersionChanged: root.enabledVersion++
    onSelectedCategoryChanged: root.enabledVersion++
    property var categories: []
    property string activeModuleName: ""
    // 覆盖层状态
    property bool settingsOpen: false
    property string settingsModuleName: ""
    property var settingsModule: null

    // ── 数据加载 / 状态同步 ──────────────────────────────
    function loadCategories() {
        if (!bridge) return
        try {
            var arr = JSON.parse(bridge.getCategoriesJson())
            if (arr && arr.length > 0) root.categories = arr
        } catch (e) {
        }
        root.syncState()
    }

    function syncState() {
        for (var c = 0; c < categories.length; c++) {
            var cat = categories[c]
            for (var m = 0; m < cat.modules.length; m++) {
                var mod = cat.modules[m]
                if (mod.action === "toggle" && bridge) {
                    mod.enabled = bridge.isModuleEnabled(mod.name)
                    mod.tag = bridge.getModuleTag(mod.name)
                }
            }
        }
        root.stateVersion++
    }

    function toggleModule(mod) {
        if (bridge) {
            mod.enabled = bridge.toggleModule(mod.name)
            mod.tag = bridge.getModuleTag(mod.name)
            root.stateVersion++
        }
    }

    function openSettings(mod) {
        root.settingsModuleName = mod.name
        root.activeModuleName = mod.name
        // 拉取设置 JSON 挂到模块对象（SettingRow 的 model 用）
        var arr = []
        if (bridge) {
            try {
                var parsed = JSON.parse(bridge.getModuleSettings(mod.name))
                if (parsed && parsed.length > 0) arr = parsed
            } catch (e) {
            }
        }
        mod.settings = arr
        root.settingsModule = mod
        root.settingsOpen = true
    }

    function closeSettings() {
        root.settingsOpen = false
        root.activeModuleName = ""
        root.settingsModule = null
        // 设置可能改了 tag → 返回时刷新列表显示
        root.syncState()
    }

    // 写回设置：bridge 改真实 Java 设置 + 同步写回 JS 对象（s.value/s.color）。
    // 关键：qml4j 绑定订阅不到 JS 对象属性，stateVersion++ 重求值读的是 JS 对象 ——
    // 不写回的话界面永远显示旧值（"要按好几下才能选中"、"预览颜色不变"的根源）。
    // 设置行高规则（与 SettingRow.rowH 一致）—— 显式计算，打开设置零收敛
    function settingRowH(s) {
        if (!s) return 44
        var t = s.type
        if (t === "enum" || t === "choice") return 72
        if (t === "int" || t === "double") return 66
        if (t === "string") return 56
        return 44
    }

    // 滚动区内容总高：顶部占位(4) + spacing(10) + 各设置卡片 + 底部留白(24)。
    // 每个设置一张独立卡片，卡片高 = 行高 + 24（上下各 12 padding），卡片间 10。
    // 必须显式计算 —— 依赖 implicitHeight 会在打开时逐轮收敛，造成内容上移/错位。
    // 二级界面内容总高（必须与实际 ColumnLayout 布局**逐项对齐**，否则最后一张卡划不到）：
    //   ColumnLayout: 顶部留白 Item(4) + N 张卡 + (N) 个 spacing(8)
    //   卡片高 = settingRowH + 32（Card 内边距 16dp×2）
    //   末尾再留 16dp 底部留白
    // 旧实现：每卡 +24、末尾 +24、且只在 i>0 时补 8 → 实际比内容**少 8N−26 px**，
    // 设置项一多，最后一张卡永远划不到底（用户实测："二级界面最后一个栏目划不到"）
    function settingsContentH() {
        var h = 4                         // 顶部留白 Item
        var n = 0
        var mod = root.settingsModule
        if (!mod || !mod.settings || mod.settings.length === 0) {
            h += 72                       // 空态卡片
            n = 1
        } else {
            for (var i = 0; i < mod.settings.length; i++) {
                h += root.settingRowH(mod.settings[i]) + 32
                n++
            }
        }
        h += 8 * n                        // ColumnLayout.spacing（n 张卡 + 顶部 Item → n 个间隙）
        return h + 16                     // 底部留白
    }

    // Hero 副标题："分类 · 已启用/未启用"（依赖 enabledVersion 触发重算）
    function settingsSubtitle() {
        var cat = root.selectedCategory >= 0 && root.selectedCategory < root.categories.length
            ? root.categories[root.selectedCategory].name : ""
        var turnedOn = root.settingsModule ? root.settingsModule.enabled === true : false
        return cat + " · " + (turnedOn ? "已启用" : "未启用")
    }

    function applySetting(s, value) {
        if (bridge) bridge.setModuleSetting(root.settingsModuleName, s.name, value)
        if (!s) return
        // 按类型写回 JS 对象
        if (s.type === "bool") {
            s.value = (value === true)
        } else if (s.type === "int") {
            s.value = Math.round(Number(value))
        } else if (s.type === "double" || s.type === "slider") {
            s.value = Number(value)
        } else if (s.type === "enum" || s.type === "choice") {
            // value = 选项索引 → 同步到 options 数组的 selected
            s.value = Number(value)
        } else if (s.type === "color") {
            // value = "#RRGGBB" → 更新 color 对象（curColor 读它）
            var c = root.hexToRgb(String(value))
            s.color = { r: c.r, g: c.g, b: c.b, a: 255 }
            s.value = String(value)
        } else if (s.type === "string") {
            s.value = String(value)
        }
        root.stateVersion++
    }

    function fmtNum(v) {
        var r = Math.round(v * 100) / 100
        if (Math.abs(r - Math.round(r)) < 0.0001) return Math.round(r) + ""
        return r + ""
    }

    function curColor(s) {
        if (!s || !s.color) return "#888888"
        return "#" + s.color.r.toString(16).padStart(2, "0")
                  + s.color.g.toString(16).padStart(2, "0")
                  + s.color.b.toString(16).padStart(2, "0")
    }

    function hexStr(r, g, b) {
        return "#" + r.toString(16).padStart(2, "0")
                  + g.toString(16).padStart(2, "0")
                  + b.toString(16).padStart(2, "0")
    }

    Component.onCompleted: loadCategories()

    // ── 导航栏（音乐播放器 NavigationRail 原样：圆角毛玻璃 + 图标/文字胶囊）──
            NavigationRail {
        id: rail
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        extended: true
        width: 80            // MD3: NavigationRail 标准宽度 80dp
        // 与右侧内容大卡同一毛玻璃观感（跟随主题，浅色模式不发黑）
        glassTint: Qt.rgba(Theme.color.surfaceContainerHigh.r, Theme.color.surfaceContainerHigh.g,
                           Theme.color.surfaceContainerHigh.b, 0.62)
        currentIndex: root.selectedCategory
        model: root.navItems
        onItemClicked: {
            root.selectedCategory = index
            root.stateVersion++
        }
    }

    property var navItems: {
        var items = []
        for (var i = 0; i < categories.length; i++)
            items.push({ icon: categories[i].icon, text: categories[i].name })
        return items
    }

    // 分类 i 的模块数组（越界返回 []）
    function categoryModules(i) {
        if (i < 0 || i >= root.categories.length) return []
        var mods = root.categories[i].modules
        return mods ? mods : []
    }

    // 分类 i 的图标（越界返回空串）
    function categoryIcon(i) {
        return (i >= 0 && i < root.categories.length) ? root.categories[i].icon : ""
    }

    // ── 内容区（音乐播放器 pageWrap 同款：surfaceContainer 实色 + 圆角 + 裁剪）──
    Rectangle {
        id: contentPanel
        anchors.left: rail.right
        anchors.leftMargin: 8
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        radius: 16
        // 积分榜式毛玻璃：背景模糊（layer.blurRadius）+ 半透明底色。
        // ⚠ 不能写死黑色：浅色模式下整块会变成黑不透明（"浅色模式爆炸"）。
        //   用主题 surface 半透明 → 深色=深玻璃、浅色=白玻璃，两种模式都跟随主题。
        color: Qt.rgba(Theme.color.surfaceContainer.r, Theme.color.surfaceContainer.g,
                       Theme.color.surfaceContainer.b, 0.62)
        clip: true
        layer.enabled: true
        layer.blurRadius: 24
        // 二级界面打开时整体隐藏：否则它和覆盖层尺寸完全重叠，覆盖层的圆角外
        // 露出来的是这块的底色（看着就是"圆角外不透明"），而不是游戏世界。
        visible: !root.settingsOpen
        // 不加 Behavior on color —— ColorAnimation 接管属性后就不再响应 scheme
        // 更新（控件"卡在上一个颜色"的根因），详见 Main.qml 顶部注释。

        // 顶栏 —— 对齐 PixelPlayer 的 TopAppBar（LibraryScreen.kt:864 / 911）：
        //   容器完全透明，没有任何渐变/分隔线装饰
        //   标题用 **primary 色 + ExtraBold 大字号**（PixelPlayer 是 40sp + letterSpacing 1）
        //   两侧是 MD3 FilledIconButton（填充式图标按钮，不是描边式 standard）
        Rectangle {
            id: headerBar
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            height: 64          // MD3: TopAppBar(small) 64dp
            color: "transparent"
            clip: true

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 14
                spacing: 0

                // 左：分类图标（FilledIconButton / surfaceContainerLow 容器）
                Rectangle {
                    Layout.alignment: Qt.AlignVCenter
                    width: 40
                    height: 40
                    radius: 12
                    color: Theme.color.surfaceContainerLow
                    Text {
                        anchors.centerIn: parent
                        text: root.categoryIcon(root.selectedCategory)
                        font.family: Theme.iconFont.name
                        font.pixelSize: 22
                        color: Theme.color.onSurfaceColor
                    }
                }

                Item { Layout.preferredWidth: 12 }

                // 标题：primary 色大号粗体（PixelPlayer 的做法）
                Text {
                    id: titleText
                    Layout.fillWidth: true
                    // 用 verticalCenterOffset 做位移动画：既保持垂直居中，又不用
                    // 额外包一层 Item（包 Item 会让 qml4j 解析中断，实测过）。
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.verticalCenterOffset: slideY
                    property real slideY: 0

                    text: root.selectedCategory >= 0 && root.selectedCategory < root.categories.length
                        ? root.categories[root.selectedCategory].name
                        : ""
                    color: Theme.color.primary
                    font.family: Theme.typography.headlineMedium.family
                    font.pixelSize: 32          // PixelPlayer 40sp；中文 32 更协调
                    font.weight: Font.Bold
                    elide: Text.ElideRight

                    // 切换栏目时：新标题从下方滑入并淡入
                    property string lastTitle: ""
                    onTextChanged: {
                        if (titleText.lastTitle !== "" && titleText.text !== ""
                                && titleText.text !== titleText.lastTitle) {
                            titleInAnim.restart()
                        }
                        titleText.lastTitle = titleText.text
                    }

                    SequentialAnimation {
                        id: titleInAnim
                        running: false
                        ParallelAnimation {
                            // 向上滑入：起点在下方 22px，滑到 0
                            NumberAnimation {
                                target: titleText; property: "slideY"
                                from: 22; to: 0; duration: 260; easing.type: Easing.OutCubic
                            }
                            NumberAnimation {
                                target: titleText; property: "opacity"
                                from: 0.2; to: 1; duration: 260; easing.type: Easing.OutCubic
                            }
                        }
                    }
                }

                // 主题模式选择器已移到「渲染」分类页顶部（见 ModuleListPage 的
                // showThemeSelector）。顶栏保持只有 分类图标 + 标题 + 音乐按钮 ——
                // 272px 宽的分段控件挤在标题旁边会把标题的可读宽度压到很窄。
                Item { Layout.preferredWidth: 10 }

                // 右：音乐播放器（FilledIconButton / primaryContainer 容器）
                Rectangle {
                    Layout.alignment: Qt.AlignVCenter
                    width: 40
                    height: 40
                    radius: 12
                    color: Theme.color.primaryContainer
                    Text {
                        anchors.centerIn: parent
                        text: "music_note"
                        font.family: Theme.iconFont.name
                        font.pixelSize: 22
                        color: Theme.color.onPrimaryContainerColor
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            if (bridge) bridge.openMusicPlayer()
                        }
                    }
                }
            }
        }

        // 页面堆叠：静态声明每个分类页面（Main.qml 同款 —— 不用 Repeater 动态生成，
        // 避免 Repeater delegate 在 visible 切换时的重建/状态丢失），visible 切换
        // 引擎不 measure 不可见子树。
        Item {
            id: pages
            anchors.top: parent.top
            anchors.topMargin: 64     // MD3: TopAppBar(small) 64dp
            // 内容卡（ModuleListPage 的 cardBg）四周留边距：贴着边框会很挤，
            // 缩一圈后与面板边缘留 10px 呼吸感（左/右/下都留）
            anchors.left: parent.left
            anchors.leftMargin: 10
            anchors.right: parent.right
            anchors.rightMargin: 10
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 10

            ModuleListPage {
                anchors.fill: parent
                visible: root.selectedCategory === 0
                moduleList: root.categoryModules(0)
                stateVersion: root.stateVersion
                onToggleModule: root.toggleModule(mod)
                onOpenSettings: root.openSettings(mod)
            }
            ModuleListPage {
                anchors.fill: parent
                visible: root.selectedCategory === 1
                moduleList: root.categoryModules(1)
                stateVersion: root.stateVersion
                onToggleModule: root.toggleModule(mod)
                onOpenSettings: root.openSettings(mod)
            }
            ModuleListPage {
                anchors.fill: parent
                visible: root.selectedCategory === 2
                moduleList: root.categoryModules(2)
                stateVersion: root.stateVersion
                onToggleModule: root.toggleModule(mod)
                onOpenSettings: root.openSettings(mod)
            }
            ModuleListPage {
                anchors.fill: parent
                visible: root.selectedCategory === 3
                moduleList: root.categoryModules(3)
                stateVersion: root.stateVersion
                // 渲染分类顶部放主题模式选择器（从右侧面板移入）
                showThemeSelector: true
                onToggleModule: root.toggleModule(mod)
                onOpenSettings: root.openSettings(mod)
            }
            ModuleListPage {
                anchors.fill: parent
                visible: root.selectedCategory === 4
                moduleList: root.categoryModules(4)
                stateVersion: root.stateVersion
                onToggleModule: root.toggleModule(mod)
                onOpenSettings: root.openSettings(mod)
            }
        }
    }

    // ── 设置覆盖层（SettingsPage 结构：返回头 + Flickable + 卡片）──
    Rectangle {
        id: settingsOverlay
        // ⚠ 不要同时写 anchors.left 和 x —— anchors 会算出 x，两者互相覆盖，
        // 打开时位置就不定了（"有时第二菜单没在正确位置"）。这里完全用显式
        // 坐标：x 只由 rail 宽度 + 入场位移决定。
        x: rail.x + rail.width + 8 + (root.settingsOpen ? 0 : 40)
        y: 0
        width: root.width - (rail.x + rail.width + 8)
        height: root.height
        radius: 16
        // 与主 contentPanel 同一观感（同样跟随主题，避免浅色模式发黑）
        color: Qt.rgba(Theme.color.surfaceContainer.r, Theme.color.surfaceContainer.g,
                       Theme.color.surfaceContainer.b, 0.62)
        layer.enabled: true
        layer.blurRadius: 24
        // clip 必须有：contentPanel 有而这里原本没有 → Hero 顶部那几层满宽的
        // 渐晕矩形会溢出圆角，把四个圆角填成直角（"圆角外没有透明"）。
        // 同理 blur layer 也是按 bounding box 合成的，会糊掉圆角 → 一并去掉；
        // 它下面盖的是不透明的 contentPanel，毛玻璃本来也看不出效果。
        clip: true

        visible: root.settingsOpen
        opacity: root.settingsOpen ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        Behavior on x { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }

        // 点击空白处不穿透到下层
        MouseArea { anchors.fill: parent; z: -1 }

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            // 二级顶栏与主界面保持同样的 64px 节奏：返回、主题模式、模块开关。
            RowLayout {
                Layout.fillWidth: true
                Layout.preferredHeight: 64
                Layout.leftMargin: 12
                Layout.rightMargin: 14
                spacing: 0
                IconButton {
                    Layout.alignment: Qt.AlignVCenter
                    type: "standard"; icon: "arrow_back"
                    onClicked: root.closeSettings()
                }
                Item { Layout.preferredWidth: 8 }
                // 主题模式选择器已移到「渲染」分类页顶部（见 ModuleListPage）
                Item { Layout.fillWidth: true }
                Text {
                    Layout.alignment: Qt.AlignVCenter
                    text: "启用模块"
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodyMedium.family
                    font.pixelSize: Theme.typography.bodyMedium.size
                }
                Item { Layout.preferredWidth: 8 }
                Switch {
                    id: overlaySwitch
                    Layout.alignment: Qt.AlignVCenter
                    // checked 不用绑定（Switch 内部写会清绑定），onClicked 手动管理
                    checked: root.settingsModule ? root.settingsModule.enabled === true : false
                    onClicked: {
                        if (root.settingsModule) {
                            root.toggleModule(root.settingsModule)
                            // 手动写回真实状态（绑定已清，直接赋值生效）
                            checked = root.settingsModule.enabled === true
                        }
                    }
                }
            }

            // ── 模块身份区：与主标题栏同一语言（容器图标 + primary 标题）──
            Rectangle {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                Layout.preferredHeight: 80
                radius: 16
                color: Theme.color.surfaceContainerHigh

                Rectangle {
                    id: modIconBg
                    anchors.left: parent.left
                    anchors.leftMargin: 16
                    anchors.verticalCenter: parent.verticalCenter
                    width: 40
                    height: 40
                    radius: 12
                    color: Theme.color.surfaceContainerLow
                }
                Text {
                    anchors.centerIn: modIconBg
                    text: root.categoryIcon(root.selectedCategory)
                    font.family: Theme.iconFont.name
                    font.pixelSize: 22
                    color: Theme.color.onSurfaceColor
                }

                ColumnLayout {
                    anchors.left: modIconBg.right
                    anchors.leftMargin: 12
                    anchors.right: parent.right
                    anchors.rightMargin: 16
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 3

                    Text {
                        Layout.fillWidth: true
                        text: root.settingsModuleName
                        color: Theme.color.primary
                        font.family: Theme.typography.titleLarge.family
                        font.pixelSize: Theme.typography.titleLarge.size
                    }
                    Text {
                        Layout.fillWidth: true
                        text: root.enabledVersion >= 0 ? root.settingsSubtitle() : ""
                        color: Theme.color.onSurfaceVariantColor
                        font.family: Theme.typography.bodySmall.family
                        font.pixelSize: Theme.typography.bodySmall.size
                    }
                }
            }

            Flickable {
                id: settingsFlick
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                contentWidth: width
                // 显式总高度（不依赖 implicitHeight 运行时收敛 → 打开零错位/可滚动）
                contentHeight: root.settingsContentH()

                ColumnLayout {
                    id: content
                    width: parent.width
                    spacing: 8          // MD3: 4dp 网格

                    Item { Layout.preferredHeight: 4 }   // 顶部留白

                    // 每个设置一张独立卡片（MD3 设置页分组观感，比挤在一个长卡片里
                    // 层次清楚得多）。SettingRow 的父不再是 Layout，所以必须显式给
                    // height —— 它只声明了 Layout.preferredHeight，在非 Layout 父下
                    // 高度为 0，内部 Loader(anchors.fill) 会整个塌陷。
                    Repeater {
                        model: root.settingsModule && root.settingsModule.settings
                            ? root.settingsModule.settings
                            : []

                        Rectangle {
                            Layout.fillWidth: true
                            Layout.leftMargin: 16
                            Layout.rightMargin: 16
                            Layout.preferredHeight: root.settingRowH(modelData) + 32   // MD3: Card 内边距 16dp
                            radius: 12          // MD3: Card = Medium 12dp
                            color: Theme.color.surfaceContainerLow   // MD3: 卡片用低一级容器色
                            border.width: 1
                            border.color: Theme.color.outlineVariant

                            SettingRow {
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.leftMargin: 16
                                anchors.rightMargin: 16
                                anchors.verticalCenter: parent.verticalCenter
                                height: root.settingRowH(modelData)
                                setting: modelData
                                rootRef: root
                            }
                        }
                    }

                    // 空态（同样是独立卡片，视觉上与有设置时保持同一语言）
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.leftMargin: 16
                        Layout.rightMargin: 16
                        Layout.preferredHeight: 72
                        radius: 12          // MD3: Card = Medium 12dp
                        color: Theme.color.surfaceContainerLow   // MD3: 卡片用低一级容器色
                        border.width: 1
                        border.color: Theme.color.outlineVariant
                        visible: !root.settingsModule
                            || !root.settingsModule.settings
                            || root.settingsModule.settings.length === 0

                        RowLayout {
                            anchors.centerIn: parent
                            spacing: 8
                            Text {
                                text: "tune"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 20
                                color: Theme.color.onSurfaceVariantColor
                            }
                            Text {
                                text: "该模块没有可调设置"
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodyMedium.family
                                font.pixelSize: Theme.typography.bodyMedium.size
                            }
                        }
                    }
                }
            }
        }

        // 二级界面的滚动条。放在外层 ColumnLayout **之后**，直接锚定到 Flickable ——
        // 放进 ColumnLayout 里会被当成一行占掉布局空间。
        ScrollBar {
            anchors.top: settingsFlick.top
            anchors.bottom: settingsFlick.bottom
            anchors.right: parent.right
            // 上下各内缩 = 面板圆角半径(16)。不缩的话滚动条两端伸进圆角区域，
            // 被 settingsOverlay 的 clip + radius 裁掉一截（实测"拖动条被圆角
            // 挡住"）。滚动条自身的 thumb 位置是按 height 归一化的，缩短轨道
            // 只是把整段滚动范围映射到更短的条上，映射关系不变。
            anchors.topMargin: 16
            anchors.bottomMargin: 16
            target: settingsFlick
        }
    }

    // ── 颜色选择对话框（Rise ColorValueComponent 移植：HSV 渐变取色器）──
    // 结构（对齐 Rise 6.9.5）：
    //  1. 饱和度/亮度面板：白→色相色（x=饱和度）+ 黑→透明（y=亮度）
    //  2. 色相渐变条：红→黄→绿→青→蓝→紫（横向）
    //  3. 当前色预览方块 + RGB 数值 + HEX
    // 渐变用 Canvas.onPaint 逐条画（qml4j 无 LinearGradient API，色条密集=渐变，
    // 与 CanvasPieChart 同款画法）。
    Dialog {
        id: colorDialog
        title: "选择颜色"
        acceptText: "确定"
        rejectText: "取消"
        showAcceptButton: true
        showRejectButton: false
        onAccepted: {
            root.applyPickerColor()
            root.pickerSetting = ""
        }
        onRejected: {
            root.pickerSetting = ""
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 10

            // 当前色 + 设置名
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Rectangle {
                    width: 36
                    height: 36
                    radius: 10
                    color: root.pickerColor
                }
                Text {
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignVCenter
                    text: root.pickerSetting
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                }
            }

            // 饱和度/亮度面板（200×140，Rise: aza=105/azb=120 缩放）
            Canvas {
                id: satCanvas
                Layout.fillWidth: true
                Layout.preferredHeight: 140
                width: 240
                height: 140

                // 当前色相（0-1），由色相条拖动更新
                property real hue: root.pickerHue

                onPaint: {
                    const ctx = getContext("2d")
                    const w = width
                    const h = height
                    ctx.clearRect(0, 0, w, h)
                    // 面板：逐条画。底 = 白→色相色（x 轴饱和度）；再叠加 黑→透明（y 轴亮度）
                    // Rise: ColorUtil.a(黑透明, ColorUtil.a(色相色, 白, sat), bright)
                    const steps = 32
                    for (let y = 0; y < steps; y++) {
                        const bright = 1.0 - y / (steps - 1)  // 顶部亮、底部黑
                        for (let x = 0; x < steps; x++) {
                            const sat = x / (steps - 1)
                            // 白→色相色 按 sat 混
                            const base = root.hsvToRgb(hue, sat, 1.0)
                            // 结果→黑 按 (1-bright) 混
                            const r = Math.round(base.r * bright)
                            const g = Math.round(base.g * bright)
                            const b = Math.round(base.b * bright)
                            ctx.fillStyle = root.hexStr(r, g, b)
                            ctx.fillRect(x * w / steps, y * h / steps, w / steps + 1, h / steps + 1)
                        }
                    }
                    // 选中十字（当前饱和度/亮度位置）
                    const cx = root.pickerSat * w
                    const cy = (1.0 - root.pickerBright) * h
                    ctx.fillStyle = "#ffffff"
                    ctx.fillRect(cx - 1, cy - 5, 2, 10)
                    ctx.fillRect(cx - 5, cy - 1, 10, 2)
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: {
                        root.pickerSat = mouseX / satCanvas.width
                        root.pickerBright = 1.0 - mouseY / satCanvas.height
                        root.updatePickerColor()
                        satCanvas.requestPaint()
                    }
                }
            }

            // 色相渐变条（红→黄→绿→青→蓝→紫）
            Canvas {
                id: hueCanvas
                Layout.fillWidth: true
                Layout.preferredHeight: 18
                width: 240
                height: 18

                onPaint: {
                    const ctx = getContext("2d")
                    const w = width
                    const h = height
                    ctx.clearRect(0, 0, w, h)
                    const steps = 64
                    for (let i = 0; i < steps; i++) {
                        const hue = i / (steps - 1)
                        const c = root.hsvToRgb(hue, 1.0, 1.0)
                        ctx.fillStyle = root.hexStr(c.r, c.g, c.b)
                        ctx.fillRect(i * w / steps, 0, w / steps + 1, h)
                    }
                    // 当前色相指示
                    const hx = root.pickerHue * w
                    ctx.fillStyle = "#ffffff"
                    ctx.fillRect(hx - 1, 0, 2, h)
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: {
                        root.pickerHue = Math.max(0, Math.min(1, mouseX / hueCanvas.width))
                        root.updatePickerColor()
                        hueCanvas.requestPaint()
                        satCanvas.requestPaint()
                    }
                }
            }

            // RGB + HEX 显示
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Text {
                    Layout.fillWidth: true
                    text: root.pickerHex
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                }
            }
        }
    }

    // ── HSV 取色器状态与工具 ──
    property string pickerSetting: ""
    property string pickerColor: "#ff0000"
    property string pickerHex: "#ff0000"
    property real pickerHue: 0
    property real pickerSat: 1
    property real pickerBright: 1

    // 打开取色器时把当前颜色解析成 HSV
    function openPicker(s) {
        root.pickerSetting = s.name
        root.pickerColor = root.curColor(s)
        var c = root.hexToRgb(root.pickerColor)
        var hsv = root.rgbToHsv(c.r, c.g, c.b)
        root.pickerHue = hsv.h
        root.pickerSat = hsv.s
        root.pickerBright = hsv.v
        root.updatePickerColor()
        colorDialog.open()
    }

    // 选中变化 → 更新预览
    function updatePickerColor() {
        var c = root.hsvToRgb(root.pickerHue, root.pickerSat, root.pickerBright)
        root.pickerColor = root.hexStr(c.r, c.g, c.b)
        root.pickerHex = root.pickerColor
    }

    // 确定 → 写回设置（applySetting 会同步 JS 对象 + stateVersion++）
    function applyPickerColor() {
        if (root.settingsModule && root.pickerSetting !== "") {
            root.applySetting({ name: root.pickerSetting, type: "color" }, root.pickerColor)
        }
    }

    // HSV→RGB（h 0-1, s 0-1, v 0-1），返回 {r,g,b} 0-255
    function hsvToRgb(h, s, v) {
        var i = Math.floor(h * 6)
        var f = h * 6 - i
        var p = v * (1 - s)
        var q = v * (1 - f * s)
        var t = v * (1 - (1 - f) * s)
        var r, g, b
        switch (i % 6) {
            case 0: r = v; g = t; b = p; break
            case 1: r = q; g = v; b = p; break
            case 2: r = p; g = v; b = t; break
            case 3: r = p; g = q; b = v; break
            case 4: r = t; g = p; b = v; break
            default: r = v; g = p; b = q; break
        }
        return { r: Math.round(r * 255), g: Math.round(g * 255), b: Math.round(b * 255) }
    }

    // RGB→HSV（0-255 → h 0-1, s 0-1, v 0-1）
    function rgbToHsv(r, g, b) {
        var rn = r / 255, gn = g / 255, bn = b / 255
        var max = Math.max(rn, gn, bn), min = Math.min(rn, gn, bn)
        var d = max - min
        var h = 0
        if (d !== 0) {
            if (max === rn) h = ((gn - bn) / d) % 6
            else if (max === gn) h = (bn - rn) / d + 2
            else h = (rn - gn) / d + 4
            h = h / 6
            if (h < 0) h += 1
        }
        return { h: h, s: max === 0 ? 0 : d / max, v: max }
    }

    // "#rrggbb" → {r,g,b}
    function hexToRgb(hex) {
        var t = String(hex || "#ff0000").replace("#", "")
        if (t.length === 8) t = t.substring(2)  // #aarrggbb → rrggbb
        if (t.length !== 6) return { r: 255, g: 0, b: 0 }
        return {
            r: parseInt(t.substring(0, 2), 16),
            g: parseInt(t.substring(2, 4), 16),
            b: parseInt(t.substring(4, 6), 16)
        }
    }
}
