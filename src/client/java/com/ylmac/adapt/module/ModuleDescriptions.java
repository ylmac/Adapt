package com.ylmac.adapt.module;

import java.util.HashMap;
import java.util.Map;

/**
 * 模块的一句话说明 — ClickGUI 列表里作为副标题显示。
 * <p>
 * 标题加一行副标题。
 * <p>
 * 集中放在这里而不是塞进 Module 类，是为了避免改动几十个模块类；没有登记的
 * 模块返回一个通用的兜底文案（见 {@link #get}）。
 */
public final class ModuleDescriptions {

    private static final Map<String, String> MAP = new HashMap<>();

    static {
        // ── 战斗 ──
        put("KillAura", "自动攻击范围内的目标");
        put("AutoClicker", "按住时自动连点左键");
        put("Criticals", "攻击时自动打出暴击");
        put("Velocity", "减少受到的击退");
        put("AimAssist", "平滑地把准心拉向目标");
        put("Reach", "延长攻击与交互距离");
        put("BackTrack", "回溯目标的历史位置再打击");
        put("NoMiss", "保证挥击必定命中");
        put("TargetStrafe", "围绕目标自动绕圈走位");
        put("WTap", "攻击后自动松开 W 键");

        // ── 移动 ──
        put("Speed", "提升地面移动速度");
        put("Fly", "自由飞行");
        put("Sprint", "自动保持疾跑");
        put("LongJump", "起跳时获得更远的水平距离");
        put("Step", "自动跨上更高的台阶");
        put("Scaffold", "移动时自动在前方铺方块");
        put("Jesus", "在水面上行走");
        put("NoSlowDown", "使用物品或潜行时不减速");
        put("Spider", "贴墙时自动向上攀爬");
        put("Blink", "暂缓同步位置，实现瞬移效果");
        put("AntiVoid", "掉落虚空时自动拉回");
        put("AutoWalk", "自动持续向前移动");
        put("ElytraFlight", "控制鞘翅滑翔与加速");
        put("NoFall", "消除落地伤害");
        put("AirStuck", "在空中定住不动");
        put("Parkour", "走到边缘自动起跳");

        // ── 玩家 ──
        put("AutoRespawn", "死亡后自动重生");
        put("AutoTool", "自动切换到合适的工具");
        put("AutoArmor", "自动穿戴更好的装备");
        put("AutoHeal", "血量过低时自动治疗");
        put("AutoPot", "自动投掷药水");
        put("AutoMLG", "落地瞬间自动放水或方块");
        put("FastPlace", "取消放置方块的冷却");
        put("NoRotate", "不向服务端同步视角旋转");
        put("Stealer", "自动取走容器内的物品");
        put("ChestStealer", "打开箱子时自动拿走物品");
        put("InventoryManager", "自动整理背包");
        put("AutoFish", "自动钓鱼并收杆");
        put("AutoEat", "饥饿时自动进食");
        put("FastBreak", "加快挖掘方块的速度");
        put("BedNuker", "自动破坏附近的床");
        put("AutoLeave", "条件满足时自动退出");
        put("HealthBypass", "绕过服务器的血量校验");
        put("Regen", "加快生命恢复");

        // ── 渲染 ──
        put("Hats", "在玩家头顶显示装饰");
        put("ESP", "透视显示实体轮廓");
        put("ESP2D", "在屏幕上绘制实体方框");
        put("BedPlates", "标出床的位置与保护范围");
        put("Shaders", "光影总开关：延迟光照 / 方向阴影 / 云");
        put("MusicWidget", "音乐小组件，右 Command 拖动位置");
        put("Fullbright", "提高亮度，看清暗处");
        put("NoFog", "消除视野中的迷雾");
        put("NoHurtCam", "取消受伤时的镜头抖动");
        put("NoBob", "取消行走时的视角摇晃");
        put("Trajectories", "预测投掷物飞行轨迹");
        put("Chams", "高亮显示实体");
        put("NameTags", "显示更详细的玩家名牌");
        put("ItemESP", "标记地面上的掉落物");
        put("StorageESP", "标记箱子等容器");
        put("FreeCam", "视角脱离角色自由移动");
        put("Zoom", "放大视野");
        put("PositionLogger", "记录并导出坐标");

        // ── 设置 / 其它 ──
        put("ClickGui", "打开功能设置界面");
        put("Hud", "屏幕上的信息显示");
        put("MusicPlayer", "内置音乐播放器");
        put("CustomFont", "自定义界面字体");
        put("ClientSettings", "客户端整体配置");
    }

    private ModuleDescriptions() {
    }

    private static void put(String name, String desc) {
        MAP.put(name, desc);
    }

    /**
 * 取模块说明；未登记时返回所在分类的兜底文案，保证列表两行结构一致。
 */
    public static String get(String moduleName, String categoryName) {
        if (moduleName != null) {
            String d = MAP.get(moduleName);
            if (d != null) return d;
        }
        if (categoryName == null || categoryName.isEmpty()) return "点击右侧开关启用";
        return categoryName + "类功能";
    }
}
