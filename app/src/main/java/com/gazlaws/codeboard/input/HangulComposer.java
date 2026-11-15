package com.gazlaws.codeboard.input;

/**
 * Composes modern Hangul from the compatibility jamo produced by a two-set keyboard.
 *
 * <p>Each update contains the text that has become final since the previous input and the
 * entire current composing text. Only the active jamo are retained, so committed text is
 * owned by the editor. Call {@link #reset()} when the editor's composing region is ended.
 */
public final class HangulComposer {
    private static final String INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    // Index zero represents a syllable without a final consonant.
    private static final String FINALS = "\0ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
    private static final String FINAL_CLUSTERS = "ㄳㄵㄶㄺㄻㄼㄽㄾㄿㅀㅄ";
    private static final String CLUSTER_FIRST = "ㄱㄴㄴㄹㄹㄹㄹㄹㄹㄹㅂ";
    private static final String CLUSTER_SECOND = "ㅅㅈㅎㄱㅁㅂㅅㅌㅍㅎㅅ";

    // Individual input events are kept so backspace can undo a compound character one key at a time.
    private final StringBuilder initialKeys = new StringBuilder(2);
    private final StringBuilder vowelKeys = new StringBuilder(3);
    private final StringBuilder finalKeys = new StringBuilder(2);

    /** The finalized prefix and active composing region resulting from one operation. */
    public static final class Update {
        public final String committedText;
        public final String composingText;

        private Update(String committedText, String composingText) {
            this.committedText = committedText;
            this.composingText = composingText;
        }
    }

    /**
     * Processes one modern compatibility jamo.
     *
     * @throws IllegalArgumentException for characters that are not modern compatibility jamo
     */
    public Update input(char jamo) {
        if (!isVowel(jamo) && !isConsonant(jamo)) {
            throw new IllegalArgumentException("Expected a modern Hangul compatibility jamo");
        }

        String committed = isVowel(jamo) ? inputVowel(jamo) : inputConsonant(jamo);
        return new Update(committed, getComposingText());
    }

    /**
     * Removes the most recent input from the active composition. Does nothing if empty.
     * The caller can check {@link #hasComposition()} before calling this method to decide
     * whether an ordinary editor backspace is needed instead.
     */
    public Update backspace() {
        if (finalKeys.length() > 0) {
            finalKeys.deleteCharAt(finalKeys.length() - 1);
        } else if (vowelKeys.length() > 0) {
            vowelKeys.deleteCharAt(vowelKeys.length() - 1);
        } else if (initialKeys.length() > 0) {
            initialKeys.deleteCharAt(initialKeys.length() - 1);
        }
        return new Update("", getComposingText());
    }

    /** Clears composition state without modifying the editor. */
    public void reset() {
        initialKeys.setLength(0);
        vowelKeys.setLength(0);
        finalKeys.setLength(0);
    }

    public boolean hasComposition() {
        return initialKeys.length() > 0 || vowelKeys.length() > 0;
    }

    public String getComposingText() {
        if (!hasComposition()) {
            return "";
        }
        if (initialKeys.length() == 0) {
            return String.valueOf(combinedVowel());
        }
        if (vowelKeys.length() == 0) {
            return String.valueOf(combinedInitial());
        }

        int initialIndex = INITIALS.indexOf(combinedInitial());
        int vowelIndex = VOWELS.indexOf(combinedVowel());
        int finalIndex = finalKeys.length() == 0 ? 0 : FINALS.indexOf(combinedFinal());
        return String.valueOf((char) (0xAC00 + (initialIndex * 21 + vowelIndex) * 28 + finalIndex));
    }

