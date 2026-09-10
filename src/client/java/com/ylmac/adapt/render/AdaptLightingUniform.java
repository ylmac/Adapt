package com.ylmac.adapt.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Beryl 式统一光照 UBO（AdaptLighting）的 CPU 侧提供者。
 * <p>
 * 布局严格对应 assets/adaptclient/shaders/include/adapt_lighting.glsl 中的
 * {@code layout(std140) uniform AdaptLighting}：
 * <pre>
 * 0   vec3  SunDirection        视空间太阳方向      12  float SunIntensity
 * 16  vec3  SunColor            太阳颜色           28  pad
 * 32  vec3  AmbientColor        环境光颜色         44  pad
 * 48  float AmbientLightFactor / NightFactor / MinAmbientLight / HaloLightCount
 * 64  vec4  HaloLightPos[MAX_HALO_LIGHTS]    xyz=视空间位置 w=半径
 * 576 vec4  HaloLightColor[MAX_HALO_LIGHTS]  rgb=颜色 a=强度
 * 1088 vec4  UpVector 1104 vec4 SkyFactors0 1120/1136/1152 vec4 ViewRotCol0-2
 * </pre>
 * 后置偏移随 MAX_HALO_LIGHTS 伸缩（见本类偏移常量），shader 端布局必须同步。
 * 太阳方向/颜色/环境光的计算逐行移植自 Beryl RenderingPipeline#updateLight
 * （celestialAngle 窗口、NightMultiplier、sunset lerp、LightVisibility=1-rain），
 * 光环点光源数组是本项目在 Beryl 之上的扩展，为实体 GGX 光照提供局部光源。
 */
public final class AdaptLightingUniform {

    public static final int MAX_HALO_LIGHTS = 32;
    // std140 偏移：头部 64 字节 + 两个灯数组（每灯 16+16=32 字节）。
    private static final int POS_BASE = 64;
    private static final int COLOR_BASE = 576; // 64 + MAX_HALO_LIGHTS*16
    private static final int UP_OFFSET = 1088; // COLOR_BASE + MAX_HALO_LIGHTS*16
    private static final int SKY_OFFSET = 1104;
    private static final int VIEW_COL0 = 1120;
    private static final int VIEW_COL1 = 1136;
    private static final int VIEW_COL2 = 1152;
    /**
     * 投影矩阵（mat4，std140 = 64 字节）：延迟光照 pass 的屏幕空间点光阴影
     * （SSRT）需要把视空间位置投影回屏幕 UV 才能采 G-buffer 做遮挡步进。
     */
    private static final int PROJ_OFFSET = 1168;
    /** x=屏幕宽 y=屏幕高 z=SSRT 步数 w=保留 */
    private static final int SCREEN_OFFSET = 1232;
    /**
     * 体素遮挡场原点（vec4：xyz=世界坐标原点，w=网格边长）。方块光阴影用
     * 世界空间体素 DDA 而非屏幕空间步进 —— shader 需要它把世界坐标换算成
     * 体素索引。见 {@link VoxelOcclusionField}。
     */
    private static final int VOXEL_ORIGIN_OFFSET = 1248;
    /**
     * 随机采样参数（vec4：x=帧序号）。MegaLights 的时空蓝噪声需要帧序号
     * 参与，否则噪点在时间上固定，降噪器吃不掉。
     */
    private static final int SAMPLE_OFFSET = 1264;
    /**
     * 上一帧的 view-projection（mat4）与相机位移（vec4）。
     * 时间累积必须重投影：同像素比较在相机旋转时抓不住几何变化
     * （旋转前后深度可能接近），实测表现为"以前的画面和现在的画面堆在一起，
     * 只有站在原地很久才会收敛"。
     */
    private static final int PREV_VIEWPROJ_OFFSET = 1280;
    /** xyz = 上一帧相机位置 - 本帧相机位置，w = 历史是否有效。 */
    private static final int CAM_DELTA_OFFSET = 1344;
    /**
     * 光源簇网格参数（vec4：x=深度切片数，y=每片厚度/格）。
     * shader 用 {@code slice = -fragPos.z / thickness} 定位自己所在的切片。
     */
    private static final int CLUSTER_OFFSET = 1360;
    private static final int SIZE = 1376;   // CLUSTER_OFFSET + 16
    /**
     * 体素场可视化（{@code -Dadapt.debugVoxel=1..3}）。
     * <ul>
     *   <li>1 = 片元所在体素的有效 AABB 槽位数（黑=空气 → 红=1 → 黄=4 → 白=8）</li>
     *   <li>2 = 第一个 AABB 的尺寸（RGB = xyz 边长 / 1 格）</li>
     *   <li>3 = 第一个 AABB 的 min 角（RGB = xyz / 1 格）</li>
     * </ul>
     * 用来分辨"栅栏那一格存的是柱+杆还是整格实心" —— 之前两轮判断都是从
     * 形状数据反推的，缺的就是这一条实测。
     */
    private static final float DEBUG_VOXEL = Integer.getInteger("adapt.debugVoxel", 0);
    /** 体素 DDA 全质量的视距上限（格）。 */
    private static final float VOXEL_NEAR_DISTANCE = 48.0f;
    /** 体素 DDA 完全跳过的视距（格）。之间线性压缩步数上限。 */
    private static final float VOXEL_FAR_DISTANCE = 96.0f;
    private static final float SUN_PATH_ROTATION = 20.0f;
    private static int frameIndex;
    /** 上一帧的 view-projection，供重投影使用。 */
    private static final Matrix4f prevViewProjection = new Matrix4f();
    private static boolean prevValid;
    private static double prevCamX;
    private static double prevCamY;
    private static double prevCamZ;

