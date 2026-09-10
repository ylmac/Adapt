package com.ylmac.adapt.features.module.settings;

/**
 * Setting 的显式分组模型（仅描述语义与折叠状态，位置由 UI 统一计算）。
 */
public class SettingGroup {

    private final String name;
    private boolean collapsed = true;

    public SettingGroup(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return name;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }

    public void toggleCollapsed() {
        collapsed = !collapsed;
    }

}
