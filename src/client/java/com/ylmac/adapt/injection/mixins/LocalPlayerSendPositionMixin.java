package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.event.bus.EventBus;
import com.ylmac.adapt.features.event.impl.SendPositionEvent;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 发包旋转事件发布（静默旋转基础设施）。
 * <p>
 * 26.2 的 LocalPlayer.sendPosition() 每 tick 用 getYRot()/getXRot() 构造
 * ServerboundMovePlayerPacket（Rot/PosRot/StatusOnly，javap 确证）。本 mixin 在
 * HEAD 发布 {@link SendPositionEvent}（从玩家当前值初始化），模块（Scaffold/
 * Criticals/LegitScaffold/ManualKBDisplacement）可修改 yaw/pitch/onGround；
 * 修改后写回玩家字段，随后构造的移动包即带新旋转 —— 等价 Rise RotationComponent
 * 的静默旋转（只改发包，不改渲染）。
 * <p>
 * 注：不直接改包（26.2 移动包 record 不可变），改 player 的 yaw/pitch 字段最简洁。
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSendPositionMixin {

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void adapt_onSendPosition(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        SendPositionEvent event = EventBus.INSTANCE.post(new SendPositionEvent(
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.onGround()));
        if (event.isCancelled()) {
            return;
        }
        // 模块可能修改了旋转/位置/落地状态 → 写回玩家（后续包构造读取）
        player.setYRot(event.getYaw());
        player.setXRot(event.getPitch());
        player.setOnGround(event.isOnGround());
    }
}
