import QtQuick
import md3.Core

/**
 * MD3 "Expressive" 滚动条 —— 对齐 PixelPlayer 的 ExpressiveScrollBar。
 *
 * 交互态（按下/拖动/列表惯性滚动）时 handle 从细条**展开**成宽条，中间浮出
 * 展开图标；拖动时左侧浮出圆形首字母气泡：
 *   静止       宽 = thickness (8)
 *   交互态     宽 = indicatorExpandedWidth + boost (24 + 4 = 28)
 *   动画       宽度/图标/气泡 均 180~200ms
 * 轨道为 secondaryContainer 细线，被 handle 切成上下两段，各留 trackGap 间隙。
 *
 * 拖动用**相对位移（delta）**而非绝对坐标换算：只依赖鼠标的移动量，不依赖
 * mouse.y 的坐标系原点，避免不同容器/偏移下出现"拉的方向是反的"。
 */
Item {
    id: bar

    // ── API ──
    property Flickable target: null
    property int orientation: Qt.Vertical

    /**
     * 拖动时气泡显示的文字回调：function(progress) -> string
     * progress 为 0~1 的滚动进度，返回空字符串则不显示气泡。
     * 模块列表传入取首字母的实现（对齐 PixelPlayer 的 extractFastScrollGlyph）。
     */
    property var dragLabelProvider: null

    // ── 尺寸（与 ExpressiveScrollBar 同名参数对齐）──
    property real thickness: 8
    property real indicatorExpandedWidth: 24
    property real indicatorExpandedWidthBoost: 4
    property real minHeight: 48
    property real trackGap: 8
    property real paddingEnd: 2
    property real indicatorRightCornerRadius: 6

    // ── 气泡（ExpressiveScrollBar 的 dragLabel* 参数）──
    property real dragLabelSize: 40
    property real dragLabelGap: 10

    readonly property bool vertical: orientation === Qt.Vertical
    readonly property real expandedWidth: indicatorExpandedWidth + indicatorExpandedWidthBoost

    width: vertical ? expandedWidth + paddingEnd : (parent ? parent.width : 0)
    height: vertical ? (parent ? parent.height : 0) : expandedWidth + paddingEnd

    visible: {
        if (target == null) return false
        if (vertical) return target.contentHeight > target.height + 1
        return target.contentWidth > target.width + 1
    }

    // ── 交互状态（全部写成绑定表达式，命令式赋值会清掉绑定）──
    property bool isPressed: dragArea.pressed
    property bool isDragging: false
    property bool isInteracting: isPressed || isDragging || (target != null && target.moving)

    property real handleWidth: isInteracting ? expandedWidth : thickness
    Behavior on handleWidth { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }

    // ── 几何 ──
    property real thumbHeight: {
        if (target == null) return minHeight
        if (vertical) {
            var r = target.height / Math.max(1, target.contentHeight)
            return Math.max(minHeight, r * height)
        }
        var rh = target.width / Math.max(1, target.contentWidth)
        return Math.max(minHeight, rh * width)
    }

    property real thumbPos: {
        if (target == null) return 0
        if (vertical) {
            var maxY = Math.max(0, height - thumbHeight)
            var range = target.contentHeight - target.height
            if (range <= 0) return 0
            return Math.max(0, Math.min(target.contentY / range * maxY, maxY))
        }
        var maxX = Math.max(0, width - thumbHeight)
        var rangeH = target.contentWidth - target.width
        if (rangeH <= 0) return 0
        return Math.max(0, Math.min(target.contentX / rangeH * maxX, maxX))
    }

    // 当前滚动进度（供 dragLabelProvider 使用）
    property real scrollProgress: {
        if (target == null) return 0
        if (vertical) {
            var range = target.contentHeight - target.height
            return range > 0 ? Math.max(0, Math.min(target.contentY / range, 1)) : 0
        }
        var rangeH = target.contentWidth - target.width
        return rangeH > 0 ? Math.max(0, Math.min(target.contentX / rangeH, 1)) : 0
    }

    // ── 轨道：被 handle 切成两段 ──
    Rectangle {
        visible: bar.vertical && bar.thumbPos > bar.trackGap
        x: bar.width - bar.paddingEnd - bar.thickness
        y: 0
        width: bar.thickness
        height: Math.max(0, bar.thumbPos - bar.trackGap)
        radius: bar.thickness / 2
        color: Theme.color.secondaryContainer
    }
    Rectangle {
        visible: bar.vertical && (bar.thumbPos + bar.thumbHeight + bar.trackGap) < bar.height
        x: bar.width - bar.paddingEnd - bar.thickness
        y: bar.thumbPos + bar.thumbHeight + bar.trackGap
        width: bar.thickness
        height: Math.max(0, bar.height - (bar.thumbPos + bar.thumbHeight + bar.trackGap))
        radius: bar.thickness / 2
        color: Theme.color.secondaryContainer
    }
    Rectangle {
        visible: !bar.vertical
        x: 0
        y: bar.height - bar.paddingEnd - bar.thickness
        width: bar.width
        height: bar.thickness
        radius: bar.thickness / 2
        color: Theme.color.secondaryContainer
    }

    // ── Handle ──
    Rectangle {
        id: thumb
        x: bar.vertical ? (bar.width - bar.paddingEnd - bar.handleWidth) : bar.thumbPos
        y: bar.vertical ? bar.thumbPos : (bar.height - bar.paddingEnd - bar.handleWidth)
        width: bar.vertical ? bar.handleWidth : bar.thumbHeight
        height: bar.vertical ? bar.thumbHeight : bar.handleWidth
        radius: bar.indicatorRightCornerRadius
        color: Theme.color.primary

        // 展开图标（UnfoldMore）：交互态淡入 + 轻微放大
        Text {
            anchors.centerIn: parent
            text: "unfold_more"
            font.family: Theme.iconFont.name
            font.pixelSize: 18
            color: Theme.color.onPrimaryColor
            opacity: bar.isInteracting ? 1 : 0
            scale: bar.isInteracting ? 1 : 0.6
            Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
            Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        }
    }

    // 按下/拖动时给 handle 加投影（MD3 elevation）。
    // qml4j 没有 Rectangle 的 shadow 属性，要像 TimePicker/DatePicker 那样用
    // MultiEffect 指向源元素并设 z: -1 把阴影垫在下面。
    MultiEffect {
        source: thumb
        anchors.fill: thumb
        shadowEnabled: bar.isInteracting
        shadowColor: Theme.color.shadow
        shadowBlur: 8
        shadowVerticalOffset: 2
        shadowOpacity: 0.35
        z: -1
    }

    // ── 拖动气泡（PixelPlayer 的 dragLabel）──
    property string dragLabelText: {
        if (!bar.isDragging || bar.dragLabelProvider == null) return ""
        var t = bar.dragLabelProvider(bar.scrollProgress)
        return t == null ? "" : String(t)
    }

    Rectangle {
        id: dragBubble
        // 全部用绑定表达式（不做命令式赋值，赋值会清掉绑定）
        property bool showing: bar.isDragging && bar.dragLabelText !== ""
        property real dragSlide: showing ? 0 : 8
        opacity: showing ? 1 : 0
        scale: showing ? 1 : 0.82
        visible: opacity > 0.01

        width: bar.dragLabelSize
        height: bar.dragLabelSize
        radius: bar.dragLabelSize / 2
        color: Theme.color.secondaryContainer

        // 位置：handle 左侧，垂直居中于 handle
        x: bar.vertical
            ? (bar.width - bar.paddingEnd - bar.handleWidth) - bar.dragLabelSize - bar.dragLabelGap - dragSlide
            : bar.thumbPos + (bar.thumbHeight / 2) - (bar.dragLabelSize / 2)
        y: bar.vertical
            ? bar.thumbPos + (bar.thumbHeight / 2) - (bar.dragLabelSize / 2)
            : (bar.height - bar.paddingEnd - bar.handleWidth) - bar.dragLabelSize - bar.dragLabelGap - dragSlide

        Behavior on opacity { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
        Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
        Behavior on dragSlide { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }

        Text {
            anchors.centerIn: parent
            text: bar.dragLabelText
            color: Theme.color.onSecondaryContainerColor
            font.family: Theme.typography.titleLarge.family
            font.pixelSize: 20
            font.weight: Font.Bold
        }
    }

    // ── 拖动：相对位移（delta）驱动 ──
    MouseArea {
        id: dragArea
        anchors.fill: parent
        hoverEnabled: true

        // 关键：滚动条浮在 Flickable 之上，不加这个的话 Flickable 会同时响应同一
        // 个拖动（列表自己滚 + 滚动条拖 → 互相打架表现为抖动/跳变）。
        preventStealing: true

        property real lastPos: 0

        // 信号处理器必须用箭头函数 —— qml4j 不认 `onXxx: function(mouse) {}` 写法
        // （项目里 Ripple / Slider / TimePicker 一律是 `(mouse) => {}`）。
        onPressed: (mouse) => {
            if (bar.target == null) return
            var mousePos = bar.vertical ? mouse.y : mouse.x
            var tPos = bar.vertical ? thumb.y : thumb.x
            var tSize = bar.vertical ? thumb.height : thumb.width

            lastPos = mousePos
            // 按在轨道上也进入拖动态（PixelPlayer 的 onDragStart 同样处理），并立即跳转
            bar.isDragging = true
            // 拖动期间关掉 Flickable 自身的滚动：否则列表和滚动条同时响应同一个
            // 手势，表现为抖动/跳变。
            if (bar.target != null) bar.target.interactive = false
            if (mousePos < tPos || mousePos > tPos + tSize) {
                var trackSize = bar.vertical ? bar.height : bar.width
                var maxPos = Math.max(1, trackSize - tSize)
                var ratio = Math.max(0, Math.min((mousePos - tSize / 2) / maxPos, 1))
                if (bar.vertical) {
                    var maxCY = bar.target.contentHeight - bar.target.height
                    bar.target.contentY = Math.max(0, Math.min(ratio * maxCY, maxCY))
                } else {
                    var maxCX = bar.target.contentWidth - bar.target.width
                    bar.target.contentX = Math.max(0, Math.min(ratio * maxCX, maxCX))
                }
            }
        }

        onPositionChanged: (mouse) => {
            if (!pressed || !bar.isDragging || bar.target == null) return
            var mousePos = bar.vertical ? mouse.y : mouse.x
            var delta = mousePos - lastPos
            lastPos = mousePos

            var trackSize = bar.vertical ? bar.height : bar.width
            var tSize = bar.vertical ? thumb.height : thumb.width
            var maxPos = Math.max(1, trackSize - tSize)

            // delta > 0（向下/向右拖） → 内容同向前进，handle 跟随鼠标。
            // 用相对位移而非绝对坐标，避免容器偏移导致方向错乱。
            if (bar.vertical) {
                var maxCY = bar.target.contentHeight - bar.target.height
                if (maxCY <= 0) return
                bar.target.contentY = Math.max(0, Math.min(bar.target.contentY + (delta / maxPos) * maxCY, maxCY))
            } else {
                var maxCX = bar.target.contentWidth - bar.target.width
                if (maxCX <= 0) return
                bar.target.contentX = Math.max(0, Math.min(bar.target.contentX + (delta / maxPos) * maxCX, maxCX))
            }
        }

        onReleased: {
            bar.isDragging = false
            if (bar.target != null) bar.target.interactive = true
        }
        onCanceled: {
            bar.isDragging = false
            if (bar.target != null) bar.target.interactive = true
        }
    }
}
