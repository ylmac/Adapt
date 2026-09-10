import QtQuick
import QtQuick.Layouts
import md3.Core

// 设置行：一行一个设置，控件按类型用 Loader.sourceComponent 分发。
// 关键：qml4j 的 Component {} 是独立作用域，内部不能直接引用外部 id（row.setting
// 解析不到 → 控件不渲染）。必须像 Menu.qml 一样：Loader 暴露属性（itemData），
// Component 内通过 Loader 的属性访问。这里 Loader 暴露 rowData（当前设置对象）
// 和 rootRef（ClickGui 根），Component 内统一用 rowData / rootRef。
Item {
    id: row

    property var setting: null
    property var rootRef: null

    Layout.fillWidth: true
    Layout.preferredHeight: row.rowH
    // 兜底高度：父项不是 Layout 时 Layout.preferredHeight 不起作用，Item 高度会
    // 变成 0 → 内部 Loader(anchors.fill) 塌陷，控件整个看不见。
    height: row.rowH

    property int rowH: {
        if (row.setting === null) return 44
        var t = row.setting.type
        if (t === "enum" || t === "choice") return 72   // 名称 + SegmentedButton 两行
        if (t === "int" || t === "double") return 66
        if (t === "string") return 56
        return 44
    }

    // 按类型分发控件（Menu.qml 同款：sourceComponent 三元链）
    Loader {
        id: loader
        anchors.fill: parent
        active: row.setting !== null
        // 暴露给 Component 的数据（Menu.qml 的 itemData 模式）
        property var rowData: row.setting
        property var rootRef: row.rootRef

        sourceComponent: row.setting === null ? null
            : row.setting.type === "enum" ? enumComp
            : row.setting.type === "choice" ? enumComp
            : row.setting.type === "bool" ? boolComp
            : row.setting.type === "int" ? sliderComp
            : row.setting.type === "double" ? sliderComp
            : row.setting.type === "color" ? colorComp
            : row.setting.type === "string" ? stringComp
            : row.setting.type === "button" ? buttonComp
            : otherComp
    }

    // ── enum（模式/选项选择：SegmentedButton）──
    Component {
        id: enumComp
        ColumnLayout {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            spacing: 4
            Text {
                Layout.fillWidth: true
                text: rowData ? rowData.name : ""
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.bodyMedium.family
                font.pixelSize: Theme.typography.bodyMedium.size
            }
            SegmentedButton {
                Layout.fillWidth: true
                // 内联生成 buttons（qml4j Component 作用域不能调外部函数）
                buttons: {
                    var opts = rowData.options || []
                    var cur = rowData.value || 0
                    var out = []
                    for (var i = 0; i < opts.length; i++) {
                        out.push({ text: opts[i], selected: i === cur })
                    }
                    return out
                }
                onClicked: {
                    if (rootRef) {
                        rootRef.applySetting(rowData, index)
                    }
                }
            }
        }
    }

    // ── bool（开关）──
    Component {
        id: boolComp
        RowLayout {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            spacing: 12
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: rowData ? rowData.name : ""
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.bodyMedium.family
                font.pixelSize: Theme.typography.bodyMedium.size
            }
            Switch {
                Layout.alignment: Qt.AlignVCenter
                checked: rootRef && rootRef.stateVersion >= 0
                    ? (rowData ? rowData.value === true : false)
                    : false
                onClicked: {
                    if (rootRef) {
                        rootRef.applySetting(rowData, checked)
                        checked = rowData.value === true
                    }
                }
            }
        }
    }

    // ── int / double（滑块）──
    Component {
        id: sliderComp
        ColumnLayout {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            spacing: 0
            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Text {
                    Layout.fillWidth: true
                    text: rowData ? rowData.name : ""
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.bodyMedium.family
                    font.pixelSize: Theme.typography.bodyMedium.size
                }
                Text {
                    id: numText
                    text: rootRef && rowData ? rootRef.fmtNum(rowData.value) : ""
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                }
            }
            Slider {
                Layout.fillWidth: true
                from: rowData ? rowData.min : 0
                to: rowData ? rowData.max : 1
                stepSize: rowData ? rowData.step : 1
                value: rowData ? rowData.value : 0
                onMoved: {
                    if (rootRef) {
                        rowData.value = value
                        numText.text = rootRef.fmtNum(value)
                        rootRef.applySetting(rowData, value)
                    }
                }
            }
        }
    }

    // ── color（色板入口）──
    Component {
        id: colorComp
        RowLayout {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            spacing: 12
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: rowData ? rowData.name : ""
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.bodyMedium.family
                font.pixelSize: Theme.typography.bodyMedium.size
            }
            Rectangle {
                Layout.alignment: Qt.AlignVCenter
                width: 28
                height: 28
                radius: 14
                border.width: 1
                border.color: Theme.color.outline
                color: rootRef && rootRef.stateVersion >= 0 && rowData
                    ? rootRef.curColor(rowData) : "#888888"
            }
            Button {
                Layout.alignment: Qt.AlignVCenter
                type: "outlined"; text: "选择"
                onClicked: {
                    if (rootRef) rootRef.openPicker(rowData)
                }
            }
        }
    }

    // ── string（输入框）──
    Component {
        id: stringComp
        TextField {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            type: "outlined"
            label: rowData ? rowData.name : ""
            text: rootRef && rootRef.stateVersion >= 0 && rowData
                ? (rowData.value || "") : ""
            onTextChanged: {
                if (rootRef) rootRef.applySetting(rowData, text)
            }
        }
    }

    // ── button ──
    Component {
        id: buttonComp
        Button {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            type: "outlined"
            text: rowData ? rowData.name : ""
            onClicked: {
                if (rootRef) rootRef.applySetting(rowData, "click")
            }
        }
    }

    // ── other ──
    Component {
        id: otherComp
        Text {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            text: rowData ? (rowData.name + ": " + rowData.value) : ""
            color: Theme.color.onSurfaceColor
            font.family: Theme.typography.bodyMedium.family
            font.pixelSize: Theme.typography.bodyMedium.size
        }
    }
}
