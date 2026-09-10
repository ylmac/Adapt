package com.ylmac.adapt.features.module.impl.player;

import com.ylmac.adapt.features.autoclick.KeyBindUtil;
import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PlayerHeadItem;

/**
 * AutoHead —— UHC 自动吃金头颅（移植自 Rise 6.9.5 AutoHead）。
 * <p>
 * Rise 原版逻辑（逐项对应）：
 * 1. PreMotionEvent 每 tick：延迟计时满 + 无伤害吸收（absorption == 0）
 *    + Scaffold 未启用时才开始处理；
 * 2. 遍历热键栏 0-8：找到 {@code ItemSkull}（1.8.9 所有头颅 = 26.2 的
 *    {@link PlayerHeadItem}，UHC 金头颅是带 CONSUMABLE 组件的玩家头颅）且
 *    玩家血量 ≤ 设置阈值（health 设置，默认 15/20）→ 切到该槽位；
 * 3. 发包使用：1.8.9 {@code C08PacketPlayerBlockPlacement} 触发"右键使用"；
 *    26.2 等价为 {@code mc.gameMode.useItem(player, hand)}（内部
 *    startPrediction → ServerboundUseItemPacket → ItemStack.use →
 *    Consumable.startConsuming → startUsingItem，已 javap 确证）——
 *    由于金头颅是食物（CONSUMABLE 组件），useItem 会让玩家进入"食用"状态，
 *    在 useItemRemaining 归零后自动 finishUsingItem 吃掉并回血；
 * 4. 使用后按 delay（Bounds 随机区间，默认 500-1000ms）冷却下一次。
 * <p>
 * 26.2 移植要点（API 全部经 javap /Users/ylmac/Documents/26.2 确证）：
 * - 伤害吸收：{@code player.getAbsorptionAmount()}（LivingEntity）；
 * - 血量：{@code player.getHealth()}（20 = 满血）；
 * - 头颅判定：26.2 无 1.8.9 ItemSkull 类；所有头颅物品中只有
 *   {@code Items.PLAYER_HEAD} 是 {@link PlayerHeadItem}（其余头颅是
 *   StandingAndWallBlockItem，字节码确证）。UHC 金头颅就是带自定义名 +
 *   CONSUMABLE 组件的玩家头颅，因此用 {@code instanceof PlayerHeadItem}
 *   精确对应 Rise 的 ItemSkull 检查（等价覆盖"能吃的头颅"）；
 * - 换槽：{@code inventory.setSelectedSlot(i)} + 服务端
 *   {@code ServerboundSetCarriedItemPacket}（ThrowableAura/AutoStuck 同款）；
 * - 使用：{@code mc.gameMode.useItem(player, InteractionHand.MAIN_HAND)}
 *   （内部自动 ensureHasSentCarriedItem 同步当前槽位）；
 * - Scaffold 冲突：Rise 检查 Scaffold.isEnabled()，26.2 同样检查同名模块。
 */
public class AutoHead extends Module {

    /** 血量阈值：低于该值且无吸收时吃金头颅（Rise 默认 15/20） */
    private final DoubleSetting health;

    /** 两次使用之间的延迟（Rise BoundsNumberValue 随机区间，默认 500-1000ms） */
    private final DoubleSetting delayMin;
    private final DoubleSetting delayMax;

    /** 距上次使用的毫秒时间戳 */
    private long nextUse;

    /** 是否正在食用金头颅（用于持续按住右键，防止 handleKeybinds 提前 release） */
    private boolean eating;

