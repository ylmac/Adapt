package com.ylmac.adapt.render;

import io.github.timer_err.qml4j.runtime.color.Hct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import io.github.timer_err.qml4j.runtime.color.StyleManager;

/**
 * Pure-Java port of the PixelPlayer (album-art) seed-color extraction pipeline,
 * retargeted at world-screenshot pixels:
 *
 *   QuantizerCelebi (Wu quantizer + histogram map)  →  HCT scoring
 *   (hue-excited proportions, chroma/tone scoring, representative-color
 *   fidelity, excess-chroma penalty)  →  local refinement → seed ARGB.
 *
 * The Android material-color-utilities Hct/Cam16 are replaced by the qml4j
 * runtime implementation (io.github.timer_err.qml4j.runtime.color.Hct), which
 * is plain double math on the same CAM16 viewing conditions — safe to run on
 * any thread, no engine instance needed.
 */
public final class MonetQuantizer {

    private MonetQuantizer() {}

    // ------------------------------------------------------------------
    //  Public entry points
    // ------------------------------------------------------------------

    /**
     * Reduce ARGB pixels to the seed color used to generate the MD3 scheme.
     * Mirrors ColorRoles.selectSeedColorArgbFromPixels(pixels, config).
     *
     * @param argbPixels     0xAARRGGBB pixels (non-empty)
     * @param accuracy       0..1; 1 = PixelPlayer "accurate" extraction mode
     * @return the seed ARGB
     */
    public static int extractSeedColorArgb(int[] argbPixels, double accuracy) {
        if (argbPixels == null || argbPixels.length == 0) return DEFAULT_SEED_ARGB;

        double acc = accuracy <= 0 ? 1.0 : Math.min(1.0, accuracy);
        int fallbackArgb = averageColorArgb(argbPixels);
        Map<Integer, Integer> quantized = quantizeCelebi(argbPixels, QUANTIZER_MAX_COLORS);
        if (quantized.isEmpty()) return fallbackArgb;

        boolean mostlyNeutral = isMostlyNeutralArtwork(quantized);
        if (mostlyNeutral && isArgbNearGrayscale(fallbackArgb)) {
            return fallbackArgb;
        }

        RepresentativeArtworkColor representative = calculateRepresentativeArtworkColor(argbPixels, acc);
        List<Integer> rankedSeeds = scoreQuantizedColors(
            quantized, fallbackArgb, representative, acc);
        if (rankedSeeds.isEmpty()) return fallbackArgb;
        int selectedSeed = rankedSeeds.get(0);

        return refineSeedColorArgb(
            selectedSeed, argbPixels, representative, CUTOFF_CHROMA, acc);
    }

    /** @return the seed as {hue, chroma, tone} (qml4j HCT space), ready for StyleManager.setSeedColorHct. */
    public static double[] extractSeedColorHct(int[] argbPixels, double accuracy) {
        int argb = extractSeedColorArgb(argbPixels, accuracy);
        Hct hct = Hct.fromInt(argb);
        return new double[] { hct.getHue(), hct.getChroma(), hct.getTone() };
    }

    private static final int DEFAULT_SEED_ARGB = 0xFF6750A4;

    // ------------------------------------------------------------------
    //  QuantizerCelebi  (Wu quantizer + quantizer map)
    // ------------------------------------------------------------------

    private static final int QUANTIZER_MAX_COLORS = 128;

    /** Port of QuantizerCelebi.quantize(pixels, maxColors) -> Map<ARGB, population>. */
    public static Map<Integer, Integer> quantizeCelebi(int[] pixels, int maxColors) {
        Map<Integer, Integer> wuResult = quantizeWu(pixels, Math.max(2, Math.min(maxColors, 256)));
        return quantizerMap(wuResult);
    }

    /** Port of QuantizerMap: histogram of the Wu palette. */
    private static Map<Integer, Integer> quantizerMap(Map<Integer, Integer> colorToCount) {
        Map<Integer, Integer> histogram = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : colorToCount.entrySet()) {
            histogram.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        return histogram;
    }

    // ------------------------------------------------------------------
    //  Wu quantizer
    // ------------------------------------------------------------------
    // Modified Median Cut over a 3D (32×32×32) histogram of 5-bit per-channel
    // buckets, using 3D inclusive prefix sums for O(1) box moments. The
    // implementation is written from Wu's paper (Efficient Statistical
    // Computations for Optimal Color Quantization) rather than transcribed
    // from the Android wheelhouse — same math, independently indexed.

    private static final int INDEX_BITS = 5;              // per-channel bucket bits
    private static final int BUCKETS = 1 << INDEX_BITS;   // 32 buckets per channel (0..31)
    private static final int INDEX_COUNT = 1 << (INDEX_BITS + 1); // 64 array slots per axis (slack)
    private static final int TOTAL_COUNT = INDEX_COUNT * INDEX_COUNT * INDEX_COUNT;

