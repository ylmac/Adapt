package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.ModuleRegistry;
import com.ylmac.adapt.features.module.settings.Setting;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.ColorSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.EnumSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import com.ylmac.adapt.features.module.settings.impl.StringSetting;
import com.ylmac.adapt.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 查看模块信息（模式/参数/当前值）。
 * <p>
 * .info &lt;模块&gt; —— 列出该模块全部设置项、类型、可选值（Choice/Enum）与当前值。
 * 无 UI 时用此命令查模块有哪些可调参数与模式。
 */
public class InfoCommand extends Command {
    public InfoCommand() {
        super(new ArrayList<>(Arrays.asList("info", "settings", "setting")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            ChatUtil.sendFormatted(String.format("%sUsage: .info <&omodule&r>&r", ChatUtil.CLIENT_NAME));
            return;
        }
        ModuleRegistry.ModuleEntry entry = ModuleRegistry.get(args.get(1));
        if (entry == null) {
            ChatUtil.sendFormatted(String.format("%sModule not found (&o%s&r)&r", ChatUtil.CLIENT_NAME, args.get(1)));
            return;
        }
        Module module = entry.linkedRealModule();
        if (module == null) {
            ChatUtil.sendFormatted(String.format("%sModule has no real impl: &o%s&r", ChatUtil.CLIENT_NAME, entry.name));
            return;
        }
        ChatUtil.sendFormatted(String.format("%s&l%s&r &7(%s&r&7)&r &o%s&r",
                ChatUtil.CLIENT_NAME, entry.name, entry.category,
                entry.isEnabled() ? "&aON" : "&cOFF"));
        java.util.List<Setting<?>> settings = module.getSettings();
        if (settings.isEmpty()) {
            ChatUtil.sendFormatted(String.format("%s  &7No settings&r", ChatUtil.CLIENT_NAME));
            return;
        }
        for (Setting<?> s : settings) {
            ChatUtil.sendFormatted(formatSetting(s));
        }
    }

    private static String formatSetting(Setting<?> s) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatUtil.CLIENT_NAME).append("  &7»&r &f").append(s.getName()).append("&r &7=&r ");
        if (s instanceof ChoiceSetting choice) {
            sb.append("&e").append(choice.getTranslatedValue()).append("&r");
            sb.append(" &7[&r").append(String.join("&7/&r", choice.getChoices())).append("&7]&r");
        } else if (s instanceof EnumSetting<?> en) {
            sb.append("&e").append(en.getTranslatedValue()).append("&r");
            sb.append(" &7[枚举]&r");
        } else if (s instanceof BoolSetting b) {
            sb.append(b.getValue() ? "&atrue" : "&cfalse").append("&r");
        } else if (s instanceof IntSetting i) {
            sb.append("&e").append(i.getValue()).append("&r");
            sb.append(" &7[min=").append(i.getMin()).append(" max=").append(i.getMax()).append("]&r");
        } else if (s instanceof DoubleSetting d) {
            sb.append("&e").append(d.getValue()).append("&r");
            sb.append(" &7[min=").append(d.getMin()).append(" max=").append(d.getMax()).append("]&r");
        } else if (s instanceof ColorSetting c) {
            sb.append("&e").append(c.getValue()).append("&r &7[color]&r");
        } else if (s instanceof StringSetting str) {
            sb.append("&e").append(str.getValue()).append("&r");
        } else {
            sb.append("&e").append(s.getValue()).append("&r");
        }
        return sb.toString();
    }
}
