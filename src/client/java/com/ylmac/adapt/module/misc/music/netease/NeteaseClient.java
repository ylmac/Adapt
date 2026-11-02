package com.ylmac.adapt.module.misc.music.netease;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.ylmac.adapt.config.AppDirs;
import com.ylmac.adapt.module.misc.music.netease.dto.NeteasePlaylist;
import com.ylmac.adapt.module.misc.music.netease.dto.NeteaseSong;
import com.ylmac.adapt.module.misc.music.netease.dto.TogetherCommand;
import com.ylmac.adapt.module.misc.music.netease.dto.TogetherRoom;
import com.ylmac.adapt.module.misc.music.netease.dto.TogetherSnapshot;
import com.ylmac.adapt.module.misc.music.netease.dto.TogetherStatus;
import com.ylmac.adapt.module.misc.music.netease.dto.TogetherUser;
import com.ylmac.adapt.module.misc.music.util.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NeteaseClient {

    private static JsonElement parseJsonLenient(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        return JsonParser.parseReader(reader);
    }

    public static final NeteaseClient INSTANCE = new NeteaseClient();
    // SLF4J logger - Minecraft 的日志系统会正确显示这些日志
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("qplayer-mod");

    private static final String BASE = "https://music.163.com";
    private static final String EAPI_BASE = "https://interface.music.163.com";
    private static final String XEAPI_BASE = "https://interface3.music.163.com";
    private static final String ANDROID_UA =
            "NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0"
          + " (Linux; U; Android 14; 23013RK75C Build/UKQ1.230804.001)";
    private static final String XEAPI_APPVER = "9.1.65";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
          + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String EAPI_UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
    private static final String CHECK_TOKEN =
            "9ca17ae2e6ffcda170e2e6ee8af14fbabdb988f225b3868eb2c15a879b9a83d274a790ac8ff54a"
          + "97b889d5d42af0feaec3b92af58cff99c470a7eafd88f75e839a9ea7c14e909da883e83fb692a3"
          + "abdb6b92adee9e";
    private static final String REAL_IP = randomChinaIp();

    private final Gson gson = new Gson();
    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private final Path cookieFile;

    private volatile String xeapiPubKey;
    private volatile String xeapiVersion;
    private volatile String xeapiSk;

    public interface ErrorListener {
        void onError(String message);
    }

    private volatile ErrorListener errorListener;
    private volatile String lastErrorMsg;
    private volatile long lastErrorAt;

    public void setErrorListener(ErrorListener l) {
        this.errorListener = l;
    }

    private void reportError(String message) {
        if (message == null || message.isEmpty()) return;
        ErrorListener l = errorListener;
        if (l == null) return;
        long now = System.currentTimeMillis();
        if (message.equals(lastErrorMsg) && now - lastErrorAt < 2500) return;
        lastErrorMsg = message;
        lastErrorAt = now;
        l.onError(message);
    }

    private static String neteaseMessage(JsonObject obj) {
        for (String key : new String[]{"message", "msg"}) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String s = obj.get(key).getAsString();
                if (s != null && !s.isEmpty()) return s;
            }
        }
        return null;
    }

    private NeteaseClient() {
        cookieFile = Paths.get(AppDirs.base(), "netease-cookies.json");
        loadCookies();
    }

    public synchronized String weapiCall(String path, Map<String, Object> json) throws IOException {
        String csrf = cookies.getOrDefault("__csrf", "");
        Map<String, Object> body = json == null ? new HashMap<String, Object>() : new HashMap<String, Object>(json);
        if (!body.containsKey("csrf_token")) body.put("csrf_token", csrf);

        String bodyJson = gson.toJson(body);
        Map<String, String> encrypted;
        try {
            encrypted = NeteaseCrypto.weapi(bodyJson);
        } catch (Exception e) {
            throw new IOException("weapi crypto failed: " + e.getMessage(), e);
        }

        String urlStr = BASE + "/weapi/" + path
                + "?csrf_token=" + urlEnc(csrf);
        String form = formEncode(encrypted);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", BASE);
            conn.setRequestProperty("Origin", BASE);
            conn.setRequestProperty("X-Real-IP", REAL_IP);
            conn.setRequestProperty("X-Forwarded-For", REAL_IP);
            String cookieHeader = cookieHeader();
            if (!cookieHeader.isEmpty()) conn.setRequestProperty("Cookie", cookieHeader);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp;
            try {
                resp = readAll(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            captureSetCookies(conn.getHeaderFields().get("Set-Cookie"));

            if (code >= 400) {
                Logger.warn("Netease HTTP {} on /weapi/{}: {}", code, path, truncate(resp, 200));
                throw new IOException("HTTP " + code + ": " + truncate(resp, 200));
            }
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    public JsonObject weapiJson(String path, Map<String, Object> json) throws IOException {
        return weapiJson(path, json, true);
    }

    public JsonObject weapiJson(String path, Map<String, Object> json, boolean reportErrors)
            throws IOException {
        String resp = weapiCall(path, json);
        JsonElement el = parseJsonLenient(resp);
        if (!el.isJsonObject()) throw new IOException("not a JSON object: " + truncate(resp, 200));
        JsonObject obj = el.getAsJsonObject();
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 200;
        if (code == 301 && isLoggedIn() && !"login/qrcode/client/login".equals(path)) {
            Logger.warn("Netease: session expired (code 301) on /weapi/{} - clearing cookies", path);
            clearCookies();
        } else if (code != 200 && !path.startsWith("login/") && reportErrors) {
            reportError(neteaseMessage(obj));
        }
        return obj;
    }

    public synchronized String eapiCall(String apiPath, Map<String, Object> json, boolean checkToken)
            throws IOException {
        ensureDeviceCookies();
        String csrf = cookies.getOrDefault("__csrf", "");
        String musicU = cookies.getOrDefault("MUSIC_U", "");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("osver", "16.2");
        header.put("deviceId", cookies.getOrDefault("deviceId", ""));
        header.put("os", "iPhone OS");
        header.put("appver", "9.0.90");
        header.put("versioncode", "140");
        header.put("mobilename", "");
        header.put("buildver", Long.toString(System.currentTimeMillis() / 1000L));
        header.put("resolution", "1920x1080");
        header.put("__csrf", csrf);
        header.put("channel", "distribution");
        header.put("requestId", System.currentTimeMillis() + "_" + String.format(java.util.Locale.ROOT,
                "%04d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1000)));
        if (checkToken) header.put("X-antiCheatToken", CHECK_TOKEN);
        if (!musicU.isEmpty()) header.put("MUSIC_U", musicU);

        Map<String, Object> body = json == null
                ? new HashMap<String, Object>() : new HashMap<String, Object>(json);
        body.put("header", header);

        String bodyJson = gson.toJson(body);
        Map<String, String> encrypted;
        try {
            encrypted = NeteaseCrypto.eapi(apiPath, bodyJson);
        } catch (Exception e) {
            throw new IOException("eapi crypto failed: " + e.getMessage(), e);
        }

        String tail = apiPath.startsWith("/api/") ? apiPath.substring(5) : apiPath;
        String urlStr = EAPI_BASE + "/eapi/" + tail;
        String form = formEncode(encrypted);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", EAPI_UA);
            conn.setRequestProperty("Referer", BASE);
            conn.setRequestProperty("X-Real-IP", REAL_IP);
            conn.setRequestProperty("X-Forwarded-For", REAL_IP);
            conn.setRequestProperty("Cookie", headerCookie(header));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp;
            try {
                resp = readAll(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            captureSetCookies(conn.getHeaderFields().get("Set-Cookie"));

            if (code >= 400) {
                Logger.warn("Netease HTTP {} on /eapi/{}: {}", code, tail, truncate(resp, 200));
                throw new IOException("HTTP " + code + ": " + truncate(resp, 200));
            }
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    public JsonObject eapiJson(String apiPath, Map<String, Object> json, boolean checkToken)
            throws IOException {
        String resp = eapiCall(apiPath, json, checkToken);
        JsonElement el = parseJsonLenient(resp);
        if (!el.isJsonObject()) throw new IOException("not a JSON object: " + truncate(resp, 200));
        JsonObject obj = el.getAsJsonObject();
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 200;
        if (code == 301 && isLoggedIn()) {
            Logger.warn("Netease: session expired (code 301) on /eapi/{} - clearing cookies", apiPath);
            clearCookies();
        } else if (code != 200) {
            reportError(neteaseMessage(obj));
        }
        return obj;
    }

    public synchronized String xeapiCall(String apiPath, String formBody) throws IOException {
        ensureDeviceCookies();
        ensureXeapiSession();
        String deviceId = cookies.getOrDefault("deviceId", "");
        String musicU = cookies.getOrDefault("MUSIC_U", "");

        Map<String, String> bsr;
        try {
            bsr = NeteaseCrypto.xeapi(formBody, xeapiPubKey, xeapiVersion, xeapiSk, "android");
        } catch (Exception e) {
            throw new IOException("xeapi crypto failed: " + e.getMessage(), e);
        }

        String tail = apiPath.startsWith("/api/") ? apiPath.substring(5) : apiPath;
        String urlStr = XEAPI_BASE + "/xeapi/" + tail;
        String form = formEncode(bsr);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
            conn.setRequestProperty("User-Agent", ANDROID_UA);
            conn.setRequestProperty("X-Client-Enc-State", "ENCRYPTED");
            conn.setRequestProperty("x-aeapi", "true");
            conn.setRequestProperty("x-deviceid", deviceId);
            conn.setRequestProperty("x-os", "android");
            conn.setRequestProperty("x-osver", "16");
            conn.setRequestProperty("x-appver", XEAPI_APPVER);
            conn.setRequestProperty("x-sdeviceid", deviceId);
            conn.setRequestProperty("x-buildver", Long.toString(System.currentTimeMillis() / 1000L));
            if (!musicU.isEmpty()) conn.setRequestProperty("x-music-u", musicU);
            conn.setRequestProperty("X-Real-IP", REAL_IP);
            conn.setRequestProperty("X-Forwarded-For", REAL_IP);
            conn.setRequestProperty("Cookie", xeapiCookieHeader());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            byte[] raw;
            try {
                raw = readAllBytes(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            captureSetCookies(conn.getHeaderFields().get("Set-Cookie"));

            if (code >= 400) {
                Logger.warn("Netease HTTP {} on /xeapi/{}: {} bytes", code, tail, raw.length);
                throw new IOException("HTTP " + code);
            }
            try {
                return NeteaseCrypto.xeapiResDecrypt(raw);
            } catch (Exception e) {
                throw new IOException("xeapi response decrypt failed: " + e.getMessage(), e);
            }
        } finally {
            conn.disconnect();
        }
    }

    public JsonObject xeapiJson(String apiPath, String formBody) throws IOException {
        String resp = xeapiCall(apiPath, formBody);
        JsonElement el = parseJsonLenient(resp);
        if (!el.isJsonObject()) throw new IOException("not a JSON object: " + truncate(resp, 200));
        JsonObject obj = el.getAsJsonObject();
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 200;
        if (code == 301 && isLoggedIn()) {
            Logger.warn("Netease: session expired (code 301) on /xeapi/{} - clearing cookies", apiPath);
            clearCookies();
        } else if (code != 200) {
            reportError(neteaseMessage(obj));
        }
        return obj;
    }

    private int xeapiCode(String apiPath, String formBody) throws IOException {
        String resp = xeapiCall(apiPath, formBody);
        JsonElement el = parseJsonLenient(resp);
        if (!el.isJsonObject()) return -1;
        JsonObject obj = el.getAsJsonObject();
        return obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
    }

    private synchronized void ensureXeapiSession() throws IOException {
        if (xeapiPubKey != null && xeapiSk != null) return;
        String deviceId = cookies.getOrDefault("deviceId", "");
        String nonce = randomDigits(16);
        String ts = Long.toString(System.currentTimeMillis());
        String sig;
        try {
            sig = NeteaseCrypto.xeapiSign(ts, nonce);
        } catch (Exception e) {
            throw new IOException("xeapiSign failed: " + e.getMessage(), e);
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("appVersion", XEAPI_APPVER);
        form.put("currentKeyVersion", "");
        form.put("deviceId", deviceId);
        form.put("nonce", nonce);
        form.put("os", "android");
        form.put("requestType", "active");
        form.put("signature", sig);
        form.put("t1", "");
        form.put("t2", "");
        form.put("timestamp", ts);
        form.put("uid", "");

        String urlStr = EAPI_BASE + "/api/gorilla/anti/crawler/security/key/get";
        String body = formEncode(form);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        String resp;
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", ANDROID_UA);
            conn.setRequestProperty("Cookie", "deviceId=" + urlEnc(deviceId));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            try {
                resp = readAll(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            if (code >= 400) throw new IOException("HTTP " + code + ": " + truncate(resp, 200));
        } finally {
            conn.disconnect();
        }

        JsonObject obj = parseJsonLenient(resp).getAsJsonObject();
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 0;
        if (code != 200 || !obj.has("data") || !obj.get("data").isJsonObject()) {
            throw new IOException("xeapi key registration failed: " + truncate(resp, 200));
        }
        JsonObject data = obj.getAsJsonObject("data");
        if (!data.has("encryptedData") || data.get("encryptedData").isJsonNull()) {
            throw new IOException("xeapi key registration missing encryptedData");
        }
        String pkJson;
        try {
            pkJson = NeteaseCrypto.xeapiDecryptPublicKey(data.get("encryptedData").getAsString());
        } catch (Exception e) {
            throw new IOException("xeapi publicKey decrypt failed: " + e.getMessage(), e);
        }
        JsonObject pk = parseJsonLenient(pkJson).getAsJsonObject();
        if (!pk.has("publicKey") || !pk.has("sk") || pk.get("publicKey").isJsonNull()
                || pk.get("sk").isJsonNull()) {
            throw new IOException("xeapi publicKey response missing publicKey/sk: " + truncate(pkJson, 200));
        }
        xeapiPubKey = pk.get("publicKey").getAsString();
        xeapiSk = pk.get("sk").getAsString();
        xeapiVersion = pk.has("version") && !pk.get("version").isJsonNull()
                ? pk.get("version").getAsString() : "";
        Logger.info("Netease: registered xeapi session key (version {})", xeapiVersion);
    }

    private String xeapiCookieHeader() {
        ensureDeviceCookies();
        Map<String, String> all = new LinkedHashMap<>(cookies);
        all.put("__remember_me", "true");
        all.put("ntes_kaola_ad", "1");
        all.put("_ntes_nnid", all.getOrDefault("_ntes_nuid", "") + "," + System.currentTimeMillis());
        all.put("WNMCID", wnmcid());
        all.put("WEVNSM", "1.0.0");
        all.put("NMTID", randomHex(16));
        all.put("os", "android");
        all.put("osver", "16");
        all.put("appver", XEAPI_APPVER);
        all.put("channel", "xiaomi");
        all.put("deviceId", cookies.getOrDefault("deviceId", ""));
        all.put("sDeviceId", cookies.getOrDefault("deviceId", ""));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String randomDigits(int n) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append((char) ('0' + r.nextInt(10)));
        return s.toString();
    }

    private static String headerCookie(Map<String, Object> header) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : header.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            String val = String.valueOf(e.getValue()).replace(" ", "%20");
            sb.append(e.getKey()).append('=').append(val);
        }
        return sb.toString();
    }

    public String songUrl(long songId, String level) throws IOException {
        UrlInfo info = songUrlInfo(songId, level);
        return info == null ? null : info.url;
    }

    public static final class UrlInfo {
        public final String url;
        public final boolean trial;
        public UrlInfo(String url, boolean trial) {
            this.url = url;
            this.trial = trial;
        }
    }

    public UrlInfo songUrlInfo(long songId, String level) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", "[" + songId + "]");
        body.put("level", level == null ? "standard" : level);
        body.put("encodeType", "mp3");
        JsonObject obj = weapiJson("song/enhance/player/url/v1", body);
        UrlInfo info = parseUrlInfoV1(obj);
        if (info != null && info.url != null) {
            LOG.info("[Netease] songUrl v1 id={} url={} trial={}", songId, truncate(info.url, 80), info.trial);
            return info;
        }
        LOG.warn("[Netease] songUrl v1 returned null url for id={} (logged_in={}) resp={}",
                songId, isLoggedIn(), truncate(obj.toString(), 300));
        // 降级: 尝试旧版 /song/enhance/player/url 接口
        UrlInfo legacy = songUrlLegacy(songId, 320000);
        if (legacy != null && legacy.url != null) {
            LOG.info("[Netease] songUrl legacy id={} url={}", songId, truncate(legacy.url, 80));
            return legacy;
        }
        return null;
    }

    private UrlInfo parseUrlInfoV1(JsonObject obj) {
        if (!obj.has("data") || !obj.get("data").isJsonArray()) return null;
        if (obj.get("data").getAsJsonArray().size() == 0) return null;
        JsonElement first = obj.get("data").getAsJsonArray().get(0);
        if (!first.isJsonObject()) return null;
        JsonObject song = first.getAsJsonObject();
        JsonElement url = song.get("url");
        if (url == null || url.isJsonNull()) return null;
        boolean trial = song.has("freeTrialInfo") && !song.get("freeTrialInfo").isJsonNull();
        return new UrlInfo(url.getAsString(), trial);
    }

    /** 旧版歌曲URL接口, 对未登录用户更友好 */
    public UrlInfo songUrlLegacy(long songId, int br) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", String.valueOf(songId));
        body.put("br", br);
        JsonObject obj = weapiJson("song/enhance/player/url", body);
        if (!obj.has("data") || !obj.get("data").isJsonArray()) return null;
        if (obj.get("data").getAsJsonArray().size() == 0) return null;
        JsonElement first = obj.get("data").getAsJsonArray().get(0);
        if (!first.isJsonObject()) return null;
        JsonObject song = first.getAsJsonObject();
        JsonElement url = song.get("url");
        if (url == null || url.isJsonNull()) return null;
        boolean trial = song.has("freeTrialInfo") && !song.get("freeTrialInfo").isJsonNull();
        return new UrlInfo(url.getAsString(), trial);
    }

    public List<String> searchHot() throws IOException {
        JsonObject obj = weapiJson("hotsearchlist/get", new HashMap<String, Object>());
        List<String> out = new ArrayList<>();
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("data")) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                if (item.has("searchWord") && !item.get("searchWord").isJsonNull()) {
                    out.add(item.get("searchWord").getAsString());
                }
            }
        }
        if (out.isEmpty()) {
            Logger.warn("hot search returned empty: {}", truncate(obj.toString(), 200));
        }
        return out;
    }

    public List<NeteasePlaylist> personalizedPlaylists(int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("limit", limit);
        body.put("offset", 0);
        body.put("total", true);
        body.put("n", 1000);
        JsonObject obj = weapiJson("personalized/playlist", body);
        List<NeteasePlaylist> out = new ArrayList<>();
        if (obj.has("result") && obj.get("result").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("result")) {
                if (!el.isJsonObject()) continue;
                if (out.size() >= limit) break;
                out.add(parsePlaylist(el.getAsJsonObject()));
            }
        }
        return out;
    }

    public List<NeteaseSong> searchSongs(String keyword, int limit, int offset) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("s", keyword);
        body.put("type", 1);
        body.put("limit", limit);
        body.put("offset", offset);
        JsonObject obj = weapiJson("search/get", body, false);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        LOG.info("[Netease] search/get code={} hasResult={} resp={}",
                code, obj.has("result"), truncate(obj.toString(), 400));
        List<NeteaseSong> out = new ArrayList<>();
        if (obj.has("result") && obj.get("result").isJsonObject()) {
            JsonObject result = obj.getAsJsonObject("result");
            if (result.has("songs") && result.get("songs").isJsonArray()) {
                for (JsonElement el : result.getAsJsonArray("songs")) {
                    if (!el.isJsonObject()) continue;
                    out.add(parseSong(el.getAsJsonObject()));
                }
            }
        }
        return out;
    }

    public static final class SongSearchPage {
        public final List<NeteaseSong> songs;
        public final int total;
        public final int consumed;

        SongSearchPage(List<NeteaseSong> songs, int total, int consumed) {
            this.songs = songs;
            this.total = total;
            this.consumed = consumed;
        }

        public boolean hasMore(int offset, int requestedLimit) {
            int next = offset + consumed;
            return total >= 0 ? next < total : consumed >= requestedLimit;
        }
    }

    public SongSearchPage searchSongsPage(String keyword, int limit, int offset) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("s", keyword);
        body.put("type", 1);
        body.put("limit", limit);
        body.put("offset", offset);
        body.put("total", true);
        JsonObject obj = weapiJson("search/get", body, false);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        LOG.info("[Netease] searchSongsPage code={} offset={}", code, offset);
        List<NeteaseSong> out = new ArrayList<>();
        int total = -1;
        int consumed = 0;
        if (obj.has("result") && obj.get("result").isJsonObject()) {
            JsonObject result = obj.getAsJsonObject("result");
            if (result.has("songCount") && !result.get("songCount").isJsonNull()) {
                total = result.get("songCount").getAsInt();
            }
            if (result.has("songs") && result.get("songs").isJsonArray()) {
                JsonArray songs = result.getAsJsonArray("songs");
                consumed = songs.size();
                for (JsonElement el : songs) {
                    if (!el.isJsonObject()) continue;
                    out.add(parseSong(el.getAsJsonObject()));
                }
            }
        }
        return new SongSearchPage(out, total, consumed);
    }

    public NeteasePlaylist playlistDetail(long playlistId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("id", playlistId);
        body.put("n", 1000);
        body.put("s", 8);
        JsonObject obj = weapiJson("v6/playlist/detail", body);
        if (!obj.has("playlist") || !obj.get("playlist").isJsonObject()) return null;
        return parsePlaylist(obj.getAsJsonObject("playlist"));
    }

    public List<NeteaseSong> playlistTracks(long playlistId, int limit) throws IOException {
        Map<String, Object> detailBody = new HashMap<>();
        detailBody.put("id", playlistId);
        detailBody.put("n", 100000);
        detailBody.put("s", 8);
        JsonObject detail = weapiJson("v6/playlist/detail", detailBody);
        List<NeteaseSong> out = new ArrayList<>();
        if (!detail.has("playlist") || !detail.get("playlist").isJsonObject()) return out;
        JsonObject pl = detail.getAsJsonObject("playlist");
        if (pl.has("tracks") && pl.get("tracks").isJsonArray()
                && pl.getAsJsonArray("tracks").size() > 0) {
            JsonArray arr = pl.getAsJsonArray("tracks");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                out.add(parseSong(el.getAsJsonObject()));
            }
            return out;
        }
        if (!pl.has("trackIds") || !pl.get("trackIds").isJsonArray()) return out;
        StringBuilder ids = new StringBuilder("[");
        int n = 0;
        for (JsonElement el : pl.getAsJsonArray("trackIds")) {
            if (n >= limit) break;
            JsonObject ti = el.getAsJsonObject();
            JsonElement idEl = ti.get("id");
            if (idEl == null || idEl.isJsonNull()) continue;
            if (n > 0) ids.append(',');
            ids.append("{\"id\":").append(idEl.getAsLong()).append('}');
            n++;
        }
        ids.append(']');
        Map<String, Object> songBody = new HashMap<>();
        songBody.put("c", ids.toString());
        JsonObject songResp = weapiJson("v3/song/detail", songBody);
        if (songResp.has("songs") && songResp.get("songs").isJsonArray()) {
            for (JsonElement el : songResp.getAsJsonArray("songs")) {
                if (!el.isJsonObject()) continue;
                out.add(parseSong(el.getAsJsonObject()));
            }
        }
        return out;
    }

    private static NeteaseSong parseSong(JsonObject s) {
        NeteaseSong out = new NeteaseSong();
        if (s.has("id") && !s.get("id").isJsonNull()) out.id = s.get("id").getAsLong();
        if (s.has("name") && !s.get("name").isJsonNull()) out.name = s.get("name").getAsString();
        if (s.has("dt"))   out.durationMs = s.get("dt").getAsLong();
        else if (s.has("duration")) out.durationMs = s.get("duration").getAsLong();
        if (s.has("fee"))  out.fee = s.get("fee").getAsInt() == 1;
        JsonArray ar = s.has("ar") && s.get("ar").isJsonArray()
                ? s.getAsJsonArray("ar")
                : (s.has("artists") && s.get("artists").isJsonArray() ? s.getAsJsonArray("artists") : null);
        if (ar != null) {
            StringBuilder names = new StringBuilder();
            for (JsonElement ae : ar) {
                if (!ae.isJsonObject()) continue;
                JsonObject ao = ae.getAsJsonObject();
                if (!ao.has("name") || ao.get("name").isJsonNull()) continue;
                if (names.length() > 0) names.append(" / ");
                names.append(ao.get("name").getAsString());
            }
            if (names.length() > 0) out.artist = names.toString();
        }
        JsonObject al = s.has("al") && s.get("al").isJsonObject()
                ? s.getAsJsonObject("al")
                : (s.has("album") && s.get("album").isJsonObject() ? s.getAsJsonObject("album") : null);
        if (al != null) {
            if (al.has("name") && !al.get("name").isJsonNull()) out.album = al.get("name").getAsString();
            if (al.has("picUrl") && !al.get("picUrl").isJsonNull()) out.coverUrl = al.get("picUrl").getAsString();
        }
        out.coverThumbPath = thumbUrl(out.coverUrl);
        return out;
    }

    public static String thumbUrl(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) return null;
        return coverUrl + (coverUrl.contains("?") ? "&" : "?") + "param=128y128";
    }

    private static NeteasePlaylist parsePlaylist(JsonObject p) {
        NeteasePlaylist out = new NeteasePlaylist();
        if (p.has("id") && !p.get("id").isJsonNull()) out.id = p.get("id").getAsLong();
        if (p.has("name") && !p.get("name").isJsonNull()) out.name = p.get("name").getAsString();
        if (p.has("picUrl")) out.coverUrl = p.get("picUrl").getAsString();
        else if (p.has("coverImgUrl")) out.coverUrl = p.get("coverImgUrl").getAsString();
        out.coverThumbPath = thumbUrl(out.coverUrl);
        if (p.has("trackCount")) out.trackCount = p.get("trackCount").getAsInt();
        if (p.has("playCount")) out.playCount = p.get("playCount").getAsLong();
        if (p.has("description") && !p.get("description").isJsonNull())
            out.description = p.get("description").getAsString();
        if (p.has("creator") && p.get("creator").isJsonObject()) {
            JsonObject c = p.getAsJsonObject("creator");
            if (c.has("nickname") && !c.get("nickname").isJsonNull())
                out.creatorNickname = c.get("nickname").getAsString();
            if (c.has("userId") && !c.get("userId").isJsonNull())
                out.creatorUid = c.get("userId").getAsLong();
        }
        if (p.has("subscribed") && !p.get("subscribed").isJsonNull())
            out.subscribed = p.get("subscribed").getAsBoolean();
        return out;
    }

    public boolean playlistSubscribe(long playlistId, boolean subscribe) throws IOException {
        String path = subscribe ? "/api/playlist/subscribe" : "/api/playlist/unsubscribe";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", playlistId);
        if (subscribe) body.put("checkToken", CHECK_TOKEN);
        JsonObject obj = eapiJson(path, body, true);
        return obj.has("code") && !obj.get("code").isJsonNull() && obj.get("code").getAsInt() == 200;
    }

    /**
 * 网易云"心动模式"：以 songId 为种子、playlistId 为上下文歌单返回推荐续播列表。
 * 接口有时内嵌完整 songInfo，有时只给 id；两种形状都归一化成完整歌曲并保持服务端顺序。
 */
    public List<NeteaseSong> intelligenceSongs(long songId, long playlistId,
            long startSongId) throws IOException {
        if (!isLoggedIn() || songId == 0L || playlistId == 0L) {
            return Collections.emptyList();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("songId", songId);
        body.put("type", "fromPlayOne");
        body.put("playlistId", playlistId);
        body.put("startMusicId", startSongId == 0L ? songId : startSongId);
        // 与原版一致：count=1 一般也返回一批推荐；若只返回 1 首，
        // 用更大的 count 重试一次，避免心动队列只有一首歌无法切歌。
        body.put("count", 1);
        JsonObject obj = eapiJson("/api/playmode/intelligence/list", body, false);
        if (obj.has("data") && obj.get("data").isJsonArray()
                && obj.getAsJsonArray("data").size() <= 1) {
            body.put("count", 20);
            obj = eapiJson("/api/playmode/intelligence/list", body, false);
        }
        ensureOk(obj, "获取心动推荐失败");

        List<NeteaseSong> embedded = new ArrayList<>();
        List<Long> orderedIds = new ArrayList<>();
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            for (JsonElement element : obj.getAsJsonArray("data")) {
                if (!element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                JsonObject song = row.has("songInfo") && row.get("songInfo").isJsonObject()
                        ? row.getAsJsonObject("songInfo") : null;
                if (song != null) {
                    NeteaseSong parsed = parseSong(song);
                    if (parsed.id != 0L) {
                        embedded.add(parsed);
                        orderedIds.add(parsed.id);
                    }
                } else if (row.has("id") && !row.get("id").isJsonNull()) {
                    orderedIds.add(row.get("id").getAsLong());
                }
            }
        }
        if (orderedIds.isEmpty()) return Collections.emptyList();
        if (embedded.size() == orderedIds.size()) return embedded;

        List<NeteaseSong> details = songDetails(orderedIds);
        Map<Long, NeteaseSong> byId = new HashMap<>();
        for (NeteaseSong song : details) byId.put(song.id, song);
        List<NeteaseSong> ordered = new ArrayList<>();
        for (Long id : orderedIds) {
            NeteaseSong song = byId.get(id);
            if (song != null) ordered.add(song);
        }
        return ordered;
    }

    public NeteaseSong songDetail(long songId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("c", "[{\"id\":" + songId + "}]");
        JsonObject obj = weapiJson("v3/song/detail", body);
        if (!obj.has("songs") || !obj.get("songs").isJsonArray()) return null;
        JsonArray arr = obj.getAsJsonArray("songs");
        if (arr.size() == 0 || !arr.get(0).isJsonObject()) return null;
        return parseSong(arr.get(0).getAsJsonObject());
    }

    public List<NeteaseSong> songDetails(List<Long> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(ids.get(i)).append('}');
        }
        sb.append(']');
        Map<String, Object> body = new HashMap<>();
        body.put("c", sb.toString());
        JsonObject obj = weapiJson("v3/song/detail", body);
        List<NeteaseSong> out = new ArrayList<>();
        if (!obj.has("songs") || !obj.get("songs").isJsonArray()) return out;
        for (JsonElement el : obj.getAsJsonArray("songs")) {
            if (!el.isJsonObject()) continue;
            out.add(parseSong(el.getAsJsonObject()));
        }
        return out;
    }

    public long loginUid() throws IOException {
        if (!isLoggedIn()) return 0L;
        JsonObject obj = weapiJson("w/nuser/account/get", new HashMap<String, Object>());
        if (!obj.has("profile") || obj.get("profile").isJsonNull()) return 0L;
        JsonObject p = obj.getAsJsonObject("profile");
        if (!p.has("userId") || p.get("userId").isJsonNull()) return 0L;
        return p.get("userId").getAsLong();
    }

    public List<NeteasePlaylist> userPlaylists(long uid, int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("uid", uid);
        body.put("limit", limit);
        body.put("offset", 0);
        body.put("includeVideo", true);
        JsonObject obj = weapiJson("user/playlist", body);
        List<NeteasePlaylist> out = new ArrayList<>();
        if (obj.has("playlist") && obj.get("playlist").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("playlist")) {
                if (!el.isJsonObject()) continue;
                NeteasePlaylist pl = parsePlaylist(el.getAsJsonObject());
                JsonObject p = el.getAsJsonObject();
                if (p.has("creator") && p.get("creator").isJsonObject()) {
                    JsonObject c = p.getAsJsonObject("creator");
                    if (c.has("userId") && !c.get("userId").isJsonNull()) {
                        pl.creatorUid = c.get("userId").getAsLong();
                    }
                }
                out.add(pl);
            }
        }
        return out;
    }

    public List<NeteaseSong> userRecord(long uid, int type) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("uid", uid);
        body.put("type", type);
        JsonObject obj = weapiJson("v1/play/record", body);
        List<NeteaseSong> out = new ArrayList<>();
        String key = type == 1 ? "weekData" : "allData";
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray(key)) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                if (!row.has("song") || !row.get("song").isJsonObject()) continue;
                out.add(parseSong(row.getAsJsonObject("song")));
            }
        }
        return out;
    }

    public com.ylmac.adapt.module.misc.music.netease.dto.NeteaseLyric lyric(long songId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("id", songId);
        body.put("lv", 0);
        body.put("kv", 0);
        body.put("tv", 0);
        body.put("rv", 0);
        body.put("yv", 0);
        JsonObject obj = weapiJson("song/lyric/v1", body);
        com.ylmac.adapt.module.misc.music.netease.dto.NeteaseLyric out =
                new com.ylmac.adapt.module.misc.music.netease.dto.NeteaseLyric();
        out.lrc     = extractLyricField(obj, "lrc");
        out.yrc     = extractLyricField(obj, "yrc");
        out.tlyric  = extractLyricField(obj, "tlyric");
        out.romalrc = extractLyricField(obj, "romalrc");
        return out;
    }

    private static String extractLyricField(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) return null;
        JsonObject node = root.getAsJsonObject(key);
        if (!node.has("lyric") || node.get("lyric").isJsonNull()) return null;
        String s = node.get("lyric").getAsString();
        return s.isEmpty() ? null : s;
    }

    public boolean like(long songId, boolean isLike) throws IOException {
        try {
            int xc = xeapiCode("/api/radio/like",
                    "trackId=" + songId + "&like=" + isLike + "&alg=itembased&time=3");
            if (xc == 200) return true;
            Logger.warn("xeapi radio/like non-200 for {} (like={}): code {}", songId, isLike, xc);
        } catch (IOException e) {
            Logger.warn("xeapi radio/like failed for {} (like={}): {}", songId, isLike, e.getMessage());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("trackId", songId);
        body.put("like", isLike);
        body.put("alg", "itembased");
        body.put("time", "3");
        JsonObject obj = weapiJson("radio/like", body, false);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) {
            Logger.warn("netease radio/like failed for {} (like={}): {}",
                    songId, isLike, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    public long favoritePlaylistId(long uid) throws IOException {
        if (uid == 0L) return 0L;
        for (NeteasePlaylist p : userPlaylists(uid, 1000)) {
            if (p.creatorUid == uid) return p.id;
        }
        return 0L;
    }

    public boolean setFavorite(long uid, long songId, boolean add) throws IOException {
        long pid = favoritePlaylistId(uid);
        if (pid == 0L) return false;
        return manipulatePlaylistTracks(pid, songId, add);
    }

    public boolean manipulatePlaylistTracks(long pid, long songId, boolean add) throws IOException {
        JsonObject obj = manipulateTracksOnce(pid, "[" + songId + "]", add);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code == 512) {
            obj = manipulateTracksOnce(pid, "[" + songId + "," + songId + "]", add);
            code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        }
        if (code != 200) {
            Logger.warn("playlist/manipulate/tracks failed pid={} track={} (add={}): {}",
                    pid, songId, add, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    private JsonObject manipulateTracksOnce(long pid, String trackIdsJson, boolean add) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("op", add ? "add" : "del");
        body.put("pid", pid);
        body.put("trackIds", trackIdsJson);
        body.put("imme", "true");
        return weapiJson("playlist/manipulate/tracks", body, false);
    }

    public long createPlaylist(String name, boolean privacy) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("privacy", privacy ? "10" : "0");
        body.put("type", "NORMAL");
        JsonObject obj = weapiJson("playlist/create", body);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) return 0L;
        if (obj.has("id") && !obj.get("id").isJsonNull()) return obj.get("id").getAsLong();
        if (obj.has("playlist") && obj.getAsJsonObject("playlist").has("id")) {
            return obj.getAsJsonObject("playlist").get("id").getAsLong();
        }
        return 0L;
    }

    public boolean deletePlaylist(long playlistId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", "[" + playlistId + "]");
        JsonObject obj = weapiJson("playlist/remove", body);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) {
            Logger.warn("playlist/remove failed id={}: {}", playlistId, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    public long uploadImage(byte[] imageBytes, String filename) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) return 0L;
        Map<String, Object> body = new HashMap<>();
        body.put("bucket", "yyimgs");
        body.put("ext", "jpg");
        body.put("filename", filename == null || filename.isEmpty() ? "cover.jpg" : filename);
        body.put("local", false);
        body.put("nos_product", 0);
        body.put("return_body", "{\"code\":200,\"size\":\"$(ObjectSize)\"}");
        body.put("type", "other");
        JsonObject obj = weapiJson("nos/token/alloc", body);
        if (!obj.has("result") || !obj.get("result").isJsonObject()) {
            Logger.warn("nos/token/alloc failed: {}", truncate(obj.toString(), 300));
            return 0L;
        }
        JsonObject result = obj.getAsJsonObject("result");
        if (!result.has("objectKey") || !result.has("token") || !result.has("docId")) {
            Logger.warn("nos/token/alloc missing fields: {}", truncate(result.toString(), 300));
            return 0L;
        }
        String objectKey = result.get("objectKey").getAsString();
        String token = result.get("token").getAsString();
        long docId = result.get("docId").getAsLong();

        String uploadUrl = "https://nosup-hz1.127.net/yyimgs/" + objectKey
                + "?offset=0&complete=true&version=1.0";
        HttpURLConnection c = (HttpURLConnection) new URL(uploadUrl).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("x-nos-token", token);
            c.setRequestProperty("Content-Type", "image/jpeg");
            try (OutputStream os = c.getOutputStream()) {
                os.write(imageBytes);
            }
            int status = c.getResponseCode();
            if (status / 100 != 2) {
                Logger.warn("NOS image upload failed: HTTP {}", status);
                return 0L;
            }
        } finally {
            c.disconnect();
        }
        return docId;
    }

    public boolean updatePlaylistCover(long playlistId, long coverImgId) throws IOException {
        if (playlistId <= 0 || coverImgId <= 0) return false;
        Map<String, Object> body = new HashMap<>();
        body.put("id", playlistId);
        body.put("coverImgId", coverImgId);
        JsonObject obj = weapiJson("playlist/cover/update", body);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) {
            Logger.warn("playlist/cover/update failed id={}: {}", playlistId, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    public java.util.Set<Long> likedSongIds(long uid) throws IOException {
        if (uid == 0L) return java.util.Collections.emptySet();
        Map<String, Object> body = new HashMap<>();
        body.put("uid", uid);
        JsonObject obj = weapiJson("song/like/get", body);
        java.util.Set<Long> out = new java.util.HashSet<>();
        if (obj.has("ids") && obj.get("ids").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("ids")) {
                if (el.isJsonPrimitive()) out.add(el.getAsLong());
            }
        }
        return out;
    }

    public List<NeteaseSong> recommendSongs() throws IOException {
        if (!isLoggedIn()) return java.util.Collections.emptyList();
        JsonObject obj = weapiJson("v1/discovery/recommend/songs", new HashMap<String, Object>());
        List<NeteaseSong> out = new ArrayList<>();
        JsonArray arr = null;
        if (obj.has("data") && obj.get("data").isJsonObject()) {
            JsonObject data = obj.getAsJsonObject("data");
            if (data.has("dailySongs") && data.get("dailySongs").isJsonArray()) {
                arr = data.getAsJsonArray("dailySongs");
            }
        }
        if (arr == null && obj.has("recommend") && obj.get("recommend").isJsonArray()) {
            arr = obj.getAsJsonArray("recommend");
        }
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonObject()) out.add(parseSong(el.getAsJsonObject()));
            }
        }
        return out;
    }

    public com.ylmac.adapt.module.misc.music.netease.dto.NeteaseUser userDetail(long uid) throws IOException {
        if (uid == 0L) return null;
        Map<String, Object> body = new HashMap<>();
        JsonObject obj = weapiJson("v1/user/detail/" + uid, body);
        if (!obj.has("profile") || !obj.get("profile").isJsonObject()) return null;
        JsonObject p = obj.getAsJsonObject("profile");
        com.ylmac.adapt.module.misc.music.netease.dto.NeteaseUser out =
                new com.ylmac.adapt.module.misc.music.netease.dto.NeteaseUser();
        out.uid = uid;
        if (p.has("nickname") && !p.get("nickname").isJsonNull())
            out.nickname = p.get("nickname").getAsString();
        if (p.has("avatarUrl") && !p.get("avatarUrl").isJsonNull())
            out.avatarUrl = p.get("avatarUrl").getAsString();
        if (p.has("vipType")) out.vipType = p.get("vipType").getAsInt();
        if (p.has("signature") && !p.get("signature").isJsonNull())
            out.signature = p.get("signature").getAsString();
        if (obj.has("level")) out.level = obj.get("level").getAsInt();
        return out;
    }

    public void logout() {
        try {
            weapiCall("logout", new HashMap<String, Object>());
        } catch (Throwable e) {
        }
        clearCookies();
    }

    public String qrLoginKey() throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("type", 1);
        JsonObject obj = weapiJson("login/qrcode/unikey", body);
        if (!obj.has("unikey") || obj.get("unikey").isJsonNull()) {
            throw new IOException("qrLoginKey: no unikey in response: " + obj);
        }
        return obj.get("unikey").getAsString();
    }

    public String qrLoginContent(String unikey) {
        return "https://music.163.com/login?codekey=" + unikey;
    }

    public boolean[][] qrMatrix(String unikey) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix bits = new QRCodeWriter().encode(
                    qrLoginContent(unikey), BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = bits.getWidth();
            int h = bits.getHeight();
            boolean[][] out = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[y][x] = bits.get(x, y);
                }
            }
            return out;
        } catch (Throwable e) {
            Logger.warn("qrMatrix failed: {}", e.getMessage());
            return null;
        }
    }

    public int qrLoginCheck(String unikey) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("key", unikey);
        body.put("type", 1);
        JsonObject obj = weapiJson("login/qrcode/client/login", body);
        if (!obj.has("code")) {
            throw new IOException("qrLoginCheck: no code in response: " + obj);
        }
        return obj.get("code").getAsInt();
    }

    public boolean hasCookie(String name) {
        return cookies.containsKey(name);
    }

    public boolean isLoggedIn() {
        return cookies.containsKey("MUSIC_U");
    }

    public void clearCookies() {
        cookies.clear();
        try {
            if (Files.exists(cookieFile)) Files.delete(cookieFile);
        } catch (IOException ignored) {}
    }

    private String cookieHeader() {
        ensureDeviceCookies();
        Map<String, String> all = new LinkedHashMap<>(cookies);
        all.put("__remember_me", "true");
        all.put("ntes_kaola_ad", "1");
        all.put("_ntes_nnid", all.getOrDefault("_ntes_nuid", "") + "," + System.currentTimeMillis());
        all.put("WNMCID", wnmcid());
        all.put("WEVNSM", "1.0.0");
        all.put("NMTID", randomHex(16));
        all.put("os", "pc");
        all.put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit");
        all.put("appver", "3.1.17.204416");
        all.put("channel", "netease");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void ensureDeviceCookies() {
        boolean changed = false;
        if (!cookies.containsKey("_ntes_nuid")) { cookies.put("_ntes_nuid", randomHex(32)); changed = true; }
        if (!cookies.containsKey("deviceId")) { cookies.put("deviceId", randomHex(16)); changed = true; }
        if (changed) saveCookies();
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(b);
        StringBuilder s = new StringBuilder(bytes * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xf, 16));
            s.append(Character.forDigit(x & 0xf, 16));
        }
        return s.toString();
    }

    private static String wnmcid() {
        StringBuilder s = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) s.append((char) ('a' + r.nextInt(26)));
        return s.append('.').append(System.currentTimeMillis()).append(".01.0").toString();
    }

    private static String randomChinaIp() {
        int[] firsts = {36, 39, 42, 58, 59, 60, 101, 106, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 175, 180, 182, 183, 202, 203, 210, 211, 218, 219, 220, 221, 222, 223};
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        int a = firsts[r.nextInt(firsts.length)];
        return a + "." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    private void captureSetCookies(List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return;
        boolean changed = false;
        for (String sc : setCookies) {
            int eq = sc.indexOf('=');
            int semi = sc.indexOf(';');
            if (eq <= 0) continue;
            String name = sc.substring(0, eq).trim();
            String val = sc.substring(eq + 1, semi > eq ? semi : sc.length()).trim();
            if (name.equalsIgnoreCase("domain") || name.equalsIgnoreCase("path")
                    || name.equalsIgnoreCase("expires") || name.equalsIgnoreCase("max-age")
                    || name.equalsIgnoreCase("samesite")) continue;
            String prev = cookies.put(name, val);
            if (prev == null || !prev.equals(val)) changed = true;
        }
        if (changed) saveCookies();
    }

    private void loadCookies() {
        if (!Files.exists(cookieFile)) return;
        try {
            String txt = new String(Files.readAllBytes(cookieFile), StandardCharsets.UTF_8);
            if (txt == null || txt.trim().isEmpty()) return;
            JsonElement el = parseJsonLenient(txt);
            if (!el.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    cookies.put(e.getKey(), e.getValue().getAsString());
                }
            }
            Logger.info("Netease: loaded {} cookies from disk", cookies.size());
        } catch (Exception e) {
            Logger.warn("Netease: cookie load failed: {}", e.getMessage());
        }
    }

    private void saveCookies() {
        try {
            new File(AppDirs.base()).mkdirs();
            Map<String, Object> out = new LinkedHashMap<>(cookies);
            Files.write(cookieFile, gson.toJson(out).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.warn("Netease: cookie save failed: {}", e.getMessage());
        }
    }

    private static String formEncode(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEnc(e.getKey())).append('=').append(urlEnc(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEnc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        return new String(readAllBytes(is), StandardCharsets.UTF_8);
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        if (is == null) return new byte[0];
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ==================== 一起听 ====================

    // 路径必须用完整 /api/listen/together/... 前缀，否则网易云返回"接口不存在"
    public TogetherRoom createTogetherRoom() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refer", "songplay_more");
        JsonObject obj = eapiJson("/api/listen/together/room/create", body, true);
        ensureOk(obj, "创建一起听房间失败");
        TogetherRoom room = parseTogetherRoomFromResponse(obj);
        if (room == null || room.roomId.isEmpty()) throw new IOException("创建房间未返回 roomId");
        return room;
    }

    public TogetherStatus togetherStatus() throws IOException {
        JsonObject obj = weapiJson("listen/together/status/get", new LinkedHashMap<String, Object>());
        ensureOk(obj, "获取一起听状态失败");
        TogetherStatus out = new TogetherStatus();
        JsonObject data = object(obj, "data");
        if (data == null) return out;
        out.inRoom = bool(data, "inRoom", false);
        out.status = string(data, "status");
        out.room = parseTogetherRoom(object(data, "roomInfo"));
        return out;
    }

    public boolean togetherRoomJoinable(String roomId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        JsonObject obj = eapiJson("/api/listen/together/room/check", body, true);
        ensureOk(obj, "检查一起听房间失败");
        JsonObject data = object(obj, "data");
        return data != null && bool(data, "joinable", false);
    }

    public TogetherRoom acceptTogetherInvitation(String roomId, long inviterId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refer", "inbox_invite");
        body.put("roomId", roomId);
        body.put("inviterId", inviterId);
        JsonObject obj = eapiJson("/api/listen/together/play/invitation/accept", body, true);
        ensureOk(obj, "加入一起听房间失败");
        TogetherRoom room = parseTogetherRoomFromResponse(obj);
        if (room == null) {
            room = new TogetherRoom();
            room.roomId = roomId;
        }
        return room;
    }

    public TogetherSnapshot togetherSnapshot(String roomId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        JsonObject obj = eapiJson("/api/listen/together/sync/playlist/get", body, true);
        ensureOk(obj, "同步一起听状态失败");
        TogetherSnapshot out = new TogetherSnapshot();
        JsonObject data = object(obj, "data");
        if (data == null) return out;
        JsonObject playlist = object(data, "playlist");
        if (playlist != null) {
            String mode = string(playlist, "playMode");
            boolean random = mode.toUpperCase(java.util.Locale.ROOT).contains("RANDOM")
                    || mode.toUpperCase(java.util.Locale.ROOT).contains("SHUFFLE");
            JsonObject list = object(playlist, random ? "randomList" : "displayList");
            if (list == null) list = object(playlist, "displayList");
            JsonArray result = list == null ? null : array(list, "result");
            if (result != null) {
                for (JsonElement element : result) {
                    try { out.songIds.add(element.getAsLong()); } catch (Throwable ignored) {}
                }
            }
            JsonArray versions = array(playlist, "version");
            if (versions != null) {
                for (JsonElement element : versions) {
                    if (!element.isJsonObject()) continue;
                    JsonObject version = element.getAsJsonObject();
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("userId", number(version, "userId", 0L));
                    value.put("version", number(version, "version", 0L));
                    out.versions.add(value);
                }
            }
        }
        JsonObject command = object(data, "playCommand");
        if (command == null) command = object(data, "commandInfo");
        if (command != null) {
            TogetherCommand parsed = new TogetherCommand();
            parsed.userId = number(command, "userId", 0L);
            parsed.commandType = string(command, "commandType");
            parsed.formerSongId = number(command, "formerSongId", 0L);
            parsed.targetSongId = number(command, "targetSongId", 0L);
            parsed.progressMs = number(command, "progress", 0L);
            parsed.playStatus = string(command, "playStatus");
            parsed.serverSeq = number(command, "serverSeq", 0L);
            parsed.clientSeq = number(command, "clientSeq", 0L);
            out.command = parsed;
        }
        return out;
    }

    public void reportTogetherPlaylist(String roomId, long userId, long version,
            List<Long> songIds) throws IOException {
        List<String> ids = new ArrayList<>();
        if (songIds != null) for (Long id : songIds) if (id != null && id != 0L) ids.add(String.valueOf(id));
        Map<String, Object> versionRow = new LinkedHashMap<>();
        versionRow.put("userId", userId);
        versionRow.put("version", version);
        Map<String, Object> playlist = new LinkedHashMap<>();
        playlist.put("commandType", "REPLACE");
        playlist.put("version", Collections.singletonList(versionRow));
        playlist.put("anchorSongId", "");
        playlist.put("anchorPosition", -1);
        playlist.put("randomList", ids);
        playlist.put("displayList", ids);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("playlistParam", gson.toJson(playlist));
        ensureOk(eapiJson("/api/listen/together/sync/list/command/report", body, true),
                "上报一起听队列失败");
    }

    public void reportTogetherCommand(String roomId, String commandType, long progressMs,
            boolean playing, long formerSongId, long targetSongId, long clientSeq)
            throws IOException {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("commandType", commandType);
        command.put("progress", Math.max(0L, progressMs));
        command.put("playStatus", playing ? "PLAY" : "PAUSE");
        command.put("formerSongId", String.valueOf(formerSongId));
        command.put("targetSongId", String.valueOf(targetSongId));
        command.put("clientSeq", clientSeq);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("commandInfo", gson.toJson(command));
        ensureOk(eapiJson("/api/listen/together/play/command/report", body, true),
                "上报一起听播放状态失败");
    }

    public void togetherHeartbeat(String roomId, long songId, boolean playing,
            long progressMs) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("songId", songId);
        body.put("playStatus", playing ? "PLAY" : "PAUSE");
        body.put("progress", Math.max(0L, progressMs));
        ensureOk(eapiJson("/api/listen/together/heartbeat", body, true), "一起听心跳失败");
    }

    public void endTogetherRoom(String roomId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        ensureOk(eapiJson("/api/listen/together/end/v2", body, true), "退出一起听失败");
    }

    private TogetherRoom parseTogetherRoomFromResponse(JsonObject response) {
        JsonObject data = object(response, "data");
        if (data == null) return null;
        JsonObject roomInfo = object(data, "roomInfo");
        return parseTogetherRoom(roomInfo != null ? roomInfo : data);
    }

    private static TogetherRoom parseTogetherRoom(JsonObject room) {
        if (room == null) return null;
        TogetherRoom out = new TogetherRoom();
        out.roomId = string(room, "roomId");
        out.creatorId = number(room, "creatorId", 0L);
        out.effectiveDurationMs = number(room, "effectiveDurationMs", 0L);
        out.status = string(room, "status");
        JsonArray users = array(room, "roomUsers");
        if (users == null) users = array(room, "users");
        if (users != null) {
            for (JsonElement element : users) {
                if (!element.isJsonObject()) continue;
                JsonObject value = element.getAsJsonObject();
                TogetherUser user = new TogetherUser();
                user.userId = number(value, "userId", 0L);
                user.nickname = string(value, "nickname");
                user.avatarUrl = string(value, "avatarUrl");
                user.status = string(value, "status");
                out.users.add(user);
            }
        }
        return out;
    }

    // ---------- JSON helpers ----------

    private static void ensureOk(JsonObject obj, String action) throws IOException {
        int code = obj.has("code") && !obj.get("code").isJsonNull()
                ? obj.get("code").getAsInt() : 200;
        if (code != 200) throw new IOException(action + ": " + neteaseMessage(obj) + " (code " + code + ")");
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return null;
        return parent.getAsJsonObject(key);
    }

    private static String string(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return "";
        try { return parent.get(key).getAsString(); } catch (Throwable ignored) { return ""; }
    }

    private static boolean bool(JsonObject parent, String key, boolean def) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return def;
        try { return parent.get(key).getAsBoolean(); } catch (Throwable ignored) { return def; }
    }

    private static long number(JsonObject parent, String key, long def) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return def;
        try { return parent.get(key).getAsLong(); } catch (Throwable ignored) { return def; }
    }

    private static JsonArray array(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) return null;
        return parent.getAsJsonArray(key);
    }
}
