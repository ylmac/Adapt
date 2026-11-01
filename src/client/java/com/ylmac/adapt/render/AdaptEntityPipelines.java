package com.ylmac.adapt.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Beryl 式实体/物品管线替换。
 * <p>
 * 重定向清单与 Beryl RenderingPipeline#setupEntityShader 一一对应
 * （14 条 entity 管线 + 2 条 item 管线）：每条管线用
 * RenderPipeline.builder(原管线 snippet…) 重建——snippet 保留了原管线的
 * 顶点格式/混合/深度状态与全部 define——仅追加 AdaptLighting bind group
 * layout 并把 vertex/fragment shader 换成 adapt_entity/adapt_item。
 * <p>
 * 编译时由 AdaptPipelineMixin 在 device.getOrCompilePipeline 入口替换，
 * GL 与 Vulkan 两个后端共用同一替换表。
 */
public final class AdaptEntityPipelines {

    /** AdaptLighting UBO bind group layout（std140，UniformType.UNIFORM_BUFFER）。 */
    public static final BindGroupLayout ADAPT_LIGHTING_LAYOUT = BindGroupLayout.builder()
            .withUniform("AdaptLighting", UniformType.UNIFORM_BUFFER)
            .build();
    /** Shadow receiver: shadow map + sky-occlusion samplers + frame UBO. */
    public static final BindGroupLayout ADAPT_SHADOW_LAYOUT = BindGroupLayout.builder()
            .withUniform("ShadowMapSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SkyOcclusionSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("ShadowFrame", UniformType.UNIFORM_BUFFER)
            .build();
    /** Shadow caster: frame UBO only. */
    public static final BindGroupLayout SHADOW_FRAME_LAYOUT = BindGroupLayout.builder()
            .withUniform("ShadowFrame", UniformType.UNIFORM_BUFFER)
            .build();

    private static final Map<RenderPipeline, RenderPipeline> REDIRECTS = new ConcurrentHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> SHADOW_REDIRECTS = new ConcurrentHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> SODIUM_TERRAIN = new ConcurrentHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> SODIUM_TERRAIN_GBUFFER = new ConcurrentHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> SODIUM_TERRAIN_TRANSLUCENT = new ConcurrentHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> SODIUM_SHADOW = new ConcurrentHashMap<>();
    private static volatile boolean built;

    private AdaptEntityPipelines() {
    }

    public static RenderPipeline redirect(RenderPipeline original) {
        // 光影总开关关闭 → 原样返回，实体/物品/天空/云全部走原版管线。
        if (!AdaptRenderToggle.isWorldEffectsEnabled()) {
            return original;
        }
        buildAll();
        if (AdaptShadowMap.isRendering()) {
            // Sodium 地形阴影已经由 redirectSodiumTerrain → cloneSodiumShadow
            // 换成 adaptclient:shadow/...。RenderPass.setPipeline 的全局 mixin
            // 会再进这里一次。若再包一层，path 变成 shadow/shadow/pipeline/...，
            // 并且把原地形 layout（含 DynamicTransforms）拷进 sodium_shadow
            // —— 用户日志实证编译失败。已经是 caster 的管线必须原样返回。
            if (isShadowPipeline(original)) {
                return original;
            }
            // 按需生成 caster 副本：不再依赖 buildAll 里的 putShadow 枚举名单。
            // 名单漏项的实测后果——玩家/生物本体走 model.renderType(texture)
            // （LivingEntityRenderer.getRenderType 字节码），落到
            // ENTITY_TRANSLUCENT_CULL / ENTITY_CUTOUT_DISSOLVE / ARMOR_TRANSLUCENT
            // 等未注册管线 → 本体不进 shadow map；而手持物品走 ITEM_CUTOUT/
            // ITEM_TRANSLUCENT（在名单里）→ 只有手上物品有影子。
            // computeIfAbsent 让任何实体管线自动获得 caster，结构上不可能再漏。
            return SHADOW_REDIRECTS.computeIfAbsent(original,
                    p -> buildShadowVariant(p, isTerrainPipeline(p)));
        }
        RenderPipeline replaced = REDIRECTS.get(original);
        return replaced != null ? replaced : original;
    }

    /**
     * 地形管线判定：Sodium 地形走 redirectSodiumTerrain 独立路径，这里只需
     * 区分原版 SOLID/CUTOUT/TRANSLUCENT_TERRAIN（顶点格式含 block 属性，
     * caster 用 adapt_terrain_shadow）与实体（adapt_entity_shadow）。
     */
    private static boolean isTerrainPipeline(RenderPipeline pipeline) {
        return pipeline == RenderPipelines.SOLID_TERRAIN
                || pipeline == RenderPipelines.CUTOUT_TERRAIN
                || pipeline == RenderPipelines.TRANSLUCENT_TERRAIN;
    }

    private static boolean isShadowPipeline(RenderPipeline pipeline) {
        if (pipeline == null || pipeline.getLocation() == null) {
            return false;
        }
        String ns = pipeline.getLocation().getNamespace();
        String path = pipeline.getLocation().getPath();
        return path.startsWith("shadow/") || "adaptclient".equals(ns) && path.contains("shadow");
    }

    /**
     * Sodium 地形管线替换（sodium:blocks/block_layer_opaque 的 Adapt 副本）。
     * Sodium 不使用原版 TERRAIN 管线，需独立映射。
     * <p>
     * 半透明分支不能用 built.getLocation().getPath() 判断：Sodium 的
     * createShader 把 path 硬编码成 blocks/block_layer_opaque，不随 pass 变。
     * 但 createShader 对 translucent pass 设置了 BlendFunction.TRANSLUCENT
     * color target（ShaderChunkRenderer.createShader:68-72）—— 用
     * getColorTargetStates()[0].blendFunction().isPresent() 判断（blendFunction
     * 是 Optional，!= null 恒真会把不透明误判为半透明）。
     */
    public static RenderPipeline redirectSodiumTerrain(RenderPipeline built, boolean translucent) {
        // 光影总开关关闭 → 原样返回，地形走 Sodium 原管线（不写 G-buffer）。
        if (!AdaptRenderToggle.isWorldEffectsEnabled()) {
            return built;
        }
        if (AdaptShadowMap.isRendering()) {
            return SODIUM_SHADOW.computeIfAbsent(built, AdaptEntityPipelines::cloneSodiumShadow);
        }
        if (translucent) {
            return SODIUM_TERRAIN_TRANSLUCENT.computeIfAbsent(built, AdaptEntityPipelines::cloneSodiumTerrainTranslucent);
        }
        // SOLID 与 CUTOUT 都是不透明（translucent=false），是延迟渲染的几何阶段：
        // 全部写 G-buffer 四目标，光照推迟到全屏 light pass。不依赖任何外部 flag
        // （此前依赖 ChunkSectionsToRenderMixin 的 HEAD 注入设 flag，但 Sodium 的
        // sodium$renderGroup 是 cancellable HEAD 且会同步画完全部地形，与我们的
        // HEAD 注入顺序竞争——日志实证 opaqueGroups=0，flag 从未成立，地形全走
        // 原管线。现在用管线自身的 blend 状态判定，与注入顺序完全解耦）。
        return SODIUM_TERRAIN_GBUFFER.computeIfAbsent(built, AdaptEntityPipelines::cloneSodiumTerrainGBuffer);
    }

    public static boolean isAdapt(RenderPipeline pipeline) {
        return pipeline.getLocation() != null
                && pipeline.getLocation().getNamespace().equals("adaptclient");
    }

    private static void buildAll() {
        if (built) {
            return;
        }
        synchronized (AdaptEntityPipelines.class) {
            if (built) {
                return;
            }
            // ---- Beryl setupEntityShader 重定向清单 ----
            put(RenderPipelines.ENTITY_SOLID);
            put(RenderPipelines.ENTITY_TRANSLUCENT);
            put(RenderPipelines.ENTITY_CUTOUT);
            put(RenderPipelines.ENTITY_CUTOUT_CULL);
            put(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET);
            put(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD);
            put(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE);
            put(RenderPipelines.EYES);
            put(RenderPipelines.ARMOR_CUTOUT_NO_CULL);
            put(RenderPipelines.ITEM_TRANSLUCENT);
            put(RenderPipelines.ITEM_CUTOUT);
            put(RenderPipelines.BANNER_PATTERN);
            put(RenderPipelines.END_CRYSTAL_BEAM);
            put(RenderPipelines.BREEZE_WIND);
            putShadow(RenderPipelines.ENTITY_SOLID, false);
            putShadow(RenderPipelines.ENTITY_TRANSLUCENT, false);
            putShadow(RenderPipelines.ENTITY_CUTOUT, false);
            putShadow(RenderPipelines.ENTITY_CUTOUT_CULL, false);
            putShadow(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, false);
            putShadow(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, false);
            putShadow(RenderPipelines.ARMOR_CUTOUT_NO_CULL, false);
            putShadow(RenderPipelines.ITEM_TRANSLUCENT, false);
            putShadow(RenderPipelines.ITEM_CUTOUT, false);
            putShadow(RenderPipelines.SOLID_TERRAIN, true);
            putShadow(RenderPipelines.CUTOUT_TERRAIN, true);
            // ---- 地形（Beryl terrain shader 等价物）----
            put(RenderPipelines.SOLID_TERRAIN);
            put(RenderPipelines.CUTOUT_TERRAIN);
            put(RenderPipelines.TRANSLUCENT_TERRAIN);
            // ---- 天空盒（Complementary Reimagined 移植）----
            putSky(RenderPipelines.SKY, "adaptclient:core/adapt_sky");
            putTransparent(RenderPipelines.STARS, "core/stars");
            putTransparent(RenderPipelines.SUNRISE_SUNSET, "core/position_color");
            putTransparent(RenderPipelines.CELESTIAL, "core/position_tex");
            putTransparent(RenderPipelines.CLOUDS, "core/rendertype_clouds");
            putTransparent(RenderPipelines.FLAT_CLOUDS, "core/rendertype_clouds");
            built = true;
        }
    }

    /**
     * 用原管线的 snippet 重建一条 Adapt 管线：snippet 保留 define/混合/深度/
     * 顶点格式/拓扑，替换 shader 路径并追加 AdaptLighting 布局。
     */
    private static void put(RenderPipeline original) {
        if (original == null || REDIRECTS.containsKey(original)) {
            return;
        }
        String name = original.getLocation().getPath();
        // String 重载走 withDefaultNamespace（硬编码 minecraft: 前缀、不解析命名空间），
        // 所有命名空间路径必须传 Identifier.parse 结果
        boolean terrain = name.contains("terrain") || name.equals("wireframe");
        net.minecraft.resources.Identifier shader = net.minecraft.resources.Identifier.parse(
                terrain ? "adaptclient:core/adapt_terrain" : "adaptclient:core/adapt_entity");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(net.minecraft.resources.Identifier.parse("adaptclient:" + name))
                .withVertexShader(shader)
                .withFragmentShader(shader);

        // 继承原管线全部静态状态（define/blend/depth/vertex format/topology/cull）
        for (String define : original.getShaderDefines().flags()) {
            builder.withShaderDefine(define);
        }
        original.getShaderDefines().values().forEach((key, value) -> {
            try {
                builder.withShaderDefine(key, Integer.parseInt(value));
            } catch (NumberFormatException first) {
                try {
                    builder.withShaderDefine(key, Float.parseFloat(value));
                } catch (NumberFormatException second) {
                    // 原版 define 值只有数字或 flag；无法表达时忽略
                }
            }
        });

        // Bind group layouts：原布局 + AdaptLighting + Shadow（保持顺序，追加在尾部）
        List<BindGroupLayout> layouts = new ArrayList<>(original.getBindGroupLayouts());
        layouts.add(ADAPT_LIGHTING_LAYOUT);
        layouts.add(ADAPT_SHADOW_LAYOUT);
        for (BindGroupLayout layout : layouts) {
            builder.withBindGroupLayout(layout);
        }

        // 颜色目标（blend 函数 + 格式 + 写掩码）；未使用的槽位保持空
        var targetStates = original.getColorTargetStates();
        for (int i = 0; i < targetStates.size(); i++) {
            var state = targetStates.get(i);
            if (state != null) {
                builder.withColorTargetState(i, state);
            } else if (i > 0) {
                builder.withUnusedColorTargetState(i);
            }
        }

        builder.withDepthStencilState(original.getDepthStencilState());
        builder.withPolygonMode(original.getPolygonMode());
        builder.withCull(original.isCull());
        var formats = original.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            if (format != null) {
                builder.withVertexBinding(i, format);
            }
        }
        builder.withPrimitiveTopology(original.getPrimitiveTopology());

        REDIRECTS.put(original, builder.build());
    }

