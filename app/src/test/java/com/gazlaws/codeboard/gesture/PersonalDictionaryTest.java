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
import java.util.HashSet;
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

    @Test public void emailPersistsLiterallyAndUsesDomainInsensitiveMatching() {
        assertTrue(dictionary.addWord("Learner80@Example.com"));
        assertFalse(dictionary.addWord("Learner80@EXAMPLE.COM"));
        // Local parts can be case-sensitive, even though domains cannot.
        assertTrue(dictionary.addWord("learner80@example.com"));
        assertTrue(dictionary.addWord("learner80+tag@example.com"));
        assertEquals(3, dictionary.getVersion());

        PersonalDictionary reloaded = new PersonalDictionary(context);
        assertEquals(Arrays.asList("learner80+tag@example.com", "Learner80@Example.com",
                "learner80@example.com"), reloaded.getEmailAddresses());
        assertTrue(reloaded.contains("Learner80@EXAMPLE.COM"));
        assertFalse(reloaded.contains("LEARNER80@example.com"));
        assertFalse(reloaded.contains("learner80@"));
        assertTrue(reloaded.removeWord("Learner80@EXAMPLE.COM"));
        assertFalse(reloaded.removeWord("Learner80@example.com"));
        assertEquals(4, dictionary.getVersion());
        assertEquals(Arrays.asList("learner80+tag@example.com", "learner80@example.com"),
                dictionary.getEmailAddresses());
    }

    @Test public void validatesEmailAddressesWithoutAcceptingMalformedInput() {
        for (String address : Arrays.asList("learner80@example.com", "Learner80+tag@Example.COM",
                "o'learner80@example.com", "learner.80@example-domain.com")) {
            assertTrue(address, PersonalDictionary.isValidEmailAddress(address));
            assertTrue(address, dictionary.addWord(address));
        }
        String domainOf254CharacterAddress = repeat("a", 63) + "." + repeat("b", 63) + "."
                + repeat("c", 63) + "." + repeat("d", 52);
        String longestAddress = "learner80@" + domainOf254CharacterAddress;
        assertEquals(254, longestAddress.length());
        assertTrue(PersonalDictionary.isValidEmailAddress(longestAddress));
        assertTrue(dictionary.addWord(longestAddress));

        for (String address : Arrays.asList("@example.com", "learner80@", "learner80@@example.com",
                "learner80@example", "learner80@.example.com", "learner80@example..com",
                "learner80@example.com.", "learner80@-example.com", "learner80@example-.com",
                ".learner80@example.com", "learner80.@example.com", "learner..80@example.com",
                "learner 80@example.com", "learner80@exa mple.com", "learner80@example.com\n",
                "learner80@éxample.com", "learner80@example_com", repeat("a", 65) + "@example.com",
                "learner80@" + repeat("a", 64) + ".com", longestAddress + "d")) {
            assertFalse(address, PersonalDictionary.isValidEmailAddress(address));
            try {
                dictionary.addWord(address);
                fail("Should reject malformed email: " + address);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        assertEquals(5, dictionary.getVersion());
    }

    @Test public void retainsPreviousWordsAndKeepsEmailsOutOfWordDictionaries() {
        context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit()
                .putStringSet("words", new HashSet<>(Arrays.asList("Jeong", "한글")))
                .putInt("version", 7).commit();
        PersonalDictionary upgraded = new PersonalDictionary(context);
        assertEquals(Arrays.asList("Jeong", "한글"), upgraded.getWords());
        assertEquals(7, upgraded.getVersion());

        assertTrue(upgraded.addWord("learner80@example.com"));
        assertEquals(Arrays.asList("Jeong", "learner80@example.com", "한글"),
                new PersonalDictionary(context).getWords());
        assertEquals(8, upgraded.getVersion());
        assertEquals(Collections.singletonList("Jeong"), words(upgraded.getEntries(false)));
        assertEquals(Collections.singletonList("한글"), words(upgraded.getEntries(true)));

        TypingCorrector typing = new TypingCorrector(upgraded.getEntries(false), false);
        assertEquals(Collections.singletonList("learner80@example.com"),
                typing.suggest("learner80@example.com", 5).candidates);
        assertNull(typing.suggest("learner80@example.com", 5).correction);
        assertFalse(typing.suggest("learner", 5).candidates.contains("learner80@example.com"));
        assertTrue(upgraded.removeWord("learner80@EXAMPLE.COM"));
        assertEquals(9, upgraded.getVersion());
        assertEquals(Arrays.asList("Jeong", "한글"), dictionary.getWords());
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
