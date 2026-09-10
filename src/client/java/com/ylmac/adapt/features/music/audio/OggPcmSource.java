package com.ylmac.adapt.features.music.audio;

import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.lwjgl.stb.STBVorbis.*;
import static org.lwjgl.system.MemoryUtil.NULL;

final class OggPcmSource extends PcmSource {

    private static final int MAX_SHORTS = 16 * 1024;

    private final ByteBuffer data;
    private final ShortBuffer pcm;
    private final long handle;
    private final int sampleRate;
    private final int channels;
    private final int totalSamples;

    OggPcmSource(String path) throws IOException {
        byte[] all = Files.readAllBytes(Paths.get(path));
        this.data = MemoryUtil.memAlloc(all.length);
        this.data.put(all).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            this.handle = stb_vorbis_open_memory(data, error, null);
            if (handle == NULL) {
                MemoryUtil.memFree(data);
                throw new IOException("stb_vorbis_open_memory failed: " + error.get(0));
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            stb_vorbis_get_info(handle, info);
            this.sampleRate = info.sample_rate();
            this.channels = info.channels();
        }
        if (channels < 1 || channels > 2) {
            stb_vorbis_close(handle);
            MemoryUtil.memFree(data);
            throw new IOException("unsupported ogg channel count: " + channels);
        }
        this.totalSamples = stb_vorbis_stream_length_in_samples(handle);
        this.pcm = MemoryUtil.memAllocShort(MAX_SHORTS);
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int read(byte[] dst, int off, int len) {
        int capShorts = Math.min(len / 2, MAX_SHORTS);
        int framesCap = capShorts / channels;
        if (framesCap <= 0) return 0;
        pcm.clear();
        pcm.limit(framesCap * channels);
        int samplesPerChannel = stb_vorbis_get_samples_short_interleaved(handle, channels, pcm);
        if (samplesPerChannel <= 0) return -1;
        int shorts = samplesPerChannel * channels;
        int o = off;
        for (int i = 0; i < shorts; i++) {
            short v = pcm.get(i);
            dst[o++] = (byte) (v & 0xff);
            dst[o++] = (byte) ((v >> 8) & 0xff);
        }
        return shorts * 2;
    }

    @Override
    public long seek(long ms) {
        long sample = ms * sampleRate / 1000L;
        if (sample < 0) sample = 0;
        if (totalSamples > 0 && sample >= totalSamples) sample = totalSamples - 1;
        stb_vorbis_seek(handle, (int) sample);
        return sample * 1000L / sampleRate;
    }

    @Override
    public long durationMs() {
        return totalSamples > 0 ? totalSamples * 1000L / sampleRate : 0L;
    }

    @Override
    public void close() {
        stb_vorbis_close(handle);
        MemoryUtil.memFree(pcm);
        MemoryUtil.memFree(data);
    }
}
