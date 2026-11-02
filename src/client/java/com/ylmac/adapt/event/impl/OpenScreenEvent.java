package com.ylmac.adapt.event.impl;

import com.ylmac.adapt.event.bus.Cancellable;
import net.minecraft.client.gui.screens.Screen;

/** 打开界面事件（可取消） */
public class OpenScreenEvent extends Cancellable {

    private final Screen screen;

    public OpenScreenEvent(Screen screen) {
        this.screen = screen;
    }

    public Screen getScreen() {
        return screen;
    }

}
