package com.ylmac.adapt.event.impl;

import com.ylmac.adapt.event.bus.Cancellable;
import net.minecraft.client.input.KeyEvent;

/** 键盘按键事件（可取消） */
public class KeyPressEvent extends Cancellable {

    private final KeyEvent keyEvent;
    private final int action;

    public KeyPressEvent(KeyEvent keyEvent, int action) {
        this.keyEvent = keyEvent;
        this.action = action;
    }

    public KeyEvent getKeyEvent() {
        return this.keyEvent;
    }

    public int getAction() {
        return this.action;
    }

    public int getKey() {
        return this.keyEvent.key();
    }

    public int getModifiers() {
        return this.keyEvent.modifiers();
    }

}
