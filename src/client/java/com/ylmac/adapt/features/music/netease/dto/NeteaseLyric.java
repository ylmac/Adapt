package com.ylmac.adapt.features.music.netease.dto;

public class NeteaseLyric {
    public String lrc;
    public String yrc;
    public String tlyric;
    public String romalrc;

    public boolean isEmpty() {
        return (lrc == null || lrc.isEmpty())
            && (yrc == null || yrc.isEmpty());
    }
}
