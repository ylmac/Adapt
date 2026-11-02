package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.ClientTickEvent;
import com.ylmac.adapt.event.impl.PacketEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 服务器回执的 action 号与本地容器 {@code transactionID} 不一致（小于本地）时，把本地
 * 事务号修正为 {@code action + 1} — 防止"服务器没收到/顺序错乱"导致本地与服务器
 * 背包状态失步。
 * <p>
 * 26.2 中 {@code ServerboundContainerClickPacket} 已改为基于 stateId 的同步机制（1.17+
 * 起废弃 transaction/action 号，26.2 的 doClick 字节码已确证：点击包携带
 * {@code container.getStateId}，服务器用它做顺序校验并回推
 * {@link ClientboundContainerSetContentPacket}/{@link ClientboundContainerSetSlotPacket}
 * 「记录背包状态 → 检测变化 → 重新整理到记录状态」的完整同步逻辑：
 * <ol>
 * <li><b>记录基线</b>：模块启用时（或按"记录"按钮）快照当前背包 0..35 格（热栏 +
 * 主背包）的物品（物品 id + 数量 + 组件哈希）。</li>
 * <li><b>变化检测</b>：每 tick 比较当前背包与基线；任一格物品不一致即视为变化
 * （自动拾取、服务器清空、其他模块操作等都会触发）。</li>
 * <li><b>差异修复</b>：检测到变化后，把「多余/错位」的物品通过窗口点击（PICKUP /
 * SWAP / THROW，与 Manager 同一套 {@code MultiPlayerGameMode.handleContainerInput}
 * 点击 API）搬回基线位置；若基线位置被占用则先移走。仅在没有容器屏幕打开时
 * 工作（外部容器时背包内容属于容器 UI，不参与同步）。</li>
 * <li><b>保持同步</b>：修复完成（背包 == 基线）后停止动作，等待下一次变化。</li>
 * </ol>
 * 这样实现满足「记录背包状态 → 检测变化 → 重新整理到记录状态」的完整同步闭环，
 * 更新本地容器，基线比较始终基于服务器确认后的真实状态）。
 * <p>
 * <b>26.2 stateId 同步说明（审计修复）</b>：1.17+ 的 ServerboundContainerClickPacket
 * 携带 {@code container.getStateId}（AbstractContainerMenu.getStateId，由
 * MultiPlayerGameMode.handleContainerInput 自动取用），服务器用它做窗口状态顺序校验：
 * stateId 与服务器不同步的点击包会被直接拒绝（表现为整理不发/被回滚）。
 * <ul>
 * <li>自开背包（无屏幕，sendOpenInventory）：26.2 客户端不创建本地窗口，点击包只能
 * 用玩家背包菜单（containerId == 0）构造；其 stateId 仅客户端自增、服务器侧
 * 无对应窗口状态 → 点击大概率被丢弃。因此<b>自开背包场景不做整理</b>。</li>
 * <li>真实打开容器（箱子等，containerMenu.containerId != 0）：服务器开窗时会推送
 * ClientboundContainerSetContent/SetSlot（携带服务器 stateId），客户端容器菜单
 * 的 stateId 随之同步 — 此时 handleContainerInput 发出的点击包 stateId 与
 * 服务器一致，整理真实有效。</li>
 * <li>InventoryScreen（自开背包界面）同样属于 containerId == 0 的本地菜单，不做
 * 点击整理（只允许非容器屏幕下执行，避免向服务器发送无效点击）。</li>
 * </ul>
 * 因此本模块只在「真实容器菜单打开」时执行整理动作；记录/基线仍可随时进行。
 * 点击包的 stateId 由 handleContainerInput 内部取菜单当前 stateId，天然与服务器
 * 同步 — 无需手动构造点击包。
 * <p>
 */
public class InventorySync extends Module {

    /** 是否启用「自动同步」（开 = 检测到变化自动整理回基线） */
    private final BoolSetting autoSync;
    /** 同步时每 tick 最多执行的点击次数（防反作弊/防卡包） */
    private final IntSetting maxClicksPerTick;
    /** 允许自动丢弃「基线里没有的物品」（false = 只移动不回基线的不丢） */
    private final BoolSetting allowDropExtras;
    /** 仅真实容器整理：只在服务器打开的容器（containerId != 0）中执行点击（防无效点击/回滚） */
    private final BoolSetting onlyRealContainer;

