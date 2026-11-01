package net.minecraft.client.renderer.rendertype;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates the halo mask render type without using the vanilla outline render type.
 * This class lives beside RenderType so it can call RenderType.create directly.
 * <p>
 * 26.3：{@code OutputTarget}/{@code setOutputTarget} 已从 {@link RenderSetup} 移除。
 * Halo 遮罩改走 outline 属性把几何画进 outline 缓冲；真正的 mask RT 仍由
 * {@link com.ylmac.adapt.render.HaloGlowRenderer} 在 framegraph 阶段自行 blit/采样。
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
