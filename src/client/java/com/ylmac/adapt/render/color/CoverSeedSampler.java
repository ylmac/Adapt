package com.ylmac.adapt.render.color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import com.ylmac.adapt.config.DesktopSettings;
import com.ylmac.adapt.module.misc.music.PlayerController;
import com.ylmac.adapt.AdaptClient;
import com.ylmac.adapt.compose.screens.music.AdaptScreen;

/**
 * 歌曲封面 → 莫奈种子（"封面" 取色来源）。
 *
 * 监听 PlayerController.coverBytes（换歌/换封面时由控制器发布），把封面图
 * 解码成 ARGB 像素、走 MonetQuantizer 提取种子色，交给 WorldColorSampler 的
 * 过渡机制平滑应用。所有重活（解码+量化）都在后台线程，主线程只收一个
 * {hue, chroma, tone}。
 *
 * 与 WorldColorSampler 由 DesktopSettings.monetSource 互斥门控：
 * monetSource == 0 → 封面路径生效（世界采样暂停）
 * monetSource == 1 → 世界路径生效（本类仅挂着监听，不投种子）
 */
public final class CoverSeedSampler {

    /** 封面的最长边上限：超过则按步长抽稀，避免大图慢量化 */
    private static final int MAX_SAMPLE_DIM = 240;

    private static CoverSeedSampler instance;

    public static CoverSeedSampler get() {
        if (instance == null) instance = new CoverSeedSampler();
        return instance;
    }

    private boolean attached = false;
    private String lastHash = "";

    private CoverSeedSampler() {}

    /** 一次性挂监听；之后每次 coverBytes 变化都会评估（来源/开关在触发时再判）。 */
    public synchronized void attachIfNeeded() {
        if (attached) return;
        PlayerController pc = AdaptScreen.getPlayerController();
        if (pc == null || pc.coverBytes == null) return;
        attached = true;
        try {
            pc.coverBytes.addListener(bytes -> onCoverSet(bytes));
        } catch (Throwable ex) {
            attached = false;
        }
    }

    /** 强制重分析当前封面（跳过 hash 去重，供 forceRefresh 调用）。 */
    public void forceNow(byte[] bytes) {
        lastHash = "";
        onCoverSet(bytes);
    }

    private void onCoverSet(byte[] bytes) {
        try {
            if (bytes == null || bytes.length == 0) return;
            DesktopSettings settings = AdaptScreen.getDesktopSettings();
            if (settings == null) return;
            boolean enabled = Boolean.TRUE.equals(settings.monetEnabled.peek());
            int source = settings.monetSource == null
                || settings.monetSource.peek() == null
                ? 0 : settings.monetSource.peek().intValue();
            // 非调试模式：一律走封面（世界取色被废弃，只在 Debug 下可选）
            if (!com.ylmac.adapt.module.render.InterfaceModule.isDebug()) {
                source = 0;
            }
            if (!enabled || source != 0) return; // 走世界采样或莫奈关闭时不介入

            String hash = sha256(bytes);
            if (hash.equals(lastHash)) return;   // 同一张封面不重复提取
            lastHash = hash;

            Thread worker = new Thread(() -> {
                try {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (img == null) {
                        AdaptClient.LOGGER.warn("[QPlayer] Cover undecodable (webp?) — skip seed");
                        return;
                    }
                    int w = img.getWidth(), h = img.getHeight();
                    int stride = (int) Math.max(1, Math.ceil(Math.max(w, h) / (double) MAX_SAMPLE_DIM));
                    int sw = (w + stride - 1) / stride, sh = (h + stride - 1) / stride;
                    int[] argb = new int[sw * sh];
                    int k = 0;
                    for (int y = 0; y < h; y += stride) {
                        for (int x = 0; x < w; x += stride) {
                            argb[k++] = img.getRGB(x, y); // 0xAARRGGBB
                        }
                    }
                    double[] hct = MonetQuantizer.extractSeedColorHct(argb, 1.0);
                    AdaptClient.LOGGER.info("[QPlayer] Cover seed H={} C={} T={} ({}x{} → {})",
                        String.format("%.1f", hct[0]), String.format("%.1f", hct[1]),
                        String.format("%.1f", hct[2]), w, h, argb.length);
                    WorldColorSampler.get().submitSeed(hct[0], hct[1], hct[2]);
                } catch (Throwable ex) {
                    AdaptClient.LOGGER.warn("[QPlayer] Cover seed failed: {} ({})",
                        ex.getClass().getSimpleName(), ex.getMessage());
                }
            }, "qplayer-cover-quantize");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable ignored) {
            // 监听回调里绝不能抛：丢回事件循环继续跑
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }
}