package com.ylmac.adapt.render.lighting;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.AddressMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * 方向阴影贴图。
 * lightView = Rx(90) * Rz(celestial*-360) * Rx(-20)
 * lightProjection 为对称正交，近平面 -100，远平面 192
 * LightSpaceMat = lightProjection * lightView * T(camPos % 2)
 * 投射几何用世界相对坐标（区块原点减相机）。深度为前向 Z，z*0.5+0.5。
 * 清空为 1.0，比较函数 LESS_OR_EQUAL。
 */
public final class AdaptShadowMap {
    /**
 * 方向影分辨率。4096 而不是 2048：去掉 distort 后每格 texel 数从
 * "中心 10× 放大"变成均匀 {@code RESOLUTION / (SHADOW_HALF_EXTENT*2)}，
 * 2048 时只有 5.33 texel/格 — 实测"太阳影子好糊"。4096 给到
 * 10.67 texel/格，比 distort 的中心放大还锐，而且是<b>全图均匀</b>的，
 * 不像 distort 那样边缘退化到 1×，覆盖范围也不损失。
 * <p>
 * 代价是 depth + color 两张 4096²（color 是 WRITE_NONE 的占位，
 * TextureTarget 不支持只建深度）。M4 统一内存下可接受；若要再省，
 * 下一步是给 caster 单独建一个只有深度附件的 RenderPassDescriptor。
 */
    private static final int RESOLUTION = 4096;
    /**
 * 方向影正交投影的半边长（格）。 shadowRenderDistance=12 区块 → 192 格。
 * <p>
 * 去掉 distort 后每格只剩 {@code RESOLUTION / (SHADOW_HALF_EXTENT * 2)}
 * = 5.33 texel，比 distort 的中心 10× 放大糊，但换来 receiver 与 caster
 * <b>精确对齐</b>（见 {@link #SHADOW_DISTORTION}）。
 */
    private static final float SHADOW_HALF_EXTENT = 192.0f;
    /**
 * 的 shadow distort 强度，<b>已停用（0 = 不 distort）</b>。
 * <p>
 * 用 {@code ndc.xy /= dist * SD + (1 - SD)} 把近处几何在 shadow map 上
 * 放大 10 倍，一张 2048² 同时兼顾近处锐利与远处覆盖。它成立的前提是
 * <b>caster 与 receiver 在同一个阶段做这个变换</b> — 的 receiver 在
 * {@code terrain.vsh:110} 的顶点阶段 distort，于是光栅器的线性插值误差两边
 * 都有、正好抵消。
 * <p>
 * 而 caster 仍是逐顶点 + 光栅线性插值。离线数值（DistortSim）：
 * <pre>
 * 0.042 0.23810 0.30303 66 texel = 1.25 格
 * 0.092 0.47544 0.50228 27 texel = 1.08 格
 * 0.442 0.88650 0.88777 1 texel = 0.52 格
 * </pre>
 * {@code x/(|x|·0.9+0.1)} 是强非线性，屏幕中心（玩家脚下）差 1.25 格 —
 * 实测正是"太阳影子有点错位"。逐 quad 的顶点阶段在延迟管线里不存在，
 * 这个误差<b>原理上无法消除</b>，只能不 distort：两边都是恒等变换，误差 0。
 */
    private static final float SHADOW_DISTORTION = 0.0f;
    /** 一格世界尺度对应的 forward-Z 深度增量（ortho z 范围 ±SHADOW_HALF_DEPTH）。 */
    private static final float SHADOW_HALF_DEPTH = 256.0f;
    private static final float SHADOW_DEPTH_PER_BLOCK = 1.0f / (SHADOW_HALF_DEPTH * 2.0f);
    /**
 * 天光可见度图分辨率。用途见 {@link #skyTarget}：判"头顶有没有方块"不需要
 * 阴影级精度，1024 足够，且省一半带宽。
 */
    private static final int SKY_RESOLUTION = 1024;
    /** 天光正交投影的半边长（格）。覆盖玩家周围 ±128 格。 */
    private static final float SKY_HALF_EXTENT = 128.0f;
    /** 天光正交投影的垂直半深度（格）。世界高度 -64..320 → 384 格，取半 +余量。 */
    private static final float SKY_HALF_DEPTH = 256.0f;
    /** 一格世界高度对应的 forward-Z 深度增量（顶视正交，z 范围 ±SKY_HALF_DEPTH）。 */
    private static final float SKY_DEPTH_PER_BLOCK = 1.0f / (SKY_HALF_DEPTH * 2.0f);
    private static final int FRAME_SIZE = 224;
    private static final float SUN_PATH_ROTATION = 20.0f;
    private static final ByteBuffer FRAME = ByteBuffer.allocateDirect(FRAME_SIZE)
            .order(ByteOrder.nativeOrder());

