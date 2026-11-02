package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;

/**
 * SnapTap 相对按键优先。
 * <p>
 * 原理：
 * - 每 tick（KeyboardInputEvent，由 KeyboardInputMixin 在 KeyboardInput.tick 尾部触发）
 * 读取 W/A/S/D 物理按键状态（26.2 的 options.keyUp/Down/Left/Right.isDown，
 * - 用"上升沿"（本 tick 按下 && 上 tick 未按）记录每个键的按下时间戳；
 * - 同轴两个键同时按下时（W+S / A+D），取"最后按下"的那个方向作为有效输入，
 * 另一个输出 0 — 即快速切键时（如 A→D 连点）不产生前后/左右抵消，
 * 输出始终跟随最后一次按键（SOCD 最后优先，Valorant 式 SnapTap 行为）；
 * - 单键按下时输出对应方向。
 * <p>
 * 26.2 接入：修改 KeyboardInputEvent 的 forward/strafe 后，KeyboardInputMixin
 * 会写回 keyPresses 与 moveVector（归一化），本 tick 的 applyInput/travel 与
 * ServerboundPlayerInputPacket 均使用修正后的输入，无需额外发包。
 */
public class SnapTap extends Module {

 /** 上 tick 各键按下状态 */
    private boolean lastForward;
    private boolean lastBack;
    private boolean lastLeft;
    private boolean lastRight;
 /** 各键最近一次按下时间戳 */
    private long forwardTime;
    private long backTime;
    private long leftTime;
    private long rightTime;

    public SnapTap() {
        super("snaptap", "SnapTap", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (mc.player == null) {
            return;
        }
        // 物理按键状态（KeyboardInput.tick 同源：options.key* .isDown）
        boolean forward = mc.options.keyUp.isDown();
        boolean back = mc.options.keyDown.isDown();
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();
        long now = System.currentTimeMillis();

 // 上升沿记录按下时间
        if (forward && !this.lastForward) {
            this.forwardTime = now;
        }
        if (back && !this.lastBack) {
            this.backTime = now;
        }
        if (left && !this.lastLeft) {
            this.leftTime = now;
        }
        if (right && !this.lastRight) {
            this.rightTime = now;
        }

        // 前后轴（W/S）：同时按下时取最后按的，时间戳相同（同 tick 按下）则抵消
        if (forward && back) {
            event.setForward(this.forwardTime == this.backTime ? 0.0F
                    : (this.forwardTime > this.backTime ? 1.0F : -1.0F));
        } else if (forward) {
            event.setForward(1.0F);
        } else if (back) {
            event.setForward(-1.0F);
        }

 // 左右轴（A/D）：同上，左=+1 右=-1
        if (left && right) {
            event.setStrafe(this.leftTime == this.rightTime ? 0.0F
                    : (this.leftTime > this.rightTime ? 1.0F : -1.0F));
        } else if (left) {
            event.setStrafe(1.0F);
        } else if (right) {
            event.setStrafe(-1.0F);
        }

 // 更新上 tick 状态
        this.lastForward = forward;
        this.lastBack = back;
        this.lastLeft = left;
        this.lastRight = right;
    }

    @Override
    protected void onDisable() {
 // 复位状态
        this.lastForward = false;
        this.lastBack = false;
        this.lastLeft = false;
        this.lastRight = false;
        this.forwardTime = 0L;
        this.backTime = 0L;
        this.leftTime = 0L;
        this.rightTime = 0L;
    }
}
