package com.ylmac.adapt.event.bus.listeners;

/**
 * 所有监听器的基类型。
 */
public interface IListener {

    /** 以指定事件调用监听器 */
    void call(Object event);

    /** 该监听器监听的事件类型 */
    Class<?> getTarget();

    /** 监听器优先级 */
    int getPriority();

    /** 是否为静态方法监听器（已废弃，保留兼容） */
    @Deprecated
    boolean isStatic();

}
