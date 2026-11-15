package com.gazlaws.codeboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Exercises ranking through real keyboard touch coordinates, IME context and dictionaries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class CodeBoardIMESwipeRankingTest {
    // Each pair is a displacement from the corresponding key center in key widths/heights.
    private static final float[][] NOISY_THIS = {
            {-.317061f, .267147f}, {-.172868f, .310178f},
            {-.125131f, -.196451f}, {.323769f, -.108337f}
    };
    private static final float[][] ANOTHER_NOISY_THIS = {
            {.106874f, -.022892f}, {.344778f, .331063f},
            {-.169710f, .135389f}, {.316829f, -.157910f}
    };
    private static final float[][] NOISY_SHIRT = {
            {-.0200f, .0710f}, {-.0902f, -.0669f}, {.0239f, .0960f},
            {.1381f, .1067f}, {-.0827f, .1339f}
    };
    private static final float[][] NOISY_STRIKE = {
            {.3242f, -.1099f}, {.4107f, .3171f}, {.1053f, .4355f},
            {-.4188f, .2777f}, {-.3119f, -.4435f}, {.4003f, -.1679f}
    };

    private Fixture fixture;

    @After public void tearDown() {
        if (fixture != null) fixture.service.destroy();
    }

    @Test public void thisGestureAfterTheUsesTheFingerPathOnStandardKeyboard() throws Exception {
        checkThisAfterThe(1000, 400);
    }

    @Test public void thisGestureAfterTheUsesTheFingerPathOnNarrowKeyboard() throws Exception {
        checkThisAfterThe(650, 240);
    }

    @Test public void thisGestureAfterTheUsesTheFingerPathOnWideKeyboard() throws Exception {
        checkThisAfterThe(1400, 300);
    }

    private void checkThisAfterThe(int width, int height) throws Exception {
        Fixture f = start(false, true, width, height);
        f.seed("the ");
        f.swipe("this", NOISY_THIS);
        f.awaitComposition();
        assertEquals("the this", f.text());
        assertEquals("this", f.composingText());
    }

    @Test public void thisGestureStillWorksWhenContextRankingIsDisabled() throws Exception {
        Fixture f = start(false, false, 1000, 400);
        f.seed("the ");
        f.swipe("this", NOISY_THIS);
        f.awaitComposition();
        assertEquals("the this", f.text());
    }

    @Test public void anotherNoisyThisGestureDoesNotBecomeThird() throws Exception {
        Fixture f = start(false, true, 1000, 400);
        f.seed("the ");
        f.swipe("this", ANOTHER_NOISY_THIS);
        f.awaitComposition();
        assertEquals("the this", f.text());
    }

    @Test public void aClearThirdGestureStillWinsAfterThe() throws Exception {
        Fixture f = start(false, true, 1000, 400);
        f.seed("the ");
        f.swipe("third", null);
        f.awaitComposition();
        assertEquals("the third", f.text());
    }

    @Test public void shirtGestureAfterTheDoesNotBecomeShort() throws Exception {
        Fixture f = start(false, true, 1000, 400);
        f.seed("the ");
        f.swipe("shirt", null);
        f.awaitComposition();
        assertEquals("the shirt", f.text());
    }

    @Test public void noisyShirtGestureAfterTheDoesNotBecomeShort() throws Exception {
        for (int[] size : new int[][] {{1000, 400}, {650, 240}}) {
            Fixture f = start(false, true, size[0], size[1]);
            f.seed("the ");
            f.swipe("shirt", NOISY_SHIRT);
            f.awaitComposition();
            assertEquals("Keyboard " + size[0] + "x" + size[1], "the shirt", f.text());
        }
    }

    @Test public void shirtGestureStillWorksWhenContextRankingIsDisabled() throws Exception {
        Fixture f = start(false, false, 1000, 400);
        f.seed("the ");
        f.swipe("shirt", null);
        f.awaitComposition();
        assertEquals("the shirt", f.text());
    }

    @Test public void aClearShortGestureStillWinsAfterThe() throws Exception {
        Fixture f = start(false, true, 1000, 400);
        f.seed("the ");
        f.swipe("short", null);
        f.awaitComposition();
        assertEquals("the short", f.text());
    }

    @Test public void typedThisRemainsThisAfterThe() throws Exception {
        Fixture f = start(false, true, 1000, 400);
        f.seed("the ");
        for (char c : "this ".toCharArray()) f.tap(c);
        f.drainWorkers();
        assertEquals("the this ", f.text());
    }

    @Test public void slowStrikeGestureKeepsItsTurnsAcrossKeyboardSizesAndContexts()
            throws Exception {
        for (int[] size : new int[][] {{650, 240}, {1000, 400}, {1400, 300}}) {
            for (String context : new String[] {"tried ", "a ", "the ", "to "}) {
                // A device can deliver the same samples separately or as MotionEvent history.
                for (boolean batched : new boolean[] {false, true}) {
                    Fixture f = start(false, true, size[0], size[1]);
                    f.seed(context);
                    f.swipeContinuously("strike", batched, false);
                    f.awaitComposition();
                    assertEquals("Keyboard " + size[0] + "x" + size[1]
                                    + ", context=" + context + ", batched=" + batched,
                            context + "strike", f.text());
                }
            }
        }
    }

    @Test public void sitterGestureWithRepeatDwellStillWins() throws Exception {
        for (String context : new String[] {"tried ", "a "}) {
            for (boolean batched : new boolean[] {false, true}) {
                Fixture f = start(false, true, 1000, 400);
                f.seed(context);
                f.swipeContinuously("sitter", batched, true);
                f.awaitComposition();
                assertEquals(context + "sitter", f.text());
            }
        }
    }

    @Test public void noisyStrikeGesturePreservesDirectionAcrossKeyboardSizes() throws Exception {
        // These points lie within the intended keys; v32 returned "sirree" for this path.
        for (int[] size : new int[][] {{650, 240}, {1000, 400}, {1400, 300}}) {
            for (String context : new String[] {"tried ", "a "}) {
                for (boolean batched : new boolean[] {false, true}) {
                    Fixture f = start(false, true, size[0], size[1]);
                    f.seed(context);
                    f.swipeContinuously("strike", NOISY_STRIKE, batched, false);
                    f.awaitComposition();
                    assertEquals("Keyboard " + size[0] + "x" + size[1]
                                    + ", context=" + context + ", batched=" + batched,
                            context + "strike", f.text());
                }
            }
        }
    }

    @Test public void siteGestureStillWins() throws Exception {
        for (String context : new String[] {"tried ", "a "}) {
            Fixture f = start(false, true, 1000, 400);
            f.seed(context);
            f.swipeContinuously("site", true, false);
            f.awaitComposition();
            assertEquals(context + "site", f.text());
        }
    }

    @Test public void typedStrikeRemainsStrikeInNaturalContexts() throws Exception {
        for (String context : new String[] {"tried ", "a ", "the ", "to "}) {
            Fixture f = start(false, true, 1000, 400);
            f.seed(context);
            for (char c : "strike ".toCharArray()) f.tap(c);
            f.drainWorkers();
            assertEquals(context + "strike ", f.text());
        }
    }

    @Test public void shiftStillCapitalizesASwipe() throws Exception {
        Fixture f = start(false, true, 1000, 400);
        f.tap(16);
        f.swipe("this", null);
        f.awaitComposition();
        assertEquals("This", f.text());
    }

    @Test public void koreanSwipeStillUsesTheNarrowTwoSetKeyboard() throws Exception {
        checkKoreanSwipe(650, 240);
    }

    @Test public void koreanSwipeStillUsesTheWideTwoSetKeyboard() throws Exception {
        checkKoreanSwipe(1400, 300);
    }

    private void checkKoreanSwipe(int width, int height) throws Exception {
        Fixture f = start(true, true, width, height);
        f.seed("가 ");
        f.swipe("gksrmf", null);
        f.awaitComposition();
        assertEquals("가 한글", f.text());
    }

    private Fixture start(boolean korean, boolean contextRanking, int width, int height)
            throws Exception {
        if (fixture != null) fixture.service.destroy();
        fixture = new Fixture(korean, contextRanking, width, height);
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

        Fixture(boolean korean, boolean contextRanking, int width, int height) throws Exception {
            Context app = RuntimeEnvironment.getApplication();
            PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
                    .putBoolean("swipe_typing", true).putBoolean("auto_correction", true)
                    .putBoolean("spelling_suggestions", true)
                    .putBoolean("use_top_suggestion", contextRanking)
                    .putBoolean("auto_capitalization", false).putBoolean("double_space_period", false)
                    .putBoolean("vibrate", false).putBoolean("sound", false)
                    .putBoolean("notification", false).putBoolean("navbar", false)
                    .putString("layout", korean ? String.valueOf(Definitions.LAYOUT_KOREAN) : "0")
                    .commit();
            service = Robolectric.buildService(TestIme.class).create();
            ime = service.get();
            connection = new Editor(new EditText(app));
            ime.connection = connection;
            EditorInfo info = new EditorInfo();
            info.inputType = InputType.TYPE_CLASS_TEXT;
            info.packageName = app.getPackageName();
            info.initialSelStart = info.initialSelEnd = 0;
            ime.onStartInput(info, false);
            View root = ime.onCreateInputView();
            keyboard = (KeyboardLayoutView) getField(ime, "mCurrentKeyboardLayoutView");
            strip = (SuggestionStripView) getField(ime, "swipeSuggestions");
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
            // Resize the actual keyboard, which recomputes each child key's bounds.
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            keyboard.layout(0, 0, width, height);
        }

        void seed(String value) { connection.commitText(value, 1); }

        void swipe(String sequence, float[][] offsets) throws Exception {
            long down = time;
            for (int i = 0; i < sequence.length(); i++) {
                View key = letter(sequence.charAt(i));
                float dx = offsets == null ? 0 : offsets[i][0];
                float dy = offsets == null ? 0 : offsets[i][1];
                send(i == 0 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE, down, time,
                        (key.getLeft() + key.getRight()) / 2f + dx * key.getWidth(),
                        (key.getTop() + key.getBottom()) / 2f + dy * key.getHeight());
                time += 70;
            }
            View last = letter(sequence.charAt(sequence.length() - 1));
            float dx = offsets == null ? 0 : offsets[offsets.length - 1][0];
            float dy = offsets == null ? 0 : offsets[offsets.length - 1][1];
            send(MotionEvent.ACTION_UP, down, time,
                    (last.getLeft() + last.getRight()) / 2f + dx * last.getWidth(),
                    (last.getTop() + last.getBottom()) / 2f + dy * last.getHeight());
            time += 100;
        }

        // Continuous motion at approximately 80 ms per key width, including every turn.
        void swipeContinuously(String sequence, boolean batched, boolean repeatDwell)
                throws Exception {
            swipeContinuously(sequence, null, batched, repeatDwell);
        }

        void swipeContinuously(String sequence, float[][] offsets, boolean batched,
                               boolean repeatDwell) throws Exception {
            long down = time;
            View first = letter(sequence.charAt(0));
            float x = (first.getLeft() + first.getRight()) / 2f
                    + (offsets == null ? 0 : offsets[0][0] * first.getWidth());
            float y = (first.getTop() + first.getBottom()) / 2f
                    + (offsets == null ? 0 : offsets[0][1] * first.getHeight());
            send(MotionEvent.ACTION_DOWN, down, time, x, y);
            for (int i = 1; i < sequence.length(); i++) {
                View target = letter(sequence.charAt(i));
                float targetX = (target.getLeft() + target.getRight()) / 2f
                        + (offsets == null ? 0 : offsets[i][0] * target.getWidth());
                float targetY = (target.getTop() + target.getBottom()) / 2f
                        + (offsets == null ? 0 : offsets[i][1] * target.getHeight());
                int steps = Math.max(1, (int) Math.ceil(10 * Math.hypot(
                        (targetX - x) / target.getWidth(),
                        (targetY - y) / target.getHeight())));
                MotionEvent move = null;
                for (int step = 1; step <= steps; step++) {
                    time += 8;
                    float sampleX = x + (targetX - x) * step / steps;
                    float sampleY = y + (targetY - y) * step / steps;
                    if (!batched) {
                        send(MotionEvent.ACTION_MOVE, down, time, sampleX, sampleY);
                    } else if (move == null) {
                        move = MotionEvent.obtain(down, time, MotionEvent.ACTION_MOVE,
                                sampleX, sampleY, 0);
                    } else {
                        move.addBatch(time, sampleX, sampleY, 1, 1, 0);
                    }
                }
                if (move != null) {
                    assertTrue("touch not handled", keyboard.dispatchTouchEvent(move));
                    move.recycle();
                }
                x = targetX;
                y = targetY;
                if (repeatDwell && sequence.charAt(i) == 't') {
                    for (int dwell = 0; dwell < 10; dwell++) {
                        time += 20;
                        send(MotionEvent.ACTION_MOVE, down, time, x, y);
                    }
                }
            }
            time += 10;
            send(MotionEvent.ACTION_UP, down, time, x, y);
            time += 100;
        }

        void tap(int code) throws Exception {
            View key = key(code);
            long down = time;
            float x = (key.getLeft() + key.getRight()) / 2f;
            float y = (key.getTop() + key.getBottom()) / 2f;
            send(MotionEvent.ACTION_DOWN, down, time, x, y);
            send(MotionEvent.ACTION_UP, down, time += 20, x, y);
            time += 100;
        }

        private void send(int action, long down, long when, float x, float y) {
            MotionEvent event = MotionEvent.obtain(down, when, action, x, y, 0);
            assertTrue("touch not handled", keyboard.dispatchTouchEvent(event));
            event.recycle();
        }

        private View letter(char letter) throws Exception {
            Field keyField = KeyboardButtonView.class.getDeclaredField("key");
            keyField.setAccessible(true);
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                Key key = (Key) keyField.get(view);
                if (KoreanKeyMap.qwertyKeyFor(key.info.code) == letter ||
                        (key.info.code == letter && key.info.onShiftLabel != null)) return view;
            }
            throw new AssertionError("Missing letter " + letter);
        }

        private View key(int code) throws Exception {
            Field keyField = KeyboardButtonView.class.getDeclaredField("key");
            keyField.setAccessible(true);
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View view = keyboard.getChildAt(i);
                if (((Key) keyField.get(view)).info.code == code) return view;
            }
            throw new AssertionError("Missing key " + code);
        }

        void awaitComposition() throws Exception {
            await(() -> !composingText().isEmpty() && strip.getCandidateCount() > 0
                    && strip.getCandidateView(0).isClickable());
        }

        void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle();
                if (condition.getAsBoolean()) return;
                Thread.sleep(10);
            }
            fail("Timed out waiting for swipe, text=" + text());
        }

        void drainWorkers() throws Exception {
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

        String text() { return connection.getEditable().toString(); }

        String composingText() {
            Editable text = connection.getEditable();
            int start = BaseInputConnection.getComposingSpanStart(text);
            int end = BaseInputConnection.getComposingSpanEnd(text);
            return start < 0 || end < start ? "" : text.subSequence(start, end).toString();
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
            if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z
                    && (event.getMetaState() & KeyEvent.META_CTRL_ON) == 0) {
                char c = (char) ('a' + code - KeyEvent.KEYCODE_A);
                if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) c = Character.toUpperCase(c);
                commitText(String.valueOf(c), 1);
            }
            return true;
        }
    }
}
