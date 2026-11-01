package com.ylmac.adapt.render;

import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;

import java.util.HashMap;
import java.util.Map;

/**
 * 投影和光晕用的高斯滤镜。半径是固定的几档，留着复用，调用方不要 close。
 */
final class CachedBlur {

    private static final Map<Long, ImageFilter> CACHE = new HashMap<>();

    private CachedBlur() {}

    static ImageFilter of(float sigma, FilterTileMode mode) {
        long key = ((long) Float.floatToIntBits(sigma) << 4) ^ mode.ordinal();
        ImageFilter filter = CACHE.get(key);
        if (filter == null) {
            filter = ImageFilter.makeBlur(sigma, sigma, mode);
            CACHE.put(key, filter);
        }
        return filter;
    }
}
