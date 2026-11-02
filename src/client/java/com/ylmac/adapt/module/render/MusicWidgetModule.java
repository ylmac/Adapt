package com.ylmac.adapt.module.render;

import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.EnumSetting;
import com.ylmac.adapt.render.hud.MusicWidgetOverlay;

/**
 * 音乐小组件。格子数在 HUD 编辑态拖右下角调整，位置和格子都写进
 * {@code DesktopSettings.hudLayout}。数据来自 {@code PlayerController}。
 */
public class MusicWidgetModule extends Module {

    /** 卡片样式（Classic = 原来的纯色底；Modern = 记分榜式面板 + 歌词流体背景 + bloom） */
    public enum Style { CLASSIC, MODERN }

    /** 卡片取色来源（World = 跟随世界取色的 Monet 主题；Cover = 从歌曲封面取色） */
    public enum ColorSource { WORLD, COVER }

    private final EnumSetting<Style> style;
    private final EnumSetting<ColorSource> colorSource;
    private final com.ylmac.adapt.module.settings.impl.BoolSetting dynamicBg;
    private final DoubleSetting scale;

    public MusicWidgetModule() {
        super("musicwidget", "MusicWidget", Category.RENDER);
        setDefaultEnabled(false);
        setDefaultHidden(false);

        style = enumSetting("Style", Style.CLASSIC);
        colorSource = enumSetting("Color Source", ColorSource.COVER);
        dynamicBg = boolSetting("Dynamic Background", true);
        scale = doubleSetting("Scale", 1.0, 0.5, 2.5, 0.05);
    }

    public double getScale() {
        return scale.getValue();
    }

    public Style getStyle() {
        return style.getValue();
    }

    /** 动态背景（歌词流体背景）开关。 */
    public boolean dynamicBackground() {
        return dynamicBg.getValue();
    }

    /**
 * 卡片取色来源（只影响音乐小组件这张卡片）。
 * 默认封面；"世界"只在 Interface → Debug 打开时才生效（世界取色已废弃）。
 */
    public ColorSource getColorSource() {
        if (!com.ylmac.adapt.module.render.InterfaceModule.isDebug()) {
            return ColorSource.COVER;
        }
        return colorSource.getValue();
    }

    /** 供渲染层取当前模块实例（与 ChatModule.get 同款）。 */
    public static MusicWidgetModule get() {
        for (var e : com.ylmac.adapt.module.ModuleRegistry.getAllModules()) {
            if (e.linkedRealModule() instanceof MusicWidgetModule m) {
                return m;
            }
        }
        return null;
    }

    @Override
    protected void onEnable() {
        MusicWidgetOverlay.get().onModuleStateChanged();
    }

    @Override
    protected void onDisable() {
        MusicWidgetOverlay.get().onModuleStateChanged();
    }
}
