package com.ylmac.adapt.features.music.unblock;

final class MatchUtil {

    private MatchUtil() {}

    static String normalizeName(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("[（(][^）)]*[）)]", "")
                .trim();
    }

    static String normalizeArtist(String artist) {
        if (artist == null) return "";
        return artist.toLowerCase()
                .replaceAll("[&/、，,;；]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static boolean isMatch(String resultName, String resultArtist,
                           String wantName, String wantArtist) {
        String rn = normalizeName(resultName);
        if (rn.isEmpty()) return false;
        String on = normalizeName(wantName);
        if (!on.isEmpty() && !rn.contains(on) && !on.contains(rn)) return false;

        if (resultArtist != null && wantArtist != null && !wantArtist.isEmpty()) {
            String ra = normalizeArtist(resultArtist);
            String oa = normalizeArtist(wantArtist);
            if (!ra.isEmpty() && !oa.isEmpty() && !ra.contains(oa) && !oa.contains(ra)) {
                return false;
            }
        }
        return true;
    }
}
