package com.gazlaws.codeboard.gesture;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Locally stored words and literal email addresses. */
public final class PersonalDictionary {
    private static final String PREFERENCES = "personal_dictionary";
    private static final String WORDS = "words";
    private static final String VERSION = "version";
    private static final int MAX_WORD_LENGTH = 48;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_EMAIL_LOCAL_LENGTH = 64;
    private static final int MAX_DOMAIN_LABEL_LENGTH = 63;
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

    /** Returns all saved entries, including email addresses, sorted for settings. */
    public List<String> getWords() {
        synchronized (LOCK) {
            return sorted(storedWords());
        }
    }

    /** Returns saved literal email addresses with the spelling originally entered. */
    public List<String> getEmailAddresses() {
        synchronized (LOCK) {
            ArrayList<String> addresses = new ArrayList<>();
            for (String entry : storedWords()) {
                if (isValidEmailAddress(entry)) addresses.add(entry);
            }
            return sorted(addresses);
        }
    }

    /** Returns words for one language; literal email addresses never enter these dictionaries. */
    public List<SwipeDecoder.Entry> getEntries(boolean korean) {
        ArrayList<SwipeDecoder.Entry> result = new ArrayList<>();
        for (String word : getWords()) {
            if (!word.isEmpty() && !isValidEmailAddress(word)
                    && isKoreanSyllable(word.charAt(0)) == korean) {
                result.add(new SwipeDecoder.Entry(word, PERSONAL_WORD_FREQUENCY));
            }
        }
        return result;
    }

    /**
     * Adds one English or Korean word or literal email address, or returns false if present.
     * Word comparisons are case-insensitive for English. Email local parts are compared
     * exactly and domains case-insensitively. The original spelling is preserved.
     *
     * @throws IllegalArgumentException if the entry is not supported or is too long
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

    /** Returns whether an entry is present, using the same matching rules as add and remove. */
    public boolean contains(String entry) {
        final String normalized;
        try {
            normalized = validate(entry);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        synchronized (LOCK) {
            return findStoredWord(storedWords(), normalized) != null;
        }
    }

    /** Removes a matching entry, returning false if it is not present. */
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

    private static ArrayList<String> sorted(Iterable<String> entries) {
        ArrayList<String> result = new ArrayList<>();
        for (String entry : entries) result.add(entry);
        Collections.sort(result, (a, b) -> {
            int comparison = String.CASE_INSENSITIVE_ORDER.compare(a, b);
            return comparison != 0 ? comparison : a.compareTo(b);
        });
        return result;
    }

    private static String findStoredWord(Set<String> words, String target) {
        if (isValidEmailAddress(target)) {
            int targetAt = target.indexOf('@');
            for (String word : words) {
                if (!isValidEmailAddress(word)) continue;
                int wordAt = word.indexOf('@');
                if (word.substring(0, wordAt).equals(target.substring(0, targetAt))
                        && word.substring(wordAt + 1).equalsIgnoreCase(target.substring(targetAt + 1))) {
                    return word;
                }
            }
            return null;
        }
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
        if (word.indexOf('@') >= 0) {
            if (word.length() > MAX_EMAIL_LENGTH) {
                throw new IllegalArgumentException("This email address is too long.");
            }
            if (!isValidEmailAddress(word)) {
                throw new IllegalArgumentException("Enter a valid email address without spaces.");
            }
            return word;
        }
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

    /** Validates an ASCII email address without changing its literal spelling. */
    public static boolean isValidEmailAddress(String address) {
        if (address == null || address.length() > MAX_EMAIL_LENGTH) return false;
        int at = address.indexOf('@');
        if (at <= 0 || at > MAX_EMAIL_LOCAL_LENGTH || at != address.lastIndexOf('@')
                || at == address.length() - 1) return false;

        for (int i = 0; i < at; i++) {
            char c = address.charAt(i);
            if (c == '.') {
                if (i == 0 || i == at - 1 || address.charAt(i - 1) == '.') return false;
            } else if (!isEmailLocalCharacter(c)) {
                return false;
            }
        }
        int labelStart = at + 1;
        int labels = 0;
        for (int i = labelStart; i <= address.length(); i++) {
            if (i == address.length() || address.charAt(i) == '.') {
                int length = i - labelStart;
                if (length == 0 || length > MAX_DOMAIN_LABEL_LENGTH
                        || address.charAt(labelStart) == '-' || address.charAt(i - 1) == '-') return false;
                labels++;
                labelStart = i + 1;
            } else {
                char c = address.charAt(i);
                if (!isAsciiLetter(c) && !isAsciiDigit(c) && c != '-') return false;
            }
        }
        return labels >= 2;
    }

    private static boolean isEmailLocalCharacter(char c) {
        return isAsciiLetter(c) || isAsciiDigit(c) || "!#$%&'*+-/=?^_`{|}~".indexOf(c) >= 0;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isKoreanSyllable(char c) {
        return c >= '\uac00' && c <= '\ud7a3';
    }

    private static IllegalArgumentException invalidWord() {
        return new IllegalArgumentException("Use one English or Korean word, or a valid email address. English apostrophes must be inside a word.");
    }

    private static IllegalArgumentException wordTooLong() {
        return new IllegalArgumentException("This word is too long. Use a shorter entry.");
    }
}
