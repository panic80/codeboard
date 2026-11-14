package com.gazlaws.codeboard.gesture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * An offline, geometry-based word gesture decoder. Instances contain immutable, precomputed
 * dictionary templates and may be reused across gestures and threads. Coordinates in the path
 * and the keyboard must use the same coordinate system.
 *
 * <p>For Korean, key labels must be the Latin positions of the two-set keyboard (for example,
 * the key labelled ㅁ is {@code 'a'}). Shifted jamo share the same gesture position as their
 * unshifted counterparts. The returned text is always the original dictionary spelling.
 */
public final class SwipeDecoder {
    private static final int SAMPLE_COUNT = 32;
    private static final int COARSE_COUNT = 192;
    private static final int MAX_RESULT_COUNT = 32;
    private static final int MAX_SEQUENCE_LENGTH = 64;
    private static final float ENDPOINT_RADIUS = 1.7f;

    /** A letter key in the same coordinate system as the recorded gesture. */
    public static final class KeyPosition {
        public final char key;
        public final float centerX;
        public final float centerY;
        public final float width;
        public final float height;

        public KeyPosition(char key, float centerX, float centerY, float width, float height) {
            this.key = key;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }
    }

    /** One sampled point. A timestamp is optional, but helps detect a repeated-letter pause. */
    public static final class Point {
        public final float x;
        public final float y;
        public final long timeMillis;

        public Point(float x, float y) {
            this(x, y, -1);
        }

        public Point(float x, float y, long timeMillis) {
            this.x = x;
            this.y = y;
            this.timeMillis = timeMillis;
        }
    }

    /** A word and its nonnegative occurrence count. Larger counts mean more common words. */
    public static final class Entry {
        public final String word;
        public final int frequency;

        public Entry(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }

    // Standard Dubeolsik. Uppercase positions require Shift when typing, but are on the same key.
    private static final String[] INITIAL_KEYS = {
            "r", "R", "s", "e", "E", "f", "a", "q", "Q", "t", "T", "d", "w", "W", "c", "z", "x", "v", "g"
    };
    private static final String[] VOWEL_KEYS = {
            "k", "o", "i", "O", "j", "p", "u", "P", "h", "hk", "ho", "hl", "y", "n", "nj", "np", "nl", "b", "m", "ml", "l"
    };
    private static final String[] FINAL_KEYS = {
            "", "r", "R", "rt", "s", "sw", "sg", "e", "f", "fr", "fa", "fq", "ft", "fx", "fv", "fg", "a", "q", "qt", "t", "T", "d", "w", "c", "z", "x", "v", "g"
    };
    private static final String COMPAT_INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String COMPAT_VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String COMPAT_FINALS = "\0ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";

    private final float[] keyX = new float[26];
    private final float[] keyY = new float[26];
    private final boolean[] hasKey = new boolean[26];
    private final float scaleX;
    private final float scaleY;
    private final List<Word>[] buckets;

