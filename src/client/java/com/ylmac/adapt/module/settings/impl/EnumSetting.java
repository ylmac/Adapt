package com.ylmac.adapt.module.settings.impl;

import com.ylmac.adapt.module.settings.Setting;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** 枚举选择设置（支持按名称/toString 匹配模式） */
public class EnumSetting<E extends Enum<E>> extends Setting<E> {

    private final Map<E, String> modeTranslations = new HashMap<>();
    private final E[] constants;

    public EnumSetting(String name, E defaultValue, Dependency dependency, Consumer<E> onChanged) {
        super(name, dependency, onChanged);
        this.value = defaultValue;
        this.defaultValue = defaultValue;

        Class<E> enumClass = defaultValue.getDeclaringClass();
        constants = enumClass.getEnumConstants();
    }

    public String getTranslatedValue() {
        String comp = modeTranslations.get(value);
        return comp != null ? comp : value.toString();
    }

    public String getTranslatedValueByIndex(int index) {
        if (index < 0 || index >= constants.length) return "";
        E enumValue = constants[index];
        return getTranslatedValue(enumValue);
    }

    public String getTranslatedValue(E enumValue) {
        String comp = modeTranslations.get(enumValue);
        return comp != null ? comp : enumValue.toString();
    }

    public String getTranslatedValueUnchecked(Enum<?> enumValue) {
        for (E value : constants) {
            if (value == enumValue) {
                return getTranslatedValue(value);
            }
        }
        return enumValue != null ? enumValue.toString() : "";
    }

    public boolean is(E enumValue) {
        return this.value.equals(enumValue);
    }

    public boolean is(String string) {
        return matchesMode(this.getValue(), string);
    }

    public void setMode(String mode) {
        for (E e : constants) {
            if (matchesMode(e, mode)) {
                setValue(e);
            }
        }
    }

    public void setModeSilently(String mode) {
        for (E e : constants) {
            if (matchesMode(e, mode)) {
                setValueSilently(e);
            }
        }
    }

    public void setMode(E mode) {
        setValue(mode);
    }

    public int getModeIndex() {
        int index = 0;
        for (E e : constants) {
            if (e == value) return index;
            index++;
        }
        return -1;
    }

    public E[] getModes() {
        return constants;
    }

    private boolean matchesMode(E enumValue, String mode) {
        return mode != null
                && (Objects.equals(enumValue.name(), mode)
                || Objects.equals(enumValue.toString(), mode)
                || enumValue.name().equalsIgnoreCase(mode)
                || enumValue.toString().equalsIgnoreCase(mode));
    }
}
