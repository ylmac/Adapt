package com.ylmac.adapt.mixins.combat;

import com.ylmac.adapt.module.combat.AutoClicker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 手动左键点击补偿：
 * 26.2 无 clickMouse，真实攻击统一走 Minecraft.startAttack（handleKeybinds 的
 * while (keyAttack.consumeClick) startAttack 链，已 javap 确证）。
 * 钩住 startAttack HEAD：只要执行了真实攻击（无论来源），就给 AutoClicker 追加一次延迟；
 * AutoClicker 自身产生的点击也会经此（clickPending 为 true 时自动跳过）。
 */
@Mixin(Minecraft.class)
public class MinecraftStartAttackMixin {

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void adapt_onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        AutoClicker autoClicker = AutoClicker.get();
        if (autoClicker != null) {
            autoClicker.onManualClick();
        }
    }
}
