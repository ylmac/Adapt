package com.ylmac.adapt.mixins.render;

import com.ylmac.adapt.module.render.Perspective;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 自由视角时，在镜头状态被拷走之前换成环顾的朝向。 */
@Mixin(Camera.class)
public abstract class FreelookCameraMixin {

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void adapt$freelook(CameraRenderState state, DeltaTracker tick, CallbackInfo ci) {
        if (!Perspective.capture()) return;
        setRotation(Perspective.yaw(), Perspective.pitch());
    }
}
