package com.ylmac.adapt.features.music.cache;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ylmac.adapt.features.music.netease.dto.NeteaseSong;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.ylmac.adapt.features.music.util.Logger;

/**
 * In-memory song metadata index with disk persistence.
 * Maps song ID → title/artist/cover for offline search and cached-songs list.
 */
public final class SongMetaIndex {

    private static final int MAX_ENTRIES = 3000;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("qplayer-mod");

    private final File file;
    private final Gson gson = new Gson();
    private final Map<Long, NeteaseSong> byId = Collections.synchronizedMap(
            new LinkedHashMap<Long, NeteaseSong>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, NeteaseSong> e) {
                    return size() > MAX_ENTRIES;
                }
            });
    private volatile boolean dirty = false;

    public SongMetaIndex(File cacheBase) {
        this.file = new File(cacheBase, "songs.json");
    }

    public void load() {
        try {
            if (!file.isFile()) return;
            try (FileReader r = new FileReader(file)) {
                Type t = new TypeToken<List<NeteaseSong>>() {}.getType();
                List<NeteaseSong> list = gson.fromJson(r, t);
                if (list == null) return;
                synchronized (byId) {
                    for (NeteaseSong s : list) {
                        if (s != null && s.id != 0 && s.name != null && !s.name.isEmpty())
                            byId.put(s.id, s);
                    }
                }
            }
        } catch (Throwable e) {
            LOG.warn("[SongMetaIndex] load failed: {}", e.getMessage());
        }
    }

    public void upsert(long id, String name, String artist, String album, String coverUrl, long durationMs) {
        if (id == 0 || name == null || name.isEmpty()) return;
        NeteaseSong s = new NeteaseSong();
        s.id = id;
        s.name = name;
        s.artist = artist;
        s.album = album;
        s.coverUrl = coverUrl;
        s.durationMs = durationMs;
        byId.put(id, s);
        dirty = true;
    }

    public void upsert(NeteaseSong s) {
        if (s == null) return;
        upsert(s.id, s.name, s.artist, s.album, s.coverUrl, s.durationMs);
    }

    public List<NeteaseSong> search(String keywordLower, int limit) {
        List<NeteaseSong> out = new ArrayList<>();
        if (keywordLower == null || keywordLower.isEmpty()) return out;
        List<NeteaseSong> snapshot;
        synchronized (byId) {
            snapshot = new ArrayList<>(byId.values());
        }
        for (int i = snapshot.size() - 1; i >= 0 && out.size() < limit; i--) {
            NeteaseSong s = snapshot.get(i);
            String name = s.name != null ? s.name.toLowerCase(Locale.ROOT) : "";
            String artist = s.artist != null ? s.artist.toLowerCase(Locale.ROOT) : "";
            if (name.contains(keywordLower) || artist.contains(keywordLower)) out.add(s);
        }
        return out;
    }

    public List<NeteaseSong> all() {
        List<NeteaseSong> snapshot;
        synchronized (byId) {
            snapshot = new ArrayList<>(byId.values());
        }
        Collections.reverse(snapshot);
        return snapshot;
    }

    public void save() {
        if (!dirty) return;
        try {
            List<NeteaseSong> snapshot;
            synchronized (byId) {
                snapshot = new ArrayList<>(byId.values());
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileWriter w = new FileWriter(file)) {
                gson.toJson(snapshot, w);
            }
            dirty = false;
        } catch (Throwable e) {
            LOG.warn("[SongMetaIndex] save failed: {}", e.getMessage());
        }
    }
}
