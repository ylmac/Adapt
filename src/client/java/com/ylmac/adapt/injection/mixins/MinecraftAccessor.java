package com.ylmac.adapt.injection.mixins;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 Minecraft 的受保护/私有字段（26.2 已 javap 确证）：
 * - missTime：攻击间隔（protected）。startAttack() 开头 if (missTime > 0) return false，
 *   空挥（MISS）时若 hasMissTime 则置 10，tick() 里每 tick 递减 —— 等价 1.8.9 的
 *   Minecraft.leftClickCounter（Rise NoClickDelay 清的就是它）。
 * - rightClickDelay：右键放置/使用延迟（private）。startUseItem() 开头置 4，
 *   tick() 里每 tick 递减，handleKeybinds() 里 keyUse.isDown() 且
 *   rightClickDelay == 0 才调 startUseItem() —— 等价 1.8.9 的
 *   Minecraft.rightClickDelayTimer（Rise FastPlace 改的就是它）。
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Accessor("missTime")
    int adapt_getMissTime();

    @Accessor("missTime")
    void adapt_setMissTime(int missTime);

    @Accessor("rightClickDelay")
    int adapt_getRightClickDelay();

    @Accessor("rightClickDelay")
    void adapt_setRightClickDelay(int rightClickDelay);

}
