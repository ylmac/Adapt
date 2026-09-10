package com.ylmac.adapt.features.music.lyric.skia;

import com.ylmac.adapt.features.music.lyric.LyricLine;
import com.ylmac.adapt.features.music.lyric.Syllable;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.ylmac.adapt.config.DesktopSettings;

public class WorldLyricRenderer {

    private static final float ROW_HEIGHT_RATIO = 1.55f;
    private static final float WRAPPED_ROW_HEIGHT_RATIO = 1.0f;
    private static final float SUB_ROW_HEIGHT_RATIO = 1.1f;
    private static final float WRAP_SUB_GAP = 4f;

    /** 渲染窗口半径：世界歌词只显示当前行 ±1 行 = 3 行（原版 16 行窗口缩小） */
    private static final int VISIBLE_RADIUS = 1;
    private static final long INTERLUDE_THRESHOLD_MS = 2000L;
    private static final long INTERLUDE_TRAIL_TRIM_MS = 250L;
    private static final float INTERLUDE_DOTS_ROW_H = 40f;
    private static final float INTERLUDE_DOT_RADIUS = 6.8f;
    private static final float INTERLUDE_DOT_SPACING = 27f;
    private static final float LIFT_PEAK_PX = 2.0f;
    private static final long LIFT_MIN_DURATION_MS = 1000L;
    private static final float SWEEP_FADE_PX = 16f;
    private static final float DARK_MASK_ALPHA = 0.36f;
    private static final long ACTIVE_FADE_OUT_MS = 400L;
    private static final long ACTIVE_FADE_IN_MS = 200L;
    private static final float BG_SCALE_IDLE = 0f;
    private static final float BG_VISIBLE_THRESHOLD = 0.001f;
    private static final long BG_POP_IN_MS = 460L;
    private static final long BG_POP_IN_DELAY_MS = 150L;
    private static final long BG_POP_OUT_MS = 280L;
    private static final float BG_HUG_OFFSET_PX = 10f;
    /** 当前行在列内垂直位置（0.70：文字带贴面板底部，配合底部锚定呈右下角观感） */
    private static final float ALIGN_POSITION = 0.70f;
    private static final float SCROLL_EDGE_PAD = 0.7f;

    private static final float DESELECTED_SCALE = 0.98f;
    private static final float EMPHASIS_SCALE = 1.14f;

    private static final double SCROLL_STIFFNESS_MIN = 170.0;
    private static final double SCROLL_STIFFNESS_MAX = 220.0;
    private static final double SCROLL_INTERVAL_MIN_MS = 100.0;
    private static final double SCROLL_INTERVAL_MAX_MS = 800.0;
    private static final double SCROLL_DAMPING_MULT = 2.2;
    private static final double SCROLL_STIFFNESS_INTERLUDE = 90.0;
    private static final double SCROLL_DAMPING_INTERLUDE = 15.0;
    private static final double SCROLL_STIFFNESS_FIRM = 180.0;
    private static final double SCROLL_DAMPING_FIRM = 28.0;
    private static final double LIFT_OMEGA0 = 3.7416574;
    private static final double LIFT_ZETA = 0.935414;
    private static final float GLOW_ALPHA = 0.55f;
    private static final double LINE_DELAY_S = 0.05;
    private static final double LINE_DELAY_DECAY = 1.05;
    private static final int SNAP_JUMP_LINES = 6;

    private List<LyricLine> lines = Collections.emptyList();
    /** 对齐模式：0=左 1=中 2=右（与 DesktopSettings.worldLyricAlign 同值，默认右） */
    private int alignMode = 2;
    /** 世界歌词字号（px，独立于桌面歌词，DesktopSettings.worldLyricFontSize） */
    private int fontSize = 28;
    /** 世界歌词字重档位：0=极细 1=细 2=常规 3=中等（DesktopSettings.worldLyricFontWeight） */
    private int fontWeight = 2;
    /** 文字描边颜色：0=无 1=黑 2=白 3=橙 4=红 5=金渐变（DesktopSettings.worldLyricBorderColor） */
    private int borderColor = 0;
    /** 金渐变描边 shader（按列区域懒创建，render 时复用） */
    private Shader borderGradientShader;
    private float borderGradTop, borderGradBottom;
    /** 当前渲染的列区域（渐变描边用） */
    private float renderTopY, renderColH;
    private boolean hasPerSyllableTiming = false;
    private List<LineGroup> groups = Collections.emptyList();
    private int[] lineToGroup = new int[0];
    private int activeGroupIndex = -1;
    private float litBandTop, litBandBottom;
    private boolean litBandValid;
    private static final float LIT_BAND_TAU = 0.14f;
    private float litBandTopSmooth, litBandBottomSmooth;
    private boolean litBandSmoothInit;
    private long litBandSmoothNs;
    private final SpringAnim scrollAnim = new SpringAnim(SCROLL_STIFFNESS_FIRM, SCROLL_DAMPING_FIRM);
    private int lastSpringMode = -1;

    private final java.util.IdentityHashMap<Font, java.util.HashMap<Integer, Float>> charWidthCache =
            new java.util.IdentityHashMap<>();

    private int[][] cachedRowStarts;
    private float[] cachedLineHeights;
    private String[][] cachedRomajiRows;
    private String[][] cachedTranslationRows;
    private float[][] cachedSylWidths;
    /** 超宽行缩放后的音节宽度（行级复用） */
    private float[] scaledSylW;
    private float[] lineTopsBuf = new float[0];
    private float[] effHeightsBuf = new float[0];
    private float[] interludeBuf = new float[0];
    private float[] lineCurTop = new float[0];
    private float[] lineVelTop = new float[0];
    private double[] cascadeDelayBuf = new double[0];
    private boolean lineSpringInit = false;
    private int prevVisStart = 0;
    private int prevVisEnd = 0;
    private int springAnchorPrev = Integer.MIN_VALUE;
    private int cascadeDir = 1;
    private boolean bigSeekEasing = false;
    private long springAnchorChangeNs = 0L;
    private long springLastNs = 0L;

    private static final float SCROLL_DECEL = 2400f;
    private static final float SCROLL_MIN_FLING = 60f;
    private static final long SCROLL_IDLE_RETURN_NS = 4_000_000_000L;
    private static final int SCROLL_VEL_SAMPLES = 8;
    private static final float SCROLL_VEL_WINDOW = 0.09f;
    private boolean userScrollActive;
    private boolean userDragging;
    private boolean userFling;
    private boolean userReturning;
    private float userScroll;
    private float userFlingVel;
    private long userScrollLastNs;
    private long userLastInteractNs;
    private int userHoldAnchor = Integer.MIN_VALUE;
    private int userScrollPrevAnchor = Integer.MIN_VALUE;
    private float lastScrollY;
    private float lastCenterY;
    private float scrollMin, scrollMax;
    private final long[] dragSampleNs = new long[SCROLL_VEL_SAMPLES];
    private final float[] dragSampleY = new float[SCROLL_VEL_SAMPLES];
    private int dragSampleCount;

    private float[] sylLeftBuf = new float[0];
    private final io.github.humbleui.skija.Paint lyricLayerPaint = new io.github.humbleui.skija.Paint();
    private final io.github.humbleui.skija.Paint dotPaint = new io.github.humbleui.skija.Paint();
    private final io.github.humbleui.skija.Paint sweepPaint = new io.github.humbleui.skija.Paint();
    private final int[] sweepColors = new int[2];
    private final float[] sweepStops = new float[2];
    private Shader sweepShader;
    private float sweepShaderDark = Float.NaN;
    private final Rect sweepBigRect = Rect.makeLTRB(-100000f, -100000f, 100000f, 100000f);
    private final io.github.humbleui.skija.Paint glowGlyphPaint = newGlowGlyphPaint();
    private final io.github.humbleui.skija.Paint glowLayerPaint = newGlowLayerPaint();
    /** 歌词阴影 layer paint：restore 时对整行文字一次性应用柔和投影 */
    private final io.github.humbleui.skija.Paint shadowLayerPaint = newShadowLayerPaint();
    private float[] liftBuf = new float[0];
    private float[] glowAlphaBuf = new float[0];

    private static io.github.humbleui.skija.Paint newGlowGlyphPaint() {
        io.github.humbleui.skija.Paint p = new io.github.humbleui.skija.Paint();
        p.setAntiAlias(true);
        return p;
    }

