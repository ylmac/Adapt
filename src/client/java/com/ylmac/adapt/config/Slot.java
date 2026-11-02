package com.ylmac.adapt.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * {@link #setPaintOnly} 只改值、不通知，进度条这种每帧写入就不会把界面整棵刷一遍。
 */
public final class Slot<T> {

    private static long changeVersion;

    private volatile T value;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private volatile BiConsumer<Slot<T>, Object> interceptor;

    public Slot() {
        this(null);
    }

    public Slot(T initial) {
        this.value = initial;
    }

    public static long changeVersion() {
        return changeVersion;
    }

    public T get() {
        return value;
    }

    public T peek() {
        return value;
    }

    public float peekFloat() {
        T current = value;
        return current instanceof Number number ? number.floatValue() : 0f;
    }

    public double peekDouble() {
        T current = value;
        return current instanceof Number number ? number.doubleValue() : 0d;
    }

    public int peekInt() {
        T current = value;
        return current instanceof Number number ? number.intValue() : 0;
    }

    public void set(T next) {
        BiConsumer<Slot<T>, Object> gate = interceptor;
        if (gate != null) {
            gate.accept(this, next);
            return;
        }
        assign(next, true);
    }

    /** 写入但不通知监听者，也不抬高 {@link #changeVersion}。 */
    public void setPaintOnly(T next) {
        assign(next, false);
    }

    public void setInterceptor(BiConsumer<Slot<T>, Object> interceptor) {
        this.interceptor = interceptor;
    }

    public void addListener(Consumer<T> listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Consumer<T> listener) {
        listeners.remove(listener);
    }

    private void assign(T next, boolean notify) {
        value = next;
        if (!notify) return;
        changeVersion++;
        for (Consumer<T> listener : listeners) {
            listener.accept(next);
        }
    }
}
