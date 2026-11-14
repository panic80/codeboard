package com.gazlaws.codeboard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Looper;
import android.speech.RecognizerIntent;
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
import android.widget.GridView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.gazlaws.codeboard.gesture.PersonalDictionary;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises new keyboard features through the IME, actual keyboard views and an editable. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class CodeBoardIMEKeyboardFeaturesTest {
    private static final String EMAIL = "learner80+tag@example.com";
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

    @Test public void emailIsLearnedAndCompletedAcrossTheRealSymbolPage() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false, false, false, true);
        f.typeEmail();
        assertEquals(EMAIL, f.text());
        f.await(() -> f.canAdd(EMAIL));
        assertTrue(f.learnAction().performClick());
        assertTrue(new PersonalDictionary(context).getEmailAddresses().contains(EMAIL));
        f.tap(' ');
        f.type("learn");
        f.await(() -> f.hasCandidate(EMAIL));
        assertTrue(f.candidate(EMAIL).performClick());
        assertEquals(EMAIL + " " + EMAIL, f.text());
    }

    @Test public void emailFieldOffersLiteralsButNeverCorrectsOrdinaryWords() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                true, false, false, true);
        f.type("wih ");
        f.drainWorkers();
        assertEquals("wih ", f.text());
        assertFalse(f.hasCandidate("with"));
        f.typeEmail();
        f.await(() -> f.canAdd(EMAIL));
        assertTrue(f.learnAction().performClick());
        f.tap(' ');
        f.type("learner80");
        f.await(() -> f.hasCandidate(EMAIL));
        assertTrue(f.candidate(EMAIL).performClick());
        assertEquals("wih " + EMAIL + " " + EMAIL, f.text());
    }

    @Test public void noSuggestionsAndPasswordDoNotExposeSavedEmails() throws Exception {
        assertTrue(new PersonalDictionary(context).addWord(EMAIL));
        int[] types = {
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        };
        for (int type : types) {
            Fixture f = start(type, true, true, true, true);
            f.type("learn");
            f.drainWorkers();
            assertEquals("learn", f.text());
            assertFalse(f.hasCandidate(EMAIL));
            assertFalse(f.canAdd("learn"));
        }
    }

    @Test public void strongestCorrectionUsesTopSuggestionAndSupportsUndo() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true, false, false, true);
        f.type("wih ");
        f.await(() -> f.text().equals("with "));
        f.tap(-5);
        assertEquals("wih ", f.text());
        f.type("hellp ");
        f.await(() -> f.text().equals("wih hello "));
        f.tap(-5);
        assertEquals("wih hellp ", f.text());
    }

    @Test public void conservativeModeKeepsAmbiguousSpelling() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false, false, false, true);
        f.type("hellp ");
        f.drainWorkers();
        assertEquals("hellp ", f.text());
        assertTrue(f.hasCandidate("hello"));
    }

    @Test public void automaticCapitalizationAndDoubleSpaceMakeSentences() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false, true, true, true);
        f.type("hello  world");
        f.drainWorkers();
        assertEquals("Hello. World", f.text());
    }

    @Test public void quickDoubleSpaceAfterCorrectionKeepsFollowingInput() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, true, false, true, true);
        f.type("wih  next");
        f.drainWorkers();
        assertEquals("with. next", f.text());
    }

    @Test public void typingConveniencesWorkWithoutSpellingOrAutocorrection() throws Exception {
        start(InputType.TYPE_CLASS_TEXT, false, true, true, true);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("auto_correction", false).putBoolean("spelling_suggestions", false).commit();
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false, true, true, false);
        f.type("hello  world");
        f.drainWorkers();
        assertEquals("Hello. World", f.text());
    }

    @Test public void doubleSpacePreservesCodeEmailAndNoSuggestionFields() throws Exception {
        Fixture code = start(InputType.TYPE_CLASS_TEXT, false, false, true, true);
        code.type("myVariable  ");
        code.drainWorkers();
        assertEquals("myVariable  ", code.text());

        Fixture email = start(InputType.TYPE_CLASS_TEXT, false, false, true, true);
        email.typeEmail();
        email.type("  ");
        email.drainWorkers();
        assertEquals(EMAIL + "  ", email.text());

        Fixture noSuggestions = start(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false, true, true, true);
        noSuggestions.type("hello  world");
        noSuggestions.drainWorkers();
        assertEquals("hello  world", noSuggestions.text());
    }

    @Test public void toolsOpenEmojiPickerAndDeleteAnEntireEmoji() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false, false, false, true);
        f.tap(Definitions.CODE_TOOLS);
        f.refresh();
        View emoji = f.root.findViewById(R.id.keyboard_tools_emoji);
        assertNotNull(emoji);
        assertTrue(emoji.performClick());
        f.refresh();
        GridView grid = f.root.findViewById(R.id.emoji_grid);
        assertNotNull(grid);
        View cell = grid.getAdapter().getView(0, null, grid);
        assertTrue(grid.performItemClick(cell, 0, 0));
        assertEquals("😀", f.text());
        assertTrue(f.root.findViewById(R.id.emoji_backspace).performClick());
        assertEquals("", f.text());
        assertTrue(f.root.findViewById(R.id.emoji_category_flags).performClick());
        View flag = grid.getAdapter().getView(0, null, grid);
        assertTrue(grid.performItemClick(flag, 0, 0));
        assertEquals("🇨🇦", f.text());
        assertTrue(f.root.findViewById(R.id.emoji_backspace).performClick());
        assertEquals("", f.text());
        assertTrue(f.root.findViewById(R.id.emoji_category_hearts).performClick());
        View heart = grid.getAdapter().getView(0, null, grid);
        assertTrue(grid.performItemClick(heart, 0, 0));
        assertEquals("❤️", f.text());
        assertTrue(f.root.findViewById(R.id.emoji_backspace).performClick());
        assertEquals("", f.text());
        assertTrue(f.root.findViewById(R.id.emoji_category_flags).performClick());
        assertTrue(grid.performItemClick(grid.getAdapter().getView(0, null, grid), 0, 0));
        assertTrue(f.root.findViewById(R.id.emoji_category_hearts).performClick());
        assertTrue(grid.performItemClick(grid.getAdapter().getView(0, null, grid), 0, 0));
        assertEquals("🇨🇦❤️", f.text());
        assertTrue(f.root.findViewById(R.id.emoji_done).performClick());
        f.refresh();
        f.tap(-5);
        assertEquals("🇨🇦", f.text());
        f.tap(-5);
        assertEquals("", f.text());
        f.tap('a');
        assertEquals("a", f.text());
    }

    @Test public void voiceResultSurvivesReturningToTheSameEditor() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false, false, false, true);
        String request = f.launchVoice();
        f.ime.onWindowHidden();
        f.ime.onFinishInput();
        f.ime.onStartInput(f.info, false);
        f.deliverVoice(request, "dictated text");
        assertEquals("dictated text", f.text());
        f.deliverVoice(request, "stale duplicate");
        assertEquals("dictated text", f.text());
    }

    @Test public void voiceResultCannotInsertIntoAnotherEditor() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false, false, false, true);
        String request = f.launchVoice();
        f.ime.onWindowHidden();
        f.ime.onFinishInput();
        Editor other = new Editor(new EditText(context));
        f.ime.connection = other;
        EditorInfo changed = new EditorInfo();
        changed.inputType = InputType.TYPE_CLASS_TEXT;
        changed.packageName = context.getPackageName();
        changed.fieldId = f.info.fieldId + 1;
        changed.initialSelStart = changed.initialSelEnd = 0;
        f.ime.onStartInput(changed, false);
        f.deliverVoice(request, "must not appear");
        assertEquals("", f.text());
        assertEquals("", other.getEditable().toString());
    }

    @Test public void resizeChangesLiveGeometryAndPersistsPerOrientation() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false, false, false, true);
        f.tap(Definitions.CODE_TOOLS);
        f.refresh();
        assertTrue(f.root.findViewById(R.id.keyboard_tools_resize).performClick());
        f.refresh();
        SeekBar height = f.root.findViewById(R.id.keyboard_resize_height_slider);
        SeekBar width = f.root.findViewById(R.id.keyboard_resize_width_slider);
        assertNotNull(height);
        assertNotNull(width);
        int oldHeight = f.keyboard.getHeight();
        height.setProgress(55);
        width.setProgress(80);
        f.layout();
        assertEquals(800, f.keyboard.getWidth());
        assertTrue(f.keyboard.getHeight() > oldHeight);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        assertEquals("55", prefs.getString("size_portrait", null));
        assertEquals("80", prefs.getString("width_portrait", null));
        assertTrue(f.root.findViewById(R.id.keyboard_resize_done_button).performClick());

        RuntimeEnvironment.setQualifiers("+land");
        Fixture landscape = start(InputType.TYPE_CLASS_TEXT, false, false, false, false);
        assertEquals(1000, landscape.keyboard.getWidth());
        landscape.tap(Definitions.CODE_TOOLS);
        landscape.refresh();
        assertTrue(landscape.root.findViewById(R.id.keyboard_tools_resize).performClick());
        landscape.refresh();
        ((SeekBar) landscape.root.findViewById(R.id.keyboard_resize_height_slider)).setProgress(60);
        ((SeekBar) landscape.root.findViewById(R.id.keyboard_resize_width_slider)).setProgress(70);
        landscape.layout();
        assertEquals(700, landscape.keyboard.getWidth());
        assertEquals("60", prefs.getString("size_landscape", null));
        assertEquals("70", prefs.getString("width_landscape", null));
        assertEquals("55", prefs.getString("size_portrait", null));
        assertEquals("80", prefs.getString("width_portrait", null));
        RuntimeEnvironment.setQualifiers("+port");
        Fixture portraitAgain = start(InputType.TYPE_CLASS_TEXT, false, false, false, false);
        assertEquals(800, portraitAgain.keyboard.getWidth());
    }

    @Test public void shiftedSpaceDragSelectsTextWithoutInsertingSpace() throws Exception {
        Fixture f = start(InputType.TYPE_CLASS_TEXT, false, false, false, true);
        f.ime.onText("abcd");
        f.tap(16);
        f.dragSpace(-2);
        assertEquals("abcd", f.text());
        assertEquals(2, Math.min(Selection.getSelectionStart(f.connection.getEditable()),
                Selection.getSelectionEnd(f.connection.getEditable())));
        assertEquals(4, Math.max(Selection.getSelectionStart(f.connection.getEditable()),
                Selection.getSelectionEnd(f.connection.getEditable())));
        assertEquals(2, f.connection.shiftedLeftEvents());
        f.tap('x');
        assertEquals("abx", f.text());
    }

    private Fixture start(int inputType, boolean top, boolean caps, boolean doubleSpace, boolean resetPrefs) {
        if (fixture != null) fixture.service.destroy();
        fixture = new Fixture(context, inputType, top, caps, doubleSpace, resetPrefs);
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
        final EditorInfo info;
        ResizableInputView root;
        KeyboardLayoutView keyboard;
        SuggestionStripView strip;
        long time = 100;

        Fixture(Context context, int inputType, boolean top, boolean caps, boolean doubleSpace, boolean reset) {
            this.context = context;
            if (reset) PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                    .putBoolean("swipe_typing", true).putBoolean("auto_correction", true)
                    .putBoolean("spelling_suggestions", true).putBoolean("use_top_suggestion", top)
                    .putBoolean("auto_capitalization", caps).putBoolean("double_space_period", doubleSpace)
                    .putBoolean("cursor_control", true).putBoolean("vibrate", false)
                    .putBoolean("sound", false).putBoolean("notification", false)
                    .putBoolean("navbar", false).putString("layout", "0").commit();
            service = Robolectric.buildService(TestIme.class).create();
            ime = service.get();
            connection = new Editor(new EditText(context));
            ime.connection = connection;
            info = new EditorInfo();
            info.inputType = inputType;
            info.packageName = context.getPackageName();
            info.fieldId = 42;
            info.initialSelStart = info.initialSelEnd = 0;
            ime.onStartInput(info, false);
            root = (ResizableInputView) ime.onCreateInputView();
            try { refresh(); } catch (Exception e) { throw new AssertionError(e); }
        }

        void refresh() throws Exception {
            Field kb = CodeBoardIME.class.getDeclaredField("mCurrentKeyboardLayoutView");
            kb.setAccessible(true);
            keyboard = (KeyboardLayoutView) kb.get(ime);
            Field container = CodeBoardIME.class.getDeclaredField("inputContainer");
            container.setAccessible(true);
            root = (ResizableInputView) container.get(ime);
            Field suggestions = CodeBoardIME.class.getDeclaredField("swipeSuggestions");
            suggestions.setAccessible(true);
            strip = (SuggestionStripView) suggestions.get(ime);
            layout();
        }

        void layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        }

        void type(String value) throws Exception {
            for (char c : value.toCharArray()) {
                if (c >= 'A' && c <= 'Z') { tap(16); tap(Character.toLowerCase(c)); }
                else tap(c);
            }
        }

        String launchVoice() throws Exception {
            ResolveInfo recognizer = new ResolveInfo();
            recognizer.activityInfo = new ActivityInfo();
            recognizer.activityInfo.packageName = "test.recognizer";
            recognizer.activityInfo.name = "test.recognizer.Recognizer";
            recognizer.activityInfo.exported = true;
            shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                    new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), recognizer);
            tap(Definitions.CODE_TOOLS);
            refresh();
            View voice = root.findViewById(R.id.keyboard_tools_voice);
            assertNotNull(voice);
            assertTrue(voice.performClick());
            Intent launched = shadowOf((Application) context).getNextStartedActivity();
            assertNotNull("Voice activity was not launched", launched);
            assertEquals(VoiceInputActivity.class.getName(), launched.getComponent().getClassName());
            String request = launched.getStringExtra(VoiceInputActivity.EXTRA_REQUEST_ID);
            assertNotNull(request);
            return request;
        }

        void deliverVoice(String request, String text) {
            Intent result = new Intent(VoiceInputActivity.ACTION_VOICE_RESULT)
                    .setPackage(context.getPackageName())
                    .putExtra(VoiceInputActivity.EXTRA_REQUEST_ID, request)
                    .putExtra(VoiceInputActivity.EXTRA_TEXT, text);
            context.sendBroadcast(result);
            shadowOf(Looper.getMainLooper()).idle();
        }

        void typeEmail() throws Exception {
            type("learner80");
            tap(-1); refresh();
            tap('+');
            tap(-1); refresh();
            type("tag");
            tap(-1); refresh();
            tap('@');
            tap(-1); refresh();
            type("example.com");
        }

        void tap(int code) throws Exception {
            View key = findKey(code);
            long down = time;
            send(MotionEvent.ACTION_DOWN, down, time, centerX(key), centerY(key));
            send(MotionEvent.ACTION_UP, down, time += 20, centerX(key), centerY(key));
            time += 100;
        }

        void dragSpace(int steps) throws Exception {
            View key = findKey(' ');
            long down = time;
            float x = centerX(key), y = centerY(key);
            float displacement = steps * Math.max(1f, 20f * context.getResources().getDisplayMetrics().density);
            send(MotionEvent.ACTION_DOWN, down, time, x, y);
            send(MotionEvent.ACTION_MOVE, down, time += 20, x + displacement, y);
            send(MotionEvent.ACTION_UP, down, time += 20, x + displacement, y);
            time += 100;
        }

        private View findKey(int code) throws Exception {
            Field field = KeyboardButtonView.class.getDeclaredField("key");
            field.setAccessible(true);
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                if (((Key) field.get(view)).info.code == code) return view;
            }
            throw new AssertionError("Missing keyboard key: " + code);
        }

        private void send(int action, long down, long when, float x, float y) {
            MotionEvent event = MotionEvent.obtain(down, when, action, x, y, 0);
            assertTrue("touch not handled", keyboard.dispatchTouchEvent(event));
            event.recycle();
        }

        private static float centerX(View view) { return (view.getLeft() + view.getRight()) / 2f; }
        private static float centerY(View view) { return (view.getTop() + view.getBottom()) / 2f; }
        View learnAction() { return strip == null ? null : strip.findViewById(R.id.inline_learning_action); }
        boolean canAdd(String word) {
            View action = learnAction();
            TextView label = strip == null ? null : strip.findViewById(R.id.inline_learning_label);
            TextView displayed = strip == null ? null : strip.findViewById(R.id.inline_learning_word);
            return strip != null && strip.getVisibility() == View.VISIBLE && action != null
                    && action.getVisibility() == View.VISIBLE && action.isClickable()
                    && label != null && context.getString(R.string.inline_learning_add_word).contentEquals(label.getText())
                    && displayed != null && word.contentEquals(displayed.getText());
        }
        TextView candidate(String value) {
            if (strip == null) return null;
            for (int i = 0; i < strip.getCandidateCount(); i++) {
                TextView view = strip.getCandidateView(i);
                if (view != null && view.isClickable() && value.contentEquals(view.getText())) return view;
            }
            return null;
        }
        boolean hasCandidate(String value) { return candidate(value) != null; }
        String text() { return connection.getEditable().toString(); }

        void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + 12_000_000_000L;
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle();
                if (condition.getAsBoolean()) return;
                Thread.sleep(10);
            }
            fail("Timed out, text=" + text());
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
        final List<KeyEvent> events = new ArrayList<>();
        Editor(EditText view) { super(view, true); this.view = view; Selection.setSelection(view.getText(), 0); }
        @Override public Editable getEditable() { return view.getText(); }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText result = new ExtractedText();
            result.selectionStart = Selection.getSelectionStart(getEditable());
            result.selectionEnd = Selection.getSelectionEnd(getEditable());
            return result;
        }
        @Override public boolean sendKeyEvent(KeyEvent event) {
            events.add(event);
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            int code = event.getKeyCode();
            Editable text = getEditable();
            int start = Selection.getSelectionStart(text), end = Selection.getSelectionEnd(text);
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0)
                    Selection.extendSelection(text, Math.max(0, end - 1));
                else Selection.setSelection(text, Math.max(0, Math.min(start, end) - 1));
            } else if (code == KeyEvent.KEYCODE_DEL && start == end && start > 0) {
                text.delete(start - 1, start);
            } else if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z
                    && (event.getMetaState() & KeyEvent.META_CTRL_ON) == 0) {
                char c = (char) ('a' + code - KeyEvent.KEYCODE_A);
                if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) c = Character.toUpperCase(c);
                commitText(String.valueOf(c), 1);
            }
            return true;
        }
        int shiftedLeftEvents() {
            int count = 0;
            for (KeyEvent event : events) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                        && (event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) count++;
            }
            return count;
        }
    }
}
