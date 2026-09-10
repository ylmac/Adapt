package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.render.ScoreBoardOverlay;

/**
 * 积分榜（侧栏计分板 HUD）—— Rise 6.9.5 {@code ScoreBoard} 的复刻。
 * <p>
 * 开启后由 {@link ScoreBoardOverlay} 用 Rise 的样式绘制侧栏（圆角半透明底 +
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

    /** Rise 的 "Blur color"：底 = 主题色 alpha 60（否则纯黑 alpha 100）。 */
    public boolean blurColor() {
        return blurColor.getValue();
    }

    /** 面板后面是否有毛玻璃（Rise 的 BLUR 队列：模块开着就一直有）。 */
    public static boolean blurEnabled() {
        for (var e : com.ylmac.adapt.features.module.ModuleRegistry.getAllModules()) {
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
