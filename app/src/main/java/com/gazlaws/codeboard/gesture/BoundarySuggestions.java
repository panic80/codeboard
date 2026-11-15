package com.gazlaws.codeboard.gesture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offers supported two-word splits. These remain manual choices because identifiers are ambiguous. */
final class BoundarySuggestions {
    private final Map<String, Integer> words = new HashMap<>();
    BoundarySuggestions(List<SwipeDecoder.Entry> entries) {
        for (SwipeDecoder.Entry entry : entries) {
            String word = AdaptiveTypingModel.normalize(entry.word);
            if (word != null) words.put(word, Math.max(entry.frequency, (words.containsKey(word) ? words.get(word) : 0)));
        }
    }
    boolean knows(String word) { return words.containsKey(AdaptiveTypingModel.normalize(word)); }

    List<String> split(String typed, TypingCorrector.ContextModel model) {
        ArrayList<String> result = new ArrayList<>();
        String word = AdaptiveTypingModel.normalize(typed);
        if (word == null || word.length() < 4 || word.length() > 36 || (words.containsKey(word) && words.get(word) >= 5000)) return result;
        // Preserve capitals in acronyms and source identifiers.
        if (!typed.equals(word) && !typed.equals(Character.toUpperCase(word.charAt(0)) + word.substring(1))) return result;
        Map<String, Double> scores = new HashMap<>();
        for (int i = 1; i < word.length(); i++) {
            String a = word.substring(0, i), b = word.substring(i);
            if (!words.containsKey(a) || !words.containsKey(b)) continue;
            if (words.containsKey(word) && Math.min(words.get(a), words.get(b)) < 10L * words.get(word)) continue;
            if (a.length() == 1 && !a.equals("a") && !a.equals("i") && a.charAt(0) < 0xac00) continue;
            TypingCorrector.ContextEvidence evidence = model == null ? null : model.score(a + " ", b);
            if (evidence == null || evidence.bigramCount < 100) continue;
            String split = typed.substring(0, i) + " " + typed.substring(i);
            scores.put(split, Math.log1p(evidence.bigramCount) + .1 * Math.log1p(Math.min(words.get(a), words.get(b))));
            result.add(split);
        }
        java.util.Collections.sort(result, (a, b) -> {
            int order = Double.compare(scores.get(b), scores.get(a));
            return order != 0 ? order : a.compareTo(b);
        });
        return result.subList(0, Math.min(2, result.size()));
    }
}
