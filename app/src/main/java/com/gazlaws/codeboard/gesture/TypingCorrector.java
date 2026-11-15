package com.gazlaws.codeboard.gesture;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
    // Misspelling suggestions need enough prior to prefer a common word with a missing letter
    // over an obscure word reached by a neighboring-key substitution. The much smaller original
    // prior remains in force for automatic replacement of an existing dictionary word.
    private static final double FREQUENCY_WEIGHT = 0.70;
    private static final double EXACT_FREQUENCY_WEIGHT = 0.18;
    private static final double SHORT_WORD_FREQUENCY_WEIGHT = 1.9;
    // Exact-word automatic replacements retain a conservative edit cost and bounded unigram prior.
    // Suggestions can use observed counts optimistically, but an automatic replacement compares
    // against the estimated upper bound for omitted counts. In that stricter
    // comparison a candidate must beat the unedited word by 0.55 and every other alternative
    // by 0.40 edit-cost units. An unedited, common word has an additional bounded prior, and
    // observed context for it provides extra protection even when its count is below the floor.
    // A frequency advantage alone is at most 0.18, less than one edit's cost.
    private static final double EXACT_SUGGESTION_CONTEXT_WEIGHT = 0.50;
    private static final double EXACT_CONFIDENCE_CONTEXT_WEIGHT = 0.32;
    private static final double EXACT_ORIGINAL_FREQUENCY_WEIGHT = 0.20;
    private static final double EXACT_OBSERVED_ORIGINAL_WEIGHT = 0.30;
    private static final double EXACT_MIN_ORIGINAL_MARGIN = 0.55;
    private static final double EXACT_MIN_RUNNER_UP_MARGIN = 0.40;
    private static final double ORIGINAL_TRIGRAM_VETO_RATIO = 4.0;

    /** An optional offline source of counts for words following the given preceding text. */
    public interface ContextModel {
        ContextEvidence score(String precedingText, String candidate);

        /** Prepares the preceding text once when comparing several candidate words. */
        default PreparedContext prepare(String precedingText) {
            return candidate -> score(precedingText, candidate);
        }

        /** Conservative estimate for a bigram omitted from a truncated source. */
        default long bigramOmittedUpperBound() { return Long.MAX_VALUE; }

        /** Conservative estimate for a trigram omitted from a truncated source. */
        default long trigramOmittedUpperBound() { return Long.MAX_VALUE; }

        /** Common continuations after the supplied text, if the model supports prediction. */
        default List<String> predict(String previousContext, int limit) {
            return Collections.emptyList();
        }
    }

    /** A context snapshot that can be reused across candidate lookups. */
    public interface PreparedContext {
        ContextEvidence score(String candidate);
    }

    /** Counts from the context model; a zero means the phrase was not available. */
    public static final class ContextEvidence {
        public final long bigramCount;
        public final long trigramCount;

        public ContextEvidence(long bigramCount, long trigramCount) {
            this.bigramCount = Math.max(0, bigramCount);
            this.trigramCount = Math.max(0, trigramCount);
        }
    }

    /** The typed text is the first candidate. A correction is present only when confidence is high. */
    public static final class Result {
        public final List<String> candidates;
        public final String correction;
        /** The highest-ranked eligible alternative, even when it is not a confident correction. */
        public final String bestSuggestion;
        /** A spelling-only choice suitable for most-likely automatic replacement. */
        public final String automaticSuggestion;
        /** A separately gated, high-confidence alternative to an exact English dictionary word. */
        public final String contextCorrection;
        public final boolean exactWord;

        private Result(List<String> candidates, String correction, String bestSuggestion,
                       boolean exactWord) {
            this(candidates, correction, bestSuggestion, null, null, exactWord);
        }

        private Result(List<String> candidates, String correction, String bestSuggestion,
                       String contextCorrection, boolean exactWord) {
            this(candidates, correction, bestSuggestion, null, contextCorrection, exactWord);
        }

        Result(List<String> candidates, String correction, String bestSuggestion,
                       String automaticSuggestion, String contextCorrection, boolean exactWord) {
            this.candidates = Collections.unmodifiableList(candidates);
            this.correction = correction;
            this.bestSuggestion = bestSuggestion;
            this.automaticSuggestion = automaticSuggestion;
            this.contextCorrection = contextCorrection;
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

    private static final class ExactMatch {
        final Match match;
        final ContextEvidence evidence;
        final double suggestionScore;
        final double confidenceScore;

        ExactMatch(Match match, ContextEvidence evidence, double suggestionScore,
                   double confidenceScore) {
            this.match = match;
            this.evidence = evidence;
            this.suggestionScore = suggestionScore;
            this.confidenceScore = confidenceScore;
        }
    }

    private static final Comparator<Match> MATCH_ORDER = (a, b) -> {
        int order = Double.compare(a.score, b.score);
        if (order != 0) return order;
        order = Integer.compare(b.word.frequency, a.word.frequency);
        return order != 0 ? order : a.word.text.compareTo(b.word.text);
    };

    private final boolean korean;
    private final ContextModel contextModel;
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
        this(entries, korean, null);
    }

    /** Adds an optional context model supplied for the active language. */
    public TypingCorrector(List<SwipeDecoder.Entry> entries, boolean korean, ContextModel contextModel) {
        this.korean = korean;
        this.contextModel = contextModel;
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
     * Unsupported tokens and unusually long tokens are never changed.
     * Only positive {@code limit} values produce candidates.
     */
    public Result suggest(String typed, int limit) {
        return suggest(typed, limit, null);
    }

    /**
     * Adds English prefix completions for the suggestion strip. This method never turns a
     * completion into a correction: all automatic fields come from the spelling-only search.
     * Call {@link #suggest(String, int, String)} when a word boundary is committed.
     */
    public Result suggestWithCompletions(String typed, int limit, String previousContext) {
        Result spelling = suggest(typed, limit, previousContext);
        if (korean || limit <= 1 || typed == null) return spelling;
        String normalized = normalize(typed, false);
        if (normalized == null || normalized.length() < 2 || normalized.length() > MAX_INPUT_LENGTH
                || caseStyle(typed) == CaseStyle.MIXED) return spelling;

        int node = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int child = firstChildren[node];
            while (child != 0 && letters[child] != normalized.charAt(i)) child = nextSiblings[child];
            if (child == 0) return spelling;
            node = child;
        }
        ArrayList<Word> completions = new ArrayList<>();
        collectCompletions(node, normalized.length(), completions);
        if (completions.isEmpty()) return spelling;

        PreparedContext context = contextModel == null || previousContext == null
                || previousContext.isEmpty() ? null : contextModel.prepare(previousContext);
        Map<Word, Double> scores = new IdentityHashMap<>();
        for (Word word : completions) {
            double score = Math.log1p(word.frequency) / frequencyScale;
            if (context != null) {
                ContextEvidence evidence = context.score(normalize(word.text, false));
                if (evidence != null) score += Math.min(0.75, 0.35 * Math.max(
                        observedContextSignal(evidence.bigramCount, contextModel.bigramOmittedUpperBound()),
                        observedContextSignal(evidence.trigramCount, contextModel.trigramOmittedUpperBound())));
            }
            scores.put(word, score);
        }
        Collections.sort(completions, (a, b) -> {
            int order = Double.compare(scores.get(b), scores.get(a));
            if (order != 0) return order;
            order = Integer.compare(b.frequency, a.frequency);
            return order != 0 ? order : a.text.compareTo(b.text);
        });

        ArrayList<String> candidates = new ArrayList<>();
        candidates.add(typed);
        int max = Math.min(limit, MAX_RESULTS);
        // Keep the strongest spelling correction visible while typing. On an exact spelling,
        // preserve a saved canonical capitalization before adding completions.
        if (spelling.contextCorrection != null) {
            candidates.add(spelling.contextCorrection);
        } else if (spelling.candidates.size() > 1 && (!spelling.exactWord
                || spelling.candidates.get(1).equalsIgnoreCase(typed))) {
            candidates.add(spelling.candidates.get(1));
        }
        CaseStyle style = caseStyle(typed);
        for (Word completion : completions) {
            if (candidates.size() >= max) break;
            String shown = display(completion.text, style, typed);
            if (!candidates.contains(shown)) candidates.add(shown);
        }
        for (String candidate : spelling.candidates) {
            if (candidates.size() >= max) break;
            if (!candidates.contains(candidate)) candidates.add(candidate);
        }
        return new Result(candidates, spelling.correction, spelling.bestSuggestion,
                spelling.automaticSuggestion, spelling.contextCorrection, spelling.exactWord);
    }

    private void collectCompletions(int node, int prefixLength, List<Word> found) {
        int floor = Math.min(1000, Math.max(1, maxFrequency / 10000));
        for (int terminal = terminals[node]; terminal != 0; terminal = nextWords[terminal - 1]) {
            Word word = words[terminal - 1];
            if (word.keys.length() > prefixLength && word.frequency >= floor) found.add(word);
        }
        for (int child = firstChildren[node]; child != 0; child = nextSiblings[child]) {
            collectCompletions(child, prefixLength, found);
        }
    }

    /**
     * Suggests alternatives using text before the current token as optional context. A strongly
     * supported alternative to an exact English word is returned as contextCorrection.
     * The caller must still protect explicitly saved words and editor-specific identifiers.
     */
    public Result suggest(String typed, int limit, String previousContext) {
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
            String contextual = contextualExactSuggestions(normalized, typed, style, exactEntry,
                    previousContext, candidates, Math.min(limit, MAX_RESULTS));
            return new Result(candidates, null, null, contextual, true);
        }
        if (normalized == null || normalized.length() > MAX_INPUT_LENGTH) {
            return new Result(candidates, null, null, exact);
        }
        CaseStyle style = korean ? CaseStyle.LOWER : caseStyle(typed);
        String keys = keysFor(normalized, korean);
        if (keys == null || keys.isEmpty() || keys.length() > MAX_KEY_LENGTH) {
            return new Result(candidates, null, null, false);
        }

        // Distance-two matches for short English text are shown only with actual context
        // evidence. They must not make an incomplete word an automatic correction.
        boolean contextMayExpand = !korean && keys.length() >= 3 && keys.length() < 5
                && contextModel != null && previousContext != null && !previousContext.isEmpty();
        int maxEdits = (korean ? countSyllables(normalized) >= 3
                : keys.length() >= 5 || contextMayExpand) ? 2 : 1;
        ArrayList<Match> matches = findMatches(keys, maxEdits);
        if (contextMayExpand) retainSupportedTwoEditMatches(matches, previousContext);
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
        Match previousLeader = matches.isEmpty() ? null : matches.get(0);
        rankWithContext(matches, previousContext, !korean && letterCount(normalized) == 3);
        // Never automatically apply the previous winner if context has made another candidate
        // the leading suggestion. The context ranking alone is not a confidence guarantee.
        if (correction != null && !matches.isEmpty() && previousLeader != matches.get(0)
                && !correction.equals(display(matches.get(0).word.text, style, typed))) {
            correction = null;
        }
        int count = Math.min(Math.min(limit, MAX_RESULTS), matches.size() + 1);
        for (Match match : matches) {
            if (candidates.size() >= count) break;
            String shown = display(match.word.text, style, typed);
            if (!shown.equals(typed) && !candidates.contains(shown)) candidates.add(shown);
        }
        String bestSuggestion = topSuggestion(normalized, typed, style, matches);
        String automaticSuggestion = automaticSuggestion(normalized, keys, bestSuggestion,
                matches, previousContext);
        return new Result(candidates, correction, bestSuggestion, automaticSuggestion, null, false);
    }

    private ArrayList<Match> findMatches(String keys, int maxEdits) {
        int[] initial = new int[keys.length() + 1];
        for (int i = 0; i < initial.length; i++) initial[i] = i;
        ArrayList<Match> matches = new ArrayList<>();
        for (int child = firstChildren[0]; child != 0; child = nextSiblings[child]) {
            search(child, keys, maxEdits, initial, null, '\0', matches);
        }
        return matches;
    }

    private void retainSupportedTwoEditMatches(List<Match> matches, String previousContext) {
        PreparedContext prepared = contextModel.prepare(previousContext);
        long bigramFloor = Math.max(1, contextModel.bigramOmittedUpperBound());
        long trigramFloor = Math.max(1, contextModel.trigramOmittedUpperBound());
        for (int i = matches.size() - 1; i >= 0; i--) {
            Match match = matches.get(i);
            if (match.edits <= 1) continue;
            ContextEvidence evidence = prepared == null ? null
                    : prepared.score(normalize(match.word.text, false));
            if (evidence == null || (evidence.bigramCount <= bigramFloor
                    && evidence.trigramCount <= trigramFloor)) matches.remove(i);
        }
    }

    private void rankWithContext(List<Match> matches, String previousContext, boolean shortWord) {
        if (contextModel == null || previousContext == null || previousContext.isEmpty()
                || matches.size() < 2) return;
        PreparedContext prepared = contextModel.prepare(previousContext);
        if (prepared == null) return;
        Map<Match, Double> bonuses = new IdentityHashMap<>();
        boolean hasEvidence = false;
        for (Match match : matches) {
            ContextEvidence evidence = prepared.score(normalize(match.word.text, korean));
            // A low observed count is still useful for display ranking, even though an omitted
            // phrase is not known to have a zero count. Never use this optimistic signal as the
            // confidence test for replacing an exact word.
            double bonus = evidence == null ? 0 : Math.min(0.75, 0.50 * Math.max(
                    observedContextSignal(evidence.bigramCount, contextModel.bigramOmittedUpperBound()),
                    observedContextSignal(evidence.trigramCount, contextModel.trigramOmittedUpperBound())));
            bonuses.put(match, bonus);
            hasEvidence |= bonus > 0;
        }
        if (!hasEvidence) return;
        double extraWeight = shortWord ? SHORT_WORD_FREQUENCY_WEIGHT - FREQUENCY_WEIGHT : 0;
        Collections.sort(matches, (a, b) -> {
            double scoreA = a.score - extraWeight * Math.log1p(a.word.frequency) / frequencyScale
                    - bonuses.get(a);
            double scoreB = b.score - extraWeight * Math.log1p(b.word.frequency) / frequencyScale
                    - bonuses.get(b);
            int order = Double.compare(scoreA, scoreB);
            return order != 0 ? order : MATCH_ORDER.compare(a, b);
        });
    }

    private String contextualExactSuggestions(String normalized, String typed, CaseStyle style,
                                               Word exactEntry, String previousContext,
                                               List<String> candidates, int limit) {
        // Capitalized entries may be names, acronyms, or a user's preferred spelling. The caller
        // additionally protects explicitly saved words, even when they have lowercase spellings.
        if (korean || style != CaseStyle.LOWER || normalized.length() < 3
                || normalized.indexOf('\'') >= 0
                || !exactEntry.text.equals(exactEntry.text.toLowerCase(Locale.ROOT))) return null;

        PreparedContext prepared = contextModel == null || previousContext == null
                || previousContext.isEmpty() ? null : contextModel.prepare(previousContext);
        long bigramFloor = contextModel == null ? Long.MAX_VALUE
                : Math.max(1, contextModel.bigramOmittedUpperBound());
        long trigramFloor = contextModel == null ? Long.MAX_VALUE
                : Math.max(1, contextModel.trigramOmittedUpperBound());
        ArrayList<ExactMatch> ranked = new ArrayList<>();
        ExactMatch original = null;
        int rareFrequency = Math.min(5000, Math.max(1000, maxFrequency / 10000));
        boolean expand = prepared != null && exactEntry.frequency < rareFrequency;
        for (Match match : findMatches(exactEntry.keys, expand ? 2 : 1)) {
            ContextEvidence evidence = prepared == null ? null
                    : prepared.score(normalize(match.word.text, false));
            if (evidence == null) evidence = new ContextEvidence(0, 0);
            // This extra reach is only for display and only when the phrase was actually
            // observed above the model's missing-row comparison floor.
            if (match.edits > 1 && (match.word.keys.length() < 3
                    || (evidence.bigramCount <= bigramFloor
                    && evidence.trigramCount <= trigramFloor))) continue;
            // Bigram and trigram are alternative views of the same context, not independent
            // observations. Taking the maximum avoids inflating a weak trigram with a bigram.
            double suggestionWeight = match.edits > 1 ? 1.20 : EXACT_SUGGESTION_CONTEXT_WEIGHT;
            double suggestionBonus = suggestionWeight * Math.max(
                    Math.log1p((double) evidence.bigramCount / bigramFloor),
                    Math.log1p((double) evidence.trigramCount / trigramFloor));
            if (match.edits > 1) suggestionBonus = Math.min(1.25, suggestionBonus);
            double confidenceBonus = EXACT_CONFIDENCE_CONTEXT_WEIGHT * Math.max(
                    conservativeContextSignal(evidence.bigramCount, bigramFloor),
                    conservativeContextSignal(evidence.trigramCount, trigramFloor));
            // Keep the conservative edit and unigram model for exact-word replacement even
            // though spelling suggestions use a stronger frequency prior.
            double confidenceBase = weightedDistance(exactEntry.keys, match.word.keys, false)
                    - EXACT_FREQUENCY_WEIGHT * Math.log1p(match.word.frequency) / frequencyScale;
            ExactMatch scored = new ExactMatch(match, evidence, match.score - suggestionBonus,
                    confidenceBase - confidenceBonus);
            if (match.edits == 0) original = scored;
            else ranked.add(scored);
        }
        Collections.sort(ranked, (a, b) -> {
            int order = Double.compare(a.suggestionScore, b.suggestionScore);
            return order != 0 ? order : MATCH_ORDER.compare(a.match, b.match);
        });
        for (ExactMatch match : ranked) {
            if (candidates.size() >= limit) break;
            String shown = display(match.match.word.text, style, typed);
            if (!shown.equals(typed) && !candidates.contains(shown)) candidates.add(shown);
        }

        if (prepared == null || original == null || ranked.isEmpty()) return null;
        ExactMatch best = ranked.get(0);
        if (best.match.edits != 1) return null;
        // A specific known phrase favoring the original vetoes a broader bigram. Counts of zero
        // mean missing data, so a candidate without a matching trigram cannot defeat that phrase.
        if (original.evidence.trigramCount > 0
                && best.evidence.trigramCount < ORIGINAL_TRIGRAM_VETO_RATIO
                * original.evidence.trigramCount) return null;
        double originalProtection = EXACT_ORIGINAL_FREQUENCY_WEIGHT
                * Math.log1p(exactEntry.frequency) / frequencyScale;
        originalProtection += EXACT_OBSERVED_ORIGINAL_WEIGHT * Math.max(
                observedContextSupport(original.evidence.bigramCount, bigramFloor),
                observedContextSupport(original.evidence.trigramCount, trigramFloor));
        if (original.confidenceScore - originalProtection - best.confidenceScore
                < EXACT_MIN_ORIGINAL_MARGIN) return null;
        for (int i = 1; i < ranked.size(); i++) {
            if (ranked.get(i).confidenceScore - best.confidenceScore
                    < EXACT_MIN_RUNNER_UP_MARGIN) return null;
        }
        return display(best.match.word.text, style, typed);
    }

    private static double conservativeContextSignal(long count, long omittedUpperBound) {
        return count <= omittedUpperBound ? 0 : Math.log((double) count / omittedUpperBound);
    }

    private static double observedContextSignal(long count, long omittedUpperBound) {
        if (count <= 0) return 0;
        return Math.log1p((double) count / Math.max(1, omittedUpperBound));
    }

    private static double observedContextSupport(long count, long omittedUpperBound) {
        return Math.min(1.0, Math.log1p(count) / Math.log1p(omittedUpperBound));
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

    private String automaticSuggestion(String normalized, String keys, String suggestion,
                                       List<Match> matches, String previousContext) {
        if (suggestion == null || matches.isEmpty()) return null;
        if (korean) return suggestion;
        Match best = matches.get(0);
        int length = letterCount(normalized);
        // Context may surface two-edit alternatives for short text, but those guesses are too
        // broad to commit automatically. They remain available in the suggestion strip.
        if (length <= 4 && best.edits > 1) return null;
        int frequencyFloor = Math.min(30000, Math.max(100, maxFrequency / 1000));
        // Short unknown words often are initials, text shorthand, or a name. Requiring a
        // frequent and plausible single keystroke error avoids forcing "omw" to "mow"
        // and similar guesses while retaining common cases such as "teh" and "wih".
        if (length == 3) {
            if (best.edits != 1 || best.word.frequency < frequencyFloor
                    || letterCount(best.word.keys) < 3) return null;
            if (best.word.keys.length() < keys.length()) return null;
            if (best.word.keys.length() == keys.length()
                    && !singleTransposition(keys, best.word.keys)
                    && !singleAdjacentSubstitution(keys, best.word.keys)
                    && !strongContextFor(best, matches, previousContext)) return null;
        }
        // An all-consonant abbreviation should not collapse into a shorter one. Missing-vowel
        // typos such as "thnks" can still resolve to a longer common spelling.
        if (length == 4 && best.word.frequency < frequencyFloor
                && !strongContextFor(best, matches, previousContext)) return null;
        if (length <= 4 && !hasEnglishVowel(normalized) && !hasEnglishVowel(best.word.keys)
                && best.word.keys.length() <= keys.length()) return null;
        return suggestion;
    }

    private boolean strongContextFor(Match best, List<Match> matches, String previousContext) {
        if (contextModel == null || previousContext == null || previousContext.isEmpty()) return false;
        PreparedContext prepared = contextModel.prepare(previousContext);
        if (prepared == null) return false;
        ContextEvidence evidence = prepared.score(normalize(best.word.text, false));
        if (evidence == null) return false;
        double bestSignal = Math.max(
                (double) evidence.bigramCount / Math.max(1, contextModel.bigramOmittedUpperBound()),
                (double) evidence.trigramCount / Math.max(1, contextModel.trigramOmittedUpperBound()));
        if (bestSignal < 3) return false;
        for (Match alternative : matches) {
            if (alternative == best) continue;
            ContextEvidence other = prepared.score(normalize(alternative.word.text, false));
            if (other != null && Math.max(
                    (double) other.bigramCount / Math.max(1, contextModel.bigramOmittedUpperBound()),
                    (double) other.trigramCount / Math.max(1, contextModel.trigramOmittedUpperBound()))
                    * 2 >= bestSignal) return false;
        }
        return true;
    }

    private static boolean hasEnglishVowel(String word) {
        for (int i = 0; i < word.length(); i++) {
            if ("aeiou".indexOf(word.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private static boolean singleAdjacentSubstitution(String input, String target) {
        if (input.length() != target.length()) return false;
        int differences = 0;
        for (int i = 0; i < input.length(); i++) {
            char a = input.charAt(i), b = target.charAt(i);
            if (a == b) continue;
            if (++differences > 1 || substitutionCost(a, b) >= 1.0) return false;
        }
        return differences == 1;
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
                double editCost = weightedDistance(input, word.keys, !korean);
                double frequencyBonus = (korean ? EXACT_FREQUENCY_WEIGHT : FREQUENCY_WEIGHT)
                        * Math.log1p(word.frequency) / frequencyScale;
                matches.add(new Match(word, current[input.length()], editCost - frequencyBonus));
            }
        }
        for (int child = firstChildren[node]; child != 0; child = nextSiblings[child]) {
            search(child, input, maxEdits, current, previous, letter, matches);
        }
    }

    private static double weightedDistance(String input, String target, boolean favorMissingKey) {
        double[] beforePrevious = null;
        double[] previous = new double[input.length() + 1];
        for (int j = 1; j < previous.length; j++) {
            previous[j] = previous[j - 1] + removalCost(input, j - 1);
        }
        for (int i = 1; i <= target.length(); i++) {
            double[] current = new double[input.length() + 1];
            current[0] = previous[0] + removalCost(target, i - 1) * (favorMissingKey ? 0.8 : 1.0);
            for (int j = 1; j < current.length; j++) {
                char a = input.charAt(j - 1), b = target.charAt(i - 1);
                double substitute = previous[j - 1] + (a == b ? 0 : substitutionCost(a, b));
                double insert = previous[j] + removalCost(target, i - 1) * (favorMissingKey ? 0.8 : 1.0);
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
