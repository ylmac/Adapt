package com.ylmac.adapt.render.halo;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Halo 遮罩的 RenderType。26.3 没有 OutputTarget，几何走 outline 属性进 outline 缓冲；
 * mask 目标本身由 {@link HaloGlowRenderer} 在 framegraph 里 blit。
 */
public final class AdaptHaloRenderTypeFactory {
    private static final Map<Identifier, RenderType> MASK_TYPES = new ConcurrentHashMap<>();

    private AdaptHaloRenderTypeFactory() {
    }

    public static RenderType create(Identifier texture) {
        if (texture == null) {
            throw new IllegalArgumentException("Halo mask texture must not be null");
        }
        return MASK_TYPES.computeIfAbsent(texture, AdaptHaloRenderTypeFactory::createUncached);
    }

    private static RenderType createUncached(Identifier texture) {
        RenderPipeline pipeline = RenderPipelines.BREEZE_WIND;
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .sortOnUpload()
                .setOutline(RenderSetup.OutlineProperty.IS_OUTLINE)
                .createRenderSetup();
        return RenderType.create("adaptclient_halo_mask", setup);
    }
}
