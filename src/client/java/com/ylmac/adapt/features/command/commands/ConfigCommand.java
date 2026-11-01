package com.ylmac.adapt.features.command.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.config.store.AppDirs;
import com.ylmac.adapt.features.command.Command;
import com.ylmac.adapt.features.module.ModuleRegistry;
import com.ylmac.adapt.util.ChatUtil;

import java.awt.Desktop;
import java.io.File;
import java.io.FileFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置保存/加载命令（移植自 myau ConfigCommand）。
 * <p>
 * .config load &lt;名字&gt; / .config save [名字] / .config list / .config folder
 * - l 无第三参 = list，有第三参 = load；"default"/"!" = 默认配置
 * - 配置内容：模块启用状态 + 隐藏状态 + 按键绑定（26.2 无 myau 的 Config 系统，简化版存 JSON）
 * - list 输出可点击组件（点击即 .config load 该配置）
 */
public class ConfigCommand extends Command {

    /** 上次使用的配置名（myau Config.lastConfig 对应物） */
    public static volatile String lastConfig = "default";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final FileFilter FILE_FILTER = (File pathname) -> pathname.isFile() && pathname.getName().toLowerCase(Locale.ROOT).endsWith(".json");

    public ConfigCommand() {
        super(new ArrayList<>(Arrays.asList("config", "cfg", "c")));
    }

    /** 配置目录：.qplayer-mod/configs/ */
    private static File configDir() {
        File dir = new File(AppDirs.base(), "configs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 保存当前模块状态为指定配置（myau Config.save 对应物） */
    public static void save(String name) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            List<String> enabled = new ArrayList<>();
            List<String> hidden = new ArrayList<>();
            Map<String, Integer> binds = new HashMap<>();
            for (ModuleRegistry.ModuleEntry m : ModuleRegistry.getAllModules()) {
                if (m.isEnabled()) enabled.add(m.name);
                if (m.isHidden()) hidden.add(m.name);
                if (m.getKey() != 0) binds.put(m.name, m.getKey());
            }
            data.put("enabled", enabled);
            data.put("hidden", hidden);
            data.put("binds", binds);
            Path f = Paths.get(configDir().getAbsolutePath(), name + ".json");
            Files.write(f, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
            lastConfig = name;
            ChatUtil.sendFormatted(String.format("%sConfig &o%s&r saved&r", ChatUtil.CLIENT_NAME, name));
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sFailed to save config &o%s&r&r", ChatUtil.CLIENT_NAME, name));
        }
    }

    /** 从指定配置恢复模块状态（myau Config.load 对应物） */
    @SuppressWarnings("unchecked")
    public static boolean load(String name) {
        try {
            Path f = Paths.get(configDir().getAbsolutePath(), name + ".json");
            if (!Files.exists(f)) return false;
            Map<String, Object> data = GSON.fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), Map.class);
            if (data == null) return false;
            // 先全部重置
            for (ModuleRegistry.ModuleEntry m : ModuleRegistry.getAllModules()) {
                m.setEnabled(false);
                m.setHidden(false);
                m.setKey(0);
            }
            List<String> enabled = (List<String>) data.getOrDefault("enabled", new ArrayList<String>());
            List<String> hidden = (List<String>) data.getOrDefault("hidden", new ArrayList<String>());
            Map<String, Number> binds = (Map<String, Number>) data.getOrDefault("binds", new HashMap<String, Number>());
            for (String n : enabled) {
                ModuleRegistry.ModuleEntry m = ModuleRegistry.get(n);
                if (m != null) m.setEnabled(true);
            }
            for (String n : hidden) {
                ModuleRegistry.ModuleEntry m = ModuleRegistry.get(n);
                if (m != null) m.setHidden(true);
            }
            for (Map.Entry<String, Number> e : binds.entrySet()) {
                ModuleRegistry.ModuleEntry m = ModuleRegistry.get(e.getKey());
                if (m != null) m.setKey(e.getValue().intValue());
            }
            lastConfig = name;
            return true;
        } catch (Exception e) {
            AdaptClient.LOGGER.warn("[ConfigCommand] load {} failed: {}", name, e.toString());
            return false;
        }
    }

    /** 供 ClickGuiCommand.save 调用：保存到默认配置 */
    public void saveDefault() {
        save("default");
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            String command = args.get(0).toLowerCase(Locale.ROOT);
            ChatUtil.sendFormatted(
                    String.format("%sUsage: .%s &oload&r/&osave&r <&oname&r> | .%s &olist&r | .%s &ofolder&r", ChatUtil.CLIENT_NAME, command, command, command)
            );
        } else {
            String subCommand = args.get(1);
            if (subCommand.equalsIgnoreCase("l")) {
                subCommand = args.size() < 3 ? "list" : "load";
            }
            String sub = subCommand.toLowerCase(Locale.ROOT);
            switch (sub) {
                case "load":
                case "reload":
                    if (args.size() < 3) {
                        ChatUtil.sendFormatted(
                                String.format("%sMissing config name (use '&odefault&r' or '&o!&r' to load default config)&r", ChatUtil.CLIENT_NAME)
                        );
                        return;
                    }
                    String name = args.get(2);
                    if (name.equals("!")) name = "default";
                    if (load(name)) {
                        ChatUtil.sendFormatted(String.format("%sConfig &o%s&r loaded&r", ChatUtil.CLIENT_NAME, name));
                    } else {
                        ChatUtil.sendFormatted(String.format("%sConfig &o%s&r not found&r", ChatUtil.CLIENT_NAME, name));
                    }
                    return;
                case "s":
                case "save":
                    if (args.size() < 3) {
                        save(lastConfig);
                        return;
                    }
                    save(args.get(2));
                    return;
                case "list":
                    try {
                        File[] configs = configDir().listFiles(FILE_FILTER);
                        if (configs == null || configs.length == 0) {
                            ChatUtil.sendFormatted(String.format("%sNo configs found&r", ChatUtil.CLIENT_NAME));
                            return;
                        }
                        Arrays.sort(configs, Comparator.comparingLong(File::lastModified).reversed());
                        ChatUtil.sendFormatted(String.format("%sConfigs:&r", ChatUtil.CLIENT_NAME));
                        for (File file : configs) {
                            String base = file.getName().substring(0, file.getName().length() - 5); // 去 .json
                            String cfg = ".config load " + base;
                            ChatUtil.send(com.ylmac.adapt.util.ChatUtil.clickable(
                                    String.format("&7»&r &o%s&r", base), cfg));
                        }
                    } catch (Exception e) {
                        ChatUtil.sendFormatted(String.format("%sFailed to read configs&r", ChatUtil.CLIENT_NAME));
                    }
                    return;
                case "f":
                case "folder":
                case "dir":
                case "directory":
                    try {
                        Desktop.getDesktop().open(configDir());
                    } catch (Exception e) {
                        ChatUtil.sendFormatted(String.format("%sFailed to open config folder&r", ChatUtil.CLIENT_NAME));
                    }
                    return;
                default:
                    ChatUtil.sendFormatted(String.format("%sInvalid argument (&o%s&r)&r", ChatUtil.CLIENT_NAME, args.get(1)));
            }
        }
    }
}
