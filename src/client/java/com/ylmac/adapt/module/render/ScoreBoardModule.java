package com.ylmac.adapt.module.render;

import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.render.hud.ScoreBoardOverlay;

/**
 * <p>
 * 毛玻璃 + 外发光 + 可选外框），原版侧栏由 {@code HudScoreboardMixin} 取消渲染，
 * 不会出现两份。
 * <p>
 * 位置由 HUD 编辑器（右 Command）拖动并持久化到 {@code DesktopSettings.hudLayout}。
 */
public class ScoreBoardModule extends Module {

    private final BoolSetting outline = boolSetting("Outline", false);
    private final BoolSetting blurColor = boolSetting("Blur Color", false);

    public ScoreBoardModule() {
        super("scoreboard", "ScoreBoard", Category.RENDER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    public boolean outline() {
        return outline.getValue();
    }

    public boolean blurColor() {
        return blurColor.getValue();
    }

 /** 面板后面是否有毛玻璃。 */
    public static boolean blurEnabled() {
        for (var e : com.ylmac.adapt.module.ModuleRegistry.getAllModules()) {
            if (e.linkedRealModule() instanceof ScoreBoardModule m) {
                return m.isEnabled();
            }
        }
        return false;
    }

    @Override
    protected void onEnable() {
        ScoreBoardOverlay.get().onModuleStateChanged();
    }

    @Override
    protected void onDisable() {
        ScoreBoardOverlay.get().onModuleStateChanged();
    }
}
