package com.ylmac.adapt.mixins.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ylmac.adapt.module.render.OldSwing;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 打开 1.7 挥击时，用手的下劈替换现在的前戳位移。 */
@Mixin(net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class)
public class OldSwingMixin {

    @Inject(method = "swingArm", at = @At("HEAD"), cancellable = true)
    private void adapt$oldSwing(float swing, PoseStack pose, int side, HumanoidArm arm, CallbackInfo ci) {
        if (!OldSwing.active()) return;
        float chop = Mth.sin(swing * swing * (float) Math.PI);
        float down = Mth.sin(Mth.sqrt(swing) * (float) Math.PI);
        pose.rotateDegrees(Axis.YP, side * chop * -20f);
        pose.rotateDegrees(Axis.ZP, side * down * -20f);
        pose.rotateDegrees(Axis.XP, down * -80f);
        ci.cancel();
    }
}
