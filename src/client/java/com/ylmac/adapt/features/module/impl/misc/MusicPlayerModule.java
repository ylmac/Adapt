package com.ylmac.adapt.features.module.impl.misc;

import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.render.QmlScreen;
import com.ylmac.adapt.render.QplayerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 音乐播放器模块（真实模块，替代原假模块条目）。
 * <p>
 * 启用 → 打开音乐播放器界面（QplayerScreen）；禁用 → 若当前正是音乐播放器界面则关闭。
 * 与 AutoClicker 一样走 ModuleManager 注册 → ClickGUI/命令/按键统一驱动。
 */
public class MusicPlayerModule extends Module {

    public MusicPlayerModule() {
        super("musicplayer", "MusicPlayer", Category.SETTINGS);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }
    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        // 已在我们自己的 QML 界面（ClickGUI）中 → 直接替换为音乐播放器；
        // 其他界面（聊天/容器）不打断，仅置位（下次进入游戏时由 onEnable 打开）。
        if (screen instanceof QmlScreen) {
            mc.gui.setScreen(new QplayerScreen());
        } else if (mc.level != null && screen == null) {
            mc.gui.setScreen(new QplayerScreen());
        }
    }

    @Override
    protected void onDisable() {
        // 若当前正是音乐播放器界面则关闭它（回到游戏）
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        if (screen instanceof QplayerScreen) {
            mc.gui.setScreen(null);
        }
    }
}
