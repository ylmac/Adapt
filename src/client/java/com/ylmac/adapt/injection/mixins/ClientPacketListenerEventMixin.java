package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.event.bus.EventBus;
import com.ylmac.adapt.features.event.impl.GameJoinedEvent;
import com.ylmac.adapt.features.event.impl.RespawnEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 加入世界 / 重生事件 → EventBus */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerEventMixin {

    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void onLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        EventBus.INSTANCE.post(new GameJoinedEvent());
    }

    @Inject(method = "handleRespawn", at = @At("RETURN"))
    private void onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        EventBus.INSTANCE.post(new RespawnEvent());
    }

}
