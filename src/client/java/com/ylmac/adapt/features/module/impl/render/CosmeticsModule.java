package com.ylmac.adapt.features.module.impl.render;

import com.ylmac.adapt.features.cosmetic.CosmeticLibrary;
import com.ylmac.adapt.features.event.bus.EventHandler;
import com.ylmac.adapt.features.event.impl.ClientTickEvent;
import com.ylmac.adapt.features.module.Category;
import com.ylmac.adapt.features.module.Module;
import com.ylmac.adapt.features.module.settings.impl.StringSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Optional;

/**
 * 本地饰品。名字对应 cosmetics-master.jar 里的文件：
 * 披风 {@code capes/<名字>.png}，翅膀 {@code wings/<名字>.png}，帽子 {@code hats/<名字>/}。
 * 填 none 关掉这一件。只画自己，第三人称能看见。
 */
public class CosmeticsModule extends Module {

    public static CosmeticsModule INSTANCE;

    private final StringSetting cape = stringSetting("披风", "redstone");
    private final StringSetting wings = stringSetting("翅膀", "turquoise");
    private final StringSetting hat = stringSetting("帽子", "horns");

    public CosmeticsModule() {
        super("cosmetics", "饰品", Category.RENDER);
        INSTANCE = this;
        setDefaultEnabled(false);
        setDefaultHidden(false);
    }

    public String cape() {
        return cape.getValue();
    }

    public String wings() {
        return wings.getValue();
    }

    public String hat() {
        return hat.getValue();
    }

    public void equipCape(String name) {
        cape.setValue(blank(name));
    }

    public void equipWings(String name) {
        wings.setValue(blank(name));
    }

    public void equipHat(String name) {
        hat.setValue(blank(name));
    }

    @EventHandler
    private void onTick(ClientTickEvent.Post event) {
        if (!isEnabled()) return;
        CosmeticLibrary.prepareFrame();
    }

    private static String blank(String name) {
        return name == null || name.isBlank() ? "none" : name.trim();
    }

    public static void applyCape(Avatar avatar, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
        CosmeticsModule module = INSTANCE;
        if (module == null || !module.isEnabled() || state.skin == null || avatar == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || avatar.getId() != minecraft.player.getId()) return;
        Identifier texture = CosmeticLibrary.cape(module.cape());
        if (texture == null) return;
        state.showCape = true;
        // 单参数构造会把路径改写成 textures/<原路径>.png，披风层按改写后的 id 取样，
        // 对不上我们注册的动态贴图，背上就会是缺贴图的紫块。第二个参数才是真正的取样路径。
        state.skin = state.skin.with(PlayerSkin.Patch.create(
                Optional.empty(),
                Optional.of(new ClientAsset.ResourceTexture(texture, texture)),
                Optional.empty(),
                Optional.empty()));
    }
}
