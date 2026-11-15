package com.gazlaws.codeboard.gesture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Selection;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.TextView;

import com.gazlaws.codeboard.gesture.SwipeDecoder.Entry;
import com.gazlaws.codeboard.gesture.SwipeDecoder.KeyPosition;
import com.gazlaws.codeboard.gesture.SwipeDecoder.Point;
import com.gazlaws.codeboard.layout.ui.SuggestionStripView;
import com.gazlaws.codeboard.theme.ThemeInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SwipeTypingControllerTest {
    private Context context;
    private EditText editor;
    private TestConnection connection;
    private MutableConnectionProvider provider;
    private ManualExecutor worker;
    private SwipeTypingController controller;
    private SuggestionStripView strip;
    private List<KeyPosition> keys;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        editor = new EditText(context);
        connection = new TestConnection(editor);
        provider = new MutableConnectionProvider(connection);
        worker = new ManualExecutor();
        controller = new SwipeTypingController(provider, worker, null, korean -> korean
                ? Arrays.asList(new Entry("한글", 500), new Entry("한국", 100))
                : Arrays.asList(new Entry("hello", 500), new Entry("hallo", 100),
                        new Entry("world", 500), new Entry("i", 500), new Entry("i'm", 500)),
                new Handler(Looper.getMainLooper()));
        strip = new SuggestionStripView(context, new ThemeInfo());
        controller.setSuggestionStrip(strip);
        keys = qwerty();
    }

    @Test public void appliesBestWordAndCandidateClickReplacesComposition() {
        swipe("hello", false, false, false);
        assertEquals("", text());
        assertFalse(controller.hasComposition());
        finishWork();
        assertEquals("hello", text());
        assertTrue(controller.hasComposition());
        assertEquals("hello", candidateAt(0).getText().toString());
        assertEquals("hallo", candidateAt(1).getText().toString());
        candidateAt(1).performClick();
        assertEquals("hallo", text());
    }

    @Test public void contextualSwipeCanResolveANearbyKeyAndHonorsTheUsersChoice() {
        installContextFixture(null, false);
        controller.setContextRankingEnabled(true);
        setText("is ");
        swipeAmbiguousBot();
        finishWork();
        assertEquals("is not", text());
        assertEquals("not", candidateAt(0).getText().toString());
        assertEquals("bot", candidateAt(1).getText().toString());
        candidateAt(1).performClick();
        controller.finish(connection, true);
        assertEquals("is bot ", text());
    }

    @Test public void contextDoesNotOverruleAClearGestureOrAnUnrelatedPhrase() {
        installContextFixture(null, false);
        controller.setContextRankingEnabled(true);
        setText("is ");
        swipe("bot", false, false, false);
        finishWork();
        assertEquals("is bot", text());
        controller.finish(connection, false);
        setText("the ");
        swipeAmbiguousBot();
        finishWork();
        assertEquals("the bot", text());
    }

    @Test public void disabledContextDoesNotReadEditorPrefixOrChangeSwipeRanking() {
        installContextFixture(null, false);
        setText("is ");
        int reads = connection.beforeCalls;
        swipeAmbiguousBot();
        assertEquals(reads, connection.beforeCalls);
        finishWork();
        assertEquals("is bot", text());
    }

    @Test public void savedGeometryWinnerIsNotReplacedByContext() {
        context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit().clear().commit();
        PersonalDictionary personal = new PersonalDictionary(context);
        assertTrue(personal.addWord("bot"));
        installContextFixture(personal, false);
        controller.setContextRankingEnabled(true);
        setText("is ");
        swipeAmbiguousBot();
        finishWork();
        assertEquals("is bot", text());
        personal.removeWord("bot");
    }

    @Test public void unavailableContextFallsBackToGestureAndStaleContextIsRejected() {
        installContextFixture(null, true);
        controller.setContextRankingEnabled(true);
        setText("is ");
        swipeAmbiguousBot();
        finishWork();
        assertEquals("is bot", text());

        installContextFixture(null, false);
        controller.setContextRankingEnabled(true);
        setText("is ");
        swipeAmbiguousBot();
        boolean[] ran = {false};
        assertTrue(controller.deferIfDecoding(() -> ran[0] = true));
        setText("us "); // The caret is unchanged, but the context no longer matches.
        finishWork();
        assertEquals("us ", text());
        assertFalse(ran[0]);
    }

    private void installContextFixture(PersonalDictionary personal, boolean failContext) {
        controller.close();
        SwipeTypingController.DictionaryLoader loader = new SwipeTypingController.DictionaryLoader() {
            @Override public List<Entry> load(boolean korean) {
                return Arrays.asList(new Entry("bot", 944), new Entry("not", 4262273));
            }
            @Override public TypingCorrector.ContextModel loadContext() throws IOException {
                if (failContext) throw new IOException("missing context");
                return (previous, candidate) -> new TypingCorrector.ContextEvidence(
                        previous.endsWith("is ") && candidate.equals("not") ? 10000 : 0, 0);
            }
        };
        controller = new SwipeTypingController(provider, worker, null, loader,
                new Handler(Looper.getMainLooper()), personal);
        controller.setSuggestionStrip(strip);
    }

    private void swipeAmbiguousBot() {
        Map<Character, KeyPosition> lookup = new HashMap<>();
        for (KeyPosition key : keys) lookup.put(key.key, key);
        KeyPosition b = lookup.get('b'), n = lookup.get('n'), o = lookup.get('o'), t = lookup.get('t');
        controller.onSwipe(Arrays.asList(new Point(b.centerX + 0.3f * (n.centerX - b.centerX), b.centerY),
                new Point(o.centerX, o.centerY), new Point(t.centerX, t.centerY)),
                keys, false, false, false);
    }

    @Test public void laterEventsWaitAndRunInOrderEvenAcrossASecondSwipe() {
        swipe("hello", false, false, false);
        List<String> order = new ArrayList<>();
        assertTrue(controller.deferIfDecoding(() -> order.add("first")));
        swipe("world", false, false, false);
        assertTrue(controller.deferIfDecoding(() -> {
            order.add("last");
            controller.finish(connection, true);
            connection.commitText("x", 1);
        }));
        finishWork();
        assertEquals("hello", text());
        assertEquals(Collections.singletonList("first"), order);
        finishWork();
        assertEquals("hello world x", text());
        assertEquals(Arrays.asList("first", "last"), order);
        assertFalse(controller.hasComposition());
    }

    @Test public void addsSpaceAfterWordsAndSentencePunctuationButNotOpeningMarks() {
        setText("typed");
        swipe("hello", false, false, false);
        finishWork();
        assertEquals("typed hello", text());
        controller.finish(connection, false);

        for (char mark : ".,!?;:".toCharArray()) {
            setText("word" + mark);
            swipe("hello", false, false, false);
            finishWork();
            assertEquals("word" + mark + " hello", text());
            controller.finish(connection, false);
        }
        for (char mark : "(['\"-/@".toCharArray()) {
            setText("word" + mark);
            swipe("hello", false, false, false);
            finishWork();
            assertEquals("word" + mark + "hello", text());
            controller.finish(connection, false);
        }

        setText("a selected");
        Selection.setSelection(editor.getText(), 2, editor.length());
        swipe("hello", false, false, false);
        finishWork();
        assertEquals("a hello", text());
    }

    @Test public void capitalizationAndKoreanSpellingFollowTheGestureSnapshot() {
        swipe("hello", false, true, false);
        finishWork();
        assertEquals("Hello", text());
        controller.finish(connection, false);
        setText("");
        swipe("hello", false, false, true);
        finishWork();
        assertEquals("HELLO", text());
        controller.finish(connection, false);
        setText("");
        swipe("gksrmf", true, true, true);
        finishWork();
        assertEquals("한글", text());
    }

    @Test public void capitalizesEnglishPronounAndContractionWithoutShift() {
        KeyPosition i = null;
        for (KeyPosition key : keys) if (key.key == 'i') i = key;
        assertNotNull(i);
        controller.onSwipe(Arrays.asList(new Point(i.centerX, i.centerY),
                new Point(i.centerX + 0.1f, i.centerY)), keys, false, false, false);
        finishWork();
        assertEquals("I", text());
        controller.finish(connection, false);
        setText("");
        swipe("im", false, false, false);
        finishWork();
        assertEquals("I'm", text());
        controller.finish(connection, false);
        setText("");
        swipe("im", false, false, true);
        finishWork();
        assertEquals("I'M", text());
    }

    @Test public void backspaceDeletesTheWholeActiveWordAndFinishAddsOneSpace() {
        swipe("hello", false, false, false);
        finishWork();
        assertTrue(controller.backspace(connection));
        assertEquals("", text());
        assertFalse(controller.backspace(connection));
        swipe("hello", false, false, false);
        finishWork();
        controller.finish(connection, true);
        assertEquals("hello ", text());
        assertFalse(controller.hasComposition());
    }

    @Test public void cancellationDiscardsPendingResultAndQueuedEvents() {
        swipe("hello", false, false, false);
        boolean[] ran = {false};
        assertTrue(controller.deferIfDecoding(() -> ran[0] = true));
        controller.cancel(connection);
        finishWork();
        assertEquals("", text());
        assertFalse(ran[0]);
        assertFalse(controller.hasComposition());
    }

    @Test public void editorChangeNeverAppliesAnOldResultOrQueuedInput() {
        swipe("hello", false, false, false);
        boolean[] ran = {false};
        assertTrue(controller.deferIfDecoding(() -> ran[0] = true));
        EditText newEditor = new EditText(context);
        provider.current = new TestConnection(newEditor);
        finishWork();
        assertEquals("", text());
        assertEquals("", newEditor.getText().toString());
        assertFalse(ran[0]);
    }

    @Test public void movingTheCursorWhileDecodingRejectsResultAndQueuedEvents() {
        setText("before");
        swipe("hello", false, false, false);
        boolean[] ran = {false};
        assertTrue(controller.deferIfDecoding(() -> ran[0] = true));
        Selection.setSelection(editor.getText(), 2);
        controller.onSelectionChanged(6, 6, 2, 2, -1);
        finishWork();
        assertEquals("before", text());
        assertFalse(ran[0]);
        assertFalse(controller.hasComposition());
    }

    @Test public void movedCursorIsDetectedEvenBeforeItsSelectionCallbackArrives() {
        setText("before");
        swipe("hello", false, false, false);
        Selection.setSelection(editor.getText(), 2);
        finishWork();
        assertEquals("before", text());
        assertFalse(controller.hasComposition());
    }

    @Test public void delayedSelectionCallbackFromPriorTypingDoesNotCancelTheSwipe() {
        setText("before");
        swipe("hello", false, false, false);
        // The callback is old, while the actual editor selection is still at position six.
        controller.onSelectionChanged(5, 5, 6, 6, -1);
        finishWork();
        assertEquals("before hello", text());
    }

    @Test public void delayedCompositionCallbackDoesNotCancelTheNextSwipe() {
        swipe("hello", false, false, false);
        finishWork();
        swipe("world", false, false, false);
        controller.onSelectionChanged(0, 0, 5, 5, 5);
        finishWork();
        assertEquals("hello world", text());
    }

    @Test public void errorsShowHintAndStillFlushTheNextKeystroke() {
        controller = new SwipeTypingController(provider, worker, null,
                korean -> { throw new IOException("missing dictionary"); },
                new Handler(Looper.getMainLooper()));
        controller.setSuggestionStrip(strip);
        swipe("hello", false, false, false);
        assertTrue(controller.deferIfDecoding(() -> connection.commitText("x", 1)));
        finishWork();
        assertEquals("x", text());
        assertEquals("No match. Try again.", candidateAt(0).getText().toString());
    }

    @Test public void movedSelectionFinalizesCandidatesWithoutChangingTheWord() {
        swipe("hello", false, false, false);
        finishWork();
        controller.onSelectionChanged(5, 5, 5);
        assertTrue(controller.hasComposition());
        Selection.setSelection(editor.getText(), 2);
        controller.onSelectionChanged(2, 2, 5);
        assertFalse(controller.hasComposition());
        assertEquals("hello", text());
        assertEquals("Swipe across letters to type", candidateAt(0).getText().toString());
    }

    @Test public void delayedSelectionCallbackAfterCompositionDoesNotClearSuggestions() {
        swipe("hello", false, false, false);
        finishWork();
        controller.onSelectionChanged(0, 0, 2, 2, -1);
        assertTrue(controller.hasComposition());
        assertEquals("hello", candidateAt(0).getText().toString());
    }

    @Test public void productionWorkerLoadsBothBundledDictionaries() throws InterruptedException {
        controller.close();
        controller = new SwipeTypingController(context, provider);
        controller.setSuggestionStrip(strip);
        try {
            swipe("hello", false, false, false);
            awaitRealWorker();
            assertEquals("hello", text());
            controller.finish(connection, false);
            setText("");
            swipe("gksrmf", true, false, false);
            awaitRealWorker();
            assertEquals("한글", text());
        } finally {
            controller.close();
        }
    }

    private void awaitRealWorker() throws InterruptedException {
        // Drain main-thread callbacks while allowing the real background executor to finish.
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!controller.hasComposition() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue("The bundled dictionary should decode the gesture", controller.hasComposition());
    }

    private void swipe(String sequence, boolean korean, boolean capitalize, boolean capsLock) {
        Map<Character, KeyPosition> lookup = new HashMap<>();
        for (KeyPosition key : keys) lookup.put(key.key, key);
        List<Point> path = new ArrayList<>();
        for (char letter : sequence.toCharArray()) {
            KeyPosition key = lookup.get(Character.toLowerCase(letter));
            path.add(new Point(key.centerX, key.centerY));
        }
        controller.onSwipe(path, keys, korean, capitalize, capsLock);
    }

    private void finishWork() {
        worker.runNext();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private TextView candidateAt(int index) {
        return strip.getCandidateView(index);
    }

    private void setText(String text) {
        editor.setText(text);
        Selection.setSelection(editor.getText(), editor.length());
    }

    private String text() { return editor.getText().toString(); }

    private static List<KeyPosition> qwerty() {
        List<KeyPosition> result = new ArrayList<>();
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        float[] offsets = {0, 0.5f, 1};
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                result.add(new KeyPosition(rows[row].charAt(column), offsets[row] + column, row, 1, 1));
            }
        }
        return result;
    }

    private static final class MutableConnectionProvider implements SwipeTypingController.ConnectionProvider {
        InputConnection current;
        MutableConnectionProvider(InputConnection connection) { current = connection; }
        @Override public InputConnection get() { return current; }
    }

    private static final class TestConnection extends BaseInputConnection {
        final EditText editor;
        int beforeCalls;
        TestConnection(EditText editor) { super((View) editor, true); this.editor = editor; }
        @Override public Editable getEditable() { return editor.getText(); }
        @Override public CharSequence getTextBeforeCursor(int length, int flags) {
            beforeCalls++;
            return super.getTextBeforeCursor(length, flags);
        }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText text = new ExtractedText();
            text.selectionStart = Selection.getSelectionStart(editor.getText());
            text.selectionEnd = Selection.getSelectionEnd(editor.getText());
            return text;
        }
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable runnable) { tasks.addLast(runnable); }
        void runNext() { tasks.removeFirst().run(); }
    }
}
