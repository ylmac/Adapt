package com.ylmac.adapt.event.impl;

import com.ylmac.adapt.event.bus.Cancellable;

/** 玩家 tick 事件（Pre=可取消，Post=仅通知） */
public class PlayerTickEvent {

    public static class Pre extends Cancellable {
    }

    public static class Post {
    }

}
