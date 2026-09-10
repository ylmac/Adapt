package com.ylmac.adapt.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.OptionalDouble;
import com.ylmac.adapt.QplayerModClient;

public final class GpuSharedTexture extends AbstractTexture {

    public GpuSharedTexture(int width, int height) {
        GpuDevice device = RenderSystem.getDevice();
        if (device == null) {
            throw new IllegalStateException("RenderSystem.getDevice() returned null");
        }

        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT
                  | GpuTexture.USAGE_TEXTURE_BINDING
                  | GpuTexture.USAGE_COPY_SRC
                  | GpuTexture.USAGE_COPY_DST;
        this.texture = device.createTexture(
            "qplayer-surface",
            usage,
            GpuFormat.RGBA8_UNORM,
            width, height,
            1, 1
        );

        if (!(this.texture instanceof VulkanGpuTexture)) {
            throw new IllegalStateException("Expected VulkanGpuTexture but got " + texture.getClass().getName());
        }

        this.textureView = device.createTextureView(this.texture);
        this.sampler = device.createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1,
            OptionalDouble.empty()
        );

        QplayerModClient.LOGGER.info("[GpuSharedTexture] Created {}x{} RGBA8, vkImage=0x{}",
            width, height, Long.toHexString(getVkImage()));
    }

    public long getVkImage() {
        return ((VulkanGpuTexture) texture).vkImage();
    }

    public int getVkWidth() {
        return texture.getWidth(0);
    }

    public int getVkHeight() {
        return texture.getHeight(0);
    }

    @Override
    public void close() {
        releaseTextures();
    }
}