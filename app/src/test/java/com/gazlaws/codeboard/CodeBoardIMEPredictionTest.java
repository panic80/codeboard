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
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.gazlaws.codeboard.gesture.PersonalDictionary;
import com.gazlaws.codeboard.input.KoreanKeyMap;
import com.gazlaws.codeboard.layout.Definitions;
import com.gazlaws.codeboard.layout.Key;
import com.gazlaws.codeboard.layout.ui.KeyboardButtonView;
import com.gazlaws.codeboard.layout.ui.KeyboardLayoutView;
import com.gazlaws.codeboard.layout.ui.ResizableInputView;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Prediction regressions through real keyboard events, editors and bundled dictionaries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class CodeBoardIMEPredictionTest {
    private Context context;
    private Fixture fixture;

    @Before public void setUp() {
        RuntimeEnvironment.setQualifiers("+port");
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit().clear().commit();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @After public void tearDown() {
        if (fixture != null) fixture.service.destroy();
        RuntimeEnvironment.setQualifiers("+port");
    }

    @Test public void completionRequiresAnExplicitTapInBothEditors() throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Fixture f = start(fallback, InputType.TYPE_CLASS_TEXT, false, true);
            // Four missing letters put this beyond the normal spelling search.
            f.type("autocor");
            f.await(() -> f.candidate("autocorrect") != null);
            assertEquals("autocor", f.text());
            assertTrue(f.candidate("autocorrect").performClick());
            assertEquals("autocorrect", f.text());
            f.tap(' ');
            f.drainWorkers();
            assertEquals("autocorrect ", f.text());
        }
    }

    @Test public void spaceDoesNotAcceptAnOfferedCompletion() throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Fixture f = start(fallback, InputType.TYPE_CLASS_TEXT, false, true);
            f.type("hel");
            f.await(() -> f.candidate("hello") != null);
            f.tap(' ');
            f.drainWorkers();
            assertEquals("hel ", f.text());

            Fixture incomplete = start(fallback, InputType.TYPE_CLASS_TEXT, false, true);
            incomplete.type("autocor");
            incomplete.await(() -> incomplete.candidate("autocorrect") != null);
            incomplete.tap(' ');
            incomplete.drainWorkers();
            assertNotEquals("Space must not accept the offered completion", "autocorrect ", incomplete.text());
        }
    }

    @Test public void nextWordTapAppendsWithoutReplacingTheCompletedWord() throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Fixture f = start(fallback, InputType.TYPE_CLASS_TEXT, false, true);
            f.type("thank you ");
            f.await(() -> f.candidate("→ for") != null);
            assertEquals("thank you ", f.text());
            assertNotNull("The completed word must remain selectable", f.candidate("you"));
            assertTrue(f.candidate("→ for").performClick());
            assertEquals("thank you for ", f.text());
        }
    }

    @Test public void predictedSpaceDoesNotTriggerDoubleSpacePeriod() throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Fixture f = start(fallback, InputType.TYPE_CLASS_TEXT, false, false, true);
            f.type("thank you ");
            f.await(() -> f.candidate("→ for") != null);
            assertTrue(f.candidate("→ for").performClick());
            assertEquals("thank you for ", f.text());
            f.tap(' ');
            f.drainWorkers();
            assertEquals("A prediction's inserted space is not a physical Space", "thank you for  ", f.text());

            Fixture physical = start(fallback, InputType.TYPE_CLASS_TEXT, false, false, true);
            physical.type("thank  ");
            physical.drainWorkers();
            assertEquals("Two physical Spaces still insert a period", "thank. ", physical.text());
        }
    }

    @Test public void predictionKeepsCorrectionUndoAndAddWordAvailable() throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Fixture f = start(fallback, InputType.TYPE_CLASS_TEXT, false, true);
            f.type("in they ");
            f.await(() -> "in the ".equals(f.text()) && f.nextWordCandidate() != null);
            assertNotNull("Original word remains available as a correction", f.candidate("they"));
            assertTrue("Add word remains available beside predictions", f.canAdd("they"));
            f.tap(-5);
            assertEquals("in they ", f.text());
            assertTrue(f.canAdd("they"));
            assertTrue(f.action().performClick());
            assertTrue(new PersonalDictionary(context).getWords().contains("they"));
            // Isolate the next pass from both explicit dictionary edits and the learned rejection.
            new com.gazlaws.codeboard.gesture.AdaptiveTypingModel(context).clear();
            context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test public void stalePredictionTapCannotEditAfterTypingOrAnUnreportedCursorMove() throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Fixture f = start(fallback, InputType.TYPE_CLASS_TEXT, false, true);
            f.type("thank you ");
            Fixture typed = f;
            typed.await(() -> typed.candidate("→ for") != null);
            TextView beforeTyping = f.candidate("→ for");
            f.type("tom");
            assertEquals("thank you tom", f.text());
            beforeTyping.performClick();
            f.drainWorkers();
            assertEquals("thank you tom", f.text());
            assertNotNull(f.candidate("tom"));

            f = start(fallback, InputType.TYPE_CLASS_TEXT, false, true);
            f.type("thank you ");
            Fixture current = f;
            current.await(() -> current.candidate("→ for") != null);
            TextView beforeMove = current.candidate("→ for");
            // Editors can move the caret before delivering onUpdateSelection to the IME.
            Selection.setSelection(current.connection.getEditable(), 0);
            beforeMove.performClick();
            current.drainWorkers();
            assertEquals("thank you ", current.text());
            assertEquals(0, Selection.getSelectionStart(current.connection.getEditable()));
        }
    }

    @Test public void delayedWorkerCannotPublishAnObsoleteBoundaryDuringFastTyping() throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Fixture f = start(fallback, InputType.TYPE_CLASS_TEXT, false, false);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            f.worker("autoCorrectController").submit(() -> {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                f.type("thank you tom");
                assertEquals("thank you tom", f.text());
            } finally {
                release.countDown();
            }
            f.drainWorkers();
            assertEquals("thank you tom", f.text());
            assertNotNull("Latest typed word remains visible", f.candidate("tom"));
            assertNull("Old boundary prediction must not reappear", f.candidate("→ for"));
        }
    }

    @Test public void protectedFieldsDoNotOfferEnglishWordPredictions() throws Exception {
        int[] types = {
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        };
        for (boolean fallback : new boolean[]{false, true}) {
            for (int type : types) {
                Fixture f = start(fallback, type, false, true);
                f.type("thank you ");
                f.drainWorkers();
                assertEquals("Input type " + type, "thank you ", f.text());
                assertEquals("Input type " + type, 0, f.strip.getCandidateCount());
            }
        }
    }

    @Test public void switchingToAProtectedEditorRejectsStaleClicksAndPendingResults() throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Fixture f = start(fallback, InputType.TYPE_CLASS_TEXT, false, false);
            f.type("thank you ");
            Fixture normal = f;
            normal.await(() -> normal.candidate("→ for") != null);
            TextView oldPrediction = normal.candidate("→ for");
            normal.enterProtectedEditor(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            oldPrediction.performClick();
            normal.drainWorkers();
            assertEquals("private", normal.text());
            assertEquals(0, normal.strip.getCandidateCount());
            assertEquals(0, normal.connection.beforeReads);
            assertEquals(0, normal.connection.afterReads);

            f = start(fallback, InputType.TYPE_CLASS_TEXT, false, false);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            f.worker("autoCorrectController").submit(() -> {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                f.type("thank you ");
                f.enterProtectedEditor(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            } finally {
                release.countDown();
            }
            f.drainWorkers();
            assertEquals("private", f.text());
            assertEquals(0, f.strip.getCandidateCount());
            assertEquals(0, f.connection.beforeReads);
            assertEquals(0, f.connection.afterReads);
        }
    }

    @Test public void switchingLanguagesInvalidatesOldEnglishPredictions() throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Fixture f = start(fallback, InputType.TYPE_CLASS_TEXT, true, false);
            f.typeKorean("gksrmf");
            f.tap(' ');
            f.drainWorkers();
            assertEquals("한글 ", f.text());
            f.switchLanguage();
            f.type("thank you ");
            f.await(() -> f.candidate("→ for") != null);
            TextView englishPrediction = f.candidate("→ for");
            f.switchLanguage();
            englishPrediction.performClick();
            assertEquals("한글 thank you ", f.text());
            assertNull(f.candidate("→ for"));
            f.typeKorean("gksrmf");
            f.drainWorkers();
            assertEquals("한글 thank you 한글", f.text());
        }
    }

    private Fixture start(boolean fallback, int inputType, boolean korean, boolean autoCorrect) {
        return start(fallback, inputType, korean, autoCorrect, false);
    }

    private Fixture start(boolean fallback, int inputType, boolean korean, boolean autoCorrect,
                          boolean doubleSpace) {
        if (fixture != null) fixture.service.destroy();
        fixture = new Fixture(context, fallback, inputType, korean, autoCorrect, doubleSpace);
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
        Editor connection;
        KeyboardLayoutView keyboard;
        SuggestionStripView strip;
        long time = 100;

        Fixture(Context context, boolean fallback, int inputType, boolean korean, boolean autoCorrect,
                boolean doubleSpace) {
            this.context = context;
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                    .putBoolean("swipe_typing", true).putBoolean("auto_correction", autoCorrect)
                    .putBoolean("spelling_suggestions", true).putBoolean("use_top_suggestion", true)
                    .putBoolean("auto_capitalization", false).putBoolean("double_space_period", doubleSpace)
                    .putBoolean("vibrate", false).putBoolean("sound", false)
                    .putBoolean("notification", false).putBoolean("navbar", false)
                    .putString("layout", korean ? String.valueOf(Definitions.LAYOUT_KOREAN) : "0").commit();
            service = Robolectric.buildService(TestIme.class).create();
            ime = service.get();
            connection = new Editor(new EditText(context), fallback);
            ime.connection = connection;
            EditorInfo info = new EditorInfo();
            info.inputType = inputType;
            info.initialSelStart = info.initialSelEnd = 0;
            ime.onStartInput(info, false);
            ResizableInputView root = (ResizableInputView) ime.onCreateInputView();
            try { refreshViews(); } catch (Exception e) { throw new AssertionError(e); }
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
            ime.onUpdateSelection(-1, -1, 0, 0, -1, -1);
        }

        void refreshViews() throws Exception {
            keyboard = (KeyboardLayoutView) field(ime, "mCurrentKeyboardLayoutView");
            strip = (SuggestionStripView) field(ime, "swipeSuggestions");
        }

        void switchLanguage() throws Exception {
            tap(Definitions.CODE_LANGUAGE_SWITCH);
            refreshViews();
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
            keyboard.layout(0, 0, 1000, 400);
        }

        void enterProtectedEditor(int type) throws Exception {
            connection = new Editor(new EditText(context), connection.fallback);
            connection.commitText("private", 1);
            ime.connection = connection;
            EditorInfo info = new EditorInfo();
            info.inputType = type;
            info.initialSelStart = info.initialSelEnd = "private".length();
            ime.onStartInput(info, false);
            ime.onStartInputView(info, false);
            refreshViews();
            connection.beforeReads = connection.afterReads = 0;
        }

        void type(String value) throws Exception {
            for (char c : value.toCharArray()) tap(c);
        }

        void typeKorean(String keys) throws Exception {
            for (char c : keys.toCharArray()) tap(KoreanKeyMap.keyCodeFor(c));
        }

        void tap(int code) throws Exception {
            int oldStart = Selection.getSelectionStart(connection.getEditable());
            int oldEnd = Selection.getSelectionEnd(connection.getEditable());
            Field keyField = KeyboardButtonView.class.getDeclaredField("key");
            keyField.setAccessible(true);
            View target = null;
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                if (((Key) keyField.get(view)).info.code == code) { target = view; break; }
            }
            assertNotNull("Missing keyboard key: " + code, target);
            long down = time;
            send(MotionEvent.ACTION_DOWN, down, time, target);
            send(MotionEvent.ACTION_UP, down, time += 20, target);
            time += 100;
            Editable text = connection.getEditable();
            ime.onUpdateSelection(oldStart, oldEnd,
                    Selection.getSelectionStart(text), Selection.getSelectionEnd(text),
                    BaseInputConnection.getComposingSpanStart(text),
                    BaseInputConnection.getComposingSpanEnd(text));
        }

        private void send(int action, long down, long when, View target) {
            MotionEvent event = MotionEvent.obtain(down, when, action,
                    (target.getLeft() + target.getRight()) / 2f,
                    (target.getTop() + target.getBottom()) / 2f, 0);
            assertTrue("touch not handled", keyboard.dispatchTouchEvent(event));
            event.recycle();
        }

        String text() { return connection.getEditable().toString(); }
        View action() { return strip.findViewById(R.id.inline_learning_action); }

        boolean canAdd(String word) {
            View action = action();
            TextView label = strip.findViewById(R.id.inline_learning_label);
            TextView target = strip.findViewById(R.id.inline_learning_word);
            return action != null && action.getVisibility() == View.VISIBLE && action.isClickable()
                    && context.getString(R.string.inline_learning_add_word).contentEquals(label.getText())
                    && word.contentEquals(target.getText());
        }

        TextView candidate(String value) {
            for (int i = 0; i < strip.getCandidateCount(); i++) {
                TextView item = strip.getCandidateView(i);
                if (item != null && item.isClickable() && value.contentEquals(item.getText())) return item;
            }
            return null;
        }

        TextView nextWordCandidate() {
            for (int i = 0; i < strip.getCandidateCount(); i++) {
                TextView item = strip.getCandidateView(i);
                if (item != null && item.isClickable() && item.getText().toString().startsWith("→ ")) return item;
            }
            return null;
        }

        void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle();
                if (condition.getAsBoolean()) return;
                Thread.sleep(10);
            }
            fail("Timed out, text=" + text());
        }

        void drainWorkers() throws Exception {
            for (int i = 0; i < 6; i++) {
                shadowOf(Looper.getMainLooper()).idle();
                worker("autoCorrectController").submit(() -> { }).get(10, TimeUnit.SECONDS);
                worker("swipeTypingController").submit(() -> { }).get(10, TimeUnit.SECONDS);
            }
            shadowOf(Looper.getMainLooper()).idle();
        }

        ExecutorService worker(String name) throws Exception {
            Object controller = field(ime, name);
            return (ExecutorService) field(controller, "worker");
        }

        private static Object field(Object owner, String name) throws Exception {
            Field field = owner.getClass() == TestIme.class
                    ? CodeBoardIME.class.getDeclaredField(name) : owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        }
    }

    private static final class Editor extends BaseInputConnection {
        final EditText view;
        final boolean fallback;
        int beforeReads;
        int afterReads;

        Editor(EditText view, boolean fallback) {
            super(view, true);
            this.view = view;
            this.fallback = fallback;
            Selection.setSelection(view.getText(), 0);
        }

        @Override public Editable getEditable() { return view.getText(); }

        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            if (fallback) return null;
            ExtractedText result = new ExtractedText();
            result.selectionStart = Selection.getSelectionStart(getEditable());
            result.selectionEnd = Selection.getSelectionEnd(getEditable());
            return result;
        }

        @Override public CharSequence getTextBeforeCursor(int length, int flags) {
            beforeReads++;
            return super.getTextBeforeCursor(length, flags);
        }

        @Override public CharSequence getTextAfterCursor(int length, int flags) {
            afterReads++;
            return super.getTextAfterCursor(length, flags);
        }

        @Override public boolean sendKeyEvent(KeyEvent event) {
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
    }
}
