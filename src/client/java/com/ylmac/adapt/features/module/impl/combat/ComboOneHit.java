package com.ylmac.adapt.features.module.impl.combat;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.AttackEntityEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.Entity;

/**
 * ComboOneHit —— Combo 模式极速击杀（移植自 Rise 6.9.5 ComboOneHit）。
 * <p>
 * Rise 原版：监听 AttackEvent（真实攻击发生时），连发 N 个
 * C02PacketUseEntity(ATTACK)，以极高攻击频率达到秒杀效果。
 * <p>
 * 26.2 移植（API 经 javap /Users/ylmac/Documents/26.2 确证）：
 * - 触发事件：{@link AttackEntityEvent} 由 AttackEntityEventMixin 在
 *   {@code Minecraft.startAttack} HEAD 发布（startAttack 是 handleKeybinds
 *   while (keyAttack.consumeClick()) 的唯一攻击出口，等价 Rise 的 AttackEvent）；
 * - 1.8.9 的 C02PacketUseEntity(ATTACK) → 26.2 独立 {@link ServerboundAttackPacket(int entityId)}；
 * - 静默发包：{@code mc.getConnection().getConnection().send(packet, null)}
 *   （与 Breaker/Blink 一致，绕过 PacketEvent）。
 * <p>
 * 注意：攻击频率远超正常值，服务端可检测（Rise 定位为"瞬杀"类辅助）。
 */
public class ComboOneHit extends Module {

    /** 每次攻击补发的攻击包数量（Rise 默认 50，范围 1-1000） */
    private final IntSetting packets;

    /** 当前瞄准目标缓存（onAttack 时取事件携带目标） */
    private Entity target;

    public ComboOneHit() {
        super("comboonehit", "ComboOneHit", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        packets = intSetting("攻击包数", 50, 1, 1000, 1);
    }

    /** 真实攻击发生时触发（等价 Rise AttackEvent 处理）：补发 N 个攻击包 */
    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (mc.player == null || mc.level == null) return;
        // 屏幕打开时不触发
        if (mc.gui.screen() != null) return;

        Entity hit = event.getEntity();
        if (hit == null || hit == mc.player || !hit.isAlive()) return;
        target = hit;

        int count = packets.getValue();
        for (int i = 0; i < count; i++) {
            sendAttack(target);
        }
    }

    /** 静默发攻击包（绕过 PacketEvent，与 Breaker/Blink 的 sendSilently 一致） */
    private void sendAttack(Entity entity) {
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().send(new ServerboundAttackPacket(entity.getId()), null);
        }
    }

    @Override
    protected void onEnable() {
        target = null;
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    @Override
    public String getInfo() {
        return String.valueOf(packets.getValue());
    }
}
