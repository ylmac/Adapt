import QtQuick
import QtQuick.Layouts
import md3.Core

// Shared ClickGUI appearance selector. Uses the same persisted DesktopSettings
// policy as the music player: 0=system, 1=light, 2=dark, 3=themed dark surface.
Item {
    id: control

    implicitWidth: selector.implicitWidth
    implicitHeight: selector.implicitHeight

    SegmentedButton {
        id: selector
        anchors.fill: parent
        buttons: [
            { text: "跟随系统", selected: settings.darkMode === 0 },
            { text: "浅色", selected: settings.darkMode === 1 },
            { text: "深色", selected: settings.darkMode === 2 },
            { text: "主题色", selected: settings.darkMode === 3 }
        ]
        onClicked: settings.darkMode = index
    }
}
