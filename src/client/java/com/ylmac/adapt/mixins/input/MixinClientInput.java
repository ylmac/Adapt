package com.ylmac.adapt.mixins.input;

import com.ylmac.adapt.input.ClientInputAddition;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * ClientInput 扩展实现：
 * - 以 @Unique 字段保存"注入后的移动向量"，供 Fences 读取原始输入
 * - 提供 setMovementInput 接口让模块改写 moveVector（Fences 反向移动用）
 * <p>
 * 26.2 的 ClientInput 结构（javap 确证）：
 * keyPresses(Input 记录)/moveVector(Vec2，protected)/hasForwardImpulse。
 * moveVector 由 KeyboardInput.tick 末尾计算（Vec2(leftImpulse, forwardImpulse).normalized），
 * LocalPlayer 移动时直接使用，故在 tick 末尾改写即可反向移动。
 */
@Mixin(ClientInput.class)
public abstract class MixinClientInput implements ClientInputAddition {

    @Shadow
    protected Vec2 moveVector;

    @Shadow
    public Input keyPresses;

    @Unique
    private Vec2 adapt_originalMoveVector = new Vec2(0.0F, 0.0F);

    @Override
    public void adapt_setMovementInput(Vec2 movementVector) {
        this.moveVector = movementVector;
    }

    @Override
    public void adapt_setKeyPresses(Input keyPresses) {
        this.keyPresses = keyPresses;
    }

    @Override
    public Vec2 adapt_getMoveVector() {
        return this.moveVector;
    }

    @Override
    public void adapt_setOriginalMoveVector(Vec2 originalMoveVector) {
        this.adapt_originalMoveVector = originalMoveVector;
    }

    /** 模块读取原始移动输入 */
    @Unique
    public Vec2 adapt_getOriginalMoveVector() {
        return this.adapt_originalMoveVector;
    }

    @Override
    public Input adapt_getKeyPresses() {
        return this.keyPresses;
    }
}