    private String inputVowel(char vowel) {
        if (vowelKeys.length() == 0) {
            // A consonant cluster on its own cannot be an initial. Split it before the vowel.
            if (initialKeys.length() > 0) {
                char initial = combinedInitial();
                int clusterIndex = FINAL_CLUSTERS.indexOf(initial);
                if (clusterIndex >= 0) {
                    String committed = String.valueOf(CLUSTER_FIRST.charAt(clusterIndex));
                    initialKeys.setLength(0);
                    initialKeys.append(CLUSTER_SECOND.charAt(clusterIndex));
                    vowelKeys.append(vowel);
                    return committed;
                }
            }
            vowelKeys.append(vowel);
            return "";
        }

        if (finalKeys.length() > 0) {
            char finalConsonant = combinedFinal();
            int clusterIndex = FINAL_CLUSTERS.indexOf(finalConsonant);
            char nextInitial;
            if (clusterIndex >= 0) {
                nextInitial = CLUSTER_SECOND.charAt(clusterIndex);
                finalKeys.setLength(0);
                finalKeys.append(CLUSTER_FIRST.charAt(clusterIndex));
            } else {
                nextInitial = finalConsonant;
                finalKeys.setLength(0);
            }
            String committed = getComposingText();
            reset();
            initialKeys.append(nextInitial);
            vowelKeys.append(vowel);
            return committed;
        }

        if (combineVowels(combinedVowel(), vowel) != 0) {
            vowelKeys.append(vowel);
            return "";
        }
        String committed = getComposingText();
        reset();
        vowelKeys.append(vowel);
        return committed;
    }

    private String inputConsonant(char consonant) {
        if (!hasComposition()) {
            initialKeys.append(consonant);
            return "";
        }

        if (vowelKeys.length() == 0) {
            if (combineFinals(combinedInitial(), consonant) != 0) {
                initialKeys.append(consonant);
                return "";
            }
        } else if (initialKeys.length() > 0) {
            if (finalKeys.length() == 0 && FINALS.indexOf(consonant) > 0) {
                finalKeys.append(consonant);
                return "";
            }
            if (finalKeys.length() > 0 && combineFinals(combinedFinal(), consonant) != 0) {
                finalKeys.append(consonant);
                return "";
            }
        }

        String committed = getComposingText();
        reset();
        initialKeys.append(consonant);
        return committed;
    }

    private char combinedInitial() {
        return initialKeys.length() == 1 ? initialKeys.charAt(0)
                : combineFinals(initialKeys.charAt(0), initialKeys.charAt(1));
    }

    private char combinedVowel() {
        char result = vowelKeys.charAt(0);
        for (int i = 1; i < vowelKeys.length(); i++) {
            result = combineVowels(result, vowelKeys.charAt(i));
        }
        return result;
    }

    private char combinedFinal() {
        return finalKeys.length() == 1 ? finalKeys.charAt(0)
                : combineFinals(finalKeys.charAt(0), finalKeys.charAt(1));
    }

    private static boolean isVowel(char jamo) {
        return VOWELS.indexOf(jamo) >= 0;
    }

    private static boolean isConsonant(char jamo) {
        return INITIALS.indexOf(jamo) >= 0 || FINALS.indexOf(jamo) > 0;
    }

    private static char combineVowels(char first, char second) {
        if (first == 'ㅗ') {
            if (second == 'ㅏ') return 'ㅘ';
            if (second == 'ㅐ') return 'ㅙ';
            if (second == 'ㅣ') return 'ㅚ';
        } else if (first == 'ㅜ') {
            if (second == 'ㅓ') return 'ㅝ';
            if (second == 'ㅔ') return 'ㅞ';
            if (second == 'ㅣ') return 'ㅟ';
        } else if (first == 'ㅡ' && second == 'ㅣ') {
            return 'ㅢ';
        } else if (first == 'ㅘ' && second == 'ㅣ') {
            return 'ㅙ';
        } else if (first == 'ㅝ' && second == 'ㅣ') {
            return 'ㅞ';
        }
        return 0;
    }

    private static char combineFinals(char first, char second) {
        for (int i = 0; i < FINAL_CLUSTERS.length(); i++) {
            if (CLUSTER_FIRST.charAt(i) == first && CLUSTER_SECOND.charAt(i) == second) {
                return FINAL_CLUSTERS.charAt(i);
            }
        }
        return 0;
    }
}
