package com.ylmac.adapt.module.combat;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AntiBot — 过滤机器人目标。
 * <p>
 * 基岩 / 重复 UUID / 颜色 / 可见时间 / 中键 / Advanced / Advanced2），全部挂在一个
 * {@code BotManager}（Set&lt;Entity&gt;）上：任意模式把实体标记为 bot 后，
 * KillAura 等模块的目标扫描就把它排除掉。
 * <p>
 * - 本模块把"标记/识别"集中到静态方法 {@link #isBot(Entity)}：任何模块
 * {@code BotManager.isBot(entity)}。
 * - 每个 SubMode 一个开关（BoolSetting），事件用 {@code ClientTickEvent.Pre}
 * 逐 tick 扫描。Advanced2 的
 * v4 即 bot"判定（见 {@link #isAdvanced2Bot} 注释）。
 * - "Tab Check"依赖 26.2 的 {@code ClientPacketListener.getListedOnlinePlayers}
 * 拿到完整 TAB 列表（javap 确证）。
 * - "No Ping Check"读 {@code PlayerInfo.getLatency}（&lt; 0 表示客户端还没
 * - 名字/颜色检查用 {@code Entity.getName.getString} 的原始格式化文本
 * （含 § 颜色码）。
 * - 自述/行为异常（NPCDetectionCheck）在 26.2 无法直接读到 1.8.9 的 isBot
 * 字段，用可观测行为替代：无敌（isInvulnerable）、无渲染（isInvisible）、
 * 无 TAB（getPlayerInfo 为 null）且名字含 § 色码 → 判定为 bot。
 * - 附加基础过滤：
 * KillAura 还有 visible/hurtTime 过滤，这里保留最常被用于反作弊的几项）。
 * <p>
 * 使用：{@code AntiBot.get.isBot(entity)}（模块关闭时返回 false）。
 */
public class AntiBot extends Module {

    /** 单例引用（KillAura / KeepRange 等模块经此调用静态 isBot） */
    private static AntiBot instance;

    public static AntiBot get() {
        return instance;
    }

 /** 本模块已识别出的 bot 实体 id 集合 */
    private final Set<Integer> botIds = new HashSet<>();
 /** 重复名字检查用：名字 → 首次出现的实体 id */
    private final java.util.Map<String, Integer> nameToEntityId = new java.util.HashMap<>();

    /** 名字含 § 色码 → 正常（Funcraft 服务器专服判定，反着看：无色码=bot） */
    private BoolSetting funcraftCheck;
    /** 不在 TAB 列表里 → bot */
    private BoolSetting tabCheck;
    /** 行为异常（无敌/无渲染/无 TAB 且带色码）→ bot */
    private BoolSetting npcDetection;
    /** 世界里有重复显示名 → 全部标记为 bot */
    private BoolSetting duplicateNameCheck;
    /** 找不到 TAB 延迟（getLatency &lt; 0）→ bot */
    private BoolSetting noPingCheck;
    /** 名字格式化模式异常（Cubecraft 基岩版 bot 特征）→ bot */
    private BoolSetting cubecraftBedrockCheck;
    /** 重复实体 ID / 重复 UUID → bot */
    private BoolSetting duplicateUniqueIdCheck;
 /** 名字不是以颜色码开头 → bot */
    private BoolSetting colourCheck;
    /** 实体可见时间不足 / 刚刷出未满 20 tick → bot（避免打出生点无敌的假人） */
    private BoolSetting timeVisibleCheck;
    /** 无敌实体（isInvulnerable）→ bot */
    private BoolSetting invulnerableCheck;
    /** 无渲染实体（isInvisible）→ bot */
    private BoolSetting invisibleCheck;
    /** 高级：TAB 已捕获后，不在 TAB 的玩家 → bot；捕获前不判定 */
    private BoolSetting advanced;
    /** 高级 2：重复名字但实体 id 不同 → bot；UUID 非 v4 → bot */
    private BoolSetting advanced2;
 /** NPC 阵营过滤 */
    private BoolSetting npcFilter;

    public AntiBot() {
        super("antibot", "AntiBot", Category.COMBAT);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);

        // ---- 基础过滤（默认开启，等价格式化后的 KillAura 目标过滤） ----
        invulnerableCheck = boolSetting("过滤无敌实体", true);
        invisibleCheck = boolSetting("过滤无渲染实体", true);
        npcFilter = boolSetting("过滤NPC阵营", true);

        funcraftCheck = boolSetting("Funcraft Check", false);
        tabCheck = boolSetting("Tab Check", false);
        npcDetection = boolSetting("NPC Detection Check", false);
        duplicateNameCheck = boolSetting("Duplicate Name Check", false);
        noPingCheck = boolSetting("No Ping Check", false);
        cubecraftBedrockCheck = boolSetting("Cubecraft Bedrock Check", false);
        duplicateUniqueIdCheck = boolSetting("Duplicate Unique ID Check", false);
        colourCheck = boolSetting("Colour Check", false);
        timeVisibleCheck = boolSetting("Time Visible Check", false);
        advanced = boolSetting("Advanced", false);
        advanced2 = boolSetting("Advanced 2", false);
    }

    @Override
    protected void onEnable() {
        botIds.clear();
        nameToEntityId.clear();
    }

    @Override
    protected void onDisable() {
        botIds.clear();
        nameToEntityId.clear();
    }

 // ================= 逐 tick 扫描 =================

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;
        scanBots();
    }

 /** 扫描世界内所有玩家，按各开关把 bot 标记进 botIds */
    private void scanBots() {
        Entity self = mc.player;
        if (self == null) return;

        List<Entity> players = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player && entity != self) {
                players.add(entity);
            }
        }

        // 名字 → 出现次数（Duplicate Name / Colour / Cubecraft 共用一次遍历）
        java.util.Map<String, Integer> nameCounts = new java.util.HashMap<>();
        for (Entity e : players) {
            String name = getRawName(e);
            nameCounts.merge(name, 1, Integer::sum);
        }

        for (Entity entity : players) {
            boolean isBot = false;

            // 1. 基础过滤（默认开）
            if (invulnerableCheck.getValue() && isInvulnerableCheck(entity)) {
                isBot = true;
            }
            if (invisibleCheck.getValue() && isInvisibleCheck(entity)) {
                isBot = true;
            }
            if (npcFilter.getValue() && isNpc(entity)) {
                isBot = true;
            }

            // 2. Funcraft Check（名字无 § 色码 → bot）
            if (!isBot && funcraftCheck.getValue()) {
                if (!getRawName(entity).contains("§")) {
                    isBot = true;
                }
            }

            // 3. Tab Check（不在 TAB 列表 → bot）
            if (!isBot && tabCheck.getValue() && isTabBot(entity)) {
                isBot = true;
            }

            // 4. NPC Detection（行为异常 → bot）
            if (!isBot && npcDetection.getValue() && isBehaviorBot(entity)) {
                isBot = true;
            }

            // 5. Duplicate Name（重复显示名 → bot）
            if (!isBot && duplicateNameCheck.getValue()) {
                String n = getRawName(entity);
                if (nameCounts.getOrDefault(n, 0) > 1) {
                    isBot = true;
                }
            }

            // 6. No Ping Check（无延迟信息 → bot）
            if (!isBot && noPingCheck.getValue() && isNoPingBot(entity)) {
                isBot = true;
            }

            // 7. Cubecraft Bedrock Check（格式化异常 → bot）
            if (!isBot && cubecraftBedrockCheck.getValue() && isCubecraftBedrockBot(entity)) {
                isBot = true;
            }

            // 8. Duplicate Unique ID（重复实体 ID / UUID → bot）
            if (!isBot && duplicateUniqueIdCheck.getValue() && isDuplicateUidBot(entity, players)) {
                isBot = true;
            }

            // 9. Colour Check（名字不是色码开头 → bot）
            if (!isBot && colourCheck.getValue()) {
                if (!startsWithColorCode(getRawName(entity))) {
                    isBot = true;
                }
            }

            // 10. Time Visible Check（可见时间不足 → bot）
            if (!isBot && timeVisibleCheck.getValue() && isTimeVisibleBot(entity)) {
                isBot = true;
            }

            // 11. Advanced（TAB 捕获完成后，不在 TAB 的玩家 → bot）
            if (!isBot && advanced.getValue() && isAdvancedTabBot(entity)) {
                isBot = true;
            }

            // 12. Advanced 2（重复名字不同 id / UUID 非 v4 → bot）
            if (!isBot && advanced2.getValue() && isAdvanced2Bot(entity)) {
                isBot = true;
            }

            if (isBot) {
                botIds.add(entity.getId());
            } else {
                botIds.remove(entity.getId());
            }
        }
    }

 // ================= 各检查实现 =================

 /** Tab Check：TAB 列表里找不到该玩家 → bot */
    private boolean isTabBot(Entity entity) {
        return mc.getConnection() == null
                || mc.getConnection().getPlayerInfo(entity.getUUID()) == null;
    }

    /**
 * NPC Detection Check：行为异常 → bot。
 * 用三合一行为特征替代：无敌（防杀假人）或（无渲染 且 不在 TAB 且 名字带色码）。
 */
    private boolean isBehaviorBot(Entity entity) {
        if (entity.isInvulnerable()) {
            return true;
        }
        if (entity.isInvisible()) {
            PlayerInfo info = mc.getConnection() == null
                    ? null : mc.getConnection().getPlayerInfo(entity.getUUID());
            return info == null && getRawName(entity).contains("§");
        }
        return false;
    }

    /** No Ping Check：TAB 里没有延迟信息（getLatency &lt; 0）→ bot */
    private boolean isNoPingBot(Entity entity) {
        PlayerInfo info = mc.getConnection() == null
                ? null : mc.getConnection().getPlayerInfo(entity.getUUID());
        return info != null && info.getLatency() < 0;
    }

 /** Cubecraft Bedrock Check：格式化异常特征 → bot */
    private boolean isCubecraftBedrockBot(Entity entity) {
        String raw = getRawName(entity);
        String stripped = stripFormatting(raw);
        return raw.chars().filter(c -> c == '§').count() >= 8L
                && stripped.contains("\u0000");
    }

    /** Duplicate Unique ID Check：世界里有相同实体 ID 或相同 UUID 的其它实体 → bot */
    private boolean isDuplicateUidBot(Entity entity, List<Entity> players) {
        for (Entity other : players) {
            if (other == entity) continue;
            if (other.getId() == entity.getId()) return true;
            if (other.getUUID().equals(entity.getUUID())) return true;
        }
        return false;
    }

 /** Time Visible Check：实体存在 tick 过少 → bot */
    private boolean isTimeVisibleBot(Entity entity) {
        return entity.tickCount < 20;
    }

    /**
 * Advanced：先等 150 tick + KillAura 有目标后捕获
 * TAB 列表是实时全量的（getListedOnlinePlayers），直接用其判缺失即可。
 */
    private boolean isAdvancedTabBot(Entity entity) {
        return isTabBot(entity);
    }

    /**
 * Advanced 2：重复名字 + 实体 id 不同 → bot；
 * UUID 非 v4 版本 → bot；玩家名映射首次记录。
 * 请求（隐私/离线考量），保留本地可判定的两项。
 */
    private boolean isAdvanced2Bot(Entity entity) {
        String name = getRawName(entity);
        Integer firstId = nameToEntityId.putIfAbsent(name, entity.getId());
        if (firstId != null && firstId != entity.getId()) {
            return true;
        }
        return entity.getUUID().version() != 4;
    }

 // ================= 基础过滤实现 =================

    /** 无敌实体（含创造模式无敌 / 被保护实体）→ bot */
    private boolean isInvulnerableCheck(Entity entity) {
        return entity.isInvulnerable();
    }

    /** 无渲染实体（隐身）→ bot */
    private boolean isInvisibleCheck(Entity entity) {
        return entity.isInvisible();
    }

    /** NPC 阵营过滤：非玩家实体类型（村民/盔甲架等）→ bot */
    private boolean isNpc(Entity entity) {
        // 26.2 EntityType 是注册表 Holder 封装，无 1.8.9 的 EntityType.PLAYER 常量；
        // 直接以 instanceof Player 判定玩家即可（与 KillAura 目标判定一致）
        return !(entity instanceof Player);
    }

    // ================= 公共静态接口 =================

    /**
 * 实体是否为 bot。
 * 供 KillAura / KeepRange / 其它战斗模块调用：
 * <pre>{@code if (AntiBot.get != null && AntiBot.get.isBot(entity)) continue;}</pre>
 * 模块关闭时恒返回 false（不干扰原版行为）。
 */
    public boolean isBot(Entity entity) {
        if (!this.isEnabled() || entity == null) return false;
        // 永不标记自己
        if (entity == mc.player) return false;
        return botIds.contains(entity.getId());
    }

    // ================= 工具方法 =================

 /** 取实体原始名字 */
    private static String getRawName(Entity entity) {
        if (entity == null) return "";
        return entity.getName().getString();
    }

    private static boolean startsWithColorCode(String name) {
        return name != null && !name.isEmpty() && name.matches("^§[0-9a-fk-or].*");
    }

    private static String stripFormatting(String name) {
        if (name == null) return null;
        String stripped = name.replaceAll("§.", "");
        return stripped.replaceAll("\\p{Cntrl}", "").trim();
    }

    @Override
    public String getInfo() {
        return botIds.size() + " bot";
    }
}
