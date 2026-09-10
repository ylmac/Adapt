package com.ylmac.adapt.features.event.impl;

import com.ylmac.adapt.features.event.bus.Cancellable;

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
