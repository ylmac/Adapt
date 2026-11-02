package com.ylmac.adapt.mixins.player;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ylmac.adapt.module.movement.NoSlow;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NoSlow 减速因子注入。
 * <p>
 * 26.2 减速链（javap 确证）：LocalPlayer.modifyInput → isUsingItem →
 * vec2.scale(itemUseSpeedMultiplier)；itemUseSpeedMultiplier 读
 * USE_EFFECTS.speedMultiplier。这里把返回值改为 1.0（不减速）—
 * 仅当 NoSlow 开启且使用物品属于已勾选类别时生效。
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerItemUseSpeedMixin {

    @ModifyReturnValue(method = "itemUseSpeedMultiplier", at = @At("RETURN"))
    private float adapt_noslow_itemUseSpeedMultiplier(float original) {
        NoSlow noSlow = NoSlow.get();
        if (noSlow != null && noSlow.isEnabled() && noSlow.shouldRemoveSlowdown()) {
            return noSlow.getSpeedMultiplier();
        }
        return original;
    }
}
