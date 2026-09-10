package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.event.bus.EventBus;
import com.ylmac.adapt.features.event.impl.OpenScreenEvent;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 打开界面事件 → EventBus（26.2 统一入口在 Gui.setScreen，Minecraft.setScreen 已移除） */
@Mixin(Gui.class)
public class GuiScreenEventMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        OpenScreenEvent event = EventBus.INSTANCE.post(new OpenScreenEvent(screen));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

}
