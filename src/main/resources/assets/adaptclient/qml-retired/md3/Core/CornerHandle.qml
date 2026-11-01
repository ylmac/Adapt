import QtQuick
import md3.Core

// 圆角手柄 —— 拖动/缩放的抓取点，可复用（同 Slider/ScrollBar 的定位）。
//
// 形状定义（用户描述）：
//   一个带圆角的正方形描边 → 只留描边、去掉填充 → 取左上角那四分之一 →
//   把这一段的两个端点加上圆角（round line cap）。
// 取的就是**那一段弧**（不含直臂），两端用 lineCap="round" 收圆。
// 弧心落在盒子的内角上，弧身鼓向盒子的外角 —— 也就是远离被装饰元素的那一侧，
// 所以摆放时要把盒子往元素里压一段距离，弧才会贴着元素的角。
//
// 用法（放在容器四角，通常再往外偏几个像素）：
//   CornerHandle {
//       anchors.left: parent.left; anchors.top: parent.top
//       corner: "topLeft"; handleSize: 36; thickness: 3.5
//       color: Theme.color.primary
//   }
//
// 坐标约定：Item 是 handleSize × handleSize，直臂贴在内侧 thickness/2 处，
// 所以描边和圆头都完整落在 Item 内，不会被裁掉。
Item {
    id: control

    /// "topLeft" | "topRight" | "bottomRight" | "bottomLeft"
    property string corner: "topLeft"
    /// Item 边长（同时决定弧半径：radius = handleSize - thickness）
    property real handleSize: 36
    /// 描边宽度（弧的粗细）
    property real thickness: 3.5
    property color color: Theme.color.primary

    implicitWidth: handleSize
    implicitHeight: handleSize

    onCornerChanged: canvas.requestPaint()
    onHandleSizeChanged: canvas.requestPaint()
    onThicknessChanged: canvas.requestPaint()
        onColorChanged: canvas.requestPaint()

    Canvas {
        id: canvas
        anchors.fill: parent

        onPaint: {
            var ctx = getContext("2d")
            ctx.reset()

            var s = control.handleSize
            var t = control.thickness
            var r = s - t            // 弧半径：两端各留 t/2 边距，圆头正好贴边
            var half = t / 2

            ctx.lineWidth = t
            ctx.lineCap = "round"
            ctx.strokeStyle = control.color
            ctx.beginPath()

            // 只有一段弧 —— "带圆角正方形的描边取四分之一"。弧心在盒子的内角上，
            // 弧身鼓向盒子的外角（远离被装饰元素那一侧），两端朝向元素。
            var cx, cy, a0, a1
            if (control.corner === "topLeft") {
                cx = s - half; cy = s - half; a0 = Math.PI; a1 = Math.PI * 1.5
            } else if (control.corner === "topRight") {
                cx = half; cy = s - half; a0 = Math.PI * 1.5; a1 = Math.PI * 2
            } else if (control.corner === "bottomRight") {
                cx = half; cy = half; a0 = 0; a1 = Math.PI * 0.5
            } else {
                cx = s - half; cy = half; a0 = Math.PI * 0.5; a1 = Math.PI
            }

            ctx.arc(cx, cy, r, a0, a1)
            ctx.stroke()
        }
    }
}
