package com.ylmac.adapt.features.music.netease.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一起听快照（队列 + 播放命令） */
public class TogetherSnapshot {
    public List<Long> songIds = new ArrayList<>();
    public List<Map<String, Object>> versions = new ArrayList<>();
    public TogetherCommand command = null;
}
