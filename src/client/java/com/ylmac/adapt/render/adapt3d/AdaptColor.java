package com.ylmac.adapt.render.adapt3d;

/**
 * 提交时按顶点改颜色。网格里的颜色是底色，光环条纹这类每帧变化的效果不写进网格。
 * {@code u}/{@code v} 是该顶点的纹理坐标。
 */
@FunctionalInterface
public interface AdaptColor {
    int argb(float u, float v, int meshColor);

    static AdaptColor solid(int argb) {
        return (u, v, mesh) -> argb;
    }

    /** 沿 U（0–1 对应一整圈）的高斯亮带。 */
    static AdaptColor angularBand(int argb, double centerRadians, double sigmaRadians) {
        double sigma = Math.max(1.0e-4, sigmaRadians);
        return (u, v, mesh) -> {
            double theta = u * (Math.PI * 2.0);
            double distance = Math.abs(shortest(theta - centerRadians));
            double glow = Math.exp(-(distance * distance) / (2.0 * sigma * sigma));
            float alpha = (float) Math.max(0.0, Math.min(1.0, glow));
            int baseA = (argb >>> 24) & 0xFF;
            int a = Math.max(0, Math.min(255, Math.round(baseA * alpha)));
            return (argb & 0x00FFFFFF) | (a << 24);
        };
    }

    private static double shortest(double angle) {
        double twoPi = Math.PI * 2.0;
        angle = (angle + Math.PI) % twoPi;
        if (angle < 0.0) angle += twoPi;
        return angle - Math.PI;
    }
}
