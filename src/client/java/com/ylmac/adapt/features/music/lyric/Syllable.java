package com.ylmac.adapt.features.music.lyric;

public class Syllable {
    public final String text;
    public final long startMs;
    public final long durationMs;

    public Syllable(String text, long startMs, long durationMs) {
        this.text = text;
        this.startMs = startMs;
        this.durationMs = durationMs;
    }

    public long endMs() {
        return startMs + durationMs;
    }
}