    private static io.github.humbleui.skija.Paint newGlowLayerPaint() {
        io.github.humbleui.skija.Paint p = new io.github.humbleui.skija.Paint();
        p.setImageFilter(io.github.humbleui.skija.ImageFilter.makeBlur(
                2.5f, 2.5f, io.github.humbleui.skija.FilterTileMode.CLAMP));
        return p;
    }

    private static io.github.humbleui.skija.Paint newShadowLayerPaint() {
        io.github.humbleui.skija.Paint p = new io.github.humbleui.skija.Paint();
        // 柔和投影：下方偏移 2px + 高斯模糊 1.5σ，50% 黑色
        p.setImageFilter(io.github.humbleui.skija.ImageFilter.makeDropShadow(
                0f, 2f, 1.5f, 1.5f, 0x80000000));
        return p;
    }

    private List<LyricLine> layoutKeyLines;
    private int layoutKeyN;
    private int layoutKeyLyricSize;
    private int layoutKeySubSize;
    private int layoutKeyColW = -1;
    private Fonts.Weight layoutKeyWeight;
    private float layoutKeyRowRatio = -1f;
    private boolean layoutKeyRomaji;
    private boolean layoutKeyTranslation;
    private boolean layoutKeyScale = true;

    private static Fonts.Weight toFontsWeight(LyricConfig.FontWeight w) {
        switch (w) {
            case THIN:
                return Fonts.Weight.THIN;
            case LIGHT:
                return Fonts.Weight.LIGHT;
            case MEDIUM:
                return Fonts.Weight.MEDIUM;
            default:
                return Fonts.Weight.REGULAR;
        }
    }

    public boolean hasLines() {
        return !lines.isEmpty();
    }

    /** 设置面板对齐：0=左 1=中 2=右（DesktopSettings.worldLyricAlign 同值） */
    public void setAlignMode(int mode) {
        this.alignMode = mode;
    }

    /** 设置世界歌词字号（px，独立于桌面歌词） */
    public void setFontSize(int px) {
        this.fontSize = Math.max(10, px);
    }

    /** 设置世界歌词字重：0=极细 1=细 2=常规 3=中等（Fonts.Weight 同序） */
    public void setFontWeight(int w) {
        this.fontWeight = Math.max(0, Math.min(3, w));
    }

    /** 设置世界歌词描边颜色：0=无 1=黑 2=白 3=橙 4=红 5=金渐变 */
    public void setBorderColor(int color) {
        this.borderColor = Math.max(0, Math.min(5, color));
    }

    public void setLyrics(List<LyricLine> newLines) {
        List<LyricLine> filtered;
        if (newLines == null || newLines.isEmpty()) {
            filtered = Collections.emptyList();
        } else {
            filtered = new java.util.ArrayList<>(newLines.size());
            for (LyricLine l : newLines) {
                if (l == null) continue;
                boolean hasText = false;
                for (Syllable s : l.syllables) {
                    if (s != null && s.text != null && !s.text.trim().isEmpty()) {
                        hasText = true;
                        break;
                    }
                }
                if (hasText) filtered.add(l);
            }
        }
        this.lines = filtered;
        this.groups = buildGroups(this.lines);
        this.lineToGroup = new int[this.lines.size()];
        for (int gi = 0; gi < this.groups.size(); gi++) {
            LineGroup g = this.groups.get(gi);
            for (int i = g.from; i < g.to; i++) lineToGroup[i] = gi;
        }
        boolean perSyl = false;
        for (LyricLine l : this.lines) {
            if (l.syllables.size() > 1) {
                perSyl = true;
                break;
            }
        }
        this.hasPerSyllableTiming = perSyl;

        if (!perSyl) {
            for (LyricLine l : this.lines) {
                if (l.syllables.size() != 1) continue;
                List<Syllable> toks = tokenizeForWrap(l.syllables.get(0));
                if (toks.size() > 1) {
                    l.syllables.clear();
                    l.syllables.addAll(toks);
                }
            }
        }

        this.activeGroupIndex = -1;
        this.scrollAnim.setValue(0);
        this.lineSpringInit = false;
        this.bigSeekEasing = false;
        this.springAnchorPrev = Integer.MIN_VALUE;
        this.userScrollActive = false;
        this.userDragging = false;
        this.userFling = false;
        this.userReturning = false;
        this.userScrollPrevAnchor = Integer.MIN_VALUE;
        this.userHoldAnchor = Integer.MIN_VALUE;
    }

