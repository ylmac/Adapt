package com.ylmac.adapt.module.settings.impl;

import com.ylmac.adapt.module.settings.Setting;

public class ButtonSetting extends Setting<Runnable> {

    public ButtonSetting(String name, Runnable func, Dependency dependency) {
        super(name, dependency, null);
        this.value = func;
        this.defaultValue = func;
    }

}
