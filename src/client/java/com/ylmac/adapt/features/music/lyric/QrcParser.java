package com.ylmac.adapt.features.music.lyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QrcParser {

    private static final Pattern LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)]");
    private static final Pattern SYLLABLE = Pattern.compile("([^()]*?)\\((\\d+),(\\d+)\\)");

    private QrcParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher header = LINE_HEADER.matcher(line);
            if (!header.find()) continue;

            LyricLine ll = new LyricLine();
            Matcher m = SYLLABLE.matcher(line);
            m.region(header.end(), line.length());
            while (m.find()) {
                String text = m.group(1);
                long start = Long.parseLong(m.group(2));
                long dur = Long.parseLong(m.group(3));
                if (text == null) text = "";
                if (start == 0L && dur == 0L && !ll.syllables.isEmpty()) {
                    Syllable prev = ll.syllables.get(ll.syllables.size() - 1);
                    ll.syllables.add(new Syllable(text, prev.startMs, 0L));
                    continue;
                }
                ll.syllables.add(new Syllable(text, start, dur));
            }
            if (!ll.syllables.isEmpty()) out.add(ll);
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }
}
