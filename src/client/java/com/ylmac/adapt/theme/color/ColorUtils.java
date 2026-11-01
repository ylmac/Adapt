/*
 * Decompiled with CFR 0.152.
 */
package com.ylmac.adapt.theme.color;

import com.ylmac.adapt.theme.color.MathUtils;

final class ColorUtils {
    private static final double[][] SRGB_TO_XYZ = new double[][]{{0.41233895, 0.35762064, 0.18051042}, {0.2126, 0.7152, 0.0722}, {0.01932141, 0.11916382, 0.95034478}};
    private static final double[][] XYZ_TO_SRGB = new double[][]{{3.2413774792388685, -1.5376652402851851, -0.49885366846268053}, {-0.9691452513005321, 1.8758853451067872, 0.04156585616912061}, {0.05562093689691305, -0.20395524564742123, 1.0571799111220335}};
    private static final double[] WHITE_POINT_D65 = new double[]{95.047, 100.0, 108.883};

    private ColorUtils() {
    }

    static int argbFromRgb(int red, int green, int blue) {
        return 0xFF000000 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | blue & 0xFF;
    }

    static int argbFromLinrgb(double[] linrgb) {
        int r = ColorUtils.delinearized(linrgb[0]);
        int g = ColorUtils.delinearized(linrgb[1]);
        int b = ColorUtils.delinearized(linrgb[2]);
        return ColorUtils.argbFromRgb(r, g, b);
    }

    static int redFromArgb(int argb) {
        return argb >> 16 & 0xFF;
    }

    static int greenFromArgb(int argb) {
        return argb >> 8 & 0xFF;
    }

    static int blueFromArgb(int argb) {
        return argb & 0xFF;
    }

    static int argbFromXyz(double x, double y, double z) {
        double[][] matrix = XYZ_TO_SRGB;
        double linearR = matrix[0][0] * x + matrix[0][1] * y + matrix[0][2] * z;
        double linearG = matrix[1][0] * x + matrix[1][1] * y + matrix[1][2] * z;
        double linearB = matrix[2][0] * x + matrix[2][1] * y + matrix[2][2] * z;
        int r = ColorUtils.delinearized(linearR);
        int g = ColorUtils.delinearized(linearG);
        int b = ColorUtils.delinearized(linearB);
        return ColorUtils.argbFromRgb(r, g, b);
    }

    static double[] xyzFromArgb(int argb) {
        double r = ColorUtils.linearized(ColorUtils.redFromArgb(argb));
        double g = ColorUtils.linearized(ColorUtils.greenFromArgb(argb));
        double b = ColorUtils.linearized(ColorUtils.blueFromArgb(argb));
        return MathUtils.matrixMultiply(new double[]{r, g, b}, SRGB_TO_XYZ);
    }

    static double yFromLstar(double lstar) {
        return 100.0 * ColorUtils.labInvf((lstar + 16.0) / 116.0);
    }

    static double lstarFromY(double y) {
        return ColorUtils.labF(y / 100.0) * 116.0 - 16.0;
    }

    static double linearized(int rgbComponent) {
        double normalized = (double)rgbComponent / 255.0;
        if (normalized <= 0.040449936) {
            return normalized / 12.92 * 100.0;
        }
        return Math.pow((normalized + 0.055) / 1.055, 2.4) * 100.0;
    }

    static int delinearized(double rgbComponent) {
        double normalized = rgbComponent / 100.0;
        double delinearized = normalized <= 0.0031308 ? normalized * 12.92 : 1.055 * Math.pow(normalized, 0.4166666666666667) - 0.055;
        return MathUtils.clampInt(0, 255, (int)Math.round(delinearized * 255.0));
    }

    static double[] whitePointD65() {
        return WHITE_POINT_D65;
    }

    static double lstarFromArgb(int argb) {
        double y = ColorUtils.xyzFromArgb(argb)[1];
        return ColorUtils.lstarFromY(y);
    }

    static int argbFromLstar(double lstar) {
        double y = ColorUtils.yFromLstar(lstar);
        int component = ColorUtils.delinearized(y);
        return ColorUtils.argbFromRgb(component, component, component);
    }

    private static double labF(double t) {
        double e = 0.008856451679035631;
        double kappa = 903.2962962962963;
        if (t > e) {
            return Math.cbrt(t);
        }
        return (kappa * t + 16.0) / 116.0;
    }

    private static double labInvf(double ft) {
        double e = 0.008856451679035631;
        double kappa = 903.2962962962963;
        double ft3 = ft * ft * ft;
        if (ft3 > e) {
            return ft3;
        }
        return (116.0 * ft - 16.0) / kappa;
    }
}