    private static Map<Integer, Integer> quantizeWu(int[] pixels, int maxColors) {
        int[] weights = new int[TOTAL_COUNT];
        int[] momentsR = new int[TOTAL_COUNT];
        int[] momentsG = new int[TOTAL_COUNT];
        int[] momentsB = new int[TOTAL_COUNT];
        double[] moments = new double[TOTAL_COUNT];

        // 1. Histogram. Pixel channel >> 3 ∈ [0, 31]; prefix coord = bucket + 1.
        for (int pixel : pixels) {
            int r = (pixel >>> 16) & 0xFF;
            int g = (pixel >>> 8) & 0xFF;
            int b = pixel & 0xFF;
            int idx = index1((r >>> 3), (g >>> 3), (b >>> 3));
            weights[idx]++;
            momentsR[idx] += r;
            momentsG[idx] += g;
            momentsB[idx] += b;
            moments[idx] += (double) r * r + (double) g * g + (double) b * b;
        }

        // 2. Inclusive 3D prefix sums per observation axis, in order r, g, b.
        prefixAxis(weights, 2);
        prefixAxis(weights, 1);
        prefixAxis(weights, 0);
        prefixAxis(momentsR, 2); prefixAxis(momentsR, 1); prefixAxis(momentsR, 0);
        prefixAxis(momentsG, 2); prefixAxis(momentsG, 1); prefixAxis(momentsG, 0);
        prefixAxis(momentsB, 2); prefixAxis(momentsB, 1); prefixAxis(momentsB, 0);
        prefixAxisD(moments, 2); prefixAxisD(moments, 1); prefixAxisD(moments, 0);

        // 3. Split boxes by maximum variance reduction.
        List<WuBox> boxes = createBoxes(maxColors, weights, momentsR, momentsG, momentsB, moments);

        // 4. Average color + population per box.
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (WuBox box : boxes) {
            int w = boxWeight(box, weights);
            if (w <= 0) continue;
            int r = (int) Math.round(boxMoment(box, momentsR, w));
            int g = (int) Math.round(boxMoment(box, momentsG, w));
            int b = (int) Math.round(boxMoment(box, momentsB, w));
            int argb = 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            result.merge(argb, w, Integer::sum);
        }
        return result;
    }

    /** index1(bucket, ...) = prefix coordinate index (bucket coord + 1) for the arrays. */
    private static int index1(int r, int g, int b) {
        // bucket coord ∈ [0,31]; prefix coord = +1 → r,g,b ∈ [1,32].
        return (r + 1) * INDEX_COUNT * INDEX_COUNT + (g + 1) * INDEX_COUNT + (b + 1);
    }

    private static int flatIndex(int x, int y, int z) { // prefix coords, zero-range safe at 0
        return x * INDEX_COUNT * INDEX_COUNT + y * INDEX_COUNT + z;
    }

    /** Inclusive prefix sum along one axis: axis 2 = r (fastest-scan stride R²), 1 = g, 0 = b. */
    private static void prefixAxis(int[] a, int axis) {
        int n = INDEX_COUNT;
        int rCount = n, gCount = n, bCount = n; // iterate in canonical order
        for (int r = 0; r < rCount; r++) {
            int rb = r * n * n;
            for (int g = 0; g < gCount; g++) {
                int rg = rb + g * n;
                for (int b = 0; b < bCount; b++) {
                    int idx = rg + b;
                    if (axis == 2 && r > 0) a[idx] += a[idx - n * n];
                    if (axis == 1 && g > 0) a[idx] += a[idx - n];
                    if (axis == 0 && b > 0) a[idx] += a[idx - 1];
                }
            }
        }
    }

    private static void prefixAxisD(double[] a, int axis) {
        int n = INDEX_COUNT;
        for (int r = 0; r < n; r++) {
            int rb = r * n * n;
            for (int g = 0; g < n; g++) {
                int rg = rb + g * n;
                for (int b = 0; b < n; b++) {
                    int idx = rg + b;
                    if (axis == 2 && r > 0) a[idx] += a[idx - n * n];
                    if (axis == 1 && g > 0) a[idx] += a[idx - n];
                    if (axis == 0 && b > 0) a[idx] += a[idx - 1];
                }
            }
        }
    }

    /** Box in prefix-coord space: [r0, r1) × [g0, g1) × [b0, b1). */
    private static final class WuBox {
        int r0, r1, g0, g1, b0, b1;
        int weight;      // population
        double variance; // variance magnitude (used by the priority queue)

        WuBox(int r0, int r1, int g0, int g1, int b0, int b1) {
            this.r0 = r0; this.r1 = r1;
            this.g0 = g0; this.g1 = g1;
            this.b0 = b0; this.b1 = b1;
        }
    }

