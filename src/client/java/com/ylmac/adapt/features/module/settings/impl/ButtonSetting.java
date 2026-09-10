package com.ylmac.adapt.features.module.settings.impl;

import com.ylmac.adapt.features.module.settings.Setting;

public class ButtonSetting extends Setting<Runnable> {

    public ButtonSetting(String name, Runnable func, Dependency dependency) {
        super(name, dependency, null);
        this.value = func;
        this.defaultValue = func;
    }

}
