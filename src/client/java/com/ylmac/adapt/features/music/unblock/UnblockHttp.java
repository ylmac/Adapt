package com.ylmac.adapt.features.music.unblock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class UnblockHttp {

    private UnblockHttp() {}

    static String get(String url, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return null;
            String body = readAll(is);
            return (code >= 400) ? null : body;
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String post(String url, String body, String contentType, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return null;
            String resp = readAll(is);
            return (code >= 400) ? null : resp;
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
