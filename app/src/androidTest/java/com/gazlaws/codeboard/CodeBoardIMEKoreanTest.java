package com.gazlaws.codeboard;

import android.content.Context;
import android.test.UiThreadTest;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.gazlaws.codeboard.input.KoreanKeyMap;
import com.gazlaws.codeboard.layout.Definitions;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the actual IME callbacks with a real editable composing region. */
@RunWith(AndroidJUnit4.class)
public class CodeBoardIMEKoreanTest {
    @Test
    @UiThreadTest
    public void qwertyKeysComposeWordsInTheEditor() throws Exception {
        Harness h = new Harness();
        h.type("dkssudgktpdy");
        h.assertTextAndComposition("안녕하세요", "요");
        assertFalse(h.editor.hasKeyDown(KeyEvent.KEYCODE_D));
    }

    @Test
    @UiThreadTest
    public void shiftIsOneShotForTenseConsonantsAndShiftedVowels() throws Exception {
        Harness h = new Harness();
        h.key(16);
        h.type("rkr");
        h.assertTextAndComposition("깍", "깍");
        assertEquals(1, h.editor.countKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT));
        assertEquals(1, h.editor.countKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT));

        h.ime.onText(" ");
        h.key(16);
        h.type("oo");
        h.assertTextAndComposition("깍 ㅒㅐ", "ㅐ");
    }

    @Test
    @UiThreadTest
    public void shiftLockKeepsMappingUntilUnlocked() throws Exception {
        Harness h = new Harness();
        h.key(16);
        h.ime.onKeyLongPress(16);
        h.type("rkr");
        h.assertTextAndComposition("깎", "깎");
        h.ime.onKeyLongPress(16);
        h.type("r");
        h.assertTextAndComposition("깎ㄱ", "ㄱ");
    }

    @Test
    @UiThreadTest
    public void backspaceUnwindsACompoundVowel() throws Exception {
        Harness h = new Harness();
        h.type("rhk");
        h.assertTextAndComposition("과", "과");
        h.key(-5);
        h.assertTextAndComposition("고", "고");
    }

    @Test
    @UiThreadTest
    public void finalClustersMoveToTheNextSyllable() throws Exception {
        Harness h = new Harness();
        h.type("rkqt");
        h.assertTextAndComposition("값", "값");
        h.type("k");
        h.assertTextAndComposition("갑사", "사");
    }

    @Test
    @UiThreadTest
    public void backspaceUndoesEachJamoThenUsesOrdinaryDelete() throws Exception {
        Harness h = new Harness();
        h.ime.onText("x");
        h.type("rkqt");
        h.assertTextAndComposition("x값", "값");
        h.key(-5);
        h.assertTextAndComposition("x갑", "갑");
        h.key(-5);
        h.assertTextAndComposition("x가", "가");
        h.key(-5);
        h.assertTextAndComposition("xㄱ", "ㄱ");
        h.key(-5);
        assertEquals("x", h.editor.text());
        assertEquals(0, h.editor.countKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        h.key(-5);
        assertEquals("", h.editor.text());
        assertEquals(1, h.editor.countKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
    }

    @Test
    @UiThreadTest
    public void punctuationSpaceAndTextEndComposition() throws Exception {
        Harness h = new Harness();
        h.type("rk");
        h.key('.');
        h.type("rk");
        h.key(32);
        h.ime.onRelease(32);
        h.type("rk");
        h.ime.onText("!");
        h.type("k");
        h.assertTextAndComposition("가.가 가!ㅏ", "ㅏ");
    }

    @Test
    @UiThreadTest
    public void controlShortcutBypassesHangulAndThenReturnsToKorean() throws Exception {
        Harness h = new Harness();
        h.type("rk");
        h.key(17);
        h.key(KoreanKeyMap.keyCodeFor('c'));
        assertEquals("가", h.editor.text());
        KeyEvent shortcut = h.editor.lastKeyDown(KeyEvent.KEYCODE_C);
        assertTrue((shortcut.getMetaState() & KeyEvent.META_CTRL_ON) != 0);
        h.type("k");
        h.assertTextAndComposition("가ㅏ", "ㅏ");
    }

    @Test
    @UiThreadTest
    public void languageSwitchCommitsAndRoundTripsThroughEnglish() throws Exception {
        Harness h = new Harness();
        h.type("rk");
        h.key(Definitions.CODE_LANGUAGE_SWITCH);
        h.type("r");
        h.key(Definitions.CODE_LANGUAGE_SWITCH);
        h.type("k");
        h.assertTextAndComposition("가rㅏ", "ㅏ");
        assertTrue(h.editor.hasKeyDown(KeyEvent.KEYCODE_R));
    }

    @Test
    @UiThreadTest
    public void literalLatinCustomKeyCommitsKoreanComposition() throws Exception {
        Harness h = new Harness();
        h.type("rk");
        h.key('r');
        h.type("k");
        h.assertTextAndComposition("가rㅏ", "ㅏ");
        assertTrue(h.editor.hasKeyDown(KeyEvent.KEYCODE_R));
    }

    @Test
    @UiThreadTest
    public void selectionMoveFinishesCompositionButUpdateAtItsEndKeepsIt() throws Exception {
        Harness h = new Harness();
        h.type("rk");
        h.ime.onUpdateSelection(0, 0, 1, 1, 0, 1);
        h.type("s");
        h.assertTextAndComposition("간", "간");
        Selection.setSelection(h.editor.editable, 0);
        h.ime.onUpdateSelection(1, 1, 0, 0, 0, 1);
        h.type("k");
        h.assertTextAndComposition("ㅏ간", "ㅏ");
    }

    @Test
    @UiThreadTest
    public void restartingInputAndChangingFocusFinishComposition() throws Exception {
        Harness h = new Harness();
        h.type("rk");
        h.ime.onStartInput(new EditorInfo(), true);
        h.type("k");
        h.assertTextAndComposition("가ㅏ", "ㅏ");
        h.ime.onViewClicked(true);
        h.type("r");
        h.assertTextAndComposition("가ㅏㄱ", "ㄱ");
    }

    @Test
    @UiThreadTest
    public void finishingInputKeepsEditorsSeparate() throws Exception {
        Harness h = new Harness();
        h.type("rk");
        h.ime.onFinishInput();
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(h.editor.editable));
        Editor next = new Editor();
        h.ime.connection = next;
        h.ime.onStartInput(new EditorInfo(), false);
        h.type("k");
        assertEquals("가", h.editor.text());
        assertEquals("ㅏ", next.text());
        assertEquals(0, BaseInputConnection.getComposingSpanStart(next.editable));
    }

    private static final class Harness {
        final Editor editor = new Editor();
        final TestIme ime = new TestIme(editor);
        boolean koreanMode = true;

        Harness() throws Exception {
            // The layout choice is normally populated by onCreateInputView and preferences.
            Field selected = CodeBoardIME.class.getDeclaredField("koreanLayoutSelected");
            selected.setAccessible(true);
            selected.setBoolean(ime, true);
        }

        void key(int code) {
            ime.onKey(code, null);
            if (code == Definitions.CODE_LANGUAGE_SWITCH) koreanMode = !koreanMode;
        }

        void type(String keys) {
            for (char code : keys.toCharArray()) {
                key(koreanMode ? KoreanKeyMap.keyCodeFor(code) : code);
            }
        }

        void assertTextAndComposition(String text, String composing) {
            assertEquals(text, editor.text());
            int start = BaseInputConnection.getComposingSpanStart(editor.editable);
            int end = BaseInputConnection.getComposingSpanEnd(editor.editable);
            assertTrue("missing composing region", start >= 0 && end >= start);
            assertEquals(composing, editor.editable.subSequence(start, end).toString());
        }
    }

    private static final class TestIme extends CodeBoardIME {
        InputConnection connection;

        TestIme(InputConnection connection) {
            this.connection = connection;
        }

        @Override public InputConnection getCurrentInputConnection() { return connection; }
        @Override public View onCreateInputView() { return null; }
        @Override public void setInputView(View view) { }
        @Override public void controlKeyUpdateView() { }
        @Override public void shiftKeyUpdateView() { }
    }

    /** Uses Android's real composition implementation; emulates basic editor key events. */
    private static final class Editor extends BaseInputConnection {
        final Editable editable = new SpannableStringBuilder();
        final List<KeyEvent> events = new ArrayList<>();

        Editor() {
            super(new View((Context) ApplicationProvider.getApplicationContext()), true);
            Selection.setSelection(editable, 0);
        }

        @Override public Editable getEditable() { return editable; }

        @Override public boolean sendKeyEvent(KeyEvent event) {
            events.add(event);
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            int code = event.getKeyCode();
            int start = Selection.getSelectionStart(editable);
            int end = Selection.getSelectionEnd(editable);
            if (code == KeyEvent.KEYCODE_DEL && start == end && start > 0) {
                editable.delete(start - 1, start);
            } else if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z
                    && (event.getMetaState() & KeyEvent.META_CTRL_ON) == 0) {
                char c = (char) ('a' + code - KeyEvent.KEYCODE_A);
                if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) c = Character.toUpperCase(c);
                editable.replace(Math.min(start, end), Math.max(start, end), String.valueOf(c));
            }
            return true;
        }

        String text() { return editable.toString(); }

        boolean hasKeyDown(int code) { return countKey(KeyEvent.ACTION_DOWN, code) > 0; }

        int countKey(int action, int code) {
            int count = 0;
            for (KeyEvent event : events) {
                if (event.getAction() == action && event.getKeyCode() == code) count++;
            }
            return count;
        }

        KeyEvent lastKeyDown(int code) {
            for (int i = events.size() - 1; i >= 0; i--) {
                KeyEvent event = events.get(i);
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == code) return event;
            }
            throw new AssertionError("Missing key down " + code);
        }
    }
}
