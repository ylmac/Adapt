package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.bus.EventPriority;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/**
 * 拦截移动包，把 onGround 改为 true，并重置 fallDistance。
 */
public class NoFall extends Module {

    public NoFall() {
        super("nofall", "NoFall", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        // 模式：SPOOF = 改写移动包 onGround；PACKET = 坠落时补发落地包
        enumSetting("模式", Mode.SPOOF);
        boolSetting("重置坠落距离", true);
    }

    public enum Mode {
        SPOOF,
        PACKET
    }

    private Mode mode() {
        return (Mode) settings.get(0).getValue();
    }

    private boolean resetFallDistance() {
        return (boolean) settings.get(1).getValue();
    }

    /** 判断是否应触发：超过安全坠落距离（3.0，等于 vanilla 无伤上限） */
    private boolean shouldProtect() {
        return mc.player != null
                && !mc.player.isCreative()
                && !mc.player.isSpectator()
                && !mc.player.getAbilities().flying
                && !mc.player.isFallFlying()
                && mc.player.fallDistance > 3.0;
    }

    /** 移动包改写（Spoof）：坠落时把发给服务器的 onGround 置 true */
    @EventHandler(priority = EventPriority.HIGH)
    private void onPacketSend(PacketEvent.Send event) {
        if (mode() != Mode.SPOOF || nullCheck()) return;
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) return;

        if (shouldProtect()) {
            // 26.2 的 ServerboundMovePlayerPacket 字段全部 final、只读，必须构造新包替换：
            // 原包带位置/旋转 → 补完整 PosRot（onGround=true）；否则发 StatusOnly
            if (mc.player != null) {
                Vec3 pos = mc.player.position();
                event.setPacket(new ServerboundMovePlayerPacket.PosRot(
                        pos.x, pos.y, pos.z,
                        mc.player.getYRot(), mc.player.getXRot(), true, mc.player.horizontalCollision));
            } else {
                event.setPacket(new ServerboundMovePlayerPacket.StatusOnly(true, false));
            }
            if (resetFallDistance()) {
                mc.player.fallDistance = 0;
            }
        }
    }

    /** 补发落地包（Packet）：坠落时主动发 onGround=true 的 StatusOnly，重置坠落距离 */
    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(PlayerTickEvent.Post event) {
        if (mode() != Mode.PACKET || nullCheck()) return;
        if (!shouldProtect()) return;
        if (mc.player.tickCount % 2 != 0) return; // 每 2 tick 补发一次，避免发包过密

        mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
        if (resetFallDistance()) {
            mc.player.fallDistance = 0;
        }
    }
}