    /** Builds templates once; reuse the decoder until keyboard geometry or dictionary changes. */
    @SuppressWarnings("unchecked")
    public SwipeDecoder(List<KeyPosition> keys, List<Entry> entries) {
        ArrayList<Float> widths = new ArrayList<>();
        ArrayList<Float> heights = new ArrayList<>();
        if (keys != null) {
            for (KeyPosition key : keys) {
                if (usableKey(key)) {
                    widths.add(key.width);
                    heights.add(key.height);
                }
            }
        }
        scaleX = median(widths);
        scaleY = median(heights);
        if (keys != null) {
            for (KeyPosition key : keys) {
                if (!usableKey(key)) continue;
                int index = asciiIndex(key.key);
                if (!hasKey[index]) {
                    hasKey[index] = true;
                    keyX[index] = key.centerX / scaleX;
                    keyY[index] = key.centerY / scaleY;
                }
            }
        }

        buckets = (List<Word>[]) new List<?>[26 * 26];
        if (entries == null || entries.isEmpty()) return;

        // Duplicate spellings are common in combined frequency lists. Keep the largest count.
        Map<String, Integer> frequencies = new HashMap<>();
        int maxFrequency = 0;
        for (Entry entry : entries) {
            if (entry == null || entry.word == null || entry.word.isEmpty()) continue;
            int frequency = Math.max(0, entry.frequency);
            Integer old = frequencies.get(entry.word);
            if (old == null || old < frequency) frequencies.put(entry.word, frequency);
            maxFrequency = Math.max(maxFrequency, frequency);
        }
        double frequencyScale = Math.log1p(maxFrequency);
        for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
            String sequence = keySequenceForWord(entry.getKey());
            if (sequence == null || sequence.length() == 0 || sequence.length() > MAX_SEQUENCE_LENGTH) continue;
            float[] vertices = new float[sequence.length() * 2];
            int repeatMask = 0;
            int previous = -1;
            boolean usable = true;
            for (int i = 0; i < sequence.length(); i++) {
                int key = asciiIndex(sequence.charAt(i));
                if (key < 0 || !hasKey[key]) {
                    usable = false;
                    break;
                }
                vertices[2 * i] = keyX[key];
                vertices[2 * i + 1] = keyY[key];
                if (key == previous) repeatMask |= 1 << key;
                previous = key;
            }
            if (!usable) continue;
            int first = asciiIndex(sequence.charAt(0));
            int last = asciiIndex(sequence.charAt(sequence.length() - 1));
            float length = polylineLength(vertices);
            float[] template = resample(vertices, length);
            float frequencyPenalty = frequencyScale == 0 ? 0 :
                    (float) (0.10 * (1 - Math.log1p(entry.getValue()) / frequencyScale));
            Word word = new Word(entry.getKey(), entry.getValue(), first, last,
                    repeatMask, template, length, frequencyPenalty);
            int bucket = first * 26 + last;
            if (buckets[bucket] == null) buckets[bucket] = new ArrayList<>();
            buckets[bucket].add(word);
        }
    }

