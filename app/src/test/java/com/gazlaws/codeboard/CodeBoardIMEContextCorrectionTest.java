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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Regression coverage through the keyboard, editor and the bundled English assets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class CodeBoardIMEContextCorrectionTest {
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

    @Test public void reportedMisspellingCorrectsOnSpace() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("autocrrect ");
        f.await(() -> f.text().equals("autocorrect "));
        f.drainWorkers();
        assertEquals("autocorrect ", f.text());
    }

    @Test public void reportedPhraseCorrectsAndBackspaceRestoresTheTypedWord() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        // Type continuously so that following input can arrive while a boundary is resolving.
        f.type("autocrrect is bot ");
        f.await(() -> f.text().equals("autocorrect is not "));
        f.drainWorkers();
        assertEquals("autocorrect is not ", f.text());
        f.tap(-5);
        assertEquals("autocorrect is bot ", f.text());
        f.tap(-5);
        assertEquals("autocorrect is bot", f.text());
    }

    @Test public void reportedPhrasePreservesInitialCapitalization() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("Autocrrect is bot ");
        f.await(() -> f.text().equals("Autocorrect is not "));
        f.drainWorkers();
        assertEquals("Autocorrect is not ", f.text());
    }

    @Test public void conservativeSettingDoesNotReplaceAKnownWordFromContext() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false);
        f.type("is bot ");
        f.drainWorkers();
        assertEquals("is bot ", f.text());
    }

    @Test public void strongerSettingKeepsOrdinaryUsesOfKnownWords() throws Exception {
        for (String phrase : new String[]{"the bot ", "chat bot ", "is hot ",
                "to eat ", "to beat ", "to heat ", "I want too much ", "on them ",
                "you gave ", "to bed ", "to hell ", "by then "}) {
            Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
            f.type(phrase);
            f.drainWorkers();
            assertEquals(phrase, f.text());
        }
    }

    @Test public void commonKnownWordCorrectsAndBackspaceRestoresTheTypedWord() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("in they ");
        f.drainWorkers();
        assertEquals("in the ", f.text());
        f.tap(-5);
        assertEquals("in they ", f.text());
        f.tap(-5);
        assertEquals("in they", f.text());
    }

    @Test public void continuousTypingAfterKnownWordCorrectionKeepsInputInOrder() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        // These key events arrive while preceding space boundaries may still be resolving.
        f.type("in they box ");
        f.drainWorkers();
        assertEquals("in the box ", f.text());
    }

    @Test public void uncertainKnownWordOffersManualAlternativeWithoutReplacingIt() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        // The bundled context has only weak evidence for "will meet".
        f.type("I will meat ");
        f.drainWorkers();
        assertEquals("I will meat ", f.text());
        TextView alternative = f.candidate("meet");
        assertNotNull("meet should be available as a manual alternative", alternative);
        assertTrue(alternative.performClick());
        assertEquals("I will meet ", f.text());
    }

    @Test public void conservativeSettingKeepsCommonKnownWordButOffersAlternatives() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false);
        f.type("in they ");
        f.drainWorkers();
        assertEquals("in they ", f.text());
        TextView alternative = f.candidate("the");
        assertNotNull("the should remain selectable", alternative);
        assertTrue(alternative.performClick());
        assertEquals("in the ", f.text());
    }

    @Test public void automaticCorrectionOffKeepsKnownWordAndAllowsManualAlternatives() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true, false);
        f.type("in they ");
        f.drainWorkers();
        assertEquals("in they ", f.text());
        TextView alternative = f.candidate("the");
        assertNotNull("the should remain selectable", alternative);
        assertTrue(alternative.performClick());
        assertEquals("in the ", f.text());
    }

    @Test public void savedCommonWordIsNotContextuallyReplaced() throws Exception {
        assertTrue(new PersonalDictionary(context).addWord("they"));
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("in they ");
        f.drainWorkers();
        assertEquals("in they ", f.text());
    }

    @Test public void explicitlyChosenCommonWordIsNotContextuallyReplaced() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("in they");
        f.await(() -> f.text().equals("in they") && f.candidate("they") != null);
        assertTrue(f.candidate("they").performClick());
        f.tap(' ');
        f.drainWorkers();
        assertEquals("in they ", f.text());
    }

    @Test public void savedPersonalWordIsNotContextuallyReplaced() throws Exception {
        assertTrue(new PersonalDictionary(context).addWord("bot"));
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("is bot ");
        f.drainWorkers();
        assertEquals("is bot ", f.text());
    }

    @Test public void explicitlyChosenTypedWordIsNotContextuallyReplaced() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("is bot");
        f.await(() -> f.text().equals("is bot") && f.candidate("bot") != null);
        assertTrue(f.candidate("bot").performClick());
        f.tap(' ');
        f.drainWorkers();
        assertEquals("is bot ", f.text());
    }

    @Test public void codeIdentifiersRemainLiteral() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true);
        f.type("autocrrectValue isBot ");
        f.drainWorkers();
        assertEquals("autocrrectValue isBot ", f.text());
    }

    @Test public void emailUriPasswordAndNoSuggestionFieldsStayLiteral() throws Exception {
        int[] inputTypes = {
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        };
        for (int inputType : inputTypes) {
            Fixture f = start(inputType, true);
            f.type("autocrrect is bot ");
            f.drainWorkers();
            assertEquals("Input type " + inputType, "autocrrect is bot ", f.text());
        }
    }

    private Fixture start(int inputType, boolean strongerCorrection) {
        return start(inputType, strongerCorrection, true);
    }

    private Fixture start(int inputType, boolean strongerCorrection, boolean autoCorrection) {
        if (fixture != null) fixture.service.destroy();
        fixture = new Fixture(context, inputType, strongerCorrection, autoCorrection);
        return fixture;
    }

    public static class TestIme extends CodeBoardIME {
        InputConnection connection;
        @Override public InputConnection getCurrentInputConnection() { return connection; }
    }

    private static final class Fixture {
        final ServiceController<TestIme> service;
        final TestIme ime;
        final Editor connection;
        final KeyboardLayoutView keyboard;
        final SuggestionStripView strip;
        long time = 100;

        Fixture(Context context, int inputType, boolean strongerCorrection, boolean autoCorrection) {
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                    .putBoolean("swipe_typing", true).putBoolean("auto_correction", autoCorrection)
                    .putBoolean("spelling_suggestions", true)
                    .putBoolean("use_top_suggestion", strongerCorrection)
                    .putBoolean("auto_capitalization", false).putBoolean("double_space_period", false)
                    .putBoolean("vibrate", false).putBoolean("sound", false)
                    .putBoolean("notification", false).putBoolean("navbar", false)
                    .putString("layout", "0").commit();
            service = Robolectric.buildService(TestIme.class).create();
            ime = service.get();
            connection = new Editor(new EditText(context));
            ime.connection = connection;
            EditorInfo info = new EditorInfo();
            info.inputType = inputType;
            info.packageName = context.getPackageName();
            info.fieldId = 42;
            info.initialSelStart = info.initialSelEnd = 0;
            ime.onStartInput(info, false);
            ResizableInputView root = (ResizableInputView) ime.onCreateInputView();
            try {
                keyboard = (KeyboardLayoutView) getField(ime, "mCurrentKeyboardLayoutView");
                strip = (SuggestionStripView) getField(ime, "swipeSuggestions");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
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

        void tap(int code) throws Exception {
            Field field = KeyboardButtonView.class.getDeclaredField("key");
            field.setAccessible(true);
            View target = null;
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                if (((Key) field.get(view)).info.code == code) { target = view; break; }
            }
            assertNotNull("Missing keyboard key: " + code, target);
            long down = time;
            send(MotionEvent.ACTION_DOWN, down, time, target);
            send(MotionEvent.ACTION_UP, down, time += 20, target);
            time += 100;
        }

        private void send(int action, long down, long when, View target) {
            MotionEvent event = MotionEvent.obtain(down, when, action,
                    (target.getLeft() + target.getRight()) / 2f,
                    (target.getTop() + target.getBottom()) / 2f, 0);
            assertTrue("touch not handled", keyboard.dispatchTouchEvent(event));
            event.recycle();
        }

        String text() { return connection.getEditable().toString(); }

        TextView candidate(String value) {
            if (strip == null) return null;
            for (int i = 0; i < strip.getCandidateCount(); i++) {
                TextView item = strip.getCandidateView(i);
                if (item != null && item.isClickable() && value.contentEquals(item.getText())) return item;
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
            // Each boundary can enqueue the next deferred keyboard event on the main thread.
            for (int i = 0; i < 6; i++) {
                shadowOf(Looper.getMainLooper()).idle();
                drain("autoCorrectController");
                drain("swipeTypingController");
            }
            shadowOf(Looper.getMainLooper()).idle();
        }

        private void drain(String name) throws Exception {
            Object controller = getField(ime, name);
            if (controller == null) return;
            Field worker = controller.getClass().getDeclaredField("worker");
            worker.setAccessible(true);
            ((ExecutorService) worker.get(controller)).submit(() -> { }).get(10, TimeUnit.SECONDS);
        }

        private static Object getField(CodeBoardIME ime, String name) throws Exception {
            Field field = CodeBoardIME.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(ime);
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
