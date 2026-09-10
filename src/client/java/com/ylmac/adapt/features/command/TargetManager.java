package com.ylmac.adapt.features.command;

/**
 * 敌人/目标名单（替代 myau 的 TargetManager，26.2 简化版）。
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
