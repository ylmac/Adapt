package com.ylmac.adapt.features.event.impl;

/** 使用物品视角事件（可修改视角） */
public class UseItemEvent {

    private float yaw;
    private float pitch;

    public UseItemEvent(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

}
