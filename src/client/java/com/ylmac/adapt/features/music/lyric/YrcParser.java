package com.ylmac.adapt.features.music.lyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YrcParser {

    private static final Pattern LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)]");
    private static final Pattern SYLLABLE = Pattern.compile("\\((\\d+),(\\d+),(\\d+)\\)([^(]*)");

    private YrcParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher header = LINE_HEADER.matcher(line);
            if (!header.find()) continue;

            LyricLine main = new LyricLine();
            LyricLine bg = new LyricLine();
            bg.vocalChannel = LyricLine.VocalChannel.BACKGROUND;
            boolean inBg = false;

            Matcher m = SYLLABLE.matcher(line);
            m.region(header.end(), line.length());
            while (m.find()) {
                long start = Long.parseLong(m.group(1));
                long dur = Long.parseLong(m.group(2));
                String text = m.group(4);
                if (text == null) text = "";

                boolean hasOpen = text.indexOf('\uFF08') >= 0;
                boolean hasClose = text.indexOf('\uFF09') >= 0;
                String stripped = text.replace("\uFF08", "").replace("\uFF09", "");
                boolean toBg = inBg || hasOpen;
                if (hasOpen) inBg = true;
                if (!stripped.isEmpty()) {
                    (toBg ? bg : main).syllables.add(new Syllable(stripped, start, dur));
                }
                if (hasClose) inBg = false;
            }
            if (!main.syllables.isEmpty()) out.add(main);
            if (!bg.syllables.isEmpty()) out.add(bg);
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }
}