    /** Sum of the array over the box range [lo, hi) per axis (8-term inclusion-exclusion). */
    private static int boxSum(int[] a, WuBox box) {
        return a[flatIndex(box.r1, box.g1, box.b1)]
                - a[flatIndex(box.r1, box.g1, box.b0)]
                - a[flatIndex(box.r1, box.g0, box.b1)]
                - a[flatIndex(box.r0, box.g1, box.b1)]
                + a[flatIndex(box.r0, box.g0, box.b1)]
                + a[flatIndex(box.r0, box.g1, box.b0)]
                + a[flatIndex(box.r1, box.g0, box.b0)]
                - a[flatIndex(box.r0, box.g0, box.b0)];
    }

    private static double boxSumD(double[] a, WuBox box) {
        return a[flatIndex(box.r1, box.g1, box.b1)]
                - a[flatIndex(box.r1, box.g1, box.b0)]
                - a[flatIndex(box.r1, box.g0, box.b1)]
                - a[flatIndex(box.r0, box.g1, box.b1)]
                + a[flatIndex(box.r0, box.g0, box.b1)]
                + a[flatIndex(box.r0, box.g1, box.b0)]
                + a[flatIndex(box.r1, box.g0, box.b0)]
                - a[flatIndex(box.r0, box.g0, box.b0)];
    }

    private static int boxWeight(WuBox box, int[] weights) { return boxSum(weights, box); }

    private static double boxMoment(WuBox box, int[] moment, int weight) {
        return weight == 0 ? 0 : (double) boxSum(moment, box) / weight;
    }

    /** Sum of squared distances proxy: S2 − (Sr²+Sg²+Sb²)/W (≥ 0). */
    private static double boxVarianceMagnitude(WuBox box, int[] w, int[] mr, int[] mg, int[] mb, double[] m2) {
        int weight = boxWeight(box, w);
        if (weight <= 0) return 0.0;
        double s2 = boxSumD(m2, box);
        double sr = boxSum(mr, box);
        double sg = boxSum(mg, box);
        double sb = boxSum(mb, box);
        return s2 - (sr * sr + sg * sg + sb * sb) / weight;
    }

    private static List<WuBox> createBoxes(int maxColors, int[] w, int[] mr, int[] mg, int[] mb, double[] m2) {
        // Initial box covers every bucket: prefix coord for bucket b is b+1, so the
        // bucket range [0, 31] maps to the prefix range [0, BUCKETS+1) = [0, 33).
        WuBox first = new WuBox(0, BUCKETS + 1, 0, BUCKETS + 1, 0, BUCKETS + 1);
        first.weight = boxWeight(first, w);
        first.variance = boxVarianceMagnitude(first, w, mr, mg, mb, m2);

        List<WuBox> result = new ArrayList<>(maxColors);
        PriorityQueue<WuBox> pq = new PriorityQueue<>((x, y) -> {
            int c = Double.compare(y.variance, x.variance);
            return c != 0 ? c : Integer.compare(y.weight, x.weight);
        });
        pq.add(first);

        while (result.size() < maxColors && !pq.isEmpty()) {
            WuBox box = pq.poll();
            if (box.weight <= 0) continue;

            IntPoint best = bestAxisCut(box, w, mr, mg, mb, m2);
            if (best == null) {
                // No interior cut holds pixels on both sides: keep whole.
                result.add(box);
                continue;
            }
            int axis = best.x, cut = best.y;

            WuBox lo = new WuBox(box.r0, box.r1, box.g0, box.g1, box.b0, box.b1);
            WuBox hi = new WuBox(box.r0, box.r1, box.g0, box.g1, box.b0, box.b1);
            if (axis == AXIS_R) { lo.r1 = cut; hi.r0 = cut; }
            else if (axis == AXIS_G) { lo.g1 = cut; hi.g0 = cut; }
            else { lo.b1 = cut; hi.b0 = cut; }

            lo.weight = boxWeight(lo, w);
            hi.weight = boxWeight(hi, w);
            if (lo.weight <= 0 || hi.weight <= 0) {
                result.add(box);
                continue;
            }
            lo.variance = boxVarianceMagnitude(lo, w, mr, mg, mb, m2);
            hi.variance = boxVarianceMagnitude(hi, w, mr, mg, mb, m2);
            pq.add(lo);
            pq.add(hi);
        }
        return result;
    }

    private static final int AXIS_R = 0;
    private static final int AXIS_G = 1;
    private static final int AXIS_B = 2;

    private static final class IntPoint {
        final int x, y;
        IntPoint(int x, int y) { this.x = x; this.y = y; }
    }

