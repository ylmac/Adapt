package com.ylmac.adapt.module.render;

import com.mojang.blaze3d.platform.InputConstants;
import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.KeyPressEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * 按住左 Alt 只转镜头、人不转。F6 看背后，F7 看面前，F8 回到第一人称。
 */
public class Perspective extends Module {

    public static Perspective INSTANCE;

    private static boolean looking;
    private static float yaw;
    private static float pitch;

    public Perspective() {
        super("perspective", "视角", Category.RENDER);
        INSTANCE = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    public static boolean capture() {
        boolean now = held();
        if (now && !looking) copyPlayer();
        looking = now;
        return now;
    }

    /** 鼠标增量已经是灵敏度之后的值，这里补上和原版 turn 一样的 0.15。 */
    public static boolean steer(double yawDelta, double pitchDelta) {
        if (!held()) {
            looking = false;
            return false;
        }
        if (!looking) copyPlayer();
        looking = true;
        yaw = Mth.wrapDegrees(yaw + (float) yawDelta * 0.15f);
        pitch = Mth.clamp(pitch + (float) pitchDelta * 0.15f, -90f, 90f);
        return true;
    }

    public static float yaw() { return yaw; }
    public static float pitch() { return pitch; }

    @Override
    public String getInfo() {
        return "Alt 环顾";
    }

    @EventHandler
    private void onKey(KeyPressEvent event) {
        if (event.getAction() != 1 || mc.options == null || mc.gui.screen() != null) return;
        int key = event.getKey();
        if (key == 295) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        else if (key == 296) mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        else if (key == 297) mc.options.setCameraType(CameraType.FIRST_PERSON);
    }

    @Override
    protected void onDisable() {
        looking = false;
    }

    private static boolean held() {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gui.screen() != null) return false;
        return InputConstants.isKeyDown(342) || InputConstants.isKeyDown(346);
    }

    private static void copyPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        yaw = player.getYRot();
        pitch = player.getXRot();
    }
}
