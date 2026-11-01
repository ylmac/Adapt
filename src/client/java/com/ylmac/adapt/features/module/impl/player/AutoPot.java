package com.ylmac.adapt.features.module.impl.player;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.AttackEntityEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * AutoPot —— 自动投掷药水（移植自 Rise 6.9.5 AutoPot）。
 * <p>
 * Rise 原版逻辑（逐项对应）：
 * 1. PreUpdateEvent 每 tick：攻击冷却计时（attackTicks++，攻击事件清零）+
 *    血量低于阈值 或 无回血 buff 时才开始处理；
 * 2. 遍历热键栏 0-8：找喷溅药水（{@code ItemPotion.isSplash}，1.8.9 metadata
 *    判断）→ 取第一个效果（1.8.9 PotionEffect）；26.2 等价读取
 *    {@code stack[DataComponents.POTION_CONTENTS].allEffects}；
 * 3. 效果必须是正面药水（1.8.9 PlayerUtil.isPositivePotion）：26.2 用
 *    {@code MobEffect.isBeneficial()}；治疗/回血只在血量低于阈值时使用
 *    （1.8.9 {@code Potion.heal/regeneration}），其余正面药水（力量/速度等）
 *    在未激活时随时使用；
 * 4. 未激活条件：1.8.9 {@code isPotionActive} 判断 26.2 等价
 *    {@code player.hasEffect(Holder)}（瞬时效果如瞬间治疗总是"未激活"→ 只会
 *    在低血时丢，等价 Rise 的 duration==0 逻辑）；
 * 5. 旋转到 87°±3° 向下看（Rise RotationComponent.setRotations，
 *    rotationSpeed 随机延迟模拟真人），pitch 大于 85° 后
 *    syncCurrentPlayItem（26.2 gameMode.useItem 内部自动做）+ 发包投掷
 *    （1.8.9 C08PacketPlayerBlockPlacement → 26.2 useItem → ServerboundUseItemPacket）；
 * 6. 投掷后按 delay（Bounds 随机区间，默认 500-1000ms）冷却。
 * <p>
 * 26.2 移植要点（API 全部经 javap /Users/ylmac/Documents/26.2 确证）：
 * - 喷溅药水：{@code stack.getItem() instanceof SplashPotionItem}（ThrowablePotionItem
 *   有 use() 投掷逻辑，SplashPotionItem 直接可右键投掷）；
 * - 药水效果：{@code stack.get(DataComponents.POTION_CONTENTS)} →
 *   {@code PotionContents.getAllEffects()}（Iterable<MobEffectInstance>）；
 * - 正面判定：{@code effect.getEffect().value().isBeneficial()}（26.2 无 1.8.9
 *   PotionID，用 Holder.value() 取 MobEffect）；
 * - 治疗/回血：{@code MobEffects.INSTANT_HEALTH / REGENERATION}（Holder.is 比较）；
 * - 未激活：{@code player.hasEffect(effectHolder)}（26.2 传 Holder）；
 * - 旋转：直接 {@code player.setYRot/setXRot}（gameMode.useItem 内部构造的
 *   ServerboundUseItemPacket 携带玩家当前旋转，服务端按旋转投掷，ThrowableAura
 *   throwAt 同款）；旋转后立即还原，避免视角抽搐；
 * - 投掷：{@code mc.gameMode.useItem(player, InteractionHand.MAIN_HAND)}；
 * - 换槽：{@code inventory.setSelectedSlot(i)} + 服务端
 *   {@code ServerboundSetCarriedItemPacket}（ThrowableAura 同款）；
 * - 战斗状态：{@link AttackEntityEvent}（AttackEntityEventMixin 在
 *   Minecraft.startAttack 时触发）清零攻击计时，等价 Rise AttackEvent；
 * - 移动条件：Rise {@code cqL > 1}（1.8.9 limbSwing，摆动动画）→ 26.2
 *   {@code player.walkAnimation.isMoving()}（WalkAnimationState.speed > 1e-5，
 *   反汇编确证）；屏幕打开时攻击计数归零（Rise currentScreen 检查）；
 * - Scaffold 冲突：Rise 检查 Scaffold.isEnabled()，26.2 检查同名模块
 *   {@code ModuleRegistry.isEnabled("Scaffold")}（AutoHead 同款）。
 */
public class AutoPot extends Module {

    /** 血量阈值：治疗/回血药水只在低于该值时投掷（Rise 默认 15/20） */
    private final DoubleSetting health;

    /** 两次投掷之间的延迟（Rise BoundsNumberValue 随机区间，默认 500-1000ms） */
    private final DoubleSetting delayMin;
    private final DoubleSetting delayMax;

    /** 旋转模拟延迟（Rise Rotation Speed 随机区间，默认 5-10 tick） */
    private final DoubleSetting rotationSpeedMin;
    private final DoubleSetting rotationSpeedMax;

    /** 距上次投掷的毫秒时间戳 */
    private long nextThrow;

    /** 攻击冷却计时（tick）：攻击后 10 tick 内不投掷，防止与近战冲突（Rise attackTicks） */
    private int attackTicks;

    /** 投掷等待状态：已旋转，等待下一 tick 确认角度后发包（Rise RotationComponent 流程） */
    private boolean throwing;

    /** 投掷延迟（旋转阶段等待的 tick 数，模拟 RotationComponent 随机速度） */
    private int throwDelay;

    /** 本次投掷的目标槽位 */
    private int throwSlot;

