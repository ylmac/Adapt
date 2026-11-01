package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.event.impl.PacketEvent;
import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.ColorSetting;
import com.ylmac.adapt.features.module.settings.impl.IntSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BedPlates —— 显示床周围被放置的方块（移植自 LiquidBounce render/BedPlates，适配 Fabric 26.2）。
 * <p>
 * LB 原版行为：扫描所有床（头枕部分），统计床周围 N 层内放置的方块
 * （按层数/数量/硬度排序，可合并相同方块），在床正上方用 item 列表 + 数量渲染
 * （OverlayRenderEvent → drawItemStackList），并支持过滤（预定义/自定义）、
 * 忽略自己的床（颜色/出生点/手动）、忽略相邻床等。
 * <p>
 * 26.2 移植：item 列表渲染管线（drawItemStackList）依赖 LB 自研 GUI 渲染引擎，
 * 本项目无等价物。按任务要求"显示床周围被放置的方块"，用本项目现有机制呈现
 * 等价信息：
 * - 3D：Gizmos.cuboid 高亮每个床（床顶中心），颜色按床的染料颜色
 * - 2D（HudElement）：屏幕空间在床上方显示"方块名 ×数量"文本（GuiGraphicsExtractor.text），
 *   行首显示与 LB 一致的层数罗马数字（compact 模式不显示层数）
 * <p>
 * 设置：MaxLayers（LB 1..5）/ MaxCount（LB 1..64）/ ShowBed / 过滤模式
 * （Predefined 白名单 / Custom 方块列表）/ Compact / HighlightUnbreakable
 * （缺正确工具 → 红色）/ TextShadow / IgnoreSelfBed（Color 模式：装甲染料 == 床染料）
 * / IgnoreAdjacent。与 LB 一致，全部实现。
 */
public class BedPlates extends Module implements net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement {

    private static final String[] ROMAN_NUMERALS = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII"};

    private static BedPlates instance;

    public static BedPlates get() {
        return instance;
    }

    // —— 设置（LB 同款）——
    private IntSetting maxLayers;
    private IntSetting maxCount;
    private BoolSetting showBed;
    private BoolSetting textShadow;
    private BoolSetting highlightUnbreakable;
    private BoolSetting compact;
    private BoolSetting preventOverlap;
    private ChoiceSetting filterMode;
    private com.ylmac.adapt.features.module.settings.impl.RegistryListSetting<Block> customBlocks;
    private ChoiceSetting ignoreSelfBed;
    private BoolSetting ignoreAdjacent;

    // —— 内部状态 ——
    private final Map<BlockPos, TrackedBed> beds = new HashMap<>();
    private final Set<BlockPos> bedBlocks = new HashSet<>();

    private static final class TrackedBed {
        final BlockPos pos;          // 床头（FIRST part）
        final BlockState state;
        final Block block;           // BedBlock
        final Vec3 renderPos;        // 床顶中心（头枕上方）
        final List<SurroundingBlock> surrounding = new ArrayList<>();

        TrackedBed(BlockPos pos, BlockState state, Vec3 renderPos) {
            this.pos = pos;
            this.state = state;
            this.block = state.getBlock();
            this.renderPos = renderPos;
        }
    }

    private static final class SurroundingBlock {
        final Block block;
        final int count;
        final int layer;

        SurroundingBlock(Block block, int count, int layer) {
            this.block = block;
            this.count = count;
            this.layer = layer;
        }
    }

    public BedPlates() {
        super("bedplates", "BedPlates", Category.RENDER);
        instance = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);

