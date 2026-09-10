package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.features.module.ModuleRegistry;
import com.ylmac.adapt.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * 在 HUD 中显示模块（移植自 myau ShowCommand）。
 * <p>
 * .show &lt;模块|*&gt; —— * 显示全部。
 */
public class ShowCommand extends Command {
    public ShowCommand() {
        super(new ArrayList<>(Arrays.asList("show", "s", "unhide")));
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
            } else if (!module.isHidden()) {
                ChatUtil.sendFormatted(String.format("%s&o%s&r is not hidden in HUD&r", ChatUtil.CLIENT_NAME, module.name));
            } else {
                module.setHidden(false);
                ChatUtil.sendFormatted(String.format("%s&o%s&r is no longer hidden in HUD&r", ChatUtil.CLIENT_NAME, module.name));
            }
        } else {
            for (ModuleRegistry.ModuleEntry module : ModuleRegistry.getAllModules()) {
                module.setHidden(false);
            }
            ChatUtil.sendFormatted(String.format("%sAll modules are no longer hidden in HUD&r", ChatUtil.CLIENT_NAME));
        }
    }
}
