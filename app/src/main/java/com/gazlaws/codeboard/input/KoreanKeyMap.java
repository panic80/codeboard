package com.gazlaws.codeboard.input;

/** Maps QWERTY key positions to standard Korean two-set compatibility jamo. */
public final class KoreanKeyMap {
    private static final int KOREAN_KEY_OFFSET = 0x10000;
    private static final String JAMO = "ㅁㅠㅊㅇㄷㄹㅎㅗㅑㅓㅏㅣㅡㅜㅐㅔㅂㄱㄴㅅㅕㅍㅈㅌㅛㅋ";

    private KoreanKeyMap() {}

    /** Encodes a lowercase QWERTY position separately from ordinary text and control codes. */
    public static int keyCodeFor(char qwerty) {
        if (qwerty < 'a' || qwerty > 'z') {
            throw new IllegalArgumentException("Expected a lowercase ASCII QWERTY key");
        }
        return KOREAN_KEY_OFFSET + qwerty;
    }

    /** Returns the lowercase QWERTY position for a Korean key code, or zero for other codes. */
    public static char qwertyKeyFor(int code) {
        int key = code - KOREAN_KEY_OFFSET;
        return key >= 'a' && key <= 'z' ? (char) key : 0;
    }

    /** Returns zero for keys outside the Latin alphabet. Uppercase represents Shift. */
    public static char toJamo(char key) {
        switch (key) {
            case 'Q': return 'ㅃ';
            case 'W': return 'ㅉ';
            case 'E': return 'ㄸ';
            case 'R': return 'ㄲ';
            case 'T': return 'ㅆ';
            case 'O': return 'ㅒ';
            case 'P': return 'ㅖ';
            default:
                char lower = key >= 'A' && key <= 'Z' ? (char) (key + ('a' - 'A')) : key;
                return lower >= 'a' && lower <= 'z' ? JAMO.charAt(lower - 'a') : 0;
        }
    }
}
