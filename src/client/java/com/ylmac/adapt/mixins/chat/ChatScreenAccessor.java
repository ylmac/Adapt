package com.ylmac.adapt.mixins.chat;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 26.2 {@code ChatScreen.input}（protected）— 聊天框 HUD 要拿输入内容与
 * 光标位置自己画输入框（原版 EditBox 的渲染被 {@link EditBoxMixin} 取消）。
 */
@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {

    @Accessor("input")
    EditBox adapt_getInput();
}