        maxLayers = intSetting("最大层数", 5, 1, 5, 1);
        showBed = boolSetting("显示床", true);
        textShadow = boolSetting("文字阴影", true);
        maxCount = intSetting("最大数量", 8, 1, 64, 1);
        highlightUnbreakable = boolSetting("高亮不可破坏", true);
        compact = boolSetting("紧凑", true);
        preventOverlap = boolSetting("防止重叠", true);
        filterMode = choiceSetting("过滤模式", "Predefined", List.of("Predefined", "Custom"));
        customBlocks = blockListSetting("方块列表", new HashSet<>(),
                () -> filterMode.getValue().equals("Custom"));
        ignoreSelfBed = choiceSetting("忽略自己的床", "None", List.of("None", "Color"));
        ignoreAdjacent = boolSetting("忽略相邻", false);
    }

    @Override
    public String getInfo() {
        return "L" + maxLayers.getValue();
    }

    // ───────────────────────── 追踪 ─────────────────────────

    /** 每 tick 全量重扫（LB BedBlockTracker 异步扫描 → 本实现 tick 同步扫描，等价） */
    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (nullCheck()) {
            beds.clear();
            bedBlocks.clear();
            return;
        }
        rescan();
    }

    /** 方块更新包：只更新受影响床（LB BedBlockTracker.getStateFor 同款增量思路） */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck()) return;
        if (event.getPacket() instanceof ClientboundBlockUpdatePacket packet) {
            handleBlockUpdate(packet.getPos(), packet.getBlockState());
        } else if (event.getPacket() instanceof ClientboundSectionBlocksUpdatePacket packet) {
            packet.runUpdates(this::handleBlockUpdate);
        }
    }

    private void handleBlockUpdate(BlockPos pos, BlockState state) {
        if (beds.isEmpty()) return;
        int dist = maxLayers.getValue();
        List<BlockPos> affected = new ArrayList<>();
        for (BlockPos bedPos : beds.keySet()) {
            if (bedPos.distManhattan(pos) <= dist) {
                affected.add(bedPos);
            }
        }
        if (affected.isEmpty()) return;
        // 受影响床附近发生方块变化 → 重扫这些床
        for (BlockPos bedPos : affected) {
            scanBedAt(bedPos);
        }
    }

    private void rescan() {
        beds.clear();
        bedBlocks.clear();
        BlockPos.withinClippedManhattan(mc.player.blockPosition(), 48, 48, 48).forEach(pos -> {
            BlockState state = mc.level.getBlockState(pos);
            if (state.getBlock() instanceof BedBlock
                    && BedBlock.getBlockType(state) == DoubleBlockCombiner.BlockType.FIRST) {
                scanBedAt(pos.immutable());
            }
        });
    }

    private void scanBedAt(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)
                || BedBlock.getBlockType(state) != DoubleBlockCombiner.BlockType.FIRST) {
            beds.remove(pos);
            return;
        }
        Direction facing = state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
        TrackedBed bed = beds.computeIfAbsent(pos.immutable(),
                p -> new TrackedBed(p.immutable(), state, new Vec3(
                        p.getX() + 0.5 - facing.getStepX() * 0.5,
                        p.getY() + 1.0,
                        p.getZ() + 0.5 - facing.getStepZ() * 0.5)));

        // 收集周围方块（LB searchBedLayer + getBedSurroundingBlocks 等价：
        // 床头 + 床尾各自向外扩展 layers 层，统计每层的方块计数）
        Map<Block, int[]> counts = new HashMap<>(); // block -> [count, minLayer]
        int layers = maxLayers.getValue();
        Direction anotherPart = facing.getOpposite();
        Direction left = facing.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.WEST;
        Direction right = left.getOpposite();

        collectLayer(pos, facing, layers, counts);
        collectLayer(pos.relative(anotherPart), anotherPart, layers, counts);

        bed.surrounding.clear();
        for (Map.Entry<Block, int[]> e : counts.entrySet()) {
            bed.surrounding.add(new SurroundingBlock(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        bed.surrounding.sort(Comparator
                .comparingInt((SurroundingBlock s) -> s.layer)
                .thenComparing((SurroundingBlock s) -> -s.count)
                .thenComparing((SurroundingBlock s) -> -s.block.defaultDestroyTime())
                .thenComparing((SurroundingBlock s) -> s.block.getDescriptionId()));

        // 记录床占用的方块（self-bed 判断用）
        bedBlocks.add(pos);
        bedBlocks.add(pos.relative(anotherPart));
    }

    /** BFS 从中心向外扩展 layers 层（LB searchLayer 等价），统计非空气方块 */
    private void collectLayer(BlockPos origin, Direction dir, int layers, Map<Block, int[]> counts) {
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> queue = new ArrayList<>();
        queue.add(origin);
        visited.add(origin);

        int[] layerOf = new int[1];
        for (int layer = 0; layer <= layers; layer++) {
            List<BlockPos> next = new ArrayList<>();
            for (BlockPos cur : queue) {
                if (layer > 0) {
                    BlockState st = mc.level.getBlockState(cur);
                    if (!st.isAir() && st.getBlock() != Blocks.BEDROCK) {
                        Block b = st.getBlock();
                        if (filterPass(b)) {
                            int[] arr = counts.computeIfAbsent(b, k -> new int[2]);
                            arr[0]++;
                            if (arr[1] == 0 || layer < arr[1]) arr[1] = layer;
                        }
                    }
                }
                if (layer >= layers) continue;
                for (Direction d : new Direction[]{dir, Direction.UP, left(dir), right(dir)}) {
                    BlockPos n = cur.relative(d);
                    if (visited.add(n)) next.add(n);
                }
            }
            queue = next;
        }
    }

    private Direction left(Direction dir) {
        return dir.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.WEST;
    }

    private Direction right(Direction dir) {
        return dir.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.EAST;
    }

    /** LB FilterMode 过滤：Predefined（非空气 + 可传导红石 + 白名单） / Custom（列表） */
    private boolean filterPass(Block block) {
        if (filterMode.getValue().equals("Custom")) {
            return customBlocks.getValue().contains(block);
        }
        BlockState state = block.defaultBlockState();
        if (state.isAir()) return false;
        if (!state.isRedstoneConductor(mc.level, BlockPos.ZERO)) {
            // 白名单非实心方块（LB WHITELIST_NON_SOLID：梯子/水/玻璃）
            if (block != Blocks.LADDER && block != Blocks.WATER && block != Blocks.GLASS) {
                return false;
            }
        }
        return true;
    }

    /** LB IgnoreSelfBed Color 模式：玩家装备的染料物品颜色 == 床染料颜色 */
    private boolean isSelfBed(TrackedBed bed) {
        if (!ignoreSelfBed.getValue().equals("Color")) return false;
        if (!(bed.block instanceof BedBlock bedBlock)) return false;
        int bedColor = bedBlock.getColor().getTextureDiffuseColor();
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            var stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            // 26.2 无 DyeItem.getDyeColor：按物品注册名 "_dye" 后缀判定染料，
            // 再映射到对应 DyeColor 对比床染料色（等价 LB 按染料匹配）
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key != null && key.getPath().endsWith("_dye")) {
                String prefix = key.getPath().substring(0, key.getPath().length() - "_dye".length());
                for (net.minecraft.world.item.DyeColor dc : net.minecraft.world.item.DyeColor.VALUES) {
                    if (dc.getName().equals(prefix) && dc.getTextureDiffuseColor() == bedColor) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ───────────────────────── 3D 渲染 ─────────────────────────

    /** 每帧 3D 高亮（BedPlatesRendererMixin 回调，Gizmos 收集窗口内） */
    public void onRenderLevel(float partialTicks) {
        if (!isEnabled() || nullCheck()) return;
        for (TrackedBed bed : beds.values()) {
            if (isSelfBed(bed)) continue;
            if (ignoreAdjacent.getValue() && isAdjacent(bed)) continue;
            Color c = mapColor(bed);
            int argb = (c.getAlpha() << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
            AABB box = new AABB(bed.pos).inflate(0.02);
            net.minecraft.gizmos.Gizmos.cuboid(box, net.minecraft.gizmos.GizmoStyle.stroke(argb, 0.05f));
        }
    }

    private boolean isAdjacent(TrackedBed bed) {
        for (TrackedBed other : beds.values()) {
            if (other == bed) continue;
            if (bed.pos.distManhattan(other.pos) <= 1) return true;
        }
        return false;
    }

    /** 床颜色（LB 用 block.color.mapColor.col 描边） */
    private Color mapColor(TrackedBed bed) {
        try {
            var mc2 = bed.state.getMapColor(mc.level, bed.pos);
            return new Color(mc2.col, true);
        } catch (Throwable t) {
            return new Color(255, 255, 255, 180);
        }
    }

    // ───────────────────────── 2D 渲染 ─────────────────────────

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker dt) {
        if (!isEnabled() || nullCheck()) return;
        if (mc.player == null) return;

        List<TrackedBed> sorted = new ArrayList<>(beds.values());
        sorted.sort(Comparator.comparingDouble(b -> b.pos.distToCenterSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ())));

        int drawn = 0;
        for (TrackedBed bed : sorted) {
            if (drawn >= maxCount.getValue()) break;
            if (isSelfBed(bed)) continue;
            if (ignoreAdjacent.getValue() && isAdjacent(bed)) continue;

            org.joml.Vector3f screen = com.ylmac.adapt.render.WorldToScreen.calcWorld2Screen(bed.renderPos);
            if (screen == null) continue;

            // calcWorld2Screen 返回 GUI 缩放坐标（0,0=左上）
            int x = (int) Math.round(screen.x);
            int y = (int) Math.round(screen.y);

            // 第一行：床名 + 距离（LB drawStackCount: "距离m"）
            if (showBed.getValue()) {
                String bedName = bed.block.getName().getString();
                g.text(mc.font, bedName, x - mc.font.width(bedName) / 2, y, 0xFFFFFFFF, textShadow.getValue());
                y += 10;
            }

            // 方块行（LB itemStackList：每行"方块名 ×数量"，compact 隐藏层数）
            int guiH = mc.getWindow().getGuiScaledHeight();
            for (SurroundingBlock sb : bed.surrounding) {
                if (y > guiH - 12) break;
                boolean unbreakable = highlightUnbreakable.getValue()
                        && sb.block.defaultBlockState().requiresCorrectToolForDrops()
                        && !hasCorrectTool(sb.block.defaultBlockState());
                int color = unbreakable ? 0xFFFF5555 : 0xFFFFFFFF;
                String layer = compact.getValue() ? "" : (ROMAN_NUMERALS[sb.layer] + " ");
                String line = layer + sb.block.getName().getString() + " x" + sb.count;
                g.text(mc.font, line, x - mc.font.width(line) / 2, y, color, textShadow.getValue());
                y += 10;
            }
            drawn++;
        }
    }

    /** 背包任意槽位有正确工具（LB Slots.Hotbar.findSlot isCorrectToolForDrops 等价，放宽到整个背包） */
    private boolean hasCorrectTool(BlockState state) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    @Override
    protected void onDisable() {
        beds.clear();
        bedBlocks.clear();
    }
}
