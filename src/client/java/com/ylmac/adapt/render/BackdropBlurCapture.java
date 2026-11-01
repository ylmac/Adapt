package com.ylmac.adapt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.ColorMatrix;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import com.ylmac.adapt.AdaptClient;

/**
 * GPU-only 帧缓冲截取 + 模糊（无 CPU 回读）。
 *
 * 流程：
 * 1. 从 Minecraft RenderTarget 获取颜色纹理（GPU）
 * 2. CommandEncoder.copyTextureToTexture → GPU 纹理间复制（不经过 CPU）
 * 3. 包装为 Skija Surface（wrapBackendRenderTarget）
 * 4. makeImageSnapshot → GPU Image
 * 5. 全分辨率 ImageFilter.makeBlur → GPU 高斯模糊
 *
 * 模糊图和帧缓冲同尺寸。没人会取样的帧不做拷贝。
 */
public final class BackdropBlurCapture {

    /**
     * 每帧缓存：同一帧内所有 HUD 元素（聊天/积分榜/音乐/时钟）共用一份模糊结果。
     * <p>
     * 每次 {@link #getBlurredImage} 都要新建一个包装 captureTexture 的 Vulkan
     * Surface 再 snapshot（重资源操作）+ 重画一遍模糊。之前 4 个 HUD 元素各自调用
     * → 每帧 4~5 次重建 = 明显掉帧（用户："模糊照片非常卡，迷你播放器都不会卡"——
     * 迷你播放器只在界面打开时每帧调一次）。
     * <p>
     * 约定：返回的 Image **由本类持有，调用方不要 close**。
     */
    private static int frameStamp = 0;
    /** 本帧的模糊图（按半径分槽，同一帧内不同元素可各取所需，帧边界统一释放） */
    private static final java.util.HashMap<Integer, Image> blurCache = new java.util.HashMap<>();
    /** blurCache 属于哪一帧；与 frameStamp 不等时说明缓存过期 */
    private static int blurCacheStamp = -1;

    private static GpuTexture captureTexture;
    private static int captureW = 0, captureH = 0;
    /**
     * 世界模糊只有这一档，和 HUD 卡片一致（半径 24 → σ12）。
     * 界面设置里的 Blur Radius 默认 12，写入时乘 2，还是这一个数。
     */
    public static final float CARD_RADIUS = 24f;
    private static float radius = CARD_RADIUS;

    /** 复用的全分辨率模糊 Surface */
    private static Surface blurSurface;
    /** 本帧包出来的源图，同一帧里不同半径共用，不再每个半径各包一次。 */
    private static Image frameSource;
    private static int frameSourceStamp = -1;
    /** 高斯滤镜按 sigma 留着。半径来自设置，不会每帧变。 */
    private static final java.util.HashMap<Integer, ImageFilter> filterCache = new java.util.HashMap<>();
    /** 把世界缓冲的 alpha 收成 1。天空的 alpha 不是透明度。 */
    private static ColorFilter opaqueAlpha;

    private BackdropBlurCapture() {}

    /**
     * 这一帧有没有人会来取模糊图。采集发生在界面绘制之前，所以按模块和当前界面判断，
     * 而不是等调用发生。判断失败时照常采集，避免玻璃突然变黑。
     */
    private static boolean wanted() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return false;
            var screen = mc.gui != null ? mc.gui.screen() : null;
            if (screen instanceof com.ylmac.adapt.compose.host.AdaptComposeScreen compose
                    && compose.wantsBackdropBlur()) {
                return true;
            }
            if (MusicWidgetOverlay.wantsBackdrop()) return true;
            if (ClockWidgetOverlay.wantsBackdrop()) return true;
            if (ScoreBoardOverlay.wantsBackdrop()) return true;
            if (ChatOverlay.wantsBackdrop()) return true;
            if (ModuleListOverlay.wantsBackdrop()) return true;
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    public static void captureFrame() {
        frameStamp++;          // 新的一帧 → 缓存的模糊作废
        closeBlurCache();
        blurCacheStamp = frameStamp;
        closeFrameSource();
        if (!wanted()) return;
        Probe.begin("blur.capture");
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameRenderer == null) return;
            RenderTarget mainRT = mc.gameRenderer.mainRenderTarget();
            if (mainRT == null || mainRT.width <= 0 || mainRT.height <= 0) return;

