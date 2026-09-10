package com.ylmac.adapt.features.music.customapi;
import com.ylmac.adapt.features.music.netease.dto.NeteaseSong;

/**
 * Song descriptor decoded from a user-configured custom API's search response.
 * Field names mirror {@code com.ylmac.adapt.features.music.netease.dto.NeteaseSong}'s
 * display fields so {@code VirtualSongList.qml} (which reads
 * {@code modelData.name/artist/coverThumbPath}) renders both lists unchanged.
 */
public final class CustomSong {
    /** External id from the custom API; string (not long) since third-party ids
     *  aren't guaranteed numeric. */
    public String id;
    public String name;
    public String artist;
    public String album;
    public String coverUrl;
    public String coverThumbPath;
    public long durationMs;
}
