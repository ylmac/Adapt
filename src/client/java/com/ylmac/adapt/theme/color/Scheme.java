/*
 * Decompiled with CFR 0.152.
 */
package com.ylmac.adapt.theme.color;

import com.ylmac.adapt.theme.color.Hct;
import com.ylmac.adapt.theme.color.MathUtils;
import com.ylmac.adapt.theme.color.TonalPalette;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Scheme {
    private static final Object[][] ROLES = new Object[][]{{"primary", Character.valueOf('P'), 40, 80}, {"onPrimaryColor", Character.valueOf('P'), 100, 20}, {"primaryContainer", Character.valueOf('P'), 90, 30}, {"onPrimaryContainerColor", Character.valueOf('P'), 10, 90}, {"secondary", Character.valueOf('S'), 40, 80}, {"onSecondaryColor", Character.valueOf('S'), 100, 20}, {"secondaryContainer", Character.valueOf('S'), 90, 30}, {"onSecondaryContainerColor", Character.valueOf('S'), 10, 90}, {"tertiary", Character.valueOf('T'), 40, 80}, {"onTertiaryColor", Character.valueOf('T'), 100, 20}, {"tertiaryContainer", Character.valueOf('T'), 90, 30}, {"onTertiaryContainerColor", Character.valueOf('T'), 10, 90}, {"error", Character.valueOf('E'), 40, 80}, {"onErrorColor", Character.valueOf('E'), 100, 20}, {"errorContainer", Character.valueOf('E'), 90, 30}, {"onErrorContainerColor", Character.valueOf('E'), 10, 90}, {"background", Character.valueOf('N'), 99, 10}, {"onBackgroundColor", Character.valueOf('N'), 10, 90}, {"surface", Character.valueOf('N'), 98, 6}, {"onSurfaceColor", Character.valueOf('N'), 10, 90}, {"surfaceVariant", Character.valueOf('V'), 90, 30}, {"onSurfaceVariantColor", Character.valueOf('V'), 30, 80}, {"outline", Character.valueOf('V'), 50, 60}, {"outlineVariant", Character.valueOf('V'), 80, 30}, {"shadow", Character.valueOf('N'), 0, 0}, {"scrim", Character.valueOf('N'), 0, 0}, {"inverseSurface", Character.valueOf('N'), 20, 90}, {"inverseOnSurface", Character.valueOf('N'), 95, 20}, {"inversePrimary", Character.valueOf('P'), 80, 40}, {"surfaceDim", Character.valueOf('N'), 87, 6}, {"surfaceBright", Character.valueOf('N'), 98, 24}, {"surfaceContainerLowest", Character.valueOf('N'), 100, 4}, {"surfaceContainerLow", Character.valueOf('N'), 96, 10}, {"surfaceContainer", Character.valueOf('N'), 94, 12}, {"surfaceContainerHigh", Character.valueOf('N'), 92, 17}, {"surfaceContainerHighest", Character.valueOf('N'), 90, 22}};

    private Scheme() {
    }

    public static Map<String, String> generate(int seedArgb, boolean dark) {
        double hue = Hct.fromInt(seedArgb).getHue();
        TonalPalette primary = TonalPalette.fromHueAndChroma(hue, 36.0);
        TonalPalette secondary = TonalPalette.fromHueAndChroma(hue, 16.0);
        TonalPalette tertiary = TonalPalette.fromHueAndChroma(MathUtils.sanitizeDegreesDouble(hue + 60.0), 24.0);
        TonalPalette neutral = TonalPalette.fromHueAndChroma(hue, 6.0);
        TonalPalette neutralVariant = TonalPalette.fromHueAndChroma(hue, 8.0);
        TonalPalette error = TonalPalette.fromHueAndChroma(25.0, 84.0);
        LinkedHashMap<String, String> scheme = new LinkedHashMap<String, String>();
        for (Object[] role : ROLES) {
            char key = ((Character)role[1]).charValue();
            TonalPalette palette = key == 'P' ? primary : (key == 'S' ? secondary : (key == 'T' ? tertiary : (key == 'E' ? error : (key == 'V' ? neutralVariant : neutral))));
            int tone = (Integer)role[dark ? 3 : 2];
            scheme.put((String)role[0], Scheme.hex(palette.tone(tone)));
        }
        return scheme;
    }

    private static String hex(int argb) {
        return String.format("#%06x", argb & 0xFFFFFF);
    }
}
