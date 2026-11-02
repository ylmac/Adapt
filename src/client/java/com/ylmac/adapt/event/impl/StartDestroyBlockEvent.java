package com.ylmac.adapt.event.impl;

import com.ylmac.adapt.event.bus.Cancellable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 开始破坏方块事件（可修改位置/方向、可取消） */
public class StartDestroyBlockEvent extends Cancellable {

    private BlockPos blockPos;
    private Direction direction;

    public StartDestroyBlockEvent(BlockPos blockPos, Direction direction) {
        this.blockPos = blockPos;
        this.direction = direction;
    }

    public BlockPos getBlockPos() {
        return this.blockPos;
    }

    public void setBlockPos(BlockPos blockPos) {
        this.blockPos = blockPos;
    }

    public Direction getDirection() {
        return this.direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

}
