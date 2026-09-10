package com.ylmac.adapt.injection.mixins;

import com.ylmac.adapt.features.event.bus.EventBus;
import com.ylmac.adapt.features.event.impl.KeyPressEvent;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 键盘事件 → EventBus（可取消） */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long handle, int action, KeyEvent keyEvent, CallbackInfo ci) {
        KeyPressEvent event = EventBus.INSTANCE.post(new KeyPressEvent(keyEvent, action));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

}
