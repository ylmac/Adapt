package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.EnumSetting;
import com.ylmac.adapt.render.ModuleListOverlay;

/**
 * 模块列表（HUD 界面）模块 —— 真实模块，替代原假条目。
 * <p>
 * 控制 ModuleListOverlay HUD 的显示/隐藏（默认启用显示）。
 * tag = 当前界面模式（Modern/Classic/Wurst/Creida），由 Mode 设置更新。
 * <p>
 * 对齐 Rise 6.9.5 Interface 模块的结构：
 * - mode          = InterfaceModeValue（Modern/Classic/Wurst/Creida）
 * - colorMode     = ModernArrayListColorModeValue（Static/Fade/Breathe）
 * - shadowEffect  = ShaderEffectModeValue（Glow/Shadow）
 * 三者都是模块的 EnumSetting → ClickGUI 设置覆盖层里直接 ComboBox 选择。
 */
public class InterfaceModule extends Module {

    /** 界面模式枚举（对齐 ModuleListOverlay.Mode） */
    public enum InterfaceMode { MODERN, CLASSIC, WURST, CREIDA }

    /** Modern 列表颜色模式（Rise: 0=Static 1=Fade 2=Breathe） */
    public enum ModernColorMode { STATIC, FADE, BREATHE }

    /** Modern 阴影特效（Rise: 0=Glow 1=Shadow） */
    public enum ShaderEffect { GLOW, SHADOW }

    /** 列表背景（Rise BackGroundModeValue: Off/Normal，默认 Normal） */
    public enum BackGround { OFF, NORMAL }

    /** ClickGUI 样式（ADAPT = 我们现在的 QML 界面；RISE = Rise 6.9.5 风格） */
    public enum ClickGuiStyle { ADAPT, RISE }

    private final EnumSetting<InterfaceMode> modeSetting;
    private final EnumSetting<ModernColorMode> colorModeSetting;
    private final EnumSetting<ShaderEffect> shaderEffectSetting;

    public InterfaceModule() {
        super("interface", "Interface", Category.SETTINGS);
        setDefaultEnabled(true);   // Rise: Interface autoEnabled
        setDefaultHidden(false);

        // Mode（Rise InterfaceModeValue）：切模式时同步 ModuleListOverlay + tag
        modeSetting = enumSetting("Mode", InterfaceMode.MODERN, mode -> {
            ModuleListOverlay overlay = ModuleListOverlay.get();
            overlay.mode = ModuleListOverlay.Mode.valueOf(mode.name());
            refreshTag();
        });
        // Modern ArrayList 颜色模式（Rise ModernArrayListColorModeValue）
        colorModeSetting = enumSetting("Color Mode", ModernColorMode.FADE, cm -> {
            ModuleListOverlay.get().modernColorMode = cm.ordinal();
        });
        // Modern Shader Effect（Rise ShaderEffectModeValue）
        shaderEffectSetting = enumSetting("Shader Effect", ShaderEffect.SHADOW, se -> {
            ModuleListOverlay.get().shadowEffectMode = se.ordinal();
        });
        // 列表背景（Rise BackGroundModeValue）：Normal = 磨砂玻璃底（模糊 + 黑色蒙版）
        enumSetting("BackGround", BackGround.NORMAL, bg ->
                ModuleListOverlay.get().backgroundMode = bg.ordinal());
        // 模糊半径（Rise Interface.blurRadius 默认 12，范围 1..30）
        intSetting("Blur Radius", 12, 1, 30, 1, r ->
                ModuleListOverlay.get().blurRadius = r);
        // 背景不透明度（Rise Interface.backgroundAlpha 默认 110；开模糊时用这个值，关模糊时 Rise 用 150）
        intSetting("Background Alpha", 110, 0, 255, 1, a ->
                ModuleListOverlay.get().backgroundAlpha = a);

        // ClickGUI 样式（默认 ADAPT = 现有界面；RISE = Rise 6.9.5 风格界面）
        clickGuiStyle = enumSetting("Click GUI", ClickGuiStyle.ADAPT);

        // Shaders（Rise Interface.Shaders，默认 false）：开关 Rise GUI 的 BLOOM 发光通道
        shaders = boolSetting("Shaders", false);


        // 左上角 logo 文字（HUD 左上角显示；默认 Pixel，可在设置里改）
        stringSetting("左上角文字", "Pixel", v -> ModuleListOverlay.get().setLogoText(v));
        ModuleListOverlay.get().setLogoText("Pixel");
        refreshTag();
    }

    /** ClickGUI 样式设置（"Click GUI"：ADAPT / RISE） */
    private final EnumSetting<ClickGuiStyle> clickGuiStyle;

    /** Rise Interface.Shaders：GUI 发光通道开关 */
    private final com.ylmac.adapt.features.module.settings.impl.BoolSetting shaders;

    /**
     * 调试模式是否开启 —— 唯一入口在 UI 设置里
     * （设置 → 外观 → Debug 开关，持久化在 DesktopSettings.debugMode）。
     * 打开后才会出现「取色来源 = 世界取色」等详细选项。
     */
    public static boolean isDebug() {
        try {
            com.ylmac.adapt.config.DesktopSettings ds = com.ylmac.adapt.render.QplayerScreen.getDesktopSettings();
            return ds != null && Boolean.TRUE.equals(ds.debugMode.peek());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Rise GUI 的 bloom 通道是否开启。 */
    public boolean shadersEnabled() {
        return Boolean.TRUE.equals(shaders.getValue());
    }

    /** 当前是否使用 Rise 风格 ClickGUI。 */
    public static boolean isRiseClickGui() {
        InterfaceModule m = get();
        return m != null && m.clickGuiStyle.getValue() == ClickGuiStyle.RISE;
    }

    /** 供渲染层/打开逻辑取当前模块实例。 */
    public static InterfaceModule get() {
        for (var e : com.ylmac.adapt.features.module.ModuleRegistry.getAllModules()) {
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
