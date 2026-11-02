package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.command.ChatUtil;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.Packet;

/**
 * ResourcePackSpoof 资源包伪装。
 * <p>
 * 直接回一个 C19PacketResourcePackStatus(SUCCESSFULLY_LOADED)，假装已加载，
 * 服务器就不会再反复推送/踢出。
 * <p>
 * 26.2 对应包：
 * - 请求包 {@link ClientboundResourcePackPushPacket}（UUID + URL + hash + required）
 * - 响应包 {@link ServerboundResourcePackPacket}(uuid, Action.SUCCESSFULLY_LOADED)
 * <p>
 * 实现：
 * 1. 收到 Push 包时取消（不触发下载/弹窗），立刻回 SUCCESSFULLY_LOADED；
 * 2. 同时回一个 {@link ServerboundResourcePackPacket}(uuid, ACCEPTED)（进入已接受
 * 状态，服务端据此认为客户端流程正常）；
 * 3. 收到 Pop 包（服务端结束资源包）时也回 SUCCESSFULLY_LOADED，保证状态机闭环。
 * <p>
 * 说明：26.2 资源包走 configuration/play 阶段的公共协议（common 包），
 * 取消后 vanilla 不会下载任何内容，只发状态包，是最干净的伪装方式。
 */
public class ResourcePackSpoof extends Module {

    public ResourcePackSpoof() {
        super("resourcepackspoof", "ResourcePackSpoof", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();
        if (packet == null) return;

        // 1. 资源包请求：取消下载流程，假装已加载
        if (packet instanceof ClientboundResourcePackPushPacket push) {
            event.cancel();
            if (mc.getConnection() != null) {
                // 静默发包（绕过 PacketEvent，避免与接收互相干扰）
                mc.getConnection().getConnection().send(
                        new ServerboundResourcePackPacket(push.id(),
                                ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED), null);
                // 再回一个 ACCEPTED，进入"已接受"状态（部分服务器要求先 ACCEPTED）
                mc.getConnection().getConnection().send(
                        new ServerboundResourcePackPacket(push.id(),
                                ServerboundResourcePackPacket.Action.ACCEPTED), null);
            }
            ChatUtil.sendFormatted("&aResourcePackSpoof&7: 已假装接受资源包 &e" + push.url());
        }

        // 2. 资源包结束（Pop）：也回一个成功，保持状态机闭环
        if (packet instanceof ClientboundResourcePackPopPacket pop) {
            pop.id().ifPresent(id -> {
                if (mc.getConnection() != null) {
                    mc.getConnection().getConnection().send(
                            new ServerboundResourcePackPacket(id,
                                    ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED), null);
                }
            });
        }
    }
}
