package com.ylmac.adapt.module.movement;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.BoolSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * GUI 内移动。
 * <p>
 * 原理：
 * - 26.2 的输入链路：LocalPlayer.aiStep 每 tick 调 input.tick（KeyboardInput），
 * KeyboardInput.tick 读 KeyMapping（keyUp/keyDown/keyLeft/keyRight/keyJump）写
 * keyPresses + moveVector，之后 applyInput 消费。打开 GUI（screen != null）后
 * vanilla 仍会调 KeyboardInput.tick 读键，但两个问题导致无法移动：
 * 1. 26.2 的 MouseHandler/KeyboardHandler 在 screen 打开时不维护 KeyMapping 的
 * "按下"状态（1.8.9 的 KeyBinding.updatePressedStates 依赖 inGameFocus）；
 * 2. 打开 GUI 时 vanilla 把全部 KeyMapping setDown(false)（KeyMapping.setAll）。
 * - 本实现：
 * 在 KeyboardInputEvent（KeyboardInput.tick 尾部，每 tick 派发）里用物理键
 * 状态（InputConstants.isKeyDown(mc.getWindow, 键码)）重写 keyPresses，并
 * 按 vanilla 同样的计算重算 moveVector — 即手动把按键状态喂回输入系统。
 * 这是唯一的输入注入点（KeyboardInputMixin 会把 set* 写回 keyPresses 并重算
 * moveVector），无需也不应在 PlayerTick 再做兜底同步（26.2 的 ClientInput
 * moveVector/keyPresses 只由 KeyboardInput.tick 写入，tick 之外的直改会被
 * 下一帧输入刷新覆盖，且 MixinClientInput 只提供读写实现不重算任何东西）。
 * - 聊天框（ChatScreen）豁免：聊天需要输入文字，原版语义不移动。
 * <p>
 * 设置：Chat 豁免、GUI 中跳跃（默认开）、
 * GUI 中潜行（默认关）。
 */
public class InventoryMove extends Module {

 /** 聊天框豁免 */
    private final BoolSetting exemptChat;
 /** GUI 中允许跳跃 */
    private final BoolSetting allowJump;
 /** GUI 中允许潜行 */
    private final BoolSetting allowSneak;

    public InventoryMove() {
        super("inventorymove", "InventoryMove", Category.MOVEMENT);
        setDefaultEnabled(false);
        setDefaultHidden(false);
        exemptChat = boolSetting("聊天豁免", true);
        allowJump = boolSetting("GUI 中跳跃", true);
        allowSneak = boolSetting("GUI 中潜行", false);
    }

    @EventHandler
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (nullCheck() || !isEnabled()) return;
        Screen screen = mc.gui.screen();
        if (screen == null) return;
 // 聊天框不劫持输入
        if (exemptChat.getValue() && screen instanceof ChatScreen) return;

        // 手动读取物理按键状态（InputConstants.isKeyDown = 1.8.9 KeyBinding.isKeyDown，
        // 26.2 的 GameSettings.isKeyDown 等价物；键码经 KeyMappingAccessor 取）
        boolean forward = physicalKeyDown(mc.options.keyUp);
        boolean backward = physicalKeyDown(mc.options.keyDown);
        boolean left = physicalKeyDown(mc.options.keyLeft);
        boolean right = physicalKeyDown(mc.options.keyRight);
        boolean jump = allowJump.getValue() && mc.options.keyJump.isDown();
        boolean sneak = allowSneak.getValue() && mc.options.keyShift.isDown();
        boolean sprint = mc.options.keySprint.isDown();

        // 写回输入（等价 vanilla KeyboardInput.tick 的计算：Vec2(strafe, forward).normalized）
        event.setForward((forward ? 1.0F : 0.0F) - (backward ? 1.0F : 0.0F));
        event.setStrafe((left ? 1.0F : 0.0F) - (right ? 1.0F : 0.0F));
        event.setJump(jump);
        event.setSneak(sneak);
        event.setSprint(sprint);
        // KeyboardInputMixin 会把 set* 写回 keyPresses 并按新 forward/strafe 重算 moveVector
    }

    /** 物理按键是否按下（26.2 KeyMapping.isDown 在 GUI 打开时不可靠，走 GLFW 直读） */
    private boolean physicalKeyDown(KeyMapping keyMapping) {
        com.mojang.blaze3d.platform.InputConstants.Key key =
                ((com.ylmac.adapt.mixins.input.KeyMappingAccessor) keyMapping).adapt_getKey();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(key.getValue());
    }
}
