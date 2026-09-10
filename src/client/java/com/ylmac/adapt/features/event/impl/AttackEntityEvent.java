package com.ylmac.adapt.features.event.impl;

import com.ylmac.adapt.features.event.bus.Cancellable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** 攻击实体事件（可取消） */
public class AttackEntityEvent extends Cancellable {

    private final Player player;
    private final Entity entity;

    public AttackEntityEvent(Player player, Entity entity) {
        this.player = player;
        this.entity = entity;
    }

    public Player getPlayer() {
        return player;
    }

    public Entity getEntity() {
        return entity;
    }

}
