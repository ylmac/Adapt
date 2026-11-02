package com.ylmac.adapt.event.impl;

/** 跳跃事件（可修改偏航角） */
public class JumpEvent {

    private float yaw;

    public JumpEvent(float yaw) {
        this.yaw = yaw;
    }

    public float getYaw() {
        return this.yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

}
