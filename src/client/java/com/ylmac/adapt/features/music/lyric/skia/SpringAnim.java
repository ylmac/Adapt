package com.ylmac.adapt.features.music.lyric.skia;

public class SpringAnim {

    private double stiffness;
    private double damping;
    private double value;
    private double velocity;
    private double target;
    private long lastNs = System.nanoTime();

    public SpringAnim(double stiffness, double damping) {
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public void setParams(double stiffness, double damping) {
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public SpringAnim() {
        this(180.0, 22.0);
    }

    public double animate(double newTarget) {
        if (!Double.isFinite(newTarget)) {
            return Double.isFinite(value) ? value : 0.0;
        }
        this.target = newTarget;

        long now = System.nanoTime();
        double dt = (now - lastNs) / 1_000_000_000.0;
        lastNs = now;
        if (dt > 0.05) dt = 0.05;
        if (dt <= 0.0) return value;

        int steps = 1 + (int) (dt / 0.008);
        double sub = dt / steps;
        for (int i = 0; i < steps; i++) {
            double a = -stiffness * (value - target) - damping * velocity;
            velocity += a * sub;
            value += velocity * sub;
        }

        if (Math.abs(velocity) < 0.01 && Math.abs(value - target) < 0.05) {
            value = target;
            velocity = 0.0;
        }
        return value;
    }

    public void setValue(double v) {
        this.value = v;
        this.velocity = 0.0;
        this.target = v;
        this.lastNs = System.nanoTime();
    }

    public double getValue() {
        return value;
    }

    public void reset() {
        setValue(target);
    }
}
