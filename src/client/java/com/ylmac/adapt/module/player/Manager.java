package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.ModuleManager;
import com.ylmac.adapt.module.ModuleRegistry;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * <p>
 * <ol>
 * <li><b>扫描</b>：遍历背包 0..39 号格（0-8 热栏、9-35 主背包、36-39 盔甲），按
 * 物品类型分类（方块/药水/食物/盔甲/剑/镐/斧/锹/弓/钓鱼竿），对每类选出
 * 「最好的一件」（伤害最高剑、效率最高工具、护甲值最高盔甲…），其余列为
 * "多余"待处理。</li>
 * <li><b>优先级处理链</b>（每 tick 只做一步，配合点击延迟）：
 * 清空光标 → 换 4 件盔甲 → 移动工具到指定热栏格（剑/二剑/镐/斧/锹/弓/钓竿）→
 * 移动方块/药水/食物到指定热栏区间 → 按上限丢多余的方块/箭/桶/雪球鸡蛋/末影珍珠
 * → 丢第一个无用物品 → 丢多余的剑/工具/盔甲。</li>
 * <li><b>规则</b>：Legit（不开物品栏则不工作/关了自动关）、Grim 反作弊点击节流
 * （每 tick 限点击数并暂时禁疾跑）、Delay 随机点击间隔、各类数量上限
 * （方块/箭/桶/雪球鸡蛋/末影珍珠）、各工具热栏槽位、药水优先溅射、可自定义
 * 保护物品（"自定义物品"：带 CUSTOM_DATA 的物品默认不碰）。</li>
 * </ol>
 * <p>
 * 26.2 API 对应（已 javap 确证）：
 * - 点击：{@code MultiPlayerGameMode.handleContainerInput(containerId, slotNum, button,
 * ContainerInput, player)} — 内部调 {@code AbstractContainerMenu.clicked} 修改本地
 * 容器状态，并自动打包 {@link ServerboundContainerClickPacket}（含 stateId + 变更槽
 * 哈希）发给服务器；等价 1.8.9 的 {@code playerController.windowClick}。
 * - 丢整组 = THROW + button 1，丢 1 个 = THROW + button 0；移动到热栏 = SWAP + 目标格。
 * - 打开背包 = {@code player.sendOpenInventory}（发 OPEN_INVENTORY 状态包）；
 * 关闭 = 直接发 {@link ServerboundContainerClosePacket}（仅通知，不关屏幕）。
 * - 槽位映射与 1.8.9 完全一致：热栏 0-8 → 窗口 36-44；主背包 9-35 → 窗口 9-35；
 * 盔甲 → 窗口 5-8（头盔 5/胸甲 6/护腿 7/靴子 8）。InventoryMenu 构造顺序已确认。
 * - 物品分类用 ItemTags（SWORDS/PICKAXES/AXES/SHOVELS/HOES/BOW_ENCHANTABLE 等）与
 * 类判断（BlockItem/PotionItem/ThrowablePotionItem）替代 1.8.9 的 ItemSword 等类。
 * - 附魔等级用 {@code EnchantmentHelper.getItemEnchantmentLevel(Holder, ItemInstance)}。
 * - 护甲评分：EQUIPPABLE 组件（含槽位）判断 + 防御 = 装备栏对应装备的
 * ARMOR 属性值；附魔保护加成 = 保护/弹射物保护/火焰保护/爆炸保护等级。
 * <p>
 */
public class Manager extends Module {

 // ================= 配置项 =================

    /** 点击间隔（ms，随机范围 100-150） */
    private final DoubleSetting delay;
 /** 合法模式：非自开物品栏时不整理 */
    private final BoolSetting legit;
    /** 不丢弃自定义物品（带自定义 NBT 的物品） */
    private final BoolSetting dropCustomItems;
    /** 不使用自定义物品（移动/排序时也不碰） */
    private final BoolSetting useCustomItems;
    /** 药水槽优先放溅射药水 */
    private final BoolSetting prioritizeSplashPotions;
    /** 方块数量上限 */
    private final IntSetting blockLimit;
    /** 箭数量上限 */
    private final IntSetting arrowLimit;
    /** 桶数量上限 */
    private final IntSetting bucketLimit;
    /** 雪球/鸡蛋数量上限 */
    private final IntSetting snowballEggLimit;
    /** 末影珍珠数量上限 */
    private final IntSetting enderPearlLimit;
    /** 剑槽位（1-9，0=禁用） */
    private final IntSetting swordSlot;
    /** 第二把剑槽位 */
    private final IntSetting secondSwordSlot;
    /** 镐槽位 */
    private final IntSetting pickaxeSlot;
    /** 斧槽位 */
    private final IntSetting axeSlot;
    /** 锹槽位 */
    private final IntSetting shovelSlot;
    /** 方块槽位区间（起始） */
    private final IntSetting blockSlotStart;
    /** 方块槽位区间（结束） */
    private final IntSetting blockSlotEnd;
    /** 药水槽位区间（起始） */
    private final IntSetting potionSlotStart;
    /** 药水槽位区间（结束） */
    private final IntSetting potionSlotEnd;
    /** 弓槽位 */
    private final IntSetting bowSlot;
    /** 钓竿槽位 */
    private final IntSetting rodSlot;
    /** 食物槽位区间（起始） */
    private final IntSetting foodSlotStart;
    /** 食物槽位区间（结束） */
    private final IntSetting foodSlotEnd;

 // ================= 内部状态 =================

    /** 看容器（箱子/熔炉/工作台）剩余 tick，防止刚关容器就开背包 */
    private int containerLookTicks = 0;
    /** 主背包物品栏（0-39 格，含盔甲）首格 */
    private static final int INV_FIRST = 0;
    /** 主背包物品栏末格（含盔甲） */
    private static final int INV_LAST = 39;
    /** 靴子槽 */
    private static final int SLOT_BOOTS = 36;
    /** 护腿槽 */
    private static final int SLOT_LEGGINGS = 37;
    /** 胸甲槽 */
    private static final int SLOT_CHESTPLATE = 38;
    /** 头盔槽 */
    private static final int SLOT_HELMET = 39;

    /** 点击节流计时器（毫秒） */
    private long nextClick = 0L;
    /** 距上次看容器的 tick */
    private int chestTicks = 0;
    /** 距上次攻击的 tick */
    private int attackTicks = 0;
    /** 距上次放置方块的 tick */
    private int placeTicks = 0;
    /** 本 tick 是否移动了物品（用于 idle 判定） */
    private boolean moved = false;
    /** 是否已发出"打开背包"包（服务器物品栏窗口已打开） */
    private boolean open = false;
    /** 待发送"关闭背包"包 */
    private boolean pendingClose = false;
    /** 本 tick 点击次数（Grim 防疾跑） */
    private int clicksThisTick = 0;
    /** 禁用疾跑剩余 tick */
    private int sprintDisableTicks = 0;
    /** 物品栏是否在工作（打开中/即将打开） */
    private boolean inventoryOpen = false;
    /** 打开后的初始延迟 tick（防止刚打开就点击） */
    private int openDelayTicks = 0;
    /** 空闲 tick 计数（连续没动作就关闭物品栏） */
    private int idleTicks = 0;

