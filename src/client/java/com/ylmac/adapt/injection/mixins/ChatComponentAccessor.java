package com.ylmac.adapt.injection.mixins;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 暴露 26.2 {@code ChatComponent.trimmedMessages}（private）—— 聊天框 HUD 复刻
 * Rise 的 {@code Chat} 时要用它拿到"已折行、待显示"的消息行（含 addedTime 用于淡出）。
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> adapt_getTrimmedMessages();

    @Accessor("chatScrollbarPos")
    int adapt_getChatScrollbarPos();
}
