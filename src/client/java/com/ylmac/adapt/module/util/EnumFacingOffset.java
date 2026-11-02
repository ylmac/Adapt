package com.ylmac.adapt.module.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * EnumFacingOffset — 支撑面方向 + 偏移。
 */
public class EnumFacingOffset {
    public final Direction facing;
    public final BlockPos offset;

    public EnumFacingOffset(Direction facing, BlockPos offset) {
        this.facing = facing;
        this.offset = offset;
    }

    public BlockPos getOffset() {
        return offset;
    }
}
