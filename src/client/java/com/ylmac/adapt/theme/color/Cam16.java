/*
 * Decompiled with CFR 0.152.
 */
package com.ylmac.adapt.theme.color;

import com.ylmac.adapt.theme.color.ColorUtils;
import com.ylmac.adapt.theme.color.ViewingConditions;

final class Cam16 {
    static final double[][] XYZ_TO_CAM16RGB = new double[][]{{0.401288, 0.650173, -0.051461}, {-0.250268, 1.204414, 0.045854}, {-0.002079, 0.048952, 0.953127}};
    static final double[][] CAM16RGB_TO_XYZ = new double[][]{{1.8620678, -1.0112547, 0.14918678}, {0.38752654, 0.62144744, -0.00897398}, {-0.0158415, -0.03412294, 1.0499644}};
    private final double hue;
    private final double chroma;
    private final double j;
    private final double jstar;
    private final double astar;
    private final double bstar;

    private Cam16(double hue, double chroma, double j, double jstar, double astar, double bstar) {
        this.hue = hue;
        this.chroma = chroma;
        this.j = j;
        this.jstar = jstar;
        this.astar = astar;
        this.bstar = bstar;
    }

    double getHue() {
        return this.hue;
    }

    double getChroma() {
        return this.chroma;
    }

    double getJ() {
        return this.j;
    }

    double distance(Cam16 other) {
        double dJ = this.jstar - other.jstar;
        double dA = this.astar - other.astar;
        double dB = this.bstar - other.bstar;
        double dEPrime = Math.sqrt(dJ * dJ + dA * dA + dB * dB);
        return 1.41 * Math.pow(dEPrime, 0.63);
    }

    static Cam16 fromInt(int argb) {
        return Cam16.fromIntInViewingConditions(argb, ViewingConditions.DEFAULT);
    }

    static Cam16 fromIntInViewingConditions(int argb, ViewingConditions vc) {
        double[] xyz = ColorUtils.xyzFromArgb(argb);
        return Cam16.fromXyzInViewingConditions(xyz[0], xyz[1], xyz[2], vc);
    }

    private static Cam16 fromXyzInViewingConditions(double x, double y, double z, ViewingConditions vc) {
        double[][] matrix = XYZ_TO_CAM16RGB;
        double rT = x * matrix[0][0] + y * matrix[0][1] + z * matrix[0][2];
        double gT = x * matrix[1][0] + y * matrix[1][1] + z * matrix[1][2];
        double bT = x * matrix[2][0] + y * matrix[2][1] + z * matrix[2][2];
        double[] rgbD = vc.getRgbD();
        double rD = rgbD[0] * rT;
        double gD = rgbD[1] * gT;
        double bD = rgbD[2] * bT;
        double rAF = Math.pow(vc.getFl() * Math.abs(rD) / 100.0, 0.42);
        double gAF = Math.pow(vc.getFl() * Math.abs(gD) / 100.0, 0.42);
        double bAF = Math.pow(vc.getFl() * Math.abs(bD) / 100.0, 0.42);
        double rA = Math.signum(rD) * 400.0 * rAF / (rAF + 27.13);
        double gA = Math.signum(gD) * 400.0 * gAF / (gAF + 27.13);
        double bA = Math.signum(bD) * 400.0 * bAF / (bAF + 27.13);
        double a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
        double b = (rA + gA - 2.0 * bA) / 9.0;
        double u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0;
        double p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0;
        double atan2 = Math.atan2(b, a);
        double atanDegrees = atan2 * 180.0 / Math.PI;
        double hue = atanDegrees < 0.0 ? atanDegrees + 360.0 : (atanDegrees >= 360.0 ? atanDegrees - 360.0 : atanDegrees);
        double hueRadians = hue * Math.PI / 180.0;
        double ac = p2 * vc.getNbb();
        double j = 100.0 * Math.pow(ac / vc.getAw(), vc.getC() * vc.getZ());
        double huePrime = hue < 20.14 ? hue + 360.0 : hue;
        double eHue = 0.25 * (Math.cos(huePrime * Math.PI / 180.0 + 2.0) + 3.8);
        double p1 = 3846.153846153846 * eHue * vc.getNc() * vc.getNcb();
        double t = p1 * Math.hypot(a, b) / (u + 0.305);
        double alpha = Math.pow(t, 0.9) * Math.pow(1.64 - Math.pow(0.29, vc.getN()), 0.73);
        double c = alpha * Math.sqrt(j / 100.0);
        double m = c * vc.getFlRoot();
        double jstar = 1.7000000000000002 * j / (1.0 + 0.007 * j);
        double mstar = 43.859649122807014 * Math.log1p(0.0228 * m);
        double astar = mstar * Math.cos(hueRadians);
        double bstar = mstar * Math.sin(hueRadians);
        return new Cam16(hue, c, j, jstar, astar, bstar);
    }

