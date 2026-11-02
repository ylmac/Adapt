package com.ylmac.adapt.module.misc.music;

import com.ylmac.adapt.module.misc.music.audio.PcmSource;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 javax.sound.sampled 的音频后端。
 * 不使用 OpenAL, 避免与 Minecraft 的 SoundEngine 冲突。
 * ffmpeg/Mp3PcmSource 输出 s16le 44100Hz stereo PCM, 直接写入 SourceDataLine。
 */
public final class QplayerAudioBackend implements AudioBackend {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("qplayer-mod");

    private static final int BUFFER_BYTES = 16 * 1024;

    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicLong seekTargetMs = new AtomicLong(-1L);

    private volatile String source;
    private volatile float volume = 0.8f;
    private volatile Thread audioThread;
    private volatile Runnable onComplete;
    private volatile Runnable onError;
    private volatile long positionMs = 0L;
    private volatile long durationMs = 0L;

    private SourceDataLine line;
    private AudioFormat format;

    public QplayerAudioBackend() {
        // 延迟初始化 SourceDataLine, 在首次 play 时创建
    }

    @Override
    public void play(String src, long startMs) {
        if (src == null || src.isEmpty()) return;
        this.source = src;
        seekTargetMs.set(Math.max(0L, startMs));
        playing.set(true);
        ensureAudioThread();
    }

    @Override
    public void pause() { playing.set(false); }

    @Override
    public void resume() { if (source != null) playing.set(true); }

    @Override
    public boolean isPlaying() { return playing.get(); }

    @Override
    public void seek(long ms) { seekTargetMs.set(Math.max(0L, ms)); }

    @Override
    public long position() { return positionMs; }

    @Override
    public long duration() { return durationMs; }

    @Override
    public void setVolume(float v) { volume = Math.max(0f, Math.min(1f, v)); }

    @Override
    public void setOnComplete(Runnable callback) { this.onComplete = callback; }

    @Override
    public void setOnError(Runnable callback) { this.onError = callback; }

    @Override
    public void release() {
        shuttingDown.set(true);
        playing.set(false);
        Thread t = audioThread;
        if (t != null) t.interrupt();
        try {
            if (line != null) {
                line.flush();
                line.close();
            }
        } catch (Exception ignored) {}
    }

    private void ensureAudioThread() {
        if (audioThread != null && audioThread.isAlive()) return;
        Thread t = new Thread(this::audioLoop, "qplayer-audio");
        t.setDaemon(true);
        audioThread = t;
        t.start();
    }

    private void audioLoop() {
        while (!shuttingDown.get()) {
            boolean reachedEnd = false;
            try {
                reachedEnd = playCurrentSource();
            } catch (Throwable e) {
                LOG.error("[QPlayerAudio] Audio error", e);
                playing.set(false);
                Runnable err = onError;
                if (err != null) err.run();
            }
            if (reachedEnd) {
                playing.set(false);
                Runnable cb = onComplete;
                if (cb != null) cb.run();
            }
            if (!playing.get() && seekTargetMs.get() < 0L && !shuttingDown.get()) {
                try { Thread.sleep(60L); } catch (InterruptedException e) { break; }
            }
        }
        closeLine();
    }

    private boolean playCurrentSource() throws Exception {
        String openSrc = source;
        if (openSrc == null) { playing.set(false); return false; }

        long startMs = Math.max(0L, seekTargetMs.getAndSet(-1L));
        PcmSource pcm = PcmSource.open(openSrc);
        try {
            int sampleRate = pcm.sampleRate();
            int channels = Math.max(1, Math.min(2, pcm.channels()));
            durationMs = pcm.durationMs();

            // 创建/重用 SourceDataLine
            ensureLine(sampleRate, channels);

            long seekBaseMs = startMs > 0 ? pcm.seek(startMs) : 0L;
            long framesSinceBase = 0L;
            int frameBytes = channels * 2;

            byte[] buf = new byte[BUFFER_BYTES];
            boolean draining = false;

            while (!shuttingDown.get()) {
                if (!openSrc.equals(source)) return false;

                long seek = seekTargetMs.getAndSet(-1L);
                if (seek >= 0L) {
                    seekBaseMs = pcm.seek(seek);
                    framesSinceBase = 0L;
                    if (line != null) line.flush();
                    draining = false;
                }

                // 音量控制 (SourceDataLine 不支持 setVolume, 需要软件调节)
                // 简单实现: 通过 PCM 数据缩放

                if (!playing.get()) {
                    Thread.sleep(20L);
                    continue;
                }

                if (draining) {
                    // 等待 line 播完
                    if (line != null && line.getMicrosecondPosition() > 0
                            && line.available() >= line.getBufferSize()) {
                        // 已播完
                        publishPosition(seekBaseMs, framesSinceBase, sampleRate);
                        return true;
                    }
                    Thread.sleep(5L);
                    continue;
                }

                int want = Math.min(buf.length, line.available());
                // 对齐 frame 边界
                want -= want % frameBytes;
                if (want <= 0) {
                    Thread.sleep(2L);
                    continue;
                }

                int got = 0;
                while (got < want) {
                    int r = pcm.read(buf, got, want - got);
                    if (r <= 0) break;
                    got += r;
                }
                got -= got % frameBytes;

                if (got <= 0) {
                    draining = true;
                    continue;
                }

                // 软件音量调节 (s16le)
                applyVolume(buf, got, volume);

                line.write(buf, 0, got);
                framesSinceBase += got / frameBytes;
                publishPosition(seekBaseMs, framesSinceBase, sampleRate);
            }
            return false;
        } finally {
            if (line != null) line.flush();
            pcm.close();
        }
    }

    private void ensureLine(int sampleRate, int channels) throws LineUnavailableException {
        AudioFormat newFormat = new AudioFormat(
                sampleRate, 16, channels, true, false);
        if (line != null && format != null
                && format.getSampleRate() == sampleRate
                && format.getChannels() == channels) {
            return; // 复用
        }
        closeLine();
        format = newFormat;
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format, BUFFER_BYTES * 2);
        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, BUFFER_BYTES * 2);
        line.start();
        LOG.info("[QPlayerAudio] Audio line opened: {}Hz {}ch", sampleRate, channels);
    }

    private void closeLine() {
        if (line != null) {
            try { line.flush(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
            line = null;
        }
        format = null;
    }

    /** 软件音量调节 — 对 s16le PCM 数据按音量缩放 */
    private static void applyVolume(byte[] buf, int len, float vol) {
        if (vol >= 0.999f) return; // 满音量不需要处理
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (buf[i] & 0xFF) | (buf[i + 1] << 8);
            s = (int) (s * vol);
            buf[i] = (byte) (s & 0xFF);
            buf[i + 1] = (byte) ((s >> 8) & 0xFF);
        }
    }

    private void publishPosition(long seekBaseMs, long framesSinceBase, int sampleRate) {
        positionMs = seekBaseMs + framesSinceBase * 1000L / sampleRate;
    }
}
