package com.ylmac.adapt.features.command;

/**
 * 好友名单（替代 myau 的 FriendManager，26.2 简化版）。
 */
public final class FriendManager extends PlayerListManager {

    private static final FriendManager INSTANCE = new FriendManager();

    private FriendManager() {
        super("friends.txt");
    }

    public static FriendManager get() {
        return INSTANCE;
    }

    public boolean isFriend(String name) {
        return contains(name);
    }
}
