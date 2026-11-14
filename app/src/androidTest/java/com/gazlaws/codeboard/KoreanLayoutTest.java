package com.gazlaws.codeboard;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.gazlaws.codeboard.input.KoreanKeyMap;
import com.gazlaws.codeboard.layout.Box;
import com.gazlaws.codeboard.layout.Definitions;
import com.gazlaws.codeboard.layout.Key;
import com.gazlaws.codeboard.layout.builder.KeyboardLayoutBuilder;
import com.gazlaws.codeboard.layout.builder.KeyboardLayoutException;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KoreanLayoutTest {
    @Test
    public void koreanRowsUseTwoSetPositionsAndShiftLabels() throws KeyboardLayoutException {
        KeyboardLayoutBuilder builder = builder();
        Definitions.addKoreanRows(builder);
        ArrayList<Key> keys = builder.build();

        assertEquals(28, keys.size());
        String codes = "qwertyuiopasdfghjklzxcvbnm";
        String labels = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ";
        String[] shifted = {"ㅃ", "ㅉ", "ㄸ", "ㄲ", "ㅆ", null, null, null, "ㅒ", "ㅖ"};
        for (int i = 0; i < codes.length(); i++) {
            // The shift key precedes the third row's seven letters.
            int keyIndex = i < 19 ? i : i + 1;
            Key key = keys.get(keyIndex);
            assertEquals(KoreanKeyMap.keyCodeFor(codes.charAt(i)), key.info.code);
            assertEquals(codes.charAt(i), KoreanKeyMap.qwertyKeyFor(key.info.code));
            assertEquals(String.valueOf(labels.charAt(i)), key.info.label);
            if (i < shifted.length) {
                assertEquals(shifted[i], key.info.onShiftLabel);
            } else {
                assertNull(key.info.onShiftLabel);
            }
        }
        assertEquals(16, keys.get(19).info.code);
        assertTrue(keys.get(19).info.isModifier);
        assertEquals(-5, keys.get(27).info.code);
        assertTrue(keys.get(27).info.isRepeatable);
    }

    @Test
    public void customLettersRemainLiteralKeyCodes() throws KeyboardLayoutException {
        Context context = ApplicationProvider.getApplicationContext();
        KeyboardLayoutBuilder builder = builder();
        Definitions.addCustomRow(builder, "qz");
        new Definitions(context).addCustomSpaceRow(builder, "ab", true);
        ArrayList<Key> keys = builder.build();

        int[] letterIndices = {0, 1, 4, 6};
        char[] letters = {'q', 'z', 'a', 'b'};
        for (int i = 0; i < letters.length; i++) {
            int code = keys.get(letterIndices[i]).info.code;
            assertEquals(letters[i], code);
            assertEquals(0, KoreanKeyMap.qwertyKeyFor(code));
        }
    }

    @Test
    public void languageSwitchPreservesBottomRowControlsAndSymbols() throws KeyboardLayoutException {
        Context context = ApplicationProvider.getApplicationContext();
        KeyboardLayoutBuilder builder = builder();
        new Definitions(context).addCustomSpaceRow(builder, ",.;", true);
        ArrayList<Key> keys = builder.build();

        assertEquals(7, keys.size());
        int[] expectedCodes = {17, Definitions.CODE_LANGUAGE_SWITCH, ',', '.', 32, ';', -4};
        for (int i = 0; i < expectedCodes.length; i++) {
            assertEquals(expectedCodes[i], keys.get(i).info.code);
        }
        assertEquals("한/영", keys.get(1).info.label);
        assertTrue(keys.get(0).info.isModifier);
    }

    @Test
    public void defaultBottomRowHasNoLanguageSwitch() throws KeyboardLayoutException {
        Context context = ApplicationProvider.getApplicationContext();
        KeyboardLayoutBuilder builder = builder();
        new Definitions(context).addCustomSpaceRow(builder, ",.;");
        ArrayList<Key> keys = builder.build();

        int[] expectedCodes = {17, ',', '.', 32, ';', -4};
        assertEquals(expectedCodes.length, keys.size());
        for (int i = 0; i < expectedCodes.length; i++) {
            assertEquals(expectedCodes[i], keys.get(i).info.code);
        }
    }

    @Test
    public void layoutSelectionKeepsExistingValues() {
        Context context = ApplicationProvider.getApplicationContext();
        String[] names = context.getResources().getStringArray(R.array.Layouts);
        String[] values = context.getResources().getStringArray(R.array.Values);
        assertEquals(names.length, values.length);
        assertEquals("Qwerty", names[0]);
        assertEquals("Azerty", names[1]);
        assertEquals("Dvorak", names[2]);
        assertEquals("Qwertz", names[3]);
        assertEquals("Korean (두벌식)", names[Definitions.LAYOUT_KOREAN]);
        for (int i = 0; i < values.length; i++) {
            assertEquals(String.valueOf(i), values[i]);
        }
    }

    private KeyboardLayoutBuilder builder() {
        Context context = ApplicationProvider.getApplicationContext();
        return new KeyboardLayoutBuilder(context).setBox(Box.create(0, 0, 100, 100));
    }
}
