package com.ylmac.adapt.module.misc.music.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class Mp3PcmSource extends PcmSource {
    private final byte[] pcmData;
    private int readOffset;
    private int seekOffset;

    public Mp3PcmSource(String path) throws IOException {
        InputStream fileIn = new FileInputStream(path);
        Bitstream bitstream = new Bitstream(fileIn);
        Decoder decoder = new Decoder();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            while (true) {
                Header h = bitstream.readFrame();
                if (h == null) break;
                try {
                    SampleBuffer buf = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                    if (sampleRate == 0) {
                        sampleRate = h.frequency();
                        channels = buf.getChannelCount();
                    }
                    short[] samples = buf.getBuffer();
                    int len = Math.min(buf.getBufferLength(), samples.length);
                    totalSamples += len;
                    for (int i = 0; i < len; i++) {
                        out.write(samples[i] & 0xFF);
                        out.write((samples[i] >> 8) & 0xFF);
                    }
                } catch (DecoderException e) {
                    // skip bad frame
                } finally {
                    bitstream.closeFrame();
                }
            }
        } catch (BitstreamException e) {
            // end of stream
        }

        try { bitstream.close(); } catch (Exception ignored) {}
        try { fileIn.close(); } catch (Exception ignored) {}

        pcmData = out.toByteArray();
        if (channels <= 0) channels = 2;
        if (sampleRate <= 0) sampleRate = 44100;
        if (sampleRate > 0) {
            durationMs = totalSamples * 1000L / ((long) sampleRate * channels);
        }
    }

    @Override
    public long seek(long ms) throws IOException {
        long targetSample = ms * sampleRate / 1000;
        int targetByte = (int) Math.min(targetSample * channels * 2, pcmData.length);
        seekOffset = targetByte;
        readOffset = targetByte;
        return ms;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (readOffset >= pcmData.length) return -1;
        int available = pcmData.length - readOffset;
        int toRead = Math.min(len, available);
        System.arraycopy(pcmData, readOffset, b, off, toRead);
        readOffset += toRead;
        return toRead;
    }

    @Override
    public void close() {
    }
}
