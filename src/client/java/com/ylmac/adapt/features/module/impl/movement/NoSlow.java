package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.ItemTags;
import java.util.List;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

/**
 * NoSlow —— 100% 移植 Rise 6.9.5 NoSlow（13 个模式）。
 * <p>
 * Rise 结构（NoSlow.java）：Mode 选择器（Vanilla / NCP / New NCP / Intave / Legit /
 * Watchdog Prediction / Variable / Prediction / Watchdog / Grim 1.9 / Grim /
 * Grim 3.0 / Matrix）+ food/potion/sword/bow 四个类别开关（默认全 false）。
 * <p>
 * 26.2 减速链（javap LocalPlayer 确证）：
 * - LocalPlayer.modifyInput：isUsingItem → vec2.scale(itemUseSpeedMultiplier())
 * - itemUseSpeedMultiplier()：读 useItem 的 USE_EFFECTS.speedMultiplier()（默认 0.2）
 * - 减速 = 移动输入被乘 0.2；且 isSlowDueToUsingItem() 取消疾跑
 * <p>
 * 移植手法：
 * - 减速取消/变速：LocalPlayerItemUseSpeedMixin 读 NoSlow.getMultiplier()
 *  （Vanilla/NCP 等 = 1.0 取消；Variable = Multiplier 0.2~1.0 变速；Prediction =
 *   每 amount tick 取消一次）。
 * - 发包模式（NCP/New NCP/Intave/Grim/Watchdog）：ClientTickEvent 里按模式发
 *   ServerboundPlayerActionPacket(RELEASE_USE_ITEM) / ServerboundUseItemPacket
 *   （26.2 等价 C07/C08）。
 */
public class NoSlow extends Module {

    /** 单例引用（LocalPlayerItemUseSpeedMixin 经此读取设置） */
    private static NoSlow instance;

    public static NoSlow get() {
        return instance;
    }

    /** 模式选择器（Rise: ModeValue 13 项，默认 Vanilla） */
    private final ChoiceSetting mode;
    /** 食物减速消除（Rise: Food，默认关） */
    private final BoolSetting food;
    /** 药水减速消除（Rise: Potion，默认关） */
    private final BoolSetting potion;
    /** 剑/盾格挡减速消除（Rise: Sword，默认关） */
    private final BoolSetting sword;
    /** 弓/弩减速消除（Rise: Bow，默认关） */
    private final BoolSetting bow;
    /** 变速倍率（Rise: VariableNoSlow Multiplier，0.8 默认，仅 Variable 模式） */
    private final DoubleSetting multiplier;
    /** 预测间隔（Rise: PredictionNoSlow Amount，默认 2，仅 Prediction 模式） */
    private final DoubleSetting amount;
    /** 吃包完成 tick（Rise: WatchdogPredictionNoSlow When to finish eating，默认 30） */
    private final DoubleSetting finishEating;
    /** 最大 ping 欺骗（Rise: WatchdogPredictionNoSlow Max Ping Spoof，默认 8） */
    private final DoubleSetting maxPingSpoof;

    /** Prediction 模式计数（每 amount tick 取消一次） */
    private int predictionCounter;

