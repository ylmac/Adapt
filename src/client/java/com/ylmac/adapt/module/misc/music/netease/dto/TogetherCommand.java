package com.ylmac.adapt.module.misc.music.netease.dto;

/** 一起听播放命令 */
public class TogetherCommand {
    public long userId;
    public String commandType = "";
    public long formerSongId;
    public long targetSongId;
    public long progressMs;
    public String playStatus = "";
    public long serverSeq;
    public long clientSeq;
}
