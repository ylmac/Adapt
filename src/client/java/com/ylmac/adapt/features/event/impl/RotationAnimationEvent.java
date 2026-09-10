package com.ylmac.adapt.features.event.impl;

/** 视角旋转动画事件（可修改当前/上一帧视角） */
public class RotationAnimationEvent {

    private float yaw;
    private float lastYaw;
    private float pitch;
    private float lastPitch;

    public RotationAnimationEvent(float yaw, float lastYaw, float pitch, float lastPitch) {
        this.yaw = yaw;
        this.lastYaw = lastYaw;
        this.pitch = pitch;
        this.lastPitch = lastPitch;
    }

    public float getYaw() {
        return yaw;
    }

    public float getLastYaw() {
        return lastYaw;
    }

    public float getPitch() {
        return pitch;
    }

    public float getLastPitch() {
        return lastPitch;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setLastYaw(float lastYaw) {
        this.lastYaw = lastYaw;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public void setLastPitch(float lastPitch) {
        this.lastPitch = lastPitch;
    }

}
