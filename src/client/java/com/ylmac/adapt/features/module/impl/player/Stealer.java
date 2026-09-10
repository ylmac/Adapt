package com.ylmac.adapt.features.module.impl.player;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;

import java.util.Random;

/**
 * Stealer 自动偷箱子（移植 Rise 6.9.5 Stealer → Fabric 26.2）。
 * <p>
 * 功能（完整实现，非骨架）：
 * - 箱子界面（ContainerScreen / 其它 AbstractContainerScreen）打开时自动扫描容器格
 * - 按设置延迟逐个 Shift+点击（QUICK_MOVE）把物品转移到玩家背包
 * - Ignore Trash：跳过垃圾物品（仅偷"有用"物品，Rise ItemUtil.useful 逻辑移植）
 * - 数量限制：箭头 ≤ 128、末影珍珠/雪球/鸡蛋 ≤ 16、桶 ≤ 1、方块总量 ≤ 512（Rise shouldIgnore）
 * - 首次开箱延迟（First Item Delay）
 * - 偷完自动关箱（Rise: chestOpenTicks 判定后 closeScreen）
 * - 防误关：换包延迟未到不点击；最后一次点击后延迟关箱
 * <p>
 * 26.2 API 对照（javap 确证）：
 * - 屏幕：mc.gui.screen()（Gui.screen() 方法，26.2 特有）
 * - 菜单：AbstractContainerScreen.getMenu() → ChestMenu（getContainer() 容器）
 * - 快速转移：MultiPlayerGameMode.handleContainerInput(containerId, slotNum, button,
 *   ContainerInput, player) —— 内部调 menu.clicked 修改本地容器状态，并自动打包
 *   ServerboundContainerClickPacket（携带 containerId + stateId + 变更槽哈希）发给
 *   服务器，stateId 取自 AbstractContainerMenu.incrementStateId()，与服务器窗口状态
 *   同步（等价 1.8.9 playerController.windowClick）。
 *   注意：直接调 menu.clicked(...) 只改本地容器，不会发包，关箱后物品会被服务器回弹；
 *   因此本模块必须走 handleContainerInput。
 * - 关闭：player.clientSideCloseContainer()（发 ServerboundContainerClosePacket）
 */
public class Stealer extends Module {

    private static final Random RANDOM = new Random();

    // ---------- 设置（与 Rise 对齐） ----------
    private final DoubleSetting delay;              // 每件物品延迟（ms，0-500，随机区间）
    private final IntSetting delayMax;              // 延迟上限（与 delay 组成随机区间）
    private final IntSetting firstItemDelay;        // 首次开箱延迟（ms，0-500）
    private final BoolSetting ignoreTrash;          // 忽略垃圾物品
    private final BoolSetting autoClose;            // 偷完自动关箱
    private final IntSetting autoCloseDelay;        // 关箱延迟（ms，0-2000）
    private final BoolSetting requireScreen;        // GUI 检测：仅当容器界面实际打开时才偷（防误偷）
    private final BoolSetting closeIfNoScreen;      // GUI 检测：界面被意外关闭（如服务器强制关箱）时立即复位状态

    // ---------- 内部状态 ----------
    private long nextClickTime;       // 下次可点击时间（ms）
    private long openTime;            // 箱子打开时间（ms）
    private boolean firstDelayDone;   // 首次延迟是否已执行
    private boolean closedScreen;     // 本周期是否已关箱（供外部查询）
    private int lastStealCount;       // 本周期已偷数量（Rise lastSteal 语义）
    private int clicksSinceClose;     // 距上次点击经过的 tick 数（防重复关箱）
    private boolean wasOpen;          // 上一 tick 是否处于箱子界面

