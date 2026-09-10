import QtQuick
import md3.Core
Item {
    id: control

    property real value: 0.0
    property bool indeterminate: false
    property bool wavy: false
    // Roll the wave only while active (playing/buffering). Mirrors PixelPlayer's
    // animatedAmplitude: paused → amplitude 0 → the wave flattens to a static
    // line; playing → the phase advances and the sine "rolls" toward the
    // thumb (the played portion visibly scrolls).
    property bool active: true
    property bool seekable: true
    // Parameterless signal + property payload (qml4j can't read cross-file signal
    // params — same convention as BottomNav.navigate). dragValue carries the
    // committed normalized position (0..1) of the last press/move/release.
    signal seekRequested()
    property real dragValue: 0.0

    // Displayed progress. Follows `value` during normal playback; animated by
    // catchUpAnim during the indeterminate→determinate handoff (smooth grow
    // from zero when loading finishes, instead of snapping to the current position).
    property real _displayValue: 0.0
    property bool _catchingUp: false
    property bool _drag: false
    property real _interact: 0.0
    // 用户跳过进度后，阻止收敛逻辑覆盖刚设定的位置，直到播放器位置更新到目标
    property bool _seekHeld: false

    // Smooth catch-up from 0 to the real progress when indeterminate→determinate.
    function beginDeterminateTransition() {
        catchUpAnim.stop();
        var targetValue = Math.max(0.0, Math.min(1.0, control.value));
        control._displayValue = 0.0;
        if (targetValue <= 0.0) {
            control._catchingUp = false;
            return;
        }
        control._catchingUp = true;
        catchUpAnim.to = targetValue;
        catchUpAnim.restart();
    }

    // Enter indeterminate immediately. Both renderers keep their visual latch on
    // exit until their active sweep animation has naturally completed.
    onIndeterminateChanged: {
        if (indeterminate) {
            catchUpAnim.stop();
            control._catchingUp = false;
            if (control.wavy)
                wavyCanvas._indet = true;
            else
                straightIndicator.beginSweep();
        } else if (!control.wavy) {
            straightIndicator.requestFinish();
        }
    }

    onValueChanged: {
        if (!control.wavy) {
            var vi = straightIndicator._indet;
            if (!control.indeterminate && !vi && !control._catchingUp)
                control._displayValue = Math.max(0.0, Math.min(1.0, control.value));
        } else {
            if (!control.indeterminate && !wavyCanvas._indet && !control._catchingUp)
                control._displayValue = Math.max(0.0, Math.min(1.0, control.value));
        }
    }

    onWavyChanged: {
        if (!control.wavy) {
            wavyCanvas._indet = false;
            if (control.indeterminate)
                straightIndicator.beginSweep();
            else
                straightIndicator.cancelSweep();
            if (!control.indeterminate && !control._catchingUp)
                control._displayValue = Math.max(0.0, Math.min(1.0, control.value));
        } else {
            straightIndicator.cancelSweep();
            wavyCanvas._indet = control.indeterminate;
            if (!control.indeterminate && !control._catchingUp)
                control._displayValue = Math.max(0.0, Math.min(1.0, control.value));
        }
    }

    onVisibleChanged: {
        if (control.visible && !control.wavy && control.indeterminate
                && straightIndicator._indet
                && (straightIndicator._bar1Done || straightIndicator._bar2Done))
            straightIndicator.beginSweep();
    }

    NumberAnimation {
        id: catchUpAnim
        target: control
        property: "_displayValue"
        duration: 450
        easing.type: Easing.OutCubic
        onFinished: {
            control._catchingUp = false;
            if (!control.indeterminate)
                control._displayValue = Math.max(0.0, Math.min(1.0, control.value));
        }
    }

    implicitWidth: 200
    // Wavy layout is tall enough for the vertical thumb handle (PixelPlayer's
    // thumbLineHeight is 24dp; ours caps at 20 so round caps stay in-bounds).
    implicitHeight: wavy ? 22 : 4

    property var _colors: Theme.color

    // Standard Linear Progress
    Rectangle {
        id: track
        anchors.fill: parent
        visible: !control.wavy
        color: _colors.surfaceContainerHighest
        radius: height / 2
        clip: true

        // Determinate Indicator
        Rectangle {
            visible: !straightIndicator._indet
            height: parent.height
            width: parent.width * Math.max(0, Math.min(1, control._displayValue))
            color: _colors.primary
            radius: height / 2
        }

        // Item.clip is rectangular in qml4j, so it cannot preserve the track's
        // rounded outline when an animated bar crosses an edge. Mask the unchanged
        // MD3 animation output with the actual rounded-track shape instead.
        Rectangle {
            id: straightMask
            anchors.fill: parent
            radius: track.radius
            color: "black"
            visible: false
        }

        // Indeterminate Indicator
        Item {
            id: straightIndicator
            anchors.fill: parent
            visible: false

            // Like the wave, keep a visual indeterminate latch after loading ends.
            // Each of the two original animations completes its current independent
            // cycle; only after both finish do we grow the real progress from zero.
            property bool _indet: false
            property bool _bar1Done: true
            property bool _bar2Done: true

            function beginSweep() {
                bar1Anim.stop();
                bar2Anim.stop();
                bar1.x = -width;
                bar1.width = 0.0;
                bar2.x = -width;
                bar2.width = 0.0;
                _indet = true;
                if (control.visible && !control.wavy) {
                    _bar1Done = false;
                    _bar2Done = false;
                    bar1Anim.restart();
                    bar2Anim.restart();
                } else {
                    _bar1Done = true;
                    _bar2Done = true;
                }
            }

            Component.onCompleted: {
                if (control.indeterminate && !control.wavy)
                    beginSweep();
            }

            function cancelSweep() {
                bar1Anim.stop();
                bar2Anim.stop();
                _bar1Done = true;
                _bar2Done = true;
                _indet = false;
            }

            function barFinished(firstBar) {
                if (control.indeterminate && _indet && !control.wavy
                        && control.visible) {
                    if (firstBar)
                        bar1Anim.restart();
                    else
                        bar2Anim.restart();
                    return;
                }
                if (firstBar)
                    _bar1Done = true;
                else
                    _bar2Done = true;
                finishIfReady();
            }

            function finishIfReady() {
                if (!_indet || control.indeterminate || !_bar1Done || !_bar2Done)
                    return;
                _indet = false;
                control.beginDeterminateTransition();
            }

            function requestFinish() {
                if (!_indet)
                    return;
                if (!bar1Anim.running)
                    _bar1Done = true;
                if (!bar2Anim.running)
                    _bar2Done = true;
                finishIfReady();
            }

            // First bar
            Rectangle {
                id: bar1
                height: parent.height
                color: _colors.primary
                radius: height / 2

                SequentialAnimation {
                    id: bar1Anim
                    onFinished: straightIndicator.barFinished(true)

                    ParallelAnimation {
                        NumberAnimation { target: bar1; property: "x"; from: -parent.width; to: parent.width; duration: 2000; easing.type: Easing.InOutCubic }
                        SequentialAnimation {
                            NumberAnimation { target: bar1; property: "width"; from: 0; to: parent.width * 0.5; duration: 1000; easing.type: Easing.OutCubic }
                            NumberAnimation { target: bar1; property: "width"; from: parent.width * 0.5; to: 0; duration: 1000; easing.type: Easing.InCubic }
                        }
                    }
                }
            }

            // Second bar (delayed)
            Rectangle {
                id: bar2
                height: parent.height
                color: _colors.primary
                radius: height / 2

                SequentialAnimation {
                    id: bar2Anim
                    onFinished: straightIndicator.barFinished(false)

                    PauseAnimation { duration: 1000 }

                    ParallelAnimation {
                        NumberAnimation { target: bar2; property: "x"; from: -parent.width; to: parent.width; duration: 2000; easing.type: Easing.InOutCubic }
                        SequentialAnimation {
                            NumberAnimation { target: bar2; property: "width"; from: 0; to: parent.width * 0.5; duration: 1000; easing.type: Easing.OutCubic }
                            NumberAnimation { target: bar2; property: "width"; from: parent.width * 0.5; to: 0; duration: 1000; easing.type: Easing.InCubic }
                        }
                    }
                }
            }
        }
    }

    // Wavy Linear Progress
    Canvas {
        id: wavyCanvas
        visible: control.wavy
        anchors.fill: parent
        antialiasing: true
        renderTarget: Canvas.FramebufferObject
        renderStrategy: Canvas.Threaded

        // Trigger repaint when dependencies change
        property color trackColor: control._colors.surfaceContainerHighest
        property color activeColor: control._colors.primary
        onTrackColorChanged: requestPaint()
        onActiveColorChanged: requestPaint()

        property real _phase: 0.0

        // 相位由动画引擎驱动（NumberAnimation），60fps 自然跟随帧率
        NumberAnimation on _phase {
            running: control.wavy && control.visible
            from: 0
            to: Math.PI * 2
            duration: 1000
            loops: Animation.Infinite
        }

        on_PhaseChanged: {
            requestPaint();
            // 相位在 2π→0 边界回绕时处理 indeterminate→determinate 切换。
            // _lastPhase 仅在本 handler 每帧被更新，构成对“上一帧相位”的跟踪；
            // 这样回绕检测与 qplayer-master 的 onPhaseChanged 一致，能在真实回绕点触发。
            if (_phase < _lastPhase) {
                var wasIndeterminate = _indet;
                _indet = control.indeterminate;
                if (wasIndeterminate && !_indet)
                    control.beginDeterminateTransition();
            }
            _lastPhase = _phase;
            // 每次相位变化（≈60fps）同时更新进度收敛和交互形态
            if (!control._drag) {
                if (control._seekHeld) {
                    var target = Math.max(0, Math.min(1, control.value));
                    if (Math.abs(control._displayValue - target) < 0.005) {
                        control._seekHeld = false;
                    }
                } else if (!control._catchingUp) {
                    var target = Math.max(0, Math.min(1, control.value));
                    var prev = control._displayValue;
                    var ad = Math.abs(prev - target);
                    if (ad > 0.1) {
                        control._displayValue = target;
                    } else if (ad > 0.0005) {
                        control._displayValue = prev + (target - prev) * 0.35;
                    }
                }
            }
            var want = control._drag ? 1 : 0;
            var di = control._interact;
            if (Math.abs(di - want) > 0.002) {
                control._interact = di + (want - di) * 0.55;
            } else if (control._interact !== want) {
                control._interact = want;
            }
        }

        // Latched indeterminate state: follows control.indeterminate up immediately,
        // but defers the switch back to determinate to the next wave-cycle boundary.
        property bool _indet: control.indeterminate
        property real _lastPhase: 0.0

        onPaint: {
            var ctx = getContext("2d");
            ctx.reset();

            var w = width;
            var h = height;
            var cy = h / 2;
            var lw = 4;
            ctx.lineWidth = lw;
            ctx.lineCap = "round";
            ctx.lineJoin = "round";
            var m = lw / 2 + 1;
            var x0 = m, x1 = w - m;
            var amplitude = Math.min(4.5, Math.min(h / 4, h / 2 - lw / 2))
                            * (1 - Math.min(1, control._interact))
                            * (control.active ? 1 : 0);
            var frequency = 0.1;
            var phase = wavyCanvas._phase;
            var endX = x0 + (x1 - x0) * Math.max(0, Math.min(1, control._displayValue));

            var it = Math.min(1, control._interact);
            var tw = control._drag ? 6.0 : 5.0;
            var th = control._drag ? 20.0 : 12.0;
            var tX = Math.max(tw / 2 + 1, Math.min(w - tw / 2 - 1, endX));

            // Track (inactive)
            ctx.beginPath();
            ctx.strokeStyle = trackColor;
            if (!wavyCanvas._indet) {
                var trackFrom = Math.min(x1, endX + tw / 2);
                if (trackFrom < x1) {
                    ctx.moveTo(trackFrom, cy);
                    ctx.lineTo(x1, cy);
                }
            }
            ctx.stroke();

            var taperDist = 15.0;
            ctx.beginPath();
            ctx.strokeStyle = activeColor;
            if (wavyCanvas._indet) {
                var indetProgress = ((Date.now() / 1500) % 1.0);
                var span = x1 - x0;
                var barWidth = span * 0.5;
                var startX = x0 + (span + barWidth) * indetProgress - barWidth;
                var endXi = startX + barWidth;
                var begun = false;
                for (var xi = x0; xi <= x1; xi += 1) {
                    if (xi >= startX && xi <= endXi) {
                        var distToStart = xi - startX;
                        var distToEnd = endXi - xi;
                        var taperFactor = Math.min(1.0, Math.min(distToStart, distToEnd) / taperDist);
                        var yi = cy + amplitude * taperFactor * Math.sin((xi * frequency) + phase);
                        if (!begun) { ctx.moveTo(xi, yi); begun = true; }
                        else ctx.lineTo(xi, yi);
                    }
                }
                ctx.stroke();
            } else {
                var started = false;
                for (var xd = x0; xd < endX; xd += 1) {
                    var taper = Math.min(1.0, (endX - xd) / taperDist);
                    var yd = cy + amplitude * taper * Math.sin((xd * frequency) + phase);
                    if (!started) { ctx.moveTo(xd, yd); started = true; }
                    else ctx.lineTo(xd, yd);
                }
                if (started) {
                    var yEnd = cy + amplitude * 0 * Math.sin((endX * frequency) + phase);
                    ctx.lineTo(endX, yEnd);
                }
                ctx.stroke();

                if (it > 0.01) {
                    wavyCanvas.drawCapsule(ctx, tX, cy, tw + 3, th + 3, activeColor, 0.30 * it);
                }
                wavyCanvas.drawCapsule(ctx, tX, cy, tw, th, activeColor, 1.0);
            }
        }

        function drawCapsule(ctx, cx, cyy, tw2, th2, color, alpha) {
            var len = th2 - tw2;
            ctx.strokeStyle = color;
            ctx.lineWidth = tw2;
            ctx.lineCap = "round";
            if (alpha !== 1.0) ctx.globalAlpha = alpha;
            ctx.beginPath();
            ctx.moveTo(cx, cyy - len / 2);
            ctx.lineTo(cx, cyy + len / 2);
            ctx.stroke();
            if (alpha !== 1.0) ctx.globalAlpha = 1.0;
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    // Drag-to-seek
    MouseArea {
        anchors.fill: control
        anchors.topMargin: -10
        anchors.bottomMargin: -10
        enabled: control.seekable && control.visible
        function applyX(x) {
            var w = control.width;
            if (w <= 0) return;
            var v = Math.max(0, Math.min(1, x / w));
            control._displayValue = v;
            control.dragValue = v;
        }
        onPressedChanged: {
            control._drag = pressed && !control.indeterminate;
            if (typeof player !== "undefined" && player.qmlDragDebug !== undefined) {
                player.qmlDragDebug = player.qmlDragDebug + 1;
            }
            if (control._drag || !pressed) {
                applyX(mouseX);
                control.seekRequested();
                if (!pressed) control._seekHeld = true;
            }
            wavyCanvas.requestPaint();
        }
        onPositionChanged: {
            if (!control._drag) return;
            applyX(mouseX);
            control.seekRequested();
        }
    }
}