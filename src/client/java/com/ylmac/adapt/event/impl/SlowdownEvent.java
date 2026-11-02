package com.ylmac.adapt.event.impl;

/** 减速事件（可修改是否减速） */
public class SlowdownEvent {

    private boolean slowdown;

    public SlowdownEvent(boolean slowdown) {
        this.slowdown = slowdown;
    }

    public boolean isSlowdown() {
        return this.slowdown;
    }

    public void setSlowdown(boolean slowdown) {
        this.slowdown = slowdown;
    }

}
