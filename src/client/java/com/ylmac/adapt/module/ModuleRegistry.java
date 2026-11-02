package com.ylmac.adapt.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ylmac.adapt.render.hud.ModuleListOverlay;

/**
 * 模块注册表 — 纯视图层，所有状态以真实 Module 为准（无任何假状态/回退）。
 * <p>
 * - 条目只映射真实模块（ModuleManager 注册时 link）。
 * - isEnabled/setEnabled 直接读/写真实模块；未关联真实模块的条目视为不存在（不显示）。
 * - 没有真实实现的"假模块"不再注册 — ClickGUI/命令/HUD 全部只处理真实状态。
 */
public final class ModuleRegistry {

    public static final class ModuleEntry {
        public final String name;
        public final String category;
        public String tag;      // 附加信息（模式名），真实模块 getInfo 同步
        private boolean hidden; // HUD 隐藏（.hide/.show 命令用）
        private int key;        // 按键绑定（GLFW 键码或 -100-鼠标按钮，0=无，.bind 命令用）
        /** 关联的真实模块（必须非空；无真实模块的条目不会被注册） */
        private com.ylmac.adapt.module.Module realModule;

        ModuleEntry(String name, String category, String tag) {
            this.name = name;
            this.category = category;
            this.tag = tag;
        }

        /** 状态 = 真实模块状态（唯一状态源） */
        public boolean isEnabled() { return realModule != null && realModule.isEnabled(); }

        /** 开关 = 驱动真实模块（Module.setEnabled 负责 EventBus 订阅/退订） */
        public void setEnabled(boolean e) {
            if (realModule != null && realModule.isEnabled() != e) {
                realModule.setEnabled(e);
            }
        }

        public boolean isHidden() { return hidden; }
        public void setHidden(boolean h) { this.hidden = h; }
        public int getKey() { return key; }

        public void setKey(int k) {
            this.key = k;
            // 反向联动：.bind 设置的按键同步到真实模块（ModuleManager.dispatchKeyBind 读真实模块的 keyBind）
            if (realModule != null && realModule.getKeyBind() != (k == 0 ? -1 : k)) {
                realModule.setKeyBind(k == 0 ? -1 : k);
            }
        }

        /** 绑定真实模块（ModuleManager.addModule → syncHud 时调用） */
        public void linkRealModule(com.ylmac.adapt.module.Module module) {
            this.realModule = module;
            this.key = module.getKeyBind() == -1 ? 0 : module.getKeyBind();
            this.hidden = module.isHidden();
        }

        /** 真实模块引用（诊断/内部用） */
        public com.ylmac.adapt.module.Module linkedRealModule() {
            return realModule;
        }
    }

    private static final Map<String, ModuleEntry> MODULES = new LinkedHashMap<>();

    private ModuleRegistry() {}

    /**
 * 注册真实模块条目（ModuleManager.addModule → syncHud 调用）。
 * 同名条目已存在则复用并重新关联。
 */
    public static ModuleEntry registerModuleEntry(String name, String category, com.ylmac.adapt.module.Module module) {
        ModuleEntry e = MODULES.get(name);
        if (e == null) {
            e = new ModuleEntry(name, category, "");
            MODULES.put(name, e);
        }
        e.linkRealModule(module);
        return e;
    }

    /** 按名字取条目（不存在返回 null） */
    public static ModuleEntry get(String name) {
        return MODULES.get(name);
    }

    /** 切换模块启用状态（驱动真实模块），返回切换后的状态 */
    public static boolean toggle(String name) {
        ModuleEntry e = MODULES.get(name);
        if (e == null) return false;
        e.setEnabled(!e.isEnabled());
        return e.isEnabled();
    }

    /** 模块是否启用（真实状态） */
    public static boolean isEnabled(String name) {
        ModuleEntry e = MODULES.get(name);
        return e != null && e.isEnabled();
    }

    /** 所有已启用模块（ModuleListOverlay 渲染用） */
    public static List<ModuleEntry> getActiveModules() {
        List<ModuleEntry> out = new ArrayList<>();
        for (ModuleEntry e : MODULES.values()) {
            if (e.isEnabled()) out.add(e);
        }
        return out;
    }

    public static List<ModuleEntry> getAllModules() {
        return new ArrayList<>(MODULES.values());
    }

    /** 设置模块隐藏状态（.hide/.show 命令），返回是否设置成功 */
    public static boolean setHidden(String name, boolean hidden) {
        ModuleEntry e = MODULES.get(name);
        if (e == null) return false;
        e.setHidden(hidden);
        return true;
    }

    /** 设置模块按键绑定（.bind 命令），返回是否设置成功 */
    public static boolean setKey(String name, int key) {
        ModuleEntry e = MODULES.get(name);
        if (e == null) return false;
        e.setKey(key);
        return true;
    }

    /** 所有已绑定按键的模块（.bind list 用） */
    public static List<ModuleEntry> getBoundModules() {
        List<ModuleEntry> out = new ArrayList<>();
        for (ModuleEntry e : MODULES.values()) {
            if (e.getKey() != 0) out.add(e);
        }
        return out;
    }
}
