package com.gazlaws.codeboard;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.gazlaws.codeboard.input.KoreanKeyMap;
import com.gazlaws.codeboard.layout.Definitions;
import com.gazlaws.codeboard.layout.Key;
import com.gazlaws.codeboard.layout.ui.KeyboardButtonView;
import com.gazlaws.codeboard.layout.ui.KeyboardLayoutView;
import com.gazlaws.codeboard.layout.ui.SuggestionStripView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises physical keyboard gestures through the real IME, dictionaries and editor. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class CodeBoardIMESwipeTest {
    private Fixture fixture;

    @After public void tearDown() {
        if (fixture != null) fixture.service.destroy();
    }

    @Test public void englishGestureShowsAlternativesAndTapsContinueNormally() throws Exception {
        Fixture f = start(false, InputType.TYPE_CLASS_TEXT);
        f.swipe("hello");
        f.await(() -> f.candidateCount() > 1);
        assertEquals("hello", f.text());
        assertEquals("hello", f.composingText());
        String alternate = f.candidate(1).getText().toString();
        assertNotEquals("hello", alternate);
        assertTrue(f.candidate(1).performClick());
        assertEquals(alternate, f.text());
        assertEquals(alternate, f.composingText());

        f.tap('a');
        f.tap('.');
        f.tap(' ');
        assertEquals(alternate + " a. ", f.text());
        assertEquals("", f.composingText());
    }

    @Test public void koreanGestureUsesTheActualTwoSetLayoutAndDictionary() throws Exception {
        Fixture f = start(true, InputType.TYPE_CLASS_TEXT);
        f.swipe("gksrmf");
        f.await(() -> f.hasCandidates());
        assertEquals("한글", f.text());
        assertEquals("한글", f.composingText());
        f.tap(KoreanKeyMap.keyCodeFor('r'));
        assertEquals("한글 ㄱ", f.text());
        assertEquals("ㄱ", f.composingText());
    }

    @Test public void languageSwitchSeparatesKoreanAndEnglishGestures() throws Exception {
        Fixture f = start(true, InputType.TYPE_CLASS_TEXT);
        f.swipe("gksrmf");
        f.await(() -> f.hasCandidates());
        assertEquals("한글", f.text());
        f.tap(Definitions.CODE_LANGUAGE_SWITCH);
        f.refreshKeyboard();
        f.swipe("hello");
        f.await(() -> f.hasCandidates());
        assertEquals("한글 hello", f.text());
        f.tap(Definitions.CODE_LANGUAGE_SWITCH);
        f.refreshKeyboard();
        f.swipe("gksrmf");
        f.await(() -> f.hasCandidates());
        assertEquals("한글 hello 한글", f.text());
    }

    @Test public void rapidTapsAfterGestureAreAppliedInOrder() throws Exception {
        Fixture f = start(false, InputType.TYPE_CLASS_TEXT);
        f.swipe("hello");
        f.tap('a');
        f.tap('.');
        f.tap(' ');
        f.await(() -> f.text().endsWith(" a. "));
        assertEquals("hello a. ", f.text());
    }

    @Test public void backspaceRemovesGestureWordThenDeletesNormally() throws Exception {
        Fixture f = start(false, InputType.TYPE_CLASS_TEXT);
        f.ime.onText("x");
        f.swipe("hello");
        f.await(() -> f.hasCandidates());
        assertEquals("x hello", f.text());
        f.tap(-5);
        assertEquals("x ", f.text());
        f.tap(-5);
        assertEquals("x", f.text());
        f.tap(-5);
        assertEquals("", f.text());
        assertEquals(2, f.connection.countKeyDown(KeyEvent.KEYCODE_DEL));
    }

    @Test public void controlShortcutIsNotTurnedIntoSwipeText() throws Exception {
        Fixture f = start(false, InputType.TYPE_CLASS_TEXT);
        f.tap(17);
        f.swipe("hello");
        assertEquals("", f.text());
        KeyEvent event = f.connection.lastKeyDown(KeyEvent.KEYCODE_H);
        assertTrue((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0);
        assertFalse(f.hasCandidates());
    }

    @Test public void passwordFieldsDisableSwipingButPreserveOrdinaryKeys() throws Exception {
        Fixture f = start(false, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        f.swipe("hello");
        assertEquals("h", f.text());
        assertFalse(f.hasCandidates());
        assertEquals(View.GONE, f.strip.getVisibility());
    }

    private Fixture start(boolean korean, int inputType) throws Exception {
        fixture = new Fixture(korean, inputType);
        return fixture;
    }

    public static class TestIme extends CodeBoardIME {
        InputConnection connection;
        @Override public InputConnection getCurrentInputConnection() { return connection; }
    }

    private static final class Fixture {
        final ServiceController<TestIme> service;
        final TestIme ime;
        final RecordingConnection connection;
        KeyboardLayoutView keyboard;
        SuggestionStripView strip;
        long time = 100;

        Fixture(boolean korean, int inputType) {
            Context app = RuntimeEnvironment.getApplication();
            PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
                    .putBoolean("swipe_typing", true).putBoolean("auto_correction", false)
                    .putBoolean("spelling_suggestions", false)
                    .putBoolean("vibrate", false)
                    .putBoolean("sound", false).putBoolean("notification", false)
                    .putBoolean("navbar", false).putString("layout", korean ?
                            String.valueOf(Definitions.LAYOUT_KOREAN) : "0").commit();
            service = Robolectric.buildService(TestIme.class).create();
            ime = service.get();
            connection = new RecordingConnection(new EditText(app));
            ime.connection = connection;
            EditorInfo info = new EditorInfo();
            info.inputType = inputType;
            ime.onStartInput(info, false);
            View input = ime.onCreateInputView();
            assertTrue(input instanceof LinearLayout);
            LinearLayout root = (LinearLayout) input;
            strip = (SuggestionStripView) root.getChildAt(0);
            keyboard = (KeyboardLayoutView) root.getChildAt(1);
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        }

        void refreshKeyboard() throws Exception {
            Field keyboardField = CodeBoardIME.class.getDeclaredField("mCurrentKeyboardLayoutView");
            keyboardField.setAccessible(true);
            keyboard = (KeyboardLayoutView) keyboardField.get(ime);
            Field stripField = CodeBoardIME.class.getDeclaredField("swipeSuggestions");
            stripField.setAccessible(true);
            strip = (SuggestionStripView) stripField.get(ime);
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
            keyboard.layout(0, 0, 1000, 400);
        }

        void swipe(String qwerty) throws Exception {
            View first = letter(qwerty.charAt(0));
            long down = time;
            send(MotionEvent.ACTION_DOWN, down, time, first);
            for (int i = 1; i < qwerty.length(); i++) {
                time += qwerty.charAt(i) == qwerty.charAt(i - 1) ? 180 : 70;
                send(MotionEvent.ACTION_MOVE, down, time, letter(qwerty.charAt(i)));
            }
            time += 20;
            send(MotionEvent.ACTION_UP, down, time, letter(qwerty.charAt(qwerty.length() - 1)));
            time += 100;
        }

        void tap(int code) throws Exception {
            View key = key(code);
            long down = time;
            send(MotionEvent.ACTION_DOWN, down, time, key);
            send(MotionEvent.ACTION_UP, down, time += 20, key);
            time += 100;
        }

        private void send(int action, long down, long eventTime, View target) {
            MotionEvent event = MotionEvent.obtain(down, eventTime, action,
                    (target.getLeft() + target.getRight()) / 2f,
                    (target.getTop() + target.getBottom()) / 2f, 0);
            assertTrue("touch not handled", keyboard.dispatchTouchEvent(event));
            event.recycle();
        }

        View letter(char letter) throws Exception {
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                Key key = keyInfo(view);
                int code = key.info.code;
                if (KoreanKeyMap.qwertyKeyFor(code) == letter ||
                        (code == letter && key.info.onShiftLabel != null)) return view;
            }
            throw new AssertionError("Missing letter " + letter);
        }

        View key(int code) throws Exception {
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                if (keyInfo(view).info.code == code) return view;
            }
            throw new AssertionError("Missing key " + code);
        }

        private static Key keyInfo(View button) throws Exception {
            Field field = KeyboardButtonView.class.getDeclaredField("key");
            field.setAccessible(true);
            return (Key) field.get(button);
        }

        void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle();
                if (condition.getAsBoolean()) return;
                Thread.sleep(10);
            }
            fail("Timed out waiting for gesture; text=" + text() + ", strip=" + candidate(0).getText());
        }

        String text() { return connection.getEditable().toString(); }

        String composingText() {
            Editable text = connection.getEditable();
            int start = BaseInputConnection.getComposingSpanStart(text);
            int end = BaseInputConnection.getComposingSpanEnd(text);
            return start < 0 || end < start ? "" : text.subSequence(start, end).toString();
        }

        int candidateCount() { return hasCandidates() ? strip.getCandidateCount() : 0; }
        TextView candidate(int index) { return strip.getCandidateView(index); }
        boolean hasCandidates() { return strip.getCandidateCount() > 0 && candidate(0).isClickable(); }
    }

    /** Uses Android's editable composition behavior and models basic editor key events. */
    private static final class RecordingConnection extends BaseInputConnection {
        final EditText editor;
        final List<KeyEvent> events = new ArrayList<>();
        RecordingConnection(EditText editor) {
            super(editor, true);
            this.editor = editor;
            Selection.setSelection(editor.getText(), 0);
        }
        @Override public Editable getEditable() { return editor.getText(); }
        @Override public boolean sendKeyEvent(KeyEvent event) {
            events.add(event);
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            int code = event.getKeyCode();
            Editable text = getEditable();
            int start = Selection.getSelectionStart(text), end = Selection.getSelectionEnd(text);
            if (code == KeyEvent.KEYCODE_DEL && start == end && start > 0) {
                text.delete(start - 1, start);
            } else if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z
                    && (event.getMetaState() & KeyEvent.META_CTRL_ON) == 0) {
                char c = (char) ('a' + code - KeyEvent.KEYCODE_A);
                if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) c = Character.toUpperCase(c);
                commitText(String.valueOf(c), 1);
            }
            return true;
        }
        int countKeyDown(int code) {
            int count = 0;
            for (KeyEvent event : events) if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == code) count++;
            return count;
        }
        KeyEvent lastKeyDown(int code) {
            for (int i = events.size() - 1; i >= 0; i--) {
                KeyEvent event = events.get(i);
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == code) return event;
            }
            throw new AssertionError("Missing key event " + code);
        }
    }
}
