package com.ylmac.adapt.command.commands;

import com.ylmac.adapt.command.Command;
import com.ylmac.adapt.command.KeyBindUtil;
import com.ylmac.adapt.module.ModuleRegistry;
import com.ylmac.adapt.command.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 绑定按键命令。
 * <p>
 * .bind &lt;模块|*&gt; &lt;按键&gt; — 按键支持 GLFW 键名（A/F1/SPACE...）或鼠标
 * （MOUSE0/MOUSE1/LMB/RMB...）；"none" 解绑；.bind list 列出所有绑定。
 */
public class BindCommand extends Command {
    public BindCommand() {
        super(new ArrayList<>(Arrays.asList("bind", "b")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 3) {
            if (args.size() == 2 && (args.get(1).equalsIgnoreCase("l") || args.get(1).equalsIgnoreCase("list"))) {
                List<ModuleRegistry.ModuleEntry> modules = ModuleRegistry.getBoundModules();
                if (modules.isEmpty()) {
                    ChatUtil.sendFormatted(String.format("%sNo binds&r", ChatUtil.CLIENT_NAME));
                } else {
                    ChatUtil.sendFormatted(String.format("%sBinds:&r", ChatUtil.CLIENT_NAME));
                    for (ModuleRegistry.ModuleEntry module : modules) {
                        ChatUtil.sendFormatted(String.format("%s»&r %s&r [%s]",
                                module.isHidden() ? "&8" : "&7", module.name, KeyBindUtil.getKeyName(module.getKey())));
                    }
                }
            } else {
                ChatUtil.sendFormatted(
                        String.format("%sUsage: .%s <&omodule&r> <&okey&r>&r | .%s <&omodule&r> &onone&r | .%s &olist&r",
                                ChatUtil.CLIENT_NAME,
                                args.get(0).toLowerCase(Locale.ROOT),
                                args.get(0).toLowerCase(Locale.ROOT),
                                args.get(0).toLowerCase(Locale.ROOT))
                );
            }
        } else {
            String keyInput = args.get(2).toUpperCase();
            int keyIndex = 0;

            if (keyInput.equalsIgnoreCase("NONE") || keyInput.equalsIgnoreCase("NULL") || keyInput.equalsIgnoreCase("0")) {
                keyIndex = 0;
            } else {
                keyIndex = KeyBindUtil.getKeyIndex(keyInput);
            }

            if (!args.get(1).equals("*")) {
                ModuleRegistry.ModuleEntry module = ModuleRegistry.get(args.get(1));
                if (module == null) {
                    ChatUtil.sendFormatted(String.format("%sModule not found (&o%s&r)&r", ChatUtil.CLIENT_NAME, args.get(1)));
                } else {
                    module.setKey(keyIndex);
                    if (keyIndex == 0) {
                        ChatUtil.sendFormatted(String.format("%sUnbind &o%s&r", ChatUtil.CLIENT_NAME, module.name));
                    } else {
                        ChatUtil.sendFormatted(String.format("%sBound &o%s&r to &l[%s]&r",
                                ChatUtil.CLIENT_NAME, module.name, KeyBindUtil.getKeyName(keyIndex)));
                    }
                }
            } else {
                for (ModuleRegistry.ModuleEntry module : ModuleRegistry.getAllModules()) {
                    module.setKey(keyIndex);
                }
                if (keyIndex == 0) {
                    ChatUtil.sendFormatted(String.format("%sUnbind all modules&r", ChatUtil.CLIENT_NAME));
                } else {
                    ChatUtil.sendFormatted(String.format("%sBind all modules to &l[%s]&r",
                            ChatUtil.CLIENT_NAME, KeyBindUtil.getKeyName(keyIndex)));
                }
            }
        }
    }
}
