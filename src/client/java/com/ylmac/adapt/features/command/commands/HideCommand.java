package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.features.module.ModuleRegistry;
import com.ylmac.adapt.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * 在 HUD 中隐藏模块（移植自 myau HideCommand）。
 * <p>
 * .hide &lt;模块|*&gt; —— * 隐藏全部。
 */
public class HideCommand extends Command {
    public HideCommand() {
        super(new ArrayList<>(Arrays.asList("hide", "h")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            ChatUtil.sendFormatted(
                    String.format("%sUsage: .%s <&omodule&r>&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
            );
        } else if (!args.get(1).equals("*")) {
            ModuleRegistry.ModuleEntry module = ModuleRegistry.get(args.get(1));
            if (module == null) {
                ChatUtil.sendFormatted(String.format("%sModule &o%s&r not found&r", ChatUtil.CLIENT_NAME, args.get(1)));
            } else if (module.isHidden()) {
                ChatUtil.sendFormatted(String.format("%s&o%s&r is already hidden in HUD&r", ChatUtil.CLIENT_NAME, module.name));
            } else {
                module.setHidden(true);
                ChatUtil.sendFormatted(String.format("%s&o%s&r has been hidden in HUD&r", ChatUtil.CLIENT_NAME, module.name));
            }
        } else {
            for (ModuleRegistry.ModuleEntry module : ModuleRegistry.getAllModules()) {
                module.setHidden(true);
            }
            ChatUtil.sendFormatted(String.format("%sAll modules have been hidden in HUD&r", ChatUtil.CLIENT_NAME));
        }
    }
}
