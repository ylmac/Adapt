package com.ylmac.adapt.features.command.commands;

import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.ModuleRegistry;
import com.ylmac.adapt.features.module.settings.Setting;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.EnumSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import com.ylmac.adapt.features.module.settings.impl.StringSetting;
import com.ylmac.adapt.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 修改模块参数（无 UI 时的调参命令）。
 * <p>
 * .set &lt;模块&gt; &lt;设置名&gt; &lt;值&gt;
 * - Choice/Enum：值 = 选项名（不区分大小写）
 * - Bool：true/false/on/off/1/0
 * - Int/Double：数字（自动 clamp 到 min/max）
 * - String：直接字符串
 * <p>
 * 先 .info &lt;模块&gt; 查看有哪些设置与可选值。
 */
public class SetCommand extends Command {
    public SetCommand() {
        super(new ArrayList<>(Arrays.asList("set", "param", "p")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 4) {
            ChatUtil.sendFormatted(String.format("%sUsage: .set <&omodule&r> <&osetting&r> <&ovalue&r>&r", ChatUtil.CLIENT_NAME));
            ChatUtil.sendFormatted(String.format("%s  先 .info <&omodule&r> 查看设置与可选值&r", ChatUtil.CLIENT_NAME));
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
        String settingName = args.get(2);
        String valueStr = args.get(3);
        // 支持带空格的值（.set x string "hello world"）
        for (int i = 4; i < args.size(); i++) valueStr += " " + args.get(i);

        Setting<?> setting = module.getSettings().stream()
                .filter(s -> s.getName().equalsIgnoreCase(settingName))
                .findFirst().orElse(null);
        if (setting == null) {
            ChatUtil.sendFormatted(String.format("%sSetting not found: &o%s&r (see .info %s)", ChatUtil.CLIENT_NAME, settingName, entry.name));
            return;
        }
        try {
            apply(setting, valueStr);
            ChatUtil.sendFormatted(String.format("%s%s &7»&r &f%s&r = &e%s&r",
                    ChatUtil.CLIENT_NAME, entry.name, setting.getName(), display(setting)));
        } catch (IllegalArgumentException e) {
            ChatUtil.sendFormatted(String.format("%sInvalid value: &o%s&r", ChatUtil.CLIENT_NAME, e.getMessage()));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void apply(Setting<?> setting, String value) {
        if (setting instanceof ChoiceSetting choice) {
            String match = choice.getChoices().stream()
                    .filter(c -> c.equalsIgnoreCase(value)).findFirst().orElse(null);
            if (match == null) throw new IllegalArgumentException("可选值: " + String.join("/", choice.getChoices()));
            ((Setting<String>) setting).setValue(match);
        } else if (setting instanceof EnumSetting en) {
            boolean ok = false;
            for (Object e : en.getModes()) {
                if (e.toString().equalsIgnoreCase(value)) { en.setValue((Enum) e); ok = true; break; }
            }
            if (!ok) throw new IllegalArgumentException("枚举值无效");
        } else if (setting instanceof BoolSetting b) {
            boolean v = value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")
                    || value.equalsIgnoreCase("1") || value.equalsIgnoreCase("yes");
            if (!v && !(value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")
                    || value.equalsIgnoreCase("0") || value.equalsIgnoreCase("no"))) {
                throw new IllegalArgumentException("需要 true/false/on/off/1/0");
            }
            b.setValue(v);
        } else if (setting instanceof IntSetting i) {
            int v = Integer.parseInt(value.trim());
            i.setValue(Math.max(i.getMin(), Math.min(i.getMax(), v)));
        } else if (setting instanceof DoubleSetting d) {
            double v = Double.parseDouble(value.trim());
            d.setValue(Math.max(d.getMin(), Math.min(d.getMax(), v)));
        } else if (setting instanceof StringSetting str) {
            str.setValue(value);
        } else {
            throw new IllegalArgumentException("该设置类型不支持命令行修改");
        }
    }

    private static String display(Setting<?> s) {
        if (s instanceof ChoiceSetting c) return c.getTranslatedValue();
        if (s instanceof EnumSetting<?> e) return e.getTranslatedValue();
        return String.valueOf(s.getValue());
    }
}
