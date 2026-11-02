package com.ylmac.adapt.render.hud;

/**
 * 可拖拽 HUD 元素的契约（{@link HudEditor} 位置编辑器用）。
 * <p>
 * 坐标一律是 <b>GUI 缩放坐标</b>（与鼠标事件、{@code GuiGraphicsExtractor} 同一空间），
 * 不是物理像素、也不是设计分辨率 — 这样编辑器的命中测试和元素自己的绘制用的是
 * 同一套数值，窗口缩放/改 GUI Scale 时不会错位。
 * <p>
 * 实现方负责：把 {@link #hudX}/{@link #hudY} 当作自己左上角的屏幕位置，
 * 并在 {@link #setHudPos(float, float)} 里存下来（一般写进 DesktopSettings 持久化）。
 */
public interface MovableHud {

    /** 持久化用的稳定 id（不要用显示名，改名会丢位置）。 */
    String hudId();

    /** 编辑器里显示给用户的名字。 */
    String hudLabel();

    /** 编辑器里是否在元素上方显示名字标签（音乐小组件返回 false）。 */
    default boolean hudShowLabel() {
        return true;
    }

    /**
 * 编辑器里的"点击"：按下后很快松开、且没有移动时调用（长按才是拖动）。
 * 元素可以在这里转发给自己内部的按钮命中测试。
 */
    default void hudClick(float mx, float my) {
    }

    float hudX();

    float hudY();

    /** 元素尺寸（GUI 坐标）；用于编辑器的边框与命中测试。 */
    float hudW();

    float hudH();

    /** 当前是否应该显示（模块关闭/无数据时为 false，编辑器会跳过它）。 */
    boolean hudVisible();

    void setHudPos(float x, float y);

    // ───────────────────────── 可选：按格调整大小 ─────────────────────────
    // 默认不可调整，元素按自己固定的尺寸渲染。实现下面这几个方法后，编辑器会
    // 在右下角手柄上提供拖拽缩放（以"格"为单位，不是渲染倍数的缩放 —
    // 布局本身会随格子数变化，就像 Android 桌面小组件）。

    /** 是否支持拖拽调整大小。 */
    default boolean hudResizable() {
        return false;
    }

    /** 当前占几格（宽 / 高）。仅 hudResizable 为真时有意义。 */
    default int hudGridCols() {
        return 0;
    }

    default int hudGridRows() {
        return 0;
    }

    default int hudMinCols() {
        return 1;
    }

    default int hudMinRows() {
        return 1;
    }

    default int hudMaxCols() {
        return 8;
    }

    default int hudMaxRows() {
        return 4;
    }

    /** 一格在 GUI 坐标下的边长（缩放前）。 */
    default int hudCellSize() {
        return 80;
    }

    /** 编辑器拖拽缩放时调用（已夹到 [min, max]）。 */
    default void setHudGridSize(int cols, int rows) {
    }
}
