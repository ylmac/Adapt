package com.ylmac.adapt.module.render;

import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.EnumSetting;
import com.ylmac.adapt.render.hud.BackdropBlurCapture;
import com.ylmac.adapt.render.hud.ModuleListOverlay;

/**
 * 模块列表（HUD 界面）模块 — 真实模块，替代原假条目。
 * <p>
 * 控制 ModuleListOverlay HUD 的显示/隐藏（默认启用显示）。
 * tag = 当前界面模式（Modern/Classic/Wurst/Creida），由 Mode 设置更新。
 * <p>
 * - mode = InterfaceModeValue（Modern/Classic/Wurst/Creida）
 * - colorMode = ModernArrayListColorModeValue（Static/Fade/Breathe）
 * - shadowEffect = ShaderEffectModeValue（Glow/Shadow）
 * 三者都是模块的 EnumSetting → ClickGUI 设置覆盖层里直接 ComboBox 选择。
 */
public class InterfaceModule extends Module {

    /** 界面模式枚举（对齐 ModuleListOverlay.Mode） */
    public enum InterfaceMode { MODERN, CLASSIC, WURST, CREIDA }

 /** Modern 列表颜色模式 */
    public enum ModernColorMode { STATIC, FADE, BREATHE }

 /** Modern 阴影特效 */
    public enum ShaderEffect { GLOW, SHADOW }

 /** 列表背景 */
    public enum BackGround { OFF, NORMAL }

 /** ClickGUI 样式 */
    public enum ClickGuiStyle { LIST, ADAPT, RISE }

    private final EnumSetting<InterfaceMode> modeSetting;
    private final EnumSetting<ModernColorMode> colorModeSetting;
    private final EnumSetting<ShaderEffect> shaderEffectSetting;

    public InterfaceModule() {
        super("interface", "Interface", Category.SETTINGS);
        setDefaultEnabled(true);
        setDefaultHidden(false);

 // Mode：切模式时同步 ModuleListOverlay + tag
        modeSetting = enumSetting("Mode", InterfaceMode.MODERN, mode -> {
            ModuleListOverlay overlay = ModuleListOverlay.get();
            overlay.mode = ModuleListOverlay.Mode.valueOf(mode.name());
            refreshTag();
        });
 // Modern ArrayList 颜色模式
        colorModeSetting = enumSetting("Color Mode", ModernColorMode.FADE, cm -> {
            ModuleListOverlay.get().modernColorMode = cm.ordinal();
        });
 // Modern Shader Effect
        shaderEffectSetting = enumSetting("Shader Effect", ShaderEffect.SHADOW, se -> {
            ModuleListOverlay.get().shadowEffectMode = se.ordinal();
        });
 // 列表背景：Normal = 磨砂玻璃底（模糊 + 黑色蒙版）
        enumSetting("BackGround", BackGround.NORMAL, bg ->
                ModuleListOverlay.get().backgroundMode = bg.ordinal());
 // 模糊半径。写入时乘 2，和 HUD 卡片的 24 是同一个数，全界面共用。
        intSetting("Blur Radius", 12, 1, 30, 1, r ->
                BackdropBlurCapture.setRadius(r * 2f));
 // 背景不透明度
        intSetting("Background Alpha", 110, 0, 255, 1, a ->
                ModuleListOverlay.get().backgroundAlpha = a);

        // ClickGUI 样式（默认分栏列表）
        clickGuiStyle = enumSetting("Click GUI", ClickGuiStyle.LIST);

        shaders = boolSetting("Shaders", false);

        refreshTag();
    }

    private final EnumSetting<ClickGuiStyle> clickGuiStyle;

    private final com.ylmac.adapt.module.settings.impl.BoolSetting shaders;

    /**
 * 调试模式是否开启 — 唯一入口在 UI 设置里
 * （设置 → 外观 → Debug 开关，持久化在 DesktopSettings.debugMode）。
 * 打开后才会出现「取色来源 = 世界取色」等详细选项。
 */
    public static boolean isDebug() {
        try {
            com.ylmac.adapt.config.DesktopSettings ds = com.ylmac.adapt.compose.screens.music.AdaptScreen.getDesktopSettings();
            return ds != null && Boolean.TRUE.equals(ds.debugMode.peek());
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean shadersEnabled() {
        return Boolean.TRUE.equals(shaders.getValue());
    }

    public static boolean isRiseClickGui() {
        InterfaceModule m = get();
        return m != null && m.clickGuiStyle.getValue() == ClickGuiStyle.RISE;
    }

    /** 按 Click GUI 设置打开对应界面。 */
    public static net.minecraft.client.gui.screens.Screen createClickGui() {
        ClickGuiStyle style = ClickGuiStyle.LIST;
        InterfaceModule m = get();
        if (m != null && m.clickGuiStyle.getValue() != null) style = m.clickGuiStyle.getValue();
        return switch (style) {
            case RISE -> new com.ylmac.adapt.render.clickgui.RiseClickGuiScreen();
            case ADAPT -> new com.ylmac.adapt.compose.screens.clickgui.ClickGuiScreen();
            case LIST -> new com.ylmac.adapt.render.clickgui.ListClickGuiScreen();
        };
    }

    /** 供渲染层/打开逻辑取当前模块实例。 */
    public static InterfaceModule get() {
        for (var e : com.ylmac.adapt.module.ModuleRegistry.getAllModules()) {
            if (e.linkedRealModule() instanceof InterfaceModule m) return m;
        }
        return null;
    }

    @Override
    protected void onEnable() {
        ModuleListOverlay.get().setOverlayVisible(true);
    }

    @Override
    protected void onDisable() {
        ModuleListOverlay.get().setOverlayVisible(false);
    }

    @Override
    public String getInfo() {
        ModuleListOverlay overlay = ModuleListOverlay.get();
        String tn = overlay.mode.name();
        return tn.charAt(0) + tn.substring(1).toLowerCase();
    }

    /** ClickGUI setMode 调用：更新 tag */
    public void refreshTag() {
        ModuleListOverlay overlay = ModuleListOverlay.get();
        String tn = overlay.mode.name();
        setInfo(tn.charAt(0) + tn.substring(1).toLowerCase());
    }

    /** 读取当前模式索引（0=Modern 1=Classic 2=Wurst 3=Creida），兼容 ClickGuiBridge */
    public int getModeIndex() {
        return modeSetting.getModeIndex();
    }
}
