package com.ylmac.adapt.render.lighting;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Native deferred port of 's CLOUDS_REIMAGINED pass.
 *
 * The pass runs inside LevelRenderer's main runnable after opaque terrain/features and
 * before translucent terrain/water. It snapshots opaque color/depth, reconstructs a
 * world ray for every sky pixel, raymarches the original cloud-water density texture,
 * and alpha-composites the first cloud hit into the main target.
 */
public final class ReimaginedCloudRenderer {

    private static final Identifier CLOUD_DENSITY_ID =
            Identifier.parse("adaptclient:textures/clouds/cloud-water.png");
    private static final Identifier CLOUD_PIPELINE_ID =
            Identifier.parse("adaptclient:reimagined_cloud_deferred");
    private static final Identifier CLOUD_SHADER_ID =
            Identifier.parse("adaptclient:post/reimagined_cloud");

    private static final int CLOUD_FRAME_SIZE = 192;
    private static final int CLOUD_TARGET_USAGE = 15;

    private static final BindGroupLayout CLOUD_LAYOUT = BindGroupLayout.builder()
            .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("OpaqueDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("CloudDensitySampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("CloudFrame", UniformType.UNIFORM_BUFFER)
            .withUniform("AdaptLighting", UniformType.UNIFORM_BUFFER)
            .build();

    private static final RenderPipeline CLOUD_PIPELINE = RenderPipeline.builder()
            .withLocation(CLOUD_PIPELINE_ID)
            .withVertexShader(Identifier.parse("minecraft:core/screenquad"))
            .withFragmentShader(CLOUD_SHADER_ID)
            .withBindGroupLayout(CLOUD_LAYOUT)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
            .build();

    private static final ByteBuffer CLOUD_FRAME = ByteBuffer.allocateDirect(CLOUD_FRAME_SIZE)
            .order(ByteOrder.nativeOrder());

    private static TextureTarget opaqueSnapshot;
    private static com.mojang.renderpearl.api.buffers.GpuBuffer cloudFrameBuffer;
    private static int frameIndex;
    private static boolean frameReady;

    private ReimaginedCloudRenderer() {
    }

    /** Updates the per-frame cloud camera data before LevelRenderer's main pass runs. */
    public static void updateFrame(ClientLevel level, CameraRenderState camera, float partialTicks) {
        frameReady = false;
        if (level == null || camera == null || camera.pos == null || camera.projectionMatrix == null
                || camera.viewRotationMatrix == null) {
            return;
        }

        Matrix4f projectionInverse = new Matrix4f(camera.projectionMatrix).invert();
        Matrix4f viewInverse = new Matrix4f(camera.viewRotationMatrix).transpose();

        float sunAngleDegrees = Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe()
                .getValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE, partialTicks);
        float timeAngle = (sunAngleDegrees * 0.0027777778f + 0.25f) % 1.0f;
        float elevation = (timeAngle - 0.25f) * (float) (Math.PI * 2.0);
        Vector3f worldSun = new Vector3f((float) Math.sin(elevation), (float) Math.cos(elevation), 0.0f);
        Vector3f trueSunView = camera.viewRotationMatrix.transformDirection(worldSun).normalize();

        CLOUD_FRAME.clear();
        projectionInverse.get(0, CLOUD_FRAME);
        viewInverse.get(64, CLOUD_FRAME);
        putVec4(128, (float) camera.pos.x, (float) camera.pos.y, (float) camera.pos.z, 1.0f);
        putVec4(144, 192.0f, 4.2f, camera.depthFar, ++frameIndex);
        putVec4(160, trueSunView.x, trueSunView.y, trueSunView.z, 0.0f);
        putVec4(176, level.getRainLevel(partialTicks), timeAngle,
                level.getThunderLevel(partialTicks), partialTicks);

        ensureCloudFrameBuffer();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(cloudFrameBuffer.slice(), CLOUD_FRAME.clear());
        frameReady = true;
    }

    /**
 * Executes inside the opaque->translucent split of the native main runnable.
 * Main color/depth are copied first to avoid sampling from an attachment being written.
 */
    public static void renderAfterOpaque(com.mojang.blaze3d.pipeline.RenderTarget mainTarget) {
        if (!frameReady || mainTarget == null || !mainTarget.hasDepth() || mainTarget.getDepthTexture() == null) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Reimagined cloud pass must run on the render thread");
        }

        ensureSnapshot(mainTarget.width, mainTarget.height);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(mainTarget.getColorTexture(), opaqueSnapshot.getColorTexture(),
                0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
        encoder.copyTextureToTexture(mainTarget.getDepthTexture(), opaqueSnapshot.getDepthTexture(),
                0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);

        AbstractTexture density = Minecraft.getInstance().getTextureManager().getTexture(CLOUD_DENSITY_ID);
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Adapt deferred clouds",
                mainTarget.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(CLOUD_PIPELINE));
            pass.setUniform("CloudFrame", cloudFrameBuffer);
            AdaptLightingUniform.bind(pass);
            pass.setUniform("SceneSampler", opaqueSnapshot.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setUniform("OpaqueDepthSampler", opaqueSnapshot.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("CloudDensitySampler", density.getTextureView(), density.getSampler());
            pass.draw(3, 1, 0, 0);
        }
    }

    public static void close() {
        if (opaqueSnapshot != null) {
            opaqueSnapshot.destroyBuffers();
            opaqueSnapshot = null;
        }
        if (cloudFrameBuffer != null) {
            cloudFrameBuffer.close();
            cloudFrameBuffer = null;
        }
        frameReady = false;
    }

    private static void ensureSnapshot(int width, int height) {
        if (opaqueSnapshot == null) {
            opaqueSnapshot = new TextureTarget("adaptclient_reimagined_cloud_snapshot", width, height,
                    GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else if (opaqueSnapshot.width != width || opaqueSnapshot.height != height) {
            opaqueSnapshot.resize(width, height);
        }
    }

    private static void ensureCloudFrameBuffer() {
        if (cloudFrameBuffer == null) {
            GpuDevice device = RenderSystem.getDevice();
            cloudFrameBuffer = device.createBuffer(() -> "Reimagined CloudFrame UBO",
                    com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_DST
                            | com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_UNIFORM,
                    CLOUD_FRAME_SIZE);
        }
    }

    private static void putVec4(int offset, float x, float y, float z, float w) {
        CLOUD_FRAME.putFloat(offset, x);
        CLOUD_FRAME.putFloat(offset + 4, y);
        CLOUD_FRAME.putFloat(offset + 8, z);
        CLOUD_FRAME.putFloat(offset + 12, w);
    }
}