    /**
     * Best split along each axis: scans every interior cut, minimizing
     * varMagnitude(lo) + varMagnitude(hi) (equivalently maximizing the variance
     * reduction). Returns (axis, cut) or null when no cut separates pixels.
     */
    private static IntPoint bestAxisCut(WuBox box, int[] w, int[] mr, int[] mg, int[] mb, double[] m2) {
        double bestScore = Double.POSITIVE_INFINITY;
        int bestAxis = -1, bestCut = -1;

        // Sweep a box whose "cut-axis" upper bound advances; the other two axes
        // stay at the full box range, so its sums are the low-side partial sums.
        WuBox sweep = new WuBox(box.r0, box.r1, box.g0, box.g1, box.b0, box.b1);
        for (int axis = 0; axis < 3; axis++) {
            int lo0, hi0;
            if (axis == AXIS_R) { lo0 = box.r0; hi0 = box.r1; }
            else if (axis == AXIS_G) { lo0 = box.g0; hi0 = box.g1; }
            else { lo0 = box.b0; hi0 = box.b1; }
            if (hi0 - lo0 <= 1) continue; // nothing to cut

            for (int cut = lo0 + 1; cut < hi0; cut++) {
                if (axis == AXIS_R) sweep.r1 = cut;
                else if (axis == AXIS_G) sweep.g1 = cut;
                else sweep.b1 = cut;

                int wl = boxWeight(sweep, w);
                if (wl <= 0) continue;
                int wr = box.weight - wl;
                if (wr <= 0) continue;

                double s2l = boxSumD(m2, sweep);
                double srl = boxSum(mr, sweep), sgl = boxSum(mg, sweep), sbl = boxSum(mb, sweep);

                double s2r = boxSumD(m2, box) - s2l;
                double srr = boxSum(mr, box) - srl, sgr = boxSum(mg, box) - sgl, sbr = boxSum(mb, box) - sbl;

                double score = (s2l - (srl * srl + sgl * sgl + sbl * sbl) / wl)
                             + (s2r - (srr * srr + sgr * sgr + sbr * sbr) / wr);
                if (score < bestScore) {
                    bestScore = score;
                    bestAxis = axis;
                    bestCut = cut;
                }
            }
        }
        return bestAxis < 0 ? null : new IntPoint(bestAxis, bestCut);
    }

    // ------------------------------------------------------------------
    //  Scoring (ColorRoles.kt port, accurate-accuracy defaults)
    // ------------------------------------------------------------------

    // ColorScoringConfig (PixelPlayer defaults)
    private static final double TARGET_CHROMA = 48.0;
    private static final double WEIGHT_PROPORTION = 0.7;
    private static final double WEIGHT_CHROMA_ABOVE = 0.3;
    private static final double WEIGHT_CHROMA_BELOW = 0.1;
    private static final double CUTOFF_CHROMA = 5.0;
    private static final double CUTOFF_EXCITED_PROPORTION = 0.01;
    private static final int MAX_COLOR_COUNT = 4;
    private static final int MAX_HUE_DIFFERENCE = 90;
    private static final int MIN_HUE_DIFFERENCE = 15;

