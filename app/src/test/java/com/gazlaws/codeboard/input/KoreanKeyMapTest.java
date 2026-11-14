package com.gazlaws.codeboard.input;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class KoreanKeyMapTest {
    @Test
    public void encodedKeysRoundTripWithoutCollidingWithAscii() {
        for (char key = 'a'; key <= 'z'; key++) {
            int encoded = KoreanKeyMap.keyCodeFor(key);
            assertEquals(0x10000 + key, encoded);
            assertEquals(key, KoreanKeyMap.qwertyKeyFor(encoded));
            assertEquals(0, KoreanKeyMap.qwertyKeyFor(key));
        }
        for (int code : new int[] {-24, 0, '0', 'A', 'ㄱ', 0x10000 + 'A',
                0x10000 + '0', Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertEquals(0, KoreanKeyMap.qwertyKeyFor(code));
        }
    }

    @Test
    public void encodingRejectsAnythingOutsideLowercaseAsciiLetters() {
        for (char key : new char[] {'A', '0', ' ', 'ㄱ', '\0'}) {
            try {
                KoreanKeyMap.keyCodeFor(key);
                fail("Expected invalid key to be rejected: " + key);
            } catch (IllegalArgumentException expected) {
                // Only the physical lowercase QWERTY positions can be encoded.
            }
        }
    }

    @Test
    public void mapsEveryTwoSetKeyPosition() {
        String keys = "qwertyuiopasdfghjklzxcvbnm";
        String jamo = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ";
        for (int i = 0; i < keys.length(); i++) {
            assertEquals(jamo.charAt(i), KoreanKeyMap.toJamo(keys.charAt(i)));
        }
    }

    @Test
    public void shiftChangesOnlyTheSevenTwoSetKeys() {
        String keys = "QWERTYUIOPASDFGHJKLZXCVBNM";
        String jamo = "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ";
        for (int i = 0; i < keys.length(); i++) {
            assertEquals(jamo.charAt(i), KoreanKeyMap.toJamo(keys.charAt(i)));
        }
    }

    @Test
    public void ignoresNumbersSymbolsAndControlCodes() {
        for (char key : new char[] {'0', ' ', ';', '\n', '\t', 'ㄱ', (char) -24}) {
            assertEquals(0, KoreanKeyMap.toJamo(key));
        }
    }
}
