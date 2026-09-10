package com.ylmac.adapt.features.music.lyric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ylmac.adapt.features.music.util.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Downloads lyrics from the free LRCLIB API (https://lrclib.net) for local tracks
 * that have no embedded / sidecar lyric.
 *
 * <p>Endpoint used:
 * <ul>
 *   <li>{@code GET https://lrclib.net/api/get?track_name=..&artist_name=..&album_name=..}
 *       — exact match, single JSON object; 404 if not found.</li>
 *   <li>fallback {@code GET https://lrclib.net/api/search?track_name=..&artist_name=..}
 *       — fuzzy search, JSON array.</li>
 * </ul>
 *
 * <p>Response fields (both endpoints): {@code plainLyrics}, {@code syncedLyrics} (LRC
 * {@code [mm:ss.xx]line}), {@code name}/{@code trackName}, {@code artistName},
 * {@code albumName}, {@code duration}. We prefer {@code syncedLyrics} (which
 * {@link LrcParser} turns into timed {@link LyricLine}s) and fall back to
 * {@code plainLyrics} for tracks that have no synced version. No API key required.
 */
public final class LyricsDownloader {

    private static final String LRCLIB_BASE = "https://lrclib.net/api";
    private static final String USER_AGENT = "qplayer/1.0";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private LyricsDownloader() {}

    /**
     * Download an LRC string for the given track via LRCLIB.
     *
     * @return the raw LRC ({@code syncedLyrics}) — or the plain text when no synced
     *         lyrics exist — or {@code null} if nothing is found / network fails.
     */
    public static String downloadLrc(String title, String artist, String album) {
        if (title == null || title.trim().isEmpty()) return null;
        String track = title.trim();
        String artistName = artist == null ? "" : artist.trim();
        String albumName = album == null ? "" : album.trim();

        // 1) exact match
        try {
            StringBuilder q = new StringBuilder(LRCLIB_BASE).append("/get?")
                    .append("track_name=").append(urlEnc(track))
                    .append("&artist_name=").append(urlEnc(artistName));
            if (!albumName.isEmpty()) {
                q.append("&album_name=").append(urlEnc(albumName));
            }
            String body = httpGet(q.toString());
            if (body != null) {
                String lrc = extractLrc(body);
                if (lrc != null) return lrc;
            }
        } catch (Throwable e) {
            Logger.warn("LyricsDownloader: /api/get failed for '{}': {}", title, e.getMessage());
        }

        // 2) fuzzy search fallback
        try {
            StringBuilder q = new StringBuilder(LRCLIB_BASE).append("/search?")
                    .append("track_name=").append(urlEnc(track))
                    .append("&artist_name=").append(urlEnc(artistName));
            String body = httpGet(q.toString());
            if (body != null) {
                String lrc = extractLrcFromArray(body);
                if (lrc != null) return lrc;
            }
        } catch (Throwable e) {
            Logger.warn("LyricsDownloader: /api/search failed for '{}': {}", title, e.getMessage());
        }

        return null;
    }

    private static String extractLrc(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            return pickLrc(o);
        } catch (Throwable e) {
            Logger.warn("LyricsDownloader: bad /api/get JSON: {}", e.getMessage());
            return null;
        }
    }

    private static String extractLrcFromArray(String body) {
        try {
            JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
            // Prefer the first entry that has synced lyrics, else first with any lyrics.
            JsonObject syncedHit = null;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                String synced = stringOrNull(o, "syncedLyrics");
                if (synced != null && !synced.trim().isEmpty()) return synced;
                if (syncedHit == null && hasAnyLyrics(o)) syncedHit = o;
            }
            if (syncedHit != null) return pickLrc(syncedHit);
        } catch (Throwable e) {
            Logger.warn("LyricsDownloader: bad /api/search JSON: {}", e.getMessage());
        }
        return null;
    }

    private static String pickLrc(JsonObject o) {
        String synced = stringOrNull(o, "syncedLyrics");
        if (synced != null && !synced.trim().isEmpty()) return synced;
        String plain = stringOrNull(o, "plainLyrics");
        return plain != null && !plain.trim().isEmpty() ? plain : null;
    }

    private static boolean hasAnyLyrics(JsonObject o) {
        return pickLrc(o) != null;
    }

    private static String stringOrNull(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    /**
     * Perform a GET and return the body as a String. Returns {@code null} for
     * non-2xx responses (e.g. 404) or on I/O failure, and closes the stream.
     */
    private static String httpGet(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            c.setInstanceFollowRedirects(true);

            int code = c.getResponseCode();
            InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            if (is == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return code >= 400 ? null : sb.toString();
        } catch (Throwable e) {
            Logger.warn("LyricsDownloader: HTTP GET failed for {}: {}", urlStr, e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String urlEnc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
