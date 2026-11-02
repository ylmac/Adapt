package com.ylmac.adapt.render.clickgui;

import com.ylmac.adapt.module.ModuleManager;
import com.ylmac.adapt.module.ModuleRegistry;
import com.ylmac.adapt.module.settings.Setting;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.module.settings.impl.ColorSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.EnumSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import com.ylmac.adapt.compose.screens.music.AdaptScreen;
import com.ylmac.adapt.render.color.WorldColorSampler;
import net.minecraft.client.Minecraft;

import java.awt.Color;
import java.util.List;
import com.ylmac.adapt.render.hud.ModuleListOverlay;

/**
 * ClickGUI 和模块设置之间的桥。静态单例，Compose 和命令共用同一份。
 * 方法内动态获取 Minecraft.getInstance，不缓存任何可能过期的引用。
 */
public class ClickGuiBridge {

    public static final ClickGuiBridge INSTANCE = new ClickGuiBridge();

    /** ClickGUI 窗口缩放（.clickgui scale 命令修改） */
    public static volatile float windowScale = 1.0f;
    /** ClickGUI 窗口圆角半径（.clickgui corner 命令修改） */
    public static volatile float cornerRadius = 16.0f;

    private ClickGuiBridge() {
        // 不注册任何假模块 — 模块列表完全来自 ModuleRegistry（真实模块经 ModuleManager 注册）。
        // 界面模块（模块列表）作为特殊条目由 ModuleListOverlay 管理，见 toggleModule。
    }

