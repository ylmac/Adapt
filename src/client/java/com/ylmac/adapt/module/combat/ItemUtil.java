package com.ylmac.adapt.module.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * 物品工具。
 * 26.2 无 ItemSword/ItemTool 类，剑/工具判定改用 ItemTags。
 */
public final class ItemUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private ItemUtil() {}

 /** 是否手持剑 */
    public static boolean isHoldingSword() {
        return mc.player != null && mc.player.getMainHandItem().is(ItemTags.SWORDS);
    }

 /** 是否手持工具 */
    public static boolean isHoldingTool() {
        ItemStack stack = mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
        return stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SHOVELS)
                || stack.is(ItemTags.HOES);
    }

 /** 手持物品是否有耐久 III 或剑 */
    public static boolean hasRawUnbreakingEnchant() {
        ItemStack stack = mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
        if (stack.isEmpty()) return false;
        if (stack.getItem() == Items.ENCHANTED_BOOK) return false;
        ItemEnchantments ench = stack.getEnchantments();
        if (ench.getLevel(holder(Enchantments.UNBREAKING)) > 0) return true;
        return stack.is(ItemTags.SWORDS);
    }

    private static Holder<Enchantment> holder(ResourceKey<Enchantment> key) {
        // 26.2 附魔是数据驱动注册表（无 BuiltInRegistries.ENCHANTMENT），
        // 只能经世界 registryAccess 查 Holder；未进世界时返回 null（getLevel(null)=0）
        if (mc.level == null || mc.level.registryAccess() == null) return null;
        var registry = mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        var opt = registry.get(key);
        return opt.isPresent() ? opt.get() : null;
    }
}
