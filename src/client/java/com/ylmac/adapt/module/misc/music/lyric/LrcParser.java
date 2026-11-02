package com.ylmac.adapt.module.misc.music.lyric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LrcParser {

    private static final Pattern LINE_TS = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");
    private static final Pattern INLINE_TS = Pattern.compile("<(\\d+):(\\d+)(?:[.:](\\d+))?>");

    private LrcParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = LINE_TS.matcher(line);
            List<Long> stamps = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                if (m.start() != lastEnd) break;
                stamps.add(parseTime(m.group(1), m.group(2), m.group(3)));
                lastEnd = m.end();
            }
            if (stamps.isEmpty()) continue;
            String body = line.substring(lastEnd);

            for (long start : stamps) {
                LyricLine ll = new LyricLine();
                if (body.indexOf('<') >= 0) {
                    parseEnhancedBody(ll, body, start);
                } else {
                    ll.syllables.add(new Syllable(body, start, 0L));
                }
                out.add(ll);
            }
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        for (int i = 0; i < out.size(); i++) {
            LyricLine ll = out.get(i);
            long start = ll.startMs();
            long next = i + 1 < out.size() ? out.get(i + 1).startMs() : start + 5000L;
            long gap = Math.min(8000L, Math.max(500L, next - start));
            for (int j = 0; j < ll.syllables.size(); j++) {
                Syllable s = ll.syllables.get(j);
                if (s.durationMs == 0L) {
                    ll.syllables.set(j, new Syllable(s.text, s.startMs, gap));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static void parseEnhancedBody(LyricLine out, String body, long lineStart) {
        Matcher m = INLINE_TS.matcher(body);
        long cursorMs = lineStart;
        int cursorIdx = 0;
        boolean opened = false;
        String pendingText = null;
        while (m.find()) {
            String segment = body.substring(cursorIdx, m.start());
            long tagMs = parseTime(m.group(1), m.group(2), m.group(3));
            if (opened && pendingText != null) {
                long dur = Math.max(0L, tagMs - cursorMs);
                out.syllables.add(new Syllable(pendingText, cursorMs, dur));
            } else if (!opened && !segment.isEmpty()) {
                long dur = Math.max(0L, tagMs - cursorMs);
                out.syllables.add(new Syllable(segment, cursorMs, dur));
            }
            cursorMs = tagMs;
            cursorIdx = m.end();
            pendingText = null;
            opened = true;
            int nextSearchFrom = cursorIdx;
            int nextTag = findNextTagStart(body, nextSearchFrom);
            String run = nextTag < 0 ? body.substring(nextSearchFrom) : body.substring(nextSearchFrom, nextTag);
            if (!run.isEmpty()) pendingText = run;
        }
        if (opened && pendingText != null) {
            out.syllables.add(new Syllable(pendingText, cursorMs, 0L));
        }
        if (out.syllables.isEmpty()) {
            out.syllables.add(new Syllable(body.replaceAll("<[^>]*>", ""), lineStart, 0L));
        }
    }

    private static int findNextTagStart(String s, int from) {
        return s.indexOf('<', from);
    }

    private static long parseTime(String minStr, String secStr, String fracStr) {
        try {
            long min = Long.parseLong(minStr);
            long sec = Long.parseLong(secStr);
            long frac = 0L;
            if (fracStr != null) {
                if (fracStr.length() == 2) frac = Long.parseLong(fracStr) * 10L;
                else if (fracStr.length() == 3) frac = Long.parseLong(fracStr);
                else frac = Long.parseLong(fracStr.substring(0, Math.min(3, fracStr.length())));
            }
            return min * 60_000L + sec * 1000L + frac;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
