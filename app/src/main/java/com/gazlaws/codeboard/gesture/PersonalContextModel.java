package com.gazlaws.codeboard.gesture;

import java.util.ArrayList;
import java.util.List;

/** Interpolates bounded local phrase evidence with the bundled language model. */
final class PersonalContextModel implements TypingCorrector.ContextModel {
    private final TypingCorrector.ContextModel base;
    private final AdaptiveTypingModel personal;
    PersonalContextModel(TypingCorrector.ContextModel base, AdaptiveTypingModel personal) {
        this.base = base; this.personal = personal;
    }
    @Override public TypingCorrector.ContextEvidence score(String previous, String candidate) {
        TypingCorrector.ContextEvidence evidence = base == null ? null : base.score(previous, candidate);
        long pair = evidence == null ? 0 : evidence.bigramCount;
        long triple = evidence == null ? 0 : evidence.trigramCount;
        // Longer learned contexts affect suggestions, but never claim corpus-sized certainty.
        double preference = personal.preference(previous, candidate);
        if (preference >= .2) triple = Math.max(triple, (long) (preference * 600));
        return new TypingCorrector.ContextEvidence(pair, triple);
    }
    @Override public List<String> predict(String previous, int limit) {
        ArrayList<String> result = new ArrayList<>(personal.predict(previous, limit));
        if (base != null) for (String word : base.predict(previous, limit)) {
            if (result.size() >= limit) break;
            if (!result.contains(word)) result.add(word);
        }
        return result;
    }
    @Override public long bigramOmittedUpperBound() { return base == null ? 210 : base.bigramOmittedUpperBound(); }
    @Override public long trigramOmittedUpperBound() { return base == null ? 130 : base.trigramOmittedUpperBound(); }
}
