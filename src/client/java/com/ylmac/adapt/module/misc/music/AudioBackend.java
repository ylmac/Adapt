package com.ylmac.adapt.module.misc.music;

public interface AudioBackend {
    void play(String source, long startMs);
    void pause();
    void resume();
    boolean isPlaying();
    void seek(long ms);
    long position();
    long duration();
    void setVolume(float volume);
    void setOnComplete(Runnable callback);
    default void setOnStarted(Runnable callback) {}
    default void setOnPaused(Runnable callback) {}
    default void setOnResumed(Runnable callback) {}
    default void setOnError(Runnable callback) {}
    void release();
}