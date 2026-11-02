package com.ylmac.adapt.mixins.input;

import com.ylmac.adapt.event.bus.EventBus;
import com.ylmac.adapt.event.impl.KeyboardInputEvent;
import com.ylmac.adapt.event.impl.StrafeEvent;
import com.ylmac.adapt.input.ClientInputAddition;
import com.ylmac.adapt.module.combat.Fences;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 键盘输入事件 → EventBus + Fences 输入改写（输入劫持枢纽）。
 * <p>
 * 26.2 输入链路：LocalPlayer.aiStep 每 tick 调 this.input.tick，实际是
 * KeyboardInput.tick（读 KeyMapping 状态 → 写 keyPresses + 计算 moveVector），
 * 之后 applyInput 消费 getMoveVector/keyPresses 驱动移动。
 * <p>
 * 本 mixin 注入 KeyboardInput.tick 尾部，做两件事（顺序固定）：
 * 1. 发布 KeyboardInputEvent（forward/strafe/jump/sneak）→ EventBus：
 * 模块（AutoStuck 锁移动）可修改；修改后写回 keyPresses 并重算 moveVector。
 * 2. 调用 Fences.onInputTick(this)（ClientInputAddition 接口）：读原始输入
 * 检测边缘并反向 moveVector。
 * <p>
 * 字段访问（javap 确证）：keyPresses(moveVector 定义在父类 {@code ClientInput}：
 * public Input keyPresses / protected Vec2 moveVector / public Vec2 getMoveVector）。
 * Mixin 字段 shadow 不沿继承链解析，且 MixinClientInput 已在 ClientInput 上
 * 混入接口实现（@Shadow 指向目标类自身字段），KeyboardInput 继承 ClientInput
 * 即继承这些实现 — 因此这里经 {@link ClientInputAddition} 动态分派读写，
 * 不 shadow 父类字段。KeyboardInput 覆盖了 tick 且不调 super.tick，
 * 故 ClientInput.tick 的注入不触发—此处是唯一实际生效的输入钩子。
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void adapt_onKeyboardInput(CallbackInfo ci) {
        ClientInputAddition input = (ClientInputAddition) this;
        Input keys = input.adapt_getKeyPresses();
        // 记录原始输入（Fences 判定玩家是否在移动）—KeyboardInput.tick 覆盖
        // 了 ClientInput.tick，故原始向量必须在此记录（MixinClientInput 的
        // ClientInput.tick 注入不触发，只提供字段读写实现）。
        input.adapt_setOriginalMoveVector(input.adapt_getMoveVector());

        // 1. 发布 KeyboardInputEvent（模块可锁移动/改输入）
        KeyboardInputEvent event = EventBus.INSTANCE.post(new KeyboardInputEvent(
                (keys.forward() ? 1.0F : 0.0F) - (keys.backward() ? 1.0F : 0.0F),
                (keys.left() ? 1.0F : 0.0F) - (keys.right() ? 1.0F : 0.0F),
                keys.jump(),
                keys.shift(),
                keys.sprint()
        ));
        // 写回修改后的输入：keyPresses 经接口，moveVector 经接口 set
        input.adapt_setKeyPresses(event.toNewInput());
        input.adapt_setMovementInput(
                new Vec2(event.getStrafe(), event.getForward()).normalized());

 // 1b. 发布 StrafeEvent：携带改写后的移动输入
        // 与当前偏航，供 Strafe/TargetStrafe 等转向类模块读取（时机 = 输入刷新后、
        EventBus.INSTANCE.post(new StrafeEvent(
                event.getForward(), event.getStrafe(),
                Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getYRot() : 0.0F));

        // 2. Fences 边缘检测（读原始输入，反向移动防掉落）。运行时 this 是
        // KeyboardInput 实例（继承 ClientInput 上 MixinClientInput 混入的接口实现），
        // 编译期 KeyboardInputMixin 不 implements 该接口，故双 cast 绕过检查。
        Fences fences = Fences.get();
        if (fences != null) {
            fences.onInputTick((ClientInputAddition) (Object) this);
        }
    }
}