    public AutoHead() {
        super("autohead", "AutoHead", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        health = doubleSetting("血量", 15.0, 1.0, 20.0, 1.0);
        delayMin = doubleSetting("最小延迟", 500.0, 50.0, 5000.0, 50.0);
        delayMax = doubleSetting("最大延迟", 1000.0, 50.0, 5000.0, 50.0);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck()) return;
        if (mc.gui.screen() != null) return;

        // 延迟计时未到，或已有伤害吸收（吃金头先产生吸收，吸收期间不重复触发）
        if (System.currentTimeMillis() < nextUse) return;
        if (mc.player.getAbsorptionAmount() > 0.0F) return;
        // Scaffold 启用时不抢右键（Rise 原版检查）
        if (com.ylmac.adapt.features.module.ModuleRegistry.isEnabled("Scaffold")) return;

        // 正在食用中：保持按住右键（26.2 handleKeybinds 中 keyUse 松开会调
        // releaseUsingItem 中断食用，已反汇编确证），直到吃完（useItemRemaining
        // 归零后 LocalPlayer.tick 自动 completeUsingItem → finishUsingItem 发包）
        if (eating) {
            if (mc.player.isUsingItem()) {
                KeyBindUtil.setKeyBindState(mc.options.keyUse, true);
                return;
            }
            // 食用完成：松开右键（防止 handleKeybinds 的"按住自动使用"分支
            // 在下一次 tick 误触发 startUseItem），进入冷却
            eating = false;
            KeyBindUtil.setKeyBindState(mc.options.keyUse, false);
            scheduleNextUse();
            return;
        }

        // 血量未低于阈值则不触发
        if (mc.player.getHealth() > health.getValue().floatValue()) return;

        // 遍历热键栏找金头颅（UHC 金头颅 = 带自定义名的玩家头颅 PlayerHeadItem）
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof PlayerHeadItem)) continue;
            if (!isGoldenHead(stack)) continue;

            eatHead(i);
            return;
        }
    }

    /**
     * 是否为可食用的金头颅：
     * - 26.2 中头颅物品都是可装备的（equippableUnswappable，注册字节码确证），
     *   普通头颅右键会戴到头上而非食用，因此必须排除；
     * - 带 CONSUMABLE 组件（服务器用组件实现食用）直接判定为金头颅；
     * - 否则按 UHC 约定匹配自定义名中的 "golden head"/"金头颅"/"金头"
     *   （服务器端处理食用的老式实现）。
     */
    private boolean isGoldenHead(ItemStack stack) {
        if (stack.has(net.minecraft.core.component.DataComponents.CONSUMABLE)) return true;
        net.minecraft.network.chat.Component name = stack.getHoverName();
        if (name == null) return false;
        String display = name.getString().toLowerCase(java.util.Locale.ROOT);
        return display.contains("golden head") || display.contains("gold head")
                || display.contains("金头颅") || display.contains("金头");
    }

    /** 切到金头颅槽位并开始食用 */
    private void eatHead(int slot) {
        Inventory inv = mc.player.getInventory();
        inv.setSelectedSlot(slot);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }

        // 26.2 等价 Rise 的 C08PacketPlayerBlockPlacement：触发右键使用。
        // gameMode.useItem 内部 startPrediction 发包 + ItemStack.use →
        // Consumable.startConsuming → startUsingItem（食用开始）。
        ItemStack held = mc.player.getMainHandItem();
        if (held == null || held.isEmpty() || !(held.getItem() instanceof PlayerHeadItem)) return;

        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);

        // 进入食用状态则按住右键（26.2 handleKeybinds：使用期间 keyUse 松开会
        // 调 releaseUsingItem 中断食用，已反汇编确证），吃完后松开
        if (mc.player.isUsingItem()) {
            eating = true;
            KeyBindUtil.setKeyBindState(mc.options.keyUse, true);
        } else {
            // 未进入使用（可能是药水头颅等不可食用的）—— 按延迟冷却即可
            scheduleNextUse();
        }
    }

    /** 计算并设置下一次使用时间（Rise 在 Bounds 区间内随机） */
    private void scheduleNextUse() {
        long min = Math.round(delayMin.getValue());
        long max = Math.round(delayMax.getValue());
        if (max < min) max = min;
        nextUse = System.currentTimeMillis() + (max == min ? min
                : min + (long) (Math.random() * (max - min)));
    }

    @Override
    protected void onEnable() {
        nextUse = 0L;
        eating = false;
    }

    @Override
    protected void onDisable() {
        // 松开模拟的右键，避免禁用后卡住使用状态
        if (eating) {
            eating = false;
            KeyBindUtil.setKeyBindState(mc.options.keyUse, false);
        }
    }
}
