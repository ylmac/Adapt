package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.render.ChatModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 聊天框模块开启时取消<b>聊天界面输入框</b>的原版渲染 —— 由 {@code ChatOverlay}
 * 画 Rise 样式的输入框（圆角 + 毛玻璃 + 白字 + 光标）。
 * <p>
 * 只对 {@link ChatScreen} 生效：EditBox 还被告示牌/铁砧/命令方块等界面用，不能一刀切。
 */
@Mixin(EditBox.class)
public class EditBoxMixin {

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
    private void adapt$skipVanillaChatInput(
            net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTick, CallbackInfo ci) {
        if (ChatModule.overlayEnabled()
                && Minecraft.getInstance().gui.screen() instanceof ChatScreen) {
            ci.cancel();
        }
    }
}
