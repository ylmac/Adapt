package com.ylmac.adapt.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Owns the external color/depth target used by the halo mask and schedules the
 * backend-neutral halo post chain. All methods must be called on the render thread.
 */
public final class HaloGlowRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("adaptclient-halo-glow");
    private static final Identifier HALO_MASK_ID = Identifier.parse("adaptclient:halo_mask");
    private static final Identifier HALO_POST_CHAIN_ID = Identifier.parse("adaptclient:halo_glow");
    private static final GpuFormat MASK_FORMAT = GpuFormat.RGBA8_UNORM;
    private static final Vector4f MASK_CLEAR_COLOR = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);

    private static TextureTarget maskTarget;
    private static boolean maskRequested;
    private static boolean chainMissingLogged;

    private HaloGlowRenderer() {
    }

    /** Starts a new frame; no GPU resources are created by this operation. */
    public static void beginFrame() {
        maskRequested = false;
    }

    public static boolean hasMask() {
        return maskRequested;
    }

    /** 屏幕辉光的额外深度目标会让 MoltenVK 原生崩溃。环本体不走这里。 */
    public static void skipScreenGlow() {
        if (!chainMissingLogged) {
            chainMissingLogged = true;
            LOGGER.warn("Halo screen glow skipped: the extra depth target faults MoltenVK");
        }
    }

    /**
     * Returns the mask render type. The factory is in Minecraft's render-type
     * package because RenderType.create is package-private in the mapped sources.
     */
    public static RenderType maskType(Identifier texture) {
        maskRequested = true;
        return net.minecraft.client.renderer.rendertype.AdaptHaloRenderTypeFactory.create(texture);
    }

    /** Compatibility overload for existing callers using the halo's white texture. */
    public static RenderType maskType() {
        return maskType(Identifier.parse("adaptclient:textures/halo/white.png"));
    }

    public static RenderTarget maskTargetOrThrow() {
        TextureTarget target = maskTarget;
        if (target == null || target.getColorTexture() == null || target.getDepthTexture() == null) {
            throw new IllegalStateException("Halo mask target is not prepared for this frame");
        }
        return target;
    }

    private static void ensureMaskTarget(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Halo mask target dimensions must be positive: "
                    + width + "x" + height);
        }
        if (maskTarget == null) {
            maskTarget = new TextureTarget("adaptclient_halo_mask", width, height, MASK_FORMAT, GpuFormat.D32_FLOAT);
            LOGGER.info("Created halo mask target {}x{}", width, height);
        } else if (maskTarget.width != width || maskTarget.height != height) {
            maskTarget.resize(width, height);
            LOGGER.info("Resized halo mask target to {}x{}", width, height);
        }
    }

    /**
     * Ensures and clears the external RGBA8/depth mask target, then copies the
     * main depth texture with the same command encoder.
     */
    public static void prepareMask(RenderTarget main) {
        if (main == null) {
            throw new IllegalArgumentException("main render target must not be null");
        }
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Halo mask preparation must run on the render thread");
        }

        if (!main.hasDepth() || main.getDepthTexture() == null) {
            throw new IllegalStateException("Main render target has no depth texture");
        }
        ensureMaskTarget(main.width, main.height);

        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.clearColorAndDepthTextures(maskTarget.getColorTexture(), MASK_CLEAR_COLOR,
                    maskTarget.getDepthTexture(), RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
            // MC 26.2: mip, destX, destY, sourceX, sourceY, width, height.
            encoder.copyTextureToTexture(main.getDepthTexture(), maskTarget.getDepthTexture(),
                    0, 0, 0, 0, 0, main.width, main.height);
        } catch (RuntimeException e) {
            if (!chainMissingLogged) {
                chainMissingLogged = true;
                LOGGER.warn("Halo mask copy skipped: {}", e.toString());
            }
        }
    }

    /**
     * Adds the halo post chain to the caller's frame graph. The main target is
     * replaced only after the post chain has registered its passes.
     */
    public static void addToFrame(FrameGraphBuilder graph,
                                  ResourceHandle<RenderTarget> mainHandle,
                                  int width,
                                  int height,
                                  Consumer<ResourceHandle<RenderTarget>> mainReplaced) {
        if (graph == null || mainHandle == null || mainReplaced == null) {
            throw new IllegalArgumentException("Halo frame graph arguments must not be null");
        }

        RenderTarget main = mainHandle.get();
        if (main == null) {
            throw new IllegalStateException("Main render target handle is invalid");
        }
        if (main.width != width || main.height != height) {
            throw new IllegalStateException("Halo frame graph dimensions do not match main target: "
                    + width + "x" + height + " vs " + main.width + "x" + main.height);
        }
        if (!hasMask()) {
            return;
        }
        ensureMaskTarget(main.width, main.height);

        // FrameGraph construction happens before the main pass executes. The mask's
        // color and copied depth are populated later by prepareMask immediately
        // before the translucent feature phase.
        ResourceHandle<RenderTarget> maskHandle = graph.importExternal(HALO_MASK_ID.toString(), maskTarget);
        Map<Identifier, ResourceHandle<RenderTarget>> targets = new HashMap<>();
        targets.put(PostChain.MAIN_TARGET_ID, mainHandle);
        targets.put(HALO_MASK_ID, maskHandle);

        PostChain.TargetBundle bundle = new MapTargetBundle(targets, mainReplaced);
        PostChain chain = Minecraft.getInstance().getShaderManager()
                .getPostChain(HALO_POST_CHAIN_ID, Set.of(PostChain.MAIN_TARGET_ID, HALO_MASK_ID));
        if (chain == null) {
            if (!chainMissingLogged) {
                chainMissingLogged = true;
                LOGGER.warn("Halo post chain unavailable, screen glow skipped: {}", HALO_POST_CHAIN_ID);
            }
            return;
        }
        chain.addToFrame(graph, width, height, bundle);
        // Keep the request alive until the main pass reaches executeTranslucent(),
        // where prepareMask copies the current scene depth into the mask target.
    }

    /** Destroys the external mask target and clears all frame state. */
    public static void close() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Halo mask target must be destroyed on the render thread");
        }
        if (maskTarget != null) {
            maskTarget.destroyBuffers();
            maskTarget = null;
        }
        maskRequested = false;
        LOGGER.info("Destroyed halo mask target");
    }

    private static final class MapTargetBundle implements PostChain.TargetBundle {
        private final Map<Identifier, ResourceHandle<RenderTarget>> targets;
        private final Consumer<ResourceHandle<RenderTarget>> mainReplaced;

        private MapTargetBundle(Map<Identifier, ResourceHandle<RenderTarget>> targets,
                                Consumer<ResourceHandle<RenderTarget>> mainReplaced) {
            this.targets = targets;
            this.mainReplaced = mainReplaced;
        }

        @Override
        public void replace(Identifier id, ResourceHandle<RenderTarget> handle) {
            targets.put(id, handle);
            if (PostChain.MAIN_TARGET_ID.equals(id)) {
                mainReplaced.accept(handle);
            }
        }

        @Override
        public ResourceHandle<RenderTarget> get(Identifier id) {
            return targets.get(id);
        }
    }
}
