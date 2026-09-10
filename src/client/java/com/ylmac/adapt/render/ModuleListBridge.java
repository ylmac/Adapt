package com.ylmac.adapt.render;

import com.ylmac.adapt.features.module.ModuleRegistry;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * 暴露给 ModuleList.qml 的数据桥接对象。
 * <p>
 * QML 通过 context("moduleList", this) 访问：
 * - getActiveModules()：所有模块（含 enabled/tag），QML 做动画与布局
 * - getMode()/setMode()：界面模式（Rise InterfaceModeValue）
 * - getPlayerName()：玩家名（Classic/Creida 附加信息）
 */
public class ModuleListBridge {

    /** 与 ModuleListOverlay 共享的模式（返回混合大小写以匹配 QML：Modern/Classic/Wurst/Creida） */
    public String getMode() {
        String n = ModuleListOverlay.get().mode.name();
        return n.charAt(0) + n.substring(1).toLowerCase();
    }

    public void setMode(String mode) {
        try {
            ModuleListOverlay.get().mode = ModuleListOverlay.Mode.valueOf(mode.toUpperCase());
        } catch (Exception ignored) {}
    }

    /** 所有模块（含已禁用）—— QML 端自行过滤 enabled 并做动画 */
    public List<ModuleData> getModules() {
        List<ModuleData> out = new ArrayList<>();
        for (ModuleRegistry.ModuleEntry m : ModuleRegistry.getAllModules()) {
            out.add(new ModuleData(m.name, m.tag, m.isEnabled()));
        }
        return out;
    }

    public String getPlayerName() {
        try {
            return Minecraft.getInstance().getUser().getName();
        } catch (Exception e) {
            return "";
        }
    }

    /** 玩家坐标（Classic 左下 XYZ 显示用），返回 [x, y, z] */
    public double[] getPlayerPos() {
        try {
            var p = Minecraft.getInstance().player;
            if (p != null) return new double[]{Math.floor(p.getX()), Math.floor(p.getY()), Math.floor(p.getZ())};
        } catch (Exception ignored) {}
        return new double[]{0, 0, 0};
    }

    /** QML 可见的简单数据载体 */
    public static final class ModuleData {
        public final String name;
        public final String tag;
        public final boolean enabled;
        ModuleData(String name, String tag, boolean enabled) {
            this.name = name;
            this.tag = tag;
            this.enabled = enabled;
        }
    }
}