package com.ylmac.adapt.injection.mixins;

import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.model.light.data.QuadLightData;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.model.SodiumShadeMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 清除 Sodium 的 CPU 侧光照计算结果，让 Adapt shader 成为唯一光照消费者。
 * BlockState/模型/UV/剔面数据完整保留。
 * <p>
 * 关键区分（用户规则"把原版处理光照的部分置空，按需要再加回来"）：
 * <ul>
 *   <li>{@code QuadLightData.br}（原版方块面 shade：顶/侧/底固定衰减）
 *       与 {@code QuadLightData.lm}（Sodium 平滑光照插值结果）——都是
 *       <b>原版算出来的光照</b>，全部清空。</li>
 *   <li>vertex <b>sky light</b> 等级——是<b>世界数据</b>（这一格有多少天空
 *       可见度），不是原版光照计算。Beryl/Complementary Reimagined 自己就要
 *       读它做天光遮蔽：r5 mainLighting.glsl 的
 *       {@code skyLightShadowMult = pow2(pow2(lightmapY²))} 与
 *       {@code ambientMult = smoothstep1(lightmapY)} 都由它驱动。清零 = 告诉
 *       shader"全世界都在地底" → {@code shadowLightMult = 0} →
 *       <b>太阳光完全无作用、方向阴影被乘成 0 看不见</b>（实机实证）。必须保留。</li>
 *   <li>vertex <b>block light</b> 等级——同为世界数据，但方块光已由 Adapt 的
 *       真实点光源系统（BlockLightSourceCache → HaloLightPos/Color UBO →
 *       adaptHaloLights）完整接管，不需要 lightmap 语义，清空。</li>
 * </ul>
 * Sodium 编码（CompactChunkVertex.encodeLight，javap 实证）：
 * {@code sky = ((light >> 16) & 255)}、{@code block = ((light >> 0) & 255)}
 * —— 掩码 {@code 0xFFFF0000} 即"保留天光、清空方块光"。
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext", remap = false)
public abstract class SodiumEmptyLightBakeMixin {

    /** The actual Sodium scratch arrays used by shadeQuad and bufferQuad. */
    @Shadow(remap = false)
    protected QuadLightData quadLightData;

    /**
     * shadeQuad 已经算完 QuadLightData 与 quad 顶点 light。在
     * BlockRenderer.bufferQuad 读取之前清掉原版光照计算结果，但保留
     * sky light 通道（世界数据，光影自己的输入）。
     * <p>
     * 处理器必须携带目标的完整参数列表（MutableQuadViewImpl, LightMode,
     * boolean, SodiumShadeMode）—— Mixin CallbackInjector.checkDescriptor
     * 只接受完整参数匹配或仅 CallbackInfo，中间前缀会抛
     * InvalidInjectionException（对 CallbackInjector$Callback.checkDescriptor
     * 字节码验证过）。
     */
    @Inject(method = "shadeQuad", at = @At("RETURN"), remap = false)
    private void adapt$zeroLightBake(MutableQuadViewImpl quad, LightMode lightMode,
                                     boolean hasBlockLight, SodiumShadeMode shadeMode,
                                     CallbackInfo ci) {
        // 光影总开关关闭 → 保留 Sodium 的原版光照烘焙（原版 AO / 方块光 /
        // 面 shade 全部照常）。清空它而 shader 又不消费 = 世界变成无光照平板。
        if (!com.ylmac.adapt.render.AdaptRenderToggle.isWorldEffectsEnabled()) {
            return;
        }
        QuadLightData data = this.quadLightData;
        if (data == null) {
            return;
        }
        for (int i = 0; i < 4; ++i) {
            // 原版方块面 shade（顶亮/侧暗/底最暗的固定衰减）：Adapt 用 r5 的
            // directionShade（法线驱动）代替，这里必须中和成 1.0。
            data.br[i] = 1.0f;
            // Sodium 平滑光照插值结果：Adapt 不消费，清空。
            data.lm[i] = 0;
            // 顶点 lightmap：保留 sky light（高 16 位）、清空 block light
            // （低 16 位）。block light 由 halo 真实点光源系统提供。
            quad.setLight(i, quad.getLight(i) & 0xFFFF0000);
        }
    }
}
