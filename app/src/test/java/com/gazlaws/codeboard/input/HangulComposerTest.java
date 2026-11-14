package com.gazlaws.codeboard.input;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HangulComposerTest {
    @Test
    public void composesKoreanWordsFromTwoSetQwertyKeys() {
        String[][] examples = {
                {"dkssudgktpdy", "안녕하세요"},
                {"gksrmf", "한글"},
                {"Rkwl", "까지"},
                {"rkqt", "값"},
                {"rhk", "과"}
        };
        for (String[] example : examples) {
            HangulComposer composer = new HangulComposer();
            StringBuilder text = new StringBuilder();
            for (char key : example[0].toCharArray()) {
                char jamo = KoreanKeyMap.toJamo(key);
                assertTrue(jamo != 0);
                text.append(composer.input(jamo).committedText);
            }
            text.append(composer.getComposingText());
            assertEquals(example[0], example[1], text.toString());
        }
    }

    @Test
    public void composesWordsAndMovesFinalToNextSyllable() {
        HangulComposer composer = new HangulComposer();
        assertUpdate(composer.input('ㅇ'), "", "ㅇ");
        assertUpdate(composer.input('ㅏ'), "", "아");
        assertUpdate(composer.input('ㄴ'), "", "안");
        assertUpdate(composer.input('ㄴ'), "안", "ㄴ");
        assertUpdate(composer.input('ㅕ'), "", "녀");
        assertUpdate(composer.input('ㅇ'), "", "녕");
        assertUpdate(composer.input('ㅎ'), "녕", "ㅎ");
        assertUpdate(composer.input('ㅏ'), "", "하");
        assertUpdate(composer.input('ㅅ'), "", "핫");
        assertUpdate(composer.input('ㅔ'), "하", "세");
        assertUpdate(composer.input('ㅇ'), "", "셍");
        assertUpdate(composer.input('ㅛ'), "세", "요");
    }

    @Test
    public void composesAndSplitsEveryFinalCluster() {
        String first = "ㄱㄴㄴㄹㄹㄹㄹㄹㄹㄹㅂ";
        String second = "ㅅㅈㅎㄱㅁㅂㅅㅌㅍㅎㅅ";
        String clusters = "ㄳㄵㄶㄺㄻㄼㄽㄾㄿㅀㅄ";
        String finals = "\0ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
        String initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
        for (int i = 0; i < clusters.length(); i++) {
            HangulComposer composer = new HangulComposer();
            composer.input('ㄱ');
            composer.input('ㅏ');
            composer.input(first.charAt(i));
            assertEquals("cluster " + clusters.charAt(i),
                    String.valueOf((char) (0xAC00 + finals.indexOf(clusters.charAt(i)))),
                    composer.input(second.charAt(i)).composingText);
            HangulComposer.Update update = composer.input('ㅏ');
            assertEquals("retained final " + clusters.charAt(i),
                    String.valueOf((char) (0xAC00 + finals.indexOf(first.charAt(i)))),
                    update.committedText);
            assertEquals("moved initial " + clusters.charAt(i),
                    String.valueOf((char) (0xAC00 + initials.indexOf(second.charAt(i)) * 21 * 28)),
                    update.composingText);
        }
    }

    @Test
    public void composesCompoundVowelsAndBackspacesEachKeystroke() {
        String[][] combinations = {
                {"ㅗㅏ", "과", "고"}, {"ㅗㅐ", "괘", "고"}, {"ㅗㅣ", "괴", "고"},
                {"ㅜㅓ", "궈", "구"}, {"ㅜㅔ", "궤", "구"}, {"ㅜㅣ", "귀", "구"},
                {"ㅡㅣ", "긔", "그"}
        };
        for (String[] combination : combinations) {
            HangulComposer composer = new HangulComposer();
            composer.input('ㄱ');
            composer.input(combination[0].charAt(0));
            assertEquals(combination[1], composer.input(combination[0].charAt(1)).composingText);
            assertEquals(combination[2], composer.backspace().composingText);
            assertEquals("ㄱ", composer.backspace().composingText);
            assertEquals("", composer.backspace().composingText);
            assertFalse(composer.hasComposition());
        }
    }

    @Test
    public void compoundVowelsCanAlsoTakeThreeKeys() {
        HangulComposer composer = new HangulComposer();
        composer.input('ㅇ');
        composer.input('ㅗ');
        composer.input('ㅏ');
        assertUpdate(composer.input('ㅣ'), "", "왜");
        assertUpdate(composer.backspace(), "", "와");
        assertUpdate(composer.backspace(), "", "오");
        assertUpdate(composer.backspace(), "", "ㅇ");
    }

    @Test
    public void backspaceRemovesFinalClusterThenFinalThenVowelThenInitial() {
        HangulComposer composer = new HangulComposer();
        for (char c : "ㅁㅏㄹㄱ".toCharArray()) composer.input(c);
        assertEquals("맑", composer.getComposingText());
        assertUpdate(composer.backspace(), "", "말");
        assertUpdate(composer.backspace(), "", "마");
        assertUpdate(composer.backspace(), "", "ㅁ");
        assertUpdate(composer.backspace(), "", "");
        assertUpdate(composer.backspace(), "", "");
        assertFalse(composer.hasComposition());
    }

    @Test
    public void standaloneJamoCombineAndSplitWithoutInventingAnInitial() {
        HangulComposer composer = new HangulComposer();
        assertUpdate(composer.input('ㅗ'), "", "ㅗ");
        assertUpdate(composer.input('ㅏ'), "", "ㅘ");
        assertUpdate(composer.input('ㄱ'), "ㅘ", "ㄱ");
        assertUpdate(composer.input('ㅅ'), "", "ㄳ");
        assertUpdate(composer.backspace(), "", "ㄱ");
        assertUpdate(composer.input('ㅅ'), "", "ㄳ");
        assertUpdate(composer.input('ㅏ'), "ㄱ", "사");
    }

    @Test
    public void shiftedConsonantsStayAtomicAndInvalidFinalsStartNextSyllable() {
        HangulComposer composer = new HangulComposer();
        assertUpdate(composer.input('ㄲ'), "", "ㄲ");
        assertUpdate(composer.input('ㅏ'), "", "까");
        assertUpdate(composer.input('ㅆ'), "", "깠");
        assertUpdate(composer.input('ㅡ'), "까", "쓰");
        assertUpdate(composer.backspace(), "", "ㅆ");
        assertUpdate(composer.backspace(), "", "");

        composer.input('ㄱ');
        composer.input('ㅏ');
        assertUpdate(composer.input('ㄸ'), "가", "ㄸ");
        assertUpdate(composer.input('ㅏ'), "", "따");
    }

    @Test
    public void directClusterJamoAlsoSplitsWhenFollowedByVowel() {
        HangulComposer composer = new HangulComposer();
        composer.input('ㄱ');
        composer.input('ㅏ');
        assertUpdate(composer.input('ㄺ'), "", "갉");
        assertUpdate(composer.input('ㅏ'), "갈", "가");

        composer.reset();
        assertUpdate(composer.input('ㄳ'), "", "ㄳ");
        assertUpdate(composer.input('ㅏ'), "ㄱ", "사");
    }

    @Test
    public void uncombinableJamoAreCommittedAndResetClearsState() {
        HangulComposer composer = new HangulComposer();
        assertFalse(composer.hasComposition());
        assertUpdate(composer.input('ㅏ'), "", "ㅏ");
        assertTrue(composer.hasComposition());
        assertUpdate(composer.input('ㅓ'), "ㅏ", "ㅓ");
        assertUpdate(composer.input('ㄴ'), "ㅓ", "ㄴ");
        assertUpdate(composer.input('ㅁ'), "ㄴ", "ㅁ");
        composer.reset();
        assertFalse(composer.hasComposition());
        assertEquals("", composer.getComposingText());
        assertUpdate(composer.input('ㅏ'), "", "ㅏ");
    }

    @Test
    public void everyModernPrecomposedSyllableCanBeProduced() {
        String initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
        String vowels = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
        String finals = "\0ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
        HangulComposer composer = new HangulComposer();
        for (int initial = 0; initial < initials.length(); initial++) {
            for (int vowel = 0; vowel < vowels.length(); vowel++) {
                for (int fin = 0; fin < finals.length(); fin++) {
                    composer.reset();
                    composer.input(initials.charAt(initial));
                    composer.input(vowels.charAt(vowel));
                    if (fin > 0) composer.input(finals.charAt(fin));
                    char expected = (char) (0xAC00 + (initial * 21 + vowel) * 28 + fin);
                    assertEquals("syllable " + expected, String.valueOf(expected),
                            composer.getComposingText());
                }
            }
        }
    }

    @Test
    public void rejectsNonJamoWithoutChangingComposition() {
        HangulComposer composer = new HangulComposer();
        composer.input('ㄱ');
        try {
            composer.input('a');
            fail("Expected non-jamo input to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("ㄱ", composer.getComposingText());
        }
    }

    private static void assertUpdate(HangulComposer.Update update, String committed, String composing) {
        assertEquals("committed text", committed, update.committedText);
        assertEquals("composing text", composing, update.composingText);
    }
}
