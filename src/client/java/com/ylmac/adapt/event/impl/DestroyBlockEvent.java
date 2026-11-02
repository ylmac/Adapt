package com.ylmac.adapt.event.impl;

import com.ylmac.adapt.event.bus.Cancellable;
import net.minecraft.core.BlockPos;

/** 破坏方块事件（可取消） */
public class DestroyBlockEvent extends Cancellable {

    private final BlockPos pos;

    public DestroyBlockEvent(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return this.pos;
    }

}
