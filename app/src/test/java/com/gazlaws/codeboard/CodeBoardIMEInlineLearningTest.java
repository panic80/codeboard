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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Learning through the real keyboard, editor, dictionaries, and asynchronous controllers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class CodeBoardIMEInlineLearningTest {
    private Context context;
    private Fixture fixture;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        if (fixture != null) fixture.service.destroy();
    }

    @Test public void learnedEnglishWordSurvivesRestartAndWorksForTypingAndSwipe() throws Exception {
        Fixture first = start(false, true, true, InputType.TYPE_CLASS_TEXT);
        first.type("teh");
        first.await(() -> first.canAdd("teh"));
        assertTrue(first.action().performClick());
        assertTrue(new PersonalDictionary(context).getWords().contains("teh"));
        assertEquals(context.getString(R.string.inline_learning_saved), first.actionLabel().getText().toString());
        assertFalse(first.action().isClickable());
        first.tap(' ');
        first.drainWorkers();
        assertEquals("teh ", first.text());

        Fixture restarted = start(false, true, true, InputType.TYPE_CLASS_TEXT);
        restarted.type("teh ");
        restarted.drainWorkers();
        assertEquals("teh ", restarted.text());
        restarted.swipe("teh");
        restarted.await(() -> restarted.text().equals("teh teh") && restarted.hasCandidate("teh"));
        assertEquals("teh", restarted.composingText());
    }

    @Test public void learnedKoreanWordSurvivesRestartAndWorksForTypingAndSwipe() throws Exception {
        Fixture first = start(true, true, true, InputType.TYPE_CLASS_TEXT);
        first.typeKorean("dkssudgktpdu");
        first.await(() -> first.canAdd("안녕하세여"));
        assertTrue(first.action().performClick());
        assertTrue(new PersonalDictionary(context).getWords().contains("안녕하세여"));
        first.tap(' ');
        first.drainWorkers();
        assertEquals("안녕하세여 ", first.text());

        Fixture restarted = start(true, true, true, InputType.TYPE_CLASS_TEXT);
        restarted.typeKorean("dkssudgktpdu");
        restarted.tap(' ');
        restarted.drainWorkers();
        assertEquals("안녕하세여 ", restarted.text());
        restarted.swipe("dkssudgktpdu");
        restarted.await(() -> restarted.text().equals("안녕하세여 안녕하세여")
                && restarted.hasCandidate("안녕하세여"));
    }

    @Test public void teachingAfterCorrectionRestoresAndSavesOriginal() throws Exception {
        Fixture f = start(false, true, true, InputType.TYPE_CLASS_TEXT);
        f.type("teh ");
        f.await(() -> f.text().equals("the ") && f.canAdd("teh"));
        assertEquals("teh", f.strip.getCandidateView(0).getText().toString());
        assertTrue(f.action().performClick());
        assertEquals("teh ", f.text());
        assertTrue(new PersonalDictionary(context).getWords().contains("teh"));
        assertEquals(context.getString(R.string.inline_learning_saved), f.actionLabel().getText().toString());
        f.type("teh ");
        f.drainWorkers();
        assertEquals("teh teh ", f.text());
    }

    @Test public void suggestionsRemainUsableWhenAutomaticCorrectionIsOff() throws Exception {
        Fixture f = start(false, false, true, InputType.TYPE_CLASS_TEXT);
        f.type("possivle");
        f.await(() -> f.hasCandidate("possible") && f.canAdd("possivle"));
        assertEquals("possivle", f.strip.getCandidateView(0).getText().toString());
        assertEquals("possivle", f.text());
        f.tap(' ');
        f.await(() -> f.hasCandidate("possible"));
        assertEquals("possivle ", f.text());
        assertTrue(f.candidate("possible").performClick());
        assertEquals("possible ", f.text());
    }

    @Test public void savedWordShowsFeedbackAndCannotBeAddedTwice() throws Exception {
        Fixture f = start(false, true, true, InputType.TYPE_CLASS_TEXT);
        f.type("teh");
        f.await(() -> f.canAdd("teh"));
        assertTrue(f.action().performClick());
        assertEquals(context.getString(R.string.inline_learning_saved), f.actionLabel().getText().toString());
        assertFalse(f.action().isClickable());
        assertFalse(f.action().performClick());
        assertEquals(1, new PersonalDictionary(context).getWords().size());
    }

    @Test public void staleActionCannotSaveAfterPasteCursorMoveOrModeChange() throws Exception {
        Fixture f = start(false, true, true, InputType.TYPE_CLASS_TEXT);
        f.type("teh");
        f.await(() -> f.canAdd("teh"));
        View oldAction = f.action();
        f.ime.onText("x");
        f.drainWorkers();
        assertFalse(f.canAdd("teh"));
        oldAction.performClick();
        assertFalse(new PersonalDictionary(context).getWords().contains("teh"));

        f.tap(' ');
        f.type("teh");
        f.await(() -> f.canAdd("teh"));
        oldAction = f.action();
        f.tap(5000);
        f.drainWorkers();
        assertFalse(f.canAdd("teh"));
        oldAction.performClick();
        assertFalse(new PersonalDictionary(context).getWords().contains("teh"));

        // Reset the editor and use the normal keyboard's SYM button to change modes.
        Fixture mode = start(false, true, true, InputType.TYPE_CLASS_TEXT);
        mode.type("teh");
        mode.await(() -> mode.canAdd("teh"));
        oldAction = mode.action();
        mode.tap(-1);
        mode.refreshView();
        mode.drainWorkers();
        assertFalse(mode.canAdd("teh"));
        oldAction.performClick();
        assertFalse(new PersonalDictionary(context).getWords().contains("teh"));
    }

    @Test public void staleActionCannotSaveIntoAnotherEditorOrAfterControl() throws Exception {
        Fixture f = start(false, true, true, InputType.TYPE_CLASS_TEXT);
        f.type("teh");
        f.await(() -> f.canAdd("teh"));
        View oldAction = f.action();
        Editor other = new Editor(new EditText(context));
        f.ime.connection = other;
        oldAction.performClick();
        f.drainWorkers();
        assertEquals("", other.getEditable().toString());
        assertFalse(new PersonalDictionary(context).getWords().contains("teh"));

        Fixture ctrl = start(false, true, true, InputType.TYPE_CLASS_TEXT);
        ctrl.type("teh");
        ctrl.await(() -> ctrl.canAdd("teh"));
        oldAction = ctrl.action();
        ctrl.tap(17);
        ctrl.drainWorkers();
        assertFalse(ctrl.canAdd("teh"));
        oldAction.performClick();
        assertFalse(new PersonalDictionary(context).getWords().contains("teh"));
    }

    @Test public void protectedFieldsAndDisabledSuggestionsNeverOfferLearning() throws Exception {
        int[] protectedTypes = {
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        };
        for (int type : protectedTypes) {
            Fixture f = start(false, true, true, type);
            f.type("teh");
            f.drainWorkers();
            assertFalse(f.canAdd("teh"));
            assertFalse(new PersonalDictionary(context).getWords().contains("teh"));
        }
        Fixture f = start(false, true, false, InputType.TYPE_CLASS_TEXT);
        f.type("teh ");
        f.drainWorkers();
        assertEquals("the ", f.text());
        assertFalse(f.canAdd("teh"));
    }

    private Fixture start(boolean korean, boolean autoCorrect, boolean suggestions, int inputType) {
        if (fixture != null) fixture.service.destroy();
        fixture = new Fixture(context, korean, autoCorrect, suggestions, inputType);
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
        final Editor connection;
        KeyboardLayoutView keyboard;
        SuggestionStripView strip;
        long time = 100;

        Fixture(Context context, boolean korean, boolean autoCorrect, boolean suggestions, int inputType) {
            this.context = context;
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                    .putBoolean("swipe_typing", true).putBoolean("auto_correction", autoCorrect)
                    .putBoolean("spelling_suggestions", suggestions)
                    .putBoolean("vibrate", false).putBoolean("sound", false)
                    .putBoolean("notification", false).putBoolean("navbar", false)
                    .putString("layout", korean ? String.valueOf(Definitions.LAYOUT_KOREAN) : "0").commit();
            service = Robolectric.buildService(TestIme.class).create();
            ime = service.get();
            connection = new Editor(new EditText(context));
            ime.connection = connection;
            EditorInfo info = new EditorInfo();
            info.inputType = inputType;
            ime.onStartInput(info, false);
            LinearLayout root = (LinearLayout) ime.onCreateInputView();
            strip = (SuggestionStripView) root.getChildAt(0);
            keyboard = (KeyboardLayoutView) root.getChildAt(1);
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        }

        void refreshView() throws Exception {
            Field field = CodeBoardIME.class.getDeclaredField("swipeSuggestions");
            field.setAccessible(true);
            strip = (SuggestionStripView) field.get(ime);
        }

        void type(String value) throws Exception {
            for (char c : value.toCharArray()) tap(c);
        }

        void typeKorean(String keys) throws Exception {
            for (char c : keys.toCharArray()) tap(KoreanKeyMap.keyCodeFor(c));
        }

        void tap(int code) throws Exception {
            View key = key(code, false);
            long down = time;
            send(MotionEvent.ACTION_DOWN, down, time, key);
            send(MotionEvent.ACTION_UP, down, time += 20, key);
            time += 100;
        }

        void swipe(String keys) throws Exception {
            long down = time;
            send(MotionEvent.ACTION_DOWN, down, time, key(keys.charAt(0), true));
            for (int i = 1; i < keys.length(); i++) {
                time += keys.charAt(i) == keys.charAt(i - 1) ? 180 : 70;
                send(MotionEvent.ACTION_MOVE, down, time, key(keys.charAt(i), true));
            }
            time += 20;
            send(MotionEvent.ACTION_UP, down, time, key(keys.charAt(keys.length() - 1), true));
            time += 100;
        }

        private View key(int code, boolean byPosition) throws Exception {
            Field field = KeyboardButtonView.class.getDeclaredField("key");
            field.setAccessible(true);
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                Key key = (Key) field.get(view);
                if (!byPosition && key.info.code == code) return view;
                if (byPosition && (KoreanKeyMap.qwertyKeyFor(key.info.code) == code ||
                        (key.info.code == code && key.info.onShiftLabel != null))) return view;
            }
            throw new AssertionError("Missing key " + code);
        }

        private void send(int action, long down, long when, View key) {
            MotionEvent event = MotionEvent.obtain(down, when, action,
                    (key.getLeft() + key.getRight()) / 2f, (key.getTop() + key.getBottom()) / 2f, 0);
            assertTrue(keyboard.dispatchTouchEvent(event));
            event.recycle();
        }

        View action() { return strip.findViewById(R.id.inline_learning_action); }
        TextView actionLabel() { return strip.findViewById(R.id.inline_learning_label); }
        TextView actionWord() { return strip.findViewById(R.id.inline_learning_word); }

        boolean canAdd(String word) {
            View action = action();
            return strip.getVisibility() == View.VISIBLE && action != null &&
                    action.getVisibility() == View.VISIBLE && action.isEnabled() && action.isClickable() &&
                    context.getString(R.string.inline_learning_add_word).contentEquals(actionLabel().getText()) &&
                    word.contentEquals(actionWord().getText()) && action.getContentDescription() != null &&
                    action.getContentDescription().toString().contains(word);
        }

        TextView candidate(String word) {
            for (int i = 0; i < strip.getCandidateCount(); i++) {
                TextView view = strip.getCandidateView(i);
                if (view != null && view.isClickable() && word.contentEquals(view.getText())) return view;
            }
            return null;
        }

        boolean hasCandidate(String word) { return candidate(word) != null; }
        String text() { return connection.getEditable().toString(); }
        String composingText() {
            Editable text = connection.getEditable();
            int start = BaseInputConnection.getComposingSpanStart(text);
            int end = BaseInputConnection.getComposingSpanEnd(text);
            return start < 0 || end < start ? "" : text.subSequence(start, end).toString();
        }

        void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle();
                if (condition.getAsBoolean()) return;
                Thread.sleep(10);
            }
            fail("Timed out, text=" + text() + ", action=" + (actionLabel() == null ? "missing" : actionLabel().getText()));
        }

        void drainWorkers() throws Exception {
            for (int i = 0; i < 4; i++) {
                shadowOf(Looper.getMainLooper()).idle();
                drain("autoCorrectController");
                drain("swipeTypingController");
            }
            shadowOf(Looper.getMainLooper()).idle();
        }

        private void drain(String name) throws Exception {
            Field owner = CodeBoardIME.class.getDeclaredField(name);
            owner.setAccessible(true);
            Object controller = owner.get(ime);
            if (controller == null) return;
            Field worker = controller.getClass().getDeclaredField("worker");
            worker.setAccessible(true);
            ((ExecutorService) worker.get(controller)).submit(() -> { }).get(10, TimeUnit.SECONDS);
        }
    }

    private static final class Editor extends BaseInputConnection {
        final EditText view;
        Editor(EditText view) {
            super(view, true);
            this.view = view;
            Selection.setSelection(view.getText(), 0);
        }
        @Override public Editable getEditable() { return view.getText(); }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText result = new ExtractedText();
            result.selectionStart = Selection.getSelectionStart(getEditable());
            result.selectionEnd = Selection.getSelectionEnd(getEditable());
            return result;
        }
        @Override public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            int code = event.getKeyCode();
            Editable text = getEditable();
            int position = Selection.getSelectionStart(text);
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && position > 0) {
                Selection.setSelection(text, position - 1);
            } else if (code == KeyEvent.KEYCODE_DEL && position > 0) {
                text.delete(position - 1, position);
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
