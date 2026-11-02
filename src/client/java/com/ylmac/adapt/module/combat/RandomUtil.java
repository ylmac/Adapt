package com.ylmac.adapt.module.combat;

import java.util.Random;

/**
 * 随机数工具。
 */
public final class RandomUtil {
    private static final Random RANDOM = new Random();

    private RandomUtil() {}

    public static int nextInt(int min, int max) {
        if (min >= max) return min;
        return RANDOM.nextInt(max - min + 1) + min;
    }

    public static long nextLong(long min, long max) {
        return (long) nextDouble((double) min, (double) (max + 1L));
    }

    public static float nextFloat(float min, float max) {
        return RANDOM.nextFloat() * (max - min) + min;
    }

    public static double nextDouble(double min, double max) {
        return RANDOM.nextDouble() * (max - min) + min;
    }
}
