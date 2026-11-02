package com.ylmac.adapt.module.player;

import com.ylmac.adapt.event.bus.EventHandler;
import com.ylmac.adapt.event.impl.AttackEntityEvent;
import com.ylmac.adapt.module.Category;
import com.ylmac.adapt.module.Module;
import com.ylmac.adapt.module.settings.impl.EnumSetting;
import com.ylmac.adapt.module.settings.impl.IntSetting;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;

/**
 * <p>
 * managers/impl/sound/SoundKey.java + SoundManager.java。
 * <p>
 * 原版行为：
 * - Hit Sound = UwU / Nya / Moan（随机 moan1-4）/ OFF（默认关闭）。
 * - 攻击实体（AttackEntityEvent，由 AttackEntityEventMixin 在 Minecraft.startAttack
 * = ticks*50ms 节流语义）。
 * - Volume 1-100 → 播放音量 volume/100。
 * <p>
 * 音效播放：26.2 的 SimpleSoundInstance 保留
 * {@code (Identifier, SoundSource, volume, pitch, RandomSource, looping, delay,
 * Attenuation, x, y, z, relative)} 构造器（javap 验证），直接以 Identifier 播放
 * 客户端自定义音效（对应 assets/adaptclient/sounds/*.ogg + sounds.json 事件注册）。
 */
public class SoundFX extends Module {

    public static SoundFX INSTANCE;

    private enum SoundKey {
        UWU("uwu"),
        NYA("nya"),
        MOAN1("moan1"),
        MOAN2("moan2"),
        MOAN3("moan3"),
        MOAN4("moan4");

        private final String path;

        SoundKey(String path) {
            this.path = path;
        }

        Identifier id() {
            return Identifier.fromNamespaceAndPath("adaptclient", path);
        }
    }

    private enum HitSound {
        UwU,
        Nya,
        Moan,
        OFF
    }

    private final IntSetting volume = intSetting("Volume", 100, 1, 100, 5);
    private final IntSetting delay = intSetting("Delay", 0, 0, 20, 1);
    private final EnumSetting<HitSound> hitSound = enumSetting("Hit Sound", HitSound.OFF);

 /** 距上次播放的毫秒节流 */
    private long lastPlayTime = -1;

    public SoundFX() {
        super("soundfx", "SoundFX", Category.PLAYER);
        INSTANCE = this;
    }

    @EventHandler
    private void onAttackEntity(AttackEntityEvent event) {
        if (hitSound.getValue() == HitSound.OFF) return;
        long now = System.currentTimeMillis();
        if (now - lastPlayTime < delay.getValue() * 50L) return;
        playHitSound(hitSound.getValue());
        lastPlayTime = now;
    }

    private void playHitSound(HitSound value) {
        switch (value) {
            case UwU -> playSound(SoundKey.UWU);
            case Nya -> playSound(SoundKey.NYA);
            case Moan -> {
                SoundKey sound = switch (ThreadLocalRandom.current().nextInt(4)) {
                    case 0 -> SoundKey.MOAN1;
                    case 1 -> SoundKey.MOAN2;
                    case 2 -> SoundKey.MOAN3;
                    default -> SoundKey.MOAN4;
                };
                playSound(sound);
            }
            default -> { }
        }
    }

    private void playSound(SoundKey key) {
        // pitch(clamp 0.5-2.0), unseeded random, false, 0, NONE, 0,0,0, relative=true)
        SimpleSoundInstance instance = new SimpleSoundInstance(
                key.id(),
                SoundSource.UI,
                Math.max(0.0f, volume.getValue().floatValue() / 100.0f),
                1.0f,
                SoundInstance.createUnseededRandom(),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                0.0,
                0.0,
                0.0,
                true
        );
        mc.getSoundManager().play(instance);
    }
}
