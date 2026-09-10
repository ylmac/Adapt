package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.EnumSetting;
import com.ylmac.adapt.render.MusicWidgetOverlay;

/**
 * 音乐小组件（HUD）—— PixelPlayer 桌面小组件的 Minecraft 版。
 * <p>
 * 尺寸按"格"计价（一格 80px），布局由格子数推导，对应 PixelPlayer 的
 * Glance widget（{@code ui/glancewidget/}）：
 * <ul>
 *   <li>4×1 条状 —— 封面 + 标题/艺术家 + 上一首/播放/下一首（BarWidget4x1.kt）</li>
 *   <li>2×2 方形 —— 封面 + 播放键 / 上一首 + 下一首（GridWidget2x2.kt）</li>
 *   <li>4×2 大卡 —— 大封面 + 两行标题 + 进度条 + 三键（ControlWidget4x2.kt）</li>
 * </ul>
 * 格子数在 HUD 编辑态（右 Command）拖右下角手柄调整，存进
 * {@code DesktopSettings.hudLayout}。
 * 数据全部来自本项目的 {@code PlayerController}（标题/艺术家/封面字节/播放状态/
 * 进度），配色取当前 Monet 主题（{@code StyleManager.currentScheme}），对应
 * PixelPlayer 的 {@code getWidgetColors()}。
 * <p>
 * 位置由 {@link com.ylmac.adapt.render.HudEditor}（右 Command）拖动并持久化到
 * {@code DesktopSettings.hudLayout}；本模块只管开关与渲染倍数。
 */
public class MusicWidgetModule extends Module {

    /** 卡片样式（Classic = 原来的纯色底；Modern = 记分榜式面板 + 歌词流体背景 + bloom） */
    public enum Style { CLASSIC, MODERN }

    /** 卡片取色来源（World = 跟随世界取色的 Monet 主题；Cover = 从歌曲封面取色） */
    public enum ColorSource { WORLD, COVER }

    private final EnumSetting<Style> style;
    private final EnumSetting<ColorSource> colorSource;
    private final com.ylmac.adapt.features.module.settings.impl.BoolSetting dynamicBg;
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
        if (!com.ylmac.adapt.features.module.impl.render.InterfaceModule.isDebug()) {
            return ColorSource.COVER;
        }
        return colorSource.getValue();
    }

    /** 供渲染层取当前模块实例（与 ChatModule.get() 同款）。 */
    public static MusicWidgetModule get() {
        for (var e : com.ylmac.adapt.features.module.ModuleRegistry.getAllModules()) {
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