    private static final ByteBuffer DATA = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.nativeOrder());
    private static GpuBuffer buffer;

    private final Vector3f sunDirection = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f sunColor = new Vector3f(1.0f, 1.0f, 1.0f);
    private final Vector3f ambientColor = new Vector3f(0.6f, 0.65f, 0.75f);
    private float sunIntensity = 1.0f;
    private float ambientLightFactor = 0.3f;
    private float nightFactor;
    private float minAmbientLight = 0.01f;
    private float sunAngleDegrees;
    private float starAngleRadians;
    private int moonPhaseOrdinal;
    private final List<AdaptLightSources.HaloSource> viewSpaceLights = new ArrayList<>();

    private static final AdaptLightingUniform INSTANCE = new AdaptLightingUniform();

    private AdaptLightingUniform() {
    }

    public static AdaptLightingUniform get() {
        return INSTANCE;
    }

    /**
     * 每帧调用一次（渲染线程）。把 Beryl 式太阳/环境光与光环点光源写入 UBO。
     * 即使 Halo 模块关闭也必须调用：实体管线已全局替换为 Beryl 光照，
     * 太阳/环境光数据是所有实体渲染的必需输入。
     */
    public void updateFrame(ClientLevel level, CameraRenderState camera, float partialTicks,
                            List<AdaptLightSources.HaloSource> sources) {
        Minecraft mc = Minecraft.getInstance();
        if (level == null || camera == null) {
            writeDisabled();
            upload();
            return;
        }

        Matrix4f viewRotation = camera.viewRotationMatrix != null
                ? new Matrix4f(camera.viewRotationMatrix)
                : new Matrix4f().rotation(camera.orientation != null ? camera.orientation : new org.joml.Quaternionf());

        EnvironmentAttributeProbe probe = Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe();
        sunAngleDegrees = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks);
        starAngleRadians = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partialTicks)
                * (float) (Math.PI / 180.0);
        moonPhaseOrdinal = probe.getValue(EnvironmentAttributes.MOON_PHASE, partialTicks).ordinal();

        updateSun(level, camera, partialTicks, viewRotation, mc);
        updateHaloLights(camera, viewRotation, sources);

        writeDisabled();
        // 覆盖太阳与环境光
        DATA.putFloat(0, sunDirection.x);
        DATA.putFloat(4, sunDirection.y);
        DATA.putFloat(8, sunDirection.z);
        DATA.putFloat(12, sunIntensity);
        DATA.putFloat(16, sunColor.x);
        DATA.putFloat(20, sunColor.y);
        DATA.putFloat(24, sunColor.z);
        DATA.putFloat(32, ambientColor.x);
        DATA.putFloat(36, ambientColor.y);
        DATA.putFloat(40, ambientColor.z);
        DATA.putFloat(48, ambientLightFactor);
        DATA.putFloat(52, nightFactor);
        DATA.putFloat(56, minAmbientLight);
        // 光源列表改由 LightListBuffer（texel buffer）承载，不再受 UBO 定长
        // 数组的 32 上限约束。HaloLightCount 是标量，可以是任意值。
        // UBO 里的 HaloLightPos/Color 数组保留但**只填前 MAX_HALO_LIGHTS 个**，
        // 供仍在用它的前向 shader（sodium_terrain.fsh / adapt_entity.fsh）过渡使用；
        // 延迟光照 pass 走 texel buffer 全量。
        LightListBuffer.upload(viewSpaceLights);
        DATA.putFloat(60, LightListBuffer.liveCount());
        var window = mc.getWindow();
        LightTileGrid.rebuild(viewSpaceLights, camera.projectionMatrix,
                window.getWidth(), window.getHeight());
        int count = Math.min(viewSpaceLights.size(), MAX_HALO_LIGHTS);
        // <b>UBO 数组必须装"最近的 N 盏"，不能装"提交顺序的前 N 盏"。</b>
        // <p>
        // 前向管线（adapt_entity.fsh / sodium_terrain.fsh）读的是 UBO 里的
        // HaloLightPos[32]，而 viewSpaceLights 的顺序是 BlockLightSourceCache
        // 按 section 索引扫出来的（sx/sz/sy 三重循环，从相机减去渲染距离开始）
        // —— 那是**空间扫描顺序**，第 0 盏灯在视距边缘的角上。实测缓存里有
        // 1000+ 盏灯，前 32 盏几乎全在几百格外，`dist >= radius` 全部落空 →
        // 实体拿到的局部光恒为 0（实测"很久没有看到光环对生物的打光了"）。
        // <p>
        // 延迟路径不受影响（走 texel buffer 全量 + 簇网格），所以地形一直是对的，
        // 只有实体这条前向路径瞎。按视距部分选择前 32 盏即可 —— 实体只需要
        // 附近的灯，远处的灯对它本来也没贡献。
        List<AdaptLightSources.HaloSource> nearest = viewSpaceLights;
        if (viewSpaceLights.size() > MAX_HALO_LIGHTS) {
            nearest = new java.util.ArrayList<>(viewSpaceLights);
            nearest.sort((a, b) -> Float.compare(
                    a.viewX() * a.viewX() + a.viewY() * a.viewY() + a.viewZ() * a.viewZ(),
                    b.viewX() * b.viewX() + b.viewY() * b.viewY() + b.viewZ() * b.viewZ()));
        }
        for (int i = 0; i < count; i++) {
            AdaptLightSources.HaloSource light = nearest.get(i);
            int base = POS_BASE + i * 16;
            DATA.putFloat(base, light.viewX());
            DATA.putFloat(base + 4, light.viewY());
            DATA.putFloat(base + 8, light.viewZ());
            DATA.putFloat(base + 12, light.radius());
            int colorBase = COLOR_BASE + i * 16;
            DATA.putFloat(colorBase, light.red());
            DATA.putFloat(colorBase + 4, light.green());
            DATA.putFloat(colorBase + 8, light.blue());
            DATA.putFloat(colorBase + 12, light.strength());
        }
        // Beryl UpVector：视空间世界上方向（地形地平线衰减/地面反弹用）
        Vector4f upVec4 = new Vector4f(0.0f, 1.0f, 0.0f, 0.0f).mul(viewRotation);
        DATA.putFloat(UP_OFFSET, upVec4.x());
        DATA.putFloat(UP_OFFSET + 4, upVec4.y());
        DATA.putFloat(UP_OFFSET + 8, upVec4.z());
        // Complementary Reimagined 天空因子：雨强 / timeAngle（SUN_ANGLE 0=正午 → +0.25 对齐
        // Complementary worldTime 语义：正午 0.25、午夜 0.75）/ 月相 ordinal（与 Iris 编码一致）
        DATA.putFloat(SKY_OFFSET, level.getRainLevel(partialTicks));
        DATA.putFloat(SKY_OFFSET + 4, (sunAngleDegrees * 0.0027777778f + 0.25f) % 1.0f);
        DATA.putFloat(SKY_OFFSET + 8, moonPhaseOrdinal);
        DATA.putFloat(SKY_OFFSET + 12, starAngleRadians); // vanilla SkyRenderer starAngle（云高度由 CloudFrame UBO 独立提供）
        // 视图旋转矩阵三列。26.2 Camera.getViewRotationMatrix =
        // rotation.conjugate() —— 对 rotationYXZ(PI-yaw,-pitch,0) 构造的相机
        // 旋转（世界→视图）取共轭后 = 视图→世界。所以 viewRotationMatrix
        // 本身就是"视图→世界"，GLSL 里 ViewRotCol0*x + ViewRotCol1*y +
        // ViewRotCol2*z = viewRotationMatrix * dir 直接把视空间方向转世界。
        // 绝不能 invert（那会把星星/月亮方向变反、随视角转动）。
        DATA.putFloat(VIEW_COL0, viewRotation.m00());
        DATA.putFloat(VIEW_COL0 + 4, viewRotation.m10());
        DATA.putFloat(VIEW_COL0 + 8, viewRotation.m20());
        DATA.putFloat(VIEW_COL1, viewRotation.m01());
        DATA.putFloat(VIEW_COL1 + 4, viewRotation.m11());
        DATA.putFloat(VIEW_COL1 + 8, viewRotation.m21());
        DATA.putFloat(VIEW_COL2, viewRotation.m02());
        DATA.putFloat(VIEW_COL2 + 4, viewRotation.m12());
        DATA.putFloat(VIEW_COL2 + 8, viewRotation.m22());
        // 投影矩阵 + 屏幕尺寸：延迟光照 pass 的点光阴影（SSRT）用它把视空间
        // 位置投回屏幕 UV，沿"片元→光源"在屏幕空间步进采 G-buffer 深度判遮挡。
        if (camera.projectionMatrix != null) {
            camera.projectionMatrix.get(PROJ_OFFSET, DATA);
        } else {
            new Matrix4f().get(PROJ_OFFSET, DATA);
        }
        DATA.putFloat(SCREEN_OFFSET, window.getWidth());
        DATA.putFloat(SCREEN_OFFSET + 4, window.getHeight());
        DATA.putFloat(SCREEN_OFFSET + 8, LightTileGrid.GRID);
        DATA.putFloat(SCREEN_OFFSET + 12, 0.0f);
        // 簇网格的深度维：shader 用 -fragPos.z / thickness 定位切片。
        DATA.putFloat(CLUSTER_OFFSET, LightTileGrid.DEPTH_SLICES);
        DATA.putFloat(CLUSTER_OFFSET + 4, LightTileGrid.SLICE_THICKNESS);
        DATA.putFloat(CLUSTER_OFFSET + 8, 0.0f);
        DATA.putFloat(CLUSTER_OFFSET + 12, 0.0f);
        AdaptRenderProfiler.recordLights(
                BlockLightSourceCache.cachedCount(),
                viewSpaceLights.size(),
                LightListBuffer.liveCount());
        // 体素遮挡场原点：每帧由 VoxelOcclusionField.update 决定（按 16 格对齐，
        // 玩家在同一对齐格内移动不重建）。
        // 存的是**相对相机**的原点（originWorld - cameraPos），不是绝对世界坐标：
        // 1) G-buffer 的 adaptWorldPos 本身就是 regionPos - camPos，相减即得体素
        //    坐标，shader 不需要再拿相机绝对位置；
        // 2) 绝对世界坐标在远离原点时（±3000 万格）float 精度不足，相对坐标恒小。
        // w=边长，shader 用它做索引展平与边界检查。
        VoxelOcclusionField.update(level, camera.pos);
        DATA.putFloat(VOXEL_ORIGIN_OFFSET, (float) (VoxelOcclusionField.originX() - camera.pos.x));
        DATA.putFloat(VOXEL_ORIGIN_OFFSET + 4, (float) (VoxelOcclusionField.originY() - camera.pos.y));
        DATA.putFloat(VOXEL_ORIGIN_OFFSET + 8, (float) (VoxelOcclusionField.originZ() - camera.pos.z));
        DATA.putFloat(VOXEL_ORIGIN_OFFSET + 12, VoxelOcclusionField.SIZE);
        // 帧序号：时空蓝噪声用。64 帧循环足够让降噪器的时间窗口去相关，
        // 又不会因为无界增长丢掉 float 精度。
        frameIndex = (frameIndex + 1) & 63;
        DATA.putFloat(SAMPLE_OFFSET, frameIndex);
        // y = 体素场调试可视化模式（-Dadapt.debugVoxel=1..3）。
        // 走 UBO 而不是启动参数直读：shader 侧要用，且不用改布局。
        DATA.putFloat(SAMPLE_OFFSET + 4, DEBUG_VOXEL);
        // z/w = 体素 DDA 的距离分档阈值（格）。近处全质量，中距离压步数，
        // 远处直接跳过（AC Shadows 的 1/4 分辨率 1 ray 同构思路：远处的
        // 局部光阴影细节看不见，不该付全价）。
        DATA.putFloat(SAMPLE_OFFSET + 8, VOXEL_NEAR_DISTANCE);
        DATA.putFloat(SAMPLE_OFFSET + 12, VOXEL_FAR_DISTANCE);

        // ---- 时间重投影数据 ----
        // GB4 存的是世界相对位置（regionPos - 本帧相机）。要投到上一帧屏幕，
        // 先加回相机位移变成"相对上一帧相机"，再乘上一帧的 VP。
        prevViewProjection.get(PREV_VIEWPROJ_OFFSET, DATA);
        DATA.putFloat(CAM_DELTA_OFFSET, (float) (prevCamX - camera.pos.x));
        DATA.putFloat(CAM_DELTA_OFFSET + 4, (float) (prevCamY - camera.pos.y));
        DATA.putFloat(CAM_DELTA_OFFSET + 8, (float) (prevCamZ - camera.pos.z));
        DATA.putFloat(CAM_DELTA_OFFSET + 12, prevValid ? 1.0f : 0.0f);

        // 记录本帧的 VP 供下一帧用。
        //
        // viewRotationMatrix = Camera.rotation().conjugate() 构成的旋转矩阵，
        // 它本身就是**世界→视图**（Camera.getViewRotationMatrix 字节码：
        // rotation() 取共轭后 Matrix4f.rotation(q)）。所以直接乘投影即可。
        //
        // 之前这里多做了一次 transpose（当成视图→世界），PrevViewProjection
        // 整个错，shader 重投影得到的 UV 是乱的；深度判据偶尔碰巧通过就把
        // 无关位置的历史混进来，相机一转矩阵变、混入位置跟着转 —— 实测
        // "会成圆形旋转，好多影子好多光"。
        //
        // 注意 GLSL 里 ViewRotCol0/1/2 存的是 m00,m10,m20 等（JOML 的 mRC 是
        // m[列][行]，所以那三组其实是**行**），用行做基相当于乘 Mᵀ，
        // 即视图→世界 —— 那边的用法是对的，不要跟这里混淆。
        if (camera.projectionMatrix != null) {
            prevViewProjection.set(camera.projectionMatrix).mul(viewRotation);
            prevValid = true;
        } else {
            prevValid = false;
        }
        prevCamX = camera.pos.x;
        prevCamY = camera.pos.y;
        prevCamZ = camera.pos.z;
        upload();
    }

    /** Beryl RenderingPipeline#updateLight 的太阳/环境光部分逐行移植。 */
    private void updateSun(ClientLevel level, CameraRenderState camera, float partialTicks,
                           Matrix4f viewRotation, Minecraft mc) {
        EnvironmentAttributeProbe probe = mc.gameRenderer.mainCamera().attributeProbe();
        float celestialAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks) * 0.0027777778f;
        boolean endDim = level.dimension().identifier().getPath().contains("end");
        boolean netherDim = level.dimension().identifier().getPath().contains("nether");

        // P1：断开 gamma→光照耦合（用户实机："调 Sodium 亮度设置（=原版
        // options.gamma）仍能改变黑房亮度"——这条链是 Adapt 自己把 gamma 接进
        // Reimagined 暗部抬升（vsBrightness → minLighting/夜间光色），不是
        // Sodium 的 lightmap 纹理残留）。Adapt 光照必须完全不受"钠/原版亮度
        // 滑块"影响：固定为 r5 基准 gamma=0.5 时的值（0.5*0.05+0.01=0.035）。
        minAmbientLight = 0.035f;

        float nightMultiplier;
        if (celestialAngle > 0.3f && celestialAngle < 0.7f) {
            celestialAngle += 0.5f;
            nightMultiplier = 1.0f;
        } else if (celestialAngle >= 0.25f && celestialAngle <= 0.3f) {
            nightMultiplier = clamp01((celestialAngle - 0.25f) / 0.05f);
        } else if (celestialAngle >= 0.7f && celestialAngle <= 0.75f) {
            nightMultiplier = clamp01(1.0f - (celestialAngle - 0.7f) / 0.05f);
        } else {
            nightMultiplier = 0.0f;
        }
        if (endDim) {
            celestialAngle = 0.0f;
        }
        nightFactor = nightMultiplier;

        // 太阳/月亮方向的**单一真值**：与 shadow map（AdaptShadowMap.updateFrame）、
        // 天空穹（adapt_sky.fsh）、云（ReimaginedCloudRenderer）完全同一个公式。
        //   timeAngle = (sunAngleDeg / 360 + 0.25) % 1
        //   elevation = (timeAngle - 0.25) * 2π
        //   worldSun  = (sin elevation, cos elevation, 0)
        // 旧实现用 Rx(90)·Ry(90)·Rz(celestial·-360)·Rx(-20) 的旋转链取第三列，
        // 与 shadow map 的 Rx(90)·Rz(...)·Rx(-20) 差 90° 偏航、与天空公式差
        // 20° 倾斜 → shadow map 投影方向、N·L 方向、可见太阳位置三者互不一致，
        // 影子错位（实机实证）。celestialAngle 已含夜间 +0.5 翻转，方向自动
        // 在夜间切到月亮，保持 Beryl "lightVec 昼夜翻转" 语义。
        float timeAngle = (celestialAngle + 0.25f) % 1.0f;
        float elevation = (timeAngle - 0.25f) * (float) (Math.PI * 2.0);
        Vector3f lightDirWS = new Vector3f(
                (float) Math.sin(elevation), (float) Math.cos(elevation), 0.0f).normalize();
        Vector3f lightDir = viewRotation.transformDirection(new Vector3f(lightDirWS)).normalize();

        Vector4f upVec4 = new Vector4f(0.0f, 1.0f, 0.0f, 0.0f).mul(viewRotation);
        Vector3f upVec = new Vector3f(upVec4.x(), upVec4.y(), upVec4.z());
        float ldotUp = Math.max(upVec.dot(lightDir), 0.0f);

        float rainLevel = level.getRainLevel(partialTicks);
        float thunderLevel = level.getThunderLevel(partialTicks);
        float lightVisibility = 1.0f - rainLevel;
        if (netherDim || endDim) {
            lightVisibility = 0.0f;
        }
        float lightIntensity = (0.2f + 0.8f * lightVisibility) * (0.5f + 0.5f * (1.0f - thunderLevel));

        float r;
        float g;
        float b;
        if (nightMultiplier >= 1.0f) {
            ambientLightFactor = 0.5f * 0.3f;
            r = g = b = 0.2f * lightIntensity;
        } else {
            ambientLightFactor = (ldotUp < 0.25f ? ldotUp * 2.0f + 0.5f : 1.0f) * 0.3f;
            float sunsetFactor = (1.0f - ldotUp) * 1.1f;
            sunsetFactor *= sunsetFactor;
            sunsetFactor = Math.min(sunsetFactor, 1.0f);
            float i = 8.0f * (1.0f - nightMultiplier) * lightIntensity;
            i = lerp(1.0f, 0.9f, sunsetFactor) * i;
            float sunsetFactor2 = sunsetFactor * (1.0f - rainLevel);
            r = lerp(1.0f, 0.75f, sunsetFactor2) * i;
            g = lerp(0.9f, 0.27f, sunsetFactor2) * i;
            b = lerp(0.8f, 0.15f, sunsetFactor2) * i;
        }
        ambientColor.set(0.6f, 0.65f, 0.75f);
        if (endDim) {
            r = 0.9f * 3.0f;
            g = 0.7f * 3.0f;
            b = 1.0f * 3.0f;
            ambientColor.set(0.7f, 0.35f, 0.7f);
        }
        sunColor.set(r, g, b);
        sunIntensity = 1.0f;
        sunDirection.set(lightDir);
    }

    private void updateHaloLights(CameraRenderState camera, Matrix4f viewRotation,
                                  List<AdaptLightSources.HaloSource> sources) {
        viewSpaceLights.clear();
        if (sources.isEmpty()) {
            return;
        }
        Vec3 camPos = camera.pos != null ? camera.pos : Vec3.ZERO;
        for (AdaptLightSources.HaloSource source : sources) {
            double dx = source.worldX() - camPos.x;
            double dy = source.worldY() - camPos.y;
            double dz = source.worldZ() - camPos.z;
            Vector3f viewPos = viewRotation.transformDirection(new Vector3f((float) dx, (float) dy, (float) dz));
            viewSpaceLights.add(new AdaptLightSources.HaloSource(
                    source.worldX(), source.worldY(), source.worldZ(),
                    viewPos.x, viewPos.y, viewPos.z,
                    source.red(), source.green(), source.blue(),
                    source.radius(), source.strength()));
        }
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private static float lerp(float from, float to, float delta) {
        return from + (to - from) * delta;
    }

    /** 光源关闭/无相机时的保守默认：正上方白光 + 夜间环境光。 */
    private void writeDisabled() {
        DATA.clear();
        DATA.putFloat(0, 0.0f);
        DATA.putFloat(4, 1.0f);
        DATA.putFloat(8, 0.0f);
        DATA.putFloat(12, 1.0f);
        DATA.putFloat(16, 1.0f);
        DATA.putFloat(20, 1.0f);
        DATA.putFloat(24, 1.0f);
        DATA.putFloat(32, 0.6f);
        DATA.putFloat(36, 0.65f);
        DATA.putFloat(40, 0.75f);
        DATA.putFloat(48, 0.3f);
        DATA.putFloat(52, 0.0f);
        DATA.putFloat(56, 0.01f);
        DATA.putFloat(60, 0.0f);
        DATA.putFloat(UP_OFFSET, 0.0f);
        DATA.putFloat(UP_OFFSET + 4, 1.0f);
        DATA.putFloat(UP_OFFSET + 8, 0.0f);
        DATA.putFloat(SKY_OFFSET, 0.0f);  // rain
        DATA.putFloat(SKY_OFFSET + 4, 0.25f); // timeAngle = 正午
        DATA.putFloat(SKY_OFFSET + 8, 0.0f);  // moonPhase = FULL
        DATA.putFloat(SKY_OFFSET + 12, starAngleRadians); // vanilla SkyRenderer starAngle（云高度由 CloudFrame UBO 独立提供）
        // 视图旋转默认单位阵
        DATA.putFloat(VIEW_COL0, 1.0f); DATA.putFloat(VIEW_COL0 + 4, 0.0f); DATA.putFloat(VIEW_COL0 + 8, 0.0f);
        DATA.putFloat(VIEW_COL1, 0.0f); DATA.putFloat(VIEW_COL1 + 4, 1.0f); DATA.putFloat(VIEW_COL1 + 8, 0.0f);
        DATA.putFloat(VIEW_COL2, 0.0f); DATA.putFloat(VIEW_COL2 + 4, 0.0f); DATA.putFloat(VIEW_COL2 + 8, 1.0f);
        // 簇参数清零：shader 看到 slices=0 就走非簇路径，count=HaloLightCount=0
        // → 直接返回黑。否则会拿上一帧的行指针去索引一个没更新的 LightTiles。
        DATA.putFloat(CLUSTER_OFFSET, 0.0f);
        DATA.putFloat(CLUSTER_OFFSET + 4, 0.0f);
    }

    private void upload() {
        if (buffer == null) {
            // 136 = USAGE_COPY_DST | USAGE_UNIFORM，与原版 GlobalSettingsUniform 一致
            buffer = RenderSystem.getDevice().createBuffer(
                    () -> "AdaptLighting UBO", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, SIZE);
        }
        DATA.clear();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), DATA);
    }

    /** 在任意 RenderPass 上绑定 UBO；管线未声明该 uniform 时后端会忽略。 */
    public static void bind(RenderPass pass) {
        if (buffer == null) {
            // 136 = USAGE_COPY_DST | USAGE_UNIFORM，与原版 GlobalSettingsUniform 一致
            buffer = RenderSystem.getDevice().createBuffer(
                    () -> "AdaptLighting UBO", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, SIZE);
            DATA.clear();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), DATA);
        }
        pass.setUniform("AdaptLighting", buffer);
    }

    public static void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
