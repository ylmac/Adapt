package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ColorSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import com.ylmac.adapt.render.AdaptLightSources;
import com.ylmac.adapt.render.AdaptLightingUniform;
import com.ylmac.adapt.render.BlockLightSourceCache;
import com.ylmac.adapt.render.HaloGlowRenderer;
import com.ylmac.adapt.render.ReimaginedCloudRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.awt.Color;

/**
 * Halo3D —— 独立的世界空间 3D 天使光环。
 * <p>
 * 这不是旧 Hats 的 Gizmos 点/线近似，而是一个真正的 torus（环面）模型：
 * 主圆周 96 段、截面 12 段，每个面严格提交 4 个 QUAD 顶点；位置使用相机相对
 * 坐标，法线使用 PoseStack 的 normal matrix 变换，交给 MC 26.2 的
 * SubmitNodeStorage 自定义几何 feature 在当前世界帧渲染。
 * <p>
 * 视觉分层：
 * - Outline：比核心管径更粗一圏的包裹外壳，模拟 Enchantment Outlines 的明亮外框；
 * - Glow：多层膨胀、低 alpha 的发光 torus，模拟 Visual Effects+ 的柔和发光；
 * - Core：纯色半透明核心环。
 * <p>
 * 管线对（关键：叠加行为与原来 cutout+emissive 完全一致）：
 * - bodyType = RenderTypes.breezeWind（BREEZE_WIND）：NO_CARDINAL_LIGHTING
 *   （vertexColor = Color，避免原版 PER_FACE_LIGHTING 阴影）+ TRANSLUCENT 半透明 +
 *   DepthStencilState.DEFAULT 写深度 + sortOnUpload。用于外框壳和核心环；UV2
 *   可选使用当前世界的 block/sky light。
 * - lightType = RenderTypes.eyes（EYES）：NO_CARDINAL_LIGHTING + EMISSIVE +
 *   NO_OVERLAY + TRANSLUCENT + 只读深度。用于发光层和条纹，保持原来的不抢深度语义。
 * - glow pass：使用 Halo 专用 FrameGraph target，复制主场景深度后再生成 mask、
 *   bilateral blur 和 composite；不读取 GPU 回 CPU，也不修改主世界材质管线。
 * <p>
 * 所有世界几何继续使用 QUADS 拓扑；纹理是本模块自带的 1×1 白色纹理，颜色由顶点
 * 颜色控制。
 */
public final class Halo3D extends Module {

    // SimpleTexture.loadContents 直接 getResourceOrThrow(resourceId)，因此必须使用
    // JAR 中资源的完整路径（包含 textures/ 和 .png 扩展名）。
    private static final Identifier HALO_TEXTURE = Identifier.parse("adaptclient:textures/halo/white.png");
    private static final int MAJOR_SEGMENTS = 96;
    private static final int MINOR_SEGMENTS = 12;
    /**
     * 增长式光列表的防御 cap，等于 LightListBuffer 容量。
     * 不是“只取最强 N 盏”：submitNearby 按 section 收录视距内全部灯，
     * 像素侧用可见光列表只测覆盖自己的灯（MegaLights：列表发现，不截断）。
     */
    private static final int LIGHT_BUDGET = 4096;

    private static Halo3D instance;

    public static Halo3D get() {
        return instance;
    }

    private final DoubleSetting radius;
    private final DoubleSetting tubeRadius;
    private final DoubleSetting tilt;
    private final DoubleSetting heightOffset;
    private final ColorSetting coreColor;
    private final ColorSetting outlineColor;
    private final DoubleSetting outlineWidth;
    private final BoolSetting outline;
    private final BoolSetting worldLighting;
    private final BoolSetting illuminateEntities;
    private final DoubleSetting entityLightRadius;
    private final DoubleSetting entityLightStrength;
    private final BoolSetting screenGlow;
    private final BoolSetting glow;
    private final DoubleSetting glowRadius;
    private final DoubleSetting glowStrength;
    private final IntSetting glowQuality;
    private final BoolSetting glowStripe;
    private final ColorSetting stripeColor;
    private final DoubleSetting stripeWidth;
    private final DoubleSetting stripeSpeed;
    private final DoubleSetting spinSpeed;
    private final BoolSetting followHead;
    private final BoolSetting onlyThirdPerson;
    private final BoolSetting showOtherPlayers;
    private final DoubleSetting otherPlayerRange;

