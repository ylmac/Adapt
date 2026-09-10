package com.ylmac.adapt.injection.mixins;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.ylmac.adapt.features.module.impl.render.CustomCursor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 保持 CustomCursor，不允许 GUI hover 的 vanilla Arrow/Hand/IBeam 覆盖它。 */
@Mixin(Window.class)
public abstract class WindowCursorMixin {

    @Inject(method = "selectCursor", at = @At("HEAD"), cancellable = true)
    private void adapt_overrideCursor(CursorType cursor, CallbackInfo ci) {
        CustomCursor customCursor = CustomCursor.get();
        if (customCursor != null
                && customCursor.overrideWindowCursor((Window) (Object) this)) {
            ci.cancel();
        }
    }
}
