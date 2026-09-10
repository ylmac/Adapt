package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.render.ChatModule;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 聊天框模块开启时取消<b>原版聊天的背景层</b>（{@code DisplayMode.BACKGROUND}）——
 * 那一趟负责"HUD 上的聊天 + 淡出 + 每行黑底"，由 {@code ChatOverlay} 用 Rise 的
 * 样式画（圆角半透明底 + 毛玻璃 + 投影）。
 * <p>
 * <b>前景层（FOREGROUND）必须留给原版</b>：打开聊天界面时是
 * {@code ChatScreen.extractRenderState} 用 FOREGROUND 画消息（ChatScreen L68/L322），
 * 那一趟同时负责<b>悬停高亮、光标形状、tooltip、滚动条</b>，而且它的坐标就是
 * {@code captureClickableText} 命中测试用的坐标 —— 取消它就等于"看得见点不中"
 * （用户："点击的效果都没有了"）。
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void adapt$skipVanillaChat(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                       net.minecraft.client.gui.Font font, int ticks,
                                       int mouseX, int mouseY,
                                       ChatComponent.DisplayMode displayMode,
                                       boolean changeCursorOnInsertions,
                                       CallbackInfo ci) {
        // 两趟都取消：我们用自己的布局画消息，悬停/点击也自己做命中测试
        // （见 ChatOverlay.handleHoverAndClick）。
        if (ChatModule.overlayEnabled()) {
            ci.cancel();
        }
    }
}