    private static TextureTarget target;
    /**
 * 天光可见度图：从**正上方**对地形做正交投影的深度图。
 * <p>
 * 用来替代原版 lightmap 的 sky 通道（项目规则要求彻底不用原版光照数据）。
 * receiver 用世界相对坐标查这张图：若片元的高度低于该 (x,z) 列的最高实心
 * 方块，说明头顶有遮挡 → 天光被遮。这是**几何真值**，比原版的整数光照
 * 等级 + Sodium 平滑插值更准，且与"清空原版光照"规则不冲突。
 * <p>
 * 复用同一套 caster 基础设施（sodium_shadow 管线），只换投影矩阵为顶视正交。
 */
    private static TextureTarget skyTarget;
    private static com.mojang.renderpearl.api.buffers.GpuBuffer frameBuffer;
    private static GpuSampler sampler;
    private static boolean rendering;

    private static final Matrix4f lightView = new Matrix4f();
    private static final Matrix4f lightProjection = new Matrix4f();
    private static final Matrix4f lightViewProjection = new Matrix4f();
    /** 顶视正交 VP（天光可见度图用）。 */
    private static final Matrix4f skyViewProjection = new Matrix4f();
    /** 天光 pass 进行中（管线重定向需要区分它与方向影 pass）。 */
    private static boolean renderingSky;
    private static long lastShadowFailLogNs;
    /**
 * 本次 caster pass 是否还需要清 shadow map。
 * <p>
 * 不能只靠 {@code clearColorAndDepthTextures}：那是独立命令，而随后
 * Sodium 建的 render pass 是 LOAD。26.2 的 {@code memoryBarrier} 只在
 * submitRenderPass 发出（字节码实证），独立 clear 对同帧后续 pass 不保证
 * 可见 → 深度跨帧累积。实测表现正是"站着不动影子越来越黑""转视角后旧
 * 影子不消失"。改为在 pass 创建时 CLEAR，由本标志保证每次 caster pass
 * 只清第一个 pass，后续 pass 追加。
 */
    private static boolean shadowNeedsClear;

    private AdaptShadowMap() {
    }

    /** 由 SodiumTerrainUniformMixin 调用：本次 caster pass 是否该清。 */
    public static boolean consumeShadowClear() {
        if (!shadowNeedsClear) {
            return false;
        }
        shadowNeedsClear = false;
        return true;
    }

    public static boolean isRendering() {
        return rendering;
    }

    public static TextureTarget target() {
        ensureResources();
        return target;
    }

    public static GpuSampler sampler() {
        ensureResources();
        return sampler;
    }

    public static void updateFrame(ClientLevel level, CameraRenderState camera, float partialTicks) {
        ensureResources();
        if (level == null || camera == null || camera.pos == null) {
            return;
        }

        // celestialAngle = SUN_ANGLE * 0.0027778, night shift
        float celestialAngle = Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe()
                .getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks) * 0.0027777778f;
        if (celestialAngle > 0.3f && celestialAngle < 0.7f) {
            celestialAngle += 0.5f;
        }
        boolean end = level.dimension().identifier().getPath().contains("end");
        if (end) celestialAngle = 0.0f;

