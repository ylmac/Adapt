package com.ylmac.adapt.command.commands;

import com.ylmac.adapt.command.Command;
import com.ylmac.adapt.command.KeyBindUtil;
import com.ylmac.adapt.module.ModuleRegistry;
import com.ylmac.adapt.command.ChatUtil;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 模块属性命令。
 * <p>
 * 命令名 = 模块名（如 .飞行 on / .杀戮光环 enabled true），由 CommandManager 动态路由：
 * 模块在运行期才注册（ClickGuiBridge 静态块），不能在构造器里固化别名。
 * <p>
 * .模块名 列出可见属性；.模块名 &lt;属性&gt; 显示当前值；.模块名 &lt;属性&gt; &lt;值&gt; 设置。
 * 属性名忽略连字符匹配。
 * <p>
 * 简化版属性（模块系统尚未就绪，直接操作 ModuleEntry）：
 * enabled（bool）、hidden（bool）、key（按键）、tag（文本）。
 */
public class ModuleCommand extends Command {
    public ModuleCommand() {
        // 模块名动态匹配，构造器不注册别名（见类注释）；HelpCommand 会跳过本命令
        super(new ArrayList<>());
    }

    /** 简化版属性描述：读写 ModuleEntry 字段 */
    private abstract static class Prop {
        final String name;
        final String prompt;
        final boolean bool;

        Prop(String name, String prompt, boolean bool) {
            this.name = name;
            this.prompt = prompt;
            this.bool = bool;
        }

        abstract Object get(ModuleRegistry.ModuleEntry m);
        abstract boolean set(ModuleRegistry.ModuleEntry m, String value);
        String format(ModuleRegistry.ModuleEntry m) { return String.valueOf(get(m)); }
    }

    private static final java.util.List<Prop> PROPS = new ArrayList<>();
    static {
        PROPS.add(new Prop("enabled", "true/false", true) {
            Object get(ModuleRegistry.ModuleEntry m) { return m.isEnabled(); }
            boolean set(ModuleRegistry.ModuleEntry m, String v) {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")
                        && !v.equalsIgnoreCase("1") && !v.equalsIgnoreCase("0") && !v.equalsIgnoreCase("on") && !v.equalsIgnoreCase("off")) return false;
                m.setEnabled(v.equalsIgnoreCase("true") || v.equalsIgnoreCase("1") || v.equalsIgnoreCase("on"));
                return true;
            }
        });
        PROPS.add(new Prop("hidden", "true/false", true) {
            Object get(ModuleRegistry.ModuleEntry m) { return m.isHidden(); }
            boolean set(ModuleRegistry.ModuleEntry m, String v) {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")
                        && !v.equalsIgnoreCase("1") && !v.equalsIgnoreCase("0") && !v.equalsIgnoreCase("on") && !v.equalsIgnoreCase("off")) return false;
                m.setHidden(v.equalsIgnoreCase("true") || v.equalsIgnoreCase("1") || v.equalsIgnoreCase("on"));
                return true;
            }
        });
        PROPS.add(new Prop("key", "key name / none (如 V, F1, LMB)", false) {
            Object get(ModuleRegistry.ModuleEntry m) { return KeyBindUtil.getKeyName(m.getKey()); }
            boolean set(ModuleRegistry.ModuleEntry m, String v) {
                int key = v.equalsIgnoreCase("none") || v.equalsIgnoreCase("null") || v.equalsIgnoreCase("0")
                        ? 0 : KeyBindUtil.getKeyIndex(v);
                m.setKey(key);
                return true;
            }
        });
        PROPS.add(new Prop("tag", "text", false) {
            Object get(ModuleRegistry.ModuleEntry m) { return m.tag; }
            boolean set(ModuleRegistry.ModuleEntry m, String v) { m.tag = v; return true; }
        });
    }

 /** 属性名匹配 */
    private static String normalize(String s) {
        return s.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private Prop getProperty(String name) {
        String n = normalize(name);
        for (Prop p : PROPS) {
            if (normalize(p.name).equals(n)) return p;
        }
        return null;
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        ModuleRegistry.ModuleEntry module = ModuleRegistry.get(args.get(0));
        if (module == null) {
            ChatUtil.sendFormatted(String.format("%sModule not found (&o%s&r)&r", ChatUtil.CLIENT_NAME, args.get(0)));
            return;
        }
        if (args.size() >= 2) {
            Prop prop = getProperty(args.get(1));
            if (prop == null) {
                ChatUtil.sendFormatted(String.format("%s%s has no property &o%s&r", ChatUtil.CLIENT_NAME, module.name, args.get(1)));
            } else if (args.size() < 3 && !prop.bool) {
 // 非布尔属性无第三参 → 只显示当前值
                ChatUtil.sendFormatted(
                        String.format("%s%s: &o%s&r is set to %s&r (%s)&r",
                                ChatUtil.CLIENT_NAME, module.name, prop.name, prop.format(module), prop.prompt));
            } else {
                String newValue = args.size() < 3 ? "true" : String.join(" ", args.subList(2, args.size()));
                try {
                    if (prop.set(module, newValue)) {
                        ChatUtil.sendFormatted(
                                String.format("%s%s: &o%s&r has been set to %s&r",
                                        ChatUtil.CLIENT_NAME, module.name, prop.name, prop.format(module)));
                        return;
                    }
                } catch (Exception ignored) {
                }
                ChatUtil.sendFormatted(
                        String.format("%sInvalid value for property &o%s&r (%s)&r",
                                ChatUtil.CLIENT_NAME, prop.name, prop.prompt));
            }
        } else {
            // 无参数 → 列出全部可见属性
            StringBuilder sb = new StringBuilder();
            for (Prop p : PROPS) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("&7").append(p.name).append("&r: ").append(p.format(module));
            }
            if (sb.length() == 0) {
                ChatUtil.sendFormatted(String.format("%s%s has no properties&r", ChatUtil.CLIENT_NAME, ListCommand.formatModule(module)));
            } else {
                ChatUtil.sendFormatted(String.format("%s%s&r: %s", ChatUtil.CLIENT_NAME, ListCommand.formatModule(module), sb));
            }
        }
    }
}
