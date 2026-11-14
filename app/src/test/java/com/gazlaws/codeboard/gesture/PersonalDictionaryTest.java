package com.gazlaws.codeboard.gesture;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PersonalDictionaryTest {
    private Context context;
    private PersonalDictionary dictionary;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit().clear().commit();
        dictionary = new PersonalDictionary(context);
    }

    @Test public void wordsPersistAcrossInstancesAndVersionsOnlyChangeForActualEdits() {
        assertEquals(0, dictionary.getVersion());
        assertTrue(dictionary.addWord("Jeong"));
        assertTrue(dictionary.addWord("한글"));
        assertTrue(dictionary.addWord("don't"));
        assertFalse(dictionary.addWord("JEONG"));
        assertEquals(3, dictionary.getVersion());

        PersonalDictionary reloaded = new PersonalDictionary(context);
        assertEquals(Arrays.asList("don't", "Jeong", "한글"), reloaded.getWords());
        assertEquals(3, reloaded.getVersion());
        assertEquals(Arrays.asList("don't", "Jeong"), words(reloaded.getEntries(false)));
        assertEquals(Collections.singletonList("한글"), words(reloaded.getEntries(true)));
        assertEquals(2_000_000, reloaded.getEntries(true).get(0).frequency);

        assertTrue(reloaded.removeWord("jEOnG"));
        assertFalse(dictionary.removeWord("jeong"));
        assertEquals(4, dictionary.getVersion());
        assertEquals(Arrays.asList("don't", "한글"), dictionary.getWords());
    }

    @Test public void normalizesComposedHangulAndAcceptsInternalApostrophes() {
        assertTrue(dictionary.addWord("\u1112\u1161\u11ab"));
        assertFalse(dictionary.addWord("한"));
        assertTrue(dictionary.addWord("O'Brien's"));
        assertTrue(dictionary.addWord("I’m"));
        assertFalse(dictionary.addWord("I'm"));
        assertEquals(Arrays.asList("I’m", "O'Brien's", "한"), dictionary.getWords());
        assertTrue(dictionary.removeWord("i'm"));
    }

    @Test public void rejectsUnsupportedEntriesWithoutChangingTheDictionary() {
        String[] invalid = {"", "hello world", " hello", "hello ", "abc123", "e-mail", "'word",
                "word'", "a''b", "hello한글", "한-글", "café", "한글😀", "ㄱ", "가나a"};
        for (String word : invalid) {
            try {
                dictionary.addWord(word);
                fail("Should reject: " + word);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        try {
            dictionary.addWord(null);
            fail("Should reject null");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        assertTrue(dictionary.getWords().isEmpty());
        assertEquals(0, dictionary.getVersion());
    }

    @Test public void enforcesBothWordAndSwipeSequenceLengthLimits() {
        String letters48 = repeat("a", 48);
        assertTrue(dictionary.addWord(letters48));
        assertTrue(dictionary.addWord(repeat("가", 32))); // 64 Dubeolsik keys
        for (String tooLong : Arrays.asList(repeat("a", 49), repeat("가", 33))) {
            try {
                dictionary.addWord(tooLong);
                fail("Should reject overlong entry");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("too long"));
            }
        }
        assertEquals(2, dictionary.getVersion());
    }

    @Test public void concurrentInstancesDoNotLoseUpdates() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (char suffix = 'a'; suffix <= 'z'; suffix++) {
                final String word = "word" + suffix;
                results.add(pool.submit(() -> {
                    start.await();
                    return new PersonalDictionary(context).addWord(word);
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) assertTrue(result.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(26, dictionary.getWords().size());
        assertEquals(26, dictionary.getVersion());
    }

    private static List<String> words(List<SwipeDecoder.Entry> entries) {
        ArrayList<String> words = new ArrayList<>();
        for (SwipeDecoder.Entry entry : entries) words.add(entry.word);
        return words;
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