            GpuTexture srcTexture = mainRT.getColorTexture();
            if (srcTexture == null) return;

            int w = mainRT.width;
            int h = mainRT.height;

            // 日志：打印帧缓冲分辨率
            if (w != captureW || h != captureH) {
                AdaptClient.LOGGER.info("[QPlayer] BackdropBlurCapture: framebuffer size {}x{}", w, h);
            }

            // 创建/复用目标纹理
            if (captureTexture == null || captureW != w || captureH != h) {
                if (captureTexture != null) { captureTexture.close(); captureTexture = null; }

                int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC;
                captureTexture = RenderSystem.getDevice().createTexture(
                        "qplayer-backdrop", usage, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
                captureW = w;
                captureH = h;
            }

            // GPU 纹理间复制（不经过 CPU）
            // copyTextureToTexture(source, dest, mipLevel, destX, destY, sourceX, sourceY, width, height)
            var enc = RenderSystem.getDevice().createCommandEncoder();
            enc.copyTextureToTexture(srcTexture, captureTexture, 0, 0, 0, 0, 0, w, h);
            enc.submit();

            // 刷新 Skija（共享 Vulkan/GL 队列，确保复制完成）。
            // 用非阻塞 flush：copy 指令与 Skia 读取在同一队列上按提交顺序执行，
            // present() 时才真正读 captureTexture，此时 copy 必然已完成。
            DirectContext ctx = GpuContextHelper.getOrCreateContext();
            if (ctx != null) {
                ctx.flushAndSubmit(false);
            }
        } catch (Throwable t) {
            AdaptClient.LOGGER.error("[QPlayer] BackdropBlurCapture.captureFrame failed: {}", t.toString());
            Probe.hit("blur.captureFail");
        } finally {
            Probe.end("blur.capture");
        }
    }

    /** 设置里的 Blur Radius 乘 2 写到这里。默认 24，和卡片相同。 */
    public static void setRadius(float value) {
        if (value > 0f && value <= 64f) radius = value;
    }

    /**
     * 本帧的世界模糊。调用方不要 close。
     * 所有界面共用 {@link #radius}，同一帧再取就是同一张图。
     */
    public static Image getBlurredImage() {
        return blurred(radius);
    }

    /**
     * 本帧没模糊的世界颜色。调用方不要 close。
     * 液态玻璃要自己在字形里做很轻的模糊，不能用卡片那张 σ12。
     */
    public static Image getFrameImage() {
        if (captureTexture == null || captureW <= 0 || captureH <= 0) return null;
        try {
            DirectContext ctx = GpuContextHelper.getOrCreateContext();
            if (ctx == null) return null;
            return sourceForFrame(ctx);
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[QPlayer] BackdropBlurCapture.getFrameImage failed: {}", t.toString());
            return null;
        }
    }

    private static Image blurred(float blurRadius) {
        if (captureTexture == null || captureW <= 0 || captureH <= 0) {
            Probe.hit("blur.missing");
            return null;
        }
        // 帧变了 → 上一帧的缓存全部失效（此刻渲染线程安全，显式 close 而不是留给 GC：
        // Skija 的 Cleaner 线程析构 GPU Image 时走 GrVkTexture::onRelease，
        // 和渲染线程的 flushAndSubmit 并发访问 GrResourceCache 就是 SIGTRAP）
        if (blurCacheStamp != frameStamp) {
            closeBlurCache();
            blurCacheStamp = frameStamp;
        }
        int key = Math.round(blurRadius * 100f);
        Image cached = blurCache.get(key);
        if (cached != null) return cached;
        Image result = buildBlurredImage(blurRadius);
        if (result != null) {
            dumpOnce(result);
            blurCache.put(key, result);
        }
        return result;
    }

    /** 释放本帧全部模糊图（渲染线程）。 */
    private static void closeBlurCache() {
        for (Image img : blurCache.values()) {
            try { img.close(); } catch (Throwable ignored) { }
        }
        blurCache.clear();
    }

    /** 一次性诊断：把模糊结果写盘（看模糊到底有没有内容、内容对不对）。 */
    private static boolean dumped;

    private static void dumpOnce(Image blurred) {
        if (dumped || blurred == null) return;
        dumped = true;
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home"), ".adapt-blur");
            java.nio.file.Files.createDirectories(dir);
            var d = blurred.encodeToData(io.github.humbleui.skija.EncodedImageFormat.PNG, 100);
            if (d != null) {
                java.nio.file.Files.write(dir.resolve("blurred.png"), d.getBytes());
                d.close();
                AdaptClient.LOGGER.info("[QPlayer] blur debug: wrote {} ({}x{})",
                        dir.resolve("blurred.png"), blurred.getWidth(), blurred.getHeight());
            }
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[QPlayer] blur debug dump failed: {}", t.toString());
        }
    }

    private static Image buildBlurredImage(float blurRadius) {
        Probe.begin("blur.build");
        try {
            DirectContext ctx = GpuContextHelper.getOrCreateContext();
            if (ctx == null) return null;
            Image snapshot = sourceForFrame(ctx);
            if (snapshot == null) return null;

            int blurW = Math.max(1, captureW);
            int blurH = Math.max(1, captureH);
            // 半径 24 → σ12。以前在 1/4 图上用 σ3，放大四倍后观感也是 σ12。
            float sigma = blurRadius / 2.0f;

            if (blurSurface == null || blurW != blurSurface.getWidth() || blurH != blurSurface.getHeight()) {
                if (blurSurface != null) blurSurface.close();
                ImageInfo info = ImageInfo.makeN32Premul(blurW, blurH);
                blurSurface = Surface.makeRenderTarget(ctx, false, info);
                if (blurSurface == null) {
                    blurSurface = null;
                    return null;
                }
            }

            Canvas blurCanvas = blurSurface.getCanvas();
            Rect full = Rect.makeXYWH(0, 0, captureW, captureH);
            // 上一帧的结果还在这张目标上。天空 alpha 往往是 0，模糊结果也不是不透明，
            // 再叠上去会在天地交界一帧帧加亮，最后变成一条白线。
            blurCanvas.restoreToCount(1);
            blurCanvas.clear(0xFF000000);
            Paint opaquePaint = new Paint();
            opaquePaint.setBlendMode(BlendMode.SRC);
            opaquePaint.setColorFilter(opaqueAlpha());
            blurCanvas.drawImageRect(snapshot, full, full, SamplingMode.LINEAR, opaquePaint, false);
            opaquePaint.close();
            Image opaque = blurSurface.makeImageSnapshot();
            try {
                blurCanvas.clear(0xFF000000);
                Paint blurPaint = new Paint();
                blurPaint.setBlendMode(BlendMode.SRC);
                blurPaint.setImageFilter(blurFilter(sigma));
                blurCanvas.drawImageRect(opaque, full, full, SamplingMode.LINEAR, blurPaint, false);
                blurPaint.close();
                return blurSurface.makeImageSnapshot();
            } finally {
                opaque.close();
            }
        } catch (Throwable t) {
            AdaptClient.LOGGER.error("[QPlayer] BackdropBlurCapture.getBlurredImage failed: {}", t.toString());
            Probe.hit("blur.buildFail");
            return null;
        } finally {
            Probe.end("blur.build");
        }
    }

    private static ColorFilter opaqueAlpha() {
        if (opaqueAlpha == null) {
            opaqueAlpha = ColorFilter.makeMatrix(new ColorMatrix(
                    1, 0, 0, 0, 0,
                    0, 1, 0, 0, 0,
                    0, 0, 1, 0, 0,
                    0, 0, 0, 0, 1));
        }
        return opaqueAlpha;
    }

    private static ImageFilter blurFilter(float sigma) {
        int key = Math.round(sigma * 100f);
        ImageFilter cached = filterCache.get(key);
        if (cached != null) return cached;
        ImageFilter blur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
        filterCache.put(key, blur);
        return blur;
    }

    /**
     * 本帧的源图。同一帧只包一次，帧边界在 {@link #captureFrame()} 关掉。
     * GPU Image 必须显式 close，不能留给 Cleaner 线程，否则和渲染线程的 flush 并发会 SIGTRAP。
     */
    private static Image sourceForFrame(DirectContext ctx) {
        if (frameSource != null && frameSourceStamp == frameStamp) return frameSource;
        closeFrameSource();
        Image snapshot = null;
        if (captureTexture instanceof VulkanGpuTexture) {
            long vkImage = ((VulkanGpuTexture) captureTexture).vkImage();
            // layout 必须是 GENERAL(1)。MC 的 copy 不做 layout 过渡，声明 UNDEFINED
            // 会让 Skia 把内容作废，模糊图全黑。
            BackendRenderTarget brt = BackendRenderTarget.makeVulkan(
                    captureW, captureH, vkImage,
                    0, 1, 37, 0x10 | 0x04 | 0x02 | 0x01, 1, 1);
            Surface freshSurface = Surface.wrapBackendRenderTarget(
                    ctx, brt, SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, null);
            if (freshSurface == null) {
                brt.close();
                return null;
            }
            snapshot = freshSurface.makeImageSnapshot();
            freshSurface.close();
            brt.close();
        } else if (GpuContextHelper.isGL()) {
            snapshot = getGlSnapshot(ctx);
        }
        frameSource = snapshot;
        frameSourceStamp = frameStamp;
        return snapshot;
    }

    private static void closeFrameSource() {
        if (frameSource != null) {
            try { frameSource.close(); } catch (Throwable ignored) { }
            frameSource = null;
        }
        frameSourceStamp = -1;
    }

    /** OpenGL 回读路径：读回帧缓冲到 CPU Image，避免 GPU 纹理包装导致的递归崩溃 */
    private static Image getGlSnapshot(DirectContext ctx) {
        try {
            // 通过反射获取 GL 纹理 ID
            int glTexId = 0;
            Class<?> cls = captureTexture.getClass();
            for (String name : new String[]{"glTextureId", "textureId", "id", "glId", "texture"}) {
                try {
                    java.lang.reflect.Field f = cls.getDeclaredField(name);
                    f.setAccessible(true);
                    Object val = f.get(captureTexture);
                    if (val instanceof Number) { glTexId = ((Number) val).intValue(); break; }
                } catch (Exception ignored) {}
            }
            if (glTexId == 0) {
                for (String name : new String[]{"glTextureId", "getId", "textureId", "id"}) {
                    try {
                        java.lang.reflect.Method m = cls.getMethod(name);
                        Object val = m.invoke(captureTexture);
                        if (val instanceof Number) { glTexId = ((Number) val).intValue(); break; }
                    } catch (Exception ignored) {}
                }
            }
            if (glTexId == 0) return null;

            // 创建临时 FBO 读回像素到 CPU
            int[] fboId = new int[1];
            GL30.glGenFramebuffers(fboId);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId[0]);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, glTexId, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glDeleteFramebuffers(fboId[0]);
                return null;
            }

            // 读回像素到 heap byte[]：makeRasterFromBytes 会拷贝进 Skia 自有存储，
            // 避免 makeRasterDirect 指向 DirectByteBuffer 在方法返回后被 GC 回收
            // （实测：drawImageRect → glTexSubImage2D → SIGSEGV / memmove）。
            java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(captureW * captureH * 4);
            GL11.glReadPixels(0, 0, captureW, captureH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL30.glDeleteFramebuffers(fboId[0]);
            pixels.rewind();
            byte[] bytes = new byte[captureW * captureH * 4];
            pixels.get(bytes);

            ImageInfo info = ImageInfo.makeN32Premul(captureW, captureH);
            return Image.makeRasterFromBytes(info, bytes, (long) captureW * 4L);
        } catch (Throwable t) {
            AdaptClient.LOGGER.warn("[QPlayer] BackdropBlurCapture GL snapshot failed: {}", t.toString());
            return null;
        }
    }

    public static void dispose() {
        closeBlurCache();
        closeFrameSource();
        blurCacheStamp = -1;
        for (ImageFilter filter : filterCache.values()) {
            try { filter.close(); } catch (Throwable ignored) { }
        }
        filterCache.clear();
        if (opaqueAlpha != null) {
            try { opaqueAlpha.close(); } catch (Throwable ignored) { }
            opaqueAlpha = null;
        }
        if (captureTexture != null) { captureTexture.close(); captureTexture = null; }
        if (blurSurface != null) { blurSurface.close(); blurSurface = null; }
        captureW = 0;
        captureH = 0;
    }
}
