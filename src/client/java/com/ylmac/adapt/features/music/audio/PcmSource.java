package com.ylmac.adapt.features.music.audio;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

public abstract class PcmSource implements AutoCloseable {
    protected int sampleRate;
    protected int channels;
    protected long totalSamples;
    protected long durationMs;

    public static PcmSource open(String path) throws IOException {
        String lower = path.toLowerCase();
        // 移除 URL 查询参数后检查扩展名
        String base = lower.contains("?") ? lower.substring(0, lower.indexOf('?')) : lower;
        if (base.endsWith(".mp3")) return new Mp3PcmSource(path);
        if (base.endsWith(".flac")) return new FlacPcmSource(path);
        if (base.endsWith(".ogg")) return new OggPcmSource(path);
        if (base.endsWith(".wav")) return new WavPcmSource(path);
        if (base.endsWith(".m4a") || base.endsWith(".mp4") || base.endsWith(".aac"))
            return new M4aPcmSource(path);
        if (lower.endsWith(".cache") || lower.startsWith("http")) {
            String fmt = detectFormat(path);
            if ("mp3".equals(fmt)) return new Mp3PcmSource(path);
            if ("flac".equals(fmt)) return new FlacPcmSource(path);
            if ("ogg".equals(fmt)) return new OggPcmSource(path);
            if ("wav".equals(fmt)) return new WavPcmSource(path);
            if ("m4a".equals(fmt)) return new M4aPcmSource(path);
        }
        throw new IOException("Unsupported format: " + path);
    }

    private static String detectFormat(String path) throws IOException {
        byte[] head = new byte[12];
        int read;
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            read = raf.read(head);
        } catch (Exception e) {
            return null;
        }
        if (read < 4) return null;
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') return "mp3";
        if (head[0] == (byte) 0xFF && (head[1] & 0xE0) == 0xE0) return "mp3";
        if (read >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') return "flac";
        if (read >= 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') return "ogg";
        if (read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') return "wav";
        if (read >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return "m4a";
        return null;
    }

    public abstract long seek(long ms) throws IOException;
    public abstract int read(byte[] b, int off, int len) throws IOException;

    public int sampleRate() { return sampleRate; }
    public int channels() { return channels; }
    public int bitsPerSample() { return 16; }
    public long durationMs() { return durationMs; }

    @Override public abstract void close();
}