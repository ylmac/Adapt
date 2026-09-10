package com.ylmac.adapt.features.event.bus.listeners;

import com.ylmac.adapt.features.event.bus.EventPriority;

import java.util.function.Consumer;

/** 接收 {@link java.util.function.Consumer} 的监听器。 */
public class ConsumerListener<T> implements IListener {
    private final Class<T> target;
    private final int priority;
    private final Consumer<T> executor;

    public ConsumerListener(Class<T> target, int priority, Consumer<T> executor) {
        this.target = target;
        this.priority = priority;
        this.executor = executor;
    }

    public ConsumerListener(Class<T> target, Consumer<T> executor) {
        this(target, EventPriority.MEDIUM, executor);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void call(Object event) {
        executor.accept((T) event);
    }

    @Override
    public Class<T> getTarget() {
        return target;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public boolean isStatic() {
        return false;
    }

}