        // 太阳/月亮方向的**单一真值**：与天空穹、云、shading 完全同一个公式
        // （adapt_lighting.glsl:258-265、ReimaginedCloudRenderer.updateFrame）：
        // timeAngle = (sunAngleDeg / 360 + 0.25) % 1
        // elevation = (timeAngle - 0.25) * 2π
        // worldSun = (sin elevation, cos elevation, 0)
        // 之前 shadow map 自建 Rx(90)·Rz(celestial·-360)·Rx(-20)，而
        // AdaptLightingUniform 用 Rx(90)·Ry(90)·Rz(...)·Rx(-20) — 差一个 90°
        // 偏航；天空公式又完全没有 20° 倾斜。三处三种太阳方向：shadow map 从
        // 一个方向投影，N·L 着色用另一个方向，玩家看到的太阳在第三个方向 →
        // 影子整体错位且随时间漂移（实机实证）。现在全部从天空方向派生。

        // celestialAngle 上面已按 语义做过夜间 +0.5 翻转，所以这里算出的
        // 方向在夜间自动是月亮方向，与 SunDirection UBO 同步。
        float timeAngle = (celestialAngle + 0.25f) % 1.0f;
        float elevation = (timeAngle - 0.25f) * (float) (Math.PI * 2.0);
        org.joml.Vector3f worldSun = new org.joml.Vector3f(
                (float) Math.sin(elevation), (float) Math.cos(elevation), 0.0f).normalize();

        // lightView：以世界太阳方向为光的 forward。太阳在世界 XY 平面内运动，
        // 用世界 +Z 作 up 参考永不与 forward 平行（若用世界 up，正午/午夜会
        // 退化成零向量）。lookAlong 产生纯旋转，与 lightView 语义一致
        // —平移由下面的 T(camPos%2) 对齐项提供。
        lightView.identity().lookAlong(
                -worldSun.x, -worldSun.y, -worldSun.z,
                0.0f, 0.0f, 1.0f);

        // lightProjection (line 817): setOrthoSymmetric(halfLen*2, halfLen*2, -100, 192, true)
        // halfLen = shadowDistance*16; default shadowRenderDistance 12 -> 192*2 = 384
        // z 范围加深到 ±SHADOW_HALF_DEPTH： 的 [-100, 192] 在相机飞高/飞低
        // 时会把地形相对坐标挤出 z 范围而裁剪（影子高处可见、低处不可见）。
        lightProjection.identity().setOrthoSymmetric(
                SHADOW_HALF_EXTENT * 2.0f, SHADOW_HALF_EXTENT * 2.0f,
                -SHADOW_HALF_DEPTH, SHADOW_HALF_DEPTH, true);

        // ShadowMap.setupRenderer (lines 262-290):
        // view = lightView * T(camX - floor(camX/2)*2) (= camX % 2)
        // VPLight = lightProjection * view
        // We bake T(camPos % 2) into lightViewProjection.
        float alignX = (float) (camera.pos.x - Math.floor(camera.pos.x / 2.0) * 2.0);
        float alignY = (float) (camera.pos.y - Math.floor(camera.pos.y / 2.0) * 2.0);
        float alignZ = (float) (camera.pos.z - Math.floor(camera.pos.z / 2.0) * 2.0);
        lightViewProjection.set(lightProjection)
                .mul(lightView)
                .mul(new Matrix4f().translate(alignX, alignY, alignZ));