    /** Returns the best dictionary spellings in ranked order, or an empty list for an unusable path. */
    public List<String> decode(List<Point> path, int limit) {
        if (path == null || path.size() < 2 || limit <= 0) return Collections.emptyList();

        ArrayList<Point> valid = new ArrayList<>(path.size());
        for (Point point : path) {
            if (point != null && finite(point.x) && finite(point.y)) valid.add(point);
        }
        if (valid.size() < 2) return Collections.emptyList();
        float[] vertices = new float[valid.size() * 2];
        for (int i = 0; i < valid.size(); i++) {
            vertices[2 * i] = valid.get(i).x / scaleX;
            vertices[2 * i + 1] = valid.get(i).y / scaleY;
        }
        float pathLength = polylineLength(vertices);
        if (!finite(pathLength) || pathLength < 0.05f) return Collections.emptyList();
        float[] observed = resample(vertices, pathLength);
        int repeatedKeys = repeatEvidence(valid, observed);

        float[] startDistance = new float[26];
        float[] endDistance = new float[26];
        for (int i = 0; i < 26; i++) {
            if (hasKey[i]) {
                startDistance[i] = distanceSquared(observed[0], observed[1], keyX[i], keyY[i]);
                endDistance[i] = distanceSquared(observed[2 * (SAMPLE_COUNT - 1)],
                        observed[2 * (SAMPLE_COUNT - 1) + 1], keyX[i], keyY[i]);
            } else {
                startDistance[i] = Float.POSITIVE_INFINITY;
                endDistance[i] = Float.POSITIVE_INFINITY;
            }
        }

        // Only words whose first and last keys are close to the finger need shape scoring.
        // A small heap bounds the more expensive dynamic-time-warping step on large dictionaries.
        PriorityQueue<ScoredWord> shortlist = new PriorityQueue<>(COARSE_COUNT + 1, WORST_FIRST);
        float radiusSquared = ENDPOINT_RADIUS * ENDPOINT_RADIUS;
        for (int first = 0; first < 26; first++) {
            if (startDistance[first] > radiusSquared) continue;
            for (int last = 0; last < 26; last++) {
                if (endDistance[last] > radiusSquared) continue;
                List<Word> bucket = buckets[first * 26 + last];
                if (bucket == null) continue;
                for (Word word : bucket) {
                    // Reject extreme length mismatches before looking at the entire shape.
                    if (word.length > pathLength * 3.0f + 2.0f ||
                            pathLength > word.length * 3.0f + 2.0f) continue;
                    float endpoints = startDistance[first] + endDistance[last];
                    float score = coarseShape(observed, word.template) + 0.55f * endpoints
                            + lengthPenalty(pathLength, word.length) + word.frequencyPenalty
                            + repeatPenalty(repeatedKeys, word.repeatMask);
                    ScoredWord scored = new ScoredWord(word, score);
                    if (shortlist.size() < COARSE_COUNT) shortlist.add(scored);
                    else if (BEST_FIRST.compare(scored, shortlist.peek()) < 0) {
                        shortlist.poll();
                        shortlist.add(scored);
                    }
                }
            }
        }

        ArrayList<ScoredWord> ranked = new ArrayList<>(shortlist.size());
        for (ScoredWord coarse : shortlist) {
            Word word = coarse.word;
            float endpoints = startDistance[word.first] + endDistance[word.last];
            float score = dynamicShape(observed, word.template) + 0.55f * endpoints
                    + lengthPenalty(pathLength, word.length) + word.frequencyPenalty
                    + repeatPenalty(repeatedKeys, word.repeatMask);
            ranked.add(new ScoredWord(word, score));
        }
        Collections.sort(ranked, BEST_FIRST);
        int count = Math.min(Math.min(limit, MAX_RESULT_COUNT), ranked.size());
        ArrayList<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(ranked.get(i).word.text);
        return result;
    }

