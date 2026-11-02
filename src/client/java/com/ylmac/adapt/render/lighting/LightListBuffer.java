package com.ylmac.adapt.render.lighting;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * 增长式光源列表缓冲：把点光源从定长 UBO 数组搬到 texel buffer，去掉硬上限。
 * <p>
 * <b>为什么必须换。</b>旧实现把光源写进 {@code AdaptLighting} UBO 的
 * {@code vec4 HaloLightPos[32]} / {@code HaloLightColor[32]}，是**定长数组**，
 * 于是 32 成了不可逾越的墙。而且截断发生在两处：
 * <ul>
 * <li>{@code Halo3D:188} 用 {@code MAX_HALO_LIGHTS - 1 = 31} 当
 * {@code submitNearby} 的 limit</li>
 * <li>{@code BlockLightSourceCache:126} 按 score 排序后只取前 31 个</li>
 * </ul>
 * 用户实测："好像还是有上限"、"有些光在我的显示范围内没法显示" — 就是这个。
 * <p>
 * <b>为什么 texel buffer 可行。</b>代理的字节码审计确认 Blaze3D 对 texel
 * buffer 的元素数<b>完全不做校验</b>（{@code maxTexelBufferElements} 在整个
 * jar 里 0 命中，{@code DeviceLimits} 是 6 字段 record 不含该项），硬件上限
 * 典型 2²⁷ — 对光源数量而言等于无限。这与 Caustica 的
 * {@code RtLightCollector} 思路一致：光列表放可增长 buffer，不放定长数组。
 * <p>
 * <b>布局</b>（每光源 2 条 {@code RGBA32_FLOAT} texel = 32 字节）：
 * <pre>
 * texel 2i: xyz = 视空间位置, w = 半径
 * texel 2i + 1: rgb = 颜色, a = 强度
 * </pre>
 * GLSL 侧用 {@code samplerBuffer} + {@code texelFetch(LightList, 2*i)} 取。
 * 光源数量由 {@code AdaptLighting.HaloLightCount} 提供（UBO 里的标量，不受
 * 数组长度限制）。
 * <p>
 * 容量按需增长（翻倍），只在不够时重建 buffer；每帧只 {@code writeToBuffer}
 * 实际用到的字节数。
 */
public final class LightListBuffer {

    /** 每光源占用的 texel 数（位置 + 颜色）。 */
    private static final int TEXELS_PER_LIGHT = 2;
    /** RGBA32_FLOAT = 16 字节/texel。 */
    private static final int BYTES_PER_TEXEL = 16;
    private static final int BYTES_PER_LIGHT = TEXELS_PER_LIGHT * BYTES_PER_TEXEL;

    /** 初始容量。超过就翻倍，不再截断。 */
    private static final int INITIAL_CAPACITY = 256;
    /**
 * 安全上限：仅防御性质（一个 buffer 4096 光源 = 128 KiB）。真实瓶颈是
 * 光照 pass 里逐光源的体素步进开销，不是 buffer 容量。
 */
    private static final int MAX_CAPACITY = 4096;

    private static GpuBuffer buffer;
    private static ByteBuffer staging;
    private static int capacity;
    private static int liveCount;

    private LightListBuffer() {
    }

    /** 本帧实际写入的光源数（供 UBO 的 HaloLightCount 使用）。 */
    public static int liveCount() {
        return liveCount;
    }

    /**
 * 把视空间光源列表写入 buffer。容量不足时自动翻倍重建。
 * 必须在渲染线程调用。
 */
    public static void upload(List<AdaptLightSources.HaloSource> lights) {
        if (lights.size() > MAX_CAPACITY) {
            org.slf4j.LoggerFactory.getLogger("adapt-perf")
                    .warn("LightList overflow: {} lights, cap {}", lights.size(), MAX_CAPACITY);
        }
        int count = Math.min(lights.size(), MAX_CAPACITY);
        liveCount = count;
        ensureCapacity(Math.max(count, INITIAL_CAPACITY));
        if (buffer == null || staging == null) {
            liveCount = 0;
            return;
        }

        staging.clear();
        for (int i = 0; i < count; i++) {
            AdaptLightSources.HaloSource light = lights.get(i);
            int base = i * BYTES_PER_LIGHT;
            staging.putFloat(base, light.viewX());
            staging.putFloat(base + 4, light.viewY());
            staging.putFloat(base + 8, light.viewZ());
            staging.putFloat(base + 12, light.radius());
            staging.putFloat(base + 16, light.red());
            staging.putFloat(base + 20, light.green());
            staging.putFloat(base + 24, light.blue());
            staging.putFloat(base + 28, light.strength());
        }
        // 只传实际用到的部分。注意 texel buffer 绑定时必须是整 buffer
        // （VulkanRenderPass.pushDescriptors off.173 拒绝 slice），但**写入**
        // 可以只写前面一段 — 后面的陈旧数据不会被 shader 读到，因为
        // HaloLightCount 限定了循环上界。
        staging.limit(Math.max(count, 1) * BYTES_PER_LIGHT);
        RenderSystem.getDevice().createCommandEncoder()
                .writeToBuffer(buffer.slice(0, staging.limit()), staging);
    }

    private static void ensureCapacity(int required) {
        if (buffer != null && capacity >= required) {
            return;
        }
        int newCapacity = Math.max(capacity == 0 ? INITIAL_CAPACITY : capacity, required);
        while (newCapacity < required && newCapacity < MAX_CAPACITY) {
            newCapacity *= 2;
        }
        newCapacity = Math.min(newCapacity, MAX_CAPACITY);
        if (buffer != null) {
            buffer.close();
        }
        capacity = newCapacity;
        staging = ByteBuffer.allocateDirect(capacity * BYTES_PER_LIGHT).order(ByteOrder.nativeOrder());
        buffer = RenderSystem.getDevice().createBuffer(
                () -> "Adapt light list (" + capacity + " lights)",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
                capacity * BYTES_PER_LIGHT);
    }

    /** texel buffer 必须整 buffer 绑定（slice 会被 Vulkan 后端拒绝）。 */
    public static void bind(RenderPass pass) {
        if (buffer == null) {
            return;
        }
        pass.setUniform("LightList", buffer);
    }

    /**
 * 资源是否就绪。延迟光照 pass 的 BindGroupLayout 声明了本 uniform，
 * 未就绪时开 pass 会在 pushDescriptors 抛
 * "Missing uniform LightList (should be TEXEL_BUFFER)" — 调用方必须先查。
 */
    public static boolean ready() {
        return buffer != null;
    }

    /** 供 BindGroupLayout 声明用。 */
    public static GpuFormat format() {
        return GpuFormat.RGBA32_FLOAT;
    }

    public static void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        staging = null;
        capacity = 0;
        liveCount = 0;
    }
}