    /**
 * 返回分类+模块的 JSON。
 * 结构：[{name, icon, modules:[{name, action, enabled, tag}]}]
 * 只包含真实模块（ModuleRegistry 中已关联真实 Module 的条目）。
 */
    public String getCategoriesJson() {
        StringBuilder sb = new StringBuilder("[");
        // 分类顺序 + 图标
        String[][] cats = {
            {"战斗", "swords"}, {"玩家", "person"}, {"移动", "directions_run"},
            {"渲染", "brush"}, {"设置", "settings"}
        };
        boolean firstCat = true;
        for (String[] cat : cats) {
            if (!firstCat) sb.append(",");
            firstCat = false;
            sb.append("{\"name\":\"").append(cat[0]).append("\",\"icon\":\"").append(cat[1]).append("\",\"modules\":[");
            boolean firstMod = true;
            for (ModuleRegistry.ModuleEntry e : ModuleRegistry.getAllModules()) {
                if (e.linkedRealModule() == null) continue;  // 只列真实模块
                if (!e.category.equals(cat[0])) continue;
                if (!firstMod) sb.append(",");
                firstMod = false;
                // desc：一句话说明，作为列表项的副标题（的
                // 是 标题 + 副标题 两行结构）
                sb.append("{\"name\":\"").append(escape(e.name))
                  .append("\",\"action\":\"toggle")
                  .append("\",\"enabled\":").append(e.isEnabled())
                  .append(",\"tag\":\"").append(escape(e.tag != null ? e.tag : ""))
                  .append("\",\"desc\":\"")
                  .append(escape(com.ylmac.adapt.module.ModuleDescriptions
                          .get(e.name, e.category)))
                  .append("\"}");
            }
            sb.append("]}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
 * 切换模块启用状态（的 Switch 调用），返回切换后的状态。
 * Interface 是真实模块（onEnable/onDisable 控制 HUD 显示），走正常 toggle；
 * 界面模式是它的 EnumSetting（设置覆盖层里选择），不在这里循环切换。
 */
    public boolean toggleModule(String name) {
        return ModuleRegistry.toggle(name);
    }

    /** 读取模块是否启用 */
    public boolean isModuleEnabled(String name) {
        ModuleRegistry.ModuleEntry e = ModuleRegistry.get(name);
        return e != null && e.isEnabled();
    }

    /** 读取模块 tag（模式名等），供 刷新显示 */
    public String getModuleTag(String name) {
        ModuleRegistry.ModuleEntry e = ModuleRegistry.get(name);
        return e != null ? e.tag : "";
    }

    /**
 * 读取模块的按键绑定（GLFW 键码或 -100-鼠标按钮，0=无），供 显示。
 */
    public int getModuleKey(String name) {
        ModuleRegistry.ModuleEntry e = ModuleRegistry.get(name);
        return e != null ? e.getKey() : 0;
    }

    /**
 * 返回模块设置 JSON（点击模块行时拉取）。
 * <p>
 * 数据源：ModuleManager 里同名的真实模块（settings 列表）。假模块/未实现模块返回 "[]"，
 * 此时 仅显示开关与 tag。结构：
 * [{name, type, value, min, max, step, options:[...], color:{r,g,b,a}}]
 * type ∈ enum | bool | int | double | choice | string | color | other
 */
    public String getModuleSettings(String moduleName) {
        com.ylmac.adapt.module.Module m = ModuleManager.INSTANCE.findByName(moduleName);
        if (m == null) return "[]";
        List<Setting<?>> settings = m.getSettings();
        if (settings == null || settings.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Setting<?> s : settings) {
            if (s instanceof EnumSetting<?> es) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(escape(s.getName()))
                  .append("\",\"type\":\"enum\",\"value\":").append(es.getModeIndex())
                  .append(",\"options\":[");
                Object[] constants = es.getModes();
                for (int i = 0; i < constants.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escape(String.valueOf(constants[i]))).append("\"");
                }
                sb.append("]}");
            } else if (s instanceof BoolSetting bs) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(escape(s.getName()))
                  .append("\",\"type\":\"bool\",\"value\":").append(Boolean.TRUE.equals(bs.getValue())).append("}");
            } else if (s instanceof IntSetting is) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(escape(s.getName()))
                  .append("\",\"type\":\"int\",\"value\":").append(is.getValue())
                  .append(",\"min\":").append(is.getMin())
                  .append(",\"max\":").append(is.getMax())
                  .append(",\"step\":").append(Math.max(1, is.getStep())).append("}");
            } else if (s instanceof DoubleSetting ds) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(escape(s.getName()))
                  .append("\",\"type\":\"double\",\"value\":").append(ds.getValue())
                  .append(",\"min\":").append(ds.getMin())
                  .append(",\"max\":").append(ds.getMax())
                  .append(",\"step\":").append(Math.max(0.0001, ds.getStep())).append("}");
            } else if (s instanceof ChoiceSetting cs) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(escape(s.getName()))
                  .append("\",\"type\":\"choice\",\"value\":").append(cs.getChoiceIndex())
                  .append(",\"options\":[");
                List<String> choices = cs.getChoices();
                for (int i = 0; i < choices.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escape(choices.get(i))).append("\"");
                }
                sb.append("]}");
            } else if (s instanceof ColorSetting cs) {
                if (!first) sb.append(",");
                first = false;
                Color c = cs.getValue();
                sb.append("{\"name\":\"").append(escape(s.getName()))
                  .append("\",\"type\":\"color\",\"color\":{")
                  .append("\"r\":").append(c.getRed())
                  .append(",\"g\":").append(c.getGreen())
                  .append(",\"b\":").append(c.getBlue())
                  .append(",\"a\":").append(c.getAlpha())
                  .append("}}");
            } else if (s instanceof com.ylmac.adapt.module.settings.impl.StringSetting ss) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(escape(s.getName()))
                  .append("\",\"type\":\"string\",\"value\":\"").append(escape(ss.getValue())).append("\"}");
            } else if (s instanceof com.ylmac.adapt.module.settings.impl.ButtonSetting) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(escape(s.getName()))
                  .append("\",\"type\":\"button\"}");
            } else {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(escape(s.getName()))
                  .append("\",\"type\":\"other\",\"value\":\"").append(escape(String.valueOf(s.getValue()))).append("\"}");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /**
 * 设置模块设置值（的 ComboBox/Switch/Slider/色板调用）。
 * <p>
 * value 为原始 JSON 值：enum/choice 传索引（int），bool 传 bool，
 * int/double 传数值，color 传 "#RRGGBB" 或 "#AARRGGBB"。无真实模块时静默忽略。
 */
    public void setModuleSetting(String moduleName, String settingName, Object value) {
        com.ylmac.adapt.module.Module m = ModuleManager.INSTANCE.findByName(moduleName);
        if (m == null) return;
        for (Setting<?> s : m.getSettings()) {
            if (!s.getName().equals(settingName)) continue;
            applySettingValue(s, value);
            // tag（模式名）由 ModuleManager.syncHud 维护，但 ClickGUI 内直接改设置不会触发 sync —
            // 这里手动同步一次 HUD 条目（ModuleRegistry），保证 HUD/列表 tag 一致。
            ModuleRegistry.ModuleEntry e = ModuleRegistry.get(moduleName);
            if (e != null) {
                String tag = m.getInfo();
                if (tag == null) {
                    for (Setting<?> st : m.getSettings()) {
                        if (st instanceof EnumSetting<?> es) {
                            tag = String.valueOf(es.getValue());
                            break;
                        }
                    }
                }
                e.tag = tag == null ? "" : tag;
            }
            return;
        }
    }

    /** 按类型把 value 写入 setting（int/double 走 clamp，enum/choice 走索引，color 走 #RRGGBB 解析） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applySettingValue(Setting<?> s, Object value) {
        if (s instanceof IntSetting is) {
            if (value instanceof Number n) is.setValue(n.intValue());
        } else if (s instanceof DoubleSetting ds) {
            if (value instanceof Number n) ds.setValue(n.doubleValue());
        } else if (s instanceof BoolSetting bs) {
            bs.setValue(Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value)));
        } else if (s instanceof EnumSetting es) {
            if (value instanceof Number n) {
                Object[] constants = es.getModes();
                int idx = n.intValue();
                if (idx >= 0 && idx < constants.length) es.setValue(constants[idx]);
            } else if (value != null) {
                es.setMode(String.valueOf(value));
            }
        } else if (s instanceof ChoiceSetting cs) {
            if (value instanceof Number n) {
                List<String> choices = cs.getChoices();
                int idx = n.intValue();
                if (idx >= 0 && idx < choices.size()) cs.setValue(choices.get(idx));
            } else if (value != null) {
                cs.setValue(String.valueOf(value));
            }
        } else if (s instanceof ColorSetting cs) {
            Color c = parseColor(String.valueOf(value));
            if (c != null) cs.setValue(c);
        } else if (s instanceof com.ylmac.adapt.module.settings.impl.StringSetting ss) {
            if (value != null) ss.setValue(String.valueOf(value));
        } else if (s instanceof com.ylmac.adapt.module.settings.impl.ButtonSetting bs) {
            // 按钮： 侧传 "click"，真正要做的是执行设置携带的 Runnable
            // （之前这里没有分支 → 点了按钮什么都不会发生，Hats 的"导入图片"
            // 这类入口就是因此打不开）。
            Runnable action = bs.getValue();
            if (action != null) {
                try {
                    action.run();
                } catch (Throwable t) {
                    com.ylmac.adapt.AdaptClient.LOGGER.warn(
                            "[ClickGui] button '{}' action failed: {}", s.getName(), t.toString());
                }
            }
        }
    }

    /** 解析 "#RRGGBB" / "#AARRGGBB" / "r,g,b[,a]"；失败返回 null */
    private static Color parseColor(String s) {
        if (s == null) return null;
        try {
            String t = s.trim();
            if (t.startsWith("#")) {
                String hex = t.substring(1);
                if (hex.length() == 6) {
                    return new Color(Integer.parseInt(hex, 16), false);
                } else if (hex.length() == 8) {
                    return new Color((int) Long.parseLong(hex, 16), true);
                }
                return null;
            }
            String[] parts = t.split(",");
            if (parts.length >= 3) {
                int r = (int) Math.round(Double.parseDouble(parts[0].trim()));
                int g = (int) Math.round(Double.parseDouble(parts[1].trim()));
                int b = (int) Math.round(Double.parseDouble(parts[2].trim()));
                int a = parts.length >= 4 ? (int) Math.round(Double.parseDouble(parts[3].trim())) : 255;
                return new Color(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)),
                        Math.max(0, Math.min(255, b)), Math.max(0, Math.min(255, a)));
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
 * 打开音乐播放器（兼容入口）：toggle 真实"音乐播放器"模块
 * （MusicPlayerModule.onEnable 打开 AdaptScreen）。
 */
    public void openMusicPlayer() {
        WorldColorSampler.get().forceSnapshot();
        toggleModule("MusicPlayer");
    }

    /**
 * 关闭 ClickGUI。
 */
    public void close() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}