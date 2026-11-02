package com.ylmac.adapt.event.impl;

import com.ylmac.adapt.event.bus.Cancellable;
import net.minecraft.world.entity.Entity;

/** 攻击减速事件（可取消） */
public class AttackSlowDownEvent extends Cancellable {

    private final Entity entity;
    private final float knockbackAmount;

    public AttackSlowDownEvent(Entity entity, float knockbackAmount) {
        this.entity = entity;
        this.knockbackAmount = knockbackAmount;
    }

    public Entity getEntity() {
        return entity;
    }

    public float getKnockbackAmount() {
        return knockbackAmount;
    }

}
