package com.ylmac.adapt.command;

/**
 * 敌人/目标名单。
 */
public final class TargetManager extends PlayerListManager {

    private static final TargetManager INSTANCE = new TargetManager();

    private TargetManager() {
        super("targets.txt");
    }

    public static TargetManager get() {
        return INSTANCE;
    }
}