    // Neutral-artwork heuristics
    private static final double GRAYSCALE_CHROMA_THRESHOLD = 12.0;
    private static final double NEUTRAL_PIXEL_CHROMA_THRESHOLD = 8.0;
    private static final double HIGH_CHROMA_THRESHOLD = 18.0;
    private static final double REQUIRED_NEUTRAL_POPULATION = 0.92;
    private static final double MAX_HIGH_CHROMA_POPULATION = 0.03;
    private static final double MAX_WEIGHTED_CHROMA_FOR_NEUTRAL = 9.0;
    private static final int MAX_GRAYSCALE_CHANNEL_DELTA = 10;
    private static final double REPRESENTATIVE_PIXEL_CHROMA_THRESHOLD = 10.0;
    private static final double MIN_REPRESENTATIVE_PIXEL_RATIO = 0.04;
    private static final double MIN_REFINEMENT_PIXEL_RATIO = 0.08;
    private static final int MIN_VISIBLE_RGB_SUM = 36;
    private static final int MIN_VISIBLE_PIXEL_ALPHA = 28;
    private static final double FIDELITY_HUE_WINDOW = 90.0;
    private static final double FIDELITY_CHROMA_WINDOW = 32.0;
    private static final double FIDELITY_TONE_WINDOW = 28.0;
    private static final double FIDELITY_HUE_WEIGHT = 18.0;
    private static final double FIDELITY_CHROMA_WEIGHT = 7.0;
    private static final double FIDELITY_TONE_WEIGHT = 3.0;
    private static final double ACCURATE_FIDELITY_HUE_WINDOW = 52.0;
    private static final double ACCURATE_FIDELITY_CHROMA_WINDOW = 18.0;
    private static final double ACCURATE_FIDELITY_TONE_WINDOW = 18.0;
    private static final double ACCURATE_FIDELITY_HUE_WEIGHT = 28.0;
    private static final double ACCURATE_FIDELITY_CHROMA_WEIGHT = 14.0;
    private static final double ACCURATE_FIDELITY_TONE_WEIGHT = 6.0;
    private static final double EXCESS_CHROMA_PENALTY_START = 18.0;
    private static final double EXCESS_CHROMA_PENALTY_WEIGHT = 0.18;
    private static final double ACCURATE_EXCESS_CHROMA_PENALTY_START = 8.0;
    private static final double ACCURATE_EXCESS_CHROMA_PENALTY_WEIGHT = 0.38;
    private static final double LOCAL_REFINEMENT_HUE_WINDOW = 32.0;
    private static final double LOCAL_REFINEMENT_BLEND_RATIO = 0.42f;
    private static final double ACCURATE_LOCAL_REFINEMENT_HUE_WINDOW = 18.0;
    private static final double ACCURATE_LOCAL_REFINEMENT_BLEND_RATIO = 0.72f;
    private static final double ACCURATE_REPRESENTATIVE_BLEND_RATIO = 0.42f;
    private static final double ACCURATE_REPRESENTATIVE_PIXEL_CHROMA_THRESHOLD = 6.0;
    private static final double ACCURATE_CHROMA_ABOVE_WEIGHT = 0.14;
    private static final double ACCURATE_CHROMA_BELOW_WEIGHT = 0.04;
    private static final double ACCURATE_CUTOFF_CHROMA = 3.5;
    private static final double ACCURATE_MAX_HUE_DIFFERENCE = 64;
    private static final double ACCURATE_MIN_HUE_DIFFERENCE = 10;

    private static final class ScoredHct {
        final Hct hct;
        final double score;
        ScoredHct(Hct hct, double score) { this.hct = hct; this.score = score; }
    }

    private static final class RepresentativeArtworkColor {
        final int argb;
        final Hct hct;
        RepresentativeArtworkColor(int argb, Hct hct) { this.argb = argb; this.hct = hct; }
    }

    private static List<Integer> scoreQuantizedColors(
            Map<Integer, Integer> colorsToPopulation,
            int fallbackColorArgb,
            RepresentativeArtworkColor representative,
            double accuracy) {
        if (colorsToPopulation.isEmpty()) {
            List<Integer> f = new ArrayList<>(1);
            f.add(fallbackColorArgb);
            return f;
        }

        List<Hct> colorsHct = new ArrayList<>(colorsToPopulation.size());
        int[] huePopulation = new int[360];
        double populationSum = 0.0;

        for (Map.Entry<Integer, Integer> e : colorsToPopulation.entrySet()) {
            int population = e.getValue();
            if (population <= 0) continue;
            Hct hct = Hct.fromInt(e.getKey());
            colorsHct.add(hct);
            huePopulation[sanitizeDegreesInt((int) Math.floor(hct.getHue()))] += population;
            populationSum += population;
        }

        if (populationSum <= 0.0) {
            List<Integer> f = new ArrayList<>(1);
            f.add(fallbackColorArgb);
            return f;
        }

        double effectiveCutoffChroma = lerpDouble(CUTOFF_CHROMA, ACCURATE_CUTOFF_CHROMA, accuracy);
        double effectiveTargetChroma = representative != null
            ? lerpDouble(TARGET_CHROMA, clampDouble(representative.hct.getChroma(), 12.0, 72.0), accuracy * 0.92)
            : TARGET_CHROMA;
        double chromaAboveWeight = lerpDouble(WEIGHT_CHROMA_ABOVE, ACCURATE_CHROMA_ABOVE_WEIGHT, accuracy);
        double chromaBelowWeight = lerpDouble(WEIGHT_CHROMA_BELOW, ACCURATE_CHROMA_BELOW_WEIGHT, accuracy);

        // Hood-averaged hue-excited population (each hue spreads ±15 to its neighbors).
        double[] hueExcitedProportions = new double[360];
        for (int hue = 0; hue < 360; hue++) {
            double proportion = huePopulation[hue] / populationSum;
            for (int neighbor = hue - 14; neighbor <= hue + 15; neighbor++) {
                hueExcitedProportions[sanitizeDegreesInt(neighbor)] += proportion;
            }
        }

        List<ScoredHct> scoredColors = new ArrayList<>(colorsHct.size());
        for (Hct hct : colorsHct) {
            int hue = sanitizeDegreesInt((int) Math.round(hct.getHue()));
            double excitedProportion = hueExcitedProportions[hue];
            if (hct.getChroma() < effectiveCutoffChroma || excitedProportion <= CUTOFF_EXCITED_PROPORTION) {
                continue;
            }

            double proportionScore = excitedProportion * 100.0 * WEIGHT_PROPORTION;
            double chromaWeight = hct.getChroma() < effectiveTargetChroma ? chromaBelowWeight : chromaAboveWeight;
            double chromaScore = (hct.getChroma() - effectiveTargetChroma) * chromaWeight;
            double fidelityScore = representative != null
                ? calculateRepresentativeFidelityScore(hct, representative.hct, accuracy) : 0.0;
            double excessChromaPenalty = representative != null
                ? calculateExcessChromaPenalty(hct, representative.hct, accuracy) : 0.0;
            scoredColors.add(new ScoredHct(hct, proportionScore + chromaScore + fidelityScore - excessChromaPenalty));
        }

        if (scoredColors.isEmpty()) {
            List<Integer> f = new ArrayList<>(1);
            f.add(fallbackColorArgb);
            return f;
        }
        scoredColors.sort((a, b) -> Double.compare(b.score, a.score));

        int minHueDifference = (int) Math.round(lerpDouble(MIN_HUE_DIFFERENCE, ACCURATE_MIN_HUE_DIFFERENCE, accuracy));
        if (minHueDifference < 1) minHueDifference = 1;
        int maxHueDifference = (int) Math.round(lerpDouble(MAX_HUE_DIFFERENCE, ACCURATE_MAX_HUE_DIFFERENCE, accuracy));
        if (maxHueDifference < minHueDifference) maxHueDifference = minHueDifference;
        int desiredColorCount = Math.max(1, MAX_COLOR_COUNT);

        List<Hct> chosen = new ArrayList<>();
        for (int differenceDegrees = maxHueDifference; differenceDegrees >= minHueDifference; differenceDegrees--) {
            chosen.clear();
            for (ScoredHct candidate : scoredColors) {
                boolean isDuplicateHue = false;
                for (Hct hct : chosen) {
                    if (differenceDegrees(hct.getHue(), candidate.hct.getHue()) < differenceDegrees) {
                        isDuplicateHue = true;
                        break;
                    }
                }
                if (!isDuplicateHue) chosen.add(candidate.hct);
                if (chosen.size() >= desiredColorCount) break;
            }
            if (chosen.size() >= desiredColorCount) break;
        }

        if (chosen.isEmpty()) {
            List<Integer> f = new ArrayList<>(1);
            f.add(fallbackColorArgb);
            return f;
        }
        List<Integer> out = new ArrayList<>(chosen.size());
        for (Hct hct : chosen) out.add(hct.toInt());
        return out;
    }

