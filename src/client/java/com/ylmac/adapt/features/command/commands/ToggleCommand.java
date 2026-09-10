package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.features.module.ModuleRegistry;
import com.ylmac.adapt.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * 开关模块命令（移植自 myau ToggleCommand）。
 * <p>
 * .toggle &lt;模块&gt; [on/off] —— 不传第二参数则切换，传 on/off/1/0 则强制设置。
 */
public class ToggleCommand extends Command {
    public ToggleCommand() {
        super(new ArrayList<>(Arrays.asList("toggle", "t")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            ChatUtil.sendFormatted(
                    String.format("%sUsage: .%s <&omodule&r> [&oon&r/&ooff&r]&r", ChatUtil.CLIENT_NAME, args.get(0).toLowerCase(Locale.ROOT))
            );
        } else {
            ModuleRegistry.ModuleEntry module = ModuleRegistry.get(args.get(1));
            if (module == null) {
                ChatUtil.sendFormatted(String.format("%sModule not found (&o%s&r)&r", ChatUtil.CLIENT_NAME, args.get(1)));
            } else {
                boolean changed = true;
                if (args.size() >= 3) {
                    if (args.get(2).equalsIgnoreCase("true")
                            || args.get(2).equalsIgnoreCase("on")
                            || args.get(2).equalsIgnoreCase("1")) {
                        changed = !module.isEnabled();
                    } else if (args.get(2).equalsIgnoreCase("false")
                            || args.get(2).equalsIgnoreCase("off")
                            || args.get(2).equalsIgnoreCase("0")) {
                        changed = module.isEnabled();
                    }
                }
                if (changed) {
                    module.setEnabled(!module.isEnabled());
                    ChatUtil.sendFormatted(String.format("%s%s: %s&r", ChatUtil.CLIENT_NAME, module.name,
                            module.isEnabled() ? "&a&lON" : "&c&lOFF"));
                }
            }
        }
    }
}
