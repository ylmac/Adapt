package com.ylmac.adapt.module.render;

import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.ModuleRegistry;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import com.ylmac.adapt.render.hud.ChatOverlay;

/**
 * <p>
 * 自下而上的消息行 + 底部输入框），原版聊天 HUD 由 {@code ChatComponentMixin} 取消，
 * 不会出现两份。
 * <p>
 * 位置由 HUD 编辑器（右 Command）拖动并持久化到 {@code DesktopSettings.hudLayout}。
 */
public class ChatModule extends Module {

    private final IntSetting width = intSetting("Width", 320, 40, 320, 1);
    private final BoolSetting background = boolSetting("Background", true);
    private final IntSetting openHeight = intSetting("Open Height", 130, 0, 200, 1);
    private final IntSetting maxClosedHeight = intSetting("Max Closed Height", 130, 0, 500, 1);
    private final IntSetting fadeSpeed = intSetting("Message Fade (ms)", 5000, 0, 5000, 50);

    public ChatModule() {
        super("chat", "Chat", Category.RENDER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    public int width() {
        return width.getValue();
    }

    public boolean background() {
        return background.getValue();
    }

    public int openHeight() {
        return openHeight.getValue();
    }

    public int maxClosedHeight() {
        return maxClosedHeight.getValue();
    }

    public int fadeSpeed() {
        return fadeSpeed.getValue();
    }

    /** 模块是否开启（mixin 里判重用，避免每帧遍历模块表）。 */
    public static boolean overlayEnabled() {
        for (ModuleRegistry.ModuleEntry e : ModuleRegistry.getAllModules()) {
            if (e.linkedRealModule() instanceof ChatModule m) {
                return m.isEnabled();
            }
        }
        return false;
    }

    private static ChatModule self() {
        for (ModuleRegistry.ModuleEntry e : ModuleRegistry.getAllModules()) {
            if (e.linkedRealModule() instanceof ChatModule m) {
                return m;
            }
        }
        return null;
    }

    public static ChatModule get() {
        return self();
    }

    @Override
    protected void onEnable() {
        ChatOverlay.get().onModuleStateChanged();
    }

    @Override
    protected void onDisable() {
        ChatOverlay.get().onModuleStateChanged();
    }
}