    private static RepresentativeArtworkColor calculateRepresentativeArtworkColor(int[] pixels, double accuracy) {
        if (pixels.length == 0) return null;

        double totalRed = 0.0, totalGreen = 0.0, totalBlue = 0.0, totalWeight = 0.0;
        int representativePixelCount = 0;
        double chromaThreshold = lerpDouble(
            REPRESENTATIVE_PIXEL_CHROMA_THRESHOLD, ACCURATE_REPRESENTATIVE_PIXEL_CHROMA_THRESHOLD, accuracy);
        double chromaWeightMultiplier = lerpDouble(1.0, 0.42, accuracy);

        for (int argb : pixels) {
            int alpha = (argb >>> 24) & 0xFF;
            if (alpha < MIN_VISIBLE_PIXEL_ALPHA) continue;
            int red = (argb >>> 16) & 0xFF;
            int green = (argb >>> 8) & 0xFF;
            int blue = argb & 0xFF;
            if (red + green + blue <= MIN_VISIBLE_RGB_SUM) continue;

            Hct hct = Hct.fromInt(argb);
            if (hct.getChroma() < chromaThreshold) continue;

            double weight = 1.0
                + Math.max(0.0, (hct.getChroma() - chromaThreshold) / 24.0) * chromaWeightMultiplier
                + hct.getTone() / 100.0;

            totalRed += red * weight;
            totalGreen += green * weight;
            totalBlue += blue * weight;
            totalWeight += weight;
            representativePixelCount++;
        }

        if (totalWeight <= 0.0) return null;
        if ((double) representativePixelCount / pixels.length < MIN_REPRESENTATIVE_PIXEL_RATIO) return null;

        int argb = (0xFF << 24)
            | (clampInt((int) Math.round(totalRed / totalWeight), 0, 255) << 16)
            | (clampInt((int) Math.round(totalGreen / totalWeight), 0, 255) << 8)
            | clampInt((int) Math.round(totalBlue / totalWeight), 0, 255);
        return new RepresentativeArtworkColor(argb, Hct.fromInt(argb));
    }

