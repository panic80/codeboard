package com.gazlaws.codeboard;

import android.content.Context;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.gazlaws.codeboard.gesture.PersonalDictionary;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** End-to-end typing tests with real keyboard touches, dictionaries, and editor composition. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class CodeBoardIMEAutoCorrectTest {
    private Fixture fixture;

    @After public void tearDown() {
        if (fixture != null) fixture.service.destroy();
    }

    @Test public void englishCorrectionCanBeUndoneWithBackspace() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.type("teh ");
        f.await(() -> f.text().equals("the "));
        assertTrue(f.hasCandidate("teh"));
        f.tap(-5);
        assertEquals("teh ", f.text());
        f.tap(-5);
        assertEquals("teh", f.text());
    }

    @Test public void originalCandidateRestoresTheTypedWordAfterCorrection() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.type("teh ");
        f.await(() -> f.text().equals("the ") && f.hasCandidate("teh"));
        assertTrue(f.candidate("teh").performClick());
        assertEquals("teh ", f.text());
    }

    @Test public void knownContractionSurvivesPunctuationKeyAndSpace() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.type("can't ");
        f.await(() -> f.hasCandidate("can't"));
        assertEquals("can't ", f.text());
    }

    @Test public void manualCandidateReplacesTheWholeWordAndStaysSelected() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.type("possivle");
        f.await(() -> f.hasCandidate("possible"));
        assertTrue(f.candidate("possible").performClick());
        assertEquals("possible", f.text());
        f.tap(' ');
        assertEquals("possible ", f.text());
    }

    @Test public void punctuationIsRetainedWithAutomaticCorrection() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.type("teh. ");
        f.await(() -> f.text().equals("the. "));
        f.tap(-5);
        assertEquals("teh. ", f.text());
    }

    @Test public void koreanManualTypingCorrectsTheWholeSyllableWord() throws Exception {
        Fixture f = start(true, true, InputType.TYPE_CLASS_TEXT);
        f.typeKorean("dkssudgktpdu");
        assertEquals("안녕하세여", f.text());
        f.tap(' ');
        f.await(() -> f.text().equals("안녕하세요 "));
        f.tap(-5);
        assertEquals("안녕하세여 ", f.text());
    }

    @Test public void koreanCandidateReplacesTheWholeComposingWord() throws Exception {
        Fixture f = start(true, true, InputType.TYPE_CLASS_TEXT);
        f.typeKorean("dkssudgktpdu");
        f.await(() -> f.hasCandidate("안녕하세요"));
        assertTrue(f.candidate("안녕하세요").performClick());
        assertEquals("안녕하세요", f.text());
        assertEquals("", f.composingText());
        f.tap(' ');
        f.typeKorean("gksrmf");
        assertEquals("안녕하세요 한글", f.text());
    }

    @Test public void pasteAndCursorMovementDoNotCorrectEarlierText() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.type("te");
        f.ime.onText("h");
        f.tap(' ');
        f.waitForIdle();
        assertEquals("teh ", f.text());
        f.type("teh");
        f.tap(5000); // Left arrow moves inside the word before a space is typed.
        f.tap(' ');
        f.waitForIdle();
        assertEquals("teh te h", f.text());
    }

    @Test public void knownWordsAndSavedNamesAreNotRewritten() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.type("hello ");
        f.await(() -> f.hasCandidate("hello"));
        assertEquals("hello ", f.text());
        assertTrue(new PersonalDictionary(f.context).addWord("Wiliam"));
        assertTrue(new PersonalDictionary(f.context).getWords().contains("Wiliam"));
        f.type("Wiliam ");
        f.await(() -> f.hasCandidate("Wiliam"));
        assertEquals("hello Wiliam ", f.text());
    }

    @Test public void disablingCorrectionLeavesMisspellingIntact() throws Exception {
        Fixture f = start(false, false, InputType.TYPE_CLASS_TEXT);
        f.type("teh ");
        f.waitForIdle();
        assertEquals("teh ", f.text());
        assertFalse(f.hasCandidate("the"));
    }

    @Test public void passwordAndUriFieldsLeaveMisspellingIntact() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        f.type("teh ");
        f.waitForIdle();
        assertEquals("teh ", f.text());
        assertFalse(f.hasCandidate("the"));
        f.service.destroy();
        fixture = null;
        f = start(false, true, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        f.type("teh ");
        f.waitForIdle();
        assertEquals("teh ", f.text());
        assertFalse(f.hasCandidate("the"));
    }

    @Test public void codeIdentifiersAndControlShortcutsAreNotRewritten() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.type("tehValue ");
        f.waitForIdle();
        assertEquals("tehValue ", f.text());
        f.type("teh");
        f.tap(17);
        f.tap('c');
        f.tap(' ');
        f.waitForIdle();
        assertEquals("tehValue teh ", f.text());
        assertTrue(f.hasCtrlKey(KeyEvent.KEYCODE_C));
    }

    @Test public void typedAndSwipedWordsRemainOrdered() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.swipe("hello");
        f.await(() -> f.text().equals("hello") && f.hasCandidate("hello"));
        f.type("teh ");
        f.swipe("world");
        f.await(() -> f.text().equals("hello the world"));
        assertEquals("world", f.composingText());
    }

    @Test public void newPersonalWordIsAvailableToAPreviouslyWarmedSwipeDecoder() throws Exception {
        Fixture f = start(false, true, InputType.TYPE_CLASS_TEXT);
        f.swipe("hello");
        f.await(() -> f.text().equals("hello") && f.hasCandidate("hello"));
        f.tap(' ');
        assertEquals("hello ", f.text());
        PersonalDictionary personal = new PersonalDictionary(f.context);
        assertTrue(personal.addWord("Hello"));
        f.swipe("hello");
        f.await(() -> f.text().equals("hello Hello") && f.hasCandidate("Hello"));
        assertEquals("Hello", f.candidateTexts().get(0));
        assertFalse(f.hasCandidate("hello"));
        f.tap(' ');
        assertTrue(personal.addWord("teh"));
        f.swipe("teh");
        f.await(() -> f.text().equals("hello Hello teh") && f.hasCandidate("teh"));
        assertEquals("teh", f.composingText());
    }

    private Fixture start(boolean korean, boolean autoCorrect, int inputType) {
        fixture = new Fixture(korean, autoCorrect, inputType);
        return fixture;
    }

    public static class TestIme extends CodeBoardIME {
        InputConnection connection;
        @Override public InputConnection getCurrentInputConnection() { return connection; }
    }

    private static final class Fixture {
        final Context context;
        final ServiceController<TestIme> service;
        final TestIme ime;
        final RecordingConnection connection;
        final KeyboardLayoutView keyboard;
        final SuggestionStripView strip;
        final boolean korean;
        long time = 100;

        Fixture(boolean korean, boolean autoCorrect, int inputType) {
            this.korean = korean;
            context = RuntimeEnvironment.getApplication();
            context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit().clear().commit();
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                    .putBoolean("swipe_typing", true).putBoolean("auto_correction", autoCorrect)
                    .putBoolean("spelling_suggestions", autoCorrect)
                    .putBoolean("vibrate", false).putBoolean("sound", false)
                    .putBoolean("notification", false).putBoolean("navbar", false)
                    .putString("layout", korean ? String.valueOf(Definitions.LAYOUT_KOREAN) : "0")
                    .commit();
            service = Robolectric.buildService(TestIme.class).create();
            ime = service.get();
            connection = new RecordingConnection(new EditText(context));
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

        void type(String value) throws Exception {
            for (char c : value.toCharArray()) {
                if (c >= 'A' && c <= 'Z') {
                    tap(16);
                    tap(Character.toLowerCase(c));
                } else {
                    tap(c);
                }
            }
        }

        void typeKorean(String positions) throws Exception {
            assertTrue(korean);
            for (char c : positions.toCharArray()) tap(KoreanKeyMap.keyCodeFor(c));
        }

        void swipe(String positions) throws Exception {
            View first = letter(positions.charAt(0));
            long down = time;
            send(MotionEvent.ACTION_DOWN, down, time, first);
            for (int i = 1; i < positions.length(); i++) {
                time += positions.charAt(i) == positions.charAt(i - 1) ? 180 : 70;
                send(MotionEvent.ACTION_MOVE, down, time, letter(positions.charAt(i)));
            }
            time += 20;
            send(MotionEvent.ACTION_UP, down, time, letter(positions.charAt(positions.length() - 1)));
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

        private View letter(char letter) throws Exception {
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                Key key = keyInfo(view);
                if (KoreanKeyMap.qwertyKeyFor(key.info.code) == letter ||
                        (key.info.code == letter && key.info.onShiftLabel != null)) return view;
            }
            throw new AssertionError("Missing letter " + letter);
        }

        private View key(int code) throws Exception {
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
            fail("Timed out: text=" + text() + ", candidates=" + candidateTexts());
        }

        void waitForIdle() throws Exception {
            // Barriers drain the real background executors without relying on timing guesses.
            for (int i = 0; i < 4; i++) {
                shadowOf(Looper.getMainLooper()).idle();
                drainWorker("autoCorrectController");
                drainWorker("swipeTypingController");
            }
            shadowOf(Looper.getMainLooper()).idle();
        }

        private void drainWorker(String name) throws Exception {
            Field controllerField = CodeBoardIME.class.getDeclaredField(name);
            controllerField.setAccessible(true);
            Object controller = controllerField.get(ime);
            if (controller == null) return;
            Field workerField = controller.getClass().getDeclaredField("worker");
            workerField.setAccessible(true);
            ExecutorService worker = (ExecutorService) workerField.get(controller);
            worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
        }

        String text() { return connection.getEditable().toString(); }
        String composingText() {
            Editable text = connection.getEditable();
            int start = BaseInputConnection.getComposingSpanStart(text);
            int end = BaseInputConnection.getComposingSpanEnd(text);
            return start < 0 || end < start ? "" : text.subSequence(start, end).toString();
        }
        boolean hasCandidate(String text) { return candidate(text) != null; }
        TextView candidate(String text) {
            for (int i = 0; i < strip.getCandidateCount(); i++) {
                TextView item = strip.getCandidateView(i);
                if (item.isClickable() && item.getText().toString().equals(text)) return item;
            }
            return null;
        }
        List<String> candidateTexts() {
            ArrayList<String> result = new ArrayList<>();
            for (int i = 0; i < strip.getCandidateCount(); i++) {
                result.add(strip.getCandidateView(i).getText().toString());
            }
            return result;
        }
        boolean hasCtrlKey(int code) {
            for (KeyEvent event : connection.events) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == code
                        && (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) return true;
            }
            return false;
        }
    }

    private static final class RecordingConnection extends BaseInputConnection {
        final EditText editor;
        final List<KeyEvent> events = new ArrayList<>();
        RecordingConnection(EditText editor) {
            super(editor, true);
            this.editor = editor;
            Selection.setSelection(editor.getText(), 0);
        }
        @Override public Editable getEditable() { return editor.getText(); }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText extracted = new ExtractedText();
            extracted.selectionStart = Selection.getSelectionStart(getEditable());
            extracted.selectionEnd = Selection.getSelectionEnd(getEditable());
            return extracted;
        }
        @Override public boolean sendKeyEvent(KeyEvent event) {
            events.add(event);
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            int code = event.getKeyCode();
            Editable text = getEditable();
            int start = Selection.getSelectionStart(text), end = Selection.getSelectionEnd(text);
            if (code == KeyEvent.KEYCODE_DEL && start == end && start > 0) {
                text.delete(start - 1, start);
            } else if (code == KeyEvent.KEYCODE_DPAD_LEFT && start == end && start > 0) {
                Selection.setSelection(text, start - 1);
            } else if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z
                    && (event.getMetaState() & KeyEvent.META_CTRL_ON) == 0) {
                char c = (char) ('a' + code - KeyEvent.KEYCODE_A);
                if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) c = Character.toUpperCase(c);
                commitText(String.valueOf(c), 1);
            }
            return true;
        }
    }
}
