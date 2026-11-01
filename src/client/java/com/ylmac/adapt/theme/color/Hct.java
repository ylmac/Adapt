/*
 * Decompiled with CFR 0.152.
 */
package com.ylmac.adapt.theme.color;

import com.ylmac.adapt.theme.color.Cam16;
import com.ylmac.adapt.theme.color.ColorUtils;
import com.ylmac.adapt.theme.color.MathUtils;

public final class Hct {
    private static final double DL_MAX = 0.2;
    private static final double DE_MAX = 1.0;
    private static final double LIGHTNESS_SEARCH_ENDPOINT = 0.01;
    private double hue;
    private double chroma;
    private double tone;
    private int argb;

    private Hct(int argb) {
        this.setInternalState(argb);
    }

    public static Hct fromInt(int argb) {
        return new Hct(argb);
    }

    public static Hct from(double hue, double chroma, double tone) {
        return new Hct(Hct.toArgb(hue, chroma, tone));
    }

    public double getHue() {
        return this.hue;
    }

    public double getChroma() {
        return this.chroma;
    }

    public double getTone() {
        return this.tone;
    }

    public int toInt() {
        return this.argb;
    }

    private void setInternalState(int argb) {
        this.argb = argb;
        Cam16 cam = Cam16.fromInt(argb);
        this.hue = cam.getHue();
        this.chroma = cam.getChroma();
        this.tone = ColorUtils.lstarFromArgb(argb);
    }

    static int toArgb(double hue, double chroma, double tone) {
        if (chroma < 1.0 || (double)Math.round(tone) <= 0.0 || (double)Math.round(tone) >= 100.0) {
            return ColorUtils.argbFromLstar(tone);
        }
        hue = MathUtils.sanitizeDegreesDouble(hue);
        double low = 0.0;
        double high = chroma;
        int answer = ColorUtils.argbFromLstar(tone);
        while (high - low >= 0.4) {
            double mid = low + (high - low) / 2.0;
            Cam16 cam = Hct.findCamByJ(hue, mid, tone);
            if (cam != null && Cam16.fromInt(cam.toInt()).getChroma() >= mid - 1.0) {
                answer = cam.toInt();
                low = mid;
                continue;
            }
            high = mid;
        }
        return answer;
    }

    private static Cam16 findCamByJ(double hue, double chroma, double tone) {
        double low = 0.0;
        double high = 100.0;
        double bestdL = 1000.0;
        double bestdE = 1000.0;
        Cam16 bestCam = null;
        while (Math.abs(low - high) > 0.01) {
            Cam16 camClipped;
            double dE;
            double mid = low + (high - low) / 2.0;
            Cam16 camBeforeClip = Cam16.fromJch(mid, chroma, hue);
            int clipped = camBeforeClip.toInt();
            double clippedLstar = ColorUtils.lstarFromArgb(clipped);
            double dL = Math.abs(tone - clippedLstar);
            if (dL < 0.2 && (dE = (camClipped = Cam16.fromInt(clipped)).distance(Cam16.fromJch(camClipped.getJ(), camClipped.getChroma(), hue))) <= 1.0 && dE <= bestdE) {
                bestdL = dL;
                bestdE = dE;
                bestCam = camClipped;
            }
            if (bestdL == 0.0 && bestdE == 0.0) break;
            if (clippedLstar < tone) {
                low = mid;
                continue;
            }
            high = mid;
        }
        return bestCam;
    }
}
