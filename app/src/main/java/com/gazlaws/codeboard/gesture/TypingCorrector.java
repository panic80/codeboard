package com.gazlaws.codeboard.gesture;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline typed-word suggestions. The dictionary is indexed once in a compact trie, and searches
 * traverse only prefixes within a small edit distance of the typed text. Instances are immutable
 * after construction and can be shared by input and worker threads.
 *
 * <p>Korean comparisons use the two-set keystrokes of each Hangul spelling, including Shift,
 * rather than treating an entire syllable as a single letter. Results use dictionary spellings.
 */
public final class TypingCorrector {
    private static final int MAX_INPUT_LENGTH = 48;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_RESULTS = 32;
    private static final double FREQUENCY_WEIGHT = 0.18;
    private static final double SHORT_WORD_FREQUENCY_WEIGHT = 1.9;

    /** The typed text is the first candidate. A correction is present only when confidence is high. */
    public static final class Result {
        public final List<String> candidates;
        public final String correction;
        /** The highest-ranked eligible alternative, even when it is not a confident correction. */
        public final String bestSuggestion;
        public final boolean exactWord;

        private Result(List<String> candidates, String correction, String bestSuggestion,
                       boolean exactWord) {
            this.candidates = Collections.unmodifiableList(candidates);
            this.correction = correction;
            this.bestSuggestion = bestSuggestion;
            this.exactWord = exactWord;
        }
    }

    private static final class Word {
        final String text;
        final String keys;
        final int frequency;

        Word(String text, String keys, int frequency) {
            this.text = text;
            this.keys = keys;
            this.frequency = frequency;
        }
    }

    private static final class Match {
        final Word word;
        final int edits;
        final double score;

        Match(Word word, int edits, double score) {
            this.word = word;
            this.edits = edits;
            this.score = score;
        }
    }

    private static final Comparator<Match> MATCH_ORDER = (a, b) -> {
        int order = Double.compare(a.score, b.score);
        if (order != 0) return order;
        order = Integer.compare(b.word.frequency, a.word.frequency);
        return order != 0 ? order : a.word.text.compareTo(b.word.text);
    };

    private final boolean korean;
    private final Map<String, Word> exactWords;
    private final Word[] words;
    private final char[] letters;
    private final int[] firstChildren;
    private final int[] nextSiblings;
    private final int[] terminals;
    private final int[] nextWords;
    private final int maxFrequency;
    private final double frequencyScale;

    /**
     * Precomputes the search index. Duplicate spellings keep the largest count and the last
     * supplied capitalization. Append personal entries after the built-in dictionary.
     */
    public TypingCorrector(List<SwipeDecoder.Entry> entries, boolean korean) {
        this.korean = korean;
        Map<String, Word> bySpelling = new HashMap<>();
        if (entries != null) {
            for (SwipeDecoder.Entry entry : entries) {
                if (entry == null || entry.word == null) continue;
                String normalized = normalize(entry.word, korean);
                if (normalized == null || normalized.length() > MAX_INPUT_LENGTH) continue;
                String keys = keysFor(normalized, korean);
                if (keys == null || keys.isEmpty() || keys.length() > MAX_KEY_LENGTH) continue;
                int frequency = Math.max(0, entry.frequency);
                String canonical = Normalizer.normalize(entry.word, Normalizer.Form.NFC);
                Word previous = bySpelling.get(normalized);
                int largest = previous == null ? frequency : Math.max(frequency, previous.frequency);
                bySpelling.put(normalized, new Word(canonical, keys, largest));
            }
        }
        exactWords = Collections.unmodifiableMap(bySpelling);
        ArrayList<Word> collected = new ArrayList<>(bySpelling.size());
        int highest = 0;
        for (Word word : bySpelling.values()) {
            collected.add(word);
            highest = Math.max(highest, word.frequency);
        }
        words = collected.toArray(new Word[0]);
        maxFrequency = highest;
        frequencyScale = highest == 0 ? 1 : Math.log1p(highest);

        TrieBuilder trie = new TrieBuilder(words.length);
        for (int i = 0; i < words.length; i++) trie.insert(words[i].keys, i);
        letters = Arrays.copyOf(trie.letters, trie.size);
        firstChildren = Arrays.copyOf(trie.firstChildren, trie.size);
        nextSiblings = Arrays.copyOf(trie.nextSiblings, trie.size);
        terminals = Arrays.copyOf(trie.terminals, trie.size);
        nextWords = trie.nextWords;
    }