    static Cam16 fromJch(double j, double c, double h) {
        return Cam16.fromJchInViewingConditions(j, c, h, ViewingConditions.DEFAULT);
    }

    private static Cam16 fromJchInViewingConditions(double j, double c, double h, ViewingConditions vc) {
        double m = c * vc.getFlRoot();
        double hueRadians = h * Math.PI / 180.0;
        double jstar = 1.7000000000000002 * j / (1.0 + 0.007 * j);
        double mstar = 43.859649122807014 * Math.log1p(0.0228 * m);
        double astar = mstar * Math.cos(hueRadians);
        double bstar = mstar * Math.sin(hueRadians);
        return new Cam16(h, c, j, jstar, astar, bstar);
    }

    int toInt() {
        return this.viewed(ViewingConditions.DEFAULT);
    }

    int viewed(ViewingConditions vc) {
        double alpha = this.chroma == 0.0 || this.j == 0.0 ? 0.0 : this.chroma / Math.sqrt(this.j / 100.0);
        double t = Math.pow(alpha / Math.pow(1.64 - Math.pow(0.29, vc.getN()), 0.73), 1.1111111111111112);
        double hRad = this.hue * Math.PI / 180.0;
        double eHue = 0.25 * (Math.cos(hRad + 2.0) + 3.8);
        double ac = vc.getAw() * Math.pow(this.j / 100.0, 1.0 / vc.getC() / vc.getZ());
        double p1 = eHue * 3846.153846153846 * vc.getNc() * vc.getNcb();
        double p2 = ac / vc.getNbb();
        double hSin = Math.sin(hRad);
        double hCos = Math.cos(hRad);
        double gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin);
        double a = gamma * hCos;
        double b = gamma * hSin;
        double rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
        double gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
        double bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;
        double rCBase = Math.max(0.0, 27.13 * Math.abs(rA) / (400.0 - Math.abs(rA)));
        double rC = Math.signum(rA) * (100.0 / vc.getFl()) * Math.pow(rCBase, 2.380952380952381);
        double gCBase = Math.max(0.0, 27.13 * Math.abs(gA) / (400.0 - Math.abs(gA)));
        double gC = Math.signum(gA) * (100.0 / vc.getFl()) * Math.pow(gCBase, 2.380952380952381);
        double bCBase = Math.max(0.0, 27.13 * Math.abs(bA) / (400.0 - Math.abs(bA)));
        double bC = Math.signum(bA) * (100.0 / vc.getFl()) * Math.pow(bCBase, 2.380952380952381);
        double[] rgbD = vc.getRgbD();
        double rF = rC / rgbD[0];
        double gF = gC / rgbD[1];
        double bF = bC / rgbD[2];
        double[][] matrix = CAM16RGB_TO_XYZ;
        double x = rF * matrix[0][0] + gF * matrix[0][1] + bF * matrix[0][2];
        double y = rF * matrix[1][0] + gF * matrix[1][1] + bF * matrix[1][2];
        double z = rF * matrix[2][0] + gF * matrix[2][1] + bF * matrix[2][2];
        return ColorUtils.argbFromXyz(x, y, z);
    }
}
