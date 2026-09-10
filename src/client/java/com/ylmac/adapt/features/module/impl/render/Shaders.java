package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.render.AdaptRenderToggle;

/**
 * 光影总开关（默认关闭）。
 * <p>
 * 打开后启用 Adapt 的整套自有渲染管线：延迟光照（G-buffer + 全屏 light pass）、
 * 方向阴影（太阳/月亮 shadow map）、体素遮挡场（方块光阴影）、Reimagined 天空
 * 与体积云、实体/物品的自有光照管线。关闭时所有入口点原样返回，游戏走
 * 完全原版渲染（含原版 AO、原版光照烘焙、原版 blob shadow）。
 * <p>
 * 模块自身的启用状态就是开关 —— 不再套一层 BoolSetting，否则"模块已启用但
 * 设置里那个勾还没打上"会出现两个真值来源。分项开关（World Lighting /
 * Illuminate Entities 等）在 Halo3D 模块里，只有本总开关打开时才生效。
 * <p>
 * 归在 <b>渲染</b> 分类（与主题模式选择器同页）：它是世界渲染效果的开关，
 * 和 Halo3D / Fullbright 这些同类；放在"设置"分类里反而不符合它的用途。
 */
public class Shaders extends Module {

    public Shaders() {
        super("shaders", "Shaders", Category.RENDER);
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    @Override
    protected void onEnable() {
        AdaptRenderToggle.setWorldEffectsEnabled(true);
    }

    @Override
    protected void onDisable() {
        AdaptRenderToggle.setWorldEffectsEnabled(false);
    }
}
