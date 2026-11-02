package com.ylmac.adapt.module.misc.music.library;

import com.google.gson.Gson;

import com.ylmac.adapt.module.misc.music.model.Track;
import com.ylmac.adapt.config.AppDirs;
import com.ylmac.adapt.module.misc.music.util.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LibraryCache {

    private static final int CACHE_VERSION = 5;

    private static final int THUMB_EDGE = 256;
    private static final int PLAY_EDGE = 1024;

    private final Path indexFile;
    private final Path coversDir;
    private final Path lyricsDir;
    private final Gson gson = new Gson();

    public LibraryCache() {
        Path baseDir = Paths.get(AppDirs.cacheBase(), "local-cache");
        this.indexFile = baseDir.resolve("library.json");
        this.coversDir = baseDir.resolve("covers");
        this.lyricsDir = baseDir.resolve("lyrics");
    }

    private static final class Index {
        int version;
        List<Entry> entries;
    }

    private static final class Entry {
        String filePath;
        String contentUri;
        String title;
        String artist;
        String album;
        long durationMs;
        String coverThumbPath;
        String coverLocalPath;
        String lyricFilePath;
        String translationFilePath;
        String romajiFilePath;
        String lyricText;
        long fileSize;
        long fileMtime;
    }

    public Map<String, Track> load() {
        Map<String, Track> out = new HashMap<>();
        if (!Files.exists(indexFile)) return out;
        try {
            String json = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
            Index index = gson.fromJson(json, Index.class);
            if (index == null || index.version != CACHE_VERSION || index.entries == null) {
                Logger.info("LibraryCache: schema {} != {}, discarding cache",
                        index == null ? -1 : index.version, CACHE_VERSION);
                return out;
            }
            for (Entry e : index.entries) {
                if (e == null || e.filePath == null) continue;
                out.put(e.filePath, toTrack(e));
            }
            Logger.info("LibraryCache: loaded {} cached tracks", out.size());
        } catch (Throwable t) {
            Logger.warn("LibraryCache: load failed: {}", t.getMessage());
        }
        return out;
    }

    public void save(List<Track> tracks) {
        try {
            Files.createDirectories(indexFile.getParent());
            Index index = new Index();
            index.version = CACHE_VERSION;
            index.entries = new ArrayList<>(tracks.size());
            for (Track t : tracks) {
                if (t.source != Track.Source.LOCAL || t.filePath == null) continue;
                index.entries.add(toEntry(t));
            }
            Files.write(indexFile, gson.toJson(index).getBytes(StandardCharsets.UTF_8));
            pruneOrphans(tracks);
        } catch (Throwable t) {
            Logger.warn("LibraryCache: save failed: {}", t.getMessage());
        }
    }

    public static boolean isFresh(Track cached, long size, long mtime) {
        return cached != null && cached.fileSize == size && cached.fileMtime == mtime;
    }

    public void writeCovers(Track t, String filePath, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        byte[][] sized = CoverThumbnailer.downscale(bytes, THUMB_EDGE, PLAY_EDGE);
        byte[] thumb = sized != null ? sized[0] : bytes;
        byte[] play = sized != null ? sized[1] : bytes;
        t.coverThumbPath = writeCoverFile(filePath, thumb, ".thumb.img");
        t.coverLocalPath = writeCoverFile(filePath, play, ".img");
    }

    private String writeCoverFile(String filePath, byte[] bytes, String suffix) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            Files.createDirectories(coversDir);
            Path p = coversDir.resolve(hash(filePath) + suffix);
            Files.write(p, bytes);
            return p.toString();
        } catch (Throwable t) {
            Logger.warn("LibraryCache: cover write failed for {}: {}", filePath, t.getMessage());
            return null;
        }
    }

    public String writeLyric(String filePath, String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            Files.createDirectories(lyricsDir);
            Path p = lyricsDir.resolve(hash(filePath) + ".lrc");
            Files.write(p, text.getBytes(StandardCharsets.UTF_8));
            return p.toString();
        } catch (Throwable t) {
            Logger.warn("LibraryCache: lyric write failed for {}: {}", filePath, t.getMessage());
            return null;
        }
    }

    private static Track toTrack(Entry e) {
        Track t = new Track();
        t.source = Track.Source.LOCAL;
        t.filePath = e.filePath;
        t.contentUri = e.contentUri;
        t.title = e.title;
        t.artist = e.artist;
        t.album = e.album;
        t.durationMs = e.durationMs;
        t.coverThumbPath = e.coverThumbPath;
        t.coverLocalPath = e.coverLocalPath;
        t.lyricFilePath = e.lyricFilePath;
        t.translationFilePath = e.translationFilePath;
        t.romajiFilePath = e.romajiFilePath;
        t.lyricText = e.lyricText;
        t.fileSize = e.fileSize;
        t.fileMtime = e.fileMtime;
        return t;
    }

    private static Entry toEntry(Track t) {
        Entry e = new Entry();
        e.filePath = t.filePath;
        e.contentUri = t.contentUri;
        e.title = t.title;
        e.artist = t.artist;
        e.album = t.album;
        e.durationMs = t.durationMs;
        e.coverThumbPath = t.coverThumbPath;
        e.coverLocalPath = t.coverLocalPath;
        e.lyricFilePath = t.lyricFilePath;
        e.translationFilePath = t.translationFilePath;
        e.romajiFilePath = t.romajiFilePath;
        e.lyricText = t.lyricText;
        e.fileSize = t.fileSize;
        e.fileMtime = t.fileMtime;
        return e;
    }

    private void pruneOrphans(List<Track> tracks) {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (Track t : tracks) {
            if (t.coverThumbPath != null && t.coverThumbPath.startsWith(coversDir.toString()))
                live.add(t.coverThumbPath);
            if (t.coverLocalPath != null && t.coverLocalPath.startsWith(coversDir.toString()))
                live.add(t.coverLocalPath);
            if (t.lyricFilePath != null && t.lyricFilePath.startsWith(lyricsDir.toString()))
                live.add(t.lyricFilePath);
        }
        pruneDir(coversDir, live);
        pruneDir(lyricsDir, live);
    }

    private static void pruneDir(Path dir, java.util.Set<String> live) {
        File d = dir.toFile();
        File[] files = d.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!live.contains(f.toString())) {
                try { f.delete(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
