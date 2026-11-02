package com.ylmac.adapt.render.lighting;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Deferred 光照的 G-buffer 资源组与全屏光照 pass。
 * <p>
 * 架构（26.2 原生 API，javap 实证）：
 * - 地形 opaque pass 的 {@code CommandEncoder.createRenderPass} 由
 * {@code DefaultChunkRenderer.render} 内部创建；本类提供
 * {@link #createOpaquePass} 把附件换成 G-buffer 六目标 + 主深度（复用，
 * 不重清），由 SodiumTerrainUniformMixin 在非半透明 pass 时 @Redirect 调用。
 * - 光照 pass 在 executeSolid 之后（云 pass 之前）全屏采样 G-buffer，
 * 执行完整 adaptDoLighting + adaptShadowVisibility，输出到主颜色；
 * 背景像素（sky/实体，coverage=0）保留主颜色快照原样。
 * <p>
 * G-buffer 布局（设备 maxColorAttachments=8，Apple M4/MoltenVK 实测）：
 * GB0 RGBA32F xyz=视空间位置, w=coverage(1=几何)
 * GB1 RGBA16F xyz=视空间法线, w=roughness
 * GB2 RGBA8 rgb=albedo, a=metallic
 * GB3 RGBA16F rgb=雾色, a=fogFactor
 * GB4 RGBA32F xyz=世界相对位置（阴影接收 + 光照）, w=vanillaAO
 * GB5 RGBA16F xy=lmCoordM(block/sky light), z=emissive, w=顶点色 alpha
 */
public final class DeferredGBuffer {

    private static final int USAGE_RT = GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private static final Identifier LIGHT_PIPELINE_ID = Identifier.parse("adaptclient:deferred_light");
    private static final Identifier LIGHT_SHADER_ID = Identifier.parse("adaptclient:post/adapt_deferred_light");
    private static final Identifier DEBUG_PIPELINE_ID = Identifier.parse("adaptclient:deferred_light_debug");
    private static final Identifier DEBUG_SHADER_ID = Identifier.parse("adaptclient:post/adapt_deferred_light_debug");
    private static final Vector4f CLEAR_ZERO = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);

    /** -Dadapt.debugGBuffer=1..6：albedo/法线/视位置/coverage/世界位置/lightmap 可视化。 */
    private static final int DEBUG_MODE = Integer.getInteger("adapt.debugGBuffer", 0);

    private static final BindGroupLayout LIGHT_LAYOUT = BindGroupLayout.builder()
            .withUniform("GB0Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB1Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB2Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB3Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB4Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB5Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("MainColorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("MainDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("ShadowMapSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SkyOcclusionSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("AdaptLighting", UniformType.UNIFORM_BUFFER)
            .withUniform("ShadowFrame", UniformType.UNIFORM_BUFFER)
            // 体素遮挡场（方块光阴影 + 玻璃遮挡）。26.2 无 3D 纹理，用 texel
            // buffer + usamplerBuffer 承载 128³ 体素。每体素 RGBA32_UINT 16 字节：
            // 四个 1/16 精度 AABB。shader 在 DDA 命中格子后对每个 AABB 做真实
            // slab 相交 — 概率式覆盖率仍然表现为"整格处理"，两个槽位时栅栏的
            // 连接臂会被并进包围盒又变成整格（实测"栅栏依旧不能正确透光"）。
            .withUniform("VoxelField", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
            // 增长式光源列表：去掉 UBO 定长数组的 32 上限（实测"显示范围内
            // 有些光没法显示"）。每光源 2 条 RGBA32_FLOAT texel。
            .withUniform("LightList", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
            .withUniform("LightTiles", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
            .build();

    private static final BindGroupLayout DEBUG_LAYOUT = BindGroupLayout.builder()
            .withUniform("GB0Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB1Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB2Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB3Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB4Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB5Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("MainColorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("DebugConfig", UniformType.UNIFORM_BUFFER)
            .build();

    private static final RenderPipeline LIGHT_PIPELINE = RenderPipeline.builder()
            .withLocation(LIGHT_PIPELINE_ID)
            .withVertexShader(Identifier.parse("minecraft:core/screenquad"))
            .withFragmentShader(LIGHT_SHADER_ID)
            .withBindGroupLayout(LIGHT_LAYOUT)
            // MRT 两目标：0=场景 HDR（太阳+环境，线性），1=随机采样的局部光。
            // 拆开是 MegaLights 范式的前提 — 局部光要单独降噪，太阳/环境是
            // 解析解没有噪点，不该被降噪器糊。
            .withColorTargetState(0, new ColorTargetState(Optional.empty(),
                    GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withColorTargetState(1, new ColorTargetState(Optional.empty(),
                    GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
            .build();

    private static final RenderPipeline DEBUG_PIPELINE = RenderPipeline.builder()
            .withLocation(DEBUG_PIPELINE_ID)
            .withVertexShader(Identifier.parse("minecraft:core/screenquad"))
            .withFragmentShader(DEBUG_SHADER_ID)
            .withBindGroupLayout(DEBUG_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(),
                    GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
            .build();

    private static GpuTexture gb0;
    private static GpuTexture gb1;
    private static GpuTexture gb2;
    private static GpuTexture gb3;
    private static GpuTexture gb4;
    private static GpuTexture gb5;
    private static GpuTextureView gb0View;
    private static GpuTextureView gb1View;
    private static GpuTextureView gb2View;
    private static GpuTextureView gb3View;
    private static GpuTextureView gb4View;
    private static GpuTextureView gb5View;
    private static TextureTarget mainColorSnapshot;
    /**
 * 主深度快照。光照 pass 的颜色附件是主颜色，若同 pass 直接采样主深度就是
 * attachment 自读—26.2 对此既无校验也无 barrier（memoryBarrier 只在
 * submitRenderPass 发出，字节码实证），结果未定义。必须先拷再采。
 */
    private static TextureTarget mainDepthSnapshot;
    private static com.mojang.renderpearl.api.buffers.GpuBuffer debugConfigBuffer;
    private static boolean gbufferNeedsClear = true;
    private static int width;
    private static int height;

    private DeferredGBuffer() {
    }

    public static boolean isOpaqueActive() {
        return OPAQUE_ACTIVE.get();
    }

    private static final ThreadLocal<Boolean> OPAQUE_ACTIVE = ThreadLocal.withInitial(() -> false);

    /** 每帧 G-buffer 接管次数（用于验证 SOLID/CUTOUT 两层是否都进 G-buffer）。 */
    private static int opaquePassCount;
    private static boolean opaqueInFrame;
    private static int opaqueGroupCount;

    public static void setOpaqueActive(boolean active) {
        OPAQUE_ACTIVE.set(active);
    }

    public static void onOpaqueGroupBegin() {
        if (!opaqueInFrame) {
            opaqueInFrame = true;
            opaqueGroupCount = 0;
            opaquePassCount = 0;
        }
        opaqueGroupCount++;
    }

    public static void onOpaquePassCreated(String label) {
        if (!opaqueInFrame) {
            return;
        }
        opaquePassCount++;
    }

    public static void logFramePassStats(String stage) {
        opaqueInFrame = false;
    }

    /**
 * 构造地形 opaque 的 RenderPass：G-buffer 六色附件 + 主深度（复用，不重清）。
 * 由 SodiumTerrainUniformMixin 在非半透明 pass 时 @Redirect 调用。
 */
    public static RenderPass createOpaquePass(CommandEncoder encoder, java.util.function.Supplier<String> label,
                                              GpuTextureView mainDepthView) {
        int w = mainDepthView.getWidth(0);
        int h = mainDepthView.getHeight(0);
        ensureResources(w, h);
        Optional<Vector4fc> clear = gbufferNeedsClear ? Optional.of(CLEAR_ZERO) : Optional.empty();
        gbufferNeedsClear = false;
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(label)
                .withColorAttachment(gb0View, clear)
                .withColorAttachment(gb1View, clear)
                .withColorAttachment(gb2View, clear)
                .withColorAttachment(gb3View, clear)
                .withColorAttachment(gb4View, clear)
                .withColorAttachment(gb5View, clear)
                .withDepthAttachment(mainDepthView, OptionalDouble.empty())
                .withRenderArea(new com.mojang.renderpearl.api.commands.RenderPass.RenderArea(0, 0, w, h))
                .build();
        return encoder.createRenderPass(descriptor);
    }

    /**
 * 全屏光照 pass：采样 G-buffer 输出到主颜色。先拷贝主颜色到快照，
 * 让背景像素（sky/实体）在覆盖不到的地方保持原样。
 * 必须在 executeSolid 之后、云 pass 之前（渲染线程）调用。
 */
    public static void renderLighting(com.mojang.blaze3d.pipeline.RenderTarget mainTarget) {
        if (mainTarget == null || mainTarget.getColorTexture() == null
                || mainTarget.getColorTextureView() == null) {
            return;
        }
        // 三个 texel buffer 在 BindGroupLayout 里是**必填** uniform，任何一个未
        // 就绪都会让 pushDescriptors 抛异常（实测 "Missing uniform VoxelField
        // (should be TEXEL_BUFFER)"，整帧崩溃）。它们的创建挂在
        // Halo3D.submit → AdaptLightingUniform.updateFrame 上，那条链任何一环
        // 断了都不该让游戏崩 — 这里退化成"本帧不跑延迟光照"，主颜色保持
        // executeSolid 写下的原样，下一帧资源就绪后自动恢复。
        if (!VoxelOcclusionField.ready() || !LightListBuffer.ready() || !LightTileGrid.ready()) {
            gbufferNeedsClear = true;
            return;
        }
        AdaptRenderProfiler.begin(AdaptRenderProfiler.LIGHTING_PASS);
        ensureResources(mainTarget.width, mainTarget.height);
        ensureSnapshot(mainTarget.width, mainTarget.height);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(mainTarget.getColorTexture(), mainColorSnapshot.getColorTexture(),
                0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
        // 主深度也要快照：本 pass 的颜色附件就是主颜色，同 pass 内既写颜色又
        // 采样主深度属于 attachment 自读（26.2 无校验也无 barrier，
        // VulkanCommandEncoder.memoryBarrier 只在 submitRenderPass 发；字节码
        // 实证），必须拷出来再采样。深度用于判断实体是否画在地形之上。
        if (mainTarget.hasDepth() && mainTarget.getDepthTexture() != null) {
            encoder.copyTextureToTexture(mainTarget.getDepthTexture(), mainDepthSnapshot.getDepthTexture(),
                    0, 0, 0, 0, 0, mainTarget.width, mainTarget.height);
        }

        // ---- 三段式：光照（MRT）→ 共用降噪 → 统一合成 ----
        // 旧实现在光照 pass 里当场 tonemap 并直接写主颜色，导致局部光的降噪
        // 只能在非线性空间做（会把亮部糊成灰）。现代管线统一在线性 HDR 空间
        // 累加，最后一次 tonemap。
        if (DEBUG_MODE > 0) {
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Adapt deferred debug",
                    mainTarget.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(DEBUG_PIPELINE));
                ensureDebugConfig();
                pass.setUniform("DebugConfig", debugConfigBuffer);
                bindGBuffer(pass, false);
                pass.draw(3, 1, 0, 0);
            }
        } else {
            GpuTextureView[] targets =
                    AdaptDirectLighting.lightingTargets(mainTarget.width, mainTarget.height);
            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Adapt deferred lighting")
                    .withColorAttachment(targets[0], Optional.of(CLEAR_ZERO))
                    .withColorAttachment(targets[1], Optional.of(CLEAR_ZERO))
                    .withRenderArea(new com.mojang.renderpearl.api.commands.RenderPass.RenderArea(
                            0, 0, mainTarget.width, mainTarget.height))
                    .build();
            try (RenderPass pass = encoder.createRenderPass(descriptor)) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(LIGHT_PIPELINE));
                AdaptLightingUniform.bind(pass);
                // 太阳方向影：ShadowFrame UBO + shadow map。G-buffer 接管后
                // 地形几何阶段不再算光照，阴影必须在这里接收。
                AdaptShadowMap.bindReceiver(pass);
                // 体素遮挡场：方块光阴影（世界空间，视角无关可缓存）
                VoxelOcclusionField.bind(pass);
                // 增长式光源列表（无上限）+ 可见光列表
                LightListBuffer.bind(pass);
                LightTileGrid.bind(pass);
                bindGBuffer(pass, true);
                pass.draw(3, 1, 0, 0);
            }
            // 所有灯共用一次双边降噪（成本与灯数无关）
            AdaptDirectLighting.denoise(encoder);
            // HDR 场景 + 降噪直接光 → 单次 tonemap → 雾 → 主颜色
            AdaptDirectLighting.composite(encoder, mainTarget.getColorTextureView(),
                    mainColorSnapshot.getColorTextureView(),
                    mainDepthSnapshot.getDepthTextureView());
        }
        // 下一帧的 SOLID pass 必须重新清空 G-buffer；本帧 CUTOUT 已经只做追加。
        gbufferNeedsClear = true;
        logFramePassStats("lighting");
        AdaptRenderProfiler.end(AdaptRenderProfiler.LIGHTING_PASS);
        AdaptRenderProfiler.tickFrame();
    }

    private static void bindGBuffer(RenderPass pass, boolean withDepth) {
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform("GB0Sampler", gb0View, nearest);
        pass.setUniform("GB1Sampler", gb1View, nearest);
        pass.setUniform("GB2Sampler", gb2View, nearest);
        pass.setUniform("GB3Sampler", gb3View, nearest);
        pass.setUniform("GB4Sampler", gb4View, nearest);
        pass.setUniform("GB5Sampler", gb5View, nearest);
        pass.setUniform("MainColorSampler", mainColorSnapshot.getColorTextureView(), nearest);
        if (withDepth) {
            pass.setUniform("MainDepthSampler", mainDepthSnapshot.getDepthTextureView(), nearest);
        }
    }

    static GpuTextureView gb0View() {
        return gb0View;
    }

    static GpuTextureView gb1View() {
        return gb1View;
    }

    static GpuTextureView gb3View() {
        return gb3View;
    }

    static GpuTextureView gb4View() {
        return gb4View;
    }

    public static void close() {
        closeTexture(gb0);
        closeTexture(gb1);
        closeTexture(gb2);
        closeTexture(gb3);
        closeTexture(gb4);
        closeTexture(gb5);
        gb0 = gb1 = gb2 = gb3 = gb4 = gb5 = null;
        gb0View = gb1View = gb2View = gb3View = gb4View = gb5View = null;
        if (mainColorSnapshot != null) {
            mainColorSnapshot.destroyBuffers();
            mainColorSnapshot = null;
        }
        if (mainDepthSnapshot != null) {
            mainDepthSnapshot.destroyBuffers();
            mainDepthSnapshot = null;
        }
        if (debugConfigBuffer != null) {
            debugConfigBuffer.close();
            debugConfigBuffer = null;
        }
        // 注意：这里**不能**关体素场。close 会被 ensureResources 在窗口尺寸
        // 变化时调用，而体素 buffer 已在本帧早些时候由 AdaptLightingUniform
        // .updateFrame → VoxelOcclusionField.update 建好并即将绑定 — 在这里
        // 释放会造成同帧 use-after-free（绑定时拿到 null，shader 缺 uniform）。
        // 体素场的生命周期跟 LevelRenderer，由 ReimaginedCloudPassMixin.close 释放。
        gbufferNeedsClear = true;
        width = height = 0;
    }

    private static void ensureResources(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (gb0 != null && width == w && height == h) {
            return;
        }
        if (gb0 != null) {
            close();
        }
        width = w;
        height = h;
        gbufferNeedsClear = true;
        var device = RenderSystem.getDevice();
        gb0 = device.createTexture(() -> "Adapt GB0 position", USAGE_RT, GpuFormat.RGBA32_FLOAT, w, h, 1, 1);
        gb1 = device.createTexture(() -> "Adapt GB1 normal", USAGE_RT, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        gb2 = device.createTexture(() -> "Adapt GB2 albedo", USAGE_RT, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        gb3 = device.createTexture(() -> "Adapt GB3 fog", USAGE_RT, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        gb4 = device.createTexture(() -> "Adapt GB4 worldpos", USAGE_RT, GpuFormat.RGBA32_FLOAT, w, h, 1, 1);
        gb5 = device.createTexture(() -> "Adapt GB5 lightmap", USAGE_RT, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        gb0View = device.createTextureView(gb0);
        gb1View = device.createTextureView(gb1);
        gb2View = device.createTextureView(gb2);
        gb3View = device.createTextureView(gb3);
        gb4View = device.createTextureView(gb4);
        gb5View = device.createTextureView(gb5);
    }

    private static void ensureSnapshot(int w, int h) {
        if (mainColorSnapshot == null) {
            // 主 target 颜色为 RGBA8_UNORM（与云 pass 快照同源），blit 必须同格式
            mainColorSnapshot = new TextureTarget("adaptclient_gbuffer_main_snapshot", w, h,
                    GpuFormat.RGBA8_UNORM, null);
        } else if (mainColorSnapshot.width != w || mainColorSnapshot.height != h) {
            mainColorSnapshot.resize(w, h);
        }
        if (mainDepthSnapshot == null) {
            // useDepth=true：只用它的深度附件，颜色附件闲置（TextureTarget 不支持
            // 只建深度）。格式与主 target 深度一致由 TextureTarget 内部保证。
            mainDepthSnapshot = new TextureTarget("adaptclient_gbuffer_depth_snapshot", w, h,
                    GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else if (mainDepthSnapshot.width != w || mainDepthSnapshot.height != h) {
            mainDepthSnapshot.resize(w, h);
        }
    }

    private static void ensureDebugConfig() {
        if (debugConfigBuffer == null) {
            ByteBuffer data = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            data.putInt(0, DEBUG_MODE);
            debugConfigBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Adapt G-buffer debug config",
                    com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_DST
                            | com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_UNIFORM,
                    16);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(debugConfigBuffer.slice(), data);
        }
    }

    private static void closeTexture(GpuTexture texture) {
        if (texture != null && !texture.isClosed()) {
            texture.close();
        }
    }
}
