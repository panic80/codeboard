package com.gazlaws.codeboard.gesture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable, offline word-pair and word-triple evidence for English typing.
 * Zero means unavailable rather than that a phrase never occurs. The inputs and
 * their truncation are described in assets/swipe/CONTEXT-NOTICE.md.
 */
public final class EnglishContextModel implements TypingCorrector.ContextModel {
    // Conservative comparison floors for missing rows. The Leipzig list was
    // truncated before case-folding, so these are estimates, not strict bounds.
    private static final long BIGRAM_OMITTED_FLOOR = 210;
    private static final long TRIGRAM_OMITTED_FLOOR = 130;
    private static final int MAX_WORD_LENGTH = 24;
    private static final TypingCorrector.ContextEvidence NO_EVIDENCE =
            new TypingCorrector.ContextEvidence(0, 0);

    private final Map<String, Long> bigrams;
    private final Map<String, Long> trigrams;

    private EnglishContextModel(Map<String, Long> bigrams, Map<String, Long> trigrams) {
        this.bigrams = Collections.unmodifiableMap(bigrams);
        this.trigrams = Collections.unmodifiableMap(trigrams);
    }

    /**
     * Reads UTF-8 rows containing two or three words and a positive tab-delimited
     * count. Invalid rows are ignored. The caller owns and closes the stream.
     */
    public static EnglishContextModel load(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        Map<String, Long> pairs = new HashMap<>();
        Map<String, Long> triples = new HashMap<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3 && fields.length != 4) continue;
            int wordCount = fields.length - 1;
            StringBuilder key = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < wordCount; i++) {
                String word = normalizeWord(fields[i]);
                if (word == null) {
                    valid = false;
                    break;
                }
                if (i > 0) key.append(' ');
                key.append(word);
            }
            if (!valid) continue;
            long count;
            try {
                count = Long.parseLong(fields[wordCount]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (count <= 0) continue;
            Map<String, Long> target = wordCount == 2 ? pairs : triples;
            String phrase = key.toString();
            Long old = target.get(phrase);
            if (old != null) count = count > Long.MAX_VALUE - old ? Long.MAX_VALUE : old + count;
            target.put(phrase, count);
        }
        return new EnglishContextModel(pairs, triples);
    }

    @Override
    public TypingCorrector.ContextEvidence score(String precedingText, String candidate) {
        return prepare(precedingText).score(candidate);
    }

    @Override
    public long bigramOmittedUpperBound() {
        return BIGRAM_OMITTED_FLOOR;
    }

    @Override
    public long trigramOmittedUpperBound() {
        return TRIGRAM_OMITTED_FLOOR;
    }

    /** Extracts the two prior words once for repeated scoring during a swipe. */
    @Override
    public PreparedContext prepare(String precedingText) {
        if (precedingText == null || precedingText.isEmpty()) {
            return new PreparedContext(null, null);
        }
        int end = precedingText.length();
        while (end > 0 && Character.isWhitespace(precedingText.charAt(end - 1))) {
            if (isLineBreak(precedingText.charAt(end - 1))) return new PreparedContext(null, null);
            end--;
        }
        int start = tokenStart(precedingText, end);
        String previous = normalizeWord(precedingText.substring(start, end));
        if (previous == null) return new PreparedContext(null, null);

        int priorEnd = start;
        while (priorEnd > 0 && Character.isWhitespace(precedingText.charAt(priorEnd - 1))) {
            if (isLineBreak(precedingText.charAt(priorEnd - 1))) {
                return new PreparedContext(previous, null);
            }
            priorEnd--;
        }
        int priorStart = tokenStart(precedingText, priorEnd);
        String prior = normalizeWord(precedingText.substring(priorStart, priorEnd));
        return new PreparedContext(previous, prior);
    }

    /** A thread-safe snapshot of the prior text, suitable for repeated candidate lookups. */
    public final class PreparedContext implements TypingCorrector.PreparedContext {
        private final String pairPrefix;
        private final String triplePrefix;

        private PreparedContext(String previous, String prior) {
            pairPrefix = previous == null ? null : previous + " ";
            triplePrefix = previous == null || prior == null ? null : prior + " " + previous + " ";
        }

        @Override
        public TypingCorrector.ContextEvidence score(String candidate) {
            if (pairPrefix == null) return NO_EVIDENCE;
            String word = normalizeWord(candidate);
            if (word == null) return NO_EVIDENCE;
            Long bigram = bigrams.get(pairPrefix + word);
            Long trigram = triplePrefix == null ? null : trigrams.get(triplePrefix + word);
            if (bigram == null && trigram == null) return NO_EVIDENCE;
            return new TypingCorrector.ContextEvidence(
                    bigram == null ? 0 : bigram, trigram == null ? 0 : trigram);
        }
    }

    private static int tokenStart(String text, int end) {
        int start = end;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        return start;
    }

    private static boolean isLineBreak(char ch) {
        return ch == '\n' || ch == '\r';
    }

    private static String normalizeWord(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_WORD_LENGTH) return null;
        String word = value.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        boolean previousApostrophe = true;
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (ch == '\'') {
                if (previousApostrophe || i == word.length() - 1) return null;
                previousApostrophe = true;
            } else if (ch >= 'a' && ch <= 'z') {
                previousApostrophe = false;
            } else {
                return null;
            }
        }
        return word;
    }
}