    public Manager() {
        super("manager", "Manager", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        delay = doubleSetting("点击延迟", 100.0, 0.0, 500.0, 50.0);
        legit = boolSetting("合法模式", false);
        dropCustomItems = boolSetting("丢弃自定义物品", false);
        useCustomItems = boolSetting("使用自定义物品", false);
        prioritizeSplashPotions = boolSetting("优先溅射药水", false);
        blockLimit = intSetting("方块上限", 512, 0, 512, 4);
        arrowLimit = intSetting("箭上限", 128, 0, 512, 4);
        bucketLimit = intSetting("桶上限", 1, 0, 4, 1);
        snowballEggLimit = intSetting("雪球/鸡蛋上限", 16, 0, 64, 1);
        enderPearlLimit = intSetting("末影珍珠上限", 16, 0, 64, 1);
        swordSlot = intSetting("剑槽位", 1, 0, 9, 1);
        secondSwordSlot = intSetting("第二剑槽位", 2, 0, 9, 1);
        pickaxeSlot = intSetting("镐槽位", 2, 0, 9, 1);
        axeSlot = intSetting("斧槽位", 3, 0, 9, 1);
        shovelSlot = intSetting("锹槽位", 4, 0, 9, 1);
        blockSlotStart = intSetting("方块槽起始", 5, 0, 9, 1);
        blockSlotEnd = intSetting("方块槽结束", 5, 0, 9, 1);
        potionSlotStart = intSetting("药水槽起始", 6, 0, 9, 1);
        potionSlotEnd = intSetting("药水槽结束", 6, 0, 9, 1);
        bowSlot = intSetting("弓槽位", 7, 0, 9, 1);
        rodSlot = intSetting("钓竿槽位", 8, 0, 9, 1);
        foodSlotStart = intSetting("食物槽起始", 9, 0, 9, 1);
        foodSlotEnd = intSetting("食物槽结束", 9, 0, 9, 1);
    }

    // ================= 事件订阅 =================

    /**
 * 主循环：每 tick 检查是否需要整理，按优先级链执行一步。
 */
    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (player.tickCount <= 40) return;

        // ---- 每 tick 状态维护（审计修复） ----
        // 发包爆发计数清零（badPackets 用的窗口统计）
        sentPacketsThisTick = 0;
        // 强制传送回弹窗口递减（badPackets 判定）
        if (teleportBounceTicks > 0) teleportBounceTicks--;
        // 整理期间禁疾跑（Grim 防疾跑）：每次点击后设置 sprintDisableTicks，
        // 期间强制 setSprinting(false)，防止"边疾跑边点击"触发反作弊
        if (sprintDisableTicks > 0) {
            sprintDisableTicks--;
            if (player.isSprinting()) {
                player.setSprinting(false);
            }
        }

        // 准星看向容器（箱子/末影箱/熔炉/工作台）时挂起整理，避免误点
        if (isLookingAtContainer(4.5)) {
            containerLookTicks = 10;
        } else if (containerLookTicks > 0) {
            containerLookTicks--;
        }

        // 处理待关闭：屏幕已关、无坏包（Grim：不吃反弹的位置包/状态包）、
        // 不再看容器 或 已经过了 10 tick — 才真正发关闭包
        if (pendingClose && mc.gui.screen() == null && open
                && !badPackets() && (containerLookTicks == 0 || placeTicks >= 10)) {
            sendCloseWindow();
            pendingClose = false;
            open = false;
        }

        if (isForeignContainerOpen()) {
            chestTicks = 0; // 外部容器（箱子等）打开时重置冷却
        } else {
            chestTicks++;
        }

        attackTicks++;
        placeTicks++;

        if (legit.getValue() && !(mc.gui.screen() instanceof InventoryScreen)) {
            // 合法模式且物品栏没开：停止工作并关闭已打开的
            resetClock();
            closeInventory(false);
            return;
        }
        if (!canClickNow() || chestTicks < 10 || attackTicks < 10 || placeTicks < 10
                || isScaffoldEnabled()) {
            // 冷却未到 / 刚看容器 / 刚攻击 / 刚放置 / 搭路中：不动作
            closeInventory(false);
            return;
        }
        if (isForeignContainerOpen()) {
            closeInventory(false);
            return;
        }
        // 需要物品栏已打开（自开或玩家自己开的）才能点击
        if (!isInventoryMoveAvailable() && !(mc.gui.screen() instanceof InventoryScreen)) {
            closeInventory(false);
            return;
        }

        moved = false;
        InventoryScan scan = scanInventory();
        boolean needs = needsWork(scan);
        if (!inventoryOpen) {
            if (!needs) return;
            openInventory();
            if (openDelayTicks > 0) {
                openDelayTicks--;
                return;
            }
        } else {
            if (!needs) {
                closeInventory(true);
                return;
            }
            if (openDelayTicks > 0) {
                openDelayTicks--;
                return;
            }
        }

        // 优先级链：清光标 → 换盔甲 → 移工具 → 移方块 → 移药水 → 移食物 →
        // 丢超量（方块/箭/桶/雪球鸡蛋/末影珍珠）→ 丢无用 → 丢多余好物
        if (clearCursorStack()) {
            updateIdle();
        } else if (swapArmor(0, scan.helmet)) {
            updateIdle();
        } else if (swapArmor(1, scan.chestplate)) {
            updateIdle();
        } else if (swapArmor(2, scan.leggings)) {
            updateIdle();
        } else if (swapArmor(3, scan.boots)) {
            updateIdle();
        } else if (moveTools(scan)) {
            updateIdle();
        } else if (moveBlocks(scan)) {
            updateIdle();
        } else if (movePotions(scan)) {
            updateIdle();
        } else if (moveFood(scan)) {
            updateIdle();
        } else if (dropExcess(scan.blockSlots, blockLimit.getValue(), stack -> stack.getItem() instanceof BlockItem)) {
            updateIdle();
        } else if (dropExcess(scan.arrowSlots, arrowLimit.getValue(), stack -> stack.is(ItemTags.ARROWS))) {
            updateIdle();
        } else if (dropExcess(scan.bucketSlots, bucketLimit.getValue(), Manager::isBucket)) {
            updateIdle();
        } else if (dropExcess(scan.snowballEggSlots, snowballEggLimit.getValue(), Manager::isSnowballOrEgg)) {
            updateIdle();
        } else if (dropExcess(scan.enderPearlSlots, enderPearlLimit.getValue(), stack -> stack.is(Items.ENDER_PEARL))) {
            updateIdle();
        } else {
            int useless = firstUselessSlot();
            if (useless != -1 && canDrop(useless)) {
                dropStack(useless);
                updateIdle();
            } else {
                int extra = firstExtraSlot(scan);
                if (extra != -1 && canDrop(extra)) {
                    dropStack(extra);
                    updateIdle();
                } else {
                    updateIdle();
                }
            }
        }
    }

