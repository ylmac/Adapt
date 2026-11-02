package com.ylmac.adapt.command;

/**
 * 好友名单。
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
