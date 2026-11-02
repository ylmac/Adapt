package com.ylmac.adapt.module;

import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.event.bus.EventBus;
import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.KeyPressEvent;
import com.ylmac.adapt.event.impl.MousePressEvent;
import com.ylmac.adapt.module.util.KeybindUtils;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import com.ylmac.adapt.input.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.ylmac.adapt.render.hud.ModuleListOverlay;

/**
 * 模块管理器：注册/开关/按键分发。
 * <p>
 * 通知用日志代替）。自身订阅事件总线监听按键，按下模块绑定键时切换模块；
 * 模块启用状态同步到 {@link ModuleRegistry}，供 ModuleListOverlay HUD 显示。
 */
public class ModuleManager {

    public static final ModuleManager INSTANCE = new ModuleManager();

    private final List<Module> modules = new ArrayList<>();
    private final Map<String, Module> modulesById = new HashMap<>();

    private ModuleManager() {
        EventBus.INSTANCE.subscribe(this);
    }

    /** 注册模块（重复 moduleId 抛异常） */
    public synchronized void addModule(Module module) {
        if (module == null) throw new IllegalArgumentException("module 不能为空");
        if (module.getModuleId() == null || module.getModuleId().isBlank()) {
            throw new IllegalArgumentException("moduleId 不能为空");
        }
        String id = module.getModuleId();
        if (modulesById.containsKey(id)) {
            throw new IllegalArgumentException("重复 Module ID: " + id);
        }
        module.setAddonId("adapt");
        modulesById.put(id, module);
        modules.add(module);
        // 注册进 ModuleRegistry（ClickGUI/HUD 的视图层），否则 UI 找不到模块
        syncHud(module);
        AdaptClient.LOGGER.info("[ModuleManager] 注册模块: {} ({})", module.getName(), module.getCategory().getId());
    }

    public synchronized Module find(String moduleId) {
        return modulesById.get(moduleId);
    }

    public synchronized Module findByName(String name) {
        for (Module module : modules) {
            if (module.getName().equals(name)) return module;
        }
        return null;
    }

    public synchronized List<Module> getModules() {
        return Collections.unmodifiableList(new ArrayList<>(modules));
    }

    /** 切换模块（Toggle 模式），并同步 HUD */
    public boolean toggle(Module module) {
        module.toggle();
        syncHud(module);
        return module.isEnabled();
    }

    /** 设置模块启用状态，并同步 HUD */
    public boolean setEnabled(Module module, boolean enabled) {
        module.setEnabled(enabled);
        syncHud(module);
        return enabled;
    }

    /**
 * 把模块启用状态同步到 ModuleRegistry（HUD 数据源 + 开关联动）。
 * <p>
 * ModuleRegistry 以显示名（中文）为键 — 与 ClickGUI/ModuleListOverlay 一致。
 * 条目只映射真实模块（无假状态）：isEnabled 读真实模块，setEnabled 驱动真实模块。
 */
    public void syncHud(Module module) {
        ModuleRegistry.ModuleEntry entry = ModuleRegistry.registerModuleEntry(
                module.getName(), module.getCategory().getDisplayName(), module);
        entry.setHidden(module.isHidden());
        entry.setKey(module.getKeyBind() == -1 ? 0 : module.getKeyBind());
        // tag：模块 mode 类设置（EnumSetting 首项）实时显示
        String tag = module.getInfo();
        if (tag == null) tag = moduleModeTag(module);
        entry.tag = tag == null ? "" : tag;
    }

 /** 取模块第一个 EnumSetting 的当前值作 tag */
    private String moduleModeTag(Module module) {
        for (com.ylmac.adapt.module.settings.Setting<?> setting : module.getSettings()) {
            if (setting instanceof com.ylmac.adapt.module.settings.impl.EnumSetting<?> es) {
                return String.valueOf(es.getValue());
            }
        }
        return null;
    }

    private static boolean ourScreen(Screen screen) {
        return screen instanceof com.ylmac.adapt.compose.host.AdaptComposeScreen
                || screen instanceof com.ylmac.adapt.render.clickgui.RiseClickGuiScreen
                || screen instanceof com.ylmac.adapt.render.clickgui.ListClickGuiScreen;
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || event.getKey() == GLFW.GLFW_KEY_UNKNOWN) return;
        // 允许模块绑定键在我们自己的 GUI（ClickGUI / 音乐播放器）打开时也生效：
        // 绑定"音乐播放器"的键在界面打开时按下应能再次关闭（之前被 screen != null 挡住，
        // 导致只能按 ESC 关闭）。第三方界面（聊天/容器等）仍不响应。
        Screen screen = mc.gui.screen();
        if (screen != null && !ourScreen(screen)) return;
        dispatchKeyBind(event.getKey(), event.getAction());
    }

    @EventHandler
    private void onMousePress(MousePressEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Screen screen = mc.gui.screen();
        if (screen != null && !ourScreen(screen)) return;
        dispatchKeyBind(KeybindUtils.encodeMouseButton(event.getButton()), event.getAction());
    }

    /** 按键分发：Toggle 按下切换；Hold 按住启用、松开停用 */
    private void dispatchKeyBind(int keyCode, int action) {
        boolean isPress = action == InputConstants.PRESS;
        boolean isRelease = action == InputConstants.RELEASE;

        List<Module> affectedModules = new ArrayList<>();

        for (Module module : modules) {
            if (module.getKeyBind() != keyCode) continue;

            if (module.getBindMode() == Module.BindMode.Toggle && isPress) {
                affectedModules.add(module);
            } else if (module.getBindMode() == Module.BindMode.Hold) {
                if (isPress && !module.isEnabled()) {
                    affectedModules.add(module);
                } else if (isRelease && module.isEnabled()) {
                    affectedModules.add(module);
                }
            }
        }

        for (Module module : affectedModules) {
            if (module.getBindMode() == Module.BindMode.Toggle) {
                toggle(module);
            } else if (module.getBindMode() == Module.BindMode.Hold) {
                setEnabled(module, isPress);
            }
        }

        if (!affectedModules.isEmpty()) {
            AdaptClient.LOGGER.info("[ModuleManager] 按键 {} 触发 {} 个模块", keyCode, affectedModules.size());
        }
    }

}
