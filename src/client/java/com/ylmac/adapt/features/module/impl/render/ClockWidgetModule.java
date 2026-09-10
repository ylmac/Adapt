package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.EnumSetting;
import com.ylmac.adapt.features.module.settings.impl.EnumSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.EnumSetting;
import com.ylmac.adapt.render.ClockWidgetOverlay;

/**
 * 时钟小组件（HUD）—— Google Clock 9.1 的模拟表盘小组件复刻。
 * <p>
 * 四种表盘对应原版的 {@code ckn} 枚举：扇贝（Scallop）/ 数字（Numeral）/
 * 日期（Day）/ 四叶（Clover）。几何、指针角度、日期环全部照反编译出的原版
 * 代码（见 {@link ClockWidgetOverlay} 类注释），不是照着截图猜的。
 * <p>
 * 格子数在 HUD 编辑态（右 Command）拖右下角手柄调整，存进
 * {@code DesktopSettings.hudLayout}；本模块只管开关、表盘、秒针与渲染倍数。
 */
public class ClockWidgetModule extends Module {

    /** 卡片样式（Classic = 原来的圆盘底；Modern = 记分榜式卡片：毛玻璃 + 动态背景 + 影子 + bloom） */
    public enum Style { CLASSIC, MODERN }

    /** 卡片取色来源（World = 世界取色；Cover = 专辑封面取色，和迷你播放器同款） */
    public enum ColorSource { WORLD, COVER }

    // ⚠ 名字不能叫 "Style"：表盘选择已经占用了这个名字，重名会导致表盘选项失效
    //   （用户实测："时钟不能调整显示模式了 永远都是扇贝形状"）
    private final EnumSetting<Style> cardStyle = enumSetting("Card Style", Style.CLASSIC);
    private final EnumSetting<ColorSource> colorSource = enumSetting("Color Source", ColorSource.COVER);
    private final BoolSetting dynamicBg = boolSetting("Dynamic Background", true);


    private final EnumSetting<ClockWidgetOverlay.Face> style =
            enumSetting("Style", ClockWidgetOverlay.Face.SCALLOP);
    private final BoolSetting secondsHand = boolSetting("Seconds Hand", true);
    private final DoubleSetting scale = doubleSetting("Scale", 1.0, 0.5, 2.5, 0.05);

    public ClockWidgetModule() {
        super("clockwidget", "ClockWidget", Category.RENDER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    public Style getStyle() {
        return cardStyle.getValue();
    }

    /** 默认封面；"世界"只在 Interface → Debug 打开时才生效（世界取色已废弃）。 */
    public ColorSource getColorSource() {
        if (!com.ylmac.adapt.features.module.impl.render.InterfaceModule.isDebug()) {
            return ColorSource.COVER;
        }
        return colorSource.getValue();
    }

    public boolean dynamicBackground() {
        return dynamicBg.getValue();
    }

    public static ClockWidgetModule get() {
        for (var e : com.ylmac.adapt.features.module.ModuleRegistry.getAllModules()) {
            if (e.linkedRealModule() instanceof ClockWidgetModule m) {
                return m;
            }
        }
        return null;
    }

    public Style getCardStyle() {
        return cardStyle.getValue();
    }




    public ClockWidgetOverlay.Face getFace() {
        return style.getValue();
    }

    /** 秒针开关（对应原版系统设置 {@code AnalogClockSecondsHandFps}，0 = 不画秒针）。 */
    public boolean showSeconds() {
        return secondsHand.getValue();
    }

    public double getScale() {
        return scale.getValue();
    }

    @Override
    protected void onEnable() {
        ClockWidgetOverlay.get().onModuleStateChanged();
    }

    @Override
    protected void onDisable() {
        ClockWidgetOverlay.get().onModuleStateChanged();
    }
}
