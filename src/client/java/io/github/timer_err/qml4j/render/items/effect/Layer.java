package io.github.timer_err.qml4j.render.items.effect;

import io.github.timer_err.qml4j.engine.binding.Property;

public class Layer {
    public final Property<Boolean> enabled = new Property<>(Boolean.FALSE);
    public final Property<Object> effect = new Property<>(null);
    public final Property<Boolean> smooth = new Property<>(Boolean.FALSE);
    /** 背景模糊半径（像素）。>0 时启用 SaveLayerRec backdrop blur（毛玻璃效果）。 */
    public final Property<Number> blurRadius = new Property<>(0);
}
