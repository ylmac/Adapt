package com.ylmac.adapt.features.module.impl.misc;

import com.ylmac.adapt.compose.host.AdaptComposeScreen;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.render.AdaptScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 音乐播放器模块。
 * 启用 → 打开 AdaptScreen（Compose）；禁用 → 若当前正是音乐播放器则关闭。
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
        // ClickGUI（Compose 或遗留 QML）内 → 直接替换为音乐播放器
        if (screen instanceof AdaptComposeScreen) {
            mc.gui.setScreen(new AdaptScreen());
        } else if (mc.level != null && screen == null) {
            mc.gui.setScreen(new AdaptScreen());
        }
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        if (screen instanceof AdaptScreen) {
            mc.gui.setScreen(null);
        }
    }
}
