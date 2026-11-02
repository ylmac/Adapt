package com.ylmac.adapt.module.misc.music.unblock;

import com.ylmac.adapt.module.misc.music.util.Logger;

import java.util.Arrays;
import java.util.List;

public final class SongUnblocker {

    public enum Source { NETEASE, BODIAN, KUWO }

    private SongUnblocker() {}

    private static volatile List<Source> order =
            Arrays.asList(Source.NETEASE, Source.BODIAN, Source.KUWO);

    public static void setSources(List<Source> sources) {
        if (sources != null) order = sources;
    }

    public static String resolve(long songId, String name, String artist) {
        String keyword = buildKeyword(name, artist);
        for (Source s : order) {
            String url = null;
            try {
                switch (s) {
                    case NETEASE: url = NeteaseSource.resolve(songId); break;
                    case KUWO:    url = KuwoSource.resolve(keyword, name, artist); break;
                    case BODIAN:  url = BodianSource.resolve(keyword, name, artist); break;
                    default: break;
                }
            } catch (RuntimeException e) {
                Logger.warn("unblock[{}] threw: {}", s, e.getMessage());
            }
            if (url != null && !url.isEmpty()) {
                Logger.info("unblock: resolved {} via {}", songId, s);
                return url;
            }
        }
        return null;
    }

    private static String buildKeyword(String name, String artist) {
        String n = name == null ? "" : name.trim();
        String a = artist == null ? "" : artist.trim();
        if (a.isEmpty()) return n;
        return n + "-" + a;
    }
}
