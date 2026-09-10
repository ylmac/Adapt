package com.ylmac.adapt.injection.mixins;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 KeyMapping 的受保护字段 key（26.2 中 key 为 protected，
 * KeyMapping.click(InputConstants.Key) 需要它；LiquidBounce 通过 Kotlin 绕过，Java 需访问器）。
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("key")
    InputConstants.Key adapt_getKey();
}
