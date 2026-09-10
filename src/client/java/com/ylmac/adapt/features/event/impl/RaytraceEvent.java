package com.ylmac.adapt.features.event.impl;

/** 射线检测视角事件（可修改视角） */
public class RaytraceEvent {

    private float yaw;
    private float pitch;

    public RaytraceEvent(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public float getYaw() {
        return this.yaw;
    }

    public float getPitch() {
        return this.pitch;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

}
