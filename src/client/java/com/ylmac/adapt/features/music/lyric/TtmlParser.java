package com.ylmac.adapt.features.music.lyric;

import com.ylmac.adapt.features.music.util.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TtmlParser {

    private TtmlParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

            Map<String, LyricLine.VocalChannel> agents = readAgents(doc);
            Map<String, String> romajiByLineKey = readTransliterations(doc);

            NodeList pList = doc.getElementsByTagName("p");
            for (int i = 0; i < pList.getLength(); i++) {
                Element p = (Element) pList.item(i);
                LyricLine ll = new LyricLine();
                String agent = attr(p, "ttm:agent");
                if (agent == null) agent = attr(p, "agent");
                ll.vocalChannel = agents.getOrDefault(agent, LyricLine.VocalChannel.MAIN);

                String lineKey = attr(p, "itunes:key");
                if (lineKey == null) lineKey = attr(p, "key");
                if (lineKey != null) {
                    String r = romajiByLineKey.get(lineKey);
                    if (r != null && !r.isEmpty()) ll.romaji = r;
                }

                long lineStart = parseClock(attr(p, "begin"));
                long lineEnd = parseClock(attr(p, "end"));

                List<LyricLine> bgLines = new ArrayList<>();

                NodeList kids = p.getChildNodes();
                for (int j = 0; j < kids.getLength(); j++) {
                    Node kid = kids.item(j);
                    if (kid.getNodeType() == Node.ELEMENT_NODE
                            && kid.getNodeName().toLowerCase(Locale.ROOT).endsWith("span")) {
                        Element span = (Element) kid;
                        String role = attr(span, "ttm:role");
                        if (role == null) role = attr(span, "role");
                        String roleLc = role == null ? "" : role.toLowerCase(Locale.ROOT);

                        if (roleLc.startsWith("x-translation")) {
                            String t = span.getTextContent();
                            if (t != null && !t.trim().isEmpty()) ll.translation = t.trim();
                            continue;
                        }
                        if (roleLc.startsWith("x-roman")) {
                            String r = span.getTextContent();
                            if (r != null && !r.trim().isEmpty()) ll.romaji = r.trim();
                            continue;
                        }
                        if (roleLc.equals("x-bg")) {
                            LyricLine bg = parseInlineBgSpan(span, ll.vocalChannel);
                            if (bg != null) bgLines.add(bg);
                            continue;
                        }
                        long s = parseClock(attr(span, "begin"));
                        long e = parseClock(attr(span, "end"));
                        String text = span.getTextContent();
                        if (text == null) text = "";
                        ll.syllables.add(new Syllable(text, s, Math.max(0L, e - s)));
                    } else if (kid.getNodeType() == Node.TEXT_NODE) {
                        String t = kid.getNodeValue();
                        if (t == null || t.isEmpty()) continue;
                        if (t.trim().isEmpty()) {
                            appendSpaceToLast(ll.syllables);
                        } else {
                            long cursor = ll.syllables.isEmpty()
                                    ? lineStart
                                    : ll.syllables.get(ll.syllables.size() - 1).endMs();
                            ll.syllables.add(new Syllable(t, cursor, 0L));
                        }
                    }
                }

                if (ll.syllables.isEmpty()) {
                    String text = p.getTextContent();
                    if (text != null && !text.trim().isEmpty()) {
                        ll.syllables.add(new Syllable(text, lineStart, Math.max(0L, lineEnd - lineStart)));
                    }
                }
                if (!ll.syllables.isEmpty()) out.add(ll);
                for (LyricLine bg : bgLines) out.add(bg);
            }
        } catch (Throwable e) {
            Logger.exception(e);
        }
        return out;
    }

    private static LyricLine parseInlineBgSpan(Element bgSpan, LyricLine.VocalChannel parentChannel) {
        LyricLine bg = new LyricLine();
        if (parentChannel == LyricLine.VocalChannel.DUET_RIGHT) {
            bg.vocalChannel = LyricLine.VocalChannel.BACKGROUND_RIGHT;
        } else if (parentChannel == LyricLine.VocalChannel.DUET_LEFT) {
            bg.vocalChannel = LyricLine.VocalChannel.BACKGROUND_LEFT;
        } else {
            bg.vocalChannel = LyricLine.VocalChannel.BACKGROUND;
        }

        NodeList inner = bgSpan.getChildNodes();
        for (int k = 0; k < inner.getLength(); k++) {
            Node innerNode = inner.item(k);
            if (innerNode.getNodeType() == Node.ELEMENT_NODE
                    && innerNode.getNodeName().toLowerCase(Locale.ROOT).endsWith("span")) {
                Element span = (Element) innerNode;
                String role = attr(span, "ttm:role");
                if (role == null) role = attr(span, "role");
                String roleLc = role == null ? "" : role.toLowerCase(Locale.ROOT);
                if (roleLc.startsWith("x-translation")) {
                    String t = span.getTextContent();
                    if (t != null && !t.trim().isEmpty()) bg.translation = t.trim();
                    continue;
                }
                if (roleLc.startsWith("x-roman")) {
                    String r = span.getTextContent();
                    if (r != null && !r.trim().isEmpty()) bg.romaji = r.trim();
                    continue;
                }
                long s = parseClock(attr(span, "begin"));
                long e = parseClock(attr(span, "end"));
                String text = span.getTextContent();
                if (text == null) text = "";
                bg.syllables.add(new Syllable(text, s, Math.max(0L, e - s)));
            } else if (innerNode.getNodeType() == Node.TEXT_NODE) {
                String t = innerNode.getNodeValue();
                if (t == null || t.isEmpty()) continue;
                if (t.trim().isEmpty()) {
                    appendSpaceToLast(bg.syllables);
                } else if (!bg.syllables.isEmpty()) {
                    long cursor = bg.syllables.get(bg.syllables.size() - 1).endMs();
                    bg.syllables.add(new Syllable(t, cursor, 0L));
                }
            }
        }
        return bg.syllables.isEmpty() ? null : bg;
    }

    private static Map<String, LyricLine.VocalChannel> readAgents(Document doc) {
        Map<String, LyricLine.VocalChannel> map = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            String name = n.getNodeName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(":agent") && !"agent".equals(name)) continue;
            Element el = (Element) n;
            String id = attr(el, "xml:id");
            if (id == null) id = attr(el, "id");
            if (id == null) continue;
            ids.add(id);
        }
        if (ids.size() <= 1) {
            for (String id : ids) map.put(id, LyricLine.VocalChannel.MAIN);
        } else {
            for (int i = 0; i < ids.size(); i++) {
                LyricLine.VocalChannel ch;
                if (i == 0) ch = LyricLine.VocalChannel.DUET_LEFT;
                else if (i == 1) ch = LyricLine.VocalChannel.DUET_RIGHT;
                else ch = LyricLine.VocalChannel.MAIN;
                map.put(ids.get(i), ch);
            }
        }
        return map;
    }

    private static Map<String, String> readTransliterations(Document doc) {
        Map<String, String> out = new HashMap<>();
        NodeList list = doc.getElementsByTagName("text");
        for (int i = 0; i < list.getLength(); i++) {
            Element el = (Element) list.item(i);
            String forKey = attr(el, "for");
            if (forKey == null) continue;
            String text = el.getTextContent();
            if (text == null) continue;
            text = text.trim();
            if (!text.isEmpty()) out.put(forKey, text);
        }
        return out;
    }

    private static void appendSpaceToLast(List<Syllable> syllables) {
        if (syllables.isEmpty()) return;
        Syllable last = syllables.get(syllables.size() - 1);
        if (last.text.endsWith(" ")) return;
        syllables.set(syllables.size() - 1,
                new Syllable(last.text + " ", last.startMs, last.durationMs));
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return v.isEmpty() ? null : v;
    }

    static long parseClock(String s) {
        if (s == null || s.isEmpty()) return 0L;
        String[] parts = s.split(":");
        try {
            double secondsPart = Double.parseDouble(parts[parts.length - 1]);
            long ms = Math.round(secondsPart * 1000.0);
            if (parts.length >= 2) ms += Long.parseLong(parts[parts.length - 2]) * 60_000L;
            if (parts.length >= 3) ms += Long.parseLong(parts[parts.length - 3]) * 3_600_000L;
            return ms;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
