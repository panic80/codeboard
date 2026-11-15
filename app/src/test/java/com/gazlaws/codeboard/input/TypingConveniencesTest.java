package com.gazlaws.codeboard.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TypingConveniencesTest {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;

    @Test public void sentenceCapitalizationRequiresSentenceBoundaryOrBeginning() {
        assertCap("", TEXT, true);
        assertCap("   ", TEXT, true);
        assertCap("hello", TEXT, false);
        assertCap("hello ", TEXT, false);
        assertCap("hello.", TEXT, false);
        assertCap("hello. ", TEXT, true);
        assertCap("hello!  ", TEXT, true);
        assertCap("hello? ", TEXT, true);
        assertCap("first line\n", TEXT, true);
        assertCap("first line\nnext", TEXT, false);
    }

    @Test public void editorCapitalizationFlagsOverrideSentenceDefault() {
        int words = TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
        int characters = TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
        int sentences = TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        assertCap("hello ", words, true);
        assertCap("hello", words, false);
        assertCap("hello", characters, true);
        assertCap("hello ", sentences, false);
        assertCap("hello. ", sentences, true);
    }

    @Test public void privateAddressUrlAndNonTextFieldsNeverCapitalize() {
        int[] unsafe = {
                InputType.TYPE_CLASS_NUMBER,
                TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        };
        for (int type : unsafe) assertCap("", type | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, false);
        assertFalse(TypingConveniences.shouldCapitalize(null, TEXT));
    }

    @Test public void fastSecondSpaceReplacesFirstAndPreservesDecimalText() {
        TypingConveniences helper = new TypingConveniences();
        Editor editor = new Editor("Version 3.14 ");
        helper.recordSpace(100);
        assertTrue(helper.tryDoubleSpace(editor, 800));
        assertEquals("Version 3.14. ", editor.text());
        assertFalse(helper.tryDoubleSpace(editor, 801));
    }

    @Test public void slowOrInterveningEditDoesNotBecomePeriod() {
        TypingConveniences helper = new TypingConveniences();
        Editor editor = new Editor("hello ");
        helper.recordSpace(100);
        assertFalse(helper.tryDoubleSpace(editor, 801));
        helper.recordSpace(900);
        helper.clear();
        assertFalse(helper.tryDoubleSpace(editor, 901));
        assertEquals("hello ", editor.text());
    }

    @Test public void punctuationMultipleSpacesAndSelectionsAreLeftUntouched() {
        TypingConveniences helper = new TypingConveniences();
        for (String text : new String[] {"hello. ", "hello! ", "hello  ", ". ", " "}) {
            Editor editor = new Editor(text);
            helper.recordSpace(100);
            assertFalse(text, helper.tryDoubleSpace(editor, 200));
            assertEquals(text, editor.text());
        }
        Editor editor = new Editor("hello world");
        Selection.setSelection(editor.getEditable(), 6, 11);
        helper.recordSpace(100);
        assertFalse(helper.tryDoubleSpace(editor, 200));
        assertEquals("hello world", editor.text());
    }

    @Test public void cursorInMiddleOfWordDoesNotChangeText() {
        TypingConveniences helper = new TypingConveniences();
        Editor editor = new Editor("hello world");
        Selection.setSelection(editor.getEditable(), 6);
        helper.recordSpace(100);
        assertFalse(helper.tryDoubleSpace(editor, 200));
        assertEquals("hello world", editor.text());
    }

    private static void assertCap(String text, int type, boolean expected) {
        assertEquals(text, expected, TypingConveniences.shouldCapitalize(new Editor(text), type));
    }

    private static final class Editor extends BaseInputConnection {
        private final Editable editable;

        Editor(String initial) {
            super(new EditText(RuntimeEnvironment.getApplication()), true);
            editable = Editable.Factory.getInstance().newEditable(initial);
            Selection.setSelection(editable, editable.length());
        }

        @Override public Editable getEditable() { return editable; }
        String text() { return editable.toString(); }
    }
}
