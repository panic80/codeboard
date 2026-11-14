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
import java.util.List;

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
    }

    @Test public void exactWordsIncludingRareOnesAndCommonContractionsAreNeverReplaced() {
        for (String text : Arrays.asList("the", "hello", "helo", "dont", "don't", "DON’T")) {
            TypingCorrector.Result result = ENGLISH.suggest(text, 6);
            assertTrue(text, result.exactWord);
            assertNull(text, result.correction);
            assertEquals(text, result.candidates.get(0));
        }
    }

    @Test public void preservesCaseAndAvoidsReplacingCapitalizedCodes() {
        assertCorrection(ENGLISH, "Teh", "The");
        TypingCorrector.Result upper = ENGLISH.suggest("TEH", 4);
        assertEquals("TEH", upper.candidates.get(0));
        assertEquals("THE", upper.candidates.get(1));
        assertNull(upper.correction);
        TypingCorrector.Result mixed = ENGLISH.suggest("tEh", 4);
        assertEquals("tEh", mixed.candidates.get(0));
        assertNull(mixed.correction);
    }

    @Test public void identifiersUrlsDigitsPunctuationAndMixedScriptsAreNotChanged() {
        for (String text : Arrays.asList("hellp2", "hellp_world", "https://hellp.com",
                "user@example.com", "hellp!", "hellp안녕", "'hellp", "hellp'", "can't''")) {
            TypingCorrector.Result result = ENGLISH.suggest(text, 5);
            assertEquals(text, Collections.singletonList(text), result.candidates);
            assertNull(text, result.correction);
        }
        assertEquals(Collections.singletonList("안녕abc"), KOREAN.suggest("안녕abc", 5).candidates);
        assertNull(KOREAN.suggest("안녕abc", 5).correction);
    }

    @Test public void noAutomaticReplacementForShortWordsOrAmbiguousAlternatives() {
        TypingCorrector english = new TypingCorrector(entries("the", 10000, "she", 10000), false);
        assertNull(english.suggest("th", 5).correction);
        assertNull(english.suggest("tge", 5).correction);
        TypingCorrector ambiguous = new TypingCorrector(entries("hello", 3000, "help", 3000), false);
        assertNull(ambiguous.suggest("hellp", 5).correction);
        TypingCorrector almostAmbiguous = new TypingCorrector(entries("hello", 1000, "help", 100), false);
        assertNull(almostAmbiguous.suggest("hellp", 5).correction);
        TypingCorrector korean = new TypingCorrector(entries("안녕", 10000), true);
        assertTrue(korean.suggest("안넝", 5).candidates.contains("안녕"));
        assertNull(korean.suggest("안넝", 5).correction);
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
        assertCorrection(english, "Albrt", "Albert");
        assertCorrection(english, "albrt", "Albert");
        assertNull(english.suggest("ALBRT", 3).correction);
        assertEquals("ALBERT", english.suggest("ALBRT", 3).candidates.get(1));
        assertEquals("JavaScript", english.suggest("javasript", 3).candidates.get(1));
        assertEquals("JavaScript", english.suggest("JavaScrit", 3).candidates.get(1));
        assertNull(english.suggest("JavaScrit", 3).correction);
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
        String decomposed = Normalizer.normalize("안녕하세요", Normalizer.Form.NFD);
        TypingCorrector.Result decomposedExact = KOREAN.suggest(decomposed, 4);
        assertTrue(decomposedExact.exactWord);
        assertEquals(decomposed, decomposedExact.candidates.get(0));
        assertNull(decomposedExact.correction);
    }

    @Test public void koreanUsesShiftSensitiveJamoAndAvoidsAutomaticAmbiguity() {
        TypingCorrector korean = new TypingCorrector(entries("땀", 500), true);
        assertTrue(korean.suggest("담", 4).candidates.contains("땀"));
        assertNull(korean.suggest("담", 4).correction);
        TypingCorrector collisions = new TypingCorrector(entries("가", 500, "ㄱㅏ", 600), true);
        assertTrue(collisions.suggest("까", 4).candidates.contains("가"));
        assertTrue(collisions.suggest("까", 4).candidates.contains("ㄱㅏ"));
        korean = new TypingCorrector(entries("안녕하세요", 5000, "안녕하세야", 4000), true);
        TypingCorrector.Result ambiguous = korean.suggest("안녕하세여", 5);
        assertTrue(ambiguous.candidates.contains("안녕하세요"));
        assertNull(ambiguous.correction);
        korean = new TypingCorrector(entries("안녕하세요", 5000, "안녕하세여", 1), true);
        assertTrue(korean.suggest("안녕하세여", 3).exactWord);
        assertNull(korean.suggest("안녕하세여", 3).correction);
        korean = new TypingCorrector(entries("정말", 5000), true);
        assertTrue(korean.suggest("정마료", 5).candidates.contains("정말"));
        assertNull(korean.suggest("정마료", 5).correction);
    }

    @Test public void twoEditMatchesRemainSuggestionsOnly() {
        TypingCorrector english = new TypingCorrector(entries("possible", 10000), false);
        TypingCorrector.Result result = english.suggest("possivls", 5);
        assertTrue(result.candidates.contains("possible"));
        assertNull(result.correction);
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
    }

    private static List<Entry> entries(Object... values) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) entries.add(new Entry((String) values[i], (Integer) values[i + 1]));
        return entries;
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
