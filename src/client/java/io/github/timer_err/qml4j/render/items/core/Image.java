/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.github.humbleui.skija.Image
 */
package io.github.timer_err.qml4j.render.items.core;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.Painter;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.Size;

public class Image
extends Item {
    public final Property<String> source = new Property<String>(null);
    public final Property<Object> fillMode = new Property<Object>("Stretch");
    public final Property<Boolean> asynchronous = new Property<>(Boolean.FALSE);
    public final Property<Boolean> cache = new Property<>(Boolean.TRUE);
    public final Property<Number> radius = new Property<Number>(0);
    public final Size sourceSize = new Size();
    public final Property<Number> horizontalAlignment = new Property<Number>(4);
    public final Property<Number> verticalAlignment = new Property<Number>(128);
    public final Property<Number> paintedWidth = new Property<Number>(0);
    public final Property<Number> paintedHeight = new Property<Number>(0);
    public final Property<Number> status = new Property<Number>(0);
    public io.github.humbleui.skija.Image skiaImage;
    public String loadedSource;
    public int intrinsicWidth;
    public int intrinsicHeight;
    public volatile long decodeGen;
    public volatile long decodeReadyGen = -1L;
    public volatile io.github.humbleui.skija.Image pendingImage;
    public volatile int pendW;
    public volatile int pendH;
    public long adoptedGen = -1L;

    public Image() {
        // source 变化 → 重新加载图片 → 尺寸可能变 → markDirty
        this.source.addInvalidationListener(this::markDirty);
    }

    @Override
    public void paint(Painter p, float w, float h, float alpha) {
        p.drawImage(this, w, h, alpha);
    }

    @Override
    protected void releaseResources() {
        io.github.humbleui.skija.Image pend;
        if (this.skiaImage != null) {
            this.skiaImage.close();
            this.skiaImage = null;
        }
        if ((pend = this.pendingImage) != null) {
            pend.close();
            this.pendingImage = null;
        }
        ++this.decodeGen;
        this.loadedSource = null;
    }
}
