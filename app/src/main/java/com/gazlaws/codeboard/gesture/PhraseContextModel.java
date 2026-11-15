package com.gazlaws.codeboard.gesture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small authored conversational seed with up to four preceding words, backed off to corpus counts. */
public final class PhraseContextModel implements TypingCorrector.ContextModel {
    private final Map<String, List<String>> continuations = new HashMap<>();
    private final TypingCorrector.ContextModel base;
    public PhraseContextModel(InputStream input, TypingCorrector.ContextModel base) throws IOException {
        this.base = base;
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("#")) continue;
            String[] words = line.trim().split("\\s+");
            for (int i = 1; i < words.length; i++) {
                String word = AdaptiveTypingModel.normalize(words[i]);
                if (word == null) continue;
                for (int n = 1; n <= Math.min(4, i); n++) {
                    StringBuilder prefix = new StringBuilder();
                    boolean valid = true;
                    for (int j = i - n; j < i; j++) {
                        String previous = AdaptiveTypingModel.normalize(words[j]);
                        if (previous == null) { valid = false; break; }
                        if (prefix.length() > 0) prefix.append(' ');
                        prefix.append(previous);
                    }
                    if (valid) {
                        List<String> choices = continuations.get(prefix.toString());
                        if (choices == null) { choices = new ArrayList<>(); continuations.put(prefix.toString(), choices); }
                        if (!choices.contains(word)) choices.add(word);
                    }
                }
            }
        }
    }

    @Override public TypingCorrector.ContextEvidence score(String previous, String candidate) {
        TypingCorrector.ContextEvidence original = base == null ? null : base.score(previous, candidate);
        long pair = original == null ? 0 : original.bigramCount;
        long triple = original == null ? 0 : original.trigramCount;
        List<String> words = AdaptiveTypingModel.contextWords(previous);
        for (int n = words.size(); n > 0; n--) {
            List<String> choices = continuations.get(android.text.TextUtils.join(" ", words.subList(words.size() - n, words.size())));
            if (choices != null && choices.contains(AdaptiveTypingModel.normalize(candidate))) {
                // Authored examples are weak suggestion evidence, not measured corpus frequencies.
                if (n == 1) pair = Math.max(pair, 100);
                else triple = Math.max(triple, 100L * n);
                break;
            }
        }
        return new TypingCorrector.ContextEvidence(pair, triple);
    }

    @Override public List<String> predict(String previous, int limit) {
        ArrayList<String> result = new ArrayList<>();
        if (limit <= 0) return result;
        List<String> words = AdaptiveTypingModel.contextWords(previous);
        for (int n = words.size(); n > 0; n--) {
            List<String> choices = continuations.get(android.text.TextUtils.join(" ", words.subList(words.size() - n, words.size())));
            if (choices != null) add(result, choices, limit);
        }
        if (base != null) add(result, base.predict(previous, limit), limit);
        return result;
    }
    private static void add(List<String> result, List<String> choices, int limit) {
        for (String word : choices) {
            if (result.size() >= limit) return;
            if (!result.contains(word)) result.add(word);
        }
    }
    @Override public long bigramOmittedUpperBound() { return base == null ? 210 : base.bigramOmittedUpperBound(); }
    @Override public long trigramOmittedUpperBound() { return base == null ? 130 : base.trigramOmittedUpperBound(); }
}
