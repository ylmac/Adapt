package com.ylmac.adapt.mixins.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import com.ylmac.adapt.render.lighting.AdaptShadowMap;

/**
 * 禁用 Sodium 的平滑光照（AO）顶点烘焙。
 *
 * / 风格渲染没有 CPU 端 AO：Sodium 把 AO 乘进 a_Color.rgb
 * （CompactChunkVertex.packColor: ColorARGB.mulRGB(color, ao)），导致方块
 * 面之间出现原版平滑光照渐变。
 *
 * 注入 AbstractBlockRenderContext.prepareAoInfo（决定 LightMode 的地方）：
 * 必须在 RETURN 同时强制 useAmbientOcclusion=false 且 defaultLightMode=FLAT。
 * 只改 useAmbientOcclusion 不够 — processQuad 里 aoMode==DEFAULT 时用
 * this.defaultLightMode（prepareAoInfo 已按旧 useAmbientOcclusion 算成 SMOOTH）。
 * FLAT 保留 cardinalLighting 面 shade（朝下的面暗），无 AO 渐变。
 *
 * sodium 不在编译类路径（本项目的 sodium 交互统一走字符串 targets + 反射，
 * 见 SodiumShaderPipelineMixin/AdaptShadowMap），因此 defaultLightMode 的
 * LightMode 枚举无法编译期引用，这里用反射写入 FLAT。
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext", remap = false)
public abstract class SodiumAoDisableMixin {

    @Shadow
    protected boolean useAmbientOcclusion;

    /** AbstractBlockRenderContext.defaultLightMode（sodium 类型，仅反射访问）。 */
    private static Field defaultLightModeField;
    private static Object lightModeFlat;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void initLightModeReflection() {
        try {
            Class<?> lightModeClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.model.light.LightMode");
            lightModeFlat = Enum.valueOf((Class<? extends Enum>) lightModeClass, "FLAT");
            Class<?> targetClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext");
            defaultLightModeField = targetClass.getDeclaredField("defaultLightMode");
            defaultLightModeField.setAccessible(true);
        } catch (Throwable t) {
            System.out.println("[Adapt] SodiumAoDisableMixin reflection init failed: " + t);
        }
    }

    @Inject(method = "prepareAoInfo", at = @At("RETURN"), remap = false)
    private void adapt$forceFlatLighting(CallbackInfo ci) {
        // 光影总开关关闭 → 保留 Sodium 的平滑光照（原版 AO 渐变）。
        if (!com.ylmac.adapt.render.lighting.AdaptRenderToggle.isWorldEffectsEnabled()) {
            return;
        }
        this.useAmbientOcclusion = false;
        if (defaultLightModeField == null) {
            initLightModeReflection();
        }
        if (defaultLightModeField != null && lightModeFlat != null) {
            try {
                defaultLightModeField.set(this, lightModeFlat);
            } catch (Throwable t) {
                System.out.println("[Adapt] force FLAT defaultLightMode failed: " + t);
            }
        }
    }
}
