import QtQuick
import QtQuick.Layouts
import QtQuick.Effects
import md3.Core
Item {
    id: control
    
    // API
    property var model: [] // Array of objects: { text, icon, trailingText, trailingIcon, type: "item"|"separator", action: func, enabled: bool, subItems: [] }
    property int menuPadding: 8
    // Cap the popup width so a long item label (e.g. a playlist name in a submenu)
    // can't stretch the menu nearly across the screen; the item text elides instead.
    property real maxWidth: 280
    // Cap the popup height; a longer list (many playlists) scrolls inside.
    property real maxHeight: 360

    // Anchor for the popup, in menuRoot coordinates. open() records the raw desired
    // position; popupContainer.x/y then CLAMP it against menuRoot reactively, so the
    // menu stays on-screen even when its size is still settling (the model was just
    // rebuilt) at open() time — a one-shot clamp read stale/zero dimensions and let
    // the grown popup overflow.
    property var menuRoot: null
    property real targetX: 0
    property real targetY: 0
    
    // Theme Colors
    property var _colors: Theme.color
    property var _shape: Theme.shape
    property var _typography: Theme.typography
    property var _elevation: Theme.elevation
    property var _state: Theme.state

    // Signals
    signal closed()

    // Hidden by default, takes no space
    visible: false
    width: 0
    height: 0
    
    // The Overlay Layer
    Item {
        id: overlayLayer
        visible: false
        
        // Helper to close menu
        function close() { 
            startExitAnimation()
        }
        
        function forceClose() {
             overlayLayer.visible = false
             overlayLayer.parent = control
             overlayLayer.anchors.fill = undefined
        }
        
        // Scrim. Dismiss on press (not just click) so the menu also closes the moment
        // an outside scroll/flick begins — a click is press+move+release and never
        // fires when the finger drags to scroll the content underneath.
        MouseArea {
            anchors.fill: parent
            onPressed: overlayLayer.close()
            z: -1
        }
        
        // Popup Container (This scales up/down, carrying shadow and content)
        Item {
            id: popupContainer
            width: Math.min(control.maxWidth, Math.max(112, contentColumn.implicitWidth))
            height: Math.min(control.maxHeight, contentColumn.implicitHeight + (control.menuPadding * 2))
            // Reactive on-screen clamp (see control.targetX/Y): recomputes whenever the
            // popup's own width/height settle, so a menu opened before layout finished
            // slides fully into view instead of overflowing.
            x: control.menuRoot
               ? Math.max(8, Math.min(control.targetX, control.menuRoot.width - width - 8))
               : control.targetX
            y: control.menuRoot
               ? Math.max(8, Math.min(control.targetY, control.menuRoot.height - height - 8))
               : control.targetY
            
            // Animation. Explicit from/to ParallelAnimations (like Dialog) instead of a
            // state toggle: setting state="closed" then "open" in one call could collapse
            // to just "open" (no from-closed transition) and pop in with no animation.
            scale: 0.8
            opacity: 0
            transformOrigin: Item.TopLeft

            ParallelAnimation {
                id: enterAnim
                NumberAnimation { target: popupContainer; property: "scale"; from: 0.8; to: 1.0; duration: 200; easing.type: Easing.OutCubic }
                NumberAnimation { target: popupContainer; property: "opacity"; from: 0.0; to: 1.0; duration: 150 }
            }
            ParallelAnimation {
                id: exitAnim
                onFinished: { overlayLayer.forceClose(); control.closed() }
                NumberAnimation { target: popupContainer; property: "opacity"; from: 1.0; to: 0.0; duration: 150 }
                NumberAnimation { target: popupContainer; property: "scale"; from: 1.0; to: 0.8; duration: 150; easing.type: Easing.InCubic }
            }

            // Shadow Source
            Rectangle {
                id: shadowSource
                anchors.fill: parent
                radius: menuBackground.radius
                color: _colors.surfaceContainer
                visible: false
            }
            
            // Shadow Effect
            MultiEffect {
                source: shadowSource
                anchors.fill: shadowSource
                shadowEnabled: true
                shadowColor: _colors.shadow
                shadowBlur: _elevation.level2 * 0.5
                shadowVerticalOffset: _elevation.level2
                shadowOpacity: 0.2
                z: 0
                // Opacity is inherited from parent (popupContainer), no need to double apply
            }
            
            // Menu Background & Content
            Rectangle {
                id: menuBackground
                z: 1
                anchors.fill: parent
                color: _colors.surfaceContainer
                radius: _shape.cornerExtraSmall
                clip: true
                
                Flickable {
                    id: menuFlick
                    anchors.fill: parent
                    anchors.topMargin: control.menuPadding
                    anchors.bottomMargin: control.menuPadding
                    contentWidth: width
                    contentHeight: contentColumn.implicitHeight
                    clip: true

                    ColumnLayout {
                        id: contentColumn
                        spacing: 0
                        width: menuFlick.width

                        Repeater {
                            model: control.model
                            delegate: Loader {
                                Layout.fillWidth: true
                                sourceComponent: modelData.type === "separator" ? separatorComponent : itemComponent

                                property var itemData: modelData

                                required property var modelData
                                required property int index
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Animation Helpers
    function startEntranceAnimation() {
        exitAnim.stop()
        enterAnim.restart()
    }

    function startExitAnimation() {
        enterAnim.stop()
        exitAnim.restart()
    }

    // Components
    Component {
        id: separatorComponent
        Item {
            implicitWidth: 112
            implicitHeight: 17 // 1px + 16dp padding
            Layout.fillWidth: true
            Rectangle {
                anchors.centerIn: parent
                width: parent.width
                height: 1
                color: _colors.outlineVariant
            }
        }
    }
    
    Component {
        id: itemComponent
        Item {
            id: menuItem
            implicitWidth: Math.max(112, row.implicitWidth + 24)
            implicitHeight: 48
            Layout.fillWidth: true
            
            property bool itemEnabled: itemData.enabled !== undefined ? itemData.enabled : true
            property bool hasSubMenu: !!itemData.subItems && itemData.subItems.length > 0
            
            // Submenu Loader. qml4j resolves Loader.source against the RESOURCE ROOT
            // (assets/), not the defining file's directory as Qt does — so a bare
            // "Menu.qml" fails to load (this file lives at md3/Core/) and the submenu
            // silently never appears. Use the full module-relative path.
            Loader {
                id: subMenuLoader
                active: hasSubMenu
                source: "md3/Core/Menu.qml"
                onLoaded: {
                    item.model = itemData.subItems
                }
            }
            
            // State Layer
            Rectangle {
                anchors.fill: parent
                color: _colors.onSurfaceColor
                opacity: {
                    if (!itemEnabled) return 0
                    if (itemRipple.pressed) return _state.pressedStateLayerOpacity
                    if (itemRipple.containsMouse) return _state.hoverStateLayerOpacity
                    return 0
                }
                Behavior on opacity { NumberAnimation { duration: 150 } }
            }

            // Ripple — the sole pointer handler. The desktop hover MouseArea that used
            // to sit on top (submenu-on-hover) swallowed touch presses under qml4j, so
            // taps never reached this Ripple; removed. Submenus open on tap below.
            Ripple {
                id: itemRipple
                anchors.fill: parent
                enabled: itemEnabled
                rippleColor: _colors.onSurfaceColor
                onClicked: {
                    if (hasSubMenu) {
                        // Open submenu
                        var sub = subMenuLoader.item
                        if (sub) {
                             sub.open(menuItem, menuItem.width, -8) // Slight overlap top
                        }
                    } else {
                        if (itemData.action && typeof itemData.action === "function") {
                            itemData.action()
                        }
                        if (control && control.close) control.close()
                    }
                }
            }
            
            RowLayout {
                id: row
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 12
                
                // Icon
                Text {
                    visible: !!itemData.icon
                    text: itemData.icon || ""
                    font.family: Theme.iconFont.name
                    font.pixelSize: 24
                    color: _colors.onSurfaceColor
                    opacity: itemEnabled ? 1 : 0.38
                    Layout.alignment: Qt.AlignVCenter
                }
                
                // Text
                Text {
                    text: itemData.text || ""
                    font.family: _typography.labelLarge.family
                    font.pixelSize: _typography.labelLarge.size
                    font.weight: _typography.labelLarge.weight
                    color: _colors.onSurfaceColor
                    opacity: itemEnabled ? 1 : 0.38
                    elide: Text.ElideRight
                    verticalAlignment: Text.AlignVCenter
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignVCenter
                }
                
                // Trailing Text
                Text {
                    visible: !!itemData.trailingText
                    text: itemData.trailingText || ""
                    font.family: _typography.labelLarge.family
                    font.pixelSize: _typography.labelLarge.size
                    font.weight: _typography.labelLarge.weight
                    color: _colors.onSurfaceColor
                    opacity: itemEnabled ? 1 : 0.38
                    horizontalAlignment: Text.AlignRight
                    Layout.alignment: Qt.AlignVCenter
                }
                
                // Trailing Icon (or Arrow for submenu)
                Text {
                    visible: !!itemData.trailingIcon || hasSubMenu
                    text: hasSubMenu ? "arrow_right" : (itemData.trailingIcon || "")
                    font.family: Theme.iconFont.name
                    font.pixelSize: 24
                    color: _colors.onSurfaceVariantColor
                    opacity: itemEnabled ? 1 : 0.38
                    Layout.alignment: Qt.AlignVCenter
                }
            }
            
        }
    }
    
    // Logic
    function open(target, xOffset, yOffset) {
        if (!target) return
        
        // Find Root
        var root = control
        while (root.parent) {
            root = root.parent
        }
        
        if (root) {
            overlayLayer.parent = root
            overlayLayer.z = 99999
            overlayLayer.anchors.fill = root
            
            var targetPos = root.mapFromItem(target, 0, 0)
            // Record the desired anchor; popupContainer.x/y clamp it reactively against
            // menuRoot so the popup can't overflow even if its size settles after open().
            control.menuRoot = root
            control.targetX = targetPos.x + (xOffset !== undefined ? xOffset : 0)
            control.targetY = targetPos.y + (yOffset !== undefined ? yOffset : 0)

            overlayLayer.visible = true
            startEntranceAnimation()
        }
    }
    
    function close() {
        overlayLayer.close()
    }
}
