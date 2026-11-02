package com.ylmac.adapt.module.misc.music.bridge;

/**
 * Unified search result row combining multiple sources (netease, local, custom).
 * Used by the search page via player.searchRows.
 */
public final class SearchRow {
    public String kind;       // "netease" | "local" | "custom"
    public int index;
    public String name;
    public String artist;
    public String album;
    public String coverThumbPath;
    public String coverUrl;
    public long id;
    public String filePath;
    public String customId;
    public long durationMs;
    public boolean menuEnabled;
    public String kindLabel;  // "网易云" / "本地" / "自定义源"
}