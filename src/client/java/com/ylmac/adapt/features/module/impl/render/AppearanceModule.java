package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.config.DesktopSettings;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.render.AdaptScreen;

/**
 * 外观（Appearance）—— 客户端界面外观总开关，设置分类下的入口模块。
 * <p>
 * 这些项直接代理 {@link DesktopSettings} 的持久化属性（音乐播放器设置页的
 * "外观"分区读的是同一份值），所以在 ClickGUI 里改和设置页里改完全等价。
 * <p>
 * 目前包含：
 * <ul>
 *   <li>ClickGUI 动态背景：玻璃面板底下画封面流体背景（模仿音乐小组件）</li>
 *   <li>毛玻璃阴影：ClickGUI 毛玻璃面板的投影</li>
 *   <li>毛玻璃发光 (Bloom)：面板主题色发光</li>
 * </ul>
 */
public class AppearanceModule extends Module {

    // onChanged 直接写 DesktopSettings（QML 侧读同一份，双向一致）
    private final BoolSetting guiFluidBg = boolSetting("ClickGUI 动态背景", true, () -> true, v -> write(ds -> ds.guiFluidBg.set(v)));
    private final BoolSetting glassShadow = boolSetting("毛玻璃阴影", true, () -> true, v -> write(ds -> ds.glassShadow.set(v)));
    private final BoolSetting glassBloom = boolSetting("毛玻璃发光", false, () -> true, v -> write(ds -> ds.glassBloom.set(v)));

    private interface DsWriter { void write(DesktopSettings ds); }

    private static void write(DsWriter w) {
        try {
            DesktopSettings ds = AdaptScreen.getDesktopSettings();
            if (ds != null) w.write(ds);
        } catch (Throwable ignored) {
        }
    }

    public AppearanceModule() {
        super("appearance", "Appearance", Category.SETTINGS);
        setDefaultEnabled(true);      // 不是开关型模块，常驻可用
        setDefaultHidden(false);
    }

    @Override
    protected void onEnable() {
        pullFromSettings();
    }

    /** 从 DesktopSettings 读回当前值（QML 设置页改过的话保持一致）。 */
    public void pullFromSettings() {
        try {
            DesktopSettings ds = AdaptScreen.getDesktopSettings();
            if (ds == null) return;
            guiFluidBg.setValueSilently(Boolean.TRUE.equals(ds.guiFluidBg.peek()));
            glassShadow.setValueSilently(Boolean.TRUE.equals(ds.glassShadow.peek()));
            glassBloom.setValueSilently(Boolean.TRUE.equals(ds.glassBloom.peek()));
        } catch (Throwable ignored) {
        }
    }

    /** 把模块里的值写入 DesktopSettings（QML 侧读的是同一份）。 */
    public void pushToSettings() {
        try {
            DesktopSettings ds = AdaptScreen.getDesktopSettings();
            if (ds == null) return;
            ds.guiFluidBg.set(Boolean.TRUE.equals(guiFluidBg.getValue()));
            ds.glassShadow.set(Boolean.TRUE.equals(glassShadow.getValue()));
            ds.glassBloom.set(Boolean.TRUE.equals(glassBloom.getValue()));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String getInfo() {
        return "外观";
    }
}
