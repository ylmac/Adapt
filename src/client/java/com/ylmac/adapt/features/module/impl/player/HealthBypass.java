package com.ylmac.adapt.features.module.impl.player;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.event.impl.GameJoinedEvent;
import com.ylmac.adapt.features.event.impl.GameLeftEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HealthBypass 血量绕过（移植自 Rise 6.9.5 player/HealthBypass）。
 * <p>
 * Rise 原版逻辑：每个 Tick 遍历世界里的所有 {@link Player}（除自己），
 * 从「下方计分板（BELOW_NAME 槽位）」读取该玩家的血量分数，然后调用
 * {@code player.setHealth(score)} 直接覆盖客户端血量 —— 从而让
 * 「计分板血量」覆盖客户端各处的血量显示（血条、Tab 列表、头顶名称等），
 * 让反作弊 / 观战者看到的血量与计分板数值一致（即"绕血"）。
 * <p>
 * 26.2 移植要点（全部 javap 确证）：
 * <ul>
 *   <li>1.8.9 的 {@code scoreboard.getObjectiveInDisplaySlot(2)} → 26.2 为
 *       {@link Scoreboard#getDisplayObjective(DisplaySlot)} + {@code DisplaySlot.BELOW_NAME}；
 *       slot 2 即下方（BELOW_NAME）计分板。</li>
 *   <li>1.8.9 的 {@code scoreboard.getValueFromObjective(name, obj).getScorePoints()}
 *       → 26.2 为 {@link Scoreboard#getPlayerScoreInfo(ScoreHolder, Objective)}
 *       （返回 {@link ReadOnlyScoreInfo}，{@code value()} 读分数），
 *       {@link ScoreHolder#forNameOnly(String)} 按名字构造计分板持有者。</li>
 *   <li>实体遍历：1.8.9 用 {@code world.loadedEntityList} → 26.2 用
 *       {@code mc.level.entitiesForRendering()}（本项目其他模块同款）。</li>
 *   <li>血条显示：Rise 靠 {@code setHealth()} 覆盖实体数据（客户端血条/头顶都读它），
 *       26.2 同样走 {@link LivingEntity#setHealth(float)}（javap 确证存在），
 *       客户端 Tab 列表血量（PlayerTabOverlay）正是经
 *       {@code ClientLevel.getPlayerByUUID} 读实体血量渲染的（javap 字节码确证），
 *       因此 setHealth 一处即可同时覆盖：血条、Tab 列表心、头顶名称。</li>
 *   <li>额外能力（比 Rise 更完整的显示覆盖）：Tab 列表玩家名的后面可以直接拼上
 *       计分板血量文本 —— 通过 {@link PlayerInfo#setTabListDisplayName(Component)}
 *       （javap 确证 public setter）在本地改写 Tab 显示名。这样即使服务器不给
 *       玩家实体同步真实血量，Tab 列表上也能直观看到计分板数值。</li>
 * </ul>
 * <p>
 * 设置：Only Below Name（只对下方计分板存在时覆盖；默认 true）、
 * TabList Display（在 Tab 列表玩家名后附加血量文本；默认 true）。
 * <p>
 * 安全性：所有读取都做空判 + NaN/Inf 过滤 + 不超过最大血量（与 Rise 一致）；
 * 禁用或离开世界自动清除对 Tab 显示名的本地改写（还原服务器下发原名）。
 */
public class HealthBypass extends Module {

    private BoolSetting onlyBelowName;
    private BoolSetting tabListDisplay;

    /** 玩家 UUID → 服务器原始 Tab 显示名（本地追加血量前的样子，用于还原） */
    private final Map<java.util.UUID, net.minecraft.network.chat.MutableComponent> originalTabNames = new HashMap<>();

    public HealthBypass() {
        super("healthbypass", "HealthBypass", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        // 只使用「下方（BELOW_NAME）计分板」作为血量来源（Rise 原版固定 slot 2）
        onlyBelowName = boolSetting("仅下方计分板", true);
        // 在 Tab 列表玩家名后追加计分板血量文本
        tabListDisplay = boolSetting("Tab列表显示血量", true);
    }

    @Override
    protected void onEnable() {
        // 无世界时无意义，等首个 tick 再执行（onEnable 时 mc.level 可能为空）
        if (nullCheck()) return;
        applyHealthOverrides();
    }

    @Override
    protected void onDisable() {
        // 关闭：清除所有本地改写的 Tab 显示名（还原服务器下发名字）
        restoreTabListNames();
        originalTabNames.clear();
    }

    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (nullCheck()) return;
        // 每个 tick 全量覆盖：保证计分板数值变化时立即反映到客户端显示
        applyHealthOverrides();
    }

    /** 对世界内所有其它玩家应用计分板血量覆盖 */
    private void applyHealthOverrides() {
        if (mc.level == null) return;
        Scoreboard scoreboard = mc.level.getScoreboard();
        if (scoreboard == null) return;

        // 只读取「下方计分板」（等价 1.8.9 的 slot 2）
        Objective objective = onlyBelowName.getValue()
                ? scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.BELOW_NAME)
                : scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.LIST);
        if (objective == null) return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player player) || player == mc.player) {
                continue;
            }
            // 计分板血量（float，分数即血量）
            float scoreboardHealth = getScoreboardHealth(player, scoreboard, objective);
            if (Float.isNaN(scoreboardHealth) || Float.isInfinite(scoreboardHealth)) {
                continue; // 非法值：跳过（Rise 同样过滤）
            }
            // 死者不覆盖（客户端已认为死亡时保留死亡状态）
            if (scoreboardHealth <= 0.0F && !player.isDeadOrDying()) {
                continue;
            }
            // 不超过玩家最大血量（Rise：Math.min(f, f1)）
            float maxHealth = player.getMaxHealth();
            if (maxHealth > 0.0F) {
                scoreboardHealth = Math.min(scoreboardHealth, maxHealth);
            }
            // 覆盖客户端血量：血条 / Tab 心 / 头顶名称读到的都是它
            player.setHealth(scoreboardHealth);

            // Tab 列表显示名追加血量文本（本地显示层覆盖）
            if (tabListDisplay.getValue()) {
                updateTabListName(player, scoreboardHealth);
            }
        }
    }

    /** 读取某玩家在计分板上的血量（找不到时返回玩家实体当前血量，与 Rise 一致） */
    private float getScoreboardHealth(Player player, Scoreboard scoreboard, Objective objective) {
        if (scoreboard == null || objective == null) {
            return player.getHealth();
        }
        ReadOnlyScoreInfo info = scoreboard.getPlayerScoreInfo(
                ScoreHolder.forNameOnly(player.getScoreboardName()), objective);
        return info == null ? player.getHealth() : info.value();
    }

    /** 在 Tab 列表（PlayerInfo）显示名后附加血量文本（从原始名重建，避免重复叠加） */
    private void updateTabListName(Player player, float health) {
        if (mc.getConnection() == null) return;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            if (info.getProfile() != null
                    && info.getProfile().id() != null
                    && info.getProfile().id().equals(player.getUUID())) {
                // 首次见到的玩家：缓存服务器原始显示名（null = 用 Profile 名）
                originalTabNames.computeIfAbsent(info.getProfile().id(), uuid ->
                        info.getTabListDisplayName() instanceof net.minecraft.network.chat.MutableComponent mc
                                ? mc.copy() : null);
                net.minecraft.network.chat.MutableComponent base = originalTabNames.get(info.getProfile().id()) != null
                        ? originalTabNames.get(info.getProfile().id()).copy()
                        : Component.literal(player.getScoreboardName());
                // 拼接血量：原名 + " ❤ " + 数值
                Component combined = base
                        .append(Component.literal(" \u2764 "))
                        .append(Component.literal(String.format(Locale.ROOT, "%.1f", health)));
                info.setTabListDisplayName(combined);
                break;
            }
        }
    }

    /** 还原所有玩家 Tab 显示名为服务器下发的原始名（去掉本地追加的血量） */
    private void restoreTabListNames() {
        if (mc.getConnection() == null) return;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            if (info.getProfile() == null || info.getProfile().id() == null) continue;
            Component original = originalTabNames.get(info.getProfile().id());
            // 有缓存：还原原始名；无缓存且带 ❤ 标记：清空（恢复 Profile 名）
            if (original != null) {
                info.setTabListDisplayName(original.copy());
            } else {
                Component current = info.getTabListDisplayName();
                if (current != null && current.getString().contains("\u2764")) {
                    info.setTabListDisplayName(null);
                }
            }
        }
    }

    /** 世界切换/离开：清空显示名缓存（旧世界的玩家已不存在） */
    @EventHandler
    private void onWorldChange(GameJoinedEvent event) {
        originalTabNames.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        originalTabNames.clear();
    }

    @Override
    public String getInfo() {
        return onlyBelowName.getValue() ? "下方计分板" : "列表计分板";
    }
}
