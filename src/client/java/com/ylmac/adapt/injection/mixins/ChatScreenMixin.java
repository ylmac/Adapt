package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.render.ChatModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 聊天框模块开启时去掉原版聊天界面输入行的那条半透明黑底
 * （{@code ChatScreen.extractRenderState} 第一句 fill(2, height-14, width-2, height-2, ...)）
 * —— Rise 的输入框是自己画的圆角条，底下压一条原版黑条就不像了。
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Redirect(method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
    private void adapt$skipInputStrip(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        if (ChatModule.overlayEnabled()) {
            return;
        }
        graphics.fill(x1, y1, x2, y2, color);
    }
}
