package com.ylmac.adapt.features.music.cache;

import com.ylmac.adapt.config.store.AppDirs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import com.ylmac.adapt.features.music.util.Logger;

public final class DiskCache {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("qplayer-mod");
    private volatile String baseDir = AppDirs.cacheBase() + "/cache";

    public static final String AUDIO  = "audio";
    public static final String LYRIC  = "lyric";
    public static final String IMAGE  = "image";
    public static final String THUMB64 = "thumb64";
    private static final int THUMB64_MAX_COUNT = 128;

    private volatile long maxSizeBytes;

    public DiskCache(long maxSizeMB) {
        this.maxSizeBytes = maxSizeMB * 1024L * 1024L;
    }

    public void setMaxSizeMB(long mb) {
        this.maxSizeBytes = mb * 1024L * 1024L;
        evictIfNeeded();
    }

    public long getMaxSizeMB() {
        return maxSizeBytes / (1024L * 1024L);
    }

    public void setBaseDir(String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        this.baseDir = dir + "/cache";
    }

    public String baseDir() {
        return baseDir;
    }

    public String audioPath(long neteaseId) {
        if (neteaseId <= 0) return null;
        return baseDir + "/" + AUDIO + "/" + neteaseId + ".cache";
    }

    /** 解锁音源（酷我/波点等）下载的音频缓存路径。独立 key，避免覆盖原生 URL 的缓存。 */
    public String audioPathUnblocked(long neteaseId) {
        if (neteaseId <= 0) return null;
        return baseDir + "/" + AUDIO + "/" + neteaseId + "-u.cache";
    }

    public String lyricPath(long songId) {
        if (songId <= 0) return null;
        return baseDir + "/" + LYRIC + "/" + songId + ".ttml";
    }

    public String neteaseLyricPath(long songId) {
        if (songId <= 0) return null;
        return baseDir + "/" + LYRIC + "/" + songId + ".nlrc";
    }

    /**
     * Disk-cache path for a downloaded lyric of a LOCAL track, keyed by an arbitrary
     * string (file path or title+artist). Uses a non-negative hash of the key so it is
     * independent of Netease song-id files (which use .ttml / .nlrc suffixes).
     */
    public String localLyricPath(String key) {
        if (key == null || key.isEmpty()) return null;
        return baseDir + "/" + LYRIC + "/" + (key.hashCode() & 0x7fffffff) + ".lrc";
    }

    public String imagePath(String url) {
        if (url == null || url.isEmpty()) return null;
        return baseDir + "/" + IMAGE + "/" + Math.abs(url.hashCode()) + ".img";
    }

    public String thumb64Path(String url) {
        if (url == null || url.isEmpty()) return null;
        return baseDir + "/" + THUMB64 + "/" + Math.abs(url.hashCode()) + ".img";
    }