    private static List<Syllable> tokenizeForWrap(Syllable s) {
        String text = s.text == null ? "" : s.text;
        long start = s.startMs;
        long dur = s.durationMs;
        List<Syllable> out = new java.util.ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == ' ') {
                int j = i;
                while (j < n && text.charAt(j) == ' ') j++;
                out.add(new Syllable(text.substring(i, j), start, dur));
                i = j;
            } else if (isWrapCjk(c)) {
                out.add(new Syllable(String.valueOf(c), start, dur));
                i++;
            } else {
                int j = i;
                while (j < n && text.charAt(j) != ' ' && !isWrapCjk(text.charAt(j))) j++;
                out.add(new Syllable(text.substring(i, j), start, dur));
                i = j;
            }
        }
        return out;
    }

    private static boolean isWrapCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7A3);
    }

    private static final class LineGroup {
        final int from;
        final int to;
        final long startMs;
        final long endMs;

        LineGroup(int from, int to, long startMs, long endMs) {
            this.from = from;
            this.to = to;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        boolean contains(int i) {
            return i >= from && i < to;
        }
    }

    private static List<LineGroup> buildGroups(List<LyricLine> lines) {
        List<LineGroup> out = new ArrayList<>();
        int n = lines.size();
        int i = 0;
        while (i < n) {
            int j = i + 1;
            long endMs = lines.get(i).endMs();
            while (j < n && isBackground(lines.get(j).vocalChannel)) {
                endMs = Math.max(endMs, lines.get(j).endMs());
                j++;
            }
            out.add(new LineGroup(i, j, lines.get(i).startMs(), endMs));
            i = j;
        }
        return out;
    }

    public float[] litBandBounds() {
        if (!litBandValid) { litBandSmoothInit = false; return null; }
        long now = System.nanoTime();
        if (!litBandSmoothInit) {
            litBandTopSmooth = litBandTop;
            litBandBottomSmooth = litBandBottom;
            litBandSmoothInit = true;
        } else {
            float dt = (now - litBandSmoothNs) / 1_000_000_000f;
            if (dt > 0.05f) dt = 0.05f;
            if (dt > 0f) {
                float a = 1f - (float) Math.exp(-dt / LIT_BAND_TAU);
                litBandTopSmooth += (litBandTop - litBandTopSmooth) * a;
                litBandBottomSmooth += (litBandBottom - litBandBottomSmooth) * a;
            }
        }
        litBandSmoothNs = now;
        return new float[]{litBandTopSmooth, litBandBottomSmooth};
    }

    public void render(Canvas canvas, float leftX, float topY,
                       float columnWidth, float columnHeight, long positionMs) {
        final java.util.List<LyricLine> lines = this.lines;
        final java.util.List<LineGroup> groups = this.groups;
        final int[] lineToGroup = this.lineToGroup;
        if (lines.isEmpty()) return;

        // 记录列区域（渐变描边 shader 用）
        renderTopY = topY;
        renderColH = columnHeight;

        LyricConfig cfg = LyricConfig.instance;
        // 世界歌词字号/字重独立设置（其余效果配置沿用桌面歌词 LyricConfig）
        int lyricFontSize = fontSize;
        int subFontSize = Math.max(10, Math.round(lyricFontSize * 0.6f));
        int bgFontSize = Math.max(10, Math.round(lyricFontSize * 0.7f));
        float lineGap = 0f;
        Fonts.Weight weight = Fonts.Weight.values()[fontWeight];
        float rowHeightRatio = cfg.lineSpacing.getValue();

        boolean spring = Boolean.TRUE.equals(cfg.springPhysics.getValue());
        boolean scaleOn = Boolean.TRUE.equals(cfg.scaleEmphasis.getValue());
        boolean glowOn = Boolean.TRUE.equals(cfg.glow.getValue());
        int springMode = spring ? 1 : 0;
        if (springMode != lastSpringMode) {
            scrollAnim.setParams(SCROLL_STIFFNESS_FIRM, SCROLL_DAMPING_FIRM);
            lineSpringInit = false;
            lastSpringMode = springMode;
        }

        Font lyricFont = Fonts.get(weight, lyricFontSize);
        Font subFont = Fonts.get(weight, subFontSize);
        Font bgFont = Fonts.get(weight, bgFontSize);

        configureForAnimation(lyricFont);
        configureForAnimation(bgFont);
        configureForAnimation(subFont);

        float rowHeightLyric = lyricFontSize * rowHeightRatio;
        float rowHeightLyricWrap = lyricFontSize * WRAPPED_ROW_HEIGHT_RATIO;
        float rowHeightBg = bgFontSize * rowHeightRatio;
        float rowHeightBgWrap = bgFontSize * WRAPPED_ROW_HEIGHT_RATIO;
        float subLineHeight = subFontSize * SUB_ROW_HEIGHT_RATIO;

        boolean showRomaji = cfg.showRomaji.getValue();
        boolean showTranslation = cfg.showTranslation.getValue();

        int n = lines.size();
        int colW = Math.round(columnWidth);
        boolean layoutValid = cachedRowStarts != null
                && layoutKeyLines == lines
                && layoutKeyN == n
                && layoutKeyLyricSize == lyricFontSize
                && layoutKeySubSize == subFontSize
                && layoutKeyColW == colW
                && layoutKeyWeight == weight
                && layoutKeyRowRatio == rowHeightRatio
                && layoutKeyRomaji == showRomaji
                && layoutKeyTranslation == showTranslation
                && layoutKeyScale == scaleOn;
        if (!layoutValid) {
            int[][] rowStarts = new int[n][];
            float[] lineHeights = new float[n];
            String[][] romajiRows = new String[n][];
            String[][] translationRows = new String[n][];
            float[][] sylWidths = new float[n][];
            for (int i = 0; i < n; i++) {
                LyricLine line = lines.get(i);
                boolean isBg = isBackground(line.vocalChannel);
                Font font = isBg ? bgFont : lyricFont;
                float rowHeight = isBg ? rowHeightBg : rowHeightLyric;

                float wrapW = (isBg || !scaleOn) ? columnWidth : columnWidth / EMPHASIS_SCALE;

                int sylCount = line.syllables.size();
                float[] widths = new float[sylCount];
                for (int s = 0; s < sylCount; s++) {
                    String st = line.syllables.get(s).text;
                    widths[s] = perCharWidth(st, fontForText(st, font));
                }
                sylWidths[i] = widths;
                rowStarts[i] = wrapStarts(line.syllables, widths, wrapW);
                int subRowCount = Math.max(1, rowStarts[i].length - 1);

                float lh = rowHeight + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                boolean hasSub = (line.romaji != null && showRomaji) || (line.translation != null && showTranslation);
                if (hasSub && subRowCount > 1) lh += WRAP_SUB_GAP;
                if (line.romaji != null && showRomaji) {
                    romajiRows[i] = wrapText(line.romaji, subFont, wrapW);
                    lh += subLineHeight * romajiRows[i].length;
                }
                if (line.translation != null && showTranslation) {
                    translationRows[i] = wrapText(line.translation, subFont, wrapW);
                    lh += subLineHeight * translationRows[i].length;
                }
                lh += lineGap;
                lineHeights[i] = lh;
            }
            cachedRowStarts = rowStarts;
            cachedLineHeights = lineHeights;
            cachedRomajiRows = romajiRows;
            cachedTranslationRows = translationRows;
            cachedSylWidths = sylWidths;
            layoutKeyLines = lines;
            layoutKeyN = n;
            layoutKeyLyricSize = lyricFontSize;
            layoutKeySubSize = subFontSize;
            layoutKeyColW = colW;
            layoutKeyWeight = weight;
            layoutKeyRowRatio = rowHeightRatio;
            layoutKeyRomaji = showRomaji;
            layoutKeyTranslation = showTranslation;
            layoutKeyScale = scaleOn;
        }
        int[][] rowStarts = cachedRowStarts;
        float[] lineHeights = cachedLineHeights;
        float[][] sylWidths = cachedSylWidths;

        float lyricDescent = lyricFont.getMetrics().getDescent();
        float lyricAscent = lyricFont.getMetrics().getAscent();
        float bgDescent = bgFont.getMetrics().getDescent();
        float bgAscent = bgFont.getMetrics().getAscent();

        if (interludeBuf.length != groups.size()) interludeBuf = new float[groups.size()];
        float[] interludeBefore = interludeBuf;
        for (int gi = 0; gi < groups.size(); gi++) {
            interludeBefore[gi] = 0f;
            long prevEnd = (gi == 0) ? 0L : groups.get(gi - 1).endMs;
            long currStart = groups.get(gi).startMs;
            long effectiveEnd = currStart - INTERLUDE_TRAIL_TRIM_MS;
            long gap = effectiveEnd - prevEnd;
            if (gap < INTERLUDE_THRESHOLD_MS) continue;
            interludeBefore[gi] = computeInterludeSlot(positionMs, prevEnd, effectiveEnd);
        }

        if (effHeightsBuf.length != n) effHeightsBuf = new float[n];
        float[] effHeights = effHeightsBuf;
        for (int i = 0; i < n; i++) {
            float h = lineHeights[i];
            if (isBackground(lines.get(i).vocalChannel)) {
                h *= computeActiveK(positionMs, groups.get(lineToGroup[i]));
            }
            effHeights[i] = h;
        }

        if (lineTopsBuf.length != n) lineTopsBuf = new float[n];
        float[] lineTops = lineTopsBuf;
        for (int i = 0; i < n; i++) {
            float prevBottom = i == 0 ? 0f : lineTops[i - 1] + effHeights[i - 1];
            int gi = lineToGroup[i];
            if (!groups.isEmpty() && gi >= 0 && gi < groups.size()
                    && groups.get(gi).from == i && interludeBefore[gi] > 0f) {
                prevBottom += interludeBefore[gi];
            }
            lineTops[i] = prevBottom;
        }

        int anchorGroup = -1;
        int latestActiveGroup = -1;
        for (int gi = 0; gi < groups.size(); gi++) {
            LineGroup g = groups.get(gi);
            if (g.startMs > positionMs) break;
            if (positionMs < g.endMs) {
                if (anchorGroup < 0) anchorGroup = gi;
            }
            latestActiveGroup = gi;
        }
        if (anchorGroup < 0) anchorGroup = latestActiveGroup;
        activeGroupIndex = anchorGroup;

        LineGroup activeGroup = (activeGroupIndex >= 0 && activeGroupIndex < groups.size())
                ? groups.get(activeGroupIndex) : null;

        float targetScroll = 0f;
        boolean inInterlude = false;
        int interludeNextGroup = -1;
        long interludeStartMs = 0L;
        if (activeGroup != null) {
            int mIdx = activeGroup.from;
            float mTop = lineTops[mIdx];
            float mBottom = mTop + lineHeights[mIdx];
            float reservedBg = 0f;
            for (int j = activeGroup.from + 1; j < activeGroup.to; j++) {
                if (isBackground(lines.get(j).vocalChannel)) reservedBg += effHeights[j];
            }
            targetScroll = (mTop + mBottom) * 0.5f + reservedBg;
        }

        LineGroup nextGroup = null;
        long gapStart = -1L;
        if (activeGroup == null && !groups.isEmpty()
                && positionMs < groups.get(0).startMs) {
            nextGroup = groups.get(0);
            gapStart = 0L;
            interludeNextGroup = 0;
        } else if (activeGroup != null && activeGroupIndex + 1 < groups.size()
                && positionMs >= activeGroup.endMs) {
            nextGroup = groups.get(activeGroupIndex + 1);
            gapStart = activeGroup.endMs;
            interludeNextGroup = activeGroupIndex + 1;
        }
        if (nextGroup != null) {
            long effectiveEnd = nextGroup.startMs - INTERLUDE_TRAIL_TRIM_MS;
            long gap = effectiveEnd - gapStart;
            if (positionMs < effectiveEnd && gap >= INTERLUDE_THRESHOLD_MS) {
                inInterlude = true;
                interludeStartMs = gapStart;
                float slotH = interludeBefore[interludeNextGroup];
                float dotsTop = lineTops[nextGroup.from] - slotH;
                targetScroll = dotsTop + slotH * 0.5f;
            } else if (positionMs >= effectiveEnd && positionMs < nextGroup.startMs
                    && gap >= INTERLUDE_THRESHOLD_MS) {
                int nIdx = nextGroup.from;
                float nTop = lineTops[nIdx];
                float nBottom = nTop + lineHeights[nIdx];
                targetScroll = (nTop + nBottom) * 0.5f;
                interludeNextGroup = -1;
            } else {
                interludeNextGroup = -1;
            }
        }

        if (n > 0) {
            float contentEnd = lineTops[n - 1] + effHeights[n - 1];
            if (contentEnd > columnHeight) {
                float pad = columnHeight * SCROLL_EDGE_PAD;
                scrollMin = columnHeight * ALIGN_POSITION - pad;
                scrollMax = contentEnd - columnHeight * (1f - ALIGN_POSITION) + pad;
            } else {
                scrollMin = scrollMax = targetScroll;
            }
            if (targetScroll < scrollMin) targetScroll = scrollMin;
            else if (targetScroll > scrollMax) targetScroll = scrollMax;
        }

        float centerY = topY + columnHeight * ALIGN_POSITION;
        lastCenterY = centerY;

        int anchorIdx = activeGroup != null ? activeGroup.from : 0;
        int windowCenter = userScrollActive ? lineIndexAt(lineTops, n, lastScrollY) : anchorIdx;
        int start = Math.max(0, windowCenter - VISIBLE_RADIUS);
        int end = Math.min(n, windowCenter + VISIBLE_RADIUS + 1);

        long nowNs = System.nanoTime();
        double springDt = 0.0;
        if (spring) {
            if (lineCurTop.length != n) {
                lineCurTop = new float[n];
                lineVelTop = new float[n];
                lineSpringInit = false;
            }
            if (anchorIdx != springAnchorPrev) {
                if (springAnchorPrev != Integer.MIN_VALUE) {
                    cascadeDir = (anchorIdx > springAnchorPrev) ? 1 : -1;
                    if (Math.abs(anchorIdx - springAnchorPrev) > SNAP_JUMP_LINES
                            && springAnchorPrev >= 0 && springAnchorPrev < n) {
                        bigSeekEasing = true;
                        scrollAnim.setValue(centerY + lineTops[springAnchorPrev] - lineCurTop[springAnchorPrev]);
                    }
                }
                springAnchorPrev = anchorIdx;
                springAnchorChangeNs = nowNs;
            }
            springDt = (nowNs - springLastNs) / 1_000_000_000.0;
            if (springDt > 0.05) springDt = 0.05;
            if (springDt < 0.0) springDt = 0.0;
            springLastNs = nowNs;
        }
        double sinceAnchorChange = (nowNs - springAnchorChangeNs) / 1_000_000_000.0;

        boolean rigidMode = !spring || bigSeekEasing;

        if (userScrollActive && userScrollPrevAnchor != Integer.MIN_VALUE
                && Math.abs(anchorIdx - userScrollPrevAnchor) > SNAP_JUMP_LINES) {
            userScrollActive = false;
            userDragging = false;
            userFling = false;
            userReturning = false;
            scrollAnim.setValue(lastScrollY);
        }
        userScrollPrevAnchor = anchorIdx;

        float scrollY;
        if (userScrollActive) {
            rigidMode = true;
            scrollY = stepUserScroll(targetScroll, nowNs, anchorIdx);
        } else {
            scrollY = (float) scrollAnim.animate(targetScroll);
            if (bigSeekEasing && Math.abs(scrollY - targetScroll) < 0.5f) {
                bigSeekEasing = false;
            }
        }
        lastScrollY = scrollY;

        double scrollStiffness;
        double scrollDamping;
        if (inInterlude) {
            scrollStiffness = SCROLL_STIFFNESS_INTERLUDE;
            scrollDamping = SCROLL_DAMPING_INTERLUDE;
        } else {
            LineGroup prevG = (activeGroupIndex > 0) ? groups.get(activeGroupIndex - 1) : null;
            double interval = (activeGroup != null && prevG != null)
                    ? (activeGroup.startMs - prevG.startMs) : SCROLL_INTERVAL_MAX_MS;
            double ci = Math.max(SCROLL_INTERVAL_MIN_MS, Math.min(SCROLL_INTERVAL_MAX_MS, interval));
            double ratio = Math.pow(1.0 - (ci - SCROLL_INTERVAL_MIN_MS)
                    / (SCROLL_INTERVAL_MAX_MS - SCROLL_INTERVAL_MIN_MS), 0.2);
            scrollStiffness = SCROLL_STIFFNESS_MIN + ratio * (SCROLL_STIFFNESS_MAX - SCROLL_STIFFNESS_MIN);
            scrollDamping = Math.sqrt(scrollStiffness) * SCROLL_DAMPING_MULT;
        }

        if (cascadeDelayBuf.length < n) cascadeDelayBuf = new double[n];
        for (int i = start; i < end; i++) cascadeDelayBuf[i] = 0.0;
        double cascDelay = 0.0;
        double cascStep = LINE_DELAY_S;
        if (cascadeDir >= 0) {
            for (int i = Math.max(start, anchorIdx + 1); i < end; i++) {
                cascDelay += cascStep;
                cascStep /= LINE_DELAY_DECAY;
                cascadeDelayBuf[i] = cascDelay;
            }
        } else {
            for (int i = Math.min(end - 1, anchorIdx - 1); i >= start; i--) {
                cascDelay += cascStep;
                cascStep /= LINE_DELAY_DECAY;
                cascadeDelayBuf[i] = cascDelay;
            }
        }

        litBandValid = false;
        for (int i = start; i < end; i++) {
            LyricLine line = lines.get(i);
            LineGroup myGroup = groups.get(lineToGroup[i]);

            float activeK = computeActiveK(positionMs, myGroup);

            LyricLine.VocalChannel ch = line.vocalChannel;
            boolean isBg = isBackground(ch);

            Font font = isBg ? bgFont : lyricFont;
            float rowHeight = isBg ? rowHeightBg : rowHeightLyric;
            float descent = isBg ? bgDescent : lyricDescent;
            float ascent = isBg ? bgAscent : lyricAscent;
            float[] lineSylW = sylWidths[i];
            float idleBase = isBg ? 0.18f : 0.22f;
            float activeBase = isBg ? 0.70f : 1f;
            float baseAlpha = idleBase + (activeBase - idleBase) * activeK;

            float restTop = centerY + lineTops[i] - targetScroll;
            float lineYTop;
            if (rigidMode) {
                lineYTop = centerY + lineTops[i] - scrollY;
                if (spring) {
                    lineCurTop[i] = lineYTop;
                    lineVelTop[i] = 0f;
                }
            } else {
                boolean wasVisible = i >= prevVisStart && i < prevVisEnd;
                if (!lineSpringInit || !wasVisible) {
                    lineCurTop[i] = restTop;
                    lineVelTop[i] = 0f;
                } else {
                    if (sinceAnchorChange >= cascadeDelayBuf[i] && springDt > 0.0) {
                        stepLineSpring(i, restTop, springDt, scrollStiffness, scrollDamping);
                    }
                }
                lineYTop = lineCurTop[i];
            }

            if (lineYTop + lineHeights[i] < topY - 32f || lineYTop > topY + columnHeight + 32f) {
                continue;
            }

            int[] starts = rowStarts[i];
            int subRowCount = Math.max(1, starts.length - 1);

            float maxRowWidth = 0f;
            float maxRowRightX = leftX;

            // 面板内对齐锚点（缩放/发射中心）：左→列左缘 中→列中心 右→列右缘
            float anchorX;
            switch (alignMode) {
                case 1: anchorX = leftX + columnWidth * 0.5f; break;
                case 2: anchorX = leftX + columnWidth; break;
                default: anchorX = leftX;
            }
            float scale;
            float anchorY;
            if (isBg) {
                float bgScaleK = computeBgScaleK(positionMs, myGroup);
                if (bgScaleK < BG_VISIBLE_THRESHOLD) continue;
                scale = BG_SCALE_IDLE + (1f - BG_SCALE_IDLE) * bgScaleK;
                anchorY = lineYTop;
            } else if (scaleOn) {
                float mainTextH = rowHeight + (subRowCount - 1) * rowHeightLyricWrap;
                float lineCenter = lineYTop + mainTextH * 0.5f;
                float emph;
                if (spring) {
                    float ref = Math.max(40f, lineHeights[i]);
                    float prog = 1f - Math.min(1f, Math.abs(lineCenter - centerY) / ref);
                    emph = activeK * prog;
                } else {
                    emph = activeK;
                }
                scale = DESELECTED_SCALE + (EMPHASIS_SCALE - DESELECTED_SCALE) * emph;
                anchorY = lineCenter;
            } else {
                scale = 1f;
                anchorY = lineYTop;
            }

            if (activeK >= 0.5f) {
                float lt = lineYTop, lb = lineYTop + lineHeights[i];
                if (!litBandValid) { litBandTop = lt; litBandBottom = lb; litBandValid = true; }
                else {
                    if (lt < litBandTop) litBandTop = lt;
                    if (lb > litBandBottom) litBandBottom = lb;
                }
            }

            canvas.save();
            canvas.translate(anchorX, anchorY);
            canvas.scale(scale, scale);
            canvas.translate(-anchorX, -anchorY);

            for (int r = 0; r < subRowCount; r++) {
                int from = starts[r];
                int to = (r + 1 < starts.length) ? starts[r + 1] : line.syllables.size();

                float rowWidth = sumWidths(lineSylW, from, to);
                float lead = (r > 0) ? leadingWsWidth(line.syllables, lineSylW, from, to, font) : 0f;
                float visWidth = rowWidth - lead;
                float rowX = rowStartX(leftX, columnWidth, visWidth);

                // 行超宽时等比缩小字号：保证完整显示在列内，且居中对齐不因钳位而偏移
                Font rowFont = font;
                float[] rowSylW = lineSylW;
                float rowLead = lead;
                if (rowWidth > columnWidth && rowWidth > 1f) {
                    float s = columnWidth / rowWidth;
                    rowFont = Fonts.get(weight, font.getSize() * s);
                    if (scaledSylW == null || scaledSylW.length != lineSylW.length) {
                        scaledSylW = new float[lineSylW.length];
                    }
                    for (int k = 0; k < lineSylW.length; k++) scaledSylW[k] = lineSylW[k] * s;
                    rowSylW = scaledSylW;
                    rowLead = lead * s;
                    visWidth = rowWidth * s;
                    rowX = rowStartX(leftX, columnWidth, visWidth);
                }

                float wrapRowH = (r == 0) ? rowHeight : (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                float rowBaselineY = lineYTop + rowHeight + r * wrapRowH - descent - 4f;
                boolean enableLift = hasPerSyllableTiming;
                drawSyllableRange(line.syllables, rowSylW, from, to, rowX - rowLead, rowBaselineY, rowFont,
                        ascent, descent, positionMs, baseAlpha, activeK, enableLift, spring, glowOn);

                if (visWidth > maxRowWidth) {
                    maxRowWidth = visWidth;
                    maxRowRightX = rowX + visWidth;
                }
            }

            float subY = lineYTop + rowHeight
                    + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap) + 4f
                    + (subRowCount > 1 ? WRAP_SUB_GAP : 0f);
            subY = drawSubline(leftX, subFont, subLineHeight, showRomaji, i, baseAlpha, maxRowRightX, subY, cachedRomajiRows);
            subY = drawSubline(leftX, subFont, subLineHeight, showTranslation, i, baseAlpha, maxRowRightX, subY, cachedTranslationRows);

            canvas.restore();
        }

        if (spring) {
            prevVisStart = start;
            prevVisEnd = end;
            lineSpringInit = true;
        }

        if (inInterlude && interludeNextGroup >= 0) {
            LineGroup interludeNext = groups.get(interludeNextGroup);
            long effectiveEnd = interludeNext.startMs - INTERLUDE_TRAIL_TRIM_MS;
            long interludeDur = effectiveEnd - interludeStartMs;
            float slotH = interludeBefore[interludeNextGroup];
            if (slotH > 4f) {
                int nf = interludeNext.from;
                float nextTop = (spring && nf >= start && nf < end)
                        ? lineCurTop[nf]
                        : centerY + lineTops[nf] - (spring && !userScrollActive ? targetScroll : scrollY);
                float nextTextOffset = rowHeightLyric + lyricAscent - lyricDescent - 4f;
                float prevTextBottom = nextTop - slotH;
                float nextTextTop = nextTop + nextTextOffset;
                float anchorY = (prevTextBottom + nextTextTop) * 0.5f - INTERLUDE_DOT_RADIUS;
                LyricLine.VocalChannel nextCh = lines.get(interludeNext.from).vocalChannel;
                boolean dotsRight = nextCh == LyricLine.VocalChannel.DUET_RIGHT
                        || nextCh == LyricLine.VocalChannel.BACKGROUND_RIGHT;
                float dotsWidth = 2f * INTERLUDE_DOT_RADIUS + 2f * INTERLUDE_DOT_SPACING;
                float dotsX = dotsRight ? Math.max(leftX, leftX + columnWidth - dotsWidth) : leftX;
                renderInterludeDots(canvas, dotsX, anchorY,
                        positionMs - interludeStartMs, interludeDur);
            }
        }
    }

    /** 面板内行起始 x：0=左 1=中 2=右（按 alignMode，不超出列左缘） */
    private float rowStartX(float leftX, float columnWidth, float textWidth) {
        switch (alignMode) {
            case 1:
                return Math.max(leftX, leftX + (columnWidth - textWidth) * 0.5f);
            case 2:
                return Math.max(leftX, leftX + columnWidth - textWidth);
            default:
                return leftX;
        }
    }

    private float drawSubline(float leftX, Font subFont, float subLineHeight, boolean showRomaji, int i, float baseAlpha, float maxRowRightX, float subY, String[][] cachedRomajiRows) {
        String[] romajiRows = cachedRomajiRows[i];
        if (romajiRows != null && showRomaji) {
            for (String romajiRow : romajiRows) {
                drawSubLine(romajiRow, leftX, maxRowRightX, subY, subFont,
                        baseAlpha * 0.75f, alignMode);
                subY += subLineHeight;
            }
        }
        return subY;
    }

    private static float computeInterludeSlot(long positionMs, long prevEnd, long currStart) {
        long lead = 150L;
        long trail = 150L;
        if (positionMs < prevEnd || positionMs > currStart) return 0f;
        float t;
        long inside = positionMs - prevEnd;
        if (inside < lead) {
            t = inside / (float) lead;
        } else if (currStart - positionMs < trail) {
            t = (currStart - positionMs) / (float) trail;
        } else {
            t = 1f;
        }
        t = Math.max(0f, Math.min(1f, t));
        float eased = t * t * (3f - 2f * t);
        return INTERLUDE_DOTS_ROW_H * eased;
    }

    private void renderInterludeDots(Canvas canvas, float leftX, float anchorY,
                                     long currentDuration, long interludeDuration) {
        if (currentDuration < 0L || currentDuration > interludeDuration) return;

        long fadeInStartMs = 0L;
        long fadeInEndMs = Math.min(600L, (long) (interludeDuration * 0.20));
        long scaleRampMs = Math.min(1500L, (long) (interludeDuration * 0.35));
        long exitScaleMs = Math.min(750L, (long) (interludeDuration * 0.20));
        long exitOpacityMs = Math.min(375L, (long) (interludeDuration * 0.10));
        if (fadeInEndMs <= fadeInStartMs) fadeInEndMs = fadeInStartMs + 1L;

        double breatheDur = interludeDuration
                / Math.ceil(interludeDuration / 1500.0);
        double scale = 1.0;
        double globalOpacity = 1.0;

        scale *= Math.sin(1.5 * Math.PI
                - (currentDuration / breatheDur) * 2.0) / 20.0 + 1.0;

        if (currentDuration < scaleRampMs) {
            scale *= easeOutExpoD(currentDuration / (double) scaleRampMs);
        }

        if (currentDuration < fadeInStartMs) {
            globalOpacity = 0.0;
        } else if (currentDuration < fadeInEndMs) {
            globalOpacity *= (currentDuration - fadeInStartMs)
                    / (double) (fadeInEndMs - fadeInStartMs);
        }

        long remaining = interludeDuration - currentDuration;
        if (remaining < exitScaleMs) {
            scale *= 1.0 - easeInOutBackD(
                    (exitScaleMs - remaining) / (double) exitScaleMs / 2.0);
        }
        if (remaining < exitOpacityMs) {
            globalOpacity *= Math.max(0.0,
                    Math.min(1.0, remaining / (double) exitOpacityMs));
        }

        long dotsDur = Math.max(1L, interludeDuration - exitScaleMs);
        scale = Math.max(0.0, scale) * 0.7;
        if (scale < 0.01) return;

        double op0 = clampD(0.25, (currentDuration * 3.0 / dotsDur) * 0.75, 1.0);
        double op1 = clampD(0.25,
                ((currentDuration - dotsDur / 3.0) * 3.0 / dotsDur) * 0.75, 1.0);
        double op2 = clampD(0.25,
                ((currentDuration - dotsDur * 2.0 / 3.0) * 3.0 / dotsDur) * 0.75, 1.0);

        float dotRadius = INTERLUDE_DOT_RADIUS;
        float spacing = INTERLUDE_DOT_SPACING;
        float cx0 = leftX + dotRadius;
        float cy = anchorY + dotRadius;

        canvas.save();
        canvas.translate(cx0 + spacing, cy);
        canvas.scale((float) scale, (float) scale);
        canvas.translate(-(cx0 + spacing), -cy);
        try {
            double[] ops = {globalOpacity * op0, globalOpacity * op1, globalOpacity * op2};
            for (int i = 0; i < 3; i++) {
                Paint p = dotPaint;
                p.setColor(0xFFFFFFFF);
                float a = (float) Math.max(0.0, Math.min(1.0, ops[i]));
                p.setAlphaf(a);
                p.setAntiAlias(true);
                canvas.drawCircle(cx0 + i * spacing, cy, dotRadius, p);
            }
        } finally {
            canvas.restore();
        }
    }

    private static double easeInOutBackD(double x) {
        double c1 = 1.70158;
        double c2 = c1 * 1.525;
        return x < 0.5
                ? (Math.pow(2 * x, 2) * ((c2 + 1) * 2 * x - c2)) / 2
                : (Math.pow(2 * x - 2, 2) * ((c2 + 1) * (x * 2 - 2) + c2) + 2) / 2;
    }

    private static double easeOutExpoD(double x) {
        if (x >= 1.0) return 1.0;
        return 1.0 - Math.pow(2, -10.0 * x);
    }

    private static double clampD(double lo, double v, double hi) {
        if (v < lo) return lo;
        return Math.min(v, hi);
    }

    private static int[] wrapStarts(List<Syllable> syls, float[] sylWidths, float maxW) {
        int sz = sylWidths.length;
        if (sz == 0) return new int[]{0, 0};

        java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
        starts.add(0);
        int rowStart = 0;
        int wordStart = 0;
        float curW = 0f;
        for (int i = 0; i < sz; i++) {
            if (i > rowStart && canBreakBefore(syls, i)) wordStart = i;
            if (i > rowStart && curW + sylWidths[i] > maxW) {
                int breakAt = (wordStart > rowStart) ? wordStart : i;
                starts.add(breakAt);
                rowStart = breakAt;
                wordStart = breakAt;
                curW = 0f;
                for (int j = rowStart; j <= i; j++) curW += sylWidths[j];
                continue;
            }
            curW += sylWidths[i];
        }
        starts.add(sz);
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = starts.get(i);
        return arr;
    }

    private static boolean canBreakBefore(List<Syllable> syls, int i) {
        String prev = syls.get(i - 1).text;
        String cur = syls.get(i).text;
        if (prev == null || prev.isEmpty() || cur == null || cur.isEmpty()) return true;
        char last = prev.charAt(prev.length() - 1);
        char first = cur.charAt(0);
        if (Character.isWhitespace(last) || Character.isWhitespace(first)) return true;
        return isWrapCjk(last) || isWrapCjk(first);
    }

    private static void configureForAnimation(Font f) {
        f.setBaselineSnapped(false);
        f.setSubpixel(true);
        f.setHinting(FontHinting.NONE);
        f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
    }

    private static boolean isBackground(LyricLine.VocalChannel ch) {
        return ch == LyricLine.VocalChannel.BACKGROUND
                || ch == LyricLine.VocalChannel.BACKGROUND_LEFT
                || ch == LyricLine.VocalChannel.BACKGROUND_RIGHT;
    }

    private float perCharWidth(String text, Font font) {
        java.util.HashMap<Integer, Float> m = charWidthCache.get(font);
        if (m == null) { m = new java.util.HashMap<>(); charWidthCache.put(font, m); }
        float w = 0f;
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i);
            Float cached = m.get(c);
            if (cached == null) {
                cached = font.measureTextWidth(String.valueOf((char) c));
                m.put(c, cached);
            }
            w += cached;
        }
        return w;
    }

    private float leadingWsWidth(List<Syllable> syls, float[] sylW, int from, int to, Font font) {
        float ws = 0f;
        for (int i = from; i < to; i++) {
            String st = syls.get(i).text;
            if (st == null || st.isEmpty()) continue;
            int k = 0;
            while (k < st.length() && Character.isWhitespace(st.charAt(k))) k++;
            if (k == st.length()) { ws += sylW[i]; continue; }
            if (k > 0) ws += perCharWidth(st.substring(0, k), fontForText(st, font));
            break;
        }
        return ws;
    }

    private static boolean needsKorean(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7FF) || (c >= 0x1100 && c <= 0x11FF)
                    || (c >= 0x3130 && c <= 0x318F) || (c >= 0xA960 && c <= 0xA97F)) {
                return true;
            }
        }
        return false;
    }

    private static Font fontForText(String text, Font base) {
        if (!needsKorean(text)) return base;
        Font ko = Fonts.korean(base);
        return ko != null ? ko : base;
    }

    public void scrollDown(float y) {
        userScrollActive = true;
        userDragging = true;
        userFling = false;
        userReturning = false;
        userScroll = clampScroll(lastScrollY);
        userFlingVel = 0f;
        dragSampleCount = 0;
        addDragSample(y);
        userLastInteractNs = System.nanoTime();
    }

    public void scrollMove(float y) {
        if (!userDragging || dragSampleCount == 0) return;
        float prevY = dragSampleY[dragSampleCount - 1];
        userScroll = clampScroll(userScroll - (y - prevY));
        addDragSample(y);
        userLastInteractNs = System.nanoTime();
    }

    public void scrollUp() {
        if (!userDragging) return;
        userDragging = false;
        userFlingVel = computeFlingVel();
        userFling = Math.abs(userFlingVel) > SCROLL_MIN_FLING;
        userScrollLastNs = System.nanoTime();
        userLastInteractNs = userScrollLastNs;
    }

    public void scrollCancel() {
        scrollUp();
    }

    public long timeAtScreenY(float screenY) {
        int n = lines.size();
        if (n == 0 || lineTopsBuf.length < n
                || cachedLineHeights == null || cachedLineHeights.length < n) {
            return -1L;
        }
        float contentY = screenY - lastCenterY + lastScrollY;
        if (contentY < lineTopsBuf[0]
                || contentY >= lineTopsBuf[n - 1] + cachedLineHeights[n - 1]) {
            return -1L;
        }
        int i = lineIndexAt(lineTopsBuf, n, contentY);
        LyricLine line = lines.get(i);
        if (line.syllables.isEmpty()) return -1L;
        return line.syllables.get(0).startMs;
    }

    private void addDragSample(float y) {
        if (dragSampleCount == SCROLL_VEL_SAMPLES) {
            System.arraycopy(dragSampleNs, 1, dragSampleNs, 0, SCROLL_VEL_SAMPLES - 1);
            System.arraycopy(dragSampleY, 1, dragSampleY, 0, SCROLL_VEL_SAMPLES - 1);
            dragSampleCount--;
        }
        dragSampleNs[dragSampleCount] = System.nanoTime();
        dragSampleY[dragSampleCount] = y;
        dragSampleCount++;
    }

    private float computeFlingVel() {
        if (dragSampleCount < 2) return 0f;
        long newest = dragSampleNs[dragSampleCount - 1];
        int oldest = dragSampleCount - 1;
        for (int i = dragSampleCount - 1; i >= 0; i--) {
            if ((newest - dragSampleNs[i]) / 1_000_000_000f > SCROLL_VEL_WINDOW) break;
            oldest = i;
        }
        float dt = (newest - dragSampleNs[oldest]) / 1_000_000_000f;
        if (dt < 0.001f) return 0f;
        return -(dragSampleY[dragSampleCount - 1] - dragSampleY[oldest]) / dt;
    }

    private float clampScroll(float v) {
        if (v < scrollMin) return scrollMin;
        return Math.min(v, scrollMax);
    }

    private static int lineIndexAt(float[] lineTops, int n, float scroll) {
        if (n <= 1) return 0;
        int lo = 0, hi = n - 1, res = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineTops[mid] <= scroll) {
                res = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return res;
    }

    private float stepUserScroll(float targetScroll, long nowNs, int anchorIdx) {
        if (userDragging) {
            userHoldAnchor = anchorIdx;
            userScrollLastNs = nowNs;
            return userScroll;
        }
        if (userFling) {
            userHoldAnchor = anchorIdx;
            float dt = (nowNs - userScrollLastNs) / 1_000_000_000f;
            userScrollLastNs = nowNs;
            if (dt > 0.05f) dt = 0.05f;
            if (dt > 0f) {
                float next = userScroll + userFlingVel * dt;
                userScroll = clampScroll(next);
                if (userScroll != next) userFlingVel = 0f;
                else userFlingVel = decayVel(userFlingVel, dt);
                if (Math.abs(userFlingVel) < SCROLL_MIN_FLING) userFling = false;
            }
            return userScroll;
        }
        if (userReturning) {
            float scrollY = (float) scrollAnim.animate(targetScroll);
            if (Math.abs(scrollY - targetScroll) < 0.5f) {
                userReturning = false;
                userScrollActive = false;
            }
            return scrollY;
        }
        if ((nowNs - userLastInteractNs) > SCROLL_IDLE_RETURN_NS && anchorIdx != userHoldAnchor) {
            userReturning = true;
            scrollAnim.setValue(userScroll);
            return (float) scrollAnim.animate(targetScroll);
        }
        return userScroll;
    }

    private static float decayVel(float v, float dt) {
        float d = SCROLL_DECEL * dt;
        if (v > 0f) return Math.max(0f, v - d);
        return Math.min(0f, v + d);
    }

    private static float sumWidths(float[] sylWidths, int from, int to) {
        float w = 0f;
        for (int i = from; i < to; i++) w += sylWidths[i];
        return w;
    }

    private static String[] wrapText(String text, Font font, float maxWidth) {
        if (text == null || text.isEmpty()) return new String[]{""};
        font = fontForText(text, font);
        if (font.measureTextWidth(text) <= maxWidth) return new String[]{text};
        java.util.List<String> rows = new java.util.ArrayList<>();
        int n = text.length();
        int lineStart = 0;
        int lastSpace = -1;
        float w = 0f;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            float cw = font.measureTextWidth(String.valueOf(c));
            if (c == ' ') lastSpace = i;
            if (w + cw > maxWidth && i > lineStart) {
                int breakAt = lastSpace > lineStart ? lastSpace : i;
                int nextStart = lastSpace > lineStart ? lastSpace + 1 : i;
                rows.add(text.substring(lineStart, breakAt));
                lineStart = nextStart;
                lastSpace = -1;
                i = nextStart;
                w = 0f;
                continue;
            }
            w += cw;
            i++;
        }
        if (lineStart < n) rows.add(text.substring(lineStart));
        return rows.toArray(new String[0]);
    }

    /** 描边颜色：1=黑 2=白 3=橙 4=红（5=金渐变走 shader） */
    private static int borderColorArgb(int mode) {
        switch (mode) {
            case 2: return 0xFFFFFFFF;
            case 3: return 0xFFFF9800;
            case 4: return 0xFFF44336;
            default: return 0xFF000000;
        }
    }

    /** 金渐变描边 shader（列区域垂直渐变，缓存复用） */
    private Shader borderGradient() {
        if (borderGradientShader != null && borderGradTop == renderTopY && borderGradBottom == renderTopY + renderColH) {
            return borderGradientShader;
        }
        if (borderGradientShader != null) {
            borderGradientShader.close();
        }
        borderGradTop = renderTopY;
        borderGradBottom = renderTopY + renderColH;
        borderGradientShader = Shader.makeLinearGradient(
                0f, renderTopY, 0f, renderTopY + renderColH,
                new int[]{0xFF8D6E63, 0xFFFFB300, 0xFFFFECB3, 0xFFFFB300, 0xFF8D6E63},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f});
        return borderGradientShader;
    }

    /** 带可选描边（纯色/渐变）的文字绘制：先描边层再填充层 */
    private void drawTextBordered(Canvas canvas, String text, float x, float y, Font font, Paint fill) {
        if (borderColor > 0) {
            Paint stroke = LyricSkia.scratchPaint();
            stroke.setStroke(true);
            stroke.setStrokeWidth(Math.max(2f, font.getSize() * 0.1f));
            stroke.setStrokeJoin(PaintStrokeJoin.ROUND);
            stroke.setAntiAlias(true);
            if (borderColor == 5) {
                stroke.setShader(borderGradient());
            } else {
                stroke.setColor(borderColorArgb(borderColor));
            }
            canvas.drawString(text, x, y, font, stroke);
        }
        canvas.drawString(text, x, y, font, fill);
    }

    private void drawSubLine(String text, float leftX, float rightAnchorX, float y,
                             Font font, float alpha, int alignMode) {
        font = fontForText(text, font);
        float w = font.measureTextWidth(text);
        float x;
        if (alignMode == 2) {
            x = Math.max(leftX, rightAnchorX - w);                      // 右：锚主行右缘
        } else if (alignMode == 1) {
            x = Math.max(leftX, leftX + (rightAnchorX - leftX - w) * 0.5f);  // 中：主行范围中心
        } else {
            x = leftX;                                                  // 左
        }
        Paint paint = LyricSkia.scratchPaint();
        paint.setColor(0xFFE6E6E6);
        paint.setAlphaf(alpha);
        paint.setAntiAlias(true);
        drawTextBordered(LyricSkia.getCanvas(), text, x, y, font, paint);
    }

    private int findActiveGroup(long pos) {
        int lo = 0, hi = groups.size() - 1, res = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (groups.get(mid).startMs <= pos) {
                res = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return res;
    }

    private void drawSyllableRange(List<Syllable> syllables, float[] sylWidths, int from, int to,
                                   float startX, float baselineY, Font font,
                                   float ascent, float descent, long pos,
                                   float baseAlpha, float activeK, boolean enableLift, boolean spring,
                                   boolean glowOn) {
        if (from >= to) return;
        Canvas canvas = LyricSkia.getCanvas();

        if (activeK <= 0.001f) {
            float x = startX;
            Paint paint = LyricSkia.scratchPaint();
            paint.setColor(0xFFFFFFFF);
            paint.setAlphaf(baseAlpha);
            paint.setAntiAlias(true);
            for (int i = from; i < to; i++) {
                String st = syllables.get(i).text;
                drawTextBordered(canvas, st, x, baselineY, fontForText(st, font), paint);
                x += sylWidths[i];
            }
            return;
        }

        int n = to - from;
        if (sylLeftBuf.length < n + 1) sylLeftBuf = new float[n + 1];
        float[] sylLeft = sylLeftBuf;
        sylLeft[0] = startX;
        for (int i = 0; i < n; i++) {
            sylLeft[i + 1] = sylLeft[i] + sylWidths[from + i];
        }
        float rowRightX = sylLeft[n];
        if (rowRightX - startX <= 0f) return;

        // 非逐字歌词线性动画：行内音节无不同起始时间（非逐字歌词）且开关关闭时，
        // 整行一次性点亮（不做逐字 sweep/上抬），否则保持逐字线性动画。
        boolean lineTimed = lineHasTiming(syllables, from, to);
        boolean liftEnabled = enableLift
                && (lineTimed || Boolean.TRUE.equals(LyricConfig.instance.lyricLinearAnim.getValue()));

        float sweepX = liftEnabled ? computeSweepX(syllables, from, to, sylLeft, pos) : 0f;

        Paint layerPaint = lyricLayerPaint;
        layerPaint.setAlphaf(baseAlpha);
        canvas.saveLayer(
                startX - 8f,
                baselineY + ascent - LIFT_PEAK_PX - 8f,
                rowRightX + 8f,
                baselineY + descent + 8f,
                layerPaint);
        try {
            if (liftBuf.length < n) liftBuf = new float[n];
            if (glowAlphaBuf.length < n) glowAlphaBuf = new float[n];
            boolean anyGlow = false;
            for (int s = 0; s < n; s++) {
                Syllable syl = syllables.get(from + s);
                long sStart = syl.startMs;

                float k;
                if (spring) {
                    k = liftSpringK((pos - sStart) / 1000.0);
                } else {
                    long ownEnd = sStart + Math.max(0L, syl.durationMs);
                    long effDur = Math.max(LIFT_MIN_DURATION_MS, ownEnd - sStart);
                    float t;
                    if (pos <= sStart) t = 0f;
                    else if (pos >= sStart + effDur) t = 1f;
                    else t = (pos - sStart) / (float) effDur;
                    k = 1f - (1f - t) * (1f - t) * (1f - t);
                }
                liftBuf[s] = liftEnabled ? -LIFT_PEAK_PX * k * activeK : 0f;
                float ga = (liftEnabled && glowOn) ? activeK * k * GLOW_ALPHA : 0f;
                glowAlphaBuf[s] = ga;
                if (ga > 0.01f) anyGlow = true;
            }

            // 歌词阴影：与 glow 同款 saveLayer 模式，restore 时对整行一次性应用
            // makeDropShadow(偏移 2px, 模糊 1.5σ)，不按音节逐个应用滤镜。
            if (Boolean.TRUE.equals(LyricConfig.instance.lyricShadow.getValue())) {
                canvas.saveLayer(
                        startX - 8f,
                        baselineY + ascent - LIFT_PEAK_PX - 8f,
                        rowRightX + 8f,
                        baselineY + descent + 16f,
                        shadowLayerPaint);
                glowGlyphPaint.setColor(0xFFFFFFFF);
                glowGlyphPaint.setAlphaf(1f);
                for (int s = 0; s < n; s++) {
                    String st = syllables.get(from + s).text;
                    canvas.drawString(st, sylLeft[s], baselineY + liftBuf[s], fontForText(st, font), glowGlyphPaint);
                }
                canvas.restore();
            }

            if (anyGlow) {
                canvas.saveLayer(
                        startX - 8f,
                        baselineY + ascent - LIFT_PEAK_PX - 8f,
                        rowRightX + 8f,
                        baselineY + descent + 8f,
                        glowLayerPaint);
                glowGlyphPaint.setColor(0xFFFFFFFF);
                for (int s = 0; s < n; s++) {
                    if (glowAlphaBuf[s] <= 0.01f) continue;
                    String st = syllables.get(from + s).text;
                    glowGlyphPaint.setAlphaf(glowAlphaBuf[s]);
                    canvas.drawString(st, sylLeft[s], baselineY + liftBuf[s], fontForText(st, font), glowGlyphPaint);
                }
                canvas.restore();
            }

            Paint textPaint = LyricSkia.scratchPaint();
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setAlphaf(1f);
            textPaint.setAntiAlias(true);
            for (int s = 0; s < n; s++) {
                String st = syllables.get(from + s).text;
                drawTextBordered(canvas, st, sylLeft[s], baselineY + liftBuf[s], fontForText(st, font), textPaint);
            }

            if (liftEnabled) {
                float maskDark = 1f - (1f - DARK_MASK_ALPHA) * activeK;
                applySweepMask(canvas, sweepX, maskDark);
            }
        } finally {
            canvas.restore();
        }
    }

    /** 该行是否有逐字时间（音节起始时间不都相同）；无 → 非逐字歌词行 */
    private static boolean lineHasTiming(List<Syllable> syllables, int from, int to) {
        long prev = Long.MIN_VALUE;
        for (int i = from; i < to; i++) {
            long t = syllables.get(i).startMs;
            if (prev != Long.MIN_VALUE && t != prev) return true;
            prev = t;
        }
        return false;
    }

    private static float computeSweepX(List<Syllable> syllables, int from, int to,
                                       float[] sylLeft, long pos) {
        int n = to - from;
        long firstStart = syllables.get(from).startMs;
        Syllable last = syllables.get(to - 1);
        long lastEnd = last.startMs + Math.max(0L, last.durationMs);

        if (pos < firstStart) return sylLeft[0] - SWEEP_FADE_PX * 2f;
        if (pos >= lastEnd) return sylLeft[n] + SWEEP_FADE_PX * 2f;

        for (int s = 0; s < n; s++) {
            Syllable syl = syllables.get(from + s);
            long sStart = syl.startMs;
            long sEnd = (s + 1 < n)
                    ? syllables.get(from + s + 1).startMs
                    : syl.startMs + Math.max(0L, syl.durationMs);
            if (sEnd <= sStart) sEnd = sStart + 1L;
            if (pos < sStart) return sylLeft[s];
            if (pos < sEnd) {
                float frac = (pos - sStart) / (float) (sEnd - sStart);
                float w = sylLeft[s + 1] - sylLeft[s];
                return sylLeft[s] + w * frac;
            }
        }
        return sylLeft[n] + SWEEP_FADE_PX * 2f;
    }

    private void applySweepMask(Canvas canvas, float sweepX, float maskDark) {
        if (sweepShader == null || sweepShaderDark != maskDark) {
            if (sweepShader != null) sweepShader.close();
            int dark = ((int) (maskDark * 255f) << 24) | 0x00FFFFFF;
            sweepColors[0] = 0xFFFFFFFF;
            sweepColors[1] = dark;
            sweepStops[0] = 0f;
            sweepStops[1] = 1f;
            sweepShader = Shader.makeLinearGradient(0f, 0f, SWEEP_FADE_PX, 0f, sweepColors, sweepStops);
            sweepShaderDark = maskDark;
        }
        sweepPaint.setShader(sweepShader);
        sweepPaint.setBlendMode(BlendMode.DST_IN);
        canvas.save();
        canvas.translate(sweepX - SWEEP_FADE_PX * 0.5f, 0f);
        canvas.drawRect(sweepBigRect, sweepPaint);
        canvas.restore();
        sweepPaint.setShader(null);
    }

    private void stepLineSpring(int i, float target, double dt, double stiffness, double damping) {
        double value = lineCurTop[i];
        double vel = lineVelTop[i];
        int steps = 1 + (int) (dt / 0.008);
        double sub = dt / steps;
        for (int s = 0; s < steps; s++) {
            double a = -stiffness * (value - target) - damping * vel;
            vel += a * sub;
            value += vel * sub;
        }
        if (Math.abs(vel) < 0.01 && Math.abs(value - target) < 0.05) {
            value = target;
            vel = 0.0;
        }
        lineCurTop[i] = (float) value;
        lineVelTop[i] = (float) vel;
    }

    private static float liftSpringK(double tau) {
        if (tau <= 0.0) return 0f;
        double zw = LIFT_ZETA * LIFT_OMEGA0;
        double wd = LIFT_OMEGA0 * Math.sqrt(1.0 - LIFT_ZETA * LIFT_ZETA);
        double env = Math.exp(-zw * tau);
        double y = 1.0 - env * (Math.cos(wd * tau) + (zw / wd) * Math.sin(wd * tau));
        if (y < 0.0) y = 0.0;
        return (float) y;
    }

    private static float smoothstep(float a, float b, float x) {
        if (b <= a) return x < a ? 0f : 1f;
        float t = (x - a) / (b - a);
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    private static float computeBgScaleK(long positionMs, LineGroup g) {
        long popStart = g.startMs + BG_POP_IN_DELAY_MS;
        if (positionMs < popStart) return 0f;
        if (positionMs < popStart + BG_POP_IN_MS) {
            float k = (positionMs - popStart) / (float) BG_POP_IN_MS;
            return easeOutBackSmall(k);
        }
        if (positionMs < g.endMs) return 1f;
        float dt = (positionMs - g.endMs) / (float) BG_POP_OUT_MS;
        if (dt >= 1f) return 0f;
        return 1f - smoothstep(0f, 1f, dt);
    }

    private static float easeOutBackSmall(float x) {
        float c1 = 1.7f;
        float c3 = c1 + 1f;
        float t = x - 1f;
        return 1f + c3 * t * t * t + c1 * t * t;
    }

    private static float computeActiveK(long positionMs, LineGroup g) {
        if (positionMs < g.startMs - ACTIVE_FADE_IN_MS) return 0f;
        if (positionMs < g.startMs) {
            float dt = (positionMs - (g.startMs - ACTIVE_FADE_IN_MS))
                    / (float) ACTIVE_FADE_IN_MS;
            return smoothstep(0f, 1f, dt);
        }
        if (positionMs < g.endMs) return 1f;
        float dt = (positionMs - g.endMs) / (float) ACTIVE_FADE_OUT_MS;
        return 1f - smoothstep(0f, 1f, dt);
    }
}