    private static double calculateRepresentativeFidelityScore(Hct candidate, Hct representative, double accuracy) {
        double hueDistance = differenceDegrees(candidate.getHue(), representative.getHue());
        double chromaDistance = Math.abs(candidate.getChroma() - representative.getChroma());
        double toneDistance = Math.abs(candidate.getTone() - representative.getTone());
        double hueWindow = lerpDouble(FIDELITY_HUE_WINDOW, ACCURATE_FIDELITY_HUE_WINDOW, accuracy);
        double chromaWindow = lerpDouble(FIDELITY_CHROMA_WINDOW, ACCURATE_FIDELITY_CHROMA_WINDOW, accuracy);
        double toneWindow = lerpDouble(FIDELITY_TONE_WINDOW, ACCURATE_FIDELITY_TONE_WINDOW, accuracy);
        double hueWeight = lerpDouble(FIDELITY_HUE_WEIGHT, ACCURATE_FIDELITY_HUE_WEIGHT, accuracy);
        double chromaWeight = lerpDouble(FIDELITY_CHROMA_WEIGHT, ACCURATE_FIDELITY_CHROMA_WEIGHT, accuracy);
        double toneWeight = lerpDouble(FIDELITY_TONE_WEIGHT, ACCURATE_FIDELITY_TONE_WEIGHT, accuracy);

        double hueScore = Math.max(0.0, hueWindow - hueDistance) / hueWindow * hueWeight;
        double chromaScore = Math.max(0.0, chromaWindow - chromaDistance) / chromaWindow * chromaWeight;
        double toneScore = Math.max(0.0, toneWindow - toneDistance) / toneWindow * toneWeight;
        return hueScore + chromaScore + toneScore;
    }

    private static double calculateExcessChromaPenalty(Hct candidate, Hct representative, double accuracy) {
        double penaltyStart = lerpDouble(EXCESS_CHROMA_PENALTY_START, ACCURATE_EXCESS_CHROMA_PENALTY_START, accuracy);
        double penaltyWeight = lerpDouble(EXCESS_CHROMA_PENALTY_WEIGHT, ACCURATE_EXCESS_CHROMA_PENALTY_WEIGHT, accuracy);
        double excessChroma = candidate.getChroma() - representative.getChroma() - penaltyStart;
        if (excessChroma <= 0.0) return 0.0;
        return excessChroma * penaltyWeight;
    }

    private static int refineSeedColorArgb(
            int candidateArgb, int[] pixels, RepresentativeArtworkColor representative,
            double cutoffChroma, double accuracy) {
        if (pixels.length == 0) return candidateArgb;

        Hct candidateHct = Hct.fromInt(candidateArgb);
        double effectiveCutoffChroma = lerpDouble(cutoffChroma, ACCURATE_CUTOFF_CHROMA, accuracy);
        double localHueWindow = lerpDouble(LOCAL_REFINEMENT_HUE_WINDOW, ACCURATE_LOCAL_REFINEMENT_HUE_WINDOW, accuracy);
        double localBlendRatio = lerpDouble(
            LOCAL_REFINEMENT_BLEND_RATIO, ACCURATE_LOCAL_REFINEMENT_BLEND_RATIO, accuracy);
        double representativeBlendRatio = lerpDouble(
            LOCAL_REFINEMENT_BLEND_RATIO / 2.0, ACCURATE_REPRESENTATIVE_BLEND_RATIO, accuracy);
        double totalRed = 0.0, totalGreen = 0.0, totalBlue = 0.0, totalWeight = 0.0;
        int matchingPixelCount = 0;

        for (int argb : pixels) {
            int alpha = (argb >>> 24) & 0xFF;
            if (alpha < MIN_VISIBLE_PIXEL_ALPHA) continue;
            int red = (argb >>> 16) & 0xFF;
            int green = (argb >>> 8) & 0xFF;
            int blue = argb & 0xFF;
            if (red + green + blue <= MIN_VISIBLE_RGB_SUM) continue;

            Hct hct = Hct.fromInt(argb);
            if (hct.getChroma() < effectiveCutoffChroma) continue;
            double hueDistance = differenceDegrees(candidateHct.getHue(), hct.getHue());
            if (hueDistance > localHueWindow) continue;

            double weight = 1.0
                + (localHueWindow - hueDistance) / localHueWindow
                + Math.max(0.0, (hct.getChroma() - effectiveCutoffChroma) / 32.0);
            totalRed += red * weight;
            totalGreen += green * weight;
            totalBlue += blue * weight;
            totalWeight += weight;
            matchingPixelCount++;
        }

        if (totalWeight <= 0.0) return candidateArgb;
        if ((double) matchingPixelCount / pixels.length < MIN_REFINEMENT_PIXEL_RATIO) return candidateArgb;

        int localAverageArgb = (0xFF << 24)
            | (clampInt((int) Math.round(totalRed / totalWeight), 0, 255) << 16)
            | (clampInt((int) Math.round(totalGreen / totalWeight), 0, 255) << 8)
            | clampInt((int) Math.round(totalBlue / totalWeight), 0, 255);
        Hct localAverageHct = Hct.fromInt(localAverageArgb);
        if (differenceDegrees(candidateHct.getHue(), localAverageHct.getHue()) > localHueWindow) {
            return candidateArgb;
        }

        int refinedArgb = blendArgb(candidateArgb, localAverageArgb, (float) localBlendRatio);
        if (representative == null) return refinedArgb;

        double fidelityHueWindow = lerpDouble(FIDELITY_HUE_WINDOW, ACCURATE_FIDELITY_HUE_WINDOW, accuracy);
        if (differenceDegrees(localAverageHct.getHue(), representative.hct.getHue()) <= fidelityHueWindow) {
            return blendArgb(refinedArgb, representative.argb, (float) representativeBlendRatio);
        }
        return refinedArgb;
    }