    public Halo3D() {
        super("halo3d", "Halo3D", Category.RENDER);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);

        radius = doubleSetting("Radius", 0.48, 0.15, 2.0, 0.01);
        tubeRadius = doubleSetting("Tube Radius", 0.055, 0.015, 0.3, 0.005);
        tilt = doubleSetting("Tilt", 20.0, -45.0, 45.0, 1.0);
        heightOffset = doubleSetting("Height Offset", 0.14, -0.2, 1.0, 0.01);
        coreColor = colorSetting("Core Color", new Color(255, 255, 255, 180));
        outlineColor = colorSetting("Outline Color", new Color(255, 255, 255, 220));
        outline = boolSetting("Enchantment Outline", true);
        outlineWidth = doubleSetting("Outline Width", 0.035, 0.005, 0.2, 0.005,
                () -> outline.getValue());
        worldLighting = boolSetting("World Lighting", true);
        illuminateEntities = boolSetting("Illuminate Entities", true);
        entityLightRadius = doubleSetting("Entity Light Radius", 4.5, 1.0, 12.0, 0.25,
                () -> illuminateEntities.getValue());
        entityLightStrength = doubleSetting("Entity Light Strength", 1.0, 0.1, 2.0, 0.05,
                () -> illuminateEntities.getValue());
        screenGlow = boolSetting("Screen Glow", true);
        glow = boolSetting("Visual Effects Glow", true);
        glowRadius = doubleSetting("Glow Radius", 0.22, 0.02, 0.8, 0.01,
                () -> glow.getValue());
        glowStrength = doubleSetting("Glow Strength", 0.9, 0.1, 2.0, 0.05,
                () -> glow.getValue());
        glowQuality = intSetting("Glow Quality", 4, 1, 8, 1,
                () -> glow.getValue());
        glowStripe = boolSetting("Glow Stripe", true);
        stripeColor = colorSetting("Stripe Color", new Color(255, 255, 255, 255),
                () -> glowStripe.getValue());
        stripeWidth = doubleSetting("Stripe Width", 0.18, 0.03, 0.6, 0.01,
                () -> glowStripe.getValue());
        stripeSpeed = doubleSetting("Stripe Speed", 0.8, -4.0, 4.0, 0.05,
                () -> glowStripe.getValue());
        spinSpeed = doubleSetting("Spin Speed", 0.35, -3.0, 3.0, 0.01);
        followHead = boolSetting("Follow Head Rotation", true);
        onlyThirdPerson = boolSetting("Only Third Person", true);
        showOtherPlayers = boolSetting("Show Other Players", false);
        otherPlayerRange = doubleSetting("Other Player Range", 32.0, 4.0, 128.0, 1.0,
                () -> showOtherPlayers.getValue());
    }

    @Override
    protected void onEnable() {
        // No per-frame resources are allocated here. The white texture is a normal
        // resource-pack texture and the RenderType is created lazily on the render thread.
    }

    @Override
    protected void onDisable() {
        AdaptLightSources.clear();
        // SubmitNodeStorage owns submitted frame geometry; there is no persistent GPU buffer
        // to destroy in the module.
    }

    /**
     * 在 LevelRenderer.render 的 prepareFrame 之前提交当前帧 Halo 3D 几何。
     * 必须在 render thread 调用；storage 是当前 LevelRenderer 本帧的 storage。
     */
    public void submit(SubmitNodeStorage storage, CameraRenderState camera,
                       float partialTicks) {
        // Clear the per-frame mask state even when the module is disabled or the camera
        // skips the local player. The custom FrameGraph pass must never reuse last frame's mask.
        HaloGlowRenderer.beginFrame();
        AdaptLightSources.beginFrame();

        // 光影总开关关闭：Adapt 管线整条不跑（见 AdaptRenderToggle），光照 UBO /
        // 云帧 / 光列表都没有消费者 —— 跳过收集，省掉每帧扫 section 索引、重建
        // 簇网格和体素场的开销。Halo3D 自己的几何提交不受影响（它用原版
        // RenderType，开关关掉时走原版管线照样能画）。
        boolean worldEffects = com.ylmac.adapt.render.AdaptRenderToggle.isWorldEffectsEnabled();

        if (mc.level == null || mc.player == null || storage == null || camera == null
                || camera.pos == null) {
            AdaptLightSources.endFrame();
            if (worldEffects) {
                AdaptLightingUniform.get().updateFrame(mc.level, camera, partialTicks,
                        AdaptLightSources.active());
                ReimaginedCloudRenderer.updateFrame(mc.level, camera, partialTicks);
            }
            return;
        }

        Vec3 cameraPos = camera.pos;
        // BlockState 光源在 Sodium chunk build 阶段进入不可变 section snapshot，
        // 这里在渲染线程查询相机附近的源。光列表现在走 LightListBuffer
        // （texel buffer，容量按需翻倍），不再受 UBO 定长数组的 32 上限约束
        // —— 旧上限的实测表现是"显示范围内有些光没法显示"。预留一个槽给玩家
        // 自身的 Halo3D 光源；即使模块关闭，真实发光方块光仍生效。
        if (worldEffects) {
            int lightSlots = isEnabled() ? LIGHT_BUDGET - 1 : LIGHT_BUDGET;
            BlockLightSourceCache.submitNearby(mc.level, cameraPos, lightSlots);
        }
        AdaptLightSources.endFrame();
        if (!isEnabled()) {
            // Halo3D 自己关着，但光影管线照跑：光照 UBO / 体素场 / 云帧仍然
            // 必须每帧更新 —— 它们的消费者是延迟光照 pass，不是本模块。
            //
            // 这一分支曾经漏掉这两个调用（我把它们挪进了 worldEffects 判断却
            // 忘了放在这里），结果是 Halo3D 默认关闭 + 光影打开时，VoxelField /
            // LightList / LightTiles 三个 texel buffer 从未创建 → 延迟光照 pass
            // 在 pushDescriptors 抛 "Missing uniform VoxelField (should be
            // TEXEL_BUFFER)" 崩溃（用户日志实证）。
            if (worldEffects) {
                AdaptLightingUniform.get().updateFrame(mc.level, camera, partialTicks,
                        AdaptLightSources.active());
                ReimaginedCloudRenderer.updateFrame(mc.level, camera, partialTicks);
            }
            return;
        }

        // 两层管线，保持与原来（cutout 写深度 + emissive 不写深度）相同的叠加行为：
        // - bodyType（RenderTypes.breezeWind ← BREEZE_WIND）：DepthStencilState.DEFAULT
        //   写深度（背景水/玻璃等半透明元素画在环体后面时被深度挡住，不会叠到前面）
        //   + NO_CARDINAL_LIGHTING（vertexColor = Color，纯色无方向性阴影）+ 无 EMISSIVE
        //   + TRANSLUCENT 半透明混合 + cull(false) 双面 + sortOnUpload 自遮挡。用于环体和外框；
        //   UV2 仅由 World Lighting 决定使用世界 packed light 还是全亮光照。
        // - lightType（RenderTypes.eyes ← EYES）：不写深度、纯光叠加。用于发光层和条纹。
        RenderType lightType = RenderTypes.eyes(HALO_TEXTURE);
        RenderType bodyType = RenderTypes.breezeWind(HALO_TEXTURE, 0.0f, 0.0f);
        RenderType maskType = screenGlow.getValue() ? HaloGlowRenderer.maskType() : null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player player)) {
                continue;
            }
            if (player.isRemoved()) {
                continue;
            }
            if (player == mc.player) {
                if (onlyThirdPerson.getValue() && mc.options.getCameraType().isFirstPerson()) {
                    continue;
                }
            } else if (!showOtherPlayers.getValue()
                    || player.distanceTo(mc.player) > otherPlayerRange.getValue()) {
                continue;
            }

            Vec3 worldPos = interpolatePosition(player, partialTicks);
            Vec3 relativePos = worldPos.subtract(cameraPos);
            double y = relativePos.y + player.getEyeHeight() + heightOffset.getValue();
            if (player.isCrouching()) {
                y -= 0.2;
            }
            Vec3 haloWorldPos = new Vec3(worldPos.x, worldPos.y + player.getEyeHeight()
                    + heightOffset.getValue() - (player.isCrouching() ? 0.2 : 0.0), worldPos.z);
            LightingProfile lighting = sampleLighting(haloWorldPos);
            if (illuminateEntities.getValue()) {
                int argb = toArgb(coreColor.getValue());
                AdaptLightSources.submitSource(
                        haloWorldPos,
                        ((argb >>> 16) & 0xFF) / 255.0f,
                        ((argb >>> 8) & 0xFF) / 255.0f,
                        (argb & 0xFF) / 255.0f,
                        entityLightRadius.getValue().floatValue(),
                        entityLightStrength.getValue().floatValue());
            }

            PoseStack pose = new PoseStack();
            pose.translate((float) relativePos.x, (float) y, (float) relativePos.z);
            if (followHead.getValue()) {
                pose.mulPose(new Quaternionf().rotateY((float) Math.toRadians(-player.getYRot(partialTicks))));
            }
            pose.mulPose(new Quaternionf().rotateY((float) (System.nanoTime() * 1.0E-9 * spinSpeed.getValue())));
            pose.mulPose(new Quaternionf().rotateZ((float) Math.toRadians(tilt.getValue())));

            submitPlayerHalo(storage, lightType, bodyType, maskType, pose, lighting);
        }
        AdaptLightSources.endFrame();
        AdaptLightingUniform.get().updateFrame(mc.level, camera, partialTicks,
                AdaptLightSources.active());
        ReimaginedCloudRenderer.updateFrame(mc.level, camera, partialTicks);
    }

    private void submitPlayerHalo(SubmitNodeStorage storage, RenderType lightType,
                                  RenderType bodyType, RenderType maskType,
                                  PoseStack pose, LightingProfile lighting) {
        final double coreRadius = radius.getValue();
        final double coreTube = tubeRadius.getValue();
        // BREEZE_WIND samples the actual packed world light. RGB is intentionally kept
        // unchanged so the halo remains a flat color without normal-based shadows.
        final int core = toArgb(coreColor.getValue());
        final int outline = toArgb(outlineColor.getValue());
        final int packedLight = lighting.packedLight();
        final int glowCore = core;
        final boolean outlineEnabled = this.outline.getValue();
        final boolean glowEnabled = this.glow.getValue();
        final double glowDistance = glowRadius.getValue();
        final double glowPower = glowStrength.getValue();
        final int quality = glowQuality.getValue();
        final boolean stripeEnabled = glowStripe.getValue();
        final int stripe = toArgb(stripeColor.getValue());
        final double stripeSigma = Math.max(0.01, stripeWidth.getValue() * Math.PI * 2.0);
        final double stripeCenter = positiveModulo(
                System.nanoTime() * 1.0E-9 * stripeSpeed.getValue(), Math.PI * 2.0);

        // 外框壳：比核心管径更粗一圈的"包裹外壳"，保持原有几何结构。
        // bodyType 写深度，且只使用真实世界 lightmap/受光后的颜色。
        if (outlineEnabled) {
            final double r = coreRadius;
            final double tube = coreTube + outlineWidth.getValue();
            storage.submitCustomGeometry(pose, bodyType,
                    (matrix, vertices) -> emitTorus(matrix, vertices, r, tube, outline, 1.0f,
                            packedLight));
        }

        // 自有屏幕光晕 mask：使用同一帧主场景深度复制出的专用目标，
        // 只在主场景深度可见的环面表面生成。
        if (maskType != null) {
            final int maskColor = withAlpha(0xFFFFFFFF,
                    (float) Math.min(1.0, glowPower));
            storage.submitCustomGeometry(pose, maskType,
                    (matrix, vertices) -> emitTorus(matrix, vertices,
                            coreRadius,
                            coreTube + (outlineEnabled ? outlineWidth.getValue() : 0.0),
                            maskColor, 1.0f, LightCoordsUtil.FULL_BRIGHT));
        }

        // Glow 层从外到内提交，alpha 按平方衰减；使用 eyes（不写深度、纯光叠加），
        // 与原来的 emissive 层行为一致——不抢深度，半透明背景画在前/后都能正确叠加。
        if (glowEnabled) {
            for (int layer = quality; layer >= 1; layer--) {
                final int layerIndex = layer;
                final double normalized = (double) layerIndex / quality;
                final double expansion = glowDistance * normalized;
                // Gaussian-like falloff：越外层越透明；近核心层保持柔亮。
                final double falloff = Math.exp(-3.4 * normalized * normalized);
                final float alpha = (float) Math.max(0.008,
                        Math.min(0.34, glowPower * 0.26 * falloff));
                storage.submitCustomGeometry(pose, lightType,
                        (matrix, vertices) -> emitTorus(matrix, vertices,
                                coreRadius + expansion * 0.08,
                                coreTube + expansion,
                                withAlpha(glowCore, alpha), 1.0f));
            }
        }

        // 自定义发光条纹：沿主圆周移动的 Gaussian 亮带。条纹略微膨胀，
        // 使用 lightType（不写深度），不依赖 vanilla outline target。
        if (stripeEnabled) {
            storage.submitCustomGeometry(pose, lightType,
                    (matrix, vertices) -> emitStripeTorus(matrix, vertices,
                            coreRadius, coreTube * 1.08,
                            stripe, stripeCenter, stripeSigma));
        }

        // 核心环最后提交。bodyType 写深度 + GREATER_THAN_OR_EQUAL：
        // 1) 头部等不透明几何早已写深度 → 光环在头后的部分被正确遮挡；
        // 2) 背景水/玻璃等半透明画在环体之后的片区，其片元深度更小 → 被环体
        //    写下的深度挡住 → 不会污染到光环前面（与原来 cutout 行为一致）。
        // NO_CARDINAL_LIGHTING → vertexColor = Color，核心保持设置中的纯色；
        // packedLight 仅由 World Lighting 决定，TRANSLUCENT → 半透明生效。
        storage.submitCustomGeometry(pose, bodyType,
                (matrix, vertices) -> emitTorus(matrix, vertices, coreRadius, coreTube, core, 1.0f,
                        packedLight));
    }

    /**
     * 读取光环所在位置的世界 packed light；关闭 World Lighting 时使用全亮光照。
     * 该设置只控制 lightmap，不改变光环的顶点 RGB 颜色。
     */
    private LightingProfile sampleLighting(Vec3 position) {
        if (!worldLighting.getValue()) {
            return LightingProfile.fullBright();
        }

        BlockPos center = BlockPos.containing(position);
        return new LightingProfile(LightCoordsUtil.getLightCoords(mc.level, center));
    }

    private record LightingProfile(int packedLight) {
        private static LightingProfile fullBright() {
            return new LightingProfile(LightCoordsUtil.FULL_BRIGHT);
        }
    }

    /**
     * 生成严格 QUADS 拓扑的 torus。每个 (major,minor) 网格面只写四个顶点，
     * 与 RenderTypes.eyes / breezeWind 的 ENTITY/QUADS 管线匹配。
     */
    private static void emitTorus(PoseStack.Pose pose, VertexConsumer vertices,
                                  double majorRadius, double minorRadius, int color, float uvScale) {
        emitTorus(pose, vertices, majorRadius, minorRadius, color, uvScale,
                LightCoordsUtil.FULL_BRIGHT);
    }

    private static void emitTorus(PoseStack.Pose pose, VertexConsumer vertices,
                                  double majorRadius, double minorRadius, int color, float uvScale,
                                  int packedLight) {
        Matrix4f matrix = pose.pose();
        Vector3f normal = new Vector3f();
        for (int major = 0; major < MAJOR_SEGMENTS; major++) {
            double theta0 = Math.PI * 2.0 * major / MAJOR_SEGMENTS;
            double theta1 = Math.PI * 2.0 * (major + 1) / MAJOR_SEGMENTS;
            for (int minor = 0; minor < MINOR_SEGMENTS; minor++) {
                double phi0 = Math.PI * 2.0 * minor / MINOR_SEGMENTS;
                double phi1 = Math.PI * 2.0 * (minor + 1) / MINOR_SEGMENTS;

                emitTorusVertex(matrix, pose, vertices, theta0, phi0,
                        majorRadius, minorRadius, color, uvScale, packedLight, normal);
                emitTorusVertex(matrix, pose, vertices, theta1, phi0,
                        majorRadius, minorRadius, color, uvScale, packedLight, normal);
                emitTorusVertex(matrix, pose, vertices, theta1, phi1,
                        majorRadius, minorRadius, color, uvScale, packedLight, normal);
                emitTorusVertex(matrix, pose, vertices, theta0, phi1,
                        majorRadius, minorRadius, color, uvScale, packedLight, normal);
            }
        }
    }

    /** 动态 Gaussian 发光条纹网格。 */
    private static void emitStripeTorus(PoseStack.Pose pose, VertexConsumer vertices,
                                        double majorRadius, double minorRadius,
                                        int stripeColor, double center, double sigma) {
        Matrix4f matrix = pose.pose();
        Vector3f normal = new Vector3f();
        for (int major = 0; major < MAJOR_SEGMENTS; major++) {
            double theta0 = Math.PI * 2.0 * major / MAJOR_SEGMENTS;
            double theta1 = Math.PI * 2.0 * (major + 1) / MAJOR_SEGMENTS;
            int color0 = stripeColorAt(stripeColor, theta0, center, sigma);
            int color1 = stripeColorAt(stripeColor, theta1, center, sigma);
            for (int minor = 0; minor < MINOR_SEGMENTS; minor++) {
                double phi0 = Math.PI * 2.0 * minor / MINOR_SEGMENTS;
                double phi1 = Math.PI * 2.0 * (minor + 1) / MINOR_SEGMENTS;
                emitTorusVertex(matrix, pose, vertices, theta0, phi0,
                        majorRadius, minorRadius, color0, 1.0f,
                        LightCoordsUtil.FULL_BRIGHT, normal);
                emitTorusVertex(matrix, pose, vertices, theta1, phi0,
                        majorRadius, minorRadius, color1, 1.0f,
                        LightCoordsUtil.FULL_BRIGHT, normal);
                emitTorusVertex(matrix, pose, vertices, theta1, phi1,
                        majorRadius, minorRadius, color1, 1.0f,
                        LightCoordsUtil.FULL_BRIGHT, normal);
                emitTorusVertex(matrix, pose, vertices, theta0, phi1,
                        majorRadius, minorRadius, color0, 1.0f,
                        LightCoordsUtil.FULL_BRIGHT, normal);
            }
        }
    }

    private static int stripeColorAt(int color, double theta, double center, double sigma) {
        double distance = Math.abs(shortestAngle(theta - center));
        double glow = Math.exp(-(distance * distance) / (2.0 * sigma * sigma));
        // 核心亮带全不透明，远处快速衰减成透明。
        float alpha = (float) Math.max(0.0, Math.min(1.0, glow));
        return withAlpha(color, alpha);
    }

    private static double shortestAngle(double angle) {
        double twoPi = Math.PI * 2.0;
        angle = positiveModulo(angle + Math.PI, twoPi) - Math.PI;
        return angle;
    }

    private static double positiveModulo(double value, double mod) {
        double result = value % mod;
        return result < 0.0 ? result + mod : result;
    }

    private static void emitTorusVertex(Matrix4f matrix, PoseStack.Pose pose,
                                        VertexConsumer vertices, double theta, double phi,
                                        double majorRadius, double minorRadius, int color,
                                        float uvScale, int packedLight, Vector3f normal) {
        float cosTheta = (float) Math.cos(theta);
        float sinTheta = (float) Math.sin(theta);
        float cosPhi = (float) Math.cos(phi);
        float sinPhi = (float) Math.sin(phi);

        float localRadius = (float) (majorRadius + minorRadius * cosPhi);
        float localX = localRadius * cosTheta;
        float localY = (float) (minorRadius * sinPhi);
        float localZ = localRadius * sinTheta;
        float normalX = cosPhi * cosTheta;
        float normalY = sinPhi;
        float normalZ = cosPhi * sinTheta;

        // Matrix4f.transform(Vector4f) mutates its argument; keep position and normal
        // independent so a transformed position never becomes the normal by accident.
        Vector4f position = matrix.transform(new Vector4f(localX, localY, localZ, 1.0f));
        pose.transformNormal(normalX, normalY, normalZ, normal).normalize();

        // ENTITY 格式需要 UV0（纹理）、UV1（overlay）、UV2（lightmap）。
        // packedLight 仅按 World Lighting 设置选择真实世界光照或全亮光照；颜色保持纯色。
        vertices.addVertex(position.x, position.y, position.z)
                .setColor(color)
                .setUv((float) (theta / (Math.PI * 2.0)) * uvScale,
                        (float) (phi / (Math.PI * 2.0)) * uvScale)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv1(0, 0)
                .setLight(packedLight)
                .setNormal(normal.x, normal.y, normal.z);
    }

    private static Vec3 interpolatePosition(Player player, float partialTicks) {
        Vec3 current = player.position();
        Vec3 old = player.oldPosition();
        return new Vec3(
                old.x + (current.x - old.x) * partialTicks,
                old.y + (current.y - old.y) * partialTicks,
                old.z + (current.z - old.z) * partialTicks);
    }

    private static int toArgb(Color color) {
        return (color.getAlpha() << 24)
                | (color.getRed() << 16)
                | (color.getGreen() << 8)
                | color.getBlue();
    }

    private static int withAlpha(int argb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * alpha)));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    @Override
    public String getInfo() {
        return String.format("%.2f", radius.getValue());
    }
}
