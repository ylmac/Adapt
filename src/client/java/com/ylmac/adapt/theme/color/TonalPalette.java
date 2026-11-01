/*
 * Decompiled with CFR 0.152.
 */
package com.ylmac.adapt.theme.color;

import com.ylmac.adapt.theme.color.Hct;

final class TonalPalette {
    private final double hue;
    private final double chroma;

    private TonalPalette(double hue, double chroma) {
        this.hue = hue;
        this.chroma = chroma;
    }

    static TonalPalette fromHueAndChroma(double hue, double chroma) {
        return new TonalPalette(hue, chroma);
    }

    int tone(int tone) {
        return Hct.from(this.hue, this.chroma, tone).toInt();
    }
}
