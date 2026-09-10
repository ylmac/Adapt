package com.ylmac.adapt.features.music.model;

public class Track {

    public enum Source { LOCAL, NETEASE, CUSTOM_API }

    public Source source = Source.LOCAL;

    public String filePath;
    public String contentUri;
    public String streamUrl;
    public boolean trial;
    public long neteaseId;
    /** 自定义 API 音源的外部歌曲 id（字符串，第三方 id 不保证是数字） */
    public String customId;

    public String title;
    public String artist;
    public String album;
    public long durationMs;

    public byte[] coverBytes;
    public String coverUrl;
    public String coverThumbPath;
    public String coverLocalPath;

    public String lyricFilePath;
    public String translationFilePath;
    public String romajiFilePath;

    /** 原始歌词文本（本地：嵌入式或同目录 .lrc/.txt sidecar；网易云：服务器返回的 lrc 文本） */
    public String lyricText;

    public long fileSize;
    public long fileMtime;

    public String playable() {
        if (source == Source.NETEASE || source == Source.CUSTOM_API) return streamUrl;
        return contentUri != null ? contentUri : filePath;
    }

    @Override
    public String toString() {
        return (artist != null ? artist : "?") + " - " + (title != null ? title : "?");
    }
}
