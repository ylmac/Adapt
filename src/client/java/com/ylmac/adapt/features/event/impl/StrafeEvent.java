package com.ylmac.adapt.features.event.impl;

import com.ylmac.adapt.features.event.bus.Cancellable;

/**
 * 移动转向事件（Rise StrafeEvent 等价物）。
 * <p>
 * 由 {@code KeyboardInputMixin} 在 {@code KeyboardInput.tick} 尾部（输入刷新后、
 * LocalPlayer.aiStep → applyInput/travel 前）发布，携带当前移动输入
 * （forward/strafe，与 KeyboardInputEvent 同源），并允许修改视角偏航 yaw。
 * 模块（Strafe/TargetStrafe 等）监听本事件按需改写移动方向或速度向量。
 */
public class StrafeEvent extends Cancellable {

    private float forward;
    private float strafe;
    private float yaw;

    public StrafeEvent(float forward, float strafe, float yaw) {
        this.forward = forward;
        this.strafe = strafe;
        this.yaw = yaw;
    }

    public float getForward() {
        return this.forward;
    }

    public float getStrafe() {
        return this.strafe;
    }

    public float getYaw() {
        return this.yaw;
    }

    public void setForward(float forward) {
        this.forward = forward;
    }

    public void setStrafe(float strafe) {
        this.strafe = strafe;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

}
