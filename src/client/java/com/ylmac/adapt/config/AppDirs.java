package com.ylmac.adapt.config;

import java.io.File;

public final class AppDirs {

    private static volatile String base =
            new File(System.getProperty("user.home", "."), ".qplayer-mod").getAbsolutePath();

    private static volatile String cacheBase = base;

    private AppDirs() {
    }

    public static void setBase(String dir) {
        base = dir;
        cacheBase = dir;
    }

    public static String base() {
        return base;
    }

    public static void setCacheBase(String dir) {
        cacheBase = (dir == null || dir.trim().isEmpty()) ? base : dir;
    }

    public static String cacheBase() {
        return cacheBase;
    }
}
