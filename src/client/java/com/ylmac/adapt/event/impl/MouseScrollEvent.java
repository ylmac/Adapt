package com.ylmac.adapt.event.impl;

import com.ylmac.adapt.event.bus.Cancellable;

/** 鼠标滚轮事件（可取消） */
public class MouseScrollEvent extends Cancellable {

    private final double value;

    public MouseScrollEvent(double value) {
        this.value = value;
    }

    public double getValue() {
        return value;
    }

}
