package com.ylmac.adapt.render;

/**
 * 光影渲染总开关（延迟光照 / 方向阴影 / 体素遮挡 / 云）。
 * <p>
 * <b>为什么需要一个中心开关而不是各模块自己判断。</b>Adapt 的自有渲染管线
 * 是<b>全局替换</b>式的：它接管地形管线（G-buffer 几何阶段）、实体/物品管线、
 * 天空/云管线、Sodium 的 AO 与光照烘焙，以及一整套全屏后处理（延迟光照 +
 * 降噪 + 合成 + 云）。这些替换分散在 10 个 mixin 里，靠"每个模块自己判断"
 * 无法保证一致性 —— 关掉一半会出现"G-buffer 在写、光照 pass 不跑"这种黑屏。
 * <p>
 * 所以这里放一个 volatile 布尔，由 {@code Shaders} 模块（设置分类，默认关闭）
 * 在 {@code onEnable/onDisable} 里写入；所有入口点只读它。关闭时每个入口点
 * 都<b>原样返回</b>，游戏回到完全的原版渲染路径（含原版 AO、原版光照烘焙、
 * 原版 blob shadow）。
 */
public final class AdaptRenderToggle {

    /**
     * 默认 false = 完全原版渲染。
     * <p>
     * volatile：模块在客户端线程切换，渲染线程读取；没有它的话 JIT 可以把
     * 这个循环里的读提到循环外，导致开关"过一会儿才生效"。
     */
    private static volatile boolean worldEffectsEnabled;

    private AdaptRenderToggle() {
    }

    public static boolean isWorldEffectsEnabled() {
        return worldEffectsEnabled;
    }

    public static void setWorldEffectsEnabled(boolean enabled) {
        worldEffectsEnabled = enabled;
    }
}
