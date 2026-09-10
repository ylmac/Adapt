package com.ylmac.adapt.features.music.lyric;

import com.ylmac.adapt.features.music.util.Logger;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class LyricParser {

    private LyricParser() {}

    public static List<LyricLine> parse(String mainPath,
                                        String translationPath,
                                        String romajiPath) {
        List<LyricLine> lines = parseFile(mainPath);
        if (lines.isEmpty()) return lines;
        if (translationPath != null) attachSidecar(lines, translationPath, true);
        if (romajiPath != null) attachSidecar(lines, romajiPath, false);
        return lines;
    }

    /** 解析原始歌词文本（嵌入式/内存中的 lrc），并用 sidecar 文件挂载翻译/罗马音 */
    public static List<LyricLine> fromString(String content,
                                             String translationPath,
                                             String romajiPath) {
        if (content == null || content.isEmpty()) return Collections.emptyList();
        List<LyricLine> lines = LrcParser.parse(content);
        if (lines.isEmpty()) return lines;
        if (translationPath != null) attachSidecar(lines, translationPath, true);
        if (romajiPath != null) attachSidecar(lines, romajiPath, false);
        return lines;
    }

    public static List<LyricLine> fromNeteaseStrings(String yrc, String lrc,
                                                       String tlyric, String romalrc) {
        List<LyricLine> base;
        if (yrc != null && !yrc.isEmpty()) {
            base = YrcParser.parse(yrc);
        } else if (lrc != null && !lrc.isEmpty()) {
            base = LrcParser.parse(lrc);
        } else {
            return Collections.emptyList();
        }
        if (base.isEmpty()) return base;
        if (tlyric != null && !tlyric.isEmpty()) attachSidecarContent(base, tlyric, true);
        if (romalrc != null && !romalrc.isEmpty()) attachSidecarContent(base, romalrc, false);
        return base;
    }

    private static void attachSidecarContent(List<LyricLine> mainLines, String content, boolean isTranslation) {
        List<LyricLine> sidecar = LrcParser.parse(content);
        if (sidecar.isEmpty()) return;
        int j = 0;
        for (LyricLine s : sidecar) {
            long start = s.startMs();
            while (j + 1 < mainLines.size()
                    && Math.abs(mainLines.get(j + 1).startMs() - start)
                    < Math.abs(mainLines.get(j).startMs() - start)) {
                j++;
            }
            String text = s.text();
            if (isTranslation) mainLines.get(j).translation = text;
            else mainLines.get(j).romaji = text;
        }
    }

    public static List<LyricLine> parseFile(String path) {
        if (path == null) return Collections.emptyList();
        String content = readWithEncodingFallback(path);
        if (content == null) return Collections.emptyList();
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ttml")) return TtmlParser.parse(content);
        if (lower.endsWith(".lys")) return LysParser.parse(content);
        if (lower.endsWith(".qrc")) return QrcParser.parse(content);
        if (lower.endsWith(".yrc")) return YrcParser.parse(content);
        if (lower.endsWith(".eslrc")) return EsLrcParser.parse(content);
        return LrcParser.parse(content);
    }

    private static void attachSidecar(List<LyricLine> mainLines, String path, boolean isTranslation) {
        List<LyricLine> sidecar = parseFile(path);
        if (sidecar.isEmpty()) return;
        int j = 0;
        for (LyricLine s : sidecar) {
            long start = s.startMs();
            while (j + 1 < mainLines.size()
                    && Math.abs(mainLines.get(j + 1).startMs() - start)
                    < Math.abs(mainLines.get(j).startMs() - start)) {
                j++;
            }
            String text = s.text();
            if (isTranslation) mainLines.get(j).translation = text;
            else mainLines.get(j).romaji = text;
        }
    }

    private static String readWithEncodingFallback(String path) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            String utf8 = new String(bytes, StandardCharsets.UTF_8);
            if (utf8.indexOf('\uFFFD') < 0) return utf8;
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception e) {
                return utf8;
            }
        } catch (IOException e) {
            Logger.warn("LyricParser: failed to read {}: {}", path, e.getMessage());
            return null;
        }
    }
}
