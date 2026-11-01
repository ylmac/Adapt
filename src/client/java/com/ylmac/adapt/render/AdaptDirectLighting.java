package com.ylmac.adapt.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * MegaLights 式随机直接光的降噪与合成阶段。
 * <p>
 * <b>为什么要分离出这一层。</b>旧管线在光照 pass 里对每盏灯各做一次体素
 * 阴影步进，成本 O(灯数 × DDA 步数)，并且当场 tonemap 输出主颜色。Epic 在
 * MegaLights 里算过：一个像素落在 50 盏灯范围内时通常只有 15 盏有贡献，
 * 其中 1 盏占 80% 能量 —— 为每盏灯付一遍阴影是纯浪费。
 * <p>
 * 新管线按 MegaLights 的范式拆成三段：
 * <ol>
 *   <li><b>光照 pass</b>（MRT 两目标）：目标 0 = 太阳/环境 HDR 场景色，
 *       目标 1 = 局部光的随机采样结果（有噪点）。局部光只发固定条数光线，
 *       成本与灯数解耦。</li>
 *   <li><b>降噪 pass</b>（本类，横+竖两趟）：所有灯共用一次双边滤波，
 *       由 G-buffer 的视空间位置/法线做引导。这是"共用降噪"的实现 ——
 *       成本不随灯数增长。</li>
 *   <li><b>合成 pass</b>（本类）：HDR 场景 + 降噪后的直接光 → 统一
 *       tonemap → 雾 → 主颜色。统一 tonemap 是刻意的：各 pass 各自
 *       tonemap 会让色彩空间不一致。</li>
 * </ol>
 * AC Shadows 的"降噪几乎占整个 pass 的一半"是这条路线的已知代价，
 * 但它换来的是成本与灯数无关。
 */
public final class AdaptDirectLighting {

    private static final int USAGE_RT = GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT;
    /**
     * 直接光 A 还要作为 copy 的**源**（A → history），必须额外声明
     * USAGE_COPY_SRC —— 26.2 的 CommandEncoder 会校验
     * ("Texture needs USAGE_COPY_SRC to be a source for a copy"，实测崩溃)。
     */
    private static final int USAGE_RT_COPYABLE = USAGE_RT | GpuTexture.USAGE_COPY_SRC;

    private static final Identifier DENOISE_PIPELINE_ID =
            Identifier.parse("adaptclient:direct_denoise");
    private static final Identifier DENOISE_SHADER_ID =
            Identifier.parse("adaptclient:post/adapt_direct_denoise");
    private static final Identifier COMPOSITE_PIPELINE_ID =
            Identifier.parse("adaptclient:direct_composite");
    private static final Identifier COMPOSITE_SHADER_ID =
            Identifier.parse("adaptclient:post/adapt_composite");