    /** Build a depth caster pipeline (Beryl shadow.vsh chain, verified by ShadowSimVk). */
    private static void putShadow(RenderPipeline original, boolean terrain) {
        if (original == null || SHADOW_REDIRECTS.containsKey(original)) {
            return;
        }
        SHADOW_REDIRECTS.put(original, buildShadowVariant(original, terrain));
    }

    /** 构造一个管线的 shadow caster 副本（深度 only，写 shadow map）。 */
    private static RenderPipeline buildShadowVariant(RenderPipeline original, boolean terrain) {
        net.minecraft.resources.Identifier shader = net.minecraft.resources.Identifier.parse(
                terrain ? "adaptclient:core/adapt_terrain_shadow" : "adaptclient:core/adapt_entity_shadow");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(net.minecraft.resources.Identifier.parse(
                        "adaptclient:shadow/" + original.getLocation().getPath()))
                .withVertexShader(shader)
                .withFragmentShader(net.minecraft.resources.Identifier.parse("adaptclient:core/adapt_shadow"));
        for (String define : original.getShaderDefines().flags()) {
            builder.withShaderDefine(define);
        }
        original.getShaderDefines().values().forEach((key, value) -> {
            try {
                builder.withShaderDefine(key, Integer.parseInt(value));
            } catch (NumberFormatException first) {
                try {
                    builder.withShaderDefine(key, Float.parseFloat(value));
                } catch (NumberFormatException second) {
                    // The vanilla pipelines use only numeric values and flags.
                }
            }
        });
        for (BindGroupLayout layout : original.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        builder.withBindGroupLayout(SHADOW_FRAME_LAYOUT)
                .withColorTargetState(new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .withCull(original.isCull());
        var formats = original.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            if (format != null) {
                builder.withVertexBinding(i, format);
            }
        }
        builder.withPrimitiveTopology(original.getPrimitiveTopology());
        return builder.build();
    }

    /** Sodium compressed terrain caster variant (sodium_shadow, verified by ShadowSimVk). */
    private static RenderPipeline cloneSodiumShadow(RenderPipeline original) {
        String name = original.getLocation().getPath();
        net.minecraft.resources.Identifier shader = net.minecraft.resources.Identifier.parse(
                "adaptclient:core/sodium_shadow");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                // Sodium's Vulkan pipeline mixin recognizes this namespace for push constants.
                // 必须用 adaptclient 命名空间。写成 sodium:shadow/ 时 Sodium 的
                // Vulkan mixin 会按 sodium 地形管线注入 DynamicTransforms，而
                // sodium_shadow.vsh 没有这个 uniform →
                // "Unable to find shader defined uniform (DynamicTransforms)"
                // → Pipeline is not valid → 方向影/天光图整帧失败（用户日志实证）。
                // 必须 sodium: 命名空间。Sodium 的 Vulkan mixin 只对 sodium:
                // 管线把 push constant 和 u_Globals 分到不同 Metal buffer 槽。
                // 写成 adaptclient:shadow/ 后 PC 和 u_Globals 都落到 buffer(0)：
                // "cannot reserve 'buffer' resource location at index 0"
                // （本轮日志实证，阴影地形每帧失败，shadowTerrain≈38ms）。
                // DynamicTransforms 仍要跳过：sodium_shadow.vsh 没有这个 UBO。
                .withLocation(net.minecraft.resources.Identifier.parse("sodium:shadow/" + name))
                .withVertexShader(shader)
                .withFragmentShader(shader);
        for (String define : original.getShaderDefines().flags()) {
            builder.withShaderDefine(define);
        }
        original.getShaderDefines().values().forEach((key, value) -> {
            try {
                builder.withShaderDefine(key, Integer.parseInt(value));
            } catch (NumberFormatException first) {
                try {
                    builder.withShaderDefine(key, Float.parseFloat(value));
                } catch (NumberFormatException ignored) {
                    // Sodium's pipeline defines are numeric or flags.
                }
            }
        });
        copyLayoutsSkipping(original, builder, "DynamicTransforms");
        builder.withBindGroupLayout(SHADOW_FRAME_LAYOUT)
                .withColorTargetState(new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .withCull(original.isCull());
        var formats = original.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            if (format != null) {
                builder.withVertexBinding(i, format);
            }
        }
        builder.withPrimitiveTopology(original.getPrimitiveTopology());
        return builder.build();
    }

    /**
     * 天空盒管线：换 adapt_sky shader（Complementary Reimagined 天空），
     * 其余状态（POSITION 顶点格式/TRIANGLE_FAN/无混合）保持原样。
     */
    private static void putSky(RenderPipeline original, String vertexFragmentShader) {
        if (original == null || REDIRECTS.containsKey(original)) {
            return;
        }
        net.minecraft.resources.Identifier shader = net.minecraft.resources.Identifier.parse(vertexFragmentShader);
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(net.minecraft.resources.Identifier.parse(
                        "adaptclient:" + original.getLocation().getPath()))
                .withVertexShader(shader)
                .withFragmentShader(shader);
        for (BindGroupLayout layout : original.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        builder.withBindGroupLayout(ADAPT_LIGHTING_LAYOUT); // adapt_sky 声明 AdaptLighting UBO
        layoutsFor(builder, original);
        withNullableDepthStencil(builder, original);
        builder.withPolygonMode(original.getPolygonMode());
        builder.withCull(original.isCull());
        var formats = original.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            if (format != null) {
                builder.withVertexBinding(i, format);
            }
        }
        builder.withPrimitiveTopology(original.getPrimitiveTopology());
        REDIRECTS.put(original, builder.build());
    }

    /**
     * 透明占位管线：原 shader 换为 adapt_transparent（全透明输出），
     * 保留原管线的混合模式——OVERLAY/TRANSLUCENT 下输出透明即无操作。
     */
    private static void putTransparent(RenderPipeline original, String vanillaShaderPath) {
        if (original == null || REDIRECTS.containsKey(original)) {
            return;
        }
        net.minecraft.resources.Identifier vsh = net.minecraft.resources.Identifier.parse(
                "minecraft:" + vanillaShaderPath);
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(net.minecraft.resources.Identifier.parse(
                        "adaptclient:" + original.getLocation().getPath()))
                .withVertexShader(vsh)
                .withFragmentShader(net.minecraft.resources.Identifier.parse("adaptclient:core/adapt_transparent"));
        for (String define : original.getShaderDefines().flags()) {
            builder.withShaderDefine(define);
        }
        original.getShaderDefines().values().forEach((key, value) -> {
            try {
                builder.withShaderDefine(key, Integer.parseInt(value));
            } catch (NumberFormatException first) {
                try {
                    builder.withShaderDefine(key, Float.parseFloat(value));
                } catch (NumberFormatException second) {
                    // 无法表达的 define 值忽略
                }
            }
        });
        for (BindGroupLayout layout : original.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        layoutsFor(builder, original);
        withNullableDepthStencil(builder, original);
        builder.withPolygonMode(original.getPolygonMode());
        builder.withCull(original.isCull());
        var formats = original.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            if (format != null) {
                builder.withVertexBinding(i, format);
            }
        }
        builder.withPrimitiveTopology(original.getPrimitiveTopology());
        REDIRECTS.put(original, builder.build());
    }

    /**
     * 拷贝原管线 bind group，但丢掉 shader 里没有的 uniform。
     * sodium_shadow 没有 DynamicTransforms；整份拷贝会让编译器要求这个 UBO。
     */
    private static void copyLayoutsSkipping(RenderPipeline original, RenderPipeline.Builder builder,
                                            String skipUniform) {
        for (BindGroupLayout layout : original.getBindGroupLayouts()) {
            boolean skip = false;
            for (BindGroupLayout.UniformDescription uniform : layout.uniforms()) {
                if (skipUniform.equals(uniform.name())) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                builder.withBindGroupLayout(layout);
            }
        }
    }

    /**
     * withDepthStencilState 内部 Optional.of 不可接受 null（SKY/CELESTIAL 等
     * 天空管线默认无深度状态，getDepthStencilState() 返回 null）。
     */
    private static void withNullableDepthStencil(RenderPipeline.Builder builder, RenderPipeline original) {
        var depth = original.getDepthStencilState();
        if (depth != null) {
            builder.withDepthStencilState(depth);
        }
    }

    /** 复制原管线的颜色目标状态（blend/format/writeMask）。 */
    private static void layoutsFor(RenderPipeline.Builder builder, RenderPipeline original) {
        var targetStates = original.getColorTargetStates();
        for (int i = 0; i < targetStates.size(); i++) {
            var state = targetStates.get(i);
            if (state != null) {
                builder.withColorTargetState(i, state);
            } else if (i > 0) {
                builder.withUnusedColorTargetState(i);
            }
        }
    }

    /**
     * 延迟渲染 G-buffer 地形管线：shader = sodium_terrain_gbuffer（几何阶段
     * 不光照），颜色目标声明 G-buffer 四目标（位置/法线/albedo/雾）。
     * 附件由 ChunkSectionsToRenderMixin 的 createOpaquePass 提供，格式必须一致：
     * GB0 RGBA32F、GB1 RGBA16F、GB2 RGBA8、GB3 RGBA16F。location 保持 sodium
     * 命名空间（Vulkan push_constant 声明要求）。
     */
    private static RenderPipeline cloneSodiumTerrainGBuffer(RenderPipeline original) {
        String name = original.getLocation().getPath();
        net.minecraft.resources.Identifier shader = net.minecraft.resources.Identifier.parse(
                "adaptclient:core/sodium_terrain_gbuffer");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(net.minecraft.resources.Identifier.parse("sodium:gbuffer/" + name))
                .withVertexShader(shader)
                .withFragmentShader(shader);

        for (String define : original.getShaderDefines().flags()) {
            builder.withShaderDefine(define);
        }
        original.getShaderDefines().values().forEach((key, value) -> {
            try {
                builder.withShaderDefine(key, Integer.parseInt(value));
            } catch (NumberFormatException first) {
                try {
                    builder.withShaderDefine(key, Float.parseFloat(value));
                } catch (NumberFormatException second) {
                    // 无法表达的 define 值忽略
                }
            }
        });

        java.util.List<BindGroupLayout> layouts = new ArrayList<>(original.getBindGroupLayouts());
        layouts.add(ADAPT_LIGHTING_LAYOUT);
        layouts.add(ADAPT_SHADOW_LAYOUT);
        for (BindGroupLayout layout : layouts) {
            builder.withBindGroupLayout(layout);
        }

        builder.withColorTargetState(0, new ColorTargetState(Optional.empty(),
                GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL));
        builder.withColorTargetState(1, new ColorTargetState(Optional.empty(),
                GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
        builder.withColorTargetState(2, new ColorTargetState(Optional.empty(),
                GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
        builder.withColorTargetState(3, new ColorTargetState(Optional.empty(),
                GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));
        // GB4 世界相对位置（阴影接收 + r5 光照）、GB5 lightmap/emissive。
        // 设备 maxColorAttachments=8（Apple M4/MoltenVK 实测，MrtLimitProbe），
        // 6 个附件在限制内。
        builder.withColorTargetState(4, new ColorTargetState(Optional.empty(),
                GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL));
        builder.withColorTargetState(5, new ColorTargetState(Optional.empty(),
                GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL));

        builder.withDepthStencilState(original.getDepthStencilState());
        builder.withPolygonMode(original.getPolygonMode());
        builder.withCull(original.isCull());
        var formats = original.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            if (format != null) {
                builder.withVertexBinding(i, format);
            }
        }
        builder.withPrimitiveTopology(original.getPrimitiveTopology());

        return builder.build();
    }

    /**
     * 半透明 Sodium 地形管线副本：与不透明版同构，但保留原管线的 alpha
     * blend（TRANSLUCENT_TERRAIN），shader 用 sodium_terrain_translucent
     * （fragment 输出 alpha，不写深度/不做 cutout）。
     */
    private static RenderPipeline cloneSodiumTerrainTranslucent(RenderPipeline original) {
        String name = original.getLocation().getPath();
        net.minecraft.resources.Identifier shader = net.minecraft.resources.Identifier.parse(
                "adaptclient:core/sodium_terrain_translucent");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(net.minecraft.resources.Identifier.parse("sodium:" + name))
                .withVertexShader(shader)
                .withFragmentShader(shader);

        for (String define : original.getShaderDefines().flags()) {
            builder.withShaderDefine(define);
        }
        original.getShaderDefines().values().forEach((key, value) -> {
            try {
                builder.withShaderDefine(key, Integer.parseInt(value));
            } catch (NumberFormatException first) {
                try {
                    builder.withShaderDefine(key, Float.parseFloat(value));
                } catch (NumberFormatException second) {
                    // 无法表达的 define 值忽略
                }
            }
        });

        java.util.List<BindGroupLayout> layouts = new ArrayList<>(original.getBindGroupLayouts());
        layouts.add(ADAPT_LIGHTING_LAYOUT);
        layouts.add(ADAPT_SHADOW_LAYOUT);
        for (BindGroupLayout layout : layouts) {
            builder.withBindGroupLayout(layout);
        }

        var targetStates = original.getColorTargetStates();
        for (int i = 0; i < targetStates.size(); i++) {
            var state = targetStates.get(i);
            if (state != null) {
                builder.withColorTargetState(i, state);
            } else if (i > 0) {
                builder.withUnusedColorTargetState(i);
            }
        }

        builder.withDepthStencilState(original.getDepthStencilState());
        builder.withPolygonMode(original.getPolygonMode());
        builder.withCull(original.isCull());
        var formats = original.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            if (format != null) {
                builder.withVertexBinding(i, format);
            }
        }
        builder.withPrimitiveTopology(original.getPrimitiveTopology());

        return builder.build();
    }

    /**
     * 克隆 Sodium 地形管线：保留其压缩顶点格式/define/混合状态，
     * 替换为 adaptclient sodium_terrain shader 并追加 AdaptLighting 布局。
     * location 必须保持 sodium 命名空间——Sodium 的 VulkanPipelineMixin 以
     * {@code namespace.contains("sodium")} 判断是否为管线布局声明
     * push_constant range（缺它会与 u_Globals 抢占 MSL buffer(0) 编译失败）。
     */
    private static RenderPipeline cloneSodiumTerrain(RenderPipeline original) {
        String name = original.getLocation().getPath();
        net.minecraft.resources.Identifier shader = net.minecraft.resources.Identifier.parse(
                "adaptclient:core/sodium_terrain");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(net.minecraft.resources.Identifier.parse("sodium:" + name))
                .withVertexShader(shader)
                .withFragmentShader(shader);        for (String define : original.getShaderDefines().flags()) {
            builder.withShaderDefine(define);
        }
        original.getShaderDefines().values().forEach((key, value) -> {
            try {
                builder.withShaderDefine(key, Integer.parseInt(value));
            } catch (NumberFormatException first) {
                try {
                    builder.withShaderDefine(key, Float.parseFloat(value));
                } catch (NumberFormatException second) {
                    // 无法表达的 define 值忽略
                }
            }
        });

        java.util.List<BindGroupLayout> layouts = new ArrayList<>(original.getBindGroupLayouts());
        layouts.add(ADAPT_LIGHTING_LAYOUT);
        layouts.add(ADAPT_SHADOW_LAYOUT);
        for (BindGroupLayout layout : layouts) {
            builder.withBindGroupLayout(layout);
        }

        var targetStates = original.getColorTargetStates();
        for (int i = 0; i < targetStates.size(); i++) {
            var state = targetStates.get(i);
            if (state != null) {
                builder.withColorTargetState(i, state);
            } else if (i > 0) {
                builder.withUnusedColorTargetState(i);
            }
        }

        builder.withDepthStencilState(original.getDepthStencilState());
        builder.withPolygonMode(original.getPolygonMode());
        builder.withCull(original.isCull());
        var formats = original.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            if (format != null) {
                builder.withVertexBinding(i, format);
            }
        }
        builder.withPrimitiveTopology(original.getPrimitiveTopology());

        return builder.build();
    }
}
