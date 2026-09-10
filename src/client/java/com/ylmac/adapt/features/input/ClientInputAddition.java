package com.ylmac.adapt.features.input;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * ClientInput 扩展接口（仿 LiquidBounce 的 ClientInputAddition）。
 * 由 mixin（MixinClientInput / KeyboardInputMixin）实现，供 Fences 等模块
 * 在输入注入点设置/读取移动输入。
 * <p>
 * 注意：接口必须放在普通包（非 mixin 包）。Mixin 包守卫会拒绝外部类
 * 直接引用 mixin 包内的类（IllegalClassLoadError: in a defined mixin package）。
 */
public interface ClientInputAddition {

    /** 设置移动输入向量（moveVector，x=左右 1/-1，y=前后 1/-1） */
    void adapt_setMovementInput(Vec2 movementVector);

    /** 写回按键状态记录（KeyboardInputMixin 修改后回写） */
    void adapt_setKeyPresses(Input keyPresses);

    /** 读取当前移动向量（KeyboardInputMixin 记录原始输入用） */
    Vec2 adapt_getMoveVector();

    /** 记录原始移动向量（KeyboardInputMixin 在 tick 尾记录，Fences 读取） */
    void adapt_setOriginalMoveVector(Vec2 originalMoveVector);

    /** 读取原版（未经模块改写）的移动输入向量（Fences 判定玩家是否在移动） */
    Vec2 adapt_getOriginalMoveVector();

    /** 读取按键状态记录（forward/backward/left/right/jump/shift/sprint） */
    Input adapt_getKeyPresses();
}