    /** 基线：背包 0..35 格的记录（null = 未记录） */
    private ItemStack[] baseline = null;
    /** 记录时的选中热栏槽（同步时一并恢复） */
    private int baselineSelected = -1;
    /** 记录时间（毫秒，用于变化冷却） */
    private long recordedAt = 0L;
    /** 同步冷却（毫秒）：两次修复动作之间至少间隔的时长 */
    private long nextActionAt = 0L;
    /** 连续无变化的 tick 数（用于判定"同步完成"后复位） */
    private int idleSyncTicks = 0;

    /** 参与同步的格子范围：热栏 0-8 + 主背包 9-35（不含盔甲/副手） */
    private static final int SYNC_FIRST = 0;
    private static final int SYNC_LAST = 35;

    public InventorySync() {
        super("inventorysync", "InventorySync", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        autoSync = boolSetting("自动同步", true);
        maxClicksPerTick = intSetting("每tick点击上限", 2, 1, 9, 1);
        allowDropExtras = boolSetting("丢弃多余物品", true);
        onlyRealContainer = boolSetting("仅真实容器", true);
 // 手动记录按钮：立即把当前背包设为基线
        buttonSetting("记录当前背包", this::recordBaseline);
    }

    // ================= 事件订阅 =================

 /** 每 tick 检查背包变化并按需修复 */
    @EventHandler
    private void onTick(ClientTickEvent.Pre event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (!isEnabled()) return;

        long now = System.currentTimeMillis();
        // 首次启用：自动记录基线
        if (baseline == null) {
            recordBaseline();
            return;
        }
        if (!autoSync.getValue()) return;

        // 真实容器判定（审计修复）：
        // 26.2 的点击包携带容器菜单的 stateId，服务器只接受与自身窗口状态同步的点击。
        // - 服务器打开的真实容器（containerId != 0）：stateId 由服务器的
        // SetContent/SetSlot 回包同步 → handleContainerInput 点击真实有效。
        // - 自开背包 / InventoryScreen：containerId == 0 的本地菜单，stateId 仅是
        // 客户端自增、服务器没有对应窗口状态 → 点击包被丢弃/整理回滚。
        // 因此只有真实容器菜单打开时才执行点击整理。
        AbstractContainerMenu menu = player.containerMenu;
        boolean realContainer = menu != null && menu.containerId != 0;
        if (onlyRealContainer.getValue() && !realContainer) {
            return; // 非真实容器：不做任何点击（记录/基线不受影响）
        }
        // 自开背包界面：containerId == 0 的本地菜单，点击对服务器无效 → 跳过
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
            return;
        }

 // 跨 tick 的点击延迟：未到时间不做动作
        if (now < nextActionAt) return;

 // 本 tick 最多执行 maxClicksPerTick 次点击；
        // 每个动作之间至少隔 50ms（防止同一帧连点触发服务端状态校验）
        int clicks = 0;
        while (clicks < maxClicksPerTick.getValue()) {
            int[] result = doSyncStep(player);
            if (result[0] == 0) {
                // 没有动作 → 同步完成
                idleSyncTicks++;
                if (idleSyncTicks >= 2) {
                    idleSyncTicks = 0;
                }
                break;
            }
            clicks += result[0];
            if (result[1] == 1) {
                break; // 需要等窗口状态刷新（换槽后服务端回包）
            }
        }
        if (clicks > 0) {
            nextActionAt = now + 50L * clicks;
            idleSyncTicks = 0;
        }
    }

