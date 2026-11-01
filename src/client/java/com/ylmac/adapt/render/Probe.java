package com.ylmac.adapt.render;

import com.ylmac.adapt.AdaptClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渲染线程上的分段计时。每秒打一行 {@code [Probe]}，不逐帧刷日志。
 * begin/end 配对；hit 只计数。
 */
public final class Probe {
    private static final class Acc {
        long ns;
        int calls;
        int hits;
    }

    private static final Map<String, Acc> ACC = new LinkedHashMap<>();
    private static final Map<String, Long> OPEN = new LinkedHashMap<>();
    private static long windowNs = System.nanoTime();

    private Probe() {}

    public static void begin(String name) {
        OPEN.put(name, System.nanoTime());
    }

    public static void end(String name) {
        Long t = OPEN.remove(name);
        if (t == null) return;
        Acc a = ACC.computeIfAbsent(name, k -> new Acc());
        a.ns += System.nanoTime() - t;
        a.calls++;
        maybeLog();
    }

    public static void hit(String name) {
        ACC.computeIfAbsent(name, k -> new Acc()).hits++;
        maybeLog();
    }

    private static void maybeLog() {
        long now = System.nanoTime();
        if (now - windowNs < 1_000_000_000L) return;
        long elapsed = now - windowNs;
        windowNs = now;
        if (ACC.isEmpty()) return;
        List<Map.Entry<String, Acc>> rows = new ArrayList<>(ACC.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, Acc> e) -> e.getValue().ns).reversed());
        StringBuilder sb = new StringBuilder(256);
        sb.append("[Probe] ");
        boolean any = false;
        for (Map.Entry<String, Acc> e : rows) {
            Acc a = e.getValue();
            if (a.ns == 0 && a.hits == 0) continue;
            if (any) sb.append(' ');
            any = true;
            sb.append(e.getKey()).append('=');
            if (a.calls > 0) {
                sb.append(String.format("%.2fms", a.ns / 1_000_000.0));
                sb.append('x').append(a.calls);
                if (elapsed > 0) {
                    sb.append('/').append(String.format("%.1fms", a.ns / (double) elapsed * 1000.0));
                }
            }
            if (a.hits > 0) {
                if (a.calls > 0) sb.append('+');
                sb.append("n").append(a.hits);
            }
        }
        ACC.clear();
        OPEN.clear();
        if (any) AdaptClient.LOGGER.info(sb.toString());
    }
}