    public boolean hasAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        return p != null && new File(p).exists();
    }

    public boolean hasAudioUnblocked(long neteaseId) {
        String p = audioPathUnblocked(neteaseId);
        return p != null && new File(p).exists() && new File(p).length() > 0;
    }

    public boolean hasLyric(long songId) {
        String p = lyricPath(songId);
        return p != null && new File(p).exists();
    }

    public boolean hasImage(String url) {
        String p = imagePath(url);
        return p != null && new File(p).exists();
    }

    public boolean hasThumb64(String url) {
        String p = thumb64Path(url);
        return p != null && new File(p).exists();
    }

    public boolean deleteAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        if (p == null) return false;
        File f = new File(p);
        return f.exists() && f.delete();
    }

    public String getAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        return touch(p);
    }

    public String getLyric(long songId) {
        String p = lyricPath(songId);
        return touch(p);
    }

    public String getNeteaseLyric(long songId) {
        String p = neteaseLyricPath(songId);
        return touch(p);
    }

    public String getImage(String url) {
        String p = imagePath(url);
        return touch(p);
    }

    public String getThumb64(String url) {
        String p = thumb64Path(url);
        return touch(p);
    }

    public void cacheAudio(String url, long neteaseId) {
        String path = audioPath(neteaseId);
        downloadToFile(url, path);
    }

    public void cacheAudioUnblocked(String url, long neteaseId) {
        String path = audioPathUnblocked(neteaseId);
        downloadToFile(url, path);
    }

    public void cacheLyric(byte[] data, long songId) {
        String path = lyricPath(songId);
        writeBytes(data, path);
    }

    public void cacheNeteaseLyric(byte[] data, long songId) {
        String path = neteaseLyricPath(songId);
        writeBytes(data, path);
    }

    public boolean hasLocalLyric(String key) {
        String p = localLyricPath(key);
        return p != null && new File(p).exists() && new File(p).length() > 0;
    }

    public String getLocalLyric(String key) {
        String p = localLyricPath(key);
        return touch(p);
    }

    public void cacheLocalLyric(byte[] data, String key) {
        String path = localLyricPath(key);
        writeBytes(data, path);
    }

    public void cacheImage(String url) {
        String path = imagePath(url);
        downloadToFile(url, path);
    }

    public void cacheThumb64(String url) {
        String path = thumb64Path(url);
        downloadToFile(url, path);
        evictThumb64IfOverCount();
    }

    public long totalSize() {
        long total = 0;
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE, THUMB64}) {
            total += dirSize(new File(baseDir, sub));
        }
        return total;
    }

    public void clearAll() {
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE, THUMB64}) {
            deleteRecursive(new File(baseDir, sub));
        }
    }

    public void clearType(String type) {
        deleteRecursive(new File(baseDir, type));
    }

    private String touch(String path) {
        if (path == null) return null;
        File f = new File(path);
        if (!f.exists()) return null;
        f.setLastModified(System.currentTimeMillis());
        return path;
    }

    private void writeBytes(byte[] data, String path) {
        if (data == null || path == null) return;
        ensureParent(path);
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(data);
            LOG.info("[DiskCache] disk cache written: {} ({} B)", fileName(path), data.length);
        } catch (Throwable e) {
            LOG.warn("[DiskCache] disk cache write failed: {}", e.getMessage());
            cleanPartial(path);
        }
        evictIfNeeded();
    }

    private void downloadToFile(String url, String path) {
        if (url == null || path == null) return;
        ensureParent(path);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "qplayer/1.0");
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(path)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            long size = new File(path).length();
            LOG.info("[DiskCache] disk cache downloaded: {} ({} B)", fileName(path), size);
        } catch (Throwable e) {
            LOG.warn("[DiskCache] disk cache download failed: {}", e.getMessage());
            cleanPartial(path);
        } finally {
            if (c != null) c.disconnect();
        }
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        long limit = maxSizeBytes;
        if (limit <= 0) return;
        long total = totalSize();
        if (total <= limit) return;

        File[] dirs = {new File(baseDir, AUDIO), new File(baseDir, LYRIC), new File(baseDir, IMAGE)};
        java.util.List<File> files = new java.util.ArrayList<>();
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                File[] children = dir.listFiles();
                if (children != null) files.addAll(Arrays.asList(children));
            }
        }
        files.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        for (File f : files) {
            if (total <= limit) break;
            long sz = f.length();
            if (f.delete()) {
                total -= sz;
                LOG.info("[DiskCache] disk cache evicted: {}", f.getName());
            }
        }
    }

    private static void ensureParent(String path) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
    }

    private static void cleanPartial(String path) {
        File f = new File(path);
        if (f.exists()) f.delete();
    }

    private static long dirSize(File dir) {
        if (!dir.isDirectory()) return 0;
        long total = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) total += f.length();
        }
        return total;
    }

    private static void deleteRecursive(File dir) {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) f.delete();
        }
        dir.delete();
    }

    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private void evictThumb64IfOverCount() {
        File dir = new File(baseDir, THUMB64);
        File[] files = dir.listFiles();
        if (files == null || files.length <= THUMB64_MAX_COUNT) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int overBy = files.length - THUMB64_MAX_COUNT;
        for (int i = 0; i < overBy; i++) {
            files[i].delete();
        }
    }
}
