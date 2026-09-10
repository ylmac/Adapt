package com.ylmac.adapt.features.module.impl.movement;

import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.KeyPressEvent;
import com.ylmac.adapt.features.event.impl.PlayerTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.ChoiceSetting;
import com.ylmac.adapt.features.module.settings.impl.DoubleSetting;
import com.ylmac.adapt.injection.mixins.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/**
 * Teleport（传送）：vclip 垂直穿墙 / hclip 水平穿墙 / 命令传送。
 * <p>
 * 移植自 Rise 6.9.5 Teleport（MiniBlox/Watchdog 模式），精简为 26.2 可用的
 * 三个通用模式：
 * <ul>
 *   <li>VClip：按跳跃键向上传送一段距离、按潜行键向下传送（按下瞬间触发一次，
 *       走 ServerboundMovePlayerPacket.Pos 静默发包，本地 setPos 同步）</li>
 *   <li>HClip：按传送方向（前进键）水平传送一段距离（沿视角偏航方向，按下触发一次）</li>
 *   <li>Command：按跳跃/潜行/前进分别把 /tp 命令发给服务器（命令方块式传送，
 *       由 KeyPressEvent 按下事件触发）</li>
 * </ul>
 * 按键处理：VClip/HClip 用 PlayerTickEvent 轮询 + 按下边沿检测（上一 tick 记录
 * 按键状态，false→true 触发一次），避免按住不放每 tick 连发；Command 模式用
 * KeyPressEvent（GLFW_PRESS 一次即触发一次）。
 * 26.2 发包：ServerboundMovePlayerPacket.Pos 构造带 horizontalCollision 参数
 * （API 参考第 2 节，已 javap 确认签名）。
 */
public class Teleport extends Module {

    /** 传送模式选择 */
    public enum Mode {
        VCLIP,   // 垂直传送（Jump 上 / Shift 下）
        HCLIP,   // 水平传送（向前传送）
        COMMAND  // 命令传送（发送 /tp）
    }

    /** 模式设置 */
    private ChoiceSetting mode;
    /** 垂直传送距离（格） */
    private DoubleSetting vClipDistance;
    /** 水平传送距离（格） */
    private DoubleSetting hClipDistance;

    /** 上一 tick 的按键状态（按下边沿检测：false→true 触发一次） */
    private boolean prevJump = false;
    private boolean prevShift = false;
    private boolean prevForward = false;

