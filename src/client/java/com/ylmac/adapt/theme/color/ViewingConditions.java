/*
 * Decompiled with CFR 0.152.
 */
package com.ylmac.adapt.theme.color;

import com.ylmac.adapt.theme.color.Cam16;
import com.ylmac.adapt.theme.color.ColorUtils;
import com.ylmac.adapt.theme.color.MathUtils;

final class ViewingConditions {
    static final ViewingConditions DEFAULT = ViewingConditions.defaultWithBackgroundLstar(50.0);
    private final double aw;
    private final double nbb;
    private final double ncb;
    private final double c;
    private final double nc;
    private final double n;
    private final double[] rgbD;
    private final double fl;
    private final double flRoot;
    private final double z;

    private ViewingConditions(double n, double aw, double nbb, double ncb, double c, double nc, double[] rgbD, double fl, double flRoot, double z) {
        this.n = n;
        this.aw = aw;
        this.nbb = nbb;
        this.ncb = ncb;
        this.c = c;
        this.nc = nc;
        this.rgbD = rgbD;
        this.fl = fl;
        this.flRoot = flRoot;
        this.z = z;
    }

    double getAw() {
        return this.aw;
    }

    double getN() {
        return this.n;
    }

    double getNbb() {
        return this.nbb;
    }

    double getNcb() {
        return this.ncb;
    }

    double getC() {
        return this.c;
    }

    double getNc() {
        return this.nc;
    }

    double[] getRgbD() {
        return this.rgbD;
    }

    double getFl() {
        return this.fl;
    }

    double getFlRoot() {
        return this.flRoot;
    }

    double getZ() {
        return this.z;
    }

    static ViewingConditions make(double[] whitePoint, double adaptingLuminance, double backgroundLstar, double surround, boolean discountingIlluminant) {
        double nbb;
        backgroundLstar = Math.max(0.1, backgroundLstar);
        double[][] matrix = Cam16.XYZ_TO_CAM16RGB;
        double[] xyz = whitePoint;
        double rW = xyz[0] * matrix[0][0] + xyz[1] * matrix[0][1] + xyz[2] * matrix[0][2];
        double gW = xyz[0] * matrix[1][0] + xyz[1] * matrix[1][1] + xyz[2] * matrix[1][2];
        double bW = xyz[0] * matrix[2][0] + xyz[1] * matrix[2][1] + xyz[2] * matrix[2][2];
        double f = 0.8 + surround / 10.0;
        double c = f >= 0.9 ? MathUtils.lerp(0.59, 0.69, (f - 0.9) * 10.0) : MathUtils.lerp(0.525, 0.59, (f - 0.8) * 10.0);
        double d = discountingIlluminant ? 1.0 : f * (1.0 - 0.2777777777777778 * Math.exp((-adaptingLuminance - 42.0) / 92.0));
        d = MathUtils.clampDouble(0.0, 1.0, d);
        double[] rgbD = new double[]{d * (100.0 / rW) + 1.0 - d, d * (100.0 / gW) + 1.0 - d, d * (100.0 / bW) + 1.0 - d};
        double k = 1.0 / (5.0 * adaptingLuminance + 1.0);
        double k4 = k * k * k * k;
        double k4F = 1.0 - k4;
        double fl = k4 * adaptingLuminance + 0.1 * k4F * k4F * Math.cbrt(5.0 * adaptingLuminance);
        double n = ColorUtils.yFromLstar(backgroundLstar) / whitePoint[1];
        double z = 1.48 + Math.sqrt(n);
        double ncb = nbb = 0.725 / Math.pow(n, 0.2);
        double[] rgbAFactors = new double[]{Math.pow(fl * rgbD[0] * rW / 100.0, 0.42), Math.pow(fl * rgbD[1] * gW / 100.0, 0.42), Math.pow(fl * rgbD[2] * bW / 100.0, 0.42)};
        double[] rgbA = new double[]{400.0 * rgbAFactors[0] / (rgbAFactors[0] + 27.13), 400.0 * rgbAFactors[1] / (rgbAFactors[1] + 27.13), 400.0 * rgbAFactors[2] / (rgbAFactors[2] + 27.13)};
        double aw = (2.0 * rgbA[0] + rgbA[1] + 0.05 * rgbA[2]) * nbb;
        return new ViewingConditions(n, aw, nbb, ncb, c, f, rgbD, fl, Math.pow(fl, 0.25), z);
    }

    static ViewingConditions defaultWithBackgroundLstar(double lstar) {
        return ViewingConditions.make(ColorUtils.whitePointD65(), 63.66197723675813 * ColorUtils.yFromLstar(50.0) / 100.0, lstar, 2.0, false);
    }
}