 /** 收到服务器背包内容回包时：状态被服务器更新 → 重新进入同步检测 */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (baseline == null || !autoSync.getValue()) return;
        if (event.getPacket() instanceof ClientboundContainerSetContentPacket
                || event.getPacket() instanceof ClientboundContainerSetSlotPacket) {
            // 服务器回推内容（自动拾取确认/他人操作回包等）→ 背包真实状态已变化，
            // 让下个 tick 的差异检测重新对齐基线（重置冷却，立即检查）
            idleSyncTicks = 0;
            nextActionAt = 0L;
        }
    }

    @Override
    protected void onDisable() {
        baseline = null;
        baselineSelected = -1;
        idleSyncTicks = 0;
        nextActionAt = 0L;
    }

    // ================= 核心逻辑 =================

    /** 记录当前背包状态为基线（热栏 0-8 + 主背包 9-35，复制物品引用防后续变化） */
    private void recordBaseline() {
        LocalPlayer player = mc.player;
        if (player == null) return;
        ItemStack[] snapshot = new ItemStack[36];
        for (int i = SYNC_FIRST; i <= SYNC_LAST; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            snapshot[i] = stack == null || stack.isEmpty() ? null : stack.copy();
        }
        baseline = snapshot;
        baselineSelected = player.getInventory().getSelectedSlot();
        recordedAt = System.currentTimeMillis();
        idleSyncTicks = 0;
    }

    /**
 * 执行一步同步修复，返回 [执行的点击次数, 是否触发换槽待回包]。
 * 规则（完整实现）：
 * 1. 光标有物品（异常残留）→ 先放回最近的差异槽或空槽；
 * 2. 遍历每个基线格：当前格与基线不一致 →
 * - 基线为空、当前有物品 → 多余物品：丢（allowDropExtras）或移动到空基线格；
 * - 基线有物品、当前为空 → 缺漏：从别处（多余的同种物品格）SWAP/PICKUP 回来；
 * - 基线有物品、当前不同 → 错位：两格直接 SWAP 交换；
 * 3. 恢复选中热栏槽。
 * 每步只做 1 次点击（PICKUP 或 SWAP），由 onTick 的 while 循环按上限执行。
 */
    private int[] doSyncStep(Player player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) return new int[]{0, 0};

        // 1) 光标残留：放回任意空基线格或原格
        if (!menu.getCarried().isEmpty()) {
            for (int i = SYNC_FIRST; i <= SYNC_LAST; i++) {
                if (baseline[i] == null && player.getInventory().getItem(i).isEmpty()) {
                    clickWindow(menu, windowSlot(i), 0, ContainerInput.PICKUP);
                    return new int[]{1, 0};
                }
            }
            // 没有空位 → 丢弃（光标物品不在基线里）
            clickWindow(menu, -999, 0, ContainerInput.PICKUP);
            return new int[]{1, 0};
        }

        // 2) 逐格对比基线
        for (int i = SYNC_FIRST; i <= SYNC_LAST; i++) {
            ItemStack current = player.getInventory().getItem(i);
            ItemStack want = baseline[i];
            boolean curEmpty = current == null || current.isEmpty();
            boolean wantEmpty = want == null || want.isEmpty();
            if (curEmpty && wantEmpty) continue;
            if (sameItem(current, want)) continue;

            if (wantEmpty) {
                // 基线为空、当前有物品 → 多余
                if (allowDropExtras.getValue()) {
                    clickWindow(menu, windowSlot(i), 1, ContainerInput.THROW); // 丢整组
                    return new int[]{1, 0};
                }
                // 不允许丢 → 尝试移到某个空基线格
                int free = findEmptyBaselineSlot(player, i);
                if (free != -1) {
                    clickWindow(menu, windowSlot(free), i, ContainerInput.SWAP);
                    return new int[]{1, 0};
                }
                continue;
            }

            // 基线有物品、当前为空 → 缺漏：找同种物品的多余格搬回来
            if (curEmpty) {
                int src = findMatchingExtra(player, want, i);
                if (src != -1) {
                    clickWindow(menu, windowSlot(i), src, ContainerInput.SWAP);
                    return new int[]{1, 0};
                }
                continue;
            }

            // 两格都有物品但不一致 → 错位：与基线对应的那格交换（找到基线内容 == 当前物品的格）
            int dst = findSlotFor(player, current, want);
            if (dst != -1 && dst != i) {
                clickWindow(menu, windowSlot(i), dst, ContainerInput.SWAP);
                return new int[]{1, 0};
            }
            // 找不到配对 → 当前物品多余：丢掉（或忽略，等其它格处理）
            if (allowDropExtras.getValue()) {
                clickWindow(menu, windowSlot(i), 1, ContainerInput.THROW);
                return new int[]{1, 0};
            }
            continue;
        }

        // 3) 恢复选中热栏槽
        if (baselineSelected >= 0 && baselineSelected <= 8
                && player.getInventory().getSelectedSlot() != baselineSelected) {
            player.getInventory().setSelectedSlot(baselineSelected);
            if (mc.getConnection() != null) {
                mc.getConnection().getConnection().send(
                        new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(baselineSelected), null);
            }
            return new int[]{0, 0};
        }

        return new int[]{0, 0};
    }

    /** 找与基线 want 同种物品、且不在正确位置的格（用于缺漏补回） */
    private int findMatchingExtra(Player player, ItemStack want, int exclude) {
        for (int i = SYNC_FIRST; i <= SYNC_LAST; i++) {
            if (i == exclude) continue;
            ItemStack stack = player.getInventory().getItem(i);
            if (stack != null && !stack.isEmpty() && sameItem(stack, want)
                    && baseline[i] != null && !sameItem(stack, baseline[i])) {
                return i;
            }
        }
        return -1;
    }

    /** 找与基线 want 同种的空基线格（用于把多余物品搬去） */
    private int findEmptyBaselineSlot(Player player, int exclude) {
        for (int i = SYNC_FIRST; i <= SYNC_LAST; i++) {
            if (i == exclude) continue;
            if (baseline[i] == null && player.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 找当前内容 == current、基线内容 == want 的格（错位交换目标） */
    private int findSlotFor(Player player, ItemStack current, ItemStack want) {
        for (int i = SYNC_FIRST; i <= SYNC_LAST; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            if (sameItem(stack, want) && baseline[i] != null && sameItem(baseline[i], current)) {
                return i;
            }
        }
        return -1;
    }

    /** 两个物品是否同种（id + 数量 + 关键组件；26.2 的 isSameItemSameComponents 语义） */
    private boolean sameItem(ItemStack a, ItemStack b) {
        if (a == null || a.isEmpty()) return b == null || b.isEmpty();
        if (b == null || b.isEmpty()) return false;
        if (a.getItem() != b.getItem()) return false;
        if (a.getCount() != b.getCount()) return false;
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /** 执行一次窗口点击（26.2：MultiPlayerGameMode.handleContainerInput，自动发点击包） */
    private void clickWindow(AbstractContainerMenu menu, int slotNum, int button, ContainerInput input) {
        if (mc.gameMode == null || menu == null) return;
        if (slotNum == -999 && menu.getCarried().isEmpty()) return;
        // handleContainerInput 内部：1) menu.clicked 修改本地容器状态；
        // 2) 用 menu.incrementStateId（= 服务器最近一次 SetContent/SetSlot 同步的
        // stateId + 1）打包 ServerboundContainerClickPacket 发送。
        // 因此点击包的 stateId 与服务器窗口状态天然同步 — 这是 26.2 唯一合法的
        // 容器点击通道；直接调 menu.clicked 不会发包，服务器会回滚。
        mc.gameMode.handleContainerInput(menu.containerId, slotNum, button, input, mc.player);
    }

    /**
 * 背包槽 → 窗口槽（动态映射，审计修复）。
 * <p>
 * 玩家背包（InventoryMenu，containerId == 0）的窗口布局固定：
 * 热栏 0-8 → 36-44；主背包 9-35 → 9-35。
 * 但<b>真实容器菜单</b>（箱子等）中玩家区起点不同：3 行箱子玩家主背包从
 * 窗口槽 27 开始、热栏从 54 开始；双箱/熔炉/工作台又各不相同。若沿用固定
 * 映射在容器菜单里点击，会点到容器的错误槽位（点击包会搬错物品甚至被
 * 服务器拒绝）。
 * <p>
 * 正确做法：扫描菜单槽位，找到容器 == 玩家背包的第一个槽（= 玩家区起点），
 * 然后按「主背包 27 格 + 热栏 9 格」顺序偏移。
 */
    private int windowSlot(int slot) {
        if (slot < 0 || slot > 35) return slot;
        int playerStart = playerSectionStart();
        if (slot >= 0 && slot <= 8) return playerStart + 27 + slot; // 热栏在玩家区末尾
        return playerStart + (slot - 9);                            // 主背包
    }

    /** 玩家背包区在菜单中的起始窗口槽（找不到则回退 InventoryMenu 的固定值 9） */
    private int playerSectionStart() {
        AbstractContainerMenu menu = mc.player == null ? null : mc.player.containerMenu;
        if (menu == null || mc.player == null) return 9;
        var inventory = mc.player.getInventory();
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).container == inventory) {
                return i;
            }
        }
        return 9; // InventoryMenu 的标准起点（find 兜底）
    }

    @Override
    public String getInfo() {
        if (baseline == null) return "未记录";
        return "已记录" + (autoSync.getValue() ? "·自动同步" : "");
    }
}