    /**
     * Suggests up to {@code limit} strings, always retaining the original typed string first.
     * Unsupported tokens, exact dictionary words and unusually long tokens are never changed.
     * Only positive {@code limit} values produce candidates.
     */
    public Result suggest(String typed, int limit) {
        ArrayList<String> candidates = new ArrayList<>();
        if (typed == null || typed.isEmpty()) return new Result(candidates, null, null, false);
        String normalized = normalize(typed, korean);
        Word exactEntry = normalized == null ? null : exactWords.get(normalized);
        boolean exact = exactEntry != null;
        if (limit <= 0) return new Result(candidates, null, null, exact);
        candidates.add(typed);
        if (exact) {
            CaseStyle style = korean ? CaseStyle.LOWER : caseStyle(typed);
            String canonical = display(exactEntry.text, style, typed);
            if (limit > 1 && !canonical.equals(typed)) candidates.add(canonical);
            return new Result(candidates, null, null, true);
        }
        if (normalized == null || normalized.length() > MAX_INPUT_LENGTH) {
            return new Result(candidates, null, null, exact);
        }
        CaseStyle style = korean ? CaseStyle.LOWER : caseStyle(typed);
        String keys = keysFor(normalized, korean);
        if (keys == null || keys.isEmpty() || keys.length() > MAX_KEY_LENGTH) {
            return new Result(candidates, null, null, false);
        }

        // Short words are especially ambiguous; distance two is useful only on longer tokens.
        int maxEdits = (korean ? countSyllables(normalized) >= 3 : keys.length() >= 5) ? 2 : 1;
        int[] initial = new int[keys.length() + 1];
        for (int i = 0; i < initial.length; i++) initial[i] = i;
        ArrayList<Match> matches = new ArrayList<>();
        for (int child = firstChildren[0]; child != 0; child = nextSiblings[child]) {
            search(child, keys, maxEdits, initial, null, '\0', matches);
        }
        Collections.sort(matches, MATCH_ORDER);
        // Preserve the conservative decision while ranking short English suggestions with a
        // stronger frequency prior: one edit in three letters offers little spelling evidence.
        String correction = confidentCorrection(normalized, keys, typed, style, matches);
        if (!korean && letterCount(normalized) == 3) {
            double extraWeight = SHORT_WORD_FREQUENCY_WEIGHT - FREQUENCY_WEIGHT;
            Collections.sort(matches, (a, b) -> {
                double scoreA = a.score - extraWeight * Math.log1p(a.word.frequency) / frequencyScale;
                double scoreB = b.score - extraWeight * Math.log1p(b.word.frequency) / frequencyScale;
                int order = Double.compare(scoreA, scoreB);
                return order != 0 ? order : MATCH_ORDER.compare(a, b);
            });
        }
        int count = Math.min(Math.min(limit, MAX_RESULTS), matches.size() + 1);
        for (Match match : matches) {
            if (candidates.size() >= count) break;
            String shown = display(match.word.text, style, typed);
            if (!shown.equals(typed) && !candidates.contains(shown)) candidates.add(shown);
        }
        String bestSuggestion = topSuggestion(normalized, typed, style, matches);
        return new Result(candidates, correction, bestSuggestion, false);
    }

    private String topSuggestion(String normalized, String typed, CaseStyle style,
                                 List<Match> matches) {
        if (matches.isEmpty() || style == CaseStyle.UPPER || style == CaseStyle.MIXED) return null;
        Match best = matches.get(0);
        if (korean) {
            // Only complete Korean words of at least three syllables can be replaced.
            if (countSyllables(normalized) < 3 || !onlySyllables(normalized)
                    || countSyllables(best.word.text) < 3 || !onlySyllables(best.word.text)) return null;
        } else if (letterCount(normalized) <= 2) {
            return null;
        }
        return display(best.word.text, style, typed);
    }

