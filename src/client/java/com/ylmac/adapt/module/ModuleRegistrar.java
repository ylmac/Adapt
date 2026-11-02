package com.ylmac.adapt.module;

/** 模块清单。新增模块改这里，入口只负责按阶段调用。 */
public final class ModuleRegistrar {
    private ModuleRegistrar() {}

    public static void registerAll() {
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.Fullbright());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.ESP2D());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.SoundFX());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.NoFall());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Sprint());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.AutoClicker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.misc.MusicPlayerModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.InterfaceModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.AppearanceModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.ESP());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.BedPlates());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.Halo3D());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.CosmeticsModule());
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.CPS);
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.SPS);
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.COMBO);
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.TAPS);
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.REACH);
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.KEYS);
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.ARMOR);
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.POTIONS);
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.COOLDOWNS);
        ModuleManager.INSTANCE.addModule(com.ylmac.adapt.module.render.PvpHud.SATURATION);
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.ToggleSprint());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.OldSwing());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.Perspective());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.Shaders());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.MusicWidgetModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.ClockWidgetModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.ScoreBoardModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.ChatModule());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.render.CustomCursor());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.AntiBot());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.ComboOneHit());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.Criticals());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.Fences());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.KeepRange());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.KillAura());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.LagBreak());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.LegitReach());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.Mimic());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.Piercing());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.Regen());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.ThrowableAura());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.TickBase());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.combat.Velocity());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.BlockTracker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.ConsoleSpammer());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.Crasher());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.Disabler());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.GhostHand());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.GodMode());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.LightningTracker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.NoRotate());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.PingSpoof());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.exploit.StaffDetector());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.AimAssist());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.AimBacktrack());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.FastPlace());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.GuiClicker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.HitBox());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.KeepSprint());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.LegitScaffold());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.ManualKBDisplacement());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.NoClickDelay());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.Reach());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.SafeWalk());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.ghost.WTap());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.AutoMLG());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.AutoStuck());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Clipper());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Flight());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.InventoryMove());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Jesus());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.LongJump());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.NoClip());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.NoJumpDelay());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.NoSlow());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Phase());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.PotionExtender());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.ResourcePackSpoof());
        // PLACED/PICKUP），不是功能模块 — 之前被误做成模块注册，已删除（功能与
        // Scaffold 重叠、含死分支、还会和 Scaffold 抢服务器视角旋转包）。
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.SnapTap());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Sneak());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Speed());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Step());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Strafe());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Stuck());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.TargetStrafe());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.Teleport());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.TerrainSpeed());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.movement.WallClimb());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.AntiFireBall());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.AntiSuffocate());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.AntiVoid());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.AutoHead());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.AutoPot());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.AutoTool());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.Blink());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.Breaker());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.Clutch());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.FastBreak());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.FastUse());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.HealthBypass());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.InventorySync());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.Manager());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.PolarDetector());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.Scaffold());
        ModuleManager.INSTANCE.addModule(new com.ylmac.adapt.module.player.Stealer());
        for (com.ylmac.adapt.module.Module m : ModuleManager.INSTANCE.getModules()) {
            ModuleManager.INSTANCE.syncHud(m); // 同步初始状态到 HUD
        }
    }
}
