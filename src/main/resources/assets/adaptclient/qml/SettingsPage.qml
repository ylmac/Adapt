import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// App settings overlay: appearance (dark-mode policy + Monet dynamic color) and
// an about section. Writes the `settings` context global, which drives
// StyleManager through the Bindings in Main.qml. Section containers are plain
// rounded rectangles sized to their content (md3 Card is fixed-size).
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

    // Catch-all so taps on empty areas don't fall through to the page beneath.
    // Declared first (lowest z); the controls above still receive their events.
    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: "设置"
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
            }
        }

        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            contentWidth: width
            contentHeight: content.implicitHeight + 24

            ColumnLayout {
                id: content
                width: parent.width
                spacing: 14

                SettingsSectionAppearance {
                    Layout.fillWidth: true
                }
                SettingsSectionPlayback {
                    Layout.fillWidth: true
                }
                SettingsSectionCustomApi {
                    Layout.fillWidth: true
                }
                SettingsSectionDesktopLyric {
                    Layout.fillWidth: true
                }
                SettingsSectionWorldLyric {
                    Layout.fillWidth: true
                }
                SettingsSectionStorage {
                    Layout.fillWidth: true
                }
                SettingsSectionLocal {
                    Layout.fillWidth: true
                }
                SettingsSectionAbout {
                    Layout.fillWidth: true
                }
            }
        }
    }

}