    private String confidentCorrection(String normalized, String keys, String typed,
                                       CaseStyle style, List<Match> matches) {
        if (matches.isEmpty() || style == CaseStyle.UPPER || style == CaseStyle.MIXED) return null;
        Match best = matches.get(0);
        if (best.edits != 1) return null;
        if (korean) {
            // Incomplete jamo and shorter Korean words are too ambiguous to replace automatically.
            if (countSyllables(normalized) < 3 || !onlySyllables(normalized)
                    || countSyllables(best.word.text) < 3 || !onlySyllables(best.word.text)) return null;
        } else {
            int length = letterCount(normalized);
            if (length <= 2 || (length == 3 && !singleTransposition(keys, best.word.keys))) return null;
        }
        // Adapt a floor to small custom dictionaries while filtering rare entries in full lexicons.
        int minimum = Math.min(korean ? 50 : 1000, Math.max(2, maxFrequency / 1000));
        if (best.word.frequency < minimum) return null;
        // A similarly common one-edit alternative is strong evidence of ambiguity, regardless of
        // its keyboard distance. Leave the typed word intact and offer the ranked candidates.
        int factor = korean ? 8 : 16;
        for (int i = 1; i < matches.size(); i++) {
            Match alternative = matches.get(i);
            if (alternative.edits == 1 && (long) alternative.word.frequency * factor >= best.word.frequency) {
                return null;
            }
        }
        return display(best.word.text, style, typed);
    }

    private void search(int node, String input, int maxEdits, int[] previous, int[] beforePrevious,
                        char previousLetter, List<Match> matches) {
        char letter = letters[node];
        int[] current = new int[input.length() + 1];
        current[0] = previous[0] + 1;
        int minimum = current[0];
        for (int j = 1; j < current.length; j++) {
            int substitution = previous[j - 1] + (letter == input.charAt(j - 1) ? 0 : 1);
            int value = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            if (beforePrevious != null && j > 1 && letter == input.charAt(j - 2)
                    && previousLetter == input.charAt(j - 1)) {
                value = Math.min(value, beforePrevious[j - 2] + 1);
            }
            current[j] = value;
            minimum = Math.min(minimum, value);
        }
        if (minimum > maxEdits) return;
        if (current[input.length()] <= maxEdits) {
            for (int terminal = terminals[node]; terminal != 0; terminal = nextWords[terminal - 1]) {
                Word word = words[terminal - 1];
                double editCost = weightedDistance(input, word.keys);
                double frequencyBonus = FREQUENCY_WEIGHT * Math.log1p(word.frequency) / frequencyScale;
                matches.add(new Match(word, current[input.length()], editCost - frequencyBonus));
            }
        }
        for (int child = firstChildren[node]; child != 0; child = nextSiblings[child]) {
            search(child, input, maxEdits, current, previous, letter, matches);
        }
    }

    private static double weightedDistance(String input, String target) {
        double[] beforePrevious = null;
        double[] previous = new double[input.length() + 1];
        for (int j = 1; j < previous.length; j++) {
            previous[j] = previous[j - 1] + removalCost(input, j - 1);
        }
        for (int i = 1; i <= target.length(); i++) {
            double[] current = new double[input.length() + 1];
            current[0] = previous[0] + removalCost(target, i - 1);
            for (int j = 1; j < current.length; j++) {
                char a = input.charAt(j - 1), b = target.charAt(i - 1);
                double substitute = previous[j - 1] + (a == b ? 0 : substitutionCost(a, b));
                double insert = previous[j] + removalCost(target, i - 1);
                double delete = current[j - 1] + removalCost(input, j - 1);
                double value = Math.min(substitute, Math.min(insert, delete));
                if (beforePrevious != null && j > 1 && a == target.charAt(i - 2)
                        && input.charAt(j - 2) == b) {
                    value = Math.min(value, beforePrevious[j - 2] + 0.55);
                }
                current[j] = value;
            }
            beforePrevious = previous;
            previous = current;
        }
        return previous[input.length()];
    }

    private static double removalCost(String text, int index) {
        char c = text.charAt(index);
        if (c == '\'') return 0.7;
        return ((index > 0 && text.charAt(index - 1) == c)
                || (index + 1 < text.length() && text.charAt(index + 1) == c)) ? 0.85 : 1.0;
    }

    private static double substitutionCost(char a, char b) {
        if (a == '\'' || b == '\'') return 1.0;
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        char lowerA = Character.toLowerCase(a), lowerB = Character.toLowerCase(b);
        int rowA = -1, rowB = -1, columnA = -1, columnB = -1;
        for (int row = 0; row < rows.length; row++) {
            int indexA = rows[row].indexOf(lowerA), indexB = rows[row].indexOf(lowerB);
            if (indexA >= 0) { rowA = row; columnA = indexA; }
            if (indexB >= 0) { rowB = row; columnB = indexB; }
        }
        if (rowA < 0 || rowB < 0) return 1.0;
        double dx = columnA + rowA * 0.5 - columnB - rowB * 0.5;
        double dy = rowA - rowB;
        return dx * dx + dy * dy <= 1.3 ? 0.65 : 1.0;
    }

