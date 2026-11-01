/*
 * Decompiled with CFR 0.152.
 */
package com.ylmac.adapt.theme.color;

final class MathUtils {
    private MathUtils() {
    }

    static double lerp(double start, double stop, double amount) {
        return (1.0 - amount) * start + amount * stop;
    }

    static double clampDouble(double min, double max, double input) {
        if (input < min) {
            return min;
        }
        if (input > max) {
            return max;
        }
        return input;
    }

    static int clampInt(int min, int max, int input) {
        if (input < min) {
            return min;
        }
        if (input > max) {
            return max;
        }
        return input;
    }

    static double sanitizeDegreesDouble(double degrees) {
        if ((degrees %= 360.0) < 0.0) {
            degrees += 360.0;
        }
        return degrees;
    }

    static int sanitizeDegreesInt(int degrees) {
        if ((degrees %= 360) < 0) {
            degrees += 360;
        }
        return degrees;
    }

    static double rotationDirection(double from, double to) {
        double increasingDifference = MathUtils.sanitizeDegreesDouble(to - from);
        return increasingDifference <= 180.0 ? 1.0 : -1.0;
    }

    static double differenceDegrees(double a, double b) {
        return 180.0 - Math.abs(Math.abs(a - b) - 180.0);
    }

    static double[] matrixMultiply(double[] row, double[][] matrix) {
        double a = row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2];
        double b = row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2];
        double c = row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2];
        return new double[]{a, b, c};
    }
}
