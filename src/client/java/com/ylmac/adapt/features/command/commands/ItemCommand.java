package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 显示手持物品信息命令（移植自 myau ItemCommand）。
 * <p>
 * .item —— 输出手持物品的显示名、注册名、NBT（26.2 组件系统：
 * 注册名 = BuiltInRegistries.ITEM.getKey，NBT = getComponents() 序列化字符串）。
 */
public class ItemCommand extends Command {
    public ItemCommand() {
        super(new ArrayList<>(Arrays.asList("itemname", "item")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack stack = player.getInventory().getSelectedItem();
        if (stack != null && !stack.isEmpty()) {
            String display = stack.getHoverName().getString().replace('§', '&');
            String registryName = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String compound = stack.getComponents().toString().replace('§', '&');
            ChatUtil.sendRaw(String.format("%s%s (%s) %s", ChatUtil.CLIENT_NAME, display, registryName, compound));
        } else {
            ChatUtil.sendRaw(String.format("%sNo item in hand&r", ChatUtil.CLIENT_NAME));
        }
    }
}
