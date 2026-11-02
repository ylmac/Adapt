package com.ylmac.adapt.command.commands;

import com.ylmac.adapt.command.Command;
import com.ylmac.adapt.module.ModuleRegistry;
import com.ylmac.adapt.command.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 列出全部模块。
 */
public class ListCommand extends Command {
    public ListCommand() {
        super(new ArrayList<>(Arrays.asList("list", "l", "modules")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (!ModuleRegistry.getAllModules().isEmpty()) {
            ChatUtil.sendFormatted(String.format("%sModules:&r", ChatUtil.CLIENT_NAME));
            for (ModuleRegistry.ModuleEntry module : ModuleRegistry.getAllModules()) {
                ChatUtil.sendFormatted(String.format("%s»&r %s&r", module.isHidden() ? "&8" : "&7", formatModule(module)));
            }
        }
    }

    /** 模块显示名：名称 + 状态（ON/OFF）+ tag（如模式名） */
    static String formatModule(ModuleRegistry.ModuleEntry m) {
        String state = m.isEnabled() ? "&aON" : "&cOFF";
        String tag = (m.tag != null && !m.tag.isEmpty()) ? " &7[&f" + m.tag + "&7]" : "";
        return "&f" + m.name + " &7" + state + tag;
    }
}
