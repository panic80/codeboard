package com.gazlaws.codeboard.gesture;

import com.gazlaws.codeboard.gesture.SwipeDecoder.Entry;
import com.gazlaws.codeboard.gesture.SwipeDecoder.KeyPosition;
import com.gazlaws.codeboard.gesture.SwipeDecoder.Point;
import com.gazlaws.codeboard.input.HangulComposer;
import com.gazlaws.codeboard.input.KoreanKeyMap;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

public class SwipeDecoderTest {
    @Test public void noisyFullPathRanksTheWordAboveNearbyAlternatives() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries(
                "hello", 300, "hallo", 500, "help", 700, "hero", 900, "jelly", 1000));
        List<String> result = decoder.decode(noisyPath(keys, "hello", 12), 5);
        assertEquals("hello", result.get(0));
        assertTrue(result.contains("hallo"));
    }

    @Test public void samplesOnlyAtEndpointsStillRepresentTheCrossedKeys() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries(
                "qwerty", 1000, "qty", 30, "qy", 5, "quy", 20));
        Map<Character, KeyPosition> lookup = byKey(keys);
        List<Point> path = Arrays.asList(point(lookup.get('q')), point(lookup.get('y')));
        assertEquals("qwerty", decoder.decode(path, 3).get(0));
    }

    @Test public void gestureOrderDistinguishesWordsWithTheSameEndpoints() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("test", 10, "text", 1000, "tent", 1000));
        assertEquals("test", decoder.decode(pathFor(keys, "test"), 3).get(0));
        assertEquals("text", decoder.decode(pathFor(keys, "text"), 3).get(0));
    }

    @Test public void slowStrikeKeepsItsOrderWithTheBundledDictionaryAndContext() throws Exception {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, bundledDictionary());
        EnglishContextModel context;
        try (FileInputStream input = new FileInputStream(assetFile("en_context.tsv"))) {
            context = EnglishContextModel.load(input);
        }
        EnglishContextModel.PreparedContext afterTried = context.prepare("tried ");
        EnglishContextModel.PreparedContext afterThe = context.prepare("the ");
        SwipeDecoder.ContextPrior tried = bundledPrior(afterTried);
        SwipeDecoder.ContextPrior the = bundledPrior(afterThe);

        List<Point> exact = pathFor(keys, "strike");
        List<Point> noisy = Arrays.asList(
                new Point(1.71f, 1.09f), new Point(4.06f, -0.25f),
                new Point(3.20f, 0.10f), new Point(6.75f, 0.12f),
                new Point(7.74f, 1.10f), new Point(1.90f, -0.20f));
        for (float spacing : new float[] {0.05f, 0.10f, 0.20f}) {
            for (List<Point> vertices : Arrays.asList(exact, roundedPath(noisy, 0.15f))) {
                List<Point> path = timedPolyline(vertices, 80, spacing);
                assertEquals("strike", decoder.decode(path, 5).get(0));
                assertEquals("strike", decoder.decode(path, 5, tried).get(0));
                assertEquals("strike", decoder.decode(path, 5, the).get(0));
            }
            // A slower turn at O should not be mistaken for a double O either.
            for (int speed : new int[] {80, 120, 160, 200}) {
                List<Point> store = timedPolyline(pathFor(keys, "store"), speed, spacing);
                assertEquals("store", decoder.decode(store, 5).get(0));
            }
        }
    }

    @Test public void continuousMovementAcrossAndBackThroughKeysIsNotADoubleLetterPause() {
        List<KeyPosition> keys = qwerty();
        // Doubling a key does not change its geometric template. This isolates
        // repeat evidence from all other scores, including the frequency prior.
        for (String repeated : Arrays.asList("sttrike", "strrike")) {
            SwipeDecoder decoder = new SwipeDecoder(keys, entries("strike", 100, repeated, 100));
            for (int millisPerKey : new int[] {40, 80, 120, 160}) {
                for (float spacing : new float[] {0.05f, 0.10f, 0.20f}) {
                    List<Point> path = timedPolyline(pathFor(keys, "strike"), millisPerKey, spacing);
                    assertEquals(repeated + " " + millisPerKey + " " + spacing,
                            "strike", decoder.decode(path, 2).get(0));
                }
            }
        }

        // This pair also has identical geometry, but here the path simply turns
        // once at T rather than crossing it again on later legs.
        SwipeDecoder slowTurn = new SwipeDecoder(keys, entries("later", 100, "latter", 100));
        assertEquals("later", slowTurn.decode(
                timedPolyline(pathFor(keys, "later"), 160, 0.10f), 2).get(0));
    }

    @Test public void noisyShortReversalsRemainDistinctFromShorterWordPaths() throws Exception {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, bundledDictionary());
        EnglishContextModel context;
        try (FileInputStream input = new FileInputStream(assetFile("en_context.tsv"))) {
            context = EnglishContextModel.load(input);
        }
        // Every turn is inside its intended key, but several are near key edges.
        // In particular, T -> R reverses direction before the long leg to I.
        List<Point> noisyStrike = Arrays.asList(
                new Point(1.8242f, 0.8901f), new Point(4.4107f, 0.3171f),
                new Point(3.1053f, 0.4355f), new Point(6.5812f, 0.2777f),
                new Point(7.1881f, 0.5565f), new Point(2.4003f, -0.1679f));
        List<Point> strike = timedPolyline(noisyStrike, 80, 0.10f);
        assertEquals("strike", decoder.decode(strike, 5).get(0));
        assertEquals("strike", decoder.decode(strike, 5, bundledPrior(context.prepare("tried "))).get(0));
        assertEquals("strike", decoder.decode(strike, 5, bundledPrior(context.prepare("a "))).get(0));

        List<Point> site = timedPolyline(pathFor(keys, "site"), 80, 0.10f);
        assertEquals("site", decoder.decode(site, 5).get(0));
        List<Point> sitter = timedPolyline(pathFor(keys, "sit"), 80, 0.10f);
        Point turn = sitter.get(sitter.size() - 1);
        sitter.add(new Point(turn.x, turn.y, turn.timeMillis + 160));
        List<Point> tail = timedPolyline(pathFor(keys, "ter"), 80, 0.10f);
        for (int i = 1; i < tail.size(); i++) {
            Point point = tail.get(i);
            sitter.add(new Point(point.x, point.y, turn.timeMillis + 160 + point.timeMillis));
        }
        assertEquals("sitter", decoder.decode(sitter, 5).get(0));
    }

    @Test public void contextCanResolveAmbiguousNeighboringStartKeys() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("bot", 944, "not", 4262273));
        Map<Character, KeyPosition> lookup = byKey(keys);
        KeyPosition b = lookup.get('b');
        KeyPosition n = lookup.get('n');
        List<Point> path = Arrays.asList(new Point(b.centerX + 0.3f * (n.centerX - b.centerX), b.centerY),
                point(lookup.get('o')), point(lookup.get('t')));

        assertEquals("bot", decoder.decode(path, 2).get(0));
        assertEquals("not", decoder.decode(path, 2,
                candidate -> candidate.equals("not") ? SwipeDecoder.MAX_CONTEXT_BONUS : 0).get(0));
    }

    @Test public void contextCannotOverruleAClearGestureOrExceedItsCap() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("bot", 944, "not", 4262273));
        List<Point> clearBot = pathFor(keys, "bot");
        List<String> bounded = decoder.decode(clearBot, 2,
                candidate -> candidate.equals("not") ? SwipeDecoder.MAX_CONTEXT_BONUS : 0);
        List<String> unboundedInput = decoder.decode(clearBot, 2,
                candidate -> candidate.equals("not") ? Double.POSITIVE_INFINITY : 0);

        assertEquals(Arrays.asList("bot", "not"), bounded);
        assertEquals(bounded, unboundedInput);
    }

    @Test public void contextCannotReplaceAWellMatchedNoisyEndpoint() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("this", 5739788, "third", 55750));
        // Each point remains inside the intended key, with offsets of up to 0.35 key widths.
        List<Point> first = Arrays.asList(
                new Point(3.682939f, 0.267147f), new Point(5.327132f, 1.310178f),
                new Point(6.874869f, -0.196451f), new Point(1.823769f, 0.891663f));
        List<Point> second = Arrays.asList(
                new Point(4.106874f, -0.022892f), new Point(5.844778f, 1.331063f),
                new Point(6.830290f, 0.135389f), new Point(1.816829f, 0.842090f));
        SwipeDecoder.ContextPrior afterThe = word -> word.equals("third") ? contextBonus(1226) : 0;

        for (List<Point> path : Arrays.asList(first, second)) {
            assertEquals("this", decoder.decode(path, 2).get(0));
            assertEquals("this", decoder.decode(path, 2, afterThe).get(0));
        }
        assertEquals("this", decoder.decode(pathFor(keys, "this"), 2, afterThe).get(0));
        assertEquals("third", decoder.decode(pathFor(keys, "third"), 2, afterThe).get(0));
    }

    @Test public void contextCannotReplaceDifferentClearGestureShapes() {
        List<KeyPosition> keys = qwerty();
        assertContextKeepsClearShapes(keys, "shirt", 30183, "short", 63714, 254);
        assertContextKeepsClearShapes(keys, "sock", 4440, "stock", 16373, 123);
        assertContextKeepsClearShapes(keys, "live", 281046, "love", 830324, 561);

        SwipeDecoder decoder = new SwipeDecoder(keys, entries("there", 3148528, "the", 22761659));
        List<Point> noisyThere = Arrays.asList(
                new Point(3.710616f, 0.054979f), new Point(5.263451f, 0.741510f),
                new Point(1.898026f, 0.231955f), new Point(3.154772f, 0.120053f),
                new Point(1.807703f, 0.021643f));
        SwipeDecoder.ContextPrior afterThat = word -> word.equals("there") ? contextBonus(2306)
                : word.equals("the") ? contextBonus(18682) : 0;
        assertEquals("there", decoder.decode(noisyThere, 2).get(0));
        assertEquals("there", decoder.decode(noisyThere, 2, afterThat).get(0));
    }

    @Test public void contextRespectsNoisyIntermediateTurns() {
        List<KeyPosition> keys = qwerty();
        List<Point> noisyShirt = Arrays.asList(
                new Point(1.4800f, 1.0710f), new Point(5.4098f, 0.9331f),
                new Point(7.0239f, 0.0960f), new Point(3.1381f, 0.1067f),
                new Point(3.9173f, 0.1339f));
        assertContextKeepsNoisyShape(keys, "shirt", 30183, "short", 63714, 254, noisyShirt);

        List<Point> noisyLive = Arrays.asList(
                new Point(8.4567f, 1.1060f), new Point(6.9915f, -0.1225f),
                new Point(4.0539f, 1.9370f), new Point(1.9569f, 0.1122f));
        assertContextKeepsNoisyShape(keys, "live", 281046, "love", 830324, 561, noisyLive);
    }

    @Test public void contextRespectsAnObservedIntermediateExcursion() {
        List<KeyPosition> keys = qwerty();
        List<Point> noisySock = Arrays.asList(
                new Point(1.4970f, 0.9858f), new Point(7.8575f, -0.0250f),
                new Point(2.9133f, 2.0620f), new Point(7.3927f, 1.0189f));
        assertContextKeepsNoisyShape(keys, "sock", 4440, "stock", 16373, 123, noisySock);
    }

    @Test public void contextStillResolvesAmbiguousShapesAndInvisibleDoubleLetters() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder neighboring = new SwipeDecoder(keys, entries("well", 2159909, "will", 1969807));
        List<Point> noisyWell = Arrays.asList(
                new Point(0.860937f, 0.113809f), new Point(1.944478f, -0.342557f),
                new Point(8.846700f, 0.679145f), new Point(8.620901f, 0.942223f));
        assertEquals("will", neighboring.decode(noisyWell, 2).get(0));
        assertEquals("well", neighboring.decode(noisyWell, 2,
                word -> word.equals("well") ? contextBonus(9423) : 0).get(0));

        SwipeDecoder repeated = new SwipeDecoder(keys, entries("good", 1741730, "god", 611924));
        List<Point> identicalShape = pathFor(keys, "good");
        SwipeDecoder.ContextPrior afterFor = word -> word.equals("good") ? contextBonus(208)
                : word.equals("god") ? contextBonus(142) : 0;
        assertEquals("god", repeated.decode(identicalShape, 2).get(0));
        assertEquals("good", repeated.decode(identicalShape, 2, afterFor).get(0));
    }

    @Test public void contextCanStillDistinguishKoreanSpellingsWithIdenticalKeyPaths() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("고리", 1000, "꼬리", 100));
        List<Point> sharedPath = pathFor(keys, "rhfl");
        assertEquals("고리", decoder.decode(sharedPath, 2).get(0));
        assertEquals("꼬리", decoder.decode(sharedPath, 2,
                word -> word.equals("꼬리") ? SwipeDecoder.MAX_CONTEXT_BONUS : 0).get(0));
    }

    @Test public void nullAndInvalidContextBonusesPreserveGeometryRanking() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("bot", 944, "not", 4262273));
        List<Point> path = pathFor(keys, "bot");
        List<String> original = decoder.decode(path, 2);

        assertEquals(original, decoder.decode(path, 2, null));
        assertEquals(original, decoder.decode(path, 2, candidate -> Double.NaN));
        assertEquals(original, decoder.decode(path, 2, candidate -> -100));
        assertEquals(original, decoder.decode(path, 2, candidate -> 0));
    }

    @Test public void protectedGeometryWinnerKeepsTheOriginalRanking() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("bot", 944, "not", 4262273));
        Map<Character, KeyPosition> lookup = byKey(keys);
        KeyPosition b = lookup.get('b');
        KeyPosition n = lookup.get('n');
        List<Point> path = Arrays.asList(new Point(b.centerX + 0.3f * (n.centerX - b.centerX), b.centerY),
                point(lookup.get('o')), point(lookup.get('t')));
        SwipeDecoder.ContextPrior preferNot = new SwipeDecoder.ContextPrior() {
            @Override public double bonus(String candidate) {
                return candidate.equals("not") ? SwipeDecoder.MAX_CONTEXT_BONUS : 0;
            }

            @Override public boolean isProtected(String candidate) {
                return candidate.equals("bot");
            }
        };

        assertEquals(Arrays.asList("bot", "not"), decoder.decode(path, 2));
        assertEquals(decoder.decode(path, 2), decoder.decode(path, 2, preferNot));
    }

    @Test public void aPauseAtAKeyCanIndicateARepeatedLetter() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("helo", 100, "hello", 100));
        List<Point> withoutPause = pathFor(keys, "helo");
        assertEquals("helo", decoder.decode(withoutPause, 2).get(0));

        Map<Character, KeyPosition> lookup = byKey(keys);
        List<Point> withPause = Arrays.asList(
                timed(lookup.get('h'), 0), timed(lookup.get('e'), 70),
                timed(lookup.get('l'), 140), timed(lookup.get('l'), 300),
                timed(lookup.get('o'), 370));
        assertEquals("hello", decoder.decode(withPause, 2).get(0));
    }

    @Test public void repeatedLetterPauseMustBeContinuousAndUseMonotonicTime() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("helo", 100, "hello", 100));
        Map<Character, KeyPosition> lookup = byKey(keys);
        KeyPosition l = lookup.get('l');
        List<Point> shortPause = Arrays.asList(timed(lookup.get('h'), 0),
                timed(lookup.get('e'), 100), timed(l, 300), timed(l, 419),
                timed(lookup.get('o'), 700));
        List<Point> fullPause = Arrays.asList(timed(lookup.get('h'), 0),
                timed(lookup.get('e'), 100), timed(l, 300), timed(l, 420),
                timed(lookup.get('o'), 700));
        List<Point> jitteredPause = Arrays.asList(timed(lookup.get('h'), 0),
                timed(lookup.get('e'), 100), timed(l, 300),
                new Point(l.centerX + 0.12f, l.centerY, 360),
                new Point(l.centerX - 0.10f, l.centerY, 420), timed(lookup.get('o'), 700));
        List<Point> separateVisits = Arrays.asList(timed(lookup.get('h'), 0),
                timed(lookup.get('e'), 100), timed(l, 300), timed(l, 370),
                timed(lookup.get('y'), 400), timed(l, 500), timed(l, 570),
                timed(lookup.get('o'), 700));
        List<Point> backwards = Arrays.asList(timed(lookup.get('h'), 0),
                timed(lookup.get('e'), 100), timed(l, 300), timed(l, 390),
                timed(l, 20), timed(l, 80), timed(lookup.get('o'), 700));
        List<Point> missing = Arrays.asList(timed(lookup.get('h'), 0),
                timed(lookup.get('e'), 100), timed(l, 300), timed(l, 390),
                timed(l, -1), timed(l, 400), timed(l, 460), timed(lookup.get('o'), 700));

        for (List<Point> path : Arrays.asList(shortPause, separateVisits, backwards, missing)) {
            assertEquals("helo", decoder.decode(path, 2).get(0));
        }
        assertEquals("hello", decoder.decode(fullPause, 2).get(0));
        assertEquals("hello", decoder.decode(jitteredPause, 2).get(0));
    }

    @Test public void repeatedLetterPauseCanJitterAroundOrNearTheKeyCenter() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("fed", 100, "feed", 100));
        Map<Character, KeyPosition> lookup = byKey(keys);
        KeyPosition e = lookup.get('e');
        for (float bias : new float[] {0f, 0.10f, 0.20f}) {
            List<Point> path = timedPolyline(pathFor(keys, "fe"), 80, 0.10f);
            long start = path.get(path.size() - 1).timeMillis;
            for (int ms = 10; ms <= 160; ms += 10) {
                float jitter = (float) (0.20 * Math.sin(2 * Math.PI * ms / 100));
                path.add(new Point(e.centerX + bias + jitter, e.centerY, start + ms));
            }
            path.add(timed(lookup.get('d'), start + 300));
            assertEquals("bias " + bias, "feed", decoder.decode(path, 2).get(0));
        }
    }

    @Test public void aSmallLoopCanIndicateARepeatedLetter() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("helo", 100, "hello", 100));
        Map<Character, KeyPosition> lookup = byKey(keys);
        List<Point> path = new ArrayList<>();
        path.add(point(lookup.get('h')));
        path.add(point(lookup.get('e')));
        KeyPosition l = lookup.get('l');
        path.add(point(l));
        for (int i = 0; i <= 24; i++) {
            double angle = 4 * Math.PI * i / 24;
            path.add(new Point(l.centerX + (float) Math.cos(angle) * l.width * 0.28f,
                    l.centerY + (float) Math.sin(angle) * l.height * 0.28f));
        }
        path.add(point(l));
        path.add(point(lookup.get('o')));
        assertEquals("hello", decoder.decode(path, 2).get(0));
    }

    @Test public void matchesKoreanWordsAndPreservesTheirSpelling() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries(
                "안녕", 100, "안내", 1000, "한글", 100, "한국", 1000, "꼬리", 100));
        assertEquals("안녕", decoder.decode(pathFor(keys, "dkssud"), 3).get(0));
        assertEquals("한글", decoder.decode(pathFor(keys, "gksrmf"), 3).get(0));
        assertEquals("꼬리", decoder.decode(pathFor(keys, "Rhf l".replace(" ", "")), 3).get(0));
        assertEquals(decoder.decode(pathFor(keys, "dkssud"), 3),
                decoder.decode(pathFor(keys, "dkssud"), 3, null));
    }

    @Test public void usesTheSuppliedGeometryRatherThanAssumingQwerty() {
        List<KeyPosition> keys = new ArrayList<>();
        for (KeyPosition key : qwerty()) {
            char reversed = (char) ('z' - (Character.toLowerCase(key.key) - 'a'));
            keys.add(new KeyPosition(reversed, key.centerX, key.centerY, key.width, key.height));
        }
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("code", 20, "cafe", 1000, "care", 1000));
        assertEquals("code", decoder.decode(pathFor(keys, "code"), 3).get(0));
    }

    @Test public void everyModernSyllableMapsBackThroughTheExistingComposer() {
        for (char syllable = '가'; syllable <= '힣'; syllable++) {
            String positions = SwipeDecoder.keySequenceForWord(String.valueOf(syllable));
            HangulComposer composer = new HangulComposer();
            StringBuilder committed = new StringBuilder();
            for (int i = 0; i < positions.length(); i++) {
                HangulComposer.Update update = composer.input(KoreanKeyMap.toJamo(positions.charAt(i)));
                committed.append(update.committedText);
            }
            committed.append(composer.getComposingText());
            assertEquals("U+" + Integer.toHexString(syllable), String.valueOf(syllable), committed.toString());
        }
        assertEquals("RhkrtO", SwipeDecoder.keySequenceForWord("꽋ㅒ"));
        assertEquals("gksrmf", SwipeDecoder.keySequenceForWord("한글"));
        assertEquals("rthk", SwipeDecoder.keySequenceForWord("ㄳㅘ"));
    }

    @Test public void ignoresUnsupportedWordsAndUnusablePathsAndBoundsResultCount() {
        List<KeyPosition> keys = qwerty();
        SwipeDecoder decoder = new SwipeDecoder(keys, entries("hello", 2, "hello", 5, "bad word", 99));
        assertTrue(decoder.decode(Collections.<Point>emptyList(), 3).isEmpty());
        assertTrue(decoder.decode(Arrays.asList(new Point(1, 1), new Point(1, 1)), 3).isEmpty());
        assertTrue(decoder.decode(pathFor(keys, "hello"), 0).isEmpty());
        assertEquals(Collections.singletonList("hello"), decoder.decode(pathFor(keys, "hello"), 100));
    }

    private static List<KeyPosition> qwerty() {
        List<KeyPosition> result = new ArrayList<>();
        addRow(result, "qwertyuiop", 0f, 0);
        addRow(result, "asdfghjkl", 0.5f, 1);
        addRow(result, "zxcvbnm", 1f, 2);
        return result;
    }

    private static void addRow(List<KeyPosition> result, String letters, float offset, int row) {
        for (int i = 0; i < letters.length(); i++) {
            result.add(new KeyPosition(letters.charAt(i), offset + i, row, 1, 1));
        }
    }

    private static List<Entry> entries(Object... values) {
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            result.add(new Entry((String) values[i], (Integer) values[i + 1]));
        }
        return result;
    }

    private static double contextBonus(long count) {
        return SwipeDecoder.MAX_CONTEXT_BONUS * Math.min(1, Math.log1p(count) / Math.log1p(10000));
    }

    private static SwipeDecoder.ContextPrior bundledPrior(EnglishContextModel.PreparedContext prepared) {
        return candidate -> {
            TypingCorrector.ContextEvidence evidence = prepared.score(candidate);
            return contextBonus(Math.max(evidence.bigramCount, evidence.trigramCount));
        };
    }

    private static File assetFile(String name) {
        File file = new File("src/main/assets/swipe/" + name);
        return file.isFile() ? file : new File("app/src/main/assets/swipe/" + name);
    }

    private static List<Entry> bundledDictionary() throws Exception {
        List<Entry> entries = new ArrayList<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new FileInputStream(assetFile("en.tsv")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                String[] fields = line.split("\\t", 2);
                entries.add(new Entry(fields[0], Integer.parseInt(fields[1])));
            }
        }
        return entries;
    }

    private static void assertContextKeepsClearShapes(List<KeyPosition> keys, String intended,
                                                       int intendedFrequency, String alternative,
                                                       int alternativeFrequency, long contextCount) {
        SwipeDecoder decoder = new SwipeDecoder(keys, entries(
                intended, intendedFrequency, alternative, alternativeFrequency));
        SwipeDecoder.ContextPrior prior = word -> word.equals(alternative) ? contextBonus(contextCount) : 0;
        assertEquals(intended, decoder.decode(pathFor(keys, intended), 2, prior).get(0));
        assertEquals(alternative, decoder.decode(pathFor(keys, alternative), 2, prior).get(0));
    }

    private static void assertContextKeepsNoisyShape(List<KeyPosition> keys, String intended,
                                                      int intendedFrequency, String alternative,
                                                      int alternativeFrequency, long contextCount,
                                                      List<Point> path) {
        SwipeDecoder decoder = new SwipeDecoder(keys, entries(
                intended, intendedFrequency, alternative, alternativeFrequency));
        SwipeDecoder.ContextPrior prior = word -> word.equals(alternative) ? contextBonus(contextCount) : 0;
        assertEquals(intended, decoder.decode(path, 2).get(0));
        assertEquals(intended, decoder.decode(path, 2, prior).get(0));
        assertEquals(alternative, decoder.decode(pathFor(keys, alternative), 2, prior).get(0));
    }

    private static Map<Character, KeyPosition> byKey(List<KeyPosition> keys) {
        Map<Character, KeyPosition> result = new HashMap<>();
        for (KeyPosition key : keys) result.put(Character.toLowerCase(key.key), key);
        return result;
    }

    private static Point point(KeyPosition key) { return new Point(key.centerX, key.centerY); }
    private static Point timed(KeyPosition key, long time) { return new Point(key.centerX, key.centerY, time); }

    private static List<Point> pathFor(List<KeyPosition> keys, String sequence) {
        Map<Character, KeyPosition> lookup = byKey(keys);
        List<Point> result = new ArrayList<>();
        for (char c : sequence.toLowerCase().toCharArray()) result.add(point(lookup.get(c)));
        return result;
    }

    private static List<Point> roundedPath(List<Point> vertices, float fraction) {
        List<Point> result = new ArrayList<>();
        result.add(vertices.get(0));
        for (int i = 1; i < vertices.size() - 1; i++) {
            Point previous = vertices.get(i - 1), current = vertices.get(i), next = vertices.get(i + 1);
            result.add(new Point(current.x + fraction * (previous.x - current.x),
                    current.y + fraction * (previous.y - current.y)));
            result.add(new Point(current.x + fraction * (next.x - current.x),
                    current.y + fraction * (next.y - current.y)));
        }
        result.add(vertices.get(vertices.size() - 1));
        return result;
    }

    private static List<Point> timedPolyline(List<Point> vertices, int millisPerKey, float spacing) {
        List<Point> result = new ArrayList<>();
        Point first = vertices.get(0);
        result.add(new Point(first.x, first.y, 0));
        double elapsed = 0;
        for (int i = 1; i < vertices.size(); i++) {
            Point a = vertices.get(i - 1), b = vertices.get(i);
            double distance = Math.hypot(b.x - a.x, b.y - a.y);
            int steps = Math.max(1, (int) Math.ceil(distance / spacing));
            for (int step = 1; step <= steps; step++) {
                float fraction = (float) step / steps;
                result.add(new Point(a.x + (b.x - a.x) * fraction,
                        a.y + (b.y - a.y) * fraction,
                        Math.round(elapsed + distance * millisPerKey * fraction)));
            }
            elapsed += distance * millisPerKey;
        }
        return result;
    }

    private static List<Point> noisyPath(List<KeyPosition> keys, String sequence, long seed) {
        Map<Character, KeyPosition> lookup = byKey(keys);
        List<Point> result = new ArrayList<>();
        Random random = new Random(seed);
        for (char c : sequence.toCharArray()) {
            KeyPosition key = lookup.get(c);
            result.add(new Point(key.centerX + (random.nextFloat() - 0.5f) * 0.22f,
                    key.centerY + (random.nextFloat() - 0.5f) * 0.22f));
        }
        return result;
    }
}
