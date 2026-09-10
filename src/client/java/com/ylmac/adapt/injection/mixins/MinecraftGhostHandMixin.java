package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.exploit.GhostHand;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GhostHand 隔空交互 Mixin（等价 Rise GhostHand 的 RightClickEvent 处理）。
 * <p>
 * 26.2 的右键开容器链路（已 javap 确证）：
 * Minecraft.tick → pick()（重算 mc.hitResult，4.5 方块距离）→ handleKeybinds()
 * → startUseItem()：hitResult 为 BLOCK 时直接调 gameMode.useItemOn（经
 * startPrediction 发 ServerboundUseItemOnPacket，无二次距离检查）。
 * <p>
 * 因此只需在每次 pick() 结束后把 mc.hitResult 覆盖成 GhostHand 自己算的
 * 远距离射线结果（mc.hitResult 是 public 可变字段），同 tick 稍后执行的
 * startUseItem 就会对远处的容器发出交互请求 —— 实现隔空开容器。
 * 覆盖时机在 tick 主线程内 pick() 之后，右键动作（handleKeybinds）之前，
 * 不会影响其它渲染用途（hitResult 每 tick 会被 pick() 重算，本 mixin 只覆盖一帧）。
 * <p>
 * 注意：26.2 的 Minecraft.pick(float) 返回 void（结果写入 mc.hitResult 字段），
 * 所以这里用 CallbackInfo（TAIL 注入，从 mc.hitResult 读结果）。
 */
@Mixin(Minecraft.class)
public class MinecraftGhostHandMixin {

    @Inject(method = "pick", at = @At("TAIL"))
    private void adapt_ghostHandPick(float tickDelta, CallbackInfo ci) {
        GhostHand ghostHand = GhostHand.get();
        if (ghostHand != null) {
            ghostHand.onPickTail(Minecraft.getInstance().hitResult);
        }
    }
}
