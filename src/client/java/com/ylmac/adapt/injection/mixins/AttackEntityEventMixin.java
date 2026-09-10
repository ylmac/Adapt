package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.event.bus.EventBus;
import com.ylmac.adapt.features.event.impl.AttackEntityEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 攻击实体事件 → EventBus（等价 Rise 的 AttackEvent / 1.8.9 的 AttackEntityEvent）。
 * <p>
 * 钩住 Minecraft.startAttack：无论攻击来自手动左键、AutoClicker 还是 KillAura 的
 * KeyMapping.click 模拟点击，都统一经过这里（startAttack 是 handleKeybinds
 * while (keyAttack.consumeClick()) 的唯一攻击出口，已 javap 确证）。
 * Criticals 模块监听此事件在攻击前补跳；KillAura 也监听以维护最近攻击时间。
 * <p>
 * 事件可取消：取消后本次 startAttack 的后续流程（攻击发包）会被跳过，
 * 但 SwingHandEvent 已在事件分发后由 swing 自行触发，不受影响。
 */
@Mixin(Minecraft.class)
public class AttackEntityEventMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void adapt_onAttackEntity(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.hitResult == null
                || !(mc.hitResult instanceof EntityHitResult entityHitResult)) {
            return;
        }
        Entity entity = entityHitResult.getEntity();
        AttackEntityEvent event = EventBus.INSTANCE.post(new AttackEntityEvent(mc.player, entity));
        if (event.isCancelled()) {
            cir.cancel();
        }
    }
}
