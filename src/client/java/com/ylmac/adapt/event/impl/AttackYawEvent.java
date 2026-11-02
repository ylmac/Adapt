package com.ylmac.adapt.event.impl;

/** 攻击偏航角事件（可修改） */
public class AttackYawEvent {

    private float yaw;

    public AttackYawEvent(float yaw) {
        this.yaw = yaw;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

}
