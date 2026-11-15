package com.gazlaws.codeboard.gesture;

import com.gazlaws.codeboard.gesture.SwipeDecoder.Entry;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TypingCorrectorTest {
    private static final TypingCorrector ENGLISH = new TypingCorrector(asset("en"), false);
    private static final TypingCorrector KOREAN = new TypingCorrector(asset("ko"), true);

    @Test public void actualEnglishDictionariesFindCommonTyposAndRetainTypedInput() {
        assertCorrection(ENGLISH, "teh", "the");
        assertCorrection(ENGLISH, "possivle", "possible");
        assertCorrection(ENGLISH, "wrold", "world");
        assertCorrection(ENGLISH, "helllo", "hello");
        assertCorrection(ENGLISH, "hllo", "hello");
    }

    @Test public void adjacentKeyboardMistakeRanksAboveCompetingWordWithoutUnsafeReplacement() {
        TypingCorrector.Result result = ENGLISH.suggest("hellp", 5);
        assertEquals("hellp", result.candidates.get(0));
        assertEquals("hello", result.candidates.get(1));
        assertTrue(result.candidates.contains("help"));
        assertNull(result.correction);
        assertEquals("hello", result.bestSuggestion);
    }

    @Test public void commonMissingLetterRanksFirstForShortInput() {
        TypingCorrector.Result result = ENGLISH.suggest("wih", 5);
        assertEquals("wih", result.candidates.get(0));
        assertEquals("with", result.candidates.get(1));
        assertEquals("with", result.bestSuggestion);
        assertNull(result.correction);
        assertEquals("With", ENGLISH.suggest("Wih", 5).bestSuggestion);
    }

    @Test public void exactWordsIncludingRareOnesAndCommonContractionsAreNeverReplaced() {
        for (String text : Arrays.asList("the", "hello", "helo", "dont", "don't", "DON’T", "my")) {
            TypingCorrector.Result result = ENGLISH.suggest(text, 6);
            assertTrue(text, result.exactWord);
            assertNull(text, result.correction);
            assertNull(text, result.bestSuggestion);
            assertEquals(text, result.candidates.get(0));
        }
    }

    @Test public void preservesCaseAndAvoidsReplacingCapitalizedCodes() {
        assertCorrection(ENGLISH, "Teh", "The");
        TypingCorrector.Result upper = ENGLISH.suggest("TEH", 4);
        assertEquals("TEH", upper.candidates.get(0));
        assertEquals("THE", upper.candidates.get(1));
        assertNull(upper.correction);
        assertNull(upper.bestSuggestion);
        TypingCorrector.Result mixed = ENGLISH.suggest("tEh", 4);
        assertEquals("tEh", mixed.candidates.get(0));
        assertNull(mixed.correction);
        assertNull(mixed.bestSuggestion);
    }

    @Test public void identifiersUrlsDigitsPunctuationAndMixedScriptsAreNotChanged() {
        for (String text : Arrays.asList("hellp2", "hellp_world", "https://hellp.com",
                "user@example.com", "hellp!", "hellp안녕", "'hellp", "hellp'", "can't''")) {
            TypingCorrector.Result result = ENGLISH.suggest(text, 5);
            assertEquals(text, Collections.singletonList(text), result.candidates);
            assertNull(text, result.correction);
            assertNull(text, result.bestSuggestion);
        }
        assertEquals(Collections.singletonList("안녕abc"), KOREAN.suggest("안녕abc", 5).candidates);
        assertNull(KOREAN.suggest("안녕abc", 5).correction);
        assertNull(KOREAN.suggest("안녕abc", 5).bestSuggestion);
    }

    @Test public void noAutomaticReplacementForShortWordsOrAmbiguousAlternatives() {
        TypingCorrector english = new TypingCorrector(entries("the", 10000, "she", 10000), false);
        assertNull(english.suggest("th", 5).correction);
        assertNull(english.suggest("th", 5).bestSuggestion);
        assertNull(english.suggest("tge", 5).correction);
        assertEquals("the", english.suggest("tge", 5).bestSuggestion);
        TypingCorrector ambiguous = new TypingCorrector(entries("hello", 3000, "help", 3000), false);
        assertNull(ambiguous.suggest("hellp", 5).correction);
        TypingCorrector almostAmbiguous = new TypingCorrector(entries("hello", 1000, "help", 100), false);
        assertNull(almostAmbiguous.suggest("hellp", 5).correction);
        TypingCorrector korean = new TypingCorrector(entries("안녕", 10000), true);
        assertTrue(korean.suggest("안넝", 5).candidates.contains("안녕"));
        assertNull(korean.suggest("안넝", 5).correction);
        assertNull(korean.suggest("안넝", 5).bestSuggestion);
    }

    @Test public void contractionsSupportApostropheTyposAndCurlyApostrophes() {
        TypingCorrector english = new TypingCorrector(entries("can't", 50000), false);
        assertCorrection(english, "cna't", "can't");
        assertCorrection(english, "cna’t", "can’t");
        assertTrue(english.suggest("can’t", 2).exactWord);
    }

    @Test public void personalWordsRetainCanonicalCaseAndAreMatchedWithoutCase() {
        TypingCorrector english = new TypingCorrector(entries(
                "albert", 300, "Albert", 2000000, "JavaScript", 2000000), false);
        TypingCorrector.Result exact = english.suggest("albert", 3);
        assertTrue(exact.exactWord);
        assertEquals(Arrays.asList("albert", "Albert"), exact.candidates);
        assertNull(exact.correction);
        assertNull(exact.bestSuggestion);
        assertCorrection(english, "Albrt", "Albert");
        assertCorrection(english, "albrt", "Albert");
        assertNull(english.suggest("ALBRT", 3).correction);
        assertNull(english.suggest("ALBRT", 3).bestSuggestion);
        assertEquals("ALBERT", english.suggest("ALBRT", 3).candidates.get(1));
        assertEquals("JavaScript", english.suggest("javasript", 3).candidates.get(1));
        assertEquals("JavaScript", english.suggest("JavaScrit", 3).candidates.get(1));
        assertNull(english.suggest("JavaScrit", 3).correction);
        assertNull(english.suggest("JavaScrit", 3).bestSuggestion);
        TypingCorrector override = new TypingCorrector(entries("the", 22761659, "The", 2000000), false);
        assertEquals(Arrays.asList("the", "The"), override.suggest("the", 3).candidates);
        assertCorrection(override, "teh", "The");
        String longPersonalWord = "abcdefghijklmnopqrstuvwxyzabcdefghijklmno";
        TypingCorrector longWords = new TypingCorrector(entries(longPersonalWord, 2000000), false);
        assertTrue(longWords.suggest(longPersonalWord, 2).exactWord);
    }

    @Test public void actualKoreanDictionaryFindsSingleJamoMistakes() {
        assertCorrection(KOREAN, "안녕하세여", "안녕하세요");
        assertCorrection(KOREAN, "감사합니디", "감사합니다");
        TypingCorrector.Result exact = KOREAN.suggest("안녕하세요", 4);
        assertTrue(exact.exactWord);
        assertNull(exact.correction);
        assertNull(exact.bestSuggestion);
        String decomposed = Normalizer.normalize("안녕하세요", Normalizer.Form.NFD);
        TypingCorrector.Result decomposedExact = KOREAN.suggest(decomposed, 4);
        assertTrue(decomposedExact.exactWord);
        assertEquals(decomposed, decomposedExact.candidates.get(0));
        assertNull(decomposedExact.correction);
        assertNull(decomposedExact.bestSuggestion);
    }

    @Test public void koreanUsesShiftSensitiveJamoAndAvoidsAutomaticAmbiguity() {
        TypingCorrector korean = new TypingCorrector(entries("땀", 500), true);
        assertTrue(korean.suggest("담", 4).candidates.contains("땀"));
        assertNull(korean.suggest("담", 4).correction);
        assertNull(korean.suggest("담", 4).bestSuggestion);
        TypingCorrector collisions = new TypingCorrector(entries("가", 500, "ㄱㅏ", 600), true);
        assertTrue(collisions.suggest("까", 4).candidates.contains("가"));
        assertTrue(collisions.suggest("까", 4).candidates.contains("ㄱㅏ"));
        korean = new TypingCorrector(entries("안녕하세요", 5000, "안녕하세야", 4000), true);
        TypingCorrector.Result ambiguous = korean.suggest("안녕하세여", 5);
        assertTrue(ambiguous.candidates.contains("안녕하세요"));
        assertNull(ambiguous.correction);
        assertEquals(ambiguous.candidates.get(1), ambiguous.bestSuggestion);
        assertEquals("안녕하세요", ambiguous.bestSuggestion);
        TypingCorrector.Result incomplete = korean.suggest("안녕하세ㅕ", 5);
        assertTrue(incomplete.candidates.contains("안녕하세요"));
        assertNull(incomplete.bestSuggestion);
        TypingCorrector.Result twoEdits = korean.suggest("안녕하새여", 5);
        assertNull(twoEdits.correction);
        assertEquals("안녕하세요", twoEdits.bestSuggestion);
        korean = new TypingCorrector(entries("안녕하세요", 5000, "안녕하세여", 1), true);
        assertTrue(korean.suggest("안녕하세여", 3).exactWord);
        assertNull(korean.suggest("안녕하세여", 3).correction);
        assertNull(korean.suggest("안녕하세여", 3).bestSuggestion);
        korean = new TypingCorrector(entries("정말", 5000), true);
        assertTrue(korean.suggest("정마료", 5).candidates.contains("정말"));
        assertNull(korean.suggest("정마료", 5).correction);
        assertNull(korean.suggest("정마료", 5).bestSuggestion);
    }

    @Test public void twoEditMatchesRemainSuggestionsOnly() {
        TypingCorrector english = new TypingCorrector(entries("possible", 10000), false);
        TypingCorrector.Result result = english.suggest("possivls", 5);
        assertTrue(result.candidates.contains("possible"));
        assertNull(result.correction);
        assertEquals("possible", result.bestSuggestion);
    }

    @Test public void topSuggestionCanUseAnAddedDictionaryWord() {
        List<Entry> entries = asset("en");
        entries.add(new Entry("autocorrect", 100000));
        TypingCorrector english = new TypingCorrector(entries, false);
        TypingCorrector.Result result = english.suggest("autocorrext", 5);
        assertEquals("autocorrect", result.bestSuggestion);
        assertEquals(result.candidates.get(1), result.bestSuggestion);
        TypingCorrector.Result missingLetter = english.suggest("autocrrect", 5);
        assertEquals("autocorrect", missingLetter.bestSuggestion);
        assertEquals(missingLetter.candidates.get(1), missingLetter.bestSuggestion);
    }

    @Test public void exactWordCanUseStrongContextWhileKeepingOtherSuggestions() {
        Counts counts = new Counts();
        counts.put("autocorrect is ", "not", 10282, 0);
        counts.put("the ", "bot", 1000, 0);
        counts.put("chat ", "bot", 1000, 0);
        TypingCorrector english = new TypingCorrector(entries("bot", 944, "not", 4262273,
                "hot", 123994, "pot", 17692), false, counts);

        TypingCorrector.Result result = english.suggest("bot", 5, "autocorrect is ");
        assertTrue(result.exactWord);
        assertEquals(Arrays.asList("bot", "not", "hot", "pot"), result.candidates);
        assertEquals("not", result.contextCorrection);
        assertNull(result.correction);
        assertNull(result.bestSuggestion);
        assertNull(english.suggest("bot", 5).contextCorrection);
        assertNull(english.suggest("bot", 5, "the ").contextCorrection);
        assertNull(english.suggest("bot", 5, "chat ").contextCorrection);
        assertNull(english.suggest("hot", 5, "autocorrect is ").contextCorrection);
        assertNull(english.suggest("pot", 5, "autocorrect is ").contextCorrection);
        assertNull(english.suggest("Bot", 5, "autocorrect is ").contextCorrection);
        assertNull(english.suggest("BOT", 5, "autocorrect is ").contextCorrection);
        assertNull(english.suggest("bOt", 5, "autocorrect is ").contextCorrection);
        assertNull(english.suggest("bot", 0, "autocorrect is ").contextCorrection);
    }

    @Test public void strongContextCanCorrectCommonWordsAcrossOneEditTypes() {
        Counts counts = new Counts();
        counts.put("we will ", "meet", 100000, 0);
        counts.put("we have a ", "plain", 100000, 0);
        counts.put("we will ", "plan", 100000, 0);
        counts.put("came ", "from", 100000, 0);
        // Each original is common, and the target is less common. Neither rarity nor a 20x
        // unigram advantage should be needed when the actual contextual evidence is strong.
        TypingCorrector english = new TypingCorrector(entries("meat", 500000, "meet", 20000,
                "plan", 500000, "plain", 20000, "plant", 500000,
                "form", 500000, "from", 20000), false, counts);
        assertExactContextCorrection(english, "we will ", "meat", "meet"); // Nonadjacent key.
        assertExactContextCorrection(english, "we have a ", "plan", "plain"); // Missing letter.
        assertExactContextCorrection(english, "we will ", "plant", "plan"); // Extra letter.
        assertExactContextCorrection(english, "came ", "form", "from"); // Transposition.
    }

    @Test public void exactWordAlternativesRemainVisibleWithoutAutomaticConfidence() {
        Counts counts = new Counts();
        counts.put("weak ", "meet", 1000, 0);
        TypingCorrector english = new TypingCorrector(entries("meat", 500000, "meet", 20000),
                false, counts);
        TypingCorrector.Result result = english.suggest("meat", 5, "weak ");
        assertEquals(Arrays.asList("meat", "meet"), result.candidates);
        assertTrue(result.exactWord);
        assertNull(result.contextCorrection);
        assertNull(result.correction);
        assertNull(result.bestSuggestion);

        // The original remains first, and a missing model or missing evidence cannot trigger
        // automatic replacement even when a competing word is far more frequent.
        TypingCorrector frequentTarget = new TypingCorrector(entries("meat", 1, "meet", 2000000),
                false, counts);
        assertNull(frequentTarget.suggest("meat", 5, "missing ").contextCorrection);
        assertNull(frequentTarget.suggest("meat", 5).contextCorrection);
        assertTrue(frequentTarget.suggest("meat", 5, "missing ").candidates.contains("meet"));
    }

    @Test public void exactWordReplacementRequiresClearWinnerAndRespectsKnownOriginalTrigram() {
        Counts counts = new Counts();
        counts.put("ambiguous ", "meet", 100000, 0);
        counts.put("ambiguous ", "seat", 90000, 0);
        counts.put("trigram ", "meet", 1000000, 0);
        counts.put("trigram ", "meat", 0, 500);
        TypingCorrector english = new TypingCorrector(entries("meat", 500000, "meet", 20000,
                "seat", 20000), false, counts);
        TypingCorrector.Result ambiguous = english.suggest("meat", 5, "ambiguous ");
        assertTrue(ambiguous.candidates.contains("meet"));
        assertTrue(ambiguous.candidates.contains("seat"));
        assertNull(ambiguous.contextCorrection);
        TypingCorrector.Result originalTrigram = english.suggest("meat", 5, "trigram ");
        assertTrue(originalTrigram.candidates.contains("meet"));
        assertNull(originalTrigram.contextCorrection);
    }

    @Test public void bundledEnglishContextHandlesRealSentencesAndSentenceBoundaries() throws Exception {
        File file = new File("src/main/assets/swipe/en_context.tsv");
        if (!file.isFile()) file = new File("app/src/main/assets/swipe/en_context.tsv");
        TypingCorrector.ContextModel context;
        try (FileInputStream input = new FileInputStream(file)) {
            context = EnglishContextModel.load(input);
        }
        TypingCorrector english = new TypingCorrector(asset("en"), false, context);
        assertEquals("autocorrect", english.suggest("autocrrect", 5, "the ").bestSuggestion);
        TypingCorrector.Result isBot = english.suggest("bot", 5, "autocorrect is ");
        assertTrue(isBot.exactWord);
        assertEquals("not", isBot.contextCorrection);
        assertEquals("not", isBot.candidates.get(1));
        assertNull(english.suggest("bot", 5, "the ").contextCorrection);
        assertNull(english.suggest("bot", 5, "chat ").contextCorrection);
        assertNull(english.suggest("hot", 5, "it is ").contextCorrection);
        assertNull(english.suggest("Bob", 5, "it is ").contextCorrection);
        assertNull(english.suggest("bot", 5, "it is. ").contextCorrection);
        assertNull(english.suggest("bot", 5, "it is\n").contextCorrection);
        assertExactContextCorrection(english, "in ", "they", "the");
        TypingCorrector.Result weakMeet = english.suggest("meat", 5, "I will ");
        assertNull(weakMeet.contextCorrection);
        assertTrue(weakMeet.candidates.contains("meet"));
        for (String[] phrase : new String[][]{{"is ", "hot"}, {"it is ", "hot"},
                {"the ", "bot"}, {"to ", "eat"}, {"to ", "beat"}, {"to ", "heat"},
                {"I want ", "too"}, {"on ", "them"}, {"you ", "gave"}}) {
            assertNull(phrase[0] + phrase[1], english.suggest(phrase[1], 5, phrase[0]).contextCorrection);
        }
    }

    @Test public void contextDoesNotGuessFromWeakMissingOrConflictingEvidence() {
        Counts counts = new Counts();
        counts.put("weak ", "not", 8399, 0); // 40 times the missing-count bound is 8400.
        counts.put("close ", "not", 10282, 0);
        counts.put("close ", "bot", 260, 0);
        counts.put("truncated ", "not", 1200, 0);
        counts.put("truncated ", "bot", 29, 0);
        counts.put("conflict ", "not", 10282, 0);
        counts.put("conflict ", "bot", 0, 500);
        counts.put("ambiguous ", "not", 10282, 0);
        counts.put("ambiguous ", "hot", 9000, 0);
        counts.put("different ", "cat", 500000, 0);
        TypingCorrector english = new TypingCorrector(entries("bot", 944, "not", 4262273,
                "hot", 123994, "cat", 100000), false, counts);
        for (String context : Arrays.asList("weak ", "close ", "truncated ", "conflict ", "ambiguous ",
                "different ", "missing ")) {
            assertNull(context, english.suggest("bot", 5, context).contextCorrection);
        }
        // Saved words are protected by the controller, not by assuming the synthetic dictionary
        // frequency itself forbids contextual correction.
        TypingCorrector savedFrequency = new TypingCorrector(entries("meat", 2000000,
                "meet", 1000), false, counts);
        counts.put("very strong ", "meet", 1000000, 0);
        assertEquals("meet", savedFrequency.suggest("meat", 5, "very strong ").contextCorrection);
        counts.put("autocorrect is ", "not", 10282, 0);
        TypingCorrector name = new TypingCorrector(entries("Bot", 944, "not", 4262273), false, counts);
        assertNull(name.suggest("bot", 5, "autocorrect is ").contextCorrection);
    }

    @Test public void contextCanRankMisspellingsWithoutWeakeningConfidenceOrKoreanProtection() {
        Counts counts = new Counts();
        counts.put("need ", "help", 100000, 0);
        TypingCorrector english = new TypingCorrector(entries("hello", 3000, "help", 3000), false, counts);
        assertEquals("hello", english.suggest("hellp", 5).bestSuggestion);
        TypingCorrector.Result withContext = english.suggest("hellp", 5, "need ");
        assertEquals("help", withContext.bestSuggestion);
        assertEquals("help", withContext.candidates.get(1));
        assertNull(withContext.correction);
        assertNull(withContext.contextCorrection);

        TypingCorrector korean = new TypingCorrector(entries("안녕하세요", 5000, "안녕하세여", 1), true, counts);
        TypingCorrector.Result koreanExact = korean.suggest("안녕하세여", 3, "need ");
        assertTrue(koreanExact.exactWord);
        assertNull(koreanExact.contextCorrection);
        assertNull(koreanExact.correction);
    }

    @Test public void topSuggestionUsesRankingRegardlessOfFrequencyFloorOrCandidateLimit() {
        TypingCorrector english = new TypingCorrector(entries("hello", 1, "help", 1), false);
        TypingCorrector.Result result = english.suggest("hellp", 5);
        assertEquals("hello", result.candidates.get(1));
        assertNull(result.correction);
        assertEquals(result.candidates.get(1), result.bestSuggestion);

        TypingCorrector.Result limited = english.suggest("hellp", 1);
        assertEquals(Collections.singletonList("hellp"), limited.candidates);
        assertEquals("hello", limited.bestSuggestion);
        assertNull(english.suggest("hellp", 0).bestSuggestion);
        assertNull(english.suggest("zzzzzz", 5).bestSuggestion);
    }

    @Test public void handlesLimitsNullsAndImmutableResults() {
        assertTrue(ENGLISH.suggest(null, 3).candidates.isEmpty());
        assertTrue(ENGLISH.suggest("", 3).candidates.isEmpty());
        assertTrue(ENGLISH.suggest("hello", 0).exactWord);
        assertTrue(ENGLISH.suggest("teh", 0).candidates.isEmpty());
        assertEquals(Collections.singletonList("teh"), ENGLISH.suggest("teh", 1).candidates);
        assertTrue(ENGLISH.suggest("teh", 100).candidates.size() <= 32);
        try {
            ENGLISH.suggest("teh", 3).candidates.add("bad");
            fail("result candidates must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void assertCorrection(TypingCorrector corrector, String typed, String expected) {
        TypingCorrector.Result result = corrector.suggest(typed, 5);
        assertFalse(typed, result.exactWord);
        assertEquals(typed, typed, result.candidates.get(0));
        assertEquals(typed, expected, result.candidates.get(1));
        assertEquals(typed, expected, result.correction);
        assertEquals(typed, expected, result.bestSuggestion);
    }

    private static void assertExactContextCorrection(TypingCorrector corrector, String preceding,
                                                      String typed, String expected) {
        TypingCorrector.Result result = corrector.suggest(typed, 5, preceding);
        assertTrue(result.exactWord);
        assertEquals(typed, result.candidates.get(0));
        assertEquals(expected, result.candidates.get(1));
        assertEquals(expected, result.contextCorrection);
        assertNull(result.correction);
        assertNull(result.bestSuggestion);
    }

    private static List<Entry> entries(Object... values) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) entries.add(new Entry((String) values[i], (Integer) values[i + 1]));
        return entries;
    }

    private static final class Counts implements TypingCorrector.ContextModel {
        private final Map<String, TypingCorrector.ContextEvidence> counts = new HashMap<>();

        void put(String preceding, String candidate, long bigram, long trigram) {
            counts.put(preceding + "\u0000" + candidate,
                    new TypingCorrector.ContextEvidence(bigram, trigram));
        }

        @Override public TypingCorrector.ContextEvidence score(String preceding, String candidate) {
            TypingCorrector.ContextEvidence result = counts.get(preceding + "\u0000" + candidate);
            return result == null ? new TypingCorrector.ContextEvidence(0, 0) : result;
        }

        @Override public long bigramOmittedUpperBound() { return 210; }
        @Override public long trigramOmittedUpperBound() { return 130; }
    }

    private static List<Entry> asset(String language) {
        File file = new File("src/main/assets/swipe/" + language + ".tsv");
        if (!file.isFile()) file = new File("app/src/main/assets/swipe/" + language + ".tsv");
        List<Entry> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\t", 2);
                result.add(new Entry(parts[0], Integer.parseInt(parts[1])));
            }
        } catch (Exception error) {
            throw new AssertionError("Could not load dictionary " + file, error);
        }
        return result;
    }
}
