package net.minecraft.client.renderer.rendertype;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates the halo mask render type without using the vanilla outline render type.
 * This class lives beside RenderType so it can call RenderType.create directly.
 */
public final class AdaptHaloRenderTypeFactory {
    private static final OutputTarget HALO_MASK_TARGET = new OutputTarget(
            "adaptclient:halo_mask", com.ylmac.adapt.render.HaloGlowRenderer::maskTargetOrThrow);
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
                .setOutputTarget(HALO_MASK_TARGET)
                .createRenderSetup();
        return RenderType.create("adaptclient_halo_mask", setup);
    }
}
