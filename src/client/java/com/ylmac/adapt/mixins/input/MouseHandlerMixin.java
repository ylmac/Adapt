package com.ylmac.adapt.mixins.input;

import com.ylmac.adapt.event.bus.EventBus;
import com.ylmac.adapt.event.impl.MousePressEvent;
import com.ylmac.adapt.event.impl.MouseScrollEvent;
import com.ylmac.adapt.module.render.Perspective;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 鼠标事件 → EventBus（可取消） */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onButton(long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
        MousePressEvent event = EventBus.INSTANCE.post(new MousePressEvent(rawButtonInfo.button(), action, rawButtonInfo.modifiers()));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    /** 按住 Alt 环顾时，鼠标只转镜头，不转人物。 */
    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void adapt$freelookTurn(LocalPlayer player, double yaw, double pitch) {
        if (Perspective.steer(yaw, pitch)) return;
        player.turn(yaw, pitch);
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        MouseScrollEvent event = EventBus.INSTANCE.post(new MouseScrollEvent(yoffset));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

}