    public Teleport() {
        super("teleport", "Teleport", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        mode = choiceSetting("模式", "VClip", java.util.List.of("VClip", "HClip", "Command"));
        vClipDistance = doubleSetting("垂直距离", 5.0, 0.1, 100.0, 0.1, () -> true);
        hClipDistance = doubleSetting("水平距离", 5.0, 0.1, 100.0, 0.1, () -> true);
    }

    @Override
    public String getInfo() {
        return this.mode.getValue();
    }

    /**
     * 每 tick 轮询按键（跳跃=上、潜行=下、前进=水平方向）。
     * 只在"按下边沿"（上一 tick 未按、本 tick 按下）触发一次，按住不放不连发
     * （等价 Rise keyBindJump/keyBindSneak 的 isPressed 语义）。
     * 三个按键的边沿状态每 tick 都推进（与当前模式无关），避免切换模式瞬间
     * 把按住中的按键误判为一次新的按下。
     */
    @EventHandler
    private void onTick(PlayerTickEvent.Post event) {
        if (mc.player == null || mc.level == null) {
            prevJump = false;
            prevShift = false;
            prevForward = false;
            return;
        }

        boolean jump = mc.options.keyJump.isDown();
        boolean shift = mc.options.keyShift.isDown();
        boolean forward = mc.options.keyUp.isDown();
        boolean jumpPressed = jump && !prevJump;
        boolean shiftPressed = shift && !prevShift;
        boolean forwardPressed = forward && !prevForward;
        prevJump = jump;
        prevShift = shift;
        prevForward = forward;

        String currentMode = this.mode.getValue();
        if ("VClip".equals(currentMode)) {
            if (jumpPressed) {
                // 向上传送
                teleportRelative(0.0, this.vClipDistance.getValue(), 0.0);
            } else if (shiftPressed) {
                // 向下传送（潜行键；26.2 Options.keyShift 已 javap 确认）
                teleportRelative(0.0, -this.vClipDistance.getValue(), 0.0);
            }
        } else if ("HClip".equals(currentMode)) {
            if (forwardPressed) {
                // 沿视角偏航方向水平传送
                double distance = this.hClipDistance.getValue();
                // 偏航：yRot=0 朝 +Z；弧度制；x = -sin(yaw)*d，z = cos(yaw)*d
                double yaw = Math.toRadians(mc.player.getYRot());
                teleportRelative(-Math.sin(yaw) * distance, 0.0, Math.cos(yaw) * distance);
            }
        }
        // Command 模式由 onKeyPress（KeyPressEvent）按下事件驱动，无需轮询
    }

    /**
     * 点击式按键（按一下触发一次）：命令模式用潜行键发送 /tp 命令。
     * 26.2 键盘事件为 KeyEvent 记录（API 参考第 8 节），KeyMapping 的底层
     * InputConstants.Key 是 protected 字段，这里直接比较 GLFW 键码：
     * - 跳跃键 GLFW 码 = mc.options.keyJump.key.getValue()（InputConstants.getKey 可用，
     *   但为了稳妥用 KeyMapping 事件里已经解析过的 GLFW 键码直接比对）——
     *   26.2 的 KeyEvent.key() 即 GLFW 键码，KeybindSetting 绑定的也是 GLFW 键码。
     */
    @EventHandler
    private void onKeyPress(KeyPressEvent event) {
        if (!"Command".equals(this.mode.getValue()) || mc.player == null) {
            return;
        }
        if (event.getAction() != 1) { // GLFW_PRESS
            return;
        }
        int key = event.getKey();
        // 命令模式：跳跃=向上 /tp，潜行=向下 /tp，前进=向前 /tp
        // 26.2 KeyMapping.key 是 protected 字段，经 KeyMappingAccessor mixin 读取
        KeyMapping jumpKey = mc.options.keyJump;
        KeyMapping shiftKey = mc.options.keyShift;
        KeyMapping upKey = mc.options.keyUp;
        String command = null;
        if (key == ((KeyMappingAccessor) jumpKey).adapt_getKey().getValue()) {
            command = "tp ~ ~" + formatNumber(this.vClipDistance.getValue()) + " ~";
        } else if (key == ((KeyMappingAccessor) shiftKey).adapt_getKey().getValue()) {
            command = "tp ~ ~" + formatNumber(-this.vClipDistance.getValue()) + " ~";
        } else if (key == ((KeyMappingAccessor) upKey).adapt_getKey().getValue()) {
            double yaw = Math.toRadians(mc.player.getYRot());
            double dx = -Math.sin(yaw) * this.hClipDistance.getValue();
            double dz = Math.cos(yaw) * this.hClipDistance.getValue();
            command = "tp ~" + formatNumber(dx) + " ~ ~" + formatNumber(dz);
        }
        if (command != null) {
            // 26.2 ClientPacketListener.sendCommand 已 javap 确认
            mc.player.connection.sendCommand(command);
        }
    }

    /** 把 double 格式化为命令坐标用的字符串（去掉多余小数位） */
    private static String formatNumber(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.2f", value);
    }

    /**
     * 相对当前位置传送：本地 setPos 立即生效 + 发一个位置包确认。
     * <p>
     * 26.2 发包用 ServerboundMovePlayerPacket.Pos(x, y, z, onGround, horizontalCollision)
     * （API 参考第 2 节：26.x 全部带 horizontalCollision 参数，已 javap 确认），
     * 走 mc.player.connection（ClientPacketListener）→ Connection.send 静默发包
     * （ConnectionEventMixin 只分发 MovePlayer 家族，此处直接发送不影响其他模块）。
     */
    private void teleportRelative(double dx, double dy, double dz) {
        if (mc.player == null) {
            return;
        }
        double x = mc.player.getX() + dx;
        double y = mc.player.getY() + dy;
        double z = mc.player.getZ() + dz;
        mc.player.setPos(x, y, z);
        mc.player.resetFallDistance();
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
                new Vec3(x, y, z), mc.player.onGround(), mc.player.horizontalCollision));
    }

    @Override
    protected void onDisable() {
        prevJump = false;
        prevShift = false;
        prevForward = false;
    }

}
