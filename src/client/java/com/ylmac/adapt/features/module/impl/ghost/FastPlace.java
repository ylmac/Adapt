package com.ylmac.adapt.features.module.impl.ghost;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import com.ylmac.adapt.injection.mixins.MinecraftAccessor;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * FastPlace 快速放置（移植自 Rise 6.9.5 FastPlace）。
 * <p>
 * Rise 原版：每 tick 检查手持物品，若是 ItemBlock 则把
 * Minecraft.rightClickDelayTimer 压到 delay 值（默认 0）—— 原版右键放置后
 * 有 4 tick 的右键冷却，期间无法再次放置；压到 0 后按住右键可连续放置。
 * <p>
 * 26.2 对应物（已 javap 确证）：Minecraft.rightClickDelay（private）。
 * - startUseItem() 开头：rightClickDelay = 4
 * - tick() 里：rightClickDelay > 0 时每 tick 递减 1
 * - handleKeybinds() 里：keyUse.isDown() && rightClickDelay == 0 &&
 *   !player.isUsingItem() 才调 startUseItem() —— 即右键延迟是放置节流的闸门
 * <p>
 * 实现：ClientTickEvent.Pre 里（tick 递减之后、handleKeybinds 之前，顺序已
 * javap 确证：tick() 中 rightClickDelay-- 发生在 handleKeybinds() 之前），
 * 手持方块时把 rightClickDelay 压到 delay 设置值（默认 0）→ 右键持续可用，
 * 连续放置。访问走 MinecraftAccessor mixin（rightClickDelay 是 private）。
 */
public class FastPlace extends Module {

    /** 放置延迟（tick 数，Rise 默认 0；压到该值以下不再有效） */
    private IntSetting delay;

    public FastPlace() {
        super("fastplace", "FastPlace", Category.COMBAT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        delay = intSetting("delay", 0, 0, 3, 1);
    }

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;
        if (!this.isEnabled()) return;

        // 手持方块时才生效（与 Rise 一致：只有放置方块才需要压右键延迟）
        ItemStack stack = mc.player.getMainHandItem();
        if (stack.getItem() instanceof BlockItem && mc instanceof MinecraftAccessor accessor) {
            accessor.adapt_setRightClickDelay(Math.min(
                    accessor.adapt_getRightClickDelay(), delay.getValue()));
        }
    }

    @Override
    public String getInfo() {
        return String.valueOf(delay.getValue());
    }
}