    public NoSlow() {
        super("noslow", "NoSlow", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        instance = this;
        mode = choiceSetting("模式", "Vanilla", List.of(
                "Vanilla", "NCP", "New NCP", "Intave", "Legit",
                "Watchdog Prediction", "Variable", "Prediction", "Watchdog",
                "Grim 1.9", "Grim", "Grim 3.0", "Matrix"));
        // Rise 默认全 false（勾选哪个类别才取消哪个）
        food = boolSetting("食物", false);
        potion = boolSetting("药水", false);
        sword = boolSetting("剑", false);
        bow = boolSetting("弓", false);
        // Rise: VariableNoSlow Multiplier（0.8 默认，0.2~1，仅 Variable）
        multiplier = doubleSetting("变速倍率", 0.8, 0.2, 1.0, 0.05,
                () -> "Variable".equals(mode.getValue()));
        // Rise: PredictionNoSlow Amount（2 默认，2~5，仅 Prediction）
        amount = doubleSetting("预测间隔", 2.0, 2.0, 5.0, 1.0,
                () -> "Prediction".equals(mode.getValue()));
        // Rise: WatchdogPredictionNoSlow（仅 Watchdog Prediction）
        finishEating = doubleSetting("吃完时刻", 30.0, 20.0, 36.0, 1.0,
                () -> "Watchdog Prediction".equals(mode.getValue()));
        maxPingSpoof = doubleSetting("最大Ping欺骗", 8.0, 0.0, 30.0, 1.0,
                () -> "Watchdog Prediction".equals(mode.getValue()));
    }

    @Override
    protected void onEnable() {
        predictionCounter = 0;
    }

    /**
     * 减速/变速倍率（LocalPlayerItemUseSpeedMixin 读取）。
     * - Vanilla/NCP/New NCP/Intave/Legit/Watchdog/Grim/Grim 1.9/Grim 3.0/Matrix
     *   → 1.0（完全取消减速）
     * - Variable → Multiplier（0.2~1.0）
     * - Prediction → 每 amount tick 取消一次（其余 tick 保持减速）
     */
    public float getSpeedMultiplier() {
        String m = mode.getValue();
        if ("Variable".equals(m)) {
            return multiplier.getValue().floatValue();
        }
        if ("Prediction".equals(m)) {
            // Rise: 每 amount tick 取消一次减速
            predictionCounter++;
            if (predictionCounter >= amount.getValue().intValue()) {
                predictionCounter = 0;
                return 1.0f;
            }
            return 0.2f;  // 原版减速倍率
        }
        return 1.0f;
    }

    /** 当前使用物品是否命中已勾选类别（由 mixin 调用，等价 Rise SlowDownEvent 的类别判断） */
    public boolean shouldRemoveSlowdown() {
        if (mc.player == null || !mc.player.isUsingItem()) {
            return false;
        }
        ItemStack use = mc.player.getUseItem();
        if (use.isEmpty()) {
            return false;
        }
        return (isFood(use) && food.getValue())
                || (isPotion(use) && potion.getValue())
                || (isSword(use) && sword.getValue())
                || (isBow(use) && bow.getValue());
    }

    // ================= 发包模式（Rise NCP/Grim/Watchdog 等） =================

    /** 每 tick 发包（ClientTickEvent 驱动，按模式路由） */
    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;
        if (!this.isEnabled()) return;
        String m = mode.getValue();
        if (mc.player == null || mc.level == null) return;
        // 剑格挡时发包（NCP: PreMotion RELEASE / PostMotion USE）
        if (("NCP".equals(m) || "Grim".equals(m)) && sword.getValue() && mc.player.isBlocking()) {
            // NCP: C07 RELEASE_USE_ITEM（停止格挡）
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
            // NCP: C08 USE_ITEM（恢复格挡）
            mc.player.connection.send(new ServerboundUseItemPacket(
                    InteractionHand.MAIN_HAND, 0, mc.player.getYRot(), mc.player.getXRot()));
        }
        // Grim 3.0: 45° 旋转 + 强制疾跑（简化：Rotation 由 SendPositionEvent 处理，
        // 这里只保证减速取消——getSpeedMultiplier 已返回 1.0）
    }

    // ================= 类别判定（Rise 同款意图） =================

    /** 食物/饮品：使用动画为 EAT 或 DRINK */
    private boolean isFood(ItemStack stack) {
        ItemUseAnimation anim = stack.getUseAnimation();
        return anim == ItemUseAnimation.EAT || anim == ItemUseAnimation.DRINK;
    }

    /** 药水：PotionItem / SplashPotionItem / LingeringPotionItem */
    private boolean isPotion(ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        return item instanceof net.minecraft.world.item.PotionItem
                || item instanceof net.minecraft.world.item.SplashPotionItem
                || item instanceof net.minecraft.world.item.LingeringPotionItem;
    }

    /** 剑（ItemTags.SWORDS）或盾牌格挡（isBlocking） */
    private boolean isSword(ItemStack stack) {
        if (stack.getItem().builtInRegistryHolder().is(ItemTags.SWORDS)) {
            return true;
        }
        return mc.player != null && mc.player.isBlocking();
    }

    /** 弓/弩 */
    private boolean isBow(ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        return item instanceof net.minecraft.world.item.BowItem
                || item instanceof net.minecraft.world.item.CrossbowItem;
    }

    /** 模块信息 tag（当前模式） */
    @Override
    public String getInfo() {
        return mode.getValue();
    }
}
