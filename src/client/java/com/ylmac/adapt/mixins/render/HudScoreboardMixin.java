package com.ylmac.adapt.mixins.render;

import com.ylmac.adapt.module.render.ScoreBoardModule;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.ylmac.adapt.render.hud.ScoreBoardOverlay;

/**
 * 积分榜模块开启时取消<b>原版侧栏</b>的渲染 — 由 {@code ScoreBoardOverlay} 用
 */
@Mixin(Hud.class)
public class HudScoreboardMixin {

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void adapt$skipVanillaSidebar(
            net.minecraft.client.gui.GuiGraphicsExtractor graphics, Objective objective,
            CallbackInfo ci) {
        if (ScoreBoardModule.blurEnabled()) {
            ci.cancel();
        }
    }
}
