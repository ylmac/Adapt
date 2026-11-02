package com.ylmac.adapt.module.render.gui;

import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 说明（用户要求：新建系统只放在 render 模块目录下，不污染项目其它位置）：
 * 本文件位于 {@code features/module/impl/render/gui/}。
 * <p>
 * 这里落地为**本地配置**：把模块开关状态存成
 * 点卡片即加载；另带 3 个内置预设（Default / Legit / Rage）。
 */
public final class RiseConfigs {

 /** 配置卡片 */
    public record Card(String title, String subtitle, Runnable action) { }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("rise-configs");
    }

 /** 内置预设 */
    public static List<Card> featured() {
        List<Card> out = new ArrayList<>();
        // ⚠ 预设只"启用清单内的模块"，**绝不关闭**其它模块 —
        // 之前 Default=全部关闭、Legit/Movement=关闭清单外的全部模块，
        // 用户点一下 HUD 模块列表里的条目就全"飞走"了（实测事故）。
        out.add(new Card("Legit", "启用合法向模块", () -> apply("sprint", "noslow", "autotool",
                "safewalk", "fastplace", "fullbright", "interface")));
        out.add(new Card("Movement", "启用移动向模块", () -> apply("sprint", "noslow", "step",
                "speed", "strafe", "inventorymove", "interface")));
        out.add(new Card("Render", "启用渲染向模块", () -> apply("interface", "fullbright", "esp",
                "scoreboard", "halo3d")));
        return out;
    }

    /** 只启用列出的模块（不动其它模块的开关）。 */
    private static void apply(String... ids) {
        for (String id : ids) {
            Module m = ModuleManager.INSTANCE.find(id);
            if (m != null) setSilently(m, true);
        }
    }

    private static boolean isIn(Module m, String... ids) {
        for (String id : ids) if (id.equalsIgnoreCase(m.getModuleId())) return true;
        return false;
    }

    private static void setSilently(Module m, boolean on) {
        try {
            if (m.isEnabled() != on) m.setEnabled(on);
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[RiseConfigs] set {} failed: {}", m.getModuleId(), t.toString());
        }
    }

    /** 用户配置卡片（含"保存当前"） */
    public static List<Card> userCards() {
        List<Card> out = new ArrayList<>();
        Map<String, Path> files = listFiles();
        for (Map.Entry<String, Path> e : files.entrySet()) {
            out.add(new Card(e.getKey(), "点击加载", () -> load(e.getKey())));
        }
        return out;
    }

    public static Map<String, Path> listFiles() {
        Map<String, Path> out = new LinkedHashMap<>();
        try {
            Path d = dir();
            if (!Files.isDirectory(d)) return out;
            try (var stream = Files.list(d)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".cfg"))
                        .sorted()
                        .forEach(p -> out.put(p.getFileName().toString().replace(".cfg", ""), p));
            }
        } catch (IOException e) {
            AdaptClient.LOGGER.warn("[RiseConfigs] list failed: {}", e.toString());
        }
        return out;
    }

    /** 保存当前模块状态（名字自动递增 config-N） */
    public static String saveCurrent() {
        String name = "config-" + (listFiles().size() + 1);
        save(name);
        return name;
    }

    public static boolean save(String name) {
        try {
            Path d = dir();
            Files.createDirectories(d);
            StringBuilder sb = new StringBuilder();
            for (Module m : ModuleManager.INSTANCE.getModules()) {
                sb.append(m.getModuleId()).append('=').append(m.isEnabled() ? 1 : 0).append('\n');
            }
            Files.writeString(d.resolve(name + ".cfg"), sb.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[RiseConfigs] save failed: {}", t.toString());
            return false;
        }
    }

    public static boolean load(String name) {
        try {
            Path f = dir().resolve(name + ".cfg");
            if (!Files.isRegularFile(f)) return false;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String id = line.substring(0, i).trim();
                boolean on = line.substring(i + 1).trim().equals("1");
                Module m = ModuleManager.INSTANCE.find(id);
                if (m != null) setSilently(m, on);
            }
            return true;
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[RiseConfigs] load failed: {}", t.toString());
            return false;
        }
    }

    public static boolean delete(String name) {
        try {
            return Files.deleteIfExists(dir().resolve(name + ".cfg"));
        } catch (Throwable t) {
            return false;
        }
    }
}