    /** Converts a spelling into two-set positions, preserving Shift on tense consonants/vowels. */
    static String keySequenceForWord(String word) {
        if (word == null) return null;
        StringBuilder result = new StringBuilder(word.length() * 3);
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c >= '가' && c <= '힣') {
                int syllable = c - '가';
                result.append(INITIAL_KEYS[syllable / 588]);
                result.append(VOWEL_KEYS[(syllable % 588) / 28]);
                result.append(FINAL_KEYS[syllable % 28]);
            } else if (c >= 0x1100 && c <= 0x1112) {
                result.append(INITIAL_KEYS[c - 0x1100]);
            } else if (c >= 0x1161 && c <= 0x1175) {
                result.append(VOWEL_KEYS[c - 0x1161]);
            } else if (c >= 0x11A8 && c <= 0x11C2) {
                result.append(FINAL_KEYS[c - 0x11A7]);
            } else if (COMPAT_INITIALS.indexOf(c) >= 0) {
                result.append(INITIAL_KEYS[COMPAT_INITIALS.indexOf(c)]);
            } else if (COMPAT_VOWELS.indexOf(c) >= 0) {
                result.append(VOWEL_KEYS[COMPAT_VOWELS.indexOf(c)]);
            } else if (c != 0 && COMPAT_FINALS.indexOf(c) > 0) {
                result.append(FINAL_KEYS[COMPAT_FINALS.indexOf(c)]);
            } else if (asciiIndex(c) >= 0) {
                result.append(c);
            } else if (c != '\'' && c != '’') {
                // Whitespace, punctuation and other scripts are not letter-key gestures.
                return null;
            }
        }
        return result.toString();
    }

    private static final class Word {
        final String text;
        final int frequency;
        final int first;
        final int last;
        final int repeatMask;
        final float[] template;
        final float length;
        final float frequencyPenalty;

        Word(String text, int frequency, int first, int last, int repeatMask,
             float[] template, float length, float frequencyPenalty) {
            this.text = text;
            this.frequency = frequency;
            this.first = first;
            this.last = last;
            this.repeatMask = repeatMask;
            this.template = template;
            this.length = length;
            this.frequencyPenalty = frequencyPenalty;
        }
    }

    private static final class ScoredWord {
        final Word word;
        final float score;
        ScoredWord(Word word, float score) { this.word = word; this.score = score; }
    }

    private static final Comparator<ScoredWord> BEST_FIRST = (a, b) -> {
        int comparison = Float.compare(a.score, b.score);
        if (comparison != 0) return comparison;
        comparison = Integer.compare(b.word.frequency, a.word.frequency);
        return comparison != 0 ? comparison : a.word.text.compareTo(b.word.text);
    };
    private static final Comparator<ScoredWord> WORST_FIRST = (a, b) -> BEST_FIRST.compare(b, a);

    private static float coarseShape(float[] path, float[] template) {
        float sum = 0;
        int count = 0;
        for (int i = 2; i < SAMPLE_COUNT - 1; i += 3) {
            float best = Float.POSITIVE_INFINITY;
            for (int j = Math.max(0, i - 4); j <= Math.min(SAMPLE_COUNT - 1, i + 4); j++) {
                best = Math.min(best, distanceSquared(path[2 * i], path[2 * i + 1],
                        template[2 * j], template[2 * j + 1]));
            }
            sum += best;
            count++;
        }
        return sum / count;
    }

    private static float dynamicShape(float[] path, float[] template) {
        // Monotone alignment tolerates smoothing and changing speed while preserving key order.
        float[] previous = new float[SAMPLE_COUNT + 1];
        float[] current = new float[SAMPLE_COUNT + 1];
        for (int j = 0; j <= SAMPLE_COUNT; j++) previous[j] = Float.POSITIVE_INFINITY;
        previous[0] = 0;
        for (int i = 1; i <= SAMPLE_COUNT; i++) {
            for (int j = 0; j <= SAMPLE_COUNT; j++) current[j] = Float.POSITIVE_INFINITY;
            int from = Math.max(1, i - 8);
            int to = Math.min(SAMPLE_COUNT, i + 8);
            for (int j = from; j <= to; j++) {
                float cost = distanceSquared(path[2 * (i - 1)], path[2 * (i - 1) + 1],
                        template[2 * (j - 1)], template[2 * (j - 1) + 1]);
                current[j] = cost + Math.min(previous[j - 1], Math.min(previous[j], current[j - 1]));
            }
            float[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[SAMPLE_COUNT] / SAMPLE_COUNT;
    }

    private static float lengthPenalty(float actual, float expected) {
        return (float) (0.10 * Math.abs(Math.log((actual + 0.5f) / (expected + 0.5f))));
    }

    private static float repeatPenalty(int evidence, int repeats) {
        // An otherwise invisible double letter can be signalled with a pause or a small loop.
        return 0.22f * Integer.bitCount(evidence & ~repeats)
                + 0.01f * Integer.bitCount(repeats & ~evidence);
    }

    private int repeatEvidence(List<Point> path, float[] sampled) {
        long[] dwell = new long[26];
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1);
            Point b = path.get(i);
            if (a.timeMillis < 0 || b.timeMillis <= a.timeMillis) continue;
            float ax = a.x / scaleX, ay = a.y / scaleY;
            float bx = b.x / scaleX, by = b.y / scaleY;
            if (distanceSquared(ax, ay, bx, by) > 0.25f * 0.25f) continue;
            int key = nearestKey((ax + bx) / 2, (ay + by) / 2, 0.55f);
            if (key >= 0) dwell[key] += Math.min(500, b.timeMillis - a.timeMillis);
        }
        // Detect a genuine loop, rather than adding together ordinary passes near a key.
        // A gesture often crosses the same key more than once on its way to other letters.
        int loopKeys = 0;
        int previousKey = -1;
        float rotation = 0;
        for (int i = 1; i < SAMPLE_COUNT; i++) {
            float ax = sampled[2 * (i - 1)], ay = sampled[2 * (i - 1) + 1];
            float bx = sampled[2 * i], by = sampled[2 * i + 1];
            int key = nearestKey((ax + bx) / 2, (ay + by) / 2, 0.70f);
            if (key != previousKey) rotation = 0;
            if (key >= 0) {
                float x1 = ax - keyX[key], y1 = ay - keyY[key];
                float x2 = bx - keyX[key], y2 = by - keyY[key];
                rotation += (float) Math.atan2(x1 * y2 - y1 * x2, x1 * x2 + y1 * y2);
                if (Math.abs(rotation) >= 4.5f) loopKeys |= 1 << key;
            }
            previousKey = key;
        }
        int result = loopKeys;
        for (int key = 0; key < 26; key++) {
            if (dwell[key] >= 120) result |= 1 << key;
        }
        return result;
    }

    private int nearestKey(float x, float y, float radius) {
        int best = -1;
        float bestDistance = radius * radius;
        for (int i = 0; i < 26; i++) {
            if (!hasKey[i]) continue;
            float distance = distanceSquared(x, y, keyX[i], keyY[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static float[] resample(float[] vertices, float length) {
        float[] result = new float[2 * SAMPLE_COUNT];
        if (vertices.length == 2 || length == 0) {
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                result[2 * i] = vertices[0];
                result[2 * i + 1] = vertices[1];
            }
            return result;
        }
        int segment = 0;
        float startDistance = 0;
        float segmentLength = segmentLength(vertices, segment);
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            float target = length * i / (SAMPLE_COUNT - 1);
            while (segment + 2 < vertices.length / 2 && startDistance + segmentLength < target) {
                startDistance += segmentLength;
                segment++;
                segmentLength = segmentLength(vertices, segment);
            }
            float fraction = segmentLength > 0 ? (target - startDistance) / segmentLength : 0;
            fraction = Math.max(0, Math.min(1, fraction));
            result[2 * i] = vertices[2 * segment] + fraction * (vertices[2 * segment + 2] - vertices[2 * segment]);
            result[2 * i + 1] = vertices[2 * segment + 1] + fraction * (vertices[2 * segment + 3] - vertices[2 * segment + 1]);
        }
        return result;
    }

    private static float polylineLength(float[] vertices) {
        float length = 0;
        for (int i = 0; i + 3 < vertices.length; i += 2) {
            length += (float) Math.sqrt(distanceSquared(vertices[i], vertices[i + 1], vertices[i + 2], vertices[i + 3]));
        }
        return length;
    }

    private static float segmentLength(float[] vertices, int segment) {
        return (float) Math.sqrt(distanceSquared(vertices[2 * segment], vertices[2 * segment + 1],
                vertices[2 * segment + 2], vertices[2 * segment + 3]));
    }

    private static float distanceSquared(float ax, float ay, float bx, float by) {
        float x = ax - bx, y = ay - by;
        return x * x + y * y;
    }

    private static int asciiIndex(char c) {
        if (c >= 'A' && c <= 'Z') c = (char) (c + ('a' - 'A'));
        return c >= 'a' && c <= 'z' ? c - 'a' : -1;
    }

    private static boolean usableKey(KeyPosition key) {
        return key != null && asciiIndex(key.key) >= 0 && finite(key.centerX) && finite(key.centerY)
                && finite(key.width) && finite(key.height) && key.width > 0 && key.height > 0;
    }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }

    private static float median(ArrayList<Float> values) {
        if (values.isEmpty()) return 1;
        Collections.sort(values);
        int middle = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2 : values.get(middle);
    }
}
