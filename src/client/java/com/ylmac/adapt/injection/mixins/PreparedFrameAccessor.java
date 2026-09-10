package com.ylmac.adapt.injection.mixins;

import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 {@code PreparedFrame} 的私有 phase 执行入口，供 shadow caster 精确
 * 挑选要重画的 phase。
 * <p>
 * <b>为什么必须精确挑。</b>玩家用 {@code RenderTypes.entityTranslucent}
 * （AvatarRenderer 字节码 off.68），而 {@code SubmitNodeCollection.submitModel}
 * 按 {@code RenderType.hasBlending()} 分流（off.63-87）：
 * <pre>
 *   hasBlending() == true  → translucentModels phase   ← 玩家
 *   hasBlending() == false → solid phase               ← 动物（entityCutoutNoCull）
 * </pre>
 * 所以只重跑 {@code executeSolid()} 时玩家的提交根本不在里面 —— 实测
 * "动物有影子、人没有"，第一人称第三人称都一样（与 {@code isDetached} 无关）。
 * <p>
 * 但也<b>不能</b>直接调 {@code executeTranslucent()}：它还会执行
 * shadows / seeThroughNameTags / nameTags / texts / translucentCustomGeometry
 * （字节码 off.55-112）。文字与名牌的 quad 会往 shadow map 写深度 —— caster
 * 的颜色掩码是 WRITE_NONE 但深度是写的，那些平面会变成凭空的遮挡物。
 * 所以只取 {@code translucentModels} 这一个 phase。
 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public interface PreparedFrameAccessor {

    @Invoker("executePhase")
    void adapt$executePhase(FeatureRenderPhase<?> phase, FeatureFrameContext context);

    @Accessor("context")
    FeatureFrameContext adapt$context();

    @Accessor("submitNodeStorage")
    SubmitNodeStorage adapt$submitNodeStorage();
}