    private enum CaseStyle { LOWER, TITLE, UPPER, MIXED }

    private static CaseStyle caseStyle(String typed) {
        int letters = 0, upper = 0;
        boolean initialUpper = false, laterUpper = false;
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                if (c >= 'A' && c <= 'Z') {
                    upper++;
                    if (letters == 0) initialUpper = true;
                    else laterUpper = true;
                }
                letters++;
            }
        }
        if (upper == 0) return CaseStyle.LOWER;
        if (upper == letters) return CaseStyle.UPPER;
        if (initialUpper && !laterUpper) return CaseStyle.TITLE;
        return CaseStyle.MIXED;
    }

    private static String display(String word, CaseStyle style, String typed) {
        String result = word;
        if (style == CaseStyle.UPPER) result = result.toUpperCase(Locale.ROOT);
        else if (style == CaseStyle.TITLE && !result.isEmpty()) {
            result = result.substring(0, 1).toUpperCase(Locale.ROOT) + result.substring(1);
        }
        if (typed.indexOf('’') >= 0) result = result.replace('\'', '’');
        return result;
    }

    private static String normalize(String word, boolean korean) {
        if (word == null || word.isEmpty()) return null;
        String text = Normalizer.normalize(word, Normalizer.Form.NFC);
        if (korean) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!(c >= '가' && c <= '힣') && !(c >= '\u1100' && c <= '\u11ff')
                        && !(c >= '\u3131' && c <= '\u318e')) return null;
            }
            return text;
        }
        text = text.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (text.charAt(0) == '\'' || text.charAt(text.length() - 1) == '\'') return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') continue;
            if (c == '\'' && i > 0 && text.charAt(i - 1) != '\'') continue;
            return null;
        }
        return text;
    }

    private static String keysFor(String text, boolean korean) {
        return korean ? SwipeDecoder.keySequenceForWord(text) : text;
    }

    private static int countSyllables(String text) {
        int result = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= '가' && text.charAt(i) <= '힣') result++;
        }
        return result;
    }

    private static boolean onlySyllables(String text) { return countSyllables(text) == text.length(); }

    private static int letterCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) != '\'') count++;
        return count;
    }

    private static boolean singleTransposition(String a, String b) {
        if (a.length() != b.length()) return false;
        int mismatch = 0;
        while (mismatch < a.length() && a.charAt(mismatch) == b.charAt(mismatch)) mismatch++;
        if (mismatch + 1 >= a.length() || a.charAt(mismatch) != b.charAt(mismatch + 1)
                || a.charAt(mismatch + 1) != b.charAt(mismatch)) return false;
        for (int i = mismatch + 2; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) return false;
        return true;
    }

    /** Array-backed trie. Zero is the null link; node zero is the root. */
    private static final class TrieBuilder {
        char[] letters = new char[1024];
        int[] firstChildren = new int[1024];
        int[] nextSiblings = new int[1024];
        int[] terminals = new int[1024];
        final int[] nextWords;
        int size = 1;

        TrieBuilder(int wordCount) { nextWords = new int[wordCount]; }

        void insert(String key, int wordIndex) {
            int node = 0;
            for (int i = 0; i < key.length(); i++) {
                char letter = key.charAt(i);
                int child = firstChildren[node];
                while (child != 0 && letters[child] != letter) child = nextSiblings[child];
                if (child == 0) {
                    if (size == letters.length) grow();
                    child = size++;
                    letters[child] = letter;
                    nextSiblings[child] = firstChildren[node];
                    firstChildren[node] = child;
                }
                node = child;
            }
            nextWords[wordIndex] = terminals[node];
            terminals[node] = wordIndex + 1;
        }

        void grow() {
            int length = letters.length * 2;
            letters = Arrays.copyOf(letters, length);
            firstChildren = Arrays.copyOf(firstChildren, length);
            nextSiblings = Arrays.copyOf(nextSiblings, length);
            terminals = Arrays.copyOf(terminals, length);
        }
    }
}
