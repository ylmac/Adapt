package com.ylmac.adapt.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.lang.reflect.Method;
import java.util.OptionalDouble;
import com.ylmac.adapt.AdaptClient;

/**
 * Skia ↔ MC 共享色纹理。26.3 起 Vulkan 纹理在 renderpearl 包，
 * 这里用反射取 {@code vkImage()}，避免硬编码迁包后的 FQN。
 */
public final class GpuSharedTexture extends AbstractTexture {

    public GpuSharedTexture(int width, int height) {
        Object device = RenderSystem.getDevice();
        if (device == null) {
            throw new IllegalStateException("RenderSystem.getDevice() returned null");
        }

        try {
            Class<?> gpuTextureCls = Class.forName("com.mojang.renderpearl.api.textures.GpuTexture");
            Class<?> gpuFormatCls = Class.forName("com.mojang.renderpearl.api.GpuFormat");
            Class<?> addressModeCls = Class.forName("com.mojang.renderpearl.api.textures.AddressMode");
            Class<?> filterModeCls = Class.forName("com.mojang.renderpearl.api.textures.FilterMode");

            int usage = gpuTextureCls.getField("USAGE_RENDER_ATTACHMENT").getInt(null)
                    | gpuTextureCls.getField("USAGE_TEXTURE_BINDING").getInt(null)
                    | gpuTextureCls.getField("USAGE_COPY_SRC").getInt(null)
                    | gpuTextureCls.getField("USAGE_COPY_DST").getInt(null);

            Object rgba = Enum.valueOf(gpuFormatCls.asSubclass(Enum.class), "RGBA8_UNORM");

            Method createTexture = device.getClass().getMethod(
                    "createTexture",
                    String.class, int.class, gpuFormatCls,
                    int.class, int.class, int.class, int.class);
            Object tex = createTexture.invoke(device, "qplayer-surface", usage, rgba, width, height, 1, 1);
            if (tex == null || !tex.getClass().getName().endsWith("VulkanGpuTexture")) {
                throw new IllegalStateException("Expected VulkanGpuTexture but got "
                        + (tex == null ? "null" : tex.getClass().getName()));
            }

            // AbstractTexture.texture 在 26.3 仍是 GpuTexture 风格字段；用反射写，
            // 避免编译期依赖迁包后的具体类型。
            setTextureField("texture", tex);

            Method createView = device.getClass().getMethod("createTextureView", gpuTextureCls);
            setTextureField("textureView", createView.invoke(device, tex));

            Object clamp = Enum.valueOf(addressModeCls.asSubclass(Enum.class), "CLAMP_TO_EDGE");
            Object linear = Enum.valueOf(filterModeCls.asSubclass(Enum.class), "LINEAR");
            Method createSampler = device.getClass().getMethod(
                    "createSampler",
                    addressModeCls, addressModeCls, filterModeCls, filterModeCls,
                    int.class, OptionalDouble.class);
            setTextureField("sampler", createSampler.invoke(
                    device, clamp, clamp, linear, linear, 1, OptionalDouble.empty()));

            AdaptClient.LOGGER.info("[GpuSharedTexture] Created {}x{} RGBA8, vkImage=0x{}",
                    width, height, Long.toHexString(getVkImage()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("GpuSharedTexture init failed on 26.3 renderpearl API", t);
        }
    }

    private void setTextureField(String name, Object value) throws Exception {
        Class<?> c = AbstractTexture.class;
        while (c != null) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(this, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        // 某些版本字段名不同：直接塞到本类不可见父字段失败时，再试常见别名
        throw new NoSuchFieldException("AbstractTexture." + name);
    }

    public long getVkImage() {
        Object tex = getTextureField("texture");
        if (tex == null) return 0L;
        try {
            Method m = tex.getClass().getMethod("vkImage");
            Object v = m.invoke(tex);
            return v instanceof Long ? (Long) v : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    public int getVkWidth() {
        return invokeInt(getTextureField("texture"), "getWidth", 0);
    }

    public int getVkHeight() {
        return invokeInt(getTextureField("texture"), "getHeight", 0);
    }

    private Object getTextureField(String name) {
        Class<?> c = AbstractTexture.class;
        while (c != null) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(this);
            } catch (ReflectiveOperationException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static int invokeInt(Object obj, String method, int level) {
        if (obj == null) return 0;
        try {
            Method m = obj.getClass().getMethod(method, int.class);
            Object v = m.invoke(obj, level);
            return v instanceof Integer ? (Integer) v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public void close() {
        releaseTextures();
    }
}
