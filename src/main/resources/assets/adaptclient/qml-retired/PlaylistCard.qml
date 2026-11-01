import QtQuick
import md3.Core
import "."

// A playlist tile. Its size is fixed from `tile` (computed by the page from the
// available width), set as implicitWidth so GridLayout sizes its columns
// correctly. No dependence on the layout-assigned width → no feedback loop.
Item {
    id: card

    property var playlistId: 0
    property string name: ""
    property int count: 0
    property string coverUrl: ""
    property string coverThumbPath: ""
    property real tile: 160
    // Latched when a long-press fired, so the release's click doesn't also open
    // the playlist; cleared when the menu closes (or on the guarded click).
    property bool _menuArmed: false
    signal clicked()

    implicitWidth: tile
    implicitHeight: tile + 52

    // Explicit sibling anchors instead of a Column: qml4j's positioner skips an
    // invisible child and does not re-flow when a child's `visible` flips false→true,
    // so the count label — hidden while the playlist is empty — landed at (0,0) over
    // the cover the moment a first track pushed count past 0 (fixed only by a restart,
    // which rebuilt the card already-visible). Anchored positions are resolved
    // regardless of visibility, so the toggle no longer moves anything.
    CoverImage {
        id: cover
        x: 4
        y: 4
        width: card.width - 8
        height: card.width - 8
        radius: 14
        icon: "queue_music"
        iconSize: 44
        fadeIn: true
        source: card.coverUrl || card.coverThumbPath
    }
    Text {
        id: nameLabel
        x: 4
        anchors.top: cover.bottom
        anchors.topMargin: 6
        width: card.width - 8
        text: card.name
        color: Theme.color.onSurfaceColor
        fontSize: 14
        elide: Text.ElideRight
    }
    Text {
        x: 4
        anchors.top: nameLabel.bottom
        anchors.topMargin: 2
        width: card.width - 8
        visible: card.count > 0
        text: card.count + " 首"
        color: Theme.color.onSurfaceVariantColor
        fontSize: 12
    }

    // Single pointer handler for the whole tile: a normal tap opens the playlist,
    // while a stationary long-press opens the context menu at the press point.
    // Keeping one handler also prevents the release after a long-press from
    // leaking through and opening the playlist behind the menu.
    Ripple {
        id: cardMa
        anchors.fill: parent
        clipRadius: 14
        rippleColor: Theme.color.onSurfaceColor
        longPressEnabled: true
        onClicked: {
            if (card._menuArmed) { card._menuArmed = false; return }
            card.clicked()
        }
        onLongPressed: { card._menuArmed = true; card._openMenu() }
    }

    // Context menu, eagerly instantiated so `item` is ready whenever the
    // long-press fires (qml4j's Loader is async; its model stays empty until
    // rebuild() on open, so idle cost is just an empty menu subtree).
    Loader {
        id: menuLoader
        active: true
        sourceComponent: PlaylistContextMenu {
            playlistId: card.playlistId
            onOpenRequested: card.clicked()
            onClosed: card._menuArmed = false
        }
    }
    function _openMenu() {
        if (menuLoader.item === null) return
        menuLoader.item.rebuild()
        menuLoader.item.open(cardMa, cardMa.pressX, cardMa.pressY)
    }
}
