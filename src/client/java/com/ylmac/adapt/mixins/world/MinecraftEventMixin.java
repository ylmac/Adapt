package com.ylmac.adapt.mixins.world;

import com.ylmac.adapt.event.bus.EventBus;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.GameLeftEvent;
import com.ylmac.adapt.event.impl.RightClickEvent;
import com.ylmac.adapt.event.impl.StartUseItemEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 客户端主循环/右键/使用物品/离开世界事件 → EventBus（部分可取消） */
@Mixin(Minecraft.class)
public class MinecraftEventMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onPreTick(CallbackInfo ci) {
        EventBus.INSTANCE.post(new ClientTickEvent.Pre());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onPostTick(CallbackInfo ci) {
        EventBus.INSTANCE.post(new ClientTickEvent.Post());
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void onDisconnect(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            EventBus.INSTANCE.post(new GameLeftEvent());
        }
    }

    @Inject(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z", ordinal = 0, shift = At.Shift.BEFORE),
            cancellable = true)
    private void onHandleKeybinds(CallbackInfo ci) {
        RightClickEvent event = EventBus.INSTANCE.post(new RightClickEvent());
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void onStartUseItem(CallbackInfo ci) {
        StartUseItemEvent event = EventBus.INSTANCE.post(new StartUseItemEvent());
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

}
