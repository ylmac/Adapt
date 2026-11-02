package com.ylmac.adapt.module.misc.music.audio;

import com.ylmac.adapt.module.misc.music.model.Track;
import com.ylmac.adapt.module.misc.music.util.Logger;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.LogManager;

public final class DesktopMetadataReader implements MetadataReader {

    static {
        try {
            LogManager.getLogManager().getLogger("org.jaudiotagger").setLevel(Level.OFF);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void read(Track track) {
        if (track.filePath == null) return;
        try {
            AudioFile af = AudioFileIO.read(new File(track.filePath));
            AudioHeader header = af.getAudioHeader();
            if (header != null) {
                track.durationMs = header.getTrackLength() * 1000L;
            }
            Tag tag = af.getTag();
            if (tag != null) {
                String title = safeTag(tag, FieldKey.TITLE);
                if (title != null && !title.isEmpty()) track.title = title;
                track.artist = nullToEmpty(safeTag(tag, FieldKey.ARTIST));
                track.album = nullToEmpty(safeTag(tag, FieldKey.ALBUM));
                Artwork art = tag.getFirstArtwork();
                if (art != null) track.coverBytes = art.getBinaryData();
                // 嵌入式歌词（ID3 USLT / Vorbis comment / MP4 ©lyr 等，jaudiotagger 统一映射到 FieldKey.LYRICS）
                String lyrics = safeTag(tag, FieldKey.LYRICS);
                if (lyrics != null && !lyrics.isEmpty()) track.lyricText = lyrics;
            }
        } catch (Throwable e) {
            Logger.warn("DesktopMetadataReader: {} - {}", track.filePath, e.getMessage());
        }
        // 无嵌入式歌词时，查找同目录同名 sidecar（.lrc / .txt）
        if (track.lyricText == null || track.lyricText.isEmpty()) {
            String sidecar = readSidecarLyric(track.filePath);
            if (sidecar != null) track.lyricText = sidecar;
        }
    }

    /** 同目录、同名、扩展名为 .lrc 或 .txt 的歌词文件；返回文本，找不到返回 null */
    private static String readSidecarLyric(String filePath) {
        if (filePath == null) return null;
        try {
            File audio = new File(filePath);
            File dir = audio.getParentFile();
            if (dir == null) return null;
            String name = audio.getName();
            int dot = name.lastIndexOf('.');
            String base = dot >= 0 ? name.substring(0, dot) : name;
            for (String ext : new String[]{".lrc", ".txt"}) {
                File candidate = new File(dir, base + ext);
                if (!candidate.exists() || !candidate.isFile()) continue;
                String content = readTextWithEncodingFallback(candidate);
                if (content != null && !content.isEmpty()) return content;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readTextWithEncodingFallback(File f) {
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String utf8 = new String(bytes, StandardCharsets.UTF_8);
            if (utf8.indexOf('\uFFFD') < 0) return utf8;
            try {
                return new String(bytes, java.nio.charset.Charset.forName("GBK"));
            } catch (Exception e) {
                return utf8;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static String safeTag(Tag tag, FieldKey key) {
        try {
            return tag.getFirst(key);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
