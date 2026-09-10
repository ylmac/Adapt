package com.ylmac.adapt.features.module.settings.impl;

import com.ylmac.adapt.features.module.settings.Setting;

public class KeybindSetting extends Setting<Integer> {

    public KeybindSetting(String name, int defaultValue, Dependency dependency) {
        super(name, dependency, null);
        this.value = defaultValue;
        this.defaultValue = defaultValue;
    }

}
