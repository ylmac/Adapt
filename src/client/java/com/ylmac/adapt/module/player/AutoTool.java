package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.PlayerTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.mixins.world.MultiPlayerGameModeAccessor;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;

/**
 * <p>
 * 找挖掘速度最高的工具切换（isMiningBlock 判断持左键/正在破坏）。
 * 26.2 无 BlockDamageEvent（StartDestroyBlockEvent 已定义但未在任何 mixin 挂载），
 * 改为在 PlayerTickEvent.Post 中：
 * 1. 通过 {@link MultiPlayerGameModeAccessor#adapt_getDestroyBlockPos} 获取正在破坏的方块
 *；
 * getStrVsBlock + EnchantmentHelper.getEfficiencyModifier）；
 * 3. 找到最佳工具后切换（26.2：inventory.setSelectedSlot + 静默发
 * ServerboundSetCarriedItemPacket，与 ThrowableAura 同款）。
 * <p>
 * 只在非空气、正在破坏的方块上触发；方块已破坏（gameMode 停止破坏）自动复位，
 * 不重复切换。
 * <p>
 * 视为挖掘中」的分支 — 破坏流程尚未开始的瞬间（第一下攻击），gameMode 的
 * destroyBlockPos/isDestroying 还没更新，此时也要提前换好工具。本实现补上该分支：
 * 按住 {@code options.keyAttack} 且准星指向可挖掘方块 → 视为挖掘中。
 */
public class AutoTool extends Module {

    public AutoTool() {
        super("autotool", "AutoTool", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
 // 是否包含效率附魔加成
        boolSetting("计算效率附魔", true);
        // 是否强制切换（false 时只选比当前更好的工具才切）
        boolSetting("总是切换", false);
    }

    private boolean countEnchant() {
        return (boolean) settings.get(0).getValue();
    }

    private boolean alwaysSwitch() {
        return (boolean) settings.get(1).getValue();
    }

    /**
 * 当前正在破坏的方块位置（26.2 MultiPlayerGameMode 私有字段 destroyBlockPos，经访问器读取）。
 * 破坏流程未开始时（按住攻击键的第一瞬间）该字段可能是旧的/空的 — 此时回退到
 * 准星指向的方块位置。
 */
    private BlockPos currentMiningPos() {
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (gameMode == null) return null;
        BlockPos destroying = ((MultiPlayerGameModeAccessor) gameMode).adapt_getDestroyBlockPos();
        if (destroying != null && ((MultiPlayerGameModeAccessor) gameMode).adapt_getIsDestroying()) {
            return destroying;
        }
        // 按住攻击键但破坏流程尚未开始：用准星指向的方块（需为可挖掘方块，由 isMiningBlock 校验）
        if (mc.options.keyAttack.isDown()) {
            HitResult hit = mc.hitResult;
            if (hit != null && hit.getType() == HitResult.Type.BLOCK
                    && hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                return blockHit.getBlockPos();
            }
        }
        return destroying;
    }

    /**
 * 是否正在挖掘：
 * - 正在破坏（MultiPlayerGameMode 已开始破坏，destroyBlockPos 非空）且准星指向该方块；
 * - <b>或</b>：未触发破坏流程但玩家正按住攻击键且准星指向一个可挖掘方块
 * — 按住攻击键的第一 tick 破坏流程尚未开始，此时就要提前换好工具，
 * 避免用空手/错误工具挥出第一下）。
 * 两个分支都要求准星指向目标方块，防止对空气或其它实体误触发。
 */
    private boolean isMiningBlock(BlockPos pos) {
        if (pos == null) return false;
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (gameMode == null) return false;
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)) return false;
        if (!pos.equals(blockHit.getBlockPos())) return false;

 // 分支 1：破坏流程已开始
        if (((MultiPlayerGameModeAccessor) gameMode).adapt_getIsDestroying()) {
            return true;
        }
 // 分支 2：按住攻击键
        // 且准星指向的方块可挖掘（非空气）。破坏流程尚未开始的瞬间也换工具。
        if (mc.options.keyAttack.isDown() && mc.level != null) {
            return !mc.level.getBlockState(pos).isAir();
        }
        return false;
    }

 /** 计算某个物品对该方块的挖掘速度 */
    private float digSpeed(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) return 1.0f;
        float speed = stack.getDestroySpeed(state);
        if (speed <= 1.0f) {
            return speed; // 不合适的工具（剑/空手打草等），不按高效工具算
        }
        if (countEnchant()) {
            // 效率附魔：+（等级² + 1），与 一致
            int level = getEnchantmentLevel(stack, Enchantments.EFFICIENCY);
            if (level > 0) {
                speed += Math.min(level * level + 1.0f, 1024.0f);
            }
        }
        return speed;
    }

    /** 读取物品的附魔等级（26.2：EnchantmentHelper.getItemEnchantmentLevel(Holder, ItemInstance)） */
    private int getEnchantmentLevel(ItemStack stack, net.minecraft.resources.ResourceKey<Enchantment> enchantment) {
        if (stack.isEmpty() || mc.level == null) return 0;
        try {
            // 注册表查找：Enchantments.EFFICIENCY 是 ResourceKey，解析成 Holder 后查等级
            Holder<Enchantment> holder = mc.level.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(enchantment);
            return EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
        } catch (Exception e) {
            return 0;
        }
    }

 /** 在热栏中找挖掘速度最高的工具槽位 */
    private int findBestTool(BlockState state) {
        int bestSlot = -1;
        float bestSpeed = 1.0f; // 空手基准 1.0
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            float speed = digSpeed(stack, state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /** 切换槽位（26.2 客户端 selectedSlot + 服务端 ServerboundSetCarriedItemPacket，ThrowableAura 同款） */
    private void switchTo(int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().send(new ServerboundSetCarriedItemPacket(slot), null);
        }
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (nullCheck()) return;
        BlockPos miningPos = currentMiningPos();
        if (!isMiningBlock(miningPos) || mc.level == null) return;

        BlockState state = mc.level.getBlockState(miningPos);
        if (state.isAir()) return;

        int bestSlot = findBestTool(state);
        if (bestSlot == -1) return;

        int currentSlot = mc.player.getInventory().getSelectedSlot();
        if (bestSlot == currentSlot) return; // 已经是最好工具

        if (alwaysSwitch()) {
            switchTo(bestSlot);
            return;
        }
        // 非强制模式：只有更好工具才切换（当前工具挖掘速度 < 最佳工具）
        float currentSpeed = digSpeed(mc.player.getInventory().getItem(currentSlot), state);
        float bestSpeed = digSpeed(mc.player.getInventory().getItem(bestSlot), state);
        if (bestSpeed > currentSpeed) {
            switchTo(bestSlot);
        }
    }
}