    /** 收到服务器确认点击（可选，仅诊断用—实际点击同步由 handleContainerInput 自动完成） */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.getPacket() instanceof ServerboundContainerClickPacket) {
            // 26.2 的点击包由 MultiPlayerGameMode.handleContainerInput 自动发送，
            // 这里只统计每 tick 的点击次数用于 Grim 疾跑节流
            clicksThisTick++;
        }
        // 统计本 tick 发包总量（badPackets 爆发检测）
        sentPacketsThisTick++;
    }

    /** 收到强制传送（服务器位置回弹）→ 标记坏包窗口（badPackets 判定） */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) {
            teleportBounceTicks = 10;
        }
    }

    @Override
    protected void onDisable() {
        closeInventory(false);
        clicksThisTick = 0;
        sprintDisableTicks = 0;
        moved = false;
    }

    // ================= 内部工具方法 =================

    /** 是否正看向容器方块（箱子/末影箱/熔炉/工作台/潜影盒） */
    private boolean isLookingAtContainer(double range) {
        Player player = mc.player;
        if (player == null || mc.level == null) return false;
        HitResult hit = player.pick(range, 1.0F, false);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        Block block = mc.level.getBlockState(pos).getBlock();
        return block instanceof ChestBlock || block instanceof EnderChestBlock
                || block instanceof FurnaceBlock || block instanceof CraftingTableBlock;
    }

    /** 是否有外部容器（非背包）屏幕打开 */
    private boolean isForeignContainerOpen() {
        Screen screen = mc.gui.screen();
        return screen instanceof AbstractContainerScreen<?> && !(screen instanceof InventoryScreen);
    }

 /** Grim 反作弊坏包检测。
 * 26.2 无 BadPacketsComponent 等价实现，用以下可观测信号近似（均为 javap 确证的
 * 26.2 数据源）：
 * <ol>
 * <li><b>发包爆发</b>：某一 tick 内客户端发包数量异常高（Connection.send 计数
 *，包括本模块的点击包与移动包），说明本地存在未消化的输出（服务器拒收
 * /网络拥塞），此时继续发容器点击包会被一并丢弃 — 判定为坏包。</li>
 * <li><b>强制传送回弹</b>：收到 {@link ClientboundPlayerPositionPacket}（服务器
 * 强制传送，通常是位置校验失败/速度异常触发的回弹），说明服务器认为本地
 * "服务器反弹位置包" 判定）。</li>
 * </ol>
 */
    private boolean badPackets() {
        // 1) 发包爆发：超过每 tick 10 个包即视为坏包（正常整理每 tick ≤ 3 个点击包）
        if (sentPacketsThisTick > 10) {
            return true;
        }
        // 2) 强制传送回弹：最近收到过服务器强制传送 → 10 tick 内不发容器包
        if (teleportBounceTicks > 0) {
            return true;
        }
        return false;
    }

    /** 最近收到的强制传送回弹 tick 数（badPackets 用；收到位置包后重置为 10 并递减） */
    private int teleportBounceTicks = 0;

    /** 本 tick 已发送的包数量（PacketEvent.Send 统计；每 tick 开头清零） */
    private int sentPacketsThisTick = 0;

 /** 是否可以在不开屏幕的情况下开背包 */
    private boolean isInventoryMoveAvailable() {
        // 等价实现：非搭路、无屏幕、未按住前进/后退键 → 打开物品栏不影响操作
        return mc.gui.screen() == null && !isScaffoldEnabled();
    }

    /**
 * 搭路模块是否启用。
 * 审计修复：旧实现查 ModuleRegistry.get("ScaffoldState") 不存在（真实注册名是
 * Scaffold 的 moduleId "scaffold"），恒返回 false。改为查 ModuleManager（以
 * moduleId 为键的稳定注册表），并同时兜底 ModuleRegistry 的显示名键。
 */
    private boolean isScaffoldEnabled() {
        Module scaffold = ModuleManager.INSTANCE.find("scaffold");
        if (scaffold != null) return scaffold.isEnabled();
        ModuleRegistry.ModuleEntry entry = ModuleRegistry.get("Scaffold");
        return entry != null && entry.isEnabled();
    }

    /** 是否到了下一次点击时间 */
    private boolean canClickNow() {
        return System.currentTimeMillis() >= nextClick;
    }

 /** 重置点击时钟 */
    private void resetClock() {
        nextClick = 0L;
    }

    /** 打开物品栏：自开（无屏幕时发 OPEN_INVENTORY 包）或检测到已开 */
    private void openInventory() {
        idleTicks = 0;
        if (isForeignContainerOpen()) return;
        if (mc.gui.screen() instanceof InventoryScreen) {
            inventoryOpen = true;
            openDelayTicks = 0;
        } else {
            if (isInventoryMoveAvailable() && !open && mc.gui.screen() == null && !badPackets()) {
                // 静默打开服务器物品栏（等价 1.8.9 C16PacketClientStatus OPEN_INVENTORY）
                if (mc.player != null) {
                    mc.player.sendOpenInventory();
                }
                registerClick();
                open = true;
                inventoryOpen = true;
                openDelayTicks = 1;
            }
        }
    }

    /**
 * 关闭物品栏。
 *
 * @param send 是否实际发送关闭包（false = 仅重置本地状态，如合法模式被打断）
 */
    private void closeInventory(boolean send) {
        if (!inventoryOpen) return;
        if (open) {
            if (send && mc.gui.screen() == null && !badPackets()) {
                // 屏幕已关：直接发关闭包；否则标记待关（等屏幕关闭后发）
                sendCloseWindow();
                open = false;
            } else if (!send) {
                // 立即放弃（不发包，服务器窗口会自然超时/下次同步重置）
                open = false;
            } else {
                pendingClose = true;
            }
        }
        inventoryOpen = false;
        openDelayTicks = 0;
        idleTicks = 0;
    }

    /** 发关闭窗口包（26.2：ServerboundContainerClosePacket，containerId=0 背包） */
    private void sendCloseWindow() {
        if (mc.getConnection() == null) return;
        mc.getConnection().getConnection().send(new ServerboundContainerClosePacket(0), null);
    }

 /** 空闲判定：连续 2 tick 没动作 → 关物品栏 */
    private void updateIdle() {
        if (moved) {
            idleTicks = 0;
        } else {
            idleTicks++;
            if (idleTicks >= 2) {
                closeInventory(true);
            }
        }
    }

    // ================= 扫描 =================

    /** 扫描整个背包（0-39），按类别归类并选出最好的物品 */
    private InventoryScan scanInventory() {
        Player player = mc.player;
        InventoryScan scan = new InventoryScan();
        List<Integer> helmets = new ArrayList<>();
        List<Integer> chestplates = new ArrayList<>();
        List<Integer> leggings = new ArrayList<>();
        List<Integer> boots = new ArrayList<>();
        List<Integer> swords = new ArrayList<>();
        List<Integer> pickaxes = new ArrayList<>();
        List<Integer> axes = new ArrayList<>();
        List<Integer> shovels = new ArrayList<>();
        List<Integer> bows = new ArrayList<>();
        List<Integer> rods = new ArrayList<>();

        for (int i = INV_FIRST; i <= INV_LAST; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (!isUseful(stack)) {
                scan.useless.add(i);
            } else {
                if (item instanceof BlockItem) scan.blocks.add(new SlotStack(i, stack));
                if (item instanceof PotionItem) scan.potions.add(new SlotStack(i, stack));
                if (stack.get(DataComponents.FOOD) != null) scan.foods.add(new SlotStack(i, stack));
                if (isArmor(stack)) {
                    switch (armorType(stack)) {
                        case 0 -> helmets.add(i);
                        case 1 -> chestplates.add(i);
                        case 2 -> leggings.add(i);
                        case 3 -> boots.add(i);
                    }
                } else if (stack.is(ItemTags.SWORDS)) {
                    swords.add(i);
                } else if (stack.is(ItemTags.PICKAXES)) {
                    pickaxes.add(i);
                } else if (stack.is(ItemTags.AXES)) {
                    axes.add(i);
                } else if (stack.is(ItemTags.SHOVELS)) {
                    shovels.add(i);
                } else if (item instanceof net.minecraft.world.item.BowItem) {
                    bows.add(i);
                } else if (item instanceof net.minecraft.world.item.FishingRodItem) {
                    rods.add(i);
                }
            }
        }

        scan.helmet = bestSlot(helmets, scan.spareHelmets, this::armorScore);
        scan.chestplate = bestSlot(chestplates, scan.spareChestplates, this::armorScore);
        scan.leggings = bestSlot(leggings, scan.spareLeggings, this::armorScore);
        scan.boots = bestSlot(boots, scan.spareBoots, this::armorScore);
        scanSwords(swords, scan);
        scan.pickaxe = bestSlot(pickaxes, scan.sparePickaxes, this::toolScore);
        scan.axe = bestSlot(axes, scan.spareAxes, this::toolScore);
        scan.shovel = bestSlot(shovels, scan.spareShovels, this::toolScore);
        scan.bow = bestSlot(bows, scan.spareBows, this::bowScore);
        scan.rod = firstSlot(rods, scan.spareRods);

        // 分类收集超量丢弃用的槽位
        for (int i = INV_FIRST; i <= INV_LAST; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item instanceof BlockItem) scan.blockSlots.add(i);
            if (stack.is(ItemTags.ARROWS)) scan.arrowSlots.add(i);
            if (isBucket(stack)) scan.bucketSlots.add(i);
            if (isSnowballOrEgg(stack)) scan.snowballEggSlots.add(i);
            if (stack.is(Items.ENDER_PEARL)) scan.enderPearlSlots.add(i);
        }
        return scan;
    }

    /** 从候选槽中选出分数最高者，其余放入 spare 列表 */
    private int bestSlot(List<Integer> candidates, List<Integer> spare, ItemScore scorer) {
        if (candidates.isEmpty()) return -1;
        int best = candidates.get(0);
        float bestScore = scorer.score(mc.player.getInventory().getItem(best));
        for (int i = 1; i < candidates.size(); i++) {
            int slot = candidates.get(i);
            float score = scorer.score(mc.player.getInventory().getItem(slot));
            if (score > bestScore) {
                spare.add(best);
                best = slot;
                bestScore = score;
            } else {
                spare.add(slot);
            }
        }
        return best;
    }

 /** 剑：按伤害降序排序，第一把 = 主剑，第二把 = 副剑，其余 spare */
    private void scanSwords(List<Integer> swords, InventoryScan scan) {
        swords.sort((a, b) -> Float.compare(
                swordDamage(mc.player.getInventory().getItem(b)),
                swordDamage(mc.player.getInventory().getItem(a))));
        if (swords.isEmpty()) return;
        scan.sword = swords.get(0);
        boolean hasSecond = swordSlot.getValue() != 0 && secondSwordSlot.getValue() != 0
                && secondSwordSlot.getValue() != swordSlot.getValue();
        int from = 1;
        if (hasSecond && swords.size() > 1) {
            scan.secondSword = swords.get(1);
            from = 2;
        }
        for (int i = from; i < swords.size(); i++) {
            scan.spareSwords.add(swords.get(i));
        }
    }

 /** 第一个槽，其余全部 spare */
    private int firstSlot(List<Integer> candidates, List<Integer> spare) {
        if (candidates.isEmpty()) return -1;
        int first = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            spare.add(candidates.get(i));
        }
        return first;
    }

    // ================= 是否需要工作 =================

    private boolean needsWork(InventoryScan scan) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu != null && !menu.getCarried().isEmpty()) {
            return true; // 光标上有物品
        }
        if (betterArmor(0, scan.helmet)) return true;
        if (betterArmor(1, scan.chestplate)) return true;
        if (betterArmor(2, scan.leggings)) return true;
        if (betterArmor(3, scan.boots)) return true;
        if (needsMove(scan.sword, swordSlot)) return true;
        if (needsMove(scan.secondSword, secondSwordSlot)) return true;
        if (!slotTakenBySword(scan, pickaxeSlot) && needsMove(scan.pickaxe, pickaxeSlot)) return true;
        if (!slotTakenBySword(scan, axeSlot) && needsMove(scan.axe, axeSlot)) return true;
        if (!slotTakenBySword(scan, shovelSlot) && needsMove(scan.shovel, shovelSlot)) return true;
        if (!slotTakenBySword(scan, bowSlot) && needsMove(scan.bow, bowSlot)) return true;
        int rod = rodSlot.getValue();
        int bow = bowSlot.getValue();
        if (!slotTakenBySword(scan, rodSlot) && rod != 0
                && (bow == 0 || rod != bow || scan.bow == -1)
                && needsMove(scan.rod, rodSlot)) {
            return true;
        }
        if (needsMoveRange(scan.blocks, blockSlotStart, blockSlotEnd)) return true;
        if (needsMovePotions(scan)) return true;
        if (needsMoveFood(scan)) return true;
        if (overLimit(scan.blockSlots, blockLimit.getValue(), stack -> stack.getItem() instanceof BlockItem)) return true;
        if (overLimit(scan.arrowSlots, arrowLimit.getValue(), stack -> stack.is(ItemTags.ARROWS))) return true;
        if (overLimit(scan.bucketSlots, bucketLimit.getValue(), Manager::isBucket)) return true;
        if (overLimit(scan.snowballEggSlots, snowballEggLimit.getValue(), Manager::isSnowballOrEgg)) return true;
        if (overLimit(scan.enderPearlSlots, enderPearlLimit.getValue(), stack -> stack.is(Items.ENDER_PEARL))) return true;
        return scan.firstUseless() != -1
                || !scan.spareSwords.isEmpty() || !scan.sparePickaxes.isEmpty()
                || !scan.spareAxes.isEmpty() || !scan.spareShovels.isEmpty()
                || !scan.spareBows.isEmpty() || !scan.spareRods.isEmpty()
                || !scan.spareHelmets.isEmpty() || !scan.spareChestplates.isEmpty()
                || !scan.spareLeggings.isEmpty() || !scan.spareBoots.isEmpty();
    }

 /** 更好的盔甲是否在背包里 */
    private boolean betterArmor(int type, int bestSlot) {
        if (bestSlot == -1) return false;
        int equipped = armorInvSlot(type);
        ItemStack equippedStack = mc.player.getInventory().getItem(equipped);
        ItemStack bestStack = mc.player.getInventory().getItem(bestSlot);
        if (bestStack != null && !bestStack.isEmpty() && isArmor(bestStack)) {
            float bestScore = armorScore(bestStack);
            float curScore = equippedStack != null && !equippedStack.isEmpty() && isArmor(equippedStack)
                    ? armorScore(equippedStack) : Float.NEGATIVE_INFINITY;
            return bestSlot != equipped && bestScore > curScore;
        }
        return false;
    }

 /** 某个物品是否需要移动到指定槽 */
    private boolean needsMove(int slot, IntSetting setting) {
        if (setting.getValue() == 0 || slot == -1) return false;
        int target = setting.getValue() - 1;
        return !isInSlot(slot, target);
    }

 /** 目标槽是否已被剑占用（主剑/副剑），避免工具顶掉剑 */
    private boolean slotTakenBySword(InventoryScan scan, IntSetting setting) {
        int slot = setting.getValue();
        return slot != 0 && ((scan.sword != -1 && swordSlot.getValue() == slot)
                || (scan.secondSword != -1 && secondSwordSlot.getValue() == slot));
    }

 /** 区间移动检查：按数量降序检查前 k 件是否已在该区间 */
    private boolean needsMoveRange(List<SlotStack> stacks, IntSetting start, IntSetting end) {
        int from = start.getValue();
        int to = end.getValue();
        if (from == 0 || from > to) return false;
        if (stacks.isEmpty()) return false;
        stacks.sort(Comparator.comparingInt(s -> -s.stack.getCount()));
        int count = to - from + 1;
        for (int i = 0; i < count && i < stacks.size(); i++) {
            int target = from - 1 + i;
            if (!isInSlot(stacks.get(i).slot, target)) return true;
        }
        return false;
    }

 /** 药水区间检查：只有带有效果的药水参与排序 */
    private boolean needsMovePotions(InventoryScan scan) {
        int from = potionSlotStart.getValue();
        int to = potionSlotEnd.getValue();
        if (from == 0 || from > to) return false;
        List<SlotStack> effective = new ArrayList<>();
        for (SlotStack ss : scan.potions) {
            if (hasEffects(ss.stack)) effective.add(ss);
        }
        if (effective.isEmpty()) return false;
        sortPotions(effective);
        int count = to - from + 1;
        for (int i = 0; i < count && i < effective.size(); i++) {
            int target = from - 1 + i;
            if (!isInSlot(effective.get(i).slot, target)) return true;
        }
        return false;
    }

 /** 食物区间检查：按饱和度降序 */
    private boolean needsMoveFood(InventoryScan scan) {
        int from = foodSlotStart.getValue();
        int to = foodSlotEnd.getValue();
        if (from == 0 || from > to) return false;
        if (scan.foods.isEmpty()) return false;
        sortFoods(scan.foods);
        int count = to - from + 1;
        for (int i = 0; i < count && i < scan.foods.size(); i++) {
            int target = from - 1 + i;
            if (!isInSlot(scan.foods.get(i).slot, target)) return true;
        }
        return false;
    }

 /** 数量是否超限 */
    private boolean overLimit(List<Integer> slots, int limit, java.util.function.Predicate<ItemStack> predicate) {
        int total = 0;
        for (int slot : slots) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack != null && !stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total > limit;
    }

    // ================= 执行动作 =================

 /** 换盔甲：三连 PICKUP 交换，换下的旧盔甲放回原背包格 */
    private boolean swapArmor(int type, int bestSlot) {
        if (bestSlot == -1) return false;
        Player player = mc.player;
        int equipped = armorInvSlot(type);
        ItemStack equippedStack = player.getInventory().getItem(equipped);
        ItemStack bestStack = player.getInventory().getItem(bestSlot);
        if (bestStack != null && !bestStack.isEmpty() && isArmor(bestStack)) {
            float bestScore = armorScore(bestStack);
            float curScore = equippedStack != null && !equippedStack.isEmpty() && isArmor(equippedStack)
                    ? armorScore(equippedStack) : Float.NEGATIVE_INFINITY;
            if (bestSlot != equipped && bestScore > curScore) {
                AbstractContainerMenu menu = player.containerMenu;
                if (menu == null || !menu.getCarried().isEmpty()) return false;
                int from = windowSlot(bestSlot);
                int to = armorWindowSlot(type);
                // 交换（等价 1.8.9 windowClick 三连）：
                // 1) 拾起背包里的新盔甲 → 2) 点击装备槽（新上身、旧进光标）→
                // 3) 点回原格（旧盔甲放回背包）
                click(menu, from, 0, ContainerInput.PICKUP, true);
                click(menu, to, 0, ContainerInput.PICKUP, true);
                click(menu, from, 0, ContainerInput.PICKUP, true);
                return true;
            }
        }
        return false;
    }

 /** 移动工具：依次尝试每把工具移到指定热栏格 */
    private boolean moveTools(InventoryScan scan) {
        if (moveToSlot(scan.sword, swordSlot)) return true;
        if (moveToSlot(scan.secondSword, secondSwordSlot)) return true;
        if (!slotTakenBySword(scan, pickaxeSlot) && moveToSlot(scan.pickaxe, pickaxeSlot)) return true;
        if (!slotTakenBySword(scan, axeSlot) && moveToSlot(scan.axe, axeSlot)) return true;
        if (!slotTakenBySword(scan, shovelSlot) && moveToSlot(scan.shovel, shovelSlot)) return true;
        if (!slotTakenBySword(scan, bowSlot) && moveToSlot(scan.bow, bowSlot)) return true;
        int rod = rodSlot.getValue();
        int bow = bowSlot.getValue();
        return !slotTakenBySword(scan, rodSlot) && rod != 0
                && (bow == 0 || rod != bow || scan.bow == -1)
                && moveToSlot(scan.rod, rodSlot);
    }

 /** 移动方块到热栏区间：数量降序，逐件 SWAP 到目标格 */
    private boolean moveBlocks(InventoryScan scan) {
        int from = blockSlotStart.getValue();
        int to = blockSlotEnd.getValue();
        if (from == 0 || from > to) return false;
        scan.blocks.sort(Comparator.comparingInt(s -> -s.stack.getCount()));
        int count = to - from + 1;
        for (int i = 0; i < count && i < scan.blocks.size(); i++) {
            int target = from - 1 + i;
            if (!isInSlot(scan.blocks.get(i).slot, target) && canClick(scan.blocks.get(i).slot, false)) {
                swapToHotbar(scan.blocks.get(i).slot, target);
                return true;
            }
        }
        return false;
    }

 /** 移动药水到热栏区间 */
    private boolean movePotions(InventoryScan scan) {
        int from = potionSlotStart.getValue();
        int to = potionSlotEnd.getValue();
        if (from == 0 || from > to) return false;
        List<SlotStack> effective = new ArrayList<>();
        for (SlotStack ss : scan.potions) {
            if (hasEffects(ss.stack)) effective.add(ss);
        }
        if (effective.isEmpty()) return false;
        sortPotions(effective);
        int count = to - from + 1;
        for (int i = 0; i < count && i < effective.size(); i++) {
            int target = from - 1 + i;
            if (!isInSlot(effective.get(i).slot, target) && canClick(effective.get(i).slot, false)) {
                swapToHotbar(effective.get(i).slot, target);
                return true;
            }
        }
        return false;
    }

 /** 移动食物到热栏区间 */
    private boolean moveFood(InventoryScan scan) {
        int from = foodSlotStart.getValue();
        int to = foodSlotEnd.getValue();
        if (from == 0 || from > to) return false;
        sortFoods(scan.foods);
        int count = to - from + 1;
        for (int i = 0; i < count && i < scan.foods.size(); i++) {
            int target = from - 1 + i;
            if (!isInSlot(scan.foods.get(i).slot, target) && canClick(scan.foods.get(i).slot, false)) {
                swapToHotbar(scan.foods.get(i).slot, target);
                return true;
            }
        }
        return false;
    }

 /** 丢超量物品：从数量多的开始丢整组或丢部分 */
    private boolean dropExcess(List<Integer> slots, int limit, java.util.function.Predicate<ItemStack> predicate) {
        int total = 0;
        List<Integer> counted = new ArrayList<>();
        for (int slot : slots) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack != null && !stack.isEmpty() && predicate.test(stack)) {
                counted.add(slot);
                total += stack.getCount();
            }
        }
        if (total <= limit) return false;
        int excess = total - limit;
        for (int i = counted.size() - 1; i >= 0; i--) {
            int slot = counted.get(i);
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            int count = stack.getCount();
            if (canDrop(slot)) {
                if (excess >= count) {
                    dropStack(slot);
                    return true;
                }
                // 只丢超出的部分：拾起整组 → 逐个丢到剩 limit → 其余放回
                dropPartialStack(slot, count - excess);
                return true;
            }
        }
        return false;
    }

 /** 第一个无用物品槽 */
    private int firstUselessSlot() {
        for (int i = INV_FIRST; i <= INV_LAST; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack != null && !stack.isEmpty() && !isUseful(stack)) {
                return i;
            }
        }
        return -1;
    }

 /** 第一件多余的好物品 */
    private int firstExtraSlot(InventoryScan scan) {
        for (int slot : scan.spareSwords) if (canDrop(slot)) return slot;
        for (int slot : scan.sparePickaxes) if (canDrop(slot)) return slot;
        for (int slot : scan.spareAxes) if (canDrop(slot)) return slot;
        for (int slot : scan.spareShovels) if (canDrop(slot)) return slot;
        for (int slot : scan.spareBows) if (canDrop(slot)) return slot;
        for (int slot : scan.spareRods) if (canDrop(slot)) return slot;
        for (int slot : scan.spareHelmets) if (canDrop(slot)) return slot;
        for (int slot : scan.spareChestplates) if (canDrop(slot)) return slot;
        for (int slot : scan.spareLeggings) if (canDrop(slot)) return slot;
        for (int slot : scan.spareBoots) if (canDrop(slot)) return slot;
        return -1;
    }

 /** 清空光标上的物品：找空槽放下或直接丢出窗口 */
    private boolean clearCursorStack() {
        Player player = mc.player;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.getCarried().isEmpty()) return false;
        for (int i = INV_FIRST; i <= INV_LAST; i++) {
            if (i != SLOT_HELMET && i != SLOT_CHESTPLATE && i != SLOT_LEGGINGS && i != SLOT_BOOTS
                    && player.getInventory().getItem(i).isEmpty()) {
                click(menu, windowSlot(i), 0, ContainerInput.PICKUP, true);
                return true;
            }
        }
        // 背包满了：直接丢出窗口（-999 PICKUP 左键 = 丢光标整组）
        click(menu, -999, 0, ContainerInput.PICKUP, true);
        return true;
    }

 /** 丢整组 */
    private void dropStack(int slot) {
        if (!canDrop(slot)) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null) return;
        click(menu, windowSlot(slot), 1, ContainerInput.THROW, true);
    }

    /**
 * 丢一部分：拾起整组 → 逐个右键放回 keep 个 →
 * 点击 -999 丢光标上剩余（= 超出的数量）。
 * （PICKUP+右键 = 放回 1 个；PICKUP+左键点 -999 = 丢光标整组，doClick 字节码已确证）
 */
    private void dropPartialStack(int slot, int keep) {
        if (!canDrop(slot) || keep < 0) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null) return;
        int winSlot = windowSlot(slot);
        click(menu, winSlot, 0, ContainerInput.PICKUP, true);          // 拾起整组
        int drop = menu.getCarried().getCount() - keep;                // 要丢的数量
        for (int i = 0; i < keep; i++) {
            click(menu, winSlot, 1, ContainerInput.PICKUP, true);      // 放回 1 个
        }
        if (drop > 0) {
            click(menu, -999, 0, ContainerInput.PICKUP, true);         // 丢剩余
        } else {
            click(menu, winSlot, 0, ContainerInput.PICKUP, true);      // 全放回（keep>=总数）
        }
    }

 /** SWAP 点击：把槽移到热栏目标格 */
    private void swapToHotbar(int slot, int hotbarSlot) {
        if (!canClick(slot, false) || hotbarSlot < 0 || hotbarSlot > 8) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null) return;
        click(menu, windowSlot(slot), hotbarSlot, ContainerInput.SWAP, true);
    }

 /** 移动工具到指定槽 */
    private boolean moveToSlot(int slot, IntSetting setting) {
        if (setting.getValue() != 0 && slot != -1) {
            int target = setting.getValue() - 1;
            if (isInSlot(slot, target)) return false;
            if (!canClick(slot, false)) return false;
            swapToHotbar(slot, target);
            return true;
        }
        return false;
    }

 /** 物品是否已在热栏目标槽 */
    private boolean isInSlot(int slot, int target) {
        return slot >= 0 && slot <= 8 && slot == target;
    }

    // ================= 点击封装 =================

    /**
 * 执行一次窗口点击（26.2：MultiPlayerGameMode.handleContainerInput）。
 * <p>
 * 该方法会：1) 调用 menu.clicked 修改本地容器状态；2) 自动把变更槽 + stateId
 * 打包成 ServerboundContainerClickPacket 发送给服务器。等价 1.8.9 的
 * playerController.windowClick，无需手动发包。
 */
    private void click(AbstractContainerMenu menu, int slotNum, int button,
                       ContainerInput input, boolean register) {
        if (mc.gameMode == null || menu == null) return;
        // -999（窗口外）：只有光标有物品时才有意义（PICKUP 左键 = 丢光标整组）
        if (slotNum == -999 && menu.getCarried().isEmpty()) {
            if (register) registerClick();
            return;
        }
        mc.gameMode.handleContainerInput(menu.containerId, slotNum, button, input, mc.player);
        if (register) registerClick();
    }

 /** 记录一次点击 + 设置下次点击延迟 */
    private void registerClick() {
        clicksThisTick++;
        nextClick = System.currentTimeMillis()
                + Math.round(Math.random() * (delay.getMax() - delay.getMin()) + delay.getMin());
        moved = true;
        idleTicks = 0;
        // Grim 防疾跑（审计修复）：点击后禁用疾跑若干 tick，防止"边疾跑边整理"
        // 被反作弊标记为移动异常；sprintDisableTicks 在 onTick 每 tick 递减，
        // >0 时强制 setSprinting(false)。
        sprintDisableTicks = 4;
    }

    /** 是否可以点击该槽（本 tick 已点过则不能 + 自定义物品保护） */
    private boolean canClick(int slot, boolean forDrop) {
        if (moved) return false;
        boolean protect = forDrop ? !dropCustomItems.getValue() : !useCustomItems.getValue();
        return !(protect && isCustomItem(slot));
    }

    private boolean canDrop(int slot) {
        return canClick(slot, true);
    }

 // ================= 物品评分 =================

 /** 剑伤害：基础攻击伤害 + 锋利*1.25 + 火焰附加*2.5 */
    private float swordDamage(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        double base = 0;
        if (modifiers != null) {
            for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
                if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                    AttributeModifier mod = entry.modifier();
                    if (mod.operation() == AttributeModifier.Operation.ADD_VALUE) {
                        base += mod.amount();
                    }
                }
            }
        }
        int sharpness = enchantLevel(stack, Enchantments.SHARPNESS);
        int fireAspect = enchantLevel(stack, Enchantments.FIRE_ASPECT);
        return (float) (base + sharpness * 1.25 + fireAspect * 2.5);
    }

    /**
 * 护甲评分：防御*100 + 材质等级*10 + 保护*6 + 其它保护*2。
 * 防御 = 装备栏对应部位 ARMOR 属性值；材质等级 = 装备资产名映射（钻石5/铁4/锁链3/金2/皮1）。
 */
    private float armorScore(ItemStack stack) {
        if (stack.isEmpty() || !isArmor(stack)) return Float.NEGATIVE_INFINITY;
        double defense = armorDefense(stack);
        int materialRank = armorMaterialRank(stack);
        int protection = enchantLevel(stack, Enchantments.PROTECTION);
        int projectileProtection = enchantLevel(stack, Enchantments.PROJECTILE_PROTECTION);
        int fireProtection = enchantLevel(stack, Enchantments.FIRE_PROTECTION);
        int blastProtection = enchantLevel(stack, Enchantments.BLAST_PROTECTION);
        return (float) (defense * 100 + materialRank * 10 + protection * 6
                + (projectileProtection + fireProtection + blastProtection) * 2);
    }

    /** 工具评分：Tool 组件基础挖掘速度（等价 1.8.9 材质效率）+ 效率附魔（等级²+1） */
    private float toolScore(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Tool tool = stack.get(DataComponents.TOOL);
        float base = tool != null ? tool.defaultMiningSpeed() : 0.0f;
        int efficiency = enchantLevel(stack, Enchantments.EFFICIENCY);
        return base + (efficiency > 0 ? efficiency * efficiency + 1.0f : 0.0f);
    }

 /** 弓评分：力量附魔等级 */
    private float bowScore(ItemStack stack) {
        return enchantLevel(stack, Enchantments.POWER);
    }

    /** 读取附魔等级（26.2：EnchantmentHelper.getItemEnchantmentLevel(Holder, ItemInstance)） */
    private int enchantLevel(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        if (stack.isEmpty() || mc.level == null) return 0;
        try {
            Holder<Enchantment> holder = mc.level.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(enchantment);
            return EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
        } catch (Exception e) {
            return 0;
        }
    }

    // ================= 物品分类 =================

 /** 物品是否有用：方块/工具/武器/护甲/食物/药水/白名单 */
    private boolean isUseful(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof BlockItem) {
            Block block = ((BlockItem) item).getBlock();
            BlockState state = block.defaultBlockState();
            if (block instanceof TntBlock) return true;
            if (block instanceof net.minecraft.world.level.block.TransparentBlock) return true;
            if (block instanceof net.minecraft.world.level.block.StainedGlassBlock) return true;
            // 完整方块（isCollisionShapeFullBlock 等价 1.8.9 isFullBlock）
            if (state.isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)
                    && !(block instanceof net.minecraft.world.level.block.EntityBlock)
                    && !(block instanceof TntBlock)
                    && !(block instanceof SlimeBlock)
                    && !(block instanceof FallingBlock)) {
                return true;
            }
            return false;
        }
        if (stack.is(ItemTags.SWORDS)) return true;
        if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)) return true;
        if (isArmor(stack)) return true;
        if (stack.get(DataComponents.FOOD) != null) return true;
        if (item instanceof PotionItem) return true;
 // 白名单
        return stack.is(Items.FISHING_ROD) || isBucket(stack) || stack.is(Items.ARROW)
                || stack.is(Items.BOW) || isSnowballOrEgg(stack) || stack.is(Items.ENDER_PEARL)
                || stack.is(Items.CLAY_BALL) || stack.is(Items.FIRE_CHARGE);
    }

    /** 是否为盔甲（EQUIPPABLE 组件的槽位属于人类盔甲 4 槽；等价 1.8.9 ItemArmor） */
    private boolean isArmor(ItemStack stack) {
        if (stack.isEmpty()) return false;
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) return false;
        return switch (equippable.slot()) {
            case HEAD, CHEST, LEGS, FEET -> true;
            default -> false;
        };
    }

    /** 盔甲类型（0=头盔 1=胸甲 2=护腿 3=靴子；等价 1.8.9 ItemArmor.armorType） */
    private int armorType(ItemStack stack) {
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) return -1;
        return switch (equippable.slot()) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> -1;
        };
    }

    /** 盔甲防御值：装备槽位的 ARMOR 属性（等价 1.8.9 damageReduceAmount） */
    private double armorDefense(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) return 0;
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) return 0;
        // 遍历属性修改器，统计对该槽位生效的 ARMOR 加成
        double armor = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().is(Attributes.ARMOR) && entry.slot().test(equippable.slot())) {
                AttributeModifier mod = entry.modifier();
                if (mod.operation() == AttributeModifier.Operation.ADD_VALUE) {
                    armor += mod.amount();
                }
            }
        }
        return armor;
    }

 /** 盔甲材质等级 */
    private int armorMaterialRank(ItemStack stack) {
        // 26.2 无直接材质枚举；用装备资产（assetId）的路径名近似（diamond/iron/chainmail/gold/leather）
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) return 0;
        Optional<ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset>> asset = equippable.assetId();
        if (asset.isEmpty()) return 0;
        String name = asset.get().identifier().getPath().toLowerCase();
        if (name.contains("diamond")) return 5;
        if (name.contains("iron")) return 4;
        if (name.contains("chainmail")) return 3;
        if (name.contains("gold")) return 2;
        if (name.contains("leather")) return 1;
        return 0;
    }

    /** 是否为桶（空桶/水桶/熔岩桶/奶桶） */
    private static boolean isBucket(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.BUCKET || item == Items.WATER_BUCKET
                || item == Items.LAVA_BUCKET || item == Items.MILK_BUCKET;
    }

    private static boolean isSnowballOrEgg(ItemStack stack) {
        return stack.is(Items.SNOWBALL) || stack.is(Items.EGG);
    }

    /** 药水是否有效果 */
    private boolean hasEffects(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.hasEffects();
    }

 /** 药水排序：优先溅射（可配置），再按效果价值 */
    private void sortPotions(List<SlotStack> potions) {
        potions.sort((a, b) -> {
            boolean aSplash = a.stack.getItem() instanceof ThrowablePotionItem;
            boolean bSplash = b.stack.getItem() instanceof ThrowablePotionItem;
            if (prioritizeSplashPotions.getValue()) {
                if (aSplash && !bSplash) return -1;
                if (!aSplash && bSplash) return 1;
            }
            int aRank = potionRank(a.stack);
            int bRank = potionRank(b.stack);
            return Integer.compare(bRank, aRank);
        });
    }

    /**
 * 22 跳跃=5、23 防火=6、5 治疗=7、1 移动=8、12 防火(旧)=9、14 隐身=10、
 * 3 水下呼吸=11、13 夜视=12；未知 = -1）。
 * 26.2 用效果键路径名近似映射。
 */
    private int potionRank(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return -1;
        var effects = contents.getAllEffects();
        int best = -1;
        for (var effect : effects) {
            String name = effect.getEffect().getRegisteredName();
            int rank = switch (name) {
                case "minecraft:speed" -> 1;
                case "minecraft:regeneration" -> 2;
                case "minecraft:resistance" -> 3;
                case "minecraft:strength" -> 4;
                case "minecraft:jump_boost" -> 5;
                case "minecraft:fire_resistance" -> 6;
                case "minecraft:instant_health" -> 7;
                case "minecraft:movement_speed" -> 8;
                case "minecraft:water_breathing" -> 11;
                case "minecraft:invisibility" -> 10;
                case "minecraft:night_vision" -> 12;
                default -> -1;
            };
            if (rank > best) best = rank;
        }
        return best;
    }

 /** 食物排序：按饱和度降序 */
    private void sortFoods(List<SlotStack> foods) {
        foods.sort((a, b) -> Float.compare(foodSaturation(b.stack), foodSaturation(a.stack)));
    }

    private float foodSaturation(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) return 0;
        return food.saturation();
    }

 /** 是否为自定义物品 */
    private boolean isCustomItem(int slot) {
        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (stack == null || stack.isEmpty()) return false;
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData != null && !customData.isEmpty();
    }

    // ================= 槽位映射 =================

    /** 背包槽 → 窗口槽（等价 1.8.9：热栏 0-8 → 36-44；主背包 9-35 → 9-35；盔甲 → 5-8） */
    private int windowSlot(int slot) {
        if (slot >= 0 && slot <= 8) return 36 + slot;
        if (slot >= 9 && slot <= 35) return slot;
        if (slot == SLOT_HELMET) return 5;
        if (slot == SLOT_CHESTPLATE) return 6;
        if (slot == SLOT_LEGGINGS) return 7;
        if (slot == SLOT_BOOTS) return 8;
        return slot;
    }

    /** 盔甲类型 → 背包槽（0=头 39，1=胸 38，2=腿 37，3=靴 36） */
    private int armorInvSlot(int type) {
        return switch (type) {
            case 0 -> SLOT_HELMET;
            case 1 -> SLOT_CHESTPLATE;
            case 2 -> SLOT_LEGGINGS;
            default -> SLOT_BOOTS;
        };
    }

    /** 盔甲类型 → 窗口槽（0=头 5，1=胸 6，2=腿 7，3=靴 8） */
    private int armorWindowSlot(int type) {
        return switch (type) {
            case 0 -> 5;
            case 1 -> 6;
            case 2 -> 7;
            default -> 8;
        };
    }

    @Override
    public String getInfo() {
        return legit.getValue() ? "Legit" : null;
    }

    // ================= 内部数据结构 =================

    private interface ItemScore {
        float score(ItemStack stack);
    }

    private static final class SlotStack {
        final int slot;
        final ItemStack stack;

        SlotStack(int slot, ItemStack stack) {
            this.slot = slot;
            this.stack = stack;
        }
    }

 /** 一次扫描的结果 */
    private static final class InventoryScan {
        int helmet = -1, chestplate = -1, leggings = -1, boots = -1;
        int sword = -1, secondSword = -1;
        int pickaxe = -1, axe = -1, shovel = -1, bow = -1, rod = -1;
        final List<SlotStack> blocks = new ArrayList<>();
        final List<SlotStack> potions = new ArrayList<>();
        final List<SlotStack> foods = new ArrayList<>();
        final List<Integer> spareSwords = new ArrayList<>();
        final List<Integer> sparePickaxes = new ArrayList<>();
        final List<Integer> spareAxes = new ArrayList<>();
        final List<Integer> spareShovels = new ArrayList<>();
        final List<Integer> spareBows = new ArrayList<>();
        final List<Integer> spareRods = new ArrayList<>();
        final List<Integer> spareHelmets = new ArrayList<>();
        final List<Integer> spareChestplates = new ArrayList<>();
        final List<Integer> spareLeggings = new ArrayList<>();
        final List<Integer> spareBoots = new ArrayList<>();
        final List<Integer> useless = new ArrayList<>();
        final List<Integer> blockSlots = new ArrayList<>();
        final List<Integer> arrowSlots = new ArrayList<>();
        final List<Integer> bucketSlots = new ArrayList<>();
        final List<Integer> snowballEggSlots = new ArrayList<>();
        final List<Integer> enderPearlSlots = new ArrayList<>();

        int firstUseless() {
            return useless.isEmpty() ? -1 : useless.get(0);
        }
    }
}