    public AutoPot() {
        super("autopot", "AutoPot", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        health = doubleSetting("血量", 15.0, 1.0, 20.0, 1.0);
        delayMin = doubleSetting("最小延迟", 500.0, 50.0, 5000.0, 50.0);
        delayMax = doubleSetting("最大延迟", 1000.0, 50.0, 5000.0, 50.0);
        rotationSpeedMin = doubleSetting("最小旋转延迟", 5.0, 0.0, 20.0, 1.0);
        rotationSpeedMax = doubleSetting("最大旋转延迟", 10.0, 0.0, 20.0, 1.0);
    }

    /** 药水是否值得使用：治疗/回血需血量低于阈值，其余正面药水未激活即可用 */
    private boolean shouldUse(MobEffectInstance effect, float playerHealth) {
        MobEffect mobEffect = effect.getEffect().value();
        if (!mobEffect.isBeneficial()) return false; // 只投正面药水（Rise PlayerUtil.isPositivePotion）

        boolean isHeal = effect.getEffect().is(MobEffects.INSTANT_HEALTH);
        boolean isRegen = effect.getEffect().is(MobEffects.REGENERATION);
        // 治疗/回血：只有血量低于阈值才投（Rise health 判断）
        if ((isHeal || isRegen) && playerHealth > health.getValue().floatValue()) return false;

        // 未激活：瞬时效果（瞬间治疗）hasEffect 恒 false → 每次低血都会投，等价
        // Rise「isPotionActive == false || duration == 0」；持续效果已激活则不重复投
        return !mc.player.hasEffect(effect.getEffect());
    }

    /** 在热键栏中找第一瓶值得投掷的喷溅药水槽位（Rise 遍历 0-8 取首个） */
    private int findPotionSlot() {
        float playerHealth = mc.player.getHealth();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof SplashPotionItem)) continue; // 只投喷溅（Rise isSplash）
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents == null || !contents.hasEffects()) continue;
            for (MobEffectInstance effect : contents.getAllEffects()) {
                if (shouldUse(effect, playerHealth)) return i;
            }
        }
        return -1;
    }

    /** 随机旋转延迟（Rise MathUtil.randomLong 区间 + rotationSpeed） */
    private int randomRotationDelay() {
        double min = rotationSpeedMin.getValue();
        double max = rotationSpeedMax.getValue();
        if (max < min) max = min;
        return (int) (max == min ? min : min + Math.random() * (max - min));
    }

    /** 计算并设置下一次投掷时间（Rise Bounds 区间内随机，ms） */
    private void scheduleNextThrow() {
        long min = Math.round(delayMin.getValue());
        long max = Math.round(delayMax.getValue());
        if (max < min) max = min;
        nextThrow = System.currentTimeMillis() + (max == min ? min
                : min + (long) (Math.random() * (max - min)));
    }

    /** 切到药水槽位（26.2 客户端 selectedSlot + 服务端 ServerboundSetCarriedItemPacket） */
    private void switchTo(int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().send(
                    new ServerboundSetCarriedItemPacket(slot), null);
        }
    }

    /** 执行投掷：旋转向上 → useItem（服务端按旋转投掷）→ 还原旋转 */
    private void throwPotion() {
        // Rise RotationComponent：87°±3° 随机 + yaw 随机 ±1.5°（向下看丢在自己脚下）
        float yaw = mc.player.getYRot() + (float) ((Math.random() - 0.5) * 3.0);
        float pitch = (float) (87.0 + Math.random() * 3.0);

        float oldYaw = mc.player.getYRot();
        float oldPitch = mc.player.getXRot();
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);

        ItemStack held = mc.player.getMainHandItem();
        if (held != null && !held.isEmpty() && held.getItem() instanceof SplashPotionItem) {
            // 26.2 gameMode.useItem：内部 startPrediction 发 ServerboundUseItemPacket
            // （携带玩家当前旋转）+ ItemStack.use → ThrowablePotionItem.use 投掷
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        }

        mc.player.setYRot(oldYaw);
        mc.player.setXRot(oldPitch);

        scheduleNextThrow();
        throwing = false;
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck()) return;
        if (mc.gui.screen() != null) {
            // Rise：屏幕打开时攻击计数归零（避免刚关界面就投）
            attackTicks = 0;
            return;
        }

        attackTicks++;

        // 投掷等待阶段：旋转延迟结束后发包投掷
        if (throwing) {
            if (--throwDelay <= 0) {
                throwPotion();
            }
            return;
        }

        // 延迟计时未到 或 攻击冷却中（Rise：攻击后 10 tick 内不投掷）
        if (System.currentTimeMillis() < nextThrow) return;
        if (attackTicks < 10) return;
        // Rise：移动中才投（cqL = 1.8.9 limbSwing > 1 → 26.2 walkAnimation.isMoving()）
        if (!mc.player.walkAnimation.isMoving()) return;
        // Scaffold 启用时不抢右键（Rise 原版检查）
        if (com.ylmac.adapt.features.module.ModuleRegistry.isEnabled("Scaffold")) return;

        int slot = findPotionSlot();
        if (slot == -1) return;

        // 进入旋转阶段：切槽 → 等待随机延迟（模拟 RotationComponent 旋转速度）
        switchTo(slot);
        throwing = true;
        throwSlot = slot;
        throwDelay = randomRotationDelay();
    }

    /** 攻击事件清零攻击计时（Rise AttackEvent → attackTicks = 0） */
    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        attackTicks = 0;
    }

    @Override
    protected void onEnable() {
        nextThrow = 0L;
        attackTicks = 0;
        throwing = false;
    }

    @Override
    protected void onDisable() {
        throwing = false;
    }
}
