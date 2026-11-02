package com.ylmac.adapt.module.misc.music.netease.dto;

import java.util.ArrayList;
import java.util.List;

/** 一起听房间 */
public class TogetherRoom {
    public String roomId = "";
    public long creatorId;
    public long effectiveDurationMs;
    public String status = "";
    public List<TogetherUser> users = new ArrayList<>();
}
