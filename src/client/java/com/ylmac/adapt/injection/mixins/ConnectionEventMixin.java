package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.event.bus.EventBus;
import com.ylmac.adapt.features.event.impl.GameJoinedEvent;
import com.ylmac.adapt.features.event.impl.PacketEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.event.impl.RespawnEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 网络数据包/玩家 tick/加入与重生事件 → EventBus */
@Mixin(Connection.class)
public class ConnectionEventMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void onSendPacket(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        // 只分发常见客户端发包类型，其余直通
        // （KeepAlive/攻击/交互：Disabler、PingSpoof 等 exploit 模块需要拦截）
        if (packet instanceof ServerboundMovePlayerPacket
                || packet instanceof ServerboundKeepAlivePacket
                || packet instanceof ServerboundPlayerActionPacket
                || packet instanceof ServerboundSwingPacket
                || packet instanceof ServerboundUseItemPacket
                || packet instanceof ServerboundAttackPacket
                || packet instanceof ServerboundInteractPacket) {
            PacketEvent.Send event = EventBus.INSTANCE.post(new PacketEvent.Send(packet));
            if (event.isCancelled()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    private void onReceivePacket(io.netty.channel.ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        PacketEvent.Receive event = EventBus.INSTANCE.post(new PacketEvent.Receive(packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

}
