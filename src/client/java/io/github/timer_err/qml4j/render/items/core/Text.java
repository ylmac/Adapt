/*
 * Decompiled with CFR 0.152.
 */
package io.github.timer_err.qml4j.render.items.core;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.Painter;
import io.github.timer_err.qml4j.render.TextLayout;
import io.github.timer_err.qml4j.render.items.core.Font;
import io.github.timer_err.qml4j.render.items.core.Item;

public class Text
extends Item {
    public final Property<String> text = new Property<>("");
    public final Property<String> color = new Property<>("#000000");
    public final Property<Number> fontSize = new Property<Number>(14);
    public final Font font = new Font();
    public final Property<Number> wrapMode = new Property<Number>(0);
    public final Property<Number> horizontalAlignment = new Property<Number>(1);
    public final Property<Number> verticalAlignment = new Property<Number>(32);
    public final Property<Number> elide = new Property<Number>(0);
    public final Property<Number> maximumLineCount = new Property<Number>(0);
    public final Property<Number> lineHeight = new Property<Number>(1);
    public final Property<Number> lineHeightMode = new Property<Number>(0);
    public final Property<Number> contentWidth = new Property<Number>(0);
    public final Property<Number> contentHeight = new Property<Number>(0);
    public String lastMeasuredText;
    public float lastMeasuredSize = -1.0f;
    public double lastSetWidth = Double.NaN;
    public double lastSetHeight = Double.NaN;
    public String cachedText;
    public float cachedSize = -1.0f;
    public boolean cachedBold;
    public float cachedWrapW = -1.0f;
    public int cachedMaxLines = -1;
    public float cachedW;
    public float cachedH;

    public Text() {
        // text/fontSize 变化 → 重新测量文本 → implicitWidth/Height 可能变 → markDirty
        this.text.addInvalidationListener(this::markDirty);
        this.fontSize.addInvalidationListener(this::markDirty);
        this.font.pixelSize.addInvalidationListener(this::markDirty);
        this.wrapMode.addInvalidationListener(this::markDirty);
        this.maximumLineCount.addInvalidationListener(this::markDirty);
        this.elide.addInvalidationListener(this::markDirty);
    }

    public float effectiveFontSize() {
        Number fp = this.font.pixelSize.peek();
        if (fp != null && fp.floatValue() > 0.0f) {
            return fp.floatValue();
        }
        Number fs = this.fontSize.peek();
        return fs != null ? fs.floatValue() : 14.0f;
    }

    @Override
    public void measure(TextLayout t) {
        t.measureText(this);
    }

    @Override
    public void paint(Painter p, float w, float h, float alpha) {
        int argb = p.alphaColor(this.color.peek(), alpha);
        float size = this.effectiveFontSize();
        String ig = p.iconGlyphFor(this);
        if (ig != null) {
            if (!ig.isEmpty()) {
                p.drawIconGlyph(ig, h, argb, size);
            }
            return;
        }
        String s = p.displayTextFor(this);
        if (s.isEmpty()) {
            return;
        }
        boolean elideRight = this.elide.peekInt() == 3;
        boolean bold = Boolean.TRUE.equals(this.font.bold.peek()) || this.font.weight.peekInt() >= 63;
        p.drawWrappedText(s, w, argb, size, this.wrapMode.peekInt(), elideRight, bold, this.horizontalAlignment.peekInt(), this.maximumLineCount.peekInt());
    }
}
