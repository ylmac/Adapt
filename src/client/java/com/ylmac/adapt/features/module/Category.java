package com.ylmac.adapt.features.module;

/** 模块分类 */
public enum Category {

    COMBAT("combat", "战斗"),
    PLAYER("player", "玩家"),
    MOVEMENT("movement", "移动"),
    RENDER("render", "渲染"),
    SETTINGS("settings", "设置");

    private final String id;
    private final String displayName;

    Category(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** 注册用英文 id */
    public String getId() {
        return id;
    }

    /** 显示名（中文，与 ClickGUI/HUD 一致） */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return id;
    }

}
