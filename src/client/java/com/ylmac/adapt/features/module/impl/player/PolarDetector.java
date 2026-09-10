package com.ylmac.adapt.features.module.impl.player;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.event.impl.GameJoinedEvent;
import com.ylmac.adapt.features.event.impl.GameLeftEvent;
import com.ylmac.adapt.features.event.impl.PacketEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.BoolSetting;
import com.ylmac.adapt.util.ChatUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PolarDetector 反作弊检测（移植自 Rise 6.9.5 player/PolarDetector）。
 * <p>
 * Rise 原版逻辑：监听 C0FPacketConfirmTransaction（1.8.9 交易确认包），
 * 一旦服务器主动下发该包即判定「Polar 已启用」，随后在 PreMotion 里
 * （ticksExisted == 30）把结果输出到聊天。Rise 的判断依据是：Polar
 * 会主动向客户端下发交易确认包（vanilla 客户端不会自发接收），
 * 即「收到服务器主动下发的 confirm transaction = 服务器跑的是 Polar」。
 * <p>
 * 26.2 移植要点（全部 javap 确证）：
 * <ul>
 *   <li>1.8.9 的 C0FPacketConfirmTransaction 在 26.x 协议中已被移除
 *       （26.2 无 ServerboundContainerAckPacket 类），它在现代协议中的
 *       直接对应物是服务器主动下发的 {@link ClientboundPingPacket}（保活 ping，
 *       客户端需回 {@code ServerboundPongPacket}）——两者都是「服务器→客户端」
 *       的主动探测/回执流量，Polar 的协议指纹正是这套 ping 序列
 *       （LiquidBounce ServerObserver.guessAntiCheat：
 *       {@code 首差 >= 100 且后续差 == -1 → Polar}）。</li>
 *   <li>收包事件：Adapt 的 ConnectionEventMixin 已把 {@link PacketEvent.Receive}
 *       分发到 EventBus（全部收包），因此直接拦截 {@link ClientboundPingPacket}
 *       即可拿到服务器主动下发的 ping id 序列。</li>
 *   <li>辅助证据：Polar 服务器会注册自定义通道（LiquidBounce 通过
 *       {@link ClientboundCustomPayloadPacket} 记录 payloadChannels），
 *       收集通道名，命中 polar 特征名同样判定。</li>
 *   <li>主动 keepalive 探测不做：26.2 服务端 {@code ServerCommonPacketListenerImpl
 *       .handleKeepAlive} 要求 id 必须匹配服务器下发的 challenge（否则直接
 *       踢出，javap 字节码确证），主动发随机 keepalive 会被断线——所以
 *       与 Rise 一样保持纯被动检测，只观察服务器下发的流量。</li>
 * </ul>
 * <p>
 * 判定结果：ping 差分序列命中 Polar 指纹，或自定义通道名含 polar，
 * 即输出「Polar is enabled」；检测窗口结束后（进入世界 30 tick，
 * 与 Rise ticksExisted==30 对齐）输出一次结论。
 * <p>
 * 设置：Show Ping Sequence（聊天显示收到的 ping id 序列，默认 false）、
 * Auto Output（检测完成后自动聊天输出结论，默认 true）。
 */
public class PolarDetector extends Module {

    /** 已判定：true = Polar 启用 */
    private boolean polarEnabled;

    /** 是否已输出本次会话结果（避免重复刷屏） */
    private boolean reported;

    /** 进入世界后的 tick 计数（Rise 用 ticksExisted == 30 做首轮输出） */
    private int tickCounter;

    /** 服务器主动下发的 ping id 序列（差分判定用） */
    private final List<Integer> pingIds = new ArrayList<>();

    /** 服务器注册的自定义通道名（辅助特征） */
    private final Set<String> payloadChannels = new HashSet<>();

    private BoolSetting showPingSequence;
    private BoolSetting autoOutput;

    public PolarDetector() {
        super("polardetector", "PolarDetector", Category.PLAYER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        // 调试：聊天里显示收到的 ping id 序列
        showPingSequence = boolSetting("显示Ping序列", false);
        // 检测完成后自动聊天输出结论
        autoOutput = boolSetting("自动输出", true);
    }

    @Override
    protected void onEnable() {
        // Rise 原版：启用时提示玩家进游戏即可检测
        ChatUtil.sendFormatted("&9Polar Detector&r：加入服务器后自动检测 Polar 反作弊状态");
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    /** 重置所有检测状态（世界切换/离开/关闭模块时） */
    private void resetState() {
        polarEnabled = false;
        reported = false;
        tickCounter = 0;
        pingIds.clear();
        payloadChannels.clear();
    }

    /** 主循环：驱动 tick 计数 + 结果输出（Rise 在 ticksExisted==30 输出一次） */
    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (nullCheck()) {
            return;
        }
        tickCounter++;
        if (!reported && tickCounter >= 30) {
            report();
        }
    }

    /** 服务器主动下发流量：ping 序列 / 自定义通道 → Polar 判定 */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();

        // 服务器主动 ping（等价 1.8.9 服务器主动 confirm transaction）
        if (packet instanceof ClientboundPingPacket ping) {
            pingIds.add(ping.getId());
            if (showPingSequence.getValue()) {
                ChatUtil.sendRaw("&7[PolarDetector] &f服务器 ping id: &a" + ping.getId());
            }
            // 差分判定：>=3 个时看序列形态（LB 指纹：首差 >=100 且后续差 == -1）
            if (pingIds.size() >= 3) {
                evaluatePingPattern();
            }
        }

        // 自定义通道：记录通道名（Polar 服务器会注册 polar 相关通道）
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            CustomPacketPayload payload = custom.payload();
            if (payload != null) {
                String channel = payload.type().id().toString();
                payloadChannels.add(channel);
                if (channel.toLowerCase(Locale.ROOT).contains("polar")) {
                    polarEnabled = true;
                }
            }
        }
    }

    /** 进入世界：重置状态（Rise 的 WorldChangeEvent 语义） */
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        resetState();
    }

    /** 离开世界：重置状态 */
    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        resetState();
    }

    /** 差分判定服务器主动 ping 序列（LiquidBounce ServerObserver 指纹） */
    private void evaluatePingPattern() {
        if (pingIds.size() < 3) {
            return;
        }
        List<Integer> diffs = new ArrayList<>();
        for (int i = 1; i < pingIds.size(); i++) {
            diffs.add(pingIds.get(i) - pingIds.get(i - 1));
        }
        // Polar 指纹：首个差值很大（>=100，重连/重置），之后每次 -1（单调递减）
        if (diffs.size() >= 3
                && diffs.get(0) >= 100
                && diffs.get(1) == -1
                && diffs.subList(2, diffs.size()).stream().allMatch(d -> d == -1)) {
            polarEnabled = true;
        }
    }

    /** 输出检测结论（Rise：ChatUtil.b + NotificationComponent） */
    private void report() {
        if (!autoOutput.getValue()) {
            reported = true;
            return;
        }
        ChatUtil.sendFormatted(polarEnabled
                ? "&c[PolarDetector] &fPolar is &cenabled&f（已启用）"
                : "&a[PolarDetector] &fPolar is &cdisabled&f（未检测到）");
        if (polarEnabled) {
            ChatUtil.sendRaw("&7[PolarDetector] &f证据：服务器主动 ping 序列 " + pingIds
                    + "，自定义通道 " + payloadChannels);
        }
        reported = true;
    }

    @Override
    public String getInfo() {
        return polarEnabled ? "Polar 已启用" : "检测中";
    }
}
