package com.ylmac.adapt.features.event.impl;

import com.mojang.blaze3d.vertex.PoseStack;

/** 3D 渲染事件 */
public class Render3DEvent {

    private final PoseStack poseStack;

    public Render3DEvent(PoseStack poseStack) {
        this.poseStack = poseStack;
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }

}
