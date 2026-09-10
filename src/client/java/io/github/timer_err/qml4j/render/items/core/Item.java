/*
 * Decompiled with CFR 0.152.
 */
package io.github.timer_err.qml4j.render.items.core;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.ObservableList;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.AnchorLine;
import io.github.timer_err.qml4j.render.Anchors;
import io.github.timer_err.qml4j.render.Painter;
import io.github.timer_err.qml4j.render.TextLayout;
import io.github.timer_err.qml4j.render.items.animation.State;
import io.github.timer_err.qml4j.render.items.animation.StateController;
import io.github.timer_err.qml4j.render.items.animation.Transition;
import io.github.timer_err.qml4j.render.items.core.ChildrenRect;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.effect.Layer;
import io.github.timer_err.qml4j.render.items.input.Keys;
import io.github.timer_err.qml4j.render.items.layout.LayoutAttached;
import io.github.timer_err.qml4j.render.items.transform.Transform;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class Item
extends QObject {
    // ── 脏标记传播 ──────────────────────────────────────────────
    // layoutDirty=true → 本 item 布局属性变了，需要重测；
    // childrenDirty=true → 子孙有变化，需要递归重测子孙。
    // 初始 true（首帧必须全测），measure 完成后清 false；
    // 属性 invalidation listener → markDirty() → 向上传播 markChildDirty()。
    // settleLayout 每帧调用，但 measure 里如果 !layoutDirty && !childrenDirty
    // 就跳过整棵子树 → 静止帧零布局开销。
    public boolean layoutDirty = true;
    public boolean childrenDirty = false;

    // 诊断：记录 markDirty 的调用者（哪个 Property 的 invalidation 触发的）
    public static int markDirtyCount = 0;
    public static String lastMarkDirtyItem = "";
    public static String lastMarkDirtyParent = "";

    public void markDirty() {
        this.layoutDirty = true;
        markDirtyCount++;
        lastMarkDirtyItem = this.getClass().getSimpleName() + "(" + (this.objectName.peek() != null ? this.objectName.peek() : "") + ")";
        Item p = this.parent.peek();
        if (p != null) {
            lastMarkDirtyParent = p.getClass().getSimpleName();
            p.markChildDirty();
        }
    }

    public void markChildDirty() {
        if (!this.childrenDirty) {
            this.childrenDirty = true;
            Item p = this.parent.peek();
            if (p != null) p.markChildDirty();
        }
    }
    // ── 脏标记传播 end ──────────────────────────────────────────

    public final Property<Number> x = new Property<Number>(0);
    public final Property<Number> y = new Property<Number>(0);
    public final Property<Number> width = new Property<Number>(0);
    public final Property<Number> height = new Property<Number>(0);
    public final Property<Number> implicitWidth = new Property<Number>(0);
    public final Property<Number> implicitHeight = new Property<Number>(0);
    public double lastImplicitWidth = Double.NaN;
    public double lastImplicitHeight = Double.NaN;
    public final Property<Boolean> visible = new Property<>(Boolean.TRUE);
    public final Property<Boolean> enabled = new Property<>(Boolean.TRUE);
    public final Property<Number> opacity = new Property<Number>(1.0);
    public final Property<Number> rotation = new Property<Number>(0);
    public final Property<Number> scale = new Property<Number>(1.0);
    public final Property<Number> transformOrigin = new Property<Number>(4);
    public final Property<Number> z = new Property<Number>(0);
    public final Property<Boolean> clip = new Property<>(Boolean.FALSE);
    public final Property<Boolean> antialiasing = new Property<>(Boolean.FALSE);
    public final Property<Boolean> cachedLayout = new Property<>(Boolean.FALSE);
    public boolean cachedLayoutValid;
    public float cachedLayoutW;
    public float cachedLayoutH;
    public int cachedLayoutCount;
    public long cachedLayoutChildVersion = -1L;
    public long cachedLayoutSettleId = -1L;
    public long cachedLayoutChildDims;
    public final Property<Item> parent = new Property<>(null);
    public final List<Item> children = new ObservableList<Item>();
    public final List<Item> resources = new ArrayList<Item>();
    public final Anchors anchors = new Anchors();
    public final ChildrenRect childrenRect = new ChildrenRect();
    public final LayoutAttached Layout = new LayoutAttached();
    public final Property<AnchorLine> left = new Property<>(new AnchorLine(this, AnchorLine.Edge.LEFT));
    public final Property<AnchorLine> right = new Property<>(new AnchorLine(this, AnchorLine.Edge.RIGHT));
    public final Property<AnchorLine> top = new Property<>(new AnchorLine(this, AnchorLine.Edge.TOP));
    public final Property<AnchorLine> bottom = new Property<>(new AnchorLine(this, AnchorLine.Edge.BOTTOM));
    public final Property<AnchorLine> horizontalCenter = new Property<>(new AnchorLine(this, AnchorLine.Edge.HORIZONTAL_CENTER));
    public final Property<AnchorLine> verticalCenter = new Property<>(new AnchorLine(this, AnchorLine.Edge.VERTICAL_CENTER));
    public final Property<String> state = new Property<>(null);
    public final Property<String> objectName = new Property<>(null);
    public final List<State> states = new ArrayList<State>();
    public final List<Transition> transitions = new ArrayList<Transition>();
    public final List<Transform> transform = new ArrayList<Transform>();
    public final Property<Boolean> focus = new Property<>(Boolean.FALSE);
    public final Property<Boolean> activeFocus = new Property<>(Boolean.FALSE);
    public final Property<Boolean> activeFocusOnTab = new Property<>(Boolean.FALSE);
    public final Layer layer = new Layer();
    private final StateController stateController = new StateController(this);
    private boolean stateBindingsInited;
    private Keys keys;
    private Consumer<Item> focusHook;
    private Item lastParent;
    private static final Map<Class<?>, Field[]> FIELDS = new HashMap();

    public Item() {
        this.state.addListener(this.stateController::apply);
        this.parent.addListener(this::onParentChanged);
        // 布局属性变化 → markDirty → 向上传播 → measure 跳过干净子树
        this.width.addInvalidationListener(this::markDirty);
        this.height.addInvalidationListener(this::markDirty);
        this.implicitWidth.addInvalidationListener(this::markDirty);
        this.implicitHeight.addInvalidationListener(this::markDirty);
        this.visible.addInvalidationListener(this::markDirty);
    }

    private void onParentChanged(Item np) {
        Item old = this.lastParent;
        this.lastParent = np;
        if (old == np || old == null) {
            return;
        }
        if (old.children.remove(this) && np != null && !np.children.contains(this)) {
            np.children.add(this);
        }
    }

    public Keys keys() {
        if (this.keys == null) {
            this.keys = new Keys();
        }
        return this.keys;
    }

    public Keys keysOrNull() {
        return this.keys;
    }

    public void installFocusHook(Consumer<Item> hook) {
        this.focusHook = hook;
    }

    public void initStateBindings() {
        if (this.stateBindingsInited) {
            return;
        }
        this.stateBindingsInited = true;
        if (!this.states.isEmpty()) {
            this.stateController.initWhen();
        }
    }

    public void initStateBindingsTree() {
        this.initStateBindings();
        for (Item c : this.children) {
            c.initStateBindingsTree();
        }
        for (Item r : this.resources) {
            r.initStateBindingsTree();
        }
    }

    public boolean isVisible() {
        return !Boolean.FALSE.equals(this.visible.peek());
    }

    public void layout() {
    }

    public void paint(Painter p, float w, float h, float alpha) {
    }

    public void measure(TextLayout t) {
    }

    public Map<String, Object> mapFromItem(Object source, double x, double y) {
        double[] dArray;
        if (source instanceof Item) {
            dArray = ((Item)source).scenePosition();
        } else {
            double[] dArray2 = new double[2];
            dArray2[0] = 0.0;
            dArray = dArray2;
            dArray2[1] = 0.0;
        }
        double[] from = dArray;
        double[] self = this.scenePosition();
        LinkedHashMap<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("x", from[0] + x - self[0]);
        p.put("y", from[1] + y - self[1]);
        return p;
    }

    private double[] scenePosition() {
        double sx = 0.0;
        double sy = 0.0;
        Item it = this;
        while (it != null) {
            sx += it.x.peekDouble();
            sy += it.y.peekDouble();
            Item p = it.parent.peek();
            if (p instanceof Flickable) {
                sx -= ((Flickable)p).contentX.peekDouble();
                sy -= ((Flickable)p).contentY.peekDouble();
            }
            it = it.parent.peek();
        }
        return new double[]{sx, sy};
    }

    public void forceActiveFocus() {
        Item r = this;
        while (r.parent.peek() != null) {
            r = r.parent.peek();
        }
        if (r.focusHook != null) {
            r.focusHook.accept(this);
        }
    }

    public void destroy() {
        this.dispose();
    }

    protected void releaseResources() {
    }

    public void dispose() {
        this.tearDown();
        Item p = this.parent.peek();
        if (p != null) {
            p.children.remove(this);
        }
        this.parent.set(null);
    }

    private void tearDown() {
        int i;
        this.unbindAll();
        this.releaseResources();
        for (i = 0; i < this.children.size(); ++i) {
            this.children.get(i).tearDown();
        }
        for (i = 0; i < this.resources.size(); ++i) {
            this.resources.get(i).tearDown();
        }
    }

    private void unbindAll() {
        Item.unbindFields(this, true);
    }

    private static void unbindFields(Object obj, boolean descendHolders) {
        for (Field f : Item.fieldsOf(obj.getClass())) {
            Object v;
            try {
                v = f.get(obj);
            }
            catch (IllegalAccessException ignore) {
                continue;
            }
            if (v instanceof Property) {
                ((Property)v).unbind();
                continue;
            }
            if (!descendHolders || !Item.isEngineHolder(v)) continue;
            Item.unbindFields(v, false);
        }
    }

    private static boolean isEngineHolder(Object v) {
        if (v == null || v instanceof Item || v instanceof Property) {
            return false;
        }
        return v.getClass().getName().startsWith("io.github.timer_err.qml4j.");
    }

    private static Field[] fieldsOf(Class<?> cls) {
        Field[] cached = FIELDS.get(cls);
        if (cached == null) {
            cached = cls.getFields();
            FIELDS.put(cls, cached);
        }
        return cached;
    }
}
