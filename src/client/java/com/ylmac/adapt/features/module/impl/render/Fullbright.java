package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.EnumSetting;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Fullbright（全亮）—— Epsilon Fullbright 移植。
 * <p>
 * 对应 Epsilon 源文件：modules/impl/render/Fullbright.java + mixins/MixinLightmap.java。
 * <p>
 * 原版行为（忠实移植）：
 * - Mode = Gamma（默认）：Lightmap 纹理每帧清成纯白 → 光照贴图全亮、无阴影
 *   （由 LightmapMixin 在 Lightmap.render HEAD 取消原渲染并 clear 纹理，见
 *   src/client/java/com/ylmac/adapt/injection/mixins/LightmapMixin.java）。
 * - Mode = Potion：每 tick 给玩家施加无限时长夜视效果（MobEffects.NIGHT_VISION，
 *   26.2 的 MobEffectInstance(Holder, int) 构造，duration=-1 无限）。
 * - 切回 Gamma / 关闭模块时移除夜视（removeEffect，26.2 在 LivingEntity）。
 * <p>
 * 本移植补全（Epsilon 26.1.x 没有，任务要求）：
 * - 亮度 0-100%：Gamma 模式下映射到 gamma 值 0.5（原版）～ 5.0（全亮）。
 * - 平滑过渡：gamma 逐 tick 向目标渐变（默认开启；关闭则立即设置）。
 */
public class Fullbright extends Module {

    /** 供 LightmapMixin 访问的静态实例（本项目模块由 ModuleManager addModule 注册，构造即赋值） */
    public static Fullbright INSTANCE;

    private enum Mode {
        Gamma,
        Potion
    }

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Gamma, v -> {
        if (v == Mode.Gamma && mc.player != null) {
            mc.player.removeEffect(MobEffects.NIGHT_VISION);
        }
    });
    private final com.ylmac.adapt.features.module.settings.impl.DoubleSetting brightness =
            doubleSetting("亮度", 100.0, 0.0, 100.0, 1.0);
    private final com.ylmac.adapt.features.module.settings.impl.BoolSetting smooth =
            boolSetting("平滑过渡", true);

    public Fullbright() {
        super("fullbright", "Fullbright", Category.RENDER);
        INSTANCE = this;
        // 默认关闭：默认开启会淹没 Halo 光照差异，且并非所有用户都想要全亮
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    /** Gamma 模式（LightmapMixin 每帧查询：全亮 = 光照贴图清白） */
    public boolean isGammaMode() {
        return isEnabled() && mode.is(Mode.Gamma);
    }

    @Override
    protected void onEnable() {
        if (nullCheck()) return;
        if (mode.is(Mode.Potion)) {
            applyNightVision();
        } else {
            applyGamma();
        }
    }

    @Override
    protected void onDisable() {
        if (nullCheck()) return;
        if (mode.is(Mode.Potion)) {
            mc.player.removeEffect(MobEffects.NIGHT_VISION);
        } else {
            // 恢复原版 gamma（0.5），立即还原不留渐变
            mc.options.gamma().set(0.5);
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (mode.is(Mode.Potion)) applyNightVision();
    }

    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        // Gamma 模式：持续保持 gamma（防止其他插件/设置改回），并按平滑设置渐变
        if (isEnabled() && mode.is(Mode.Gamma)) applyGamma();
    }

    private void applyNightVision() {
        if (nullCheck()) return;
        mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0));
    }

    private void applyGamma() {
        if (mc.options == null) return;
        // 26.2 的 gamma OptionInstance 范围是 0.0~1.0（javap Options 确证：
        // ×100 显示，0=min / 0.5=default / 1.0=max）。映射亮度 0-100% → 0.5~1.0
        // （原版默认 0.5 = 50%，全亮 = 1.0 = 100%）。
        double target = 0.5 + 0.5 * (brightness.getValue() / 100.0);
        double current = mc.options.gamma().get();
        if (smooth.getValue()) {
            // 逐 tick 平滑过渡（每 tick 移 20% 距离，接近时吸附）
            double next = current + (target - current) * 0.2;
            if (Math.abs(next - target) < 0.01) next = target;
            mc.options.gamma().set(next);
        } else {
            mc.options.gamma().set(target);
        }
    }
}
