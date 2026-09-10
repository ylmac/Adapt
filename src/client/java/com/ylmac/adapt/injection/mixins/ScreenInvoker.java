package com.ylmac.adapt.injection.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 {@code Screen.defaultHandleGameClickEvent}（protected static）——
 * 聊天框自己做点击命中测试后要用它来派发 §链接/命令/复制 等点击事件。
 */
@Mixin(Screen.class)
public interface ScreenInvoker {

    @Invoker("defaultHandleGameClickEvent")
    static void adapt$handleGameClick(ClickEvent event, Minecraft minecraft, Screen screen) {
        throw new AssertionError();
    }
}