    private static final BindGroupLayout DENOISE_LAYOUT = BindGroupLayout.builder()
            .withUniform("DirectSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB0Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB1Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB4Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("HistorySampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("DenoiseConfig", UniformType.UNIFORM_BUFFER)
            .withUniform("AdaptLighting", UniformType.UNIFORM_BUFFER)
            .build();

    private static final BindGroupLayout COMPOSITE_LAYOUT = BindGroupLayout.builder()
            .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("DirectSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB0Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("GB3Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("MainColorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("MainDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("AdaptLighting", UniformType.UNIFORM_BUFFER)
            .build();

    private static final RenderPipeline DENOISE_PIPELINE = RenderPipeline.builder()
            .withLocation(DENOISE_PIPELINE_ID)
            .withVertexShader(Identifier.parse("minecraft:core/screenquad"))
            .withFragmentShader(DENOISE_SHADER_ID)
            .withBindGroupLayout(DENOISE_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(),
                    GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
            .build();

    private static final RenderPipeline COMPOSITE_PIPELINE = RenderPipeline.builder()
            .withLocation(COMPOSITE_PIPELINE_ID)
            .withVertexShader(Identifier.parse("minecraft:core/screenquad"))
            .withFragmentShader(COMPOSITE_SHADER_ID)
            .withBindGroupLayout(COMPOSITE_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(),
                    GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
            .build();

    /** 统一 HDR 场景色（太阳 + 环境 + albedo），未 tonemap。 */
    private static GpuTexture sceneHdr;
    private static GpuTextureView sceneHdrView;
    /** 随机采样的局部光（有噪点）与降噪 ping-pong。 */
    private static GpuTexture directA;
    private static GpuTextureView directAView;
    private static GpuTexture directB;
    private static GpuTextureView directBView;
    /** 上一帧的降噪结果（A 通道存视距，供时间累积判据）。 */
    private static GpuTexture history;
    private static GpuTextureView historyView;
    /** 横/竖两趟的方向常量（各自一个 buffer，避免同帧改写同一 UBO）。 */
    private static GpuBuffer denoiseHorizontal;
    private static GpuBuffer denoiseVertical;
    private static int width;
    private static int height;

    private AdaptDirectLighting() {
    }

    public static GpuTextureView sceneHdrView() {
        return sceneHdrView;
    }

    public static GpuTextureView directRawView() {
        return directAView;
    }

    /** 光照 pass 的两个 MRT 附件视图（场景 HDR + 直接光原始采样）。 */
    public static GpuTextureView[] lightingTargets(int w, int h) {
        ensureResources(w, h);
        return new GpuTextureView[]{sceneHdrView, directAView};
    }

    /**
     * 降噪：横一趟 A→B（纯空间），竖一趟 B→A（空间 + 时间累积）。
     * 所有灯共用这一次滤波 —— 成本与灯数无关，这是 MegaLights「共用降噪」
     * 的核心。竖趟额外读上一帧的历史（history）做指数移动平均：1 SPP 的
     * 空间滤波压不掉噪点（实测"噪点有点多，只有一盏灯时很少"），时间累积
     * 让有效采样数随帧数上升。
     */
    public static void denoise(CommandEncoder encoder) {
        if (directAView == null || directBView == null || historyView == null) {
            return;
        }
        ensureDenoiseConfig();
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        // 横趟：A → B，不做时间累积
        runDenoisePass(encoder, "Adapt direct denoise H", directAView, directBView,
                historyView, denoiseHorizontal, nearest);
        // 竖趟：B → A，混入历史
        runDenoisePass(encoder, "Adapt direct denoise V", directBView, directAView,
                historyView, denoiseVertical, nearest);
        // A → history，供下一帧使用（不能直接把 history 当 A：同 pass 读写
        // 同一附件在 26.2 无 barrier 保障）
        encoder.copyTextureToTexture(directA, history, 0, 0, 0, 0, 0, width, height);
    }

    private static void runDenoisePass(CommandEncoder encoder, String label,
                                      GpuTextureView source, GpuTextureView target,
                                      GpuTextureView historySource,
                                      GpuBuffer config,
                                      com.mojang.renderpearl.api.textures.GpuSampler sampler) {
        try (RenderPass pass = encoder.createRenderPass(() -> label, target,
                Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(DENOISE_PIPELINE));
            pass.setUniform("DenoiseConfig", config);
            AdaptLightingUniform.bind(pass);
            pass.setUniform("DirectSampler", source, sampler);
            pass.setUniform("GB0Sampler", DeferredGBuffer.gb0View(), sampler);
            pass.setUniform("GB1Sampler", DeferredGBuffer.gb1View(), sampler);
            pass.setUniform("GB4Sampler", DeferredGBuffer.gb4View(), sampler);
            pass.setUniform("HistorySampler", historySource, sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * 合成：HDR 场景 + 降噪直接光 → 统一 tonemap → 雾 → 主颜色。
     * 背景像素（sky/实体）保留主颜色快照。
     */
    public static void composite(CommandEncoder encoder, GpuTextureView mainColorTarget,
                                 GpuTextureView mainColorSnapshot,
                                 GpuTextureView mainDepthSnapshot) {
        if (sceneHdrView == null || directAView == null) {
            return;
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "Adapt composite",
                mainColorTarget, Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(COMPOSITE_PIPELINE));
            AdaptLightingUniform.bind(pass);
            pass.setUniform("SceneSampler", sceneHdrView, nearest);
            pass.setUniform("DirectSampler", directAView, nearest);
            pass.setUniform("GB0Sampler", DeferredGBuffer.gb0View(), nearest);
            pass.setUniform("GB3Sampler", DeferredGBuffer.gb3View(), nearest);
            pass.setUniform("MainColorSampler", mainColorSnapshot, nearest);
            pass.setUniform("MainDepthSampler", mainDepthSnapshot, nearest);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static void ensureResources(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (sceneHdr != null && width == w && height == h) {
            return;
        }
        close();
        width = w;
        height = h;
        var device = RenderSystem.getDevice();
        sceneHdr = device.createTexture(() -> "Adapt scene HDR", USAGE_RT,
                GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        directA = device.createTexture(() -> "Adapt direct light A", USAGE_RT_COPYABLE,
                GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        directB = device.createTexture(() -> "Adapt direct light B", USAGE_RT,
                GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        history = device.createTexture(() -> "Adapt direct light history", USAGE_RT,
                GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        sceneHdrView = device.createTextureView(sceneHdr);
        directAView = device.createTextureView(directA);
        directBView = device.createTextureView(directB);
        historyView = device.createTextureView(history);
        // history 必须清零：第一帧读未初始化显存，NaN 一旦进 EMA 会永久
        // 传播（实测"地板有时会莫名变成绿色"）。A 通道=0 也让首帧的
        // historyDepth>0 判据自然失效，走"丢弃历史"分支。
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorTexture(history, new org.joml.Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
    }

    private static void ensureDenoiseConfig() {
        if (denoiseHorizontal != null) {
            return;
        }
        // 横趟不做时间累积（还要再滤一次），竖趟才混历史
        denoiseHorizontal = createConfig("Adapt denoise H", 1.0f, 0.0f, 0.0f);
        denoiseVertical = createConfig("Adapt denoise V", 0.0f, 1.0f, 1.0f);
    }

    private static GpuBuffer createConfig(String label, float dirX, float dirY, float temporal) {
        ByteBuffer data = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        data.putFloat(0, dirX);
        data.putFloat(4, dirY);
        data.putFloat(8, width <= 0 ? 1.0f : 1.0f / width);
        data.putFloat(12, height <= 0 ? 1.0f : 1.0f / height);
        data.putFloat(16, temporal);
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> label,
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, 32);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data);
        return buffer;
    }

    public static void close() {
        closeTexture(sceneHdr);
        closeTexture(directA);
        closeTexture(directB);
        closeTexture(history);
        sceneHdr = directA = directB = history = null;
        sceneHdrView = directAView = directBView = historyView = null;
        if (denoiseHorizontal != null) {
            denoiseHorizontal.close();
            denoiseHorizontal = null;
        }
        if (denoiseVertical != null) {
            denoiseVertical.close();
            denoiseVertical = null;
        }
        width = height = 0;
    }

    private static void closeTexture(GpuTexture texture) {
        if (texture != null && !texture.isClosed()) {
            texture.close();
        }
    }
}
