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

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Editor returns surrounding text and selection callbacks, but no extracted-text snapshot. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class CodeBoardIMEEditorCompatibilityTest {
    private Context context;
    private Fixture fixture;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        if (fixture != null) fixture.service.destroy();
    }

    @Test public void surroundingTextAndSelectionUpdatesEnableSuggestionsAndLearning() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        assertNull(f.connection.getExtractedText(new ExtractedTextRequest(), 0));
        f.type("isd", true);
        assertEquals("isd", f.text());
        assertEquals("isd", f.connection.getTextBeforeCursor(20, 0).toString());
        assertEquals("", f.connection.getTextAfterCursor(20, 0).toString());
        f.drainWorkers();
        assertTrue("The typed word should appear among suggestions", f.hasCandidate("isd"));
        assertTrue("The typed word should offer Add", f.canAdd("isd"));
        assertTrue(f.action().performClick());
        assertTrue(new PersonalDictionary(context).getWords().contains("isd"));
        assertEquals(context.getString(R.string.inline_learning_saved), f.actionLabel().getText().toString());
    }

    @Test public void coalescedSelectionCallbackDoesNotDiscardTypedWord() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("isd", false);
        // Android may coalesce multiple editor notifications into one update.
        f.ime.onUpdateSelection(0, 0, 3, 3, -1, -1);
        f.drainWorkers();
        assertTrue(f.hasCandidate("isd"));
        assertTrue(f.canAdd("isd"));
    }

    @Test public void delayedOrderedSelectionCallbacksDoNotDiscardTypedWord() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("isd", false);
        // The editor can post the notifications and deliver them after subsequent key presses.
        f.ime.onUpdateSelection(0, 0, 1, 1, -1, -1);
        f.ime.onUpdateSelection(1, 1, 2, 2, -1, -1);
        f.ime.onUpdateSelection(2, 2, 3, 3, -1, -1);
        f.drainWorkers();
        assertTrue(f.hasCandidate("isd"));
        assertTrue(f.canAdd("isd"));
    }

    @Test public void leadingSpaceWithoutSelectionCallbacksDoesNotBlockNextWord() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type(" isd", false);
        f.drainWorkers();
        assertEquals(" isd", f.text());
        assertTrue(f.hasCandidate("isd"));
        assertTrue(f.canAdd("isd"));
        // A later coalesced editor notification must not discard it either.
        f.ime.onUpdateSelection(0, 0, 4, 4, -1, -1);
        f.drainWorkers();
        assertTrue(f.hasCandidate("isd"));
        assertTrue(f.canAdd("isd"));
    }

    @Test public void longEditorContextUsesTheReportedCaretPosition() throws Exception {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 300; i++) prefix.append('a');
        prefix.append(' ');
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true, prefix.toString());
        f.type("isd", true);
        f.drainWorkers();
        assertEquals(prefix + "isd", f.text());
        assertTrue(f.hasCandidate("isd"));
        assertTrue(f.canAdd("isd"));
    }

    @Test public void automaticCorrectionCanBeRestoredAndLearnedWithoutExtractedText() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("teh ", true);
        f.drainWorkers();
        assertEquals("the ", f.text());
        assertTrue(f.canAdd("teh"));
        assertTrue(f.action().performClick());
        assertEquals("teh ", f.text());
        assertTrue(new PersonalDictionary(context).getWords().contains("teh"));
    }

    @Test public void missingSurroundingTextStillFailsClosed() throws Exception {
        for (boolean missingBefore : new boolean[] { true, false }) {
            Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
            f.connection.beforeUnavailable = missingBefore;
            f.connection.afterUnavailable = !missingBefore;
            f.type("isd", true);
            f.drainWorkers();
            assertEquals("isd", f.text());
            assertFalse(f.canAdd("isd"));
            assertEquals(0, f.strip.getCandidateCount());
            assertFalse(new PersonalDictionary(context).getWords().contains("isd"));
        }
    }

    @Test public void cursorMoveInvalidatesOldAction() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("isd", true);
        f.drainWorkers();
        assertTrue(f.canAdd("isd"));
        View oldAction = f.action();
        f.moveSelection(0, 0);
        oldAction.performClick();
        f.drainWorkers();
        assertFalse(f.canAdd("isd"));
        assertFalse(f.hasCandidate("isd"));
        assertFalse(new PersonalDictionary(context).getWords().contains("isd"));
    }

    @Test public void selectionRangeInvalidatesOldAction() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("isd", true);
        f.drainWorkers();
        assertTrue(f.canAdd("isd"));
        View oldAction = f.action();
        f.moveSelection(1, 2);
        oldAction.performClick();
        f.drainWorkers();
        assertFalse(f.canAdd("isd"));
        assertFalse(new PersonalDictionary(context).getWords().contains("isd"));
    }

    @Test public void changedTextInvalidatesOldActionWithoutASelectionCallback() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("isd", true);
        f.drainWorkers();
        assertTrue(f.canAdd("isd"));
        View oldAction = f.action();
        f.connection.getEditable().replace(0, 3, "xyz");
        Selection.setSelection(f.connection.getEditable(), 3);
        oldAction.performClick();
        f.drainWorkers();
        assertFalse(new PersonalDictionary(context).getWords().contains("isd"));
    }

    @Test public void cursorMoveInvalidatesOldActionBeforeItsNotification() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("isd", true);
        f.drainWorkers();
        assertTrue(f.canAdd("isd"));
        View oldAction = f.action();
        Selection.setSelection(f.connection.getEditable(), 0);
        oldAction.performClick();
        f.drainWorkers();
        assertFalse(new PersonalDictionary(context).getWords().contains("isd"));
    }

    @Test public void returningToAnEarlierCaretWithIdenticalSurroundingTextIsStillAMove() throws Exception {
        StringBuilder repeating = new StringBuilder();
        for (int i = 0; i < 100; i++) repeating.append("isd ");
        String initialText = repeating + " " + repeating;
        int initialCaret = repeating.length();
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true, initialText, initialCaret, false);
        f.type("isd isd", true);
        f.drainWorkers();
        assertTrue(f.canAdd("isd"));
        View oldAction = f.action();
        String before = f.connection.getTextBeforeCursor(256, 0).toString();
        String after = f.connection.getTextAfterCursor(256, 0).toString();
        f.moveSelection(initialCaret + 3, initialCaret + 3);
        assertEquals(before, f.connection.getTextBeforeCursor(256, 0).toString());
        assertEquals(after, f.connection.getTextAfterCursor(256, 0).toString());
        oldAction.performClick();
        f.drainWorkers();
        assertFalse(f.canAdd("isd"));
        assertFalse(new PersonalDictionary(context).getWords().contains("isd"));
    }

    @Test public void changedEditorCannotUseOldActionEvenWithSameTextAndCaret() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("isd", true);
        f.drainWorkers();
        assertTrue(f.canAdd("isd"));
        View oldAction = f.action();
        Editor other = new Editor(new EditText(context));
        other.commitText("isd", 1);
        f.ime.connection = other;
        oldAction.performClick();
        f.drainWorkers();
        assertFalse(new PersonalDictionary(context).getWords().contains("isd"));
    }

    @Test public void noSuggestionsFlagAndProtectedFieldTypesStillExcludeTypingSuggestions() throws Exception {
        int[] protectedTypes = {
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        };
        for (int type : protectedTypes) {
            Fixture f = start(type, true);
            f.type("isd", true);
            f.drainWorkers();
            assertEquals("isd", f.text());
            assertFalse("Add must stay unavailable for input type " + type, f.canAdd("isd"));
            assertEquals("No typing suggestions for input type " + type, 0, f.strip.getCandidateCount());
            if (type == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)) {
                assertEquals(View.VISIBLE, f.strip.getVisibility());
                assertEquals(context.getString(R.string.spelling_suggestions_unavailable_hint),
                        f.strip.getCandidateView(0).getText().toString());
            }
            assertFalse(new PersonalDictionary(context).getWords().contains("isd"));
        }
    }

    @Test public void protectedFieldSelectionCallbacksDoNotReadSurroundingText() {
        int[] protectedTypes = {
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        };
        for (int type : protectedTypes) {
            Fixture f = start(type, true);
            f.connection.getEditable().append("isd");
            Selection.setSelection(f.connection.getEditable(), 3);
            f.connection.beforeReads = 0;
            f.connection.afterReads = 0;
            f.ime.onUpdateSelection(0, 0, 3, 3, -1, -1);
            assertEquals("Before-cursor text requested for input type " + type, 0, f.connection.beforeReads);
            assertEquals("After-cursor text requested for input type " + type, 0, f.connection.afterReads);
        }
    }

    @Test public void disabledSpellingSuggestionsExplainTheSetting() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false);
        f.type("isd", true);
        f.drainWorkers();
        assertEquals("isd", f.text());
        assertEquals(0, f.strip.getCandidateCount());
        assertEquals(context.getString(R.string.spelling_suggestions_disabled_hint),
                f.strip.getCandidateView(0).getText().toString());
        assertFalse(f.canAdd("isd"));
    }

    private Fixture start(int inputType, boolean suggestions) {
        return start(inputType, suggestions, "");
    }

    private Fixture start(int inputType, boolean suggestions, String initialText) {
        return start(inputType, suggestions, initialText, initialText.length(), true);
    }

    private Fixture start(int inputType, boolean suggestions, String initialText,
                          int initialCaret, boolean autoCorrection) {
        if (fixture != null) fixture.service.destroy();
        fixture = new Fixture(context, inputType, suggestions, initialText, initialCaret, autoCorrection);
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
        final KeyboardLayoutView keyboard;
        final SuggestionStripView strip;
        long time = 100;

        Fixture(Context context, int inputType, boolean suggestions, String initialText,
                int initialCaret, boolean autoCorrection) {
            this.context = context;
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                    .putBoolean("swipe_typing", true).putBoolean("auto_correction", autoCorrection)
                    .putBoolean("spelling_suggestions", suggestions)
                    .putBoolean("vibrate", false).putBoolean("sound", false)
                    .putBoolean("notification", false).putBoolean("navbar", false)
                    .putString("layout", "0").commit();
            service = Robolectric.buildService(TestIme.class).create();
            ime = service.get();
            connection = new Editor(new EditText(context));
            connection.commitText(initialText, 1);
            Selection.setSelection(connection.getEditable(), initialCaret);
            ime.connection = connection;
            EditorInfo info = new EditorInfo();
            info.inputType = inputType;
            info.initialSelStart = initialCaret;
            info.initialSelEnd = initialCaret;
            ime.onStartInput(info, false);
            LinearLayout root = (LinearLayout) ime.onCreateInputView();
            strip = (SuggestionStripView) root.getChildAt(0);
            keyboard = (KeyboardLayoutView) root.getChildAt(1);
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
            ime.onUpdateSelection(-1, -1, initialCaret, initialCaret, -1, -1);
        }

        void type(String value, boolean notifyEachKey) throws Exception {
            for (char c : value.toCharArray()) tap(c, notifyEachKey);
        }

        void tap(int code, boolean notify) throws Exception {
            int oldStart = Selection.getSelectionStart(connection.getEditable());
            int oldEnd = Selection.getSelectionEnd(connection.getEditable());
            View key = key(code);
            long down = time;
            send(MotionEvent.ACTION_DOWN, down, time, key);
            send(MotionEvent.ACTION_UP, down, time += 20, key);
            time += 100;
            if (notify) ime.onUpdateSelection(oldStart, oldEnd,
                    Selection.getSelectionStart(connection.getEditable()),
                    Selection.getSelectionEnd(connection.getEditable()), -1, -1);
        }

        void moveSelection(int start, int end) {
            int oldStart = Selection.getSelectionStart(connection.getEditable());
            int oldEnd = Selection.getSelectionEnd(connection.getEditable());
            Selection.setSelection(connection.getEditable(), start, end);
            ime.onUpdateSelection(oldStart, oldEnd, start, end, -1, -1);
        }

        private View key(int code) throws Exception {
            Field field = KeyboardButtonView.class.getDeclaredField("key");
            field.setAccessible(true);
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                Key key = (Key) field.get(view);
                if (key.info.code == code) return view;
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
        String text() { return connection.getEditable().toString(); }

        boolean canAdd(String word) {
            View action = action();
            return strip.getVisibility() == View.VISIBLE && action != null &&
                    action.getVisibility() == View.VISIBLE && action.isEnabled() && action.isClickable() &&
                    context.getString(R.string.inline_learning_add_word).contentEquals(actionLabel().getText()) &&
                    word.contentEquals(actionWord().getText());
        }

        boolean hasCandidate(String word) {
            for (int i = 0; i < strip.getCandidateCount(); i++) {
                TextView view = strip.getCandidateView(i);
                if (view != null && view.isClickable() && word.contentEquals(view.getText())) return true;
            }
            return false;
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
        boolean beforeUnavailable;
        boolean afterUnavailable;
        int beforeReads;
        int afterReads;
        Editor(EditText view) {
            super(view, true);
            this.view = view;
            Selection.setSelection(view.getText(), 0);
        }
        @Override public Editable getEditable() { return view.getText(); }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            return null;
        }
        @Override public CharSequence getTextBeforeCursor(int length, int flags) {
            beforeReads++;
            return beforeUnavailable ? null : super.getTextBeforeCursor(length, flags);
        }
        @Override public CharSequence getTextAfterCursor(int length, int flags) {
            afterReads++;
            return afterUnavailable ? null : super.getTextAfterCursor(length, flags);
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
