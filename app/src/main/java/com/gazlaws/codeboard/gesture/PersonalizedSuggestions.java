package com.gazlaws.codeboard.gesture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded reranking; automatic choices still require spelling eligibility or repeated confirmation. */
final class PersonalizedSuggestions {
    static TypingCorrector.Result apply(TypingCorrector.Result result, String typed, String previous,
            AdaptiveTypingModel personal, TouchModel touch, List<TouchModel.Sample> samples,
            List<String> splits, int limit) {
        ArrayList<String> candidates = new ArrayList<>(result.candidates);
        Map<String, Double> score = new HashMap<>();
        for (int i = 1; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            double value = .10 * (i - 1);
            if (personal != null) {
                value -= personal.preference(previous, candidate);
                value -= .4 * Math.min(3, personal.choices(typed, candidate));
                if (personal.rejected(typed, candidate)) value += 1;
            }
            if (touch != null) {
                double cost = touch.cost(samples, candidate);
                double originalCost = touch.cost(samples, typed);
                if (!Double.isNaN(cost) && !Double.isInfinite(cost) && !Double.isNaN(originalCost))
                    value -= Math.min(.35, Math.max(0, originalCost - cost) * .6);
            }
            score.put(candidate, value);
        }
        if (candidates.size() > 2) java.util.Collections.sort(candidates.subList(1, candidates.size()), (a, b) -> {
            int order = Double.compare(score.get(a), score.get(b));
            return order != 0 ? order : Integer.compare(result.candidates.indexOf(a), result.candidates.indexOf(b));
        });
        String correction = result.correction, automatic = result.automaticSuggestion, contextual = result.contextCorrection;
        if (personal != null) {
            if (personal.rejected(typed, correction)) correction = null;
            if (personal.rejected(typed, automatic)) automatic = null;
            if (personal.rejected(typed, contextual)) contextual = null;
        }
        if (candidates.size() > 1 && !splits.contains(candidates.get(1))) {
            String best = candidates.get(1);
            if (!best.equals(automatic) && !result.exactWord) {
                automatic = null; correction = null;
            }
            // Only a candidate already reached by the spelling search can become an automatic preference.
            if (personal != null && (result.exactWord
                    ? personal.contextualChoices(previous, typed, best) : personal.choices(typed, best)) >= 2
                    && !personal.rejected(typed, best) && best.length() <= typed.length() + 2) {
                if (result.exactWord) contextual = best;
                else automatic = best;
            }
        }
        for (int i = splits.size() - 1; i >= 0; i--) if (!candidates.contains(splits.get(i))) candidates.add(1, splits.get(i));
        if (candidates.size() > limit) candidates = new ArrayList<>(candidates.subList(0, limit));
        return new TypingCorrector.Result(candidates, correction, result.bestSuggestion,
                automatic, contextual, result.exactWord);
    }
}
