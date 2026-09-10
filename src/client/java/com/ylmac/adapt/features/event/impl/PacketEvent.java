package com.ylmac.adapt.features.event.impl;

import com.ylmac.adapt.features.event.bus.Cancellable;
import net.minecraft.network.protocol.Packet;

/** 网络数据包事件（Send=发送，Receive=接收，均可取消/替换） */
public class PacketEvent {

    public static class Send extends Cancellable {

        private Packet<?> packet;

        public Send(Packet<?> packet) {
            this.packet = packet;
        }

        public Packet<?> getPacket() {
            return this.packet;
        }

        public void setPacket(Packet<?> packet) {
            this.packet = packet;
        }

    }

    public static class Receive extends Cancellable {

        private Packet<?> packet;

        public Receive(Packet<?> packet) {
            this.packet = packet;
        }

        public Packet<?> getPacket() {
            return this.packet;
        }

        public void setPacket(Packet<?> packet) {
            this.packet = packet;
        }

    }

}
