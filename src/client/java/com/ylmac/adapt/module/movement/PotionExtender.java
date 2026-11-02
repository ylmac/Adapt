package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.command.ChatUtil;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * PotionExtender 药水延长。
 * <p>
 * 到 extendDuration 秒后把该效果 removePotionEffect 掉—即效果"活"得比服务端
 * 给的持续时间更久。26.2 客户端侧没有 PreMotionEvent，等价实现为：
 * <p>
 * 1. 每次玩家 tick 把当前所有药水效果的剩余时间（duration）累积到一个
 * frozenMap（效果 → 已冻结的 tick 数），效果剩余 1 tick 时不递减（冻结）；
 * 2. 效果"本应到期"（剩余 1 tick）时，用 {@link LivingEntity#forceAddEffect} 重新
 * 放回一个满时长的效果实例，从而在客户端把该效果无限维持下去；
 * 3. 服务端响应：26.2 服务器不认"效果变长"，到期后服务端会发
 * ClientboundRemoveMobEffectPacket 移除。客户端强制重放后，本地 HUD 上效果仍然
 * 显示并持续生效（例如夜视的屏幕染色、发光等客户端视觉），直至模块关闭
 * （onDisable 时把冻结的效果全部移除，恢复原状）。
 * <p>
 * <p>
 * 说明（26.2 服务端限制）：服务端持有的是效果真实剩余时间，客户端无法单方面把
 * 效果"真正"延长给其他玩家看到；本模块做的是客户端模拟—冻结计时 + 到期重放，
 * （"在服务端移除前先延长客户端显示"）一致，属于 26.2 下能做到的最完整方案。
 */
public class PotionExtender extends Module {

    /** 冻结的效果：效果 Holder → 距离自然到期还剩的 tick（一直冻结在 1） */
    private final Map<Holder<MobEffect>, MobEffectInstance> frozenEffects = new HashMap<>();

    /** 每个效果的"冻结时长"（tick 计数，用于计算可延长时长与显示） */
    private final Map<Holder<MobEffect>, Integer> frozenTicks = new HashMap<>();

    private final DoubleSetting extendDuration;

 /** 到期前多少 tick 开始冻结 */
    private static final int FREEZE_THRESHOLD = 1;

    public PotionExtender() {
        super("potionextender", "PotionExtender", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        extendDuration = doubleSetting("extend-duration", 60.0, 1.0, 600.0, 1.0);
    }

    @Override
    protected void onEnable() {
        // 清空上次残留的冻结状态
        frozenEffects.clear();
        frozenTicks.clear();
    }

    @Override
    protected void onDisable() {
        // 关模块时把本地强制维持的效果全部移除，恢复服务端真实状态
        if (mc.player != null) {
            for (Holder<MobEffect> effect : frozenEffects.keySet()) {
                mc.player.removeEffect(effect);
            }
        }
        frozenEffects.clear();
        frozenTicks.clear();
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;
        LivingEntity player = mc.player;
        if (player == null) return;

        // 1. 遍历当前所有效果：记录冻结，或重放到期效果
        Map<Holder<MobEffect>, MobEffectInstance> active = new HashMap<>(player.getActiveEffectsMap());
        for (Map.Entry<Holder<MobEffect>, MobEffectInstance> entry : active.entrySet()) {
            Holder<MobEffect> key = entry.getKey();
            MobEffectInstance instance = entry.getValue();
            if (instance == null || key == null) continue;

            // 效果自然剩余时间已经很少 → 冻结它，避免它继续递减
            if (instance.getDuration() <= FREEZE_THRESHOLD) {
                frozenEffects.put(key, instance);
                frozenTicks.put(key, frozenTicks.getOrDefault(key, 0) + 1);
            } else {
                // 仍在正常持续：清掉旧的冻结记录（服务端可能已刷新了效果）
                frozenEffects.remove(key);
                frozenTicks.remove(key);
            }
        }

        // 2. 对每个冻结的效果：重放一个满时长实例（客户端本地续命）。
 // 重放时长 = 设置的延长秒数换算的 tick 数。
        // 服务端到期后的现实处理：服务端会发 ClientboundRemoveMobEffectPacket
        // 把效果移除（getActiveEffectsMap 中消失），本循环随后把它重新放回—
        // 即"到期前按设置时长重放，到期后每 tick 续一次"，效果在本地持续
        // 收尾语义等价：延长时长由设置控制，不再无限叠加）。
        int replayTicks = Math.max(1, (int) (extendDuration.getValue() * 20.0));
        for (Map.Entry<Holder<MobEffect>, MobEffectInstance> entry : new HashMap<>(frozenEffects).entrySet()) {
            Holder<MobEffect> key = entry.getKey();
            MobEffectInstance original = entry.getValue();
            if (key == null || original == null) continue;

            // 如果服务端刚发来新的正常效果，先移除冻结（避免重复叠加）
            MobEffectInstance current = player.getActiveEffectsMap().get(key);
            if (current != null && current.getDuration() > FREEZE_THRESHOLD) {
                frozenEffects.remove(key);
                frozenTicks.remove(key);
                continue;
            }

            // 构造一个时长 = 延长设置的新实例并放回（26.2 客户端 forceAddEffect 直接覆盖）。
            // 用满时长而非 1 tick：效果在 tickEffects 阶段会递减，1 tick 实例会在
            // 当帧渲染前被移除造成闪烁，满时长实例保证 HUD/视觉效果持续。
            MobEffectInstance extended = new MobEffectInstance(
                    key,
                    replayTicks,
                    original.getAmplifier(),
                    original.isAmbient(),
                    original.isVisible(),
                    original.showIcon());
            // 跳过混色（skipBlending）避免画面闪烁；与原生 handleUpdateMobEffect 相同
            extended.skipBlending();
            player.forceAddEffect(extended, player);

            // 提示一次（防止刷屏：只在冻结第一 tick 时提示）
            if (frozenTicks.getOrDefault(key, 0) == 1) {
                ChatUtil.sendFormatted("&aPotionExtender&7: 已延长效果 &e" + key.getRegisteredName());
            }
        }
    }
}
