package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.event.bus.EventBus;
import com.ylmac.adapt.features.event.impl.LevelUpdateEvent;
import com.ylmac.adapt.render.BlockLightSourceCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 世界加载/更新事件 → EventBus。
 * <p>
 * 26.2 中所有世界切换（登录/重生/维度传送/退出）都收敛到
 * Minecraft.setLevel(ClientLevel)（javap 确证：handleLogin/handleRespawn
 * 均调用 Minecraft.setLevel）。在这里发布 LevelUpdateEvent，
 * 供 ManualKBDisplacement 等模块在换世界时重置状态
 * （原 LevelUpdateEvent 无人发布 → 世界重置逻辑死代码，本 mixin 修复）。
 */
@Mixin(Minecraft.class)
public class LevelUpdateEventMixin {

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void adapt_onSetLevel(ClientLevel level, CallbackInfo ci) {
        BlockLightSourceCache.clear();
        EventBus.INSTANCE.post(new LevelUpdateEvent());
    }
}
