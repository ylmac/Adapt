import QtQuick
import "."

// CoverImage wrapper with AMLL React Full's playback-state motion: pause shrinks the
// artwork to 75%, while resume restores it with a small overshoot. qml4j does
// not expose QML's BezierSpline easing, so a linear clock is mapped through the
// original CSS cubic-bezier curves explicitly. This intentionally uses composition
// rather than inheriting CoverImage: qml4j does not reliably instantiate the visual
// children of a custom QML type derived from another custom QML type.
Item {
    id: cover

    property alias source: image.source
    property alias radius: image.radius
    property alias icon: image.icon
    property alias iconSize: image.iconSize
    property alias fadeIn: image.fadeIn

    property bool playing: true
    property real baseScale: 1.0
    property real pauseShrinkAspect: 0.75

    property real playbackScale: playing ? 1.0 : pauseShrinkAspect
    property real motionProgress: 1.0
    property real motionFrom: playbackScale
    property real motionTo: playbackScale
    property bool motionResuming: playing
    property bool motionReady: false

    scale: baseScale * playbackScale

    CoverImage {
        id: image
        anchors.fill: parent
    }

    function bezierCoordinate(t, p1, p2) {
        var oneMinusT = 1.0 - t
        return 3.0 * oneMinusT * oneMinusT * t * p1
                + 3.0 * oneMinusT * t * t * p2 + t * t * t
    }

    function amllTiming(progress, resuming) {
        if (progress <= 0.0) return 0.0
        if (progress >= 1.0) return 1.0

        var x1 = resuming ? 0.3 : 0.4
        var y1 = 0.2
        var x2 = resuming ? 0.2 : 0.1
        var y2 = resuming ? 1.4 : 1.0
        var low = 0.0
        var high = 1.0
        var t = progress
        // CSS cubic-bezier timing is y(t) at the t whose x(t) equals the linear
        // clock. Bisection is stable for both AMLL curves and plenty accurate at
        // display refresh rates.
        for (var i = 0; i < 14; i++) {
            t = (low + high) * 0.5
            if (bezierCoordinate(t, x1, x2) < progress)
                low = t
            else
                high = t
        }
        return bezierCoordinate((low + high) * 0.5, y1, y2)
    }

    function animatePlaybackState() {
        motionAnim.stop()
        motionFrom = playbackScale
        motionTo = playing ? 1.0 : pauseShrinkAspect
        motionResuming = playing
        motionProgress = 0.0
        motionAnim.duration = playing ? 500 : 600
        motionAnim.restart()
    }

    onPlayingChanged: {
        if (motionReady)
            animatePlaybackState()
    }

    onMotionProgressChanged: {
        playbackScale = motionFrom
                + (motionTo - motionFrom) * amllTiming(motionProgress, motionResuming)
    }

    Component.onCompleted: {
        playbackScale = playing ? 1.0 : pauseShrinkAspect
        motionReady = true
    }

    NumberAnimation {
        id: motionAnim
        target: cover
        property: "motionProgress"
        from: 0.0
        to: 1.0
        duration: 500
        easing.type: Easing.Linear
    }
}
