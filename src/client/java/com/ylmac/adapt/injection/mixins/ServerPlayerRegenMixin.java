package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.module.impl.combat.Regen;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Regen 集成服务器路径（26.2 合理实现，替代 Rise 1.8.9 的发包骗回血）：
 * 单人/LAN 时客户端与服务端同进程（IntegratedServer），注入
 * ServerPlayer.tickRegeneration HEAD（Player.aiStep 每 tick 调用，
 * 服务端主线程执行）—— 本进程服务端实体直接真实回血。
 * 仅对本机服务器玩家生效（LAN 主机的客户端），远程服务器走 Regen 的本地模拟。
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerRegenMixin {

    @Inject(method = "tickRegeneration", at = @At("HEAD"))
    private void adapt_onTickRegeneration(CallbackInfo ci) {
        Regen regen = Regen.get();
        if (regen != null) {
            regen.onServerTickRegen((ServerPlayer) (Object) this);
        }
    }
}
