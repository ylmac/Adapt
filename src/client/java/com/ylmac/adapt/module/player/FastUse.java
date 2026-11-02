package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.ItemStack;

/**
 * <p>
 * 老服务器依赖客户端 tickrate 计算食用速度，多发包 = 吃得更快。
 * 26.2 等价实现（同款）：使用物品期间每 tick
 * 连发 {@code ServerboundMovePlayerPacket.StatusOnly(true, horizontalCollision)}，
 * 服务器每收到一个移动包就推进一次使用计时 → 使用速度翻倍。
 * <p>
 * 可选「提前释放」：使用进度提前完成时调用 releaseUsingItem（Immediate 模式
 * 在发包后 release 的简化版），提前结束使用动作。
 */
public class FastUse extends Module {

    public FastUse() {
        super("fastuse", "FastUse", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
 // 每 tick 补发的移动包数量
        intSetting("每tick发包数", 1, 1, 20, 1);
        // 是否提前释放使用动作（进度到 90% 就 release， ItemUseTime 思路）
        boolSetting("提前释放", false);
    }

    private int packetsPerTick() {
        return (int) settings.get(0).getValue();
    }

    private boolean earlyRelease() {
        return (boolean) settings.get(1).getValue();
    }

    /** 判断当前使用的物品是否需要加速：吃/喝等有使用进度条的物品 */
    private boolean shouldAccelerate() {
        if (!mc.player.isUsingItem()) return false;
        ItemStack stack = mc.player.getUseItem();
        if (stack.isEmpty()) return false;
        // 26.2 可消耗物品带 CONSUMABLE 组件（食物/药水等）；没有的一律不加速（如弓/盾）
        return stack.has(DataComponents.CONSUMABLE);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck()) return;
        if (!shouldAccelerate()) return;

        int count = Math.max(packetsPerTick(), 1);
 // 连发 onGround=true 的移动包：老服务器按移动包推进使用计时
        for (int i = 0; i < count; i++) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
        }

        // 提前释放：使用剩余时间 ≤ 总时长 10% 时主动结束使用（等价 releaseUsingItem）
        if (earlyRelease()) {
            ItemStack stack = mc.player.getUseItem();
            if (!stack.isEmpty()) {
                int total = stack.getItem().getUseDuration(stack, mc.player);
                int remaining = mc.player.getUseItemRemainingTicks();
                if (total > 0 && remaining <= total / 10) {
                    mc.player.releaseUsingItem();
                }
            }
        }
    }
}
