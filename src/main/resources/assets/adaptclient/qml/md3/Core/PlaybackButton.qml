import QtQuick
import md3.Core

// Circular transport button in the PixelPlayer style: a filled circle (or a
// heavily-rounded "smooth corner" form for the play/pause key) with an icon
// glyph, press feedback via a slight scale dip. Colors are supplied by the
// caller (`fillColor` = container, `iconColor` = content) so each row can
// follow its own roles (primary/onPrimary for the play key, secondary
// container for prev/next, etc.).
Rectangle {
    id: root

    property string iconName: ""
    property color fillColor: "transparent"
    property color iconColor: "#000000"
    property real iconSizePx: 20
    // Corner radius for the "smooth corner" effect the PixelPlayer play
    // button uses (26–60dp); set < width/2 for a rounded squircle, == width/2
    // for a full circle.
    property real cornerRadius: width / 2
    property bool enabled: true
    signal clicked()

    width: 44
    height: 44
    radius: cornerRadius
    color: fillColor
    scale: downArea.pressed ? 0.88 : 1.0
    Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }

    Text {
        anchors.centerIn: parent
        text: root.iconName
        font.family: Theme.iconFont.name
        font.pixelSize: root.iconSizePx
        color: root.iconColor
    }

    MouseArea {
        id: downArea
        anchors.fill: parent
        enabled: root.enabled
        onClicked: root.clicked()
    }
}