    private static int blendArgb(int firstArgb, int secondArgb, float ratio) {
        float clampedRatio = ratio < 0 ? 0f : (ratio > 1f ? 1f : ratio);
        float inverseRatio = 1f - clampedRatio;
        int alpha = clampInt(Math.round(((firstArgb >>> 24) & 0xFF) * inverseRatio
            + ((secondArgb >>> 24) & 0xFF) * clampedRatio), 0, 255);
        int red = clampInt(Math.round(((firstArgb >>> 16) & 0xFF) * inverseRatio
            + ((secondArgb >>> 16) & 0xFF) * clampedRatio), 0, 255);
        int green = clampInt(Math.round(((firstArgb >>> 8) & 0xFF) * inverseRatio
            + ((secondArgb >>> 8) & 0xFF) * clampedRatio), 0, 255);
        int blue = clampInt(Math.round((firstArgb & 0xFF) * inverseRatio
            + (secondArgb & 0xFF) * clampedRatio), 0, 255);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int averageColorArgb(int[] pixels) {
        if (pixels.length == 0) return DEFAULT_SEED_ARGB;
        long totalRed = 0, totalGreen = 0, totalBlue = 0;
        for (int argb : pixels) {
            totalRed += (argb >>> 16) & 0xFF;
            totalGreen += (argb >>> 8) & 0xFF;
            totalBlue += argb & 0xFF;
        }
        long size = pixels.length;
        int r = clampInt((int) (totalRed / size), 0, 255);
        int g = clampInt((int) (totalGreen / size), 0, 255);
        int b = clampInt((int) (totalBlue / size), 0, 255);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static boolean isMostlyNeutralArtwork(Map<Integer, Integer> colorsToPopulation) {
        if (colorsToPopulation.isEmpty()) return false;
        double totalPopulation = 0.0, neutralPopulation = 0.0, highChromaPopulation = 0.0, weightedChroma = 0.0;
        for (Map.Entry<Integer, Integer> e : colorsToPopulation.entrySet()) {
            int populationInt = e.getValue();
            if (populationInt <= 0) continue;
            double population = populationInt;
            double chroma = Hct.fromInt(e.getKey()).getChroma();
            totalPopulation += population;
            weightedChroma += chroma * population;
            if (chroma <= NEUTRAL_PIXEL_CHROMA_THRESHOLD) neutralPopulation += population;
            if (chroma >= HIGH_CHROMA_THRESHOLD) highChromaPopulation += population;
        }
        if (totalPopulation <= 0.0) return false;
        double neutralRatio = neutralPopulation / totalPopulation;
        double highChromaRatio = highChromaPopulation / totalPopulation;
        double meanChroma = weightedChroma / totalPopulation;
        return neutralRatio >= REQUIRED_NEUTRAL_POPULATION
            && highChromaRatio <= MAX_HIGH_CHROMA_POPULATION
            && meanChroma <= MAX_WEIGHTED_CHROMA_FOR_NEUTRAL;
    }

    private static boolean isArgbNearGrayscale(int argb) {
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        int maxDelta = Math.max(Math.abs(red - green), Math.max(Math.abs(green - blue), Math.abs(red - blue)));
        return maxDelta <= MAX_GRAYSCALE_CHANNEL_DELTA;
    }

    // ------------------------------------------------------------------
    //  Math helpers (MathUtils ports)
    // ------------------------------------------------------------------

    static double lerpDouble(double start, double stop, double fraction) {
        double f = fraction < 0 ? 0 : (fraction > 1 ? 1 : fraction);
        return start + (stop - start) * f;
    }

    static double clampDouble(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    static int clampInt(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    static int sanitizeDegreesInt(int degrees) {
        int d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }

    static double differenceDegrees(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return 180.0 - Math.abs(d - 180.0);
    }
}