package com.ylmac.adapt.features.music.audio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class WavPcmSource extends PcmSource {
    private final RandomAccessFile raf;
    private long dataStart;
    private long dataLength;

    public WavPcmSource(String path) throws IOException {
        raf = new RandomAccessFile(path, "r");
        readHeader();
    }

    private void readHeader() throws IOException {
        byte[] head = new byte[44];
        raf.readFully(head);

        int channels = head[22] | (head[23] << 8);
        int sampleRate = head[24] | (head[25] << 8) | (head[26] << 16) | (head[27] << 24);
        int bitsPerSample = head[34] | (head[35] << 8);

        this.channels = channels;
        this.sampleRate = sampleRate;

        dataStart = 44;
        for (long i = 12; i < dataStart; ) {
            raf.seek(i);
            byte[] chunkId = new byte[4];
            raf.readFully(chunkId);
            int chunkSize = readIntLE();
            if (chunkId[0] == 'd' && chunkId[1] == 'a' && chunkId[2] == 't' && chunkId[3] == 'a') {
                dataStart = i + 8;
                dataLength = chunkSize;
                break;
            }
            i += 8 + chunkSize;
        }
        if (dataLength <= 0) dataLength = raf.length() - dataStart;

        totalSamples = dataLength / (channels * bitsPerSample / 8);
        durationMs = totalSamples * 1000L / sampleRate;
        raf.seek(dataStart);
    }

    private int readIntLE() throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    @Override
    public long seek(long ms) throws IOException {
        long targetByte = dataStart + ms * sampleRate * channels * 2 / 1000;
        targetByte -= targetByte % (channels * 2);
        if (targetByte < dataStart) targetByte = dataStart;
        if (targetByte >= dataStart + dataLength) targetByte = dataStart + dataLength - (channels * 2);
        if (targetByte < dataStart) targetByte = dataStart;
        raf.seek(targetByte);
        return ms;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        long remaining = dataStart + dataLength - raf.getFilePointer();
        if (remaining <= 0) return -1;
        int toRead = (int) Math.min(len, remaining);
        raf.readFully(b, off, toRead);
        return toRead;
    }

    @Override
    public void close() {
        try { raf.close(); } catch (Exception ignored) {}
    }
}