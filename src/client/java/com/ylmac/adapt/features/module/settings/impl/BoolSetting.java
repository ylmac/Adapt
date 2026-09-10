package com.ylmac.adapt.features.module.settings.impl;

import com.ylmac.adapt.features.module.settings.Setting;

import java.util.function.Consumer;

public class BoolSetting extends Setting<Boolean> {

    public BoolSetting(String name, boolean defaultValue, Dependency dependency, Consumer<Boolean> onChanged) {
        super(name, dependency, onChanged);
        this.value = defaultValue;
        this.defaultValue = defaultValue;
    }

}
