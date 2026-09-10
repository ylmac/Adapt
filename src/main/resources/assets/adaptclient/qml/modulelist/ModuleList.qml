import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 模块列表 HUD —— Rise 6.9.5 Interface 模块的 QML 完整翻译。
// 本文件 = Rise 的 Interface.java：布局/动画/排序/数据构建。
// 四个模式组件（ModernList/ClassicList/WurstList/CreidaList）注册在根 qmldir，
// 组织方式与 SettingsPage → SettingsSection* 完全一致。
//
// 布局算法（Rise Interface.onRender2D，逐字翻译）：
// - animationTime 0..10：启用 +elapsed/f 到 10（Modern/Wurst/Creida f=100ms，Classic f=10ms），
//   禁用 -elapsed/f 到 0，为 0 不绘制
// - 目标位置：右上角右对齐 x = screenW - nameWidth - tagWidth - edgeOffset，
//   关闭中的条目滑出屏幕右侧 x = screenW + width
// - y 从顶部向下堆叠，每行 + moduleSpacing
// - 位置 lerp：pos += (target - pos) * 0.015 * elapsed（帧率无关）
// - 每帧按 -(nameWidth + tagWidth) 排序（最长在上）
Item {
    id: root
    // width/height 由 Java 端 MemberAccess.writeMember 设置为屏幕尺寸
    width: 854
    height: 480

    // 当前界面模式（由 Java ModuleListOverlay.cycleMode() 切换，每帧同步）
    property var mode: "Modern"

    // 计算后的显示条目（Rise ArrayListEntry 列表）
    property var entries: []

    // 每帧布局参数（Rise: 各模式 onRender2D 调用 n()/setEdgeOffset()）
    property real spacing: 20
    property real edgeOffset: 10
    property real fontSize: 18

    // 动画状态表（Rise ArrayListEntry: animationTime/position）
    property var anim: ({})
    property var posX: ({})
    property var posY: ({})
    property int lastTime: 0

    // 每帧驱动（Rise: Render2DEvent 每帧触发）
    Timer {
        interval: 16
        repeat: true
        running: true
        onTriggered: root.step()
    }

    // ── 文字宽度（Rise Font.getStringWidth；CJK 全角 = 1.0×字号，ASCII ≈ 0.55×） ──
    function textWidth(text, size) {
        if (!text) return 0
        var w = 0
        for (var i = 0; i < text.length; i++) {
            var c = text.charCodeAt(i)
            w += (c > 0x2E7F) ? size : size * 0.55
        }
        return w
    }

    // ── Rise Themes 主题色（getBlendFactor / getAccentColor 逐字翻译） ──
    function hexToRgb(hex) {
        hex = String(hex).replace("#", "")
        if (hex.length < 6) return { r: 180, g: 180, b: 180 }
        return { r: parseInt(hex.substr(0, 2), 16), g: parseInt(hex.substr(2, 2), 16), b: parseInt(hex.substr(4, 2), 16) }
    }
    function lerpColor(a, b, t) {
        var ca = hexToRgb(a), cb = hexToRgb(b)
        var r = Math.round(ca.r + (cb.r - ca.r) * t)
        var g = Math.round(ca.g + (cb.g - ca.g) * t)
        var bl = Math.round(ca.b + (cb.b - ca.b) * t)
        var h = function(v) { return (v < 16 ? "0" : "") + v.toString(16) }
        return "#" + h(r) + h(g) + h(bl)
    }
    // Rise Themes.getBlendFactor: sin(now/600 + x*0.005 + y*0.06) * 0.5 + 0.5
    function blendFactor(x, y) {
        return Math.sin(Date.now() / 600 + x * 0.005 + y * 0.06) * 0.5 + 0.5
    }
    // Rise Themes.getAccentColor（triColor: rA/rB/rC = primary/secondary/tertiary）
    function accentColor(x, y) {
        var rA = Theme.color.primary
        var rB = Theme.color.secondary
        var rC = Theme.color.tertiary
        var d = blendFactor(x, y)
        return d <= 0.5 ? lerpColor(rB, rA, d * 2) : lerpColor(rC, rB, (d - 0.5) * 2)
    }

    // ── Rise Interface.onRender2D 布局（逐字翻译） ──
    function applyModeLayout() {
        if (mode === "Modern")  { spacing = 20; edgeOffset = 10; fontSize = 18 }
        else if (mode === "Classic") { spacing = 20; edgeOffset = 3;  fontSize = 18 }
        else if (mode === "Wurst")   { spacing = 19; edgeOffset = 4;  fontSize = 18 }
        else                        { spacing = 21; edgeOffset = 3;  fontSize = 24 } // Creida
    }

    function step() {
        var now = Date.now()
        var elapsed = lastTime === 0 ? 16 : now - lastTime
        lastTime = now

        mode = moduleList.getMode()
        applyModeLayout()

        // 1. 读模块 + 更新 animationTime（Rise: f = mode=="Classic" ? 10 : 100）
        var f = mode === "Classic" ? 10 : 100
        var mods = moduleList.getModules()
        var i, m, t, at
        for (i = 0; i < mods.length; i++) {
            m = mods[i]
            t = anim[m.name] || 0
            if (m.enabled) t = Math.min(t + elapsed / f, 10)
            else t = Math.max(t - elapsed / f, 0)
            anim[m.name] = t
        }

        // 2. 过滤可见条目（Rise createArrayList: animationTime!=0，且排除界面模块自身）
        var active = []
        for (i = 0; i < mods.length; i++) {
            m = mods[i]
            if ((anim[m.name] || 0) > 0 && m.name !== "模块列表") {
                active.push(m)
            }
        }
        // 排序（Rise: 按 -(nameWidth + tagWidth) 降序，最长在上）
        active.sort(function(a, b) {
            var wa = textWidth(a.name, fontSize) + (a.tag ? textWidth(a.tag, fontSize) + 3 : 0)
            var wb = textWidth(b.name, fontSize) + (b.tag ? textWidth(b.tag, fontSize) + 3 : 0)
            return wb - wa
        })

        // 3. 位置计算 + lerp（Rise onRender2D 的 targetPosition + MathUtil.m）
        var stackY = 0
        var out = []
        for (i = 0; i < active.length; i++) {
            m = active[i]
            var nameW = textWidth(m.name, fontSize)
            var tagW = m.tag ? textWidth(m.tag, fontSize) + 3 : 0
            var w = nameW + tagW
            var tx = root.width - w
            var ty = stackY
            if (!m.enabled && (anim[m.name] || 0) < 10) {
                tx = root.width + w            // 关闭中：滑出屏幕右侧
            } else {
                stackY += spacing
            }
            tx -= edgeOffset
            ty += edgeOffset

            var px = posX[m.name] !== undefined ? posX[m.name] : tx
            var py = posY[m.name] !== undefined ? posY[m.name] : ty
            var l = 0.015 * elapsed           // Rise: MathUtil.m(pos, target, 0.015 * elapsed)
            px += (tx - px) * l
            py += (ty - py) * l
            at = anim[m.name] || 0
            if (Math.abs(px - tx) <= 0.5 && Math.abs(py - ty) <= 0.5 && (at === 0 || at === 10)) {
                px = tx; py = ty
            }
            posX[m.name] = px
            posY[m.name] = py

            out.push({
                name: m.name,
                tag: m.tag || "",
                hasTag: !!(m.tag && m.tag !== ""),
                animTime: at,
                posX: px,
                posY: py,
                nameWidth: nameW,
                tagWidth: tagW,
                color: accentColor(px, py)   // Fade 模式颜色（Rise: getAccentColor）
            })
        }
        entries = out
    }

    // ── 四个模式组件（Rise InterfaceModeValue: Modern 默认，与 SettingsSection* 同组织方式） ──
    ModernList {
        visible: root.mode === "Modern"
        entries: root.entries
    }
    ClassicList {
        visible: root.mode === "Classic"
        entries: root.entries
    }
    WurstList {
        visible: root.mode === "Wurst"
        entries: root.entries
    }
    CreidaList {
        visible: root.mode === "Creida"
        entries: root.entries
    }
}