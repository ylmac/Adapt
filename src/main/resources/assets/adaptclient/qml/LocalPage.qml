import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 本地音乐库：按专辑分组展示，复刻 PixelPlayer 的库设计。
// 专辑以卡片网格展示（3:2 封面 + 渐变 + 加粗标题/艺术家/曲目数 + 20dp 圆角），
// 点击进入专辑详情查看歌曲列表。
Item {
    id: page

    property int sortMode: 0     // 0 default(scan order) 1 title 2 artist 3 duration
    property bool sortDesc: false
    property int groupMode: 0    // 0 all 1 by artist 2 by folder
    property string groupValue: ""
    property bool valuePickerOpen: false
    // 专辑详情模式：-1 表示专辑网格，>=0 表示选中专辑的索引
    property int albumIndex: -1

    property var allTracks: player.tracks || []

    // 卡片布局参数（对应 PixelPlayer AlbumGridItemRedesigned）
    readonly property int cardRadius: 20
    readonly property int artRatioH: 2  // 3:2 封面
    readonly property int artRatioW: 3
    readonly property int textAreaH: 84
    readonly property int cardMinW: 168

    // 从曲目提取专辑信息（PixelPlayer 分组键 = 标题 + 专辑艺术家/目录，同名不同艺术家是两个专辑）
    property var albums: {
        var seen = {};
        var out = [];
        for (var i = 0; i < allTracks.length; i++) {
            var t = allTracks[i];
            var albumTitle = t.album || "未知专辑";
            var albumArtist = t.artist || "未知艺术家";
            var key = albumTitle + "|" + albumArtist;
            if (!seen[key]) {
                seen[key] = true;
                out.push({
                    title: albumTitle,
                    artist: albumArtist,
                    cover: "",
                    trackCount: 1
                });
            } else {
                for (var j = 0; j < out.length; j++) {
                    if (out[j].title === albumTitle && out[j].artist === albumArtist) { out[j].trackCount++; break; }
                }
            }
        }
        // 第一首有封面的曲目作为专辑封面（PixelPlayer 用代表曲目的 art）
        for (var a = 0; a < out.length; a++) {
            for (var k = 0; k < allTracks.length; k++) {
                var tt = allTracks[k];
                if ((tt.album || "未知专辑") === out[a].title && (tt.artist || "未知艺术家") === out[a].artist) {
                    var src = tt.coverLocalPath || tt.coverThumbPath || tt.coverUrl || "";
                    if (src !== "") { out[a].cover = src; break; }
                }
            }
        }
        // 按标题排序（PixelPlayer 默认 Title A-Z，大小写不敏感 + 艺术家 tie-break）
        out.sort(function(a, b) {
            var av = (a.title || "").toLowerCase();
            var bv = (b.title || "").toLowerCase();
            if (av < bv) return -1;
            if (av > bv) return 1;
            var aa = (a.artist || "").toLowerCase();
            var ba = (b.artist || "").toLowerCase();
            return aa < ba ? -1 : (aa > ba ? 1 : 0);
        });
        return out;
    }

    // 当前专辑的曲目（按 title+artist 复合键匹配，和分组一致）
    property var albumTracks: {
        if (albumIndex < 0 || albumIndex >= albums.length) return [];
        var albumTitle = albums[albumIndex].title;
        var albumArtist = albums[albumIndex].artist;
        var out = [];
        for (var i = 0; i < allTracks.length; i++) {
            if ((allTracks[i].album || "未知专辑") === albumTitle
                    && (allTracks[i].artist || "未知艺术家") === albumArtist)
                out.push(allTracks[i]);
        }
        return out;
    }

    // 列数固定 2（PixelPlayer GridCells.Fixed(2)）
    readonly property int cols: 2
    readonly property int gap: 8
    readonly property int pad: 14
    readonly property int cardW: ((parent ? parent.width : 360) - pad * 2 - gap) / cols

    function folderOf(t) {
        if (!t || !t.filePath) return "";
        var p = t.filePath.split("\\").join("/");
        var idx = p.lastIndexOf("/");
        return idx >= 0 ? p.substring(0, idx) : "";
    }

    property var groupValues: {
        if (groupMode === 0) return [];
        var seen = {};
        var out = [];
        for (var i = 0; i < allTracks.length; i++) {
            var v = groupMode === 1 ? (allTracks[i].artist || "未知艺术家") : folderOf(allTracks[i]);
            if (v !== "" && !seen[v]) { seen[v] = true; out.push(v); }
        }
        out.sort();
        return out;
    }
    onGroupValuesChanged: if (groupValue !== "" && groupValues.indexOf(groupValue) < 0) groupValue = ""

    property var displayTracks: {
        var list = [];
        for (var i = 0; i < allTracks.length; i++) {
            var t = allTracks[i];
            if (groupMode === 1 && groupValue !== "" && (t.artist || "未知艺术家") !== groupValue) continue;
            if (groupMode === 2 && groupValue !== "" && folderOf(t) !== groupValue) continue;
            list.push(t);
        }
        if (sortMode !== 0) {
            list.sort(function(a, b) {
                var av, bv;
                if (sortMode === 1) { av = (a.title || "").toLowerCase(); bv = (b.title || "").toLowerCase(); }
                else if (sortMode === 2) { av = (a.artist || "").toLowerCase(); bv = (b.artist || "").toLowerCase(); }
                else { av = a.durationMs || 0; bv = b.durationMs || 0; }
                if (av < bv) return -1;
                if (av > bv) return 1;
                return 0;
            });
            if (sortDesc) list.reverse();
        }
        return list;
    }

    // ========== 专辑网格视图 ==========
    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        visible: player.libraryCount > 0 && albumIndex < 0

        // 排序/分组工具栏
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 8
            Layout.topMargin: 8
            Layout.leftMargin: 12
            Layout.rightMargin: 12

            RowLayout {
                Layout.fillWidth: true
                spacing: 6

                Text {
                    text: "排序"
                    color: Theme.color.onSurfaceVariantColor
                    fontSize: 12
                }
                Repeater {
                    model: ["默认", "标题", "艺术家", "时长"]
                    Rectangle {
                        property bool active: index === page.sortMode
                        implicitWidth: chipText.implicitWidth + 20
                        implicitHeight: 28
                        radius: 14
                        color: active ? Theme.color.primary : Theme.color.surfaceContainerHighest
                        Text {
                            id: chipText
                            anchors.centerIn: parent
                            text: modelData
                            fontSize: 12
                            color: active ? Theme.color.onPrimaryColor : Theme.color.onSurfaceVariantColor
                        }
                        MouseArea { anchors.fill: parent; onClicked: page.sortMode = index }
                    }
                }
                IconButton {
                    type: "standard"
                    enabled: page.sortMode !== 0
                    icon: page.sortDesc ? "arrow_downward" : "arrow_upward"
                    onClicked: page.sortDesc = !page.sortDesc
                }
                Item { Layout.fillWidth: true }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 6

                Text {
                    text: "分组"
                    color: Theme.color.onSurfaceVariantColor
                    fontSize: 12
                }
                Repeater {
                    model: ["全部", "按艺术家", "按文件夹"]
                    Rectangle {
                        property bool active: index === page.groupMode
                        implicitWidth: gChipText.implicitWidth + 20
                        implicitHeight: 28
                        radius: 14
                        color: active ? Theme.color.primary : Theme.color.surfaceContainerHighest
                        Text {
                            id: gChipText
                            anchors.centerIn: parent
                            text: modelData
                            fontSize: 12
                            color: active ? Theme.color.onPrimaryColor : Theme.color.onSurfaceVariantColor
                        }
                        MouseArea {
                            anchors.fill: parent
                            onClicked: { page.groupMode = index; page.groupValue = "" }
                        }
                    }
                }
                Rectangle {
                    visible: page.groupMode !== 0
                    Layout.fillWidth: true
                    implicitHeight: 28
                    radius: 14
                    color: Theme.color.surfaceContainerHighest
                    Text {
                        anchors.left: parent.left
                        anchors.leftMargin: 12
                        anchors.right: parent.right
                        anchors.rightMargin: 12
                        anchors.verticalCenter: parent.verticalCenter
                        elide: Text.ElideRight
                        text: page.groupValue !== "" ? page.groupValue : "全部"
                        fontSize: 12
                        color: Theme.color.onSurfaceColor
                    }
                    MouseArea { anchors.fill: parent; onClicked: page.valuePickerOpen = true }
                }
            }
        }

        // 专辑卡片网格
        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            contentWidth: width
            contentHeight: Math.ceil(albums.length / cols) * (cardW * artRatioH / artRatioW + textAreaH + gap) + pad * 2

            Item {
                width: parent.width
                height: Math.ceil(albums.length / cols) * (page.cardW * page.artRatioH / page.artRatioW + page.textAreaH + page.gap) + page.pad * 2

                Repeater {
                    model: albums
                    Rectangle {
                        x: page.pad + (index % page.cols) * (page.cardW + page.gap)
                        y: page.pad + Math.floor(index / page.cols) * (page.cardW * page.artRatioH / page.artRatioW + page.textAreaH + page.gap)
                        width: page.cardW
                        height: page.cardW * page.artRatioH / page.artRatioW + page.textAreaH + page.gap
                        color: "transparent"

                        // 专辑卡片
                        Rectangle {
                            width: parent.width - 12
                            height: parent.height - 12
                            anchors.centerIn: parent
                            radius: page.cardRadius
                            // PixelPlayer: surfaceVariant @ 30% alpha 背景 + primaryContainer 渐变
                            color: Theme.color.surfaceContainerHighest

                            // 封面区域（3:2）
                            Rectangle {
                                width: parent.width
                                height: parent.width * page.artRatioH / page.artRatioW
                                radius: page.cardRadius
                                color: Theme.color.primaryContainer

                                Image {
                                    anchors.fill: parent
                                    source: modelData.cover || ""
                                    radius: page.cardRadius
                                    fillMode: Image.PreserveAspectCrop
                                    visible: source !== ""
                                    opacity: source !== "" ? 1 : 0
                                }

                                // 底部渐变（透明 → primaryContainer）：不依赖 qml4j 的 Gradient shader，
                                // 改用 ~12 层自下而上逐级增大的半透明 Rectangle 叠出渐变 ——
                                // 任何渲染后端/任何显卡都能保证呈现为透明→色块的可见渐变，绝不退化成纯色。
                                Repeater {
                                    model: (modelData.cover || "") !== "" ? 12 : 0
                                    Rectangle {
                                        width: parent.width
                                        height: parent.height / 12 + 1
                                        y: index * parent.height / 12
                                        color: Theme.color.primaryContainer
                                        // 顶部几乎透明，到底部完全不透明（index 0 → 11）
                                        opacity: (index + 1) / 12
                                        // 只让最上面一层圆上角、最下面一层圆下角，其余直角，
                                        // 使渐变裁剪跟封面圆角一致，不破坏卡片圆角。
                                        topLeftRadius: index === 0 ? page.cardRadius : 0
                                        topRightRadius: index === 0 ? page.cardRadius : 0
                                        bottomLeftRadius: index === 11 ? page.cardRadius : 0
                                        bottomRightRadius: index === 11 ? page.cardRadius : 0
                                    }
                                }

                                // 占位图标（无封面时）
                                Text {
                                    anchors.centerIn: parent
                                    text: "album"
                                    font.family: Theme.iconFont.name
                                    font.pixelSize: 32
                                    color: Theme.color.onPrimaryContainerColor
                                    visible: (modelData.cover || "") === ""
                                }
                            }

                            // 文字区域
                            Column {
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.bottom: parent.bottom
                                anchors.margins: 12
                                spacing: 2

                                Text {
                                    width: parent.width
                                    text: modelData.title
                                    elide: Text.ElideRight
                                    font.bold: true
                                    fontSize: 15
                                    color: Theme.color.onPrimaryContainerColor
                                }
                                Text {
                                    width: parent.width
                                    text: modelData.artist
                                    elide: Text.ElideRight
                                    fontSize: 12
                                    color: Theme.color.onPrimaryContainerColor
                                    opacity: 0.85
                                }
                                Text {
                                    width: parent.width
                                    text: modelData.trackCount + " 首歌曲"
                                    elide: Text.ElideRight
                                    fontSize: 12
                                    color: Theme.color.onPrimaryContainerColor
                                    opacity: 0.7
                                }
                            }
                        }

                        // 点击进入专辑详情
                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                for (var i = 0; i < albums.length; i++) {
                                    if (albums[i].title === modelData.title) {
                                        page.albumIndex = i;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ========== 专辑详情视图（曲目列表） ==========
    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        visible: player.libraryCount > 0 && albumIndex >= 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.albumIndex = -1
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: albumIndex >= 0 && albumIndex < albums.length ? albums[albumIndex].title : ""
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
                elide: Text.ElideRight
            }
        }

        VirtualSongList {
            id: albumDetail
            Layout.fillWidth: true
            Layout.fillHeight: true
            list: page.visible ? page.albumTracks : null
            isLocal: true
            highlightCurrent: true
            highlightByFilePath: true
            songMenu: true
            onActivated: {
                var t = albumDetail.list[albumDetail.activatedIndex];
                if (!t) return;
                var all = player.tracks;
                for (var i = 0; i < all.length; i++) {
                    if (all[i].filePath === t.filePath) { player.play(i); return; }
                }
            }
        }
    }

    // 空状态提示
    Text {
        anchors.centerIn: parent
        visible: player.libraryCount === 0
        text: typeof settings.musicFolder !== "undefined"
            ? "未找到本地音乐\n可在设置 → 本地中修改音乐目录"
            : "未找到本地音乐\n把歌曲放进 Music 文件夹"
        horizontalAlignment: Text.AlignHCenter
        color: Theme.color.onSurfaceVariantColor
        fontSize: 15
    }

    // 分组值选择器弹窗
    Rectangle {
        id: valueDialog
        anchors.fill: parent
        opacity: page.valuePickerOpen ? 1 : 0
        visible: opacity > 0.01
        color: "#99000000"
        Behavior on opacity { NumberAnimation { duration: 150 } }

        MouseArea { anchors.fill: parent; onClicked: page.valuePickerOpen = false }

        property var filtered: {
            var q = valueSearchField.text.toLowerCase();
            var out = [];
            for (var i = 0; i < page.groupValues.length; i++) {
                var name = page.groupValues[i];
                if (q === "" || name.toLowerCase().indexOf(q) >= 0) out.push(name);
            }
            return out;
        }

        Rectangle {
            anchors.centerIn: parent
            width: Math.min(340, parent.width - 32)
            height: Math.min(420, parent.height - 32)
            radius: 24
            color: Theme.color.surfaceContainerHigh
            scale: page.valuePickerOpen ? 1 : 0.9
            Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }

            MouseArea { anchors.fill: parent }

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 16
                spacing: 12

                Text {
                    Layout.fillWidth: true
                    text: page.groupMode === 1 ? "选择艺术家" : "选择文件夹"
                    color: Theme.color.onSurfaceColor
                    fontSize: 18
                }

                TextField {
                    id: valueSearchField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "搜索"
                }

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: 40
                    radius: 8
                    color: allValuesMa.pressed ? Theme.color.surfaceContainerHighest : "transparent"
                    Text {
                        anchors.left: parent.left
                        anchors.leftMargin: 12
                        anchors.verticalCenter: parent.verticalCenter
                        text: "全部"
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }
                    MouseArea {
                        id: allValuesMa
                        anchors.fill: parent
                        onClicked: { page.groupValue = ""; page.valuePickerOpen = false }
                    }
                }

                Flickable {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    contentWidth: width
                    contentHeight: valueDialog.filtered.length * 40

                    Item {
                        width: parent.width
                        height: valueDialog.filtered.length * 40

                        Repeater {
                            model: valueDialog.filtered
                            Rectangle {
                                width: parent.width
                                height: 40
                                y: index * 40
                                radius: 8
                                color: valueRowMa.pressed ? Theme.color.surfaceContainerHighest : "transparent"
                                Text {
                                    anchors.left: parent.left
                                    anchors.leftMargin: 12
                                    anchors.right: parent.right
                                    anchors.rightMargin: 12
                                    anchors.verticalCenter: parent.verticalCenter
                                    elide: Text.ElideRight
                                    text: modelData || ""
                                    color: Theme.color.onSurfaceColor
                                    fontSize: 14
                                }
                                MouseArea {
                                    id: valueRowMa
                                    anchors.fill: parent
                                    onClicked: { page.groupValue = modelData; page.valuePickerOpen = false }
                                }
                            }
                        }
                    }
                }

                Button {
                    Layout.alignment: Qt.AlignHCenter
                    type: "text"; text: "取消"
                    onClicked: page.valuePickerOpen = false
                }
            }
        }
    }
}