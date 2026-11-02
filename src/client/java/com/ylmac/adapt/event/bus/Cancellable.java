package com.ylmac.adapt.event.bus;

/** 可取消事件基类（取消后事件不再向后续监听器传播） */
public class Cancellable {

    private boolean cancelled = false;

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        this.cancelled = true;
    }

}
