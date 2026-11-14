package com.gazlaws.codeboard.gesture;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Locally stored words shared by the typing and swipe dictionaries. */
public final class PersonalDictionary {
    private static final String PREFERENCES = "personal_dictionary";
    private static final String WORDS = "words";
    private static final String VERSION = "version";
    private static final int MAX_WORD_LENGTH = 48;
    // The swipe decoder accepts key sequences up to 64 characters.
    private static final int MAX_SWIPE_SEQUENCE_LENGTH = 64;
    private static final int PERSONAL_WORD_FREQUENCY = 2_000_000;
    // Multiple instances are used by settings and by background decoding workers.
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    public PersonalDictionary(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Changes after an actual addition or removal, including changes made by another instance. */
    public int getVersion() {
        synchronized (LOCK) {
            return preferences.getInt(VERSION, 0);
        }
    }

    /** Returns a sorted snapshot suitable for showing in settings. */
    public List<String> getWords() {
        synchronized (LOCK) {
            ArrayList<String> words = new ArrayList<>(storedWords());
            Collections.sort(words, (a, b) -> {
                int comparison = String.CASE_INSENSITIVE_ORDER.compare(a, b);
                return comparison != 0 ? comparison : a.compareTo(b);
            });
            return words;
        }
    }

    /** Returns a snapshot of the entries for one language, with their original spelling. */
    public List<SwipeDecoder.Entry> getEntries(boolean korean) {
        ArrayList<SwipeDecoder.Entry> result = new ArrayList<>();
        for (String word : getWords()) {
            if (isKoreanSyllable(word.charAt(0)) == korean) {
                result.add(new SwipeDecoder.Entry(word, PERSONAL_WORD_FREQUENCY));
            }
        }
        return result;
    }

    /**
     * Adds one English or Korean word, or returns false if it is already present.
     * English spelling comparisons are case-insensitive; the saved spelling is preserved.
     *
     * @throws IllegalArgumentException if the word is not supported or is too long
     */
    public boolean addWord(String word) {
        String normalized = validate(word);
        synchronized (LOCK) {
            Set<String> words = storedWords();
            if (findStoredWord(words, normalized) != null) return false;
            words.add(normalized);
            save(words);
            return true;
        }
    }

    /** Removes a matching word, returning false if it is not present. */
    public boolean removeWord(String word) {
        String normalized = validate(word);
        synchronized (LOCK) {
            Set<String> words = storedWords();
            String stored = findStoredWord(words, normalized);
            if (stored == null) return false;
            words.remove(stored);
            save(words);
            return true;
        }
    }

    private Set<String> storedWords() {
        return new HashSet<>(preferences.getStringSet(WORDS, Collections.emptySet()));
    }

    private void save(Set<String> words) {
        preferences.edit().putStringSet(WORDS, words)
                .putInt(VERSION, preferences.getInt(VERSION, 0) + 1).apply();
    }

    private static String findStoredWord(Set<String> words, String target) {
        boolean korean = isKoreanSyllable(target.charAt(0));
        String englishTarget = korean ? null : target.replace('\u2019', '\'');
        for (String word : words) {
            if (korean ? word.equals(target)
                    : word.replace('\u2019', '\'').equalsIgnoreCase(englishTarget)) return word;
        }
        return null;
    }

    private static String validate(String word) {
        if (word == null) throw invalidWord();
        String normalized = Normalizer.normalize(word, Normalizer.Form.NFC);
        if (normalized.length() == 0) throw invalidWord();
        if (normalized.length() > MAX_WORD_LENGTH) throw wordTooLong();

        boolean korean = isKoreanSyllable(normalized.charAt(0));
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (korean) {
                if (!isKoreanSyllable(c)) throw invalidWord();
            } else if (!isAsciiLetter(c)) {
                // Apostrophes are accepted only between English letters.
                if ((c != '\'' && c != '\u2019') || i == 0 || i == normalized.length() - 1
                        || !isAsciiLetter(normalized.charAt(i - 1))
                        || !isAsciiLetter(normalized.charAt(i + 1))) throw invalidWord();
            }
        }
        String sequence = SwipeDecoder.keySequenceForWord(normalized);
        if (sequence == null || sequence.length() > MAX_SWIPE_SEQUENCE_LENGTH) throw wordTooLong();
        return normalized;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isKoreanSyllable(char c) {
        return c >= '\uac00' && c <= '\ud7a3';
    }

    private static IllegalArgumentException invalidWord() {
        return new IllegalArgumentException("Use one English or Korean word. English apostrophes must be inside the word.");
    }

    private static IllegalArgumentException wordTooLong() {
        return new IllegalArgumentException("This word is too long. Use a shorter entry.");
    }
}
