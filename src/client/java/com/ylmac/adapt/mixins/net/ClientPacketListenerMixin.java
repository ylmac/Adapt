package com.ylmac.adapt.mixins.net;

import com.ylmac.adapt.command.CommandManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 — 该方案在 26.2 已不存在）。
 * <p>
 * 26.2 聊天发送统一走 ClientPacketListener：普通消息 sendChat(String)、
 * "/" 命令 sendCommand(String)（见 ChatScreen.handleChatInput 字节码确认）。
 * 注入 sendChat 的 HEAD：以 "." 开头的消息 cancel（不发服务器），本地执行。
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void onSendChat(String message, CallbackInfo ci) {
        if (message != null && CommandManager.get().isTypingCommand(message)) {
            ci.cancel(); // 拦截：不上报服务器
            CommandManager.get().handleCommand(message); // 本地执行
        }
    }
}
