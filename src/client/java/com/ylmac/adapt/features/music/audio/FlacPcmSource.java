package com.ylmac.adapt.features.music.audio;

import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.io.RandomFileInputStream;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

import java.io.File;
import java.io.IOException;

final class FlacPcmSource extends PcmSource {

    private final RandomFileInputStream rfis;
    private final FLACDecoder decoder;
    private final int sampleRate;
    private final int channels;
    private final int bits;
    private final long totalSamples;

    private ByteData framePcm = new ByteData(0);
    private byte[] pending = new byte[0];
    private int pendingPos = 0;
    private int pendingLen = 0;
    private boolean eof = false;

    FlacPcmSource(String path) throws IOException {
        this.rfis = new RandomFileInputStream(new File(path));
        this.decoder = new FLACDecoder(rfis);
        decoder.readMetadata();
        StreamInfo si = decoder.getStreamInfo();
        if (si == null) throw new IOException("FLAC stream info missing");
        this.sampleRate = si.getSampleRate();
        this.channels = si.getChannels();
        this.bits = si.getBitsPerSample();
        this.totalSamples = si.getTotalSamples();
        if (channels < 1 || channels > 2) {
            close();
            throw new IOException("unsupported FLAC channel count: " + channels);
        }
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
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len <= 0) return 0;
        while (pendingPos >= pendingLen) {
            if (!fill()) return -1;
        }
        int n = Math.min(len, pendingLen - pendingPos);
        System.arraycopy(pending, pendingPos, dst, off, n);
        pendingPos += n;
        return n;
    }

    private boolean fill() throws IOException {
        if (eof) return false;
        Frame frame;
        try {
            frame = decoder.readNextFrame();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("FLAC frame decode failed", e);
        }
        if (frame == null) {
            eof = true;
            return false;
        }
        framePcm = decoder.decodeFrame(frame, framePcm);
        byte[] raw = framePcm.getData();
        int rawLen = framePcm.getLen();
        if (bits == 16) {
            if (pending.length < rawLen) pending = new byte[rawLen];
            System.arraycopy(raw, 0, pending, 0, rawLen);
            pendingLen = rawLen;
        } else if (bits == 24) {
            int samples = rawLen / 3;
            int outLen = samples * 2;
            if (pending.length < outLen) pending = new byte[outLen];
            for (int i = 0, o = 0; i < rawLen; i += 3) {
                pending[o++] = raw[i + 1];
                pending[o++] = raw[i + 2];
            }
            pendingLen = outLen;
        } else {
            throw new IOException("unsupported FLAC bit depth: " + bits);
        }
        pendingPos = 0;
        return true;
    }

    @Override
    public long seek(long ms) throws IOException {
        if (totalSamples <= 0) return ms;
        long sample = ms * sampleRate / 1000L;
        if (sample < 0) sample = 0;
        if (sample >= totalSamples) sample = totalSamples - 1;
        long landed;
        try {
            landed = decoder.seek(sample);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("FLAC seek failed", e);
        }
        pendingPos = pendingLen = 0;
        eof = false;
        return landed * 1000L / sampleRate;
    }

    @Override
    public long durationMs() {
        return totalSamples > 0 ? totalSamples * 1000L / sampleRate : 0L;
    }

    @Override
    public void close() {
        try {
            rfis.close();
        } catch (IOException ignored) {
        }
    }
}
