package com.ylmac.adapt.mixins.player;

import com.ylmac.adapt.event.bus.EventBus;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家 tick 事件 → EventBus（Pre 可取消） */
@Mixin(LocalPlayer.class)
public class LocalPlayerEventMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onPreTick(CallbackInfo ci) {
        PlayerTickEvent.Pre event = EventBus.INSTANCE.post(new PlayerTickEvent.Pre());
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onPostTick(CallbackInfo ci) {
        EventBus.INSTANCE.post(new PlayerTickEvent.Post());
    }

}