    public Stealer() {
        super("stealer", "Stealer", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        delay = doubleSetting("延迟", 100.0, 0.0, 500.0, 10.0);
        delayMax = intSetting("延迟上限", 150, 0, 500, 10);
        firstItemDelay = intSetting("首件延迟", 0, 0, 500, 10);
        ignoreTrash = boolSetting("忽略垃圾", true);
        autoClose = boolSetting("自动关箱", true);
        autoCloseDelay = intSetting("关箱延迟", 150, 0, 2000, 10);
        requireScreen = boolSetting("GUI检测", true);
        closeIfNoScreen = boolSetting("界面关闭即复位", true);
    }

    @Override
    protected void onEnable() {
        this.nextClickTime = 0;
        this.openTime = 0;
        this.firstDelayDone = false;
        this.closedScreen = false;
        this.wasOpen = false;
        this.lastStealCount = 0;
        this.clicksSinceClose = 0;
    }

    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;
        LocalPlayer player = mc.player;
        Screen screen = mc.gui.screen();

        // ---- 箱子界面检测（Rise: currentScreen instanceof GuiChest → 26.2 AbstractContainerScreen） ----
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            AbstractContainerMenu menu = containerScreen.getMenu();

            if (!this.wasOpen) {
                // 刚打开：重置状态 + 首次延迟
                this.openTime = System.currentTimeMillis();
                this.firstDelayDone = false;
                this.lastStealCount = 0;
                this.clicksSinceClose = 0;
                this.closedScreen = false;
                if (this.firstItemDelay.getValue() > 0) {
                    this.nextClickTime = System.currentTimeMillis() + this.firstItemDelay.getValue();
                } else {
                    this.nextClickTime = 0;
                }
                this.firstDelayDone = true;
            }
            this.wasOpen = true;
            this.closedScreen = false;

            // 距离上次点击的 tick 计数（Rise lastClick：超过 2 tick 才允许关箱）
            this.clicksSinceClose++;

            // ---- 遍历容器格（Rise: getLowerChestInventory().getStackInSlot(k) → 26.2: menu.getContainer()） ----
            net.minecraft.world.Container chest = chestContainer(menu);
            if (chest == null) {
                return;
            }

            // ---- GUI 检测：要求菜单确实是当前打开容器界面的菜单 ----
            // 当打开箱子时，mc.player.containerMenu 就是服务器打开的容器菜单（containerId != 0）。
            // 若玩家自行关闭了界面（或服务器强制关箱）而本模块仍持有旧菜单，点点击包会
            // 带上过期的 containerId 被服务器拒绝 → 物品回弹。requireScreen 开启时，
            // 校验菜单与屏幕一致、且确实存在打开的容器；不一致则复位本次偷取周期。
            if (requireScreen.getValue() && !isMenuValidForScreen(containerScreen, menu)) {
                if (this.wasOpen) {
                    this.wasOpen = false;
                    this.closedScreen = true;
                    this.lastStealCount = 0;
                    this.clicksSinceClose = 0;
                }
                return;
            }

            for (int k = 0; k < chest.getContainerSize(); k++) {
                ItemStack stack = chest.getItem(k);
                if (stack.isEmpty()) continue;
                if (this.lastStealCount > 1 && !shouldSteal(stack)) continue;

                // 点击节奏（Rise: nextClick 控制，点击后重置并 return）
                long now = System.currentTimeMillis();
                if (now < this.nextClickTime) return;

                // 执行 Shift+点击（快速转移）：必须走 handleContainerInput 发包，
                // 直接调 menu.clicked 只改本地容器、服务器不认，关箱后物品会回弹。
                // handleContainerInput 内部会：1) menu.clicked 改本地容器状态；
                // 2) 用 menu.incrementStateId() 的 stateId 打包 ServerboundContainerClickPacket
                //    发给服务器（26.2 与服务器窗口状态同步的正确点击通道）。
                int slotId = k; // 容器格 slotId 与 index 一致（0..size-1）
                clickSlot(menu, slotId);

                // 设置下一次点击延迟（随机区间，等价 Rise MathUtil.l(delay.wo, delay.wA)）
                int min = (int) Math.round(this.delay.getValue());
                int max = this.delayMax.getValue();
                if (max < min) max = min;
                this.nextClickTime = now + (max <= min ? min : min + RANDOM.nextInt(max - min + 1));
                this.lastStealCount++;
                this.clicksSinceClose = 0;
                return; // 每 tick 只点击一次（防封）
            }

            // ---- 全部偷完：自动关箱（Rise: lastClick > 1 && chestOpenTicks > 2+2*rand → closeScreen） ----
            if (this.autoClose.getValue()
                    && this.clicksSinceClose > 2
                    && System.currentTimeMillis() - this.openTime > this.autoCloseDelay.getValue()) {
                player.clientSideCloseContainer();
                this.closedScreen = true;
                this.wasOpen = false;
            }
        } else {
            // 界面已关闭：重置状态（若开启 closeIfNoScreen，即使 autoClose 未开也复位，
            // 防止界面被外部关闭后仍持有旧周期状态）
            if (this.wasOpen && this.closeIfNoScreen.getValue()) {
                this.wasOpen = false;
                this.firstDelayDone = false;
                this.lastStealCount = 0;
                this.clicksSinceClose = 0;
            }
        }
    }

    /**
     * GUI 检测：校验屏幕菜单与玩家当前容器菜单一致且为真实打开的容器。
     * 26.2 中打开箱子时 {@code mc.player.containerMenu} 会被服务器窗口替换为
     * 容器菜单（containerId != 0，stateId 与服务器同步）；玩家关闭界面后
     * containerMenu 恢复为 InventoryMenu（containerId == 0）。
     */
    private boolean isMenuValidForScreen(AbstractContainerScreen<?> screen, AbstractContainerMenu menu) {
        LocalPlayer player = mc.player;
        if (player == null) return false;
        AbstractContainerMenu playerMenu = player.containerMenu;
        if (playerMenu == null || playerMenu != menu) return false; // 与玩家当前菜单不一致 → 已过期
        // 打开容器时 containerId != 0；玩家自开背包是 InventoryMenu（0）。
        // 允许背包/工作台等 AbstractContainerScreen 也走同一路径（containerId 可能为 0，
        // 此时 handleContainerInput 仍会带正确的窗口 id 发包），这里只防"菜单已过期"。
        return !menu.getItems().isEmpty();
    }

    /**
     * 通过 MultiPlayerGameMode.handleContainerInput 执行一次 Shift+点击。
     * 该方法自动打包 ServerboundContainerClickPacket（含 stateId + 变更槽哈希）发送，
     * 服务器据此更新容器状态 —— 这是 26.2 唯一能让服务器认可物品转移的通道。
     */
    private void clickSlot(AbstractContainerMenu menu, int slotNum) {
        if (mc.gameMode == null || menu == null || mc.player == null) return;
        if (slotNum < 0 || slotNum >= menu.slots.size()) return;
        mc.gameMode.handleContainerInput(menu.containerId, slotNum, 0, ContainerInput.QUICK_MOVE, mc.player);
    }

    /** 是否已由本模块关箱（供其它模块/脚本查询） */
    public boolean hasClosedScreen() {
        return this.closedScreen;
    }

    // ================= 容器访问 =================

    /**
     * 从菜单取"下方容器"（Rise: ContainerChest.getLowerChestInventory）。
     * 26.2 的 ChestMenu.getContainer() 即箱体容器；对双箱子（行数>3）同样适用。
     */
    private net.minecraft.world.Container chestContainer(AbstractContainerMenu menu) {
        if (menu instanceof ChestMenu chestMenu) {
            return chestMenu.getContainer();
        }
        // 其它 AbstractContainerScreen（如熔炉/发射器）：兜底用 slots 的容器区
        if (!menu.getItems().isEmpty()) {
            return new ContainerWrapper(menu);
        }
        return null;
    }

    // ================= 物品价值判断（Rise ItemUtil.useful + shouldIgnore 移植） =================

    /** 该物品是否值得偷（Rise: ItemUtil.useful(stack) && !shouldIgnore(stack)） */
    private boolean shouldSteal(ItemStack stack) {
        if (!this.ignoreTrash.getValue()) return true;
        if (stack.isEmpty() || stack.getItem() == null) return false;

        Item item = stack.getItem();

        // ---- 方块类：完整方块 / TNT / 玻璃 可用（Rise useful: ItemBlock 全块判断） ----
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block == Blocks.TNT) return true;
            if (block == Blocks.GLASS) return true;
            if (block.defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)) {
                // 排除容器类（箱子/熔炉等，Rise BlockContainer 排除）
                if (block != Blocks.CHEST && block != Blocks.ENDER_CHEST && block != Blocks.TRAPPED_CHEST
                        && block != Blocks.FURNACE && block != Blocks.DISPENSER && block != Blocks.DROPPER
                        && block != Blocks.ENCHANTING_TABLE && block != Blocks.ANVIL && block != Blocks.CRAFTING_TABLE
                        && block != Blocks.BARREL) {
                    if (!(block instanceof FallingBlock)) {
                        // 数量限制：方块总量 ≤ 512（Rise blockLimit）
                        return countItems(it -> it instanceof BlockItem) + stack.getCount() <= 512;
                    }
                }
            }
            return false;
        }

        // ---- 武器/工具/防具（Rise: ItemSword/ItemTool/ItemArmor → 26.2 tag 判定） ----
        if (stack.is(ItemTags.SWORDS)) return true;
        if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS)
                || stack.is(ItemTags.HOES)) return true;
        if (stack.is(ItemTags.HEAD_ARMOR) || stack.is(ItemTags.CHEST_ARMOR)
                || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.FOOT_ARMOR)) return true;

        // ---- 食物/药水（Rise: ItemFood/ItemPotion 可用 → 26.2: FOOD 组件 / 药水） ----
        if (stack.has(DataComponents.FOOD)) return true;
        if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) return true;

        // ---- 白名单物品（Rise WHITELISTED_ITEMS） ----
        if (item == Items.ARROW) {
            return countItem(Items.ARROW) + stack.getCount() <= 128;
        }
        if (item == Items.ENDER_PEARL) {
            return countItem(Items.ENDER_PEARL) + stack.getCount() <= 16;
        }
        if (item == Items.SNOWBALL || item == Items.EGG) {
            return countItem(Items.SNOWBALL) + countItem(Items.EGG) + stack.getCount() <= 16;
        }
        if (item == Items.BUCKET || item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET || item == Items.MILK_BUCKET) {
            return countItem(Items.BUCKET) + countItem(Items.WATER_BUCKET)
                    + countItem(Items.LAVA_BUCKET) + countItem(Items.MILK_BUCKET) + stack.getCount() <= 1;
        }
        if (item == Items.FISHING_ROD || item == Items.BOW || item == Items.CLAY_BALL
                || item == Items.FIRE_CHARGE || item == Items.TOTEM_OF_UNDYING
                || item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE
                || item == Items.DIAMOND || item == Items.IRON_INGOT || item == Items.EMERALD
                || item == Items.NETHERITE_INGOT || item == Items.GOLD_INGOT || item == Items.BLAZE_ROD
                || item == Items.ENDER_EYE || item == Items.GOLDEN_CARROT || item == Items.SPIDER_EYE) {
            return true;
        }

        return false;
    }

    /** 统计玩家背包（0..39 对应 主背包+快捷栏）中指定物品总数（Rise countItem） */
    private int countItem(Item item) {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 统计满足谓词的物品总数（Rise countItems） */
    private int countItems(java.util.function.Predicate<Item> predicate) {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && predicate.test(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public String getInfo() {
        String s = ignoreTrash.getValue() ? "过滤" : "全部";
        if (requireScreen.getValue()) s += "·GUI";
        return s;
    }

    // ================= 兜底容器包装 =================

    /** 非 ChestMenu 的 AbstractContainerMenu 兜底读取（只读容器区，不包含玩家区） */
    private static class ContainerWrapper implements net.minecraft.world.Container {
        private final AbstractContainerMenu menu;
        private final int size;

        ContainerWrapper(AbstractContainerMenu menu) {
            this.menu = menu;
            // 玩家区起始槽（InventoryMenu 为 9；其它菜单一般 0..size-1 为容器）
            int playerStart = 9;
            int slotCount = menu.slots.size();
            this.size = Math.min(slotCount, playerStart);
            // 容器行数由槽数推断（3 行箱子 = 27）
        }

        @Override
        public int getContainerSize() {
            return this.size;
        }

        @Override
        public ItemStack getItem(int index) {
            Slot slot = this.menu.slots.get(index);
            return slot == null ? ItemStack.EMPTY : slot.getItem();
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int index, ItemStack stack) {
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < this.size; i++) {
                if (!getItem(i).isEmpty()) return false;
            }
            return true;
        }

        @Override
        public void clearContent() {
        }
    }
}