        FRAME.clear();
        lightViewProjection.get(0, FRAME);
        // ViewToWorld = R⁻¹ (view->world rotation): vanilla entity caster/receiver
        // convert view-space fragPos to world-relative via this matrix.
        Matrix4f viewToWorld = new Matrix4f();
        if (camera.viewRotationMatrix != null) {
            viewToWorld.set(camera.viewRotationMatrix).invert();
        } else if (camera.orientation != null) {
            // 回退：viewRotationMatrix = orientation.conjugate（世界→视图），
            // 逆 = orientation（视图→世界）。
            viewToWorld.rotation(camera.orientation);
        }
        viewToWorld.get(64, FRAME);
        // ShadowParams: x=texelSize, y=每格的深度单位, z=texel 的世界尺度(格),
        // w=ShadowDistortion（恒 0，见 SHADOW_DISTORTION）
        FRAME.putFloat(128, 1.0f / RESOLUTION);
        // 旧值是 的 1e-4 常量 bias + shader 里的 0.002*dist² 斜率项。
        // 换算过后（depthPerBlock = 1/512）：常量项 = 0.05 格（太小，挡不住
        // 自阴影），dist² 项在屏幕边缘 = 2.05 格（太大，影子从投影物脚下脱开）。
        // dist² 项本来是给 distort 的可变 texel 足迹擦屁股的，distort 去掉后它
        // 是纯误差。现在传"每格 = 多少深度单位"，shader 用它把 bias 直接写成
        // 世界格（常量 0.25 格 + 斜率补偿），量纲清晰、与分辨率解耦。
        FRAME.putFloat(132, SHADOW_DEPTH_PER_BLOCK);
        // 一个 shadow texel 覆盖多少格：斜率 bias 要按它缩放（texel 越大，
        // 同一斜面在一个 texel 内的高度差越大，需要的 bias 也越大）。
        FRAME.putFloat(136, SHADOW_HALF_EXTENT * 2.0f / RESOLUTION);
        FRAME.putFloat(140, SHADOW_DISTORTION);
        // 顶视正交 VP：天光可见度图。lookAlong(0,-1,0) 从上往下看，up 参考用
        // 世界 +Z（不能用 +Y，会与 forward 平行退化）。几何与 caster 同为
        // 相对相机坐标，所以这里同样只需纯旋转 + 与阴影一致的 2 格对齐。
        Matrix4f skyProjection = new Matrix4f().setOrthoSymmetric(
                SKY_HALF_EXTENT * 2.0f, SKY_HALF_EXTENT * 2.0f,
                -SKY_HALF_DEPTH, SKY_HALF_DEPTH, true);
        Matrix4f skyView = new Matrix4f().lookAlong(0.0f, -1.0f, 0.0f, 0.0f, 0.0f, 1.0f);
        skyViewProjection.set(skyProjection).mul(skyView)
                .mul(new Matrix4f().translate(alignX, alignY, alignZ));
        skyViewProjection.get(144, FRAME);
        // SkyParams: x=texel size, y=深度偏置, z=正交半边长（格）, w=每格的深度单位
        FRAME.putFloat(208, 1.0f / SKY_RESOLUTION);
        // 1 格容差。软锥形比较（adaptSkyVisibility 的 smoothstep）自己带 ±2 格
        // 过渡带，不再需要靠大偏置去吃掉 distort 的 texel 足迹误差 — 旧的
        // 8 格等效偏置只是把硬边缘整体抬高，边缘依然是硬的。
        FRAME.putFloat(212, SKY_DEPTH_PER_BLOCK);
        // receiver 要把"世界格"换算成 UV 半径与深度差，必须知道这两个比例。
        FRAME.putFloat(216, SKY_HALF_EXTENT);
        FRAME.putFloat(220, SKY_DEPTH_PER_BLOCK);
        uploadFrame();
    }

    /**
 * 渲染地形进 shadow map（ShadowMap.renderShadowMap 的地形部分）。
 * <p>
 * 实体不在这里画，而在 {@link #renderEntityShadow} — 那个在主渲染
 * {@code executeSolid} <b>之后</b>调用，见该方法的说明。
 */
    public static void renderTerrainShadow(ChunkSectionsToRender sections, CameraRenderState camera,
                                           Object preparedFrame) {
        ensureResources();
        if (sections == null || camera == null) return;

        AdaptRenderProfiler.begin(AdaptRenderProfiler.SHADOW_TERRAIN);
        CommandEncoderHolder.clear(target);
        rendering = true;
        shadowNeedsClear = true;
        // 必须先把 Sodium 的 u_Globals 一次性写入守卫清掉：drawChunkLayer 内部
        // UniformBufferManager.update(lightMatrices) 在 hasUpdatedThisFrame=true
        // 时直接返回（u_Globals 保持主渲染矩阵），导致 caster 用主投影渲染，
        // 影子被 perspective 投影放大/错位。
        resetSodiumUniformGuard();
        try {
            renderSodiumTerrainShadow(camera);
        } finally {
            // 主渲染的 u_Globals 每帧由 Sodium setupTerrain prepareFrame 重置，
            // 但 shadow pass 已经写过 light 矩阵；这里再清一次守卫，保证主
            // 渲染 drawChunkLayer 能重新写入主矩阵（否则世界会用光矩阵渲染）。
            resetSodiumUniformGuard();
            rendering = false;
        }

        // 天光可见度图：同一套 caster，投影换成顶视正交。这是 lightmap.y 的
        // 替代品（几何真值），必须每帧与方向影一起更新。
        CommandEncoderHolder.clear(skyTarget);
        rendering = true;
        renderingSky = true;
        shadowNeedsClear = true;
        resetSodiumUniformGuard();
        try {
            renderSodiumSkyOcclusion(camera);
        } finally {
            resetSodiumUniformGuard();
            renderingSky = false;
            rendering = false;
        }
        AdaptRenderProfiler.end(AdaptRenderProfiler.SHADOW_TERRAIN);
    }

    /** 天光 pass 进行中：SodiumTerrainTargetMixin 用它选 skyTarget。 */
    public static boolean isRenderingSky() {
        return renderingSky;
    }

    public static TextureTarget skyTarget() {
        ensureResources();
        return skyTarget;
    }

    /**
 * 用顶视正交投影把地形画进天光可见度图。矩阵通过 ChunkRenderMatrices
 * 传给 Sodium 的 u_Globals（与方向影 caster 同一条路径），caster shader
 * 不需要改—它只是 {@code u_ProjectionMatrix * u_ModelViewMatrix * pos}。
 * <p>
 * 注意：sodium_shadow.vsh 里有 的 distort（xy 按到中心距离缩放），
 * 天光图不需要 distort。distort 对 xy 是单调径向缩放，receiver 端做同样
 * 变换即可自洽，所以沿用同一个 caster 不影响正确性（省一套管线）。
 */
    private static boolean renderSodiumSkyOcclusion(CameraRenderState camera) {
        try {
            Class<?> rendererClass = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            Method instance = rendererClass.getMethod("instanceNullable");
            Object renderer = instance.invoke(null);
            if (renderer == null) return false;

            float alignX = (float) (camera.pos.x - Math.floor(camera.pos.x / 2.0) * 2.0);
            float alignY = (float) (camera.pos.y - Math.floor(camera.pos.y / 2.0) * 2.0);
            float alignZ = (float) (camera.pos.z - Math.floor(camera.pos.z / 2.0) * 2.0);
            Matrix4f skyProjection = new Matrix4f().setOrthoSymmetric(
                    SKY_HALF_EXTENT * 2.0f, SKY_HALF_EXTENT * 2.0f,
                    -SKY_HALF_DEPTH, SKY_HALF_DEPTH, true);
            Matrix4f skyModelView = new Matrix4f()
                    .lookAlong(0.0f, -1.0f, 0.0f, 0.0f, 0.0f, 1.0f)
                    .mul(new Matrix4f().translate(alignX, alignY, alignZ));

            Class<?> matricesClass = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices");
            Constructor<?> matricesCtor = matricesClass.getConstructor(org.joml.Matrix4fc.class, org.joml.Matrix4fc.class);
            Object matrices = matricesCtor.newInstance(skyProjection, skyModelView);
            Class<?> groupClass = net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup.class;
            Class<?> samplerClass = com.mojang.renderpearl.api.textures.GpuSampler.class;
            Method draw = rendererClass.getMethod("drawChunkLayer", groupClass, matricesClass,
                    double.class, double.class, double.class, samplerClass);
            draw.invoke(renderer, net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup.OPAQUE,
                    matrices, (double) camera.pos.x, (double) camera.pos.y, (double) camera.pos.z,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            return true;
        } catch (Throwable t) {
            long now = System.nanoTime();
            if (now - lastShadowFailLogNs > 3_000_000_000L) {
                lastShadowFailLogNs = now;
                org.slf4j.LoggerFactory.getLogger("adapt-perf")
                        .error("sky occlusion failed: {}", t.toString());
            }
            return false;
        }
    }

    /**
 * 实体/玩家 caster：把主渲染已经画过的 solid 几何再画一遍进 shadow map。
 * <p>
 * <b>必须在主渲染 {@code executeSolid} 之后调用</b>（由
 * ReimaginedCloudPassMixin 在同一个 hook 里、延迟光照之前调）。上一版把它
 * 放在地形 caster 里（即主渲染之前），实测"动物看不到，但看到了它们的影子，
 * 位置还是对的" — 影子那份画成功了、本体那份没画。放到主渲染之后，
 * 最坏情况只是 shadow map 少一份实体（回到现状），本体一定在。
 * <p>
 * <b>为什么重复执行是安全的</b>（26.2 字节码逐条实证）：
 * <ul>
 * <li>{@code StagedVertexBuffer.getExecuteInfo(draw)} 是纯读 — 只读
 * {@code draw.vertexCount/vertexOffset/format/indexOffset/indexCount}
 * 与 {@code currentVertexBuffer}，不写任何字段。返回 null 的唯一条件是
 * {@code vertexCount == 0}，而 vertexCount 只在构建阶段写。</li>
 * <li>{@code RenderTypeFeatureRenderer.groups} 只在 {@code finishExecute}
 * 清空，而它只被 {@code PreparedFrame.close} 调用（整帧结束）。</li>
 * <li>{@code StagedVertexBuffer.endDraw}（清 draws + 置空
 * currentVertexBuffer）同样只在 {@code close/endFrame} 调用。</li>
 * </ul>
 * 这正是 的语义：同一批实体几何画两遍，一遍进 shadow map、一遍进主
 * target。blob shadow 已由 BlobShadowMixin 移除，方向影是实体唯一地面阴影。
 * <p>
 * 26.3：{@code PreparedFrame.executeSolid/Translucent} 改为接收外部
 * {@link RenderPass}，不再自建 pass，{@code RenderSystem.output*TextureOverride}
 * 已删除。这里直接开一张指向 shadow map 的 pass（颜色 LOAD / 深度 LOAD，
 * 保留地形 caster 已写入的深度），把实体 solid + translucentModels 画进去。
 * 管线由 AdaptRenderPassPipelineMixin（{@code getCompiledPipelineNullable}）
 * 在 {@code rendering=true} 时换成 caster 变体；ShadowFrame 由
 * AdaptRenderSystemMixin 在 {@code bindDefaultUniforms} 换成 caster 版本。
 * <p>
 * caster 的颜色写掩码是 WRITE_NONE，所以一起画进来的方块实体（箱子/告示牌）
 * 只贡献深度 — 它们本来就该投影。文字/名牌不在 solid 阶段
 * （{@code SubmitNodeCollection.texts/nameTags} 由 executeTranslucent 执行），
 * 不会污染 shadow map。
 */
    public static void renderEntityShadow(Object preparedFrame) {
        if (preparedFrame == null) {
            return;
        }
        ensureResources();
        AdaptRenderProfiler.begin(AdaptRenderProfiler.SHADOW_ENTITY);
        rendering = true;
        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Adapt entity shadow",
                    target.getColorTextureView(), Optional.empty(),
                    target.getDepthTextureView(), OptionalDouble.empty())) {
                java.lang.reflect.Method executeSolid = preparedFrame.getClass()
                        .getMethod("executeSolid", RenderPass.class);
                executeSolid.invoke(preparedFrame, pass);
                // 玩家走 translucentModels phase，不在 solid 里 — 必须单独执行。
                renderTranslucentModelShadow(preparedFrame, pass);
            }
        } catch (Throwable t) {
            long now = System.nanoTime();
            if (now - lastShadowFailLogNs > 3_000_000_000L) {
                lastShadowFailLogNs = now;
                org.slf4j.LoggerFactory.getLogger("adapt-perf")
                        .error("entity shadow caster failed: {}", t.toString());
            }
        } finally {
            rendering = false;
            AdaptRenderProfiler.end(AdaptRenderProfiler.SHADOW_ENTITY);
        }
    }

    /**
 * 把 {@code translucentModels} 这一个 phase 也画进 shadow map。
 * <p>
 * <b>玩家没影子的真正原因。</b>玩家用 {@code RenderTypes.entityTranslucent}
 * （AvatarRenderer 字节码 off.68），而 {@code SubmitNodeCollection.submitModel}
 * 按 {@code hasBlending} 分流（off.63-87）：有混合 → {@code translucentModels}，
 * 无混合 → {@code solid}。动物是 {@code entityCutoutNoCull}（无混合）落在
 * solid，所以只重跑 {@code executeSolid} 时动物有影子、玩家没有 —
 * 第一人称第三人称都一样，与 {@code Camera.isDetached} 无关
 * （我此前判断成 LevelExtractor 跳过相机实体，那是错的：第三人称也没影子，
 * 而第三人称的玩家确实在 entityRenderStates 里）。
 * <p>
 * <b>为什么不能直接调 {@code executeTranslucent}。</b>它还会执行
 * shadows / seeThroughNameTags / nameTags / texts / translucentCustomGeometry
 * 五个 phase（字节码 off.55-112）。caster 的颜色掩码是 WRITE_NONE 但<b>深度是写的</b>
 * — 名牌和文字是面向相机的平面 quad，写进 shadow map 会变成凭空的遮挡物，
 * 在地面投出一块矩形黑影。所以只挑 {@code translucentModels}。
 * <p>
 * 半透明模型当作不透明遮挡物是刻意的： 的 shadow caster 同样按
 * alpha cutout（{@code color.a < 0.1} discard）处理，玩家皮肤的不透明
 * 区域本来就该投影。
 */
    private static void renderTranslucentModelShadow(Object preparedFrame, RenderPass pass) {
        if (!(preparedFrame instanceof com.ylmac.adapt.mixins.render.PreparedFrameAccessor accessor)) {
            return;
        }
        net.minecraft.client.renderer.feature.FeatureFrameContext context = accessor.adapt$context();
        net.minecraft.client.renderer.SubmitNodeStorage storage = accessor.adapt$submitNodeStorage();
        if (context == null || storage == null) {
            return;
        }
        for (net.minecraft.client.renderer.SubmitNodeCollection collection
                : storage.getSubmitsPerOrder().values()) {
            accessor.adapt$executePhase(collection.translucentModels, context, pass);
        }
    }

    private static boolean renderSodiumTerrainShadow(CameraRenderState camera) {
        try {
            Class<?> rendererClass = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            Method instance = rendererClass.getMethod("instanceNullable");
            Object renderer = instance.invoke(null);
            if (renderer == null) return false;

            // u_ProjectionMatrix = lightProjection
            // u_ModelViewMatrix = lightView * T(camPos%2) (baked in lightViewProjection's rotation part)
            // Sodium geometry is world-relative (regionPos - camPos); lightViewProjection
            // already carries T(camPos%2) so position maps to light clip directly.
            Matrix4f modelView = new Matrix4f(lightViewProjection);
            // Strip projection: u_Globals has projection separate from modelView.
            // modelView should be lightView * T(align); projection = lightProjection.
            modelView.set(lightView).mul(new Matrix4f().translate(
                    (float) (camera.pos.x - Math.floor(camera.pos.x / 2.0) * 2.0),
                    (float) (camera.pos.y - Math.floor(camera.pos.y / 2.0) * 2.0),
                    (float) (camera.pos.z - Math.floor(camera.pos.z / 2.0) * 2.0)));

            Class<?> matricesClass = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices");
            Constructor<?> matricesCtor = matricesClass.getConstructor(org.joml.Matrix4fc.class, org.joml.Matrix4fc.class);
            Object matrices = matricesCtor.newInstance(lightProjection, modelView);
            Class<?> groupClass = net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup.class;
            Class<?> samplerClass = com.mojang.renderpearl.api.textures.GpuSampler.class;
            Method draw = rendererClass.getMethod("drawChunkLayer", groupClass, matricesClass,
                    double.class, double.class, double.class, samplerClass);
            draw.invoke(renderer, net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup.OPAQUE,
                    matrices, (double) camera.pos.x, (double) camera.pos.y, (double) camera.pos.z,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            return true;
        } catch (Throwable t) {
            long now = System.nanoTime();
            if (now - lastShadowFailLogNs > 3_000_000_000L) {
                lastShadowFailLogNs = now;
                org.slf4j.LoggerFactory.getLogger("adapt-perf")
                        .error("Sodium terrain shadow failed: {}", t.toString());
            }
            return false;
        }
    }

    public static void bindCasterUniforms(com.mojang.renderpearl.api.commands.RenderPass pass) {
        ensureResources();
        pass.setUniform("ShadowFrame", frameBuffer);
    }

    /** Bind shadow map + sky-occlusion map + frame UBO to a receiver pass. */
    public static void bindReceiver(com.mojang.renderpearl.api.commands.RenderPass pass) {
        ensureResources();
        pass.setUniform("ShadowFrame", frameBuffer);
        pass.setUniform("ShadowMapSampler", target.getDepthTextureView(), sampler);
        // 天光可见度图：lightmap.y 的自建替代（adaptSkyVisibility）
        pass.setUniform("SkyOcclusionSampler", skyTarget.getDepthTextureView(), sampler);
    }

    public static void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        if (skyTarget != null) {
            skyTarget.destroyBuffers();
            skyTarget = null;
        }
        if (frameBuffer != null) {
            frameBuffer.close();
            frameBuffer = null;
        }
        if (sampler != null) {
            sampler.close();
            sampler = null;
        }
        rendering = false;
    }

    private static void resetSodiumUniformGuard() {
        try {
            Class<?> managerClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager");
            java.lang.reflect.Field guard = managerClass.getDeclaredField("hasUpdatedThisFrame");
            guard.setAccessible(true);
            Class<?> rendererClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            java.lang.reflect.Method instance = rendererClass.getMethod("instanceNullable");
            instance.setAccessible(true);
            Object renderer = instance.invoke(null);
            if (renderer == null) return;
            java.lang.reflect.Field managerField = rendererClass.getDeclaredField("uniformBufferManager");
            managerField.setAccessible(true);
            Object uniforms = managerField.get(renderer);
            if (uniforms != null) {
                guard.setBoolean(uniforms, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureResources() {
        if (target == null) {
            target = new TextureTarget("adaptclient_directional_shadow", RESOLUTION, RESOLUTION,
                    GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }
        if (skyTarget == null) {
            skyTarget = new TextureTarget("adaptclient_sky_occlusion", SKY_RESOLUTION, SKY_RESOLUTION,
                    GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }
        if (frameBuffer == null) {
            frameBuffer = RenderSystem.getDevice().createBuffer(() -> "Adapt ShadowFrame UBO",
                    com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_DST
                            | com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_UNIFORM,
                    FRAME_SIZE);
            FRAME.clear();
            lightViewProjection.identity();
            lightViewProjection.get(0, FRAME);
            new Matrix4f().identity().get(64, FRAME);
            FRAME.putFloat(128, 1.0f / RESOLUTION);
            FRAME.putFloat(132, SHADOW_DEPTH_PER_BLOCK);
            FRAME.putFloat(136, SHADOW_HALF_EXTENT * 2.0f / RESOLUTION);
            FRAME.putFloat(140, SHADOW_DISTORTION);
            // 天光字段的安全初值：updateFrame 尚未跑过时（首帧/世界切换）
            // 单位矩阵 + 非零 texel，让 receiver 拿到"全天光可见"而不是 NaN。
            new Matrix4f().identity().get(144, FRAME);
            FRAME.putFloat(208, 1.0f / SKY_RESOLUTION);
            FRAME.putFloat(212, SKY_DEPTH_PER_BLOCK);
            FRAME.putFloat(216, SKY_HALF_EXTENT);
            FRAME.putFloat(220, SKY_DEPTH_PER_BLOCK);
            uploadFrame();
        }
        if (sampler == null) {
            // LINEAR: 的 sampler2DShadow 双线性硬件 PCF — 手动 3x3 PCF
            // + NEAREST 会产生硬边缘条纹（banding）。
            sampler = RenderSystem.getDevice().createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
        }
    }

    private static void uploadFrame() {
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(frameBuffer.slice(), FRAME.clear());
    }

    private static final class CommandEncoderHolder {
        private static void clear(TextureTarget shadow) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    shadow.getColorTexture(), new Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
                    shadow.getDepthTexture(), 1.0);
        }
    }
}
