package com.ylmac.adapt.module.misc.music.lyric.skia;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;

public final class LyricSkia {

    private LyricSkia() {
    }

    private static Canvas current;
    private static final Paint[] POOL = new Paint[6];
    private static int next;

    public static void setCanvas(Canvas c) {
        current = c;
    }

    public static Canvas getCanvas() {
        return current;
    }

    public static Paint scratchPaint() {
        int i = next;
        next = (next + 1) % POOL.length;
        Paint p = POOL[i];
        if (p == null) {
            p = new Paint();
            POOL[i] = p;
        }
        p.reset();
        p.setAntiAlias(true);
        return p;
    }
}
