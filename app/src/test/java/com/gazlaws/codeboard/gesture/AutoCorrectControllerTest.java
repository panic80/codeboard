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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gazlaws.codeboard.layout.ui.SuggestionStripView;
import com.gazlaws.codeboard.theme.ThemeInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class AutoCorrectControllerTest {
    private Context context;
    private EditText editor;
    private TestConnection connection;
    private Provider provider;
    private ManualExecutor worker;
    private Loader loader;
    private SuggestionStripView strip;
    private AutoCorrectController controller;
    private int edits;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        editor = new EditText(context);
        connection = new TestConnection(editor);
        provider = new Provider(connection);
        worker = new ManualExecutor();
        loader = new Loader();
        strip = new SuggestionStripView(context, new ThemeInfo());
        controller = new AutoCorrectController(provider, () -> {
            edits++;
            if (provider.current != null) provider.current.finishComposingText();
        }, worker, null, loader, new Handler(Looper.getMainLooper()));
        controller.setSuggestionStrip(strip);
    }

    @Test public void correctsAtSpaceRetainsOriginalCandidateAndBackspaceRestoresIt() {
        type("teh");
        drain();
        assertEquals("teh", text());
        assertEquals("teh", candidate(0));
        assertEquals("the", candidate(1));
        assertTrue(controller.commitSpace());
        assertEquals("teh ", text());
        drain();
        assertEquals("the ", text());
        assertEquals("teh", candidate(0));
        assertTrue(controller.undoCorrection());
        assertEquals("teh ", text());
        assertFalse(controller.undoCorrection());
    }

    @Test public void selectingOriginalAfterSpacePreservesSpace() {
        type("teh");
        assertTrue(controller.commitSpace());
        drain();
        click(0);
        assertEquals("teh ", text());
        assertFalse(controller.undoCorrection());
    }

    @Test public void candidateChoiceReplacesWholeCurrentWordAndIsNotOverriddenAtSpace() {
        type("teh");
        drain();
        click(1);
        assertEquals("the", text());
        assertEquals(1, edits);
        assertFalse(controller.commitSpace());
        connection.commitText(" ", 1);
        assertEquals("the ", text());
    }

    @Test public void sentencePunctuationAndCapitalizationArePreserved() {
        type("Teh");
        nonletter('.');
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("The. ", text());
        assertTrue(controller.undoCorrection());
        assertEquals("Teh. ", text());
    }

    @Test public void exactWordIsNeverAutomaticallyReplaced() {
        type("the");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("the ", text());
        assertFalse(controller.undoCorrection());
    }

    @Test public void queuedInputsRunInArrivalOrderAfterCorrection() {
        type("teh");
        assertTrue(controller.commitSpace());
        List<String> order = new ArrayList<>();
        assertTrue(controller.deferIfBusy(() -> { order.add("a"); connection.commitText("a", 1); controller.onTypedTextUpdated(false); }));
        assertTrue(controller.deferIfBusy(() -> { order.add("b"); connection.commitText("b", 1); controller.onTypedTextUpdated(false); }));
        assertEquals("teh ", text());
        drain();
        assertEquals("the ab", text());
        assertEquals(Arrays.asList("a", "b"), order);
        assertFalse(controller.undoCorrection());
    }

    @Test public void aSecondQueuedBoundaryKeepsLaterInputInOrder() {
        type("teh");
        assertTrue(controller.commitSpace());
        assertTrue(controller.deferIfBusy(() -> type("teh")));
        assertTrue(controller.deferIfBusy(() -> assertTrue(controller.commitSpace())));
        assertTrue(controller.deferIfBusy(() -> type("x")));
        drain();
        assertEquals("the the x", text());
    }

    @Test public void dictionaryFailureKeepsOriginalSpaceAndFlushesQueuedInput() {
        loader.fail = true;
        type("teh");
        assertTrue(controller.commitSpace());
        assertTrue(controller.deferIfBusy(() -> connection.commitText("x", 1)));
        drain();
        assertEquals("teh x", text());
        assertFalse(controller.undoCorrection());
    }

    @Test public void dictionaryVersionFailureStillInsertsTheOriginalSpace() {
        loader.failVersion = true;
        type("teh");
        assertTrue(controller.commitSpace());
        assertEquals("teh ", text());
        assertFalse(controller.deferIfBusy(null));
    }

    @Test public void movingCursorRejectsCorrectionButDoesNotRemoveTheAlreadyInsertedSpace() {
        type("teh");
        assertTrue(controller.commitSpace());
        Selection.setSelection(editor.getText(), 1);
        controller.onSelectionChanged(4, 4, 1, 1, -1);
        drain();
        assertEquals("teh ", text());
        assertFalse(controller.undoCorrection());
    }

    @Test public void movementWithoutCallbackAlsoRejectsCorrection() {
        type("teh");
        assertTrue(controller.commitSpace());
        Selection.setSelection(editor.getText(), 1);
        drain();
        assertEquals("teh ", text());
    }

    @Test public void oldSelectionCallbacksDoNotCancelAnUnmovedWord() {
        type("teh");
        controller.onSelectionChanged(0, 0, 1, 1, -1);
        assertTrue(controller.commitSpace());
        controller.onSelectionChanged(1, 1, 2, 2, -1);
        drain();
        assertEquals("the ", text());
    }

    @Test public void changingEditorOrCancellingDiscardsStaleWorkAndQueuedEvents() {
        type("teh");
        assertTrue(controller.commitSpace());
        boolean[] executed = {false};
        assertTrue(controller.deferIfBusy(() -> executed[0] = true));
        EditText second = new EditText(context);
        provider.current = new TestConnection(second);
        drain();
        assertEquals("teh ", text());
        assertEquals("", second.getText().toString());
        assertFalse(executed[0]);

        provider.current = connection;
        setText("");
        type("teh");
        assertTrue(controller.commitSpace());
        controller.cancel();
        drain();
        assertEquals("teh ", text());
    }

    @Test public void typingAfterPasteOrInMiddleOfPriorWordDoesNotReplaceIt() {
        setText("te");
        type("h");
        assertFalse(controller.commitSpace());
        connection.commitText(" ", 1);
        assertEquals("teh ", text());

        controller.cancel();
        setText("testing");
        Selection.setSelection(editor.getText(), 2);
        type("teh");
        assertFalse(controller.commitSpace());
        connection.commitText(" ", 1);
        assertEquals("teteh sting", text());
        drain();
    }

    @Test public void identifiersAndEmbeddedPunctuationStayUntouched() {
        for (String token : Arrays.asList("teh_test", "teh.test", "teh@test", "teh2", "tEh", "APIKey")) {
            controller.cancel();
            setText("");
            for (char c : token.toCharArray()) {
                if (Character.isLetter(c)) type(String.valueOf(c)); else nonletter(c);
            }
            assertFalse(token, controller.commitSpace());
            connection.commitText(" ", 1);
            drain();
            assertEquals(token + " ", text());
        }
    }

    @Test public void movingToAnotherPositionClearsAnIdentifierBlock() {
        setText("          ");
        Selection.setSelection(editor.getText(), 0);
        type("foo");
        nonletter('2');
        Selection.setSelection(editor.getText(), editor.length());
        controller.onSelectionChanged(4, 4, editor.length(), editor.length(), -1);
        type("teh");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("foo2          the ", text());
    }

    @Test public void canTypeAContractionWithoutTreatingItsApostropheAsCode() {
        type("can");
        nonletter('\'');
        type("t");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("can't ", text());
    }

    @Test public void backspaceUpdatesTheTrackedWord() {
        type("tehh");
        connection.deleteSurroundingText(1, 0);
        controller.onBackspaceTyped(false);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("the ", text());
    }

    @Test public void koreanComposingSyllableIsFinishedBeforeCandidateReplacement() {
        compose("ㅇ"); compose("아"); compose("안");
        commitComposedAndStart("ㄴ"); compose("너"); compose("넝");
        commitComposedAndStart("ㅎ"); compose("하");
        commitComposedAndStart("ㅅ"); compose("세");
        commitComposedAndStart("ㅇ"); compose("여");
        assertEquals("안넝하세여", text());
        drain();
        assertEquals("안녕하세요", candidate(1));
        click(1);
        assertEquals("안녕하세요", text());
        assertEquals(1, edits);
    }

    @Test public void dictionaryVersionRejectsStaleResultsAndReloadsForNextWord() {
        type("teh");
        assertTrue(controller.commitSpace());
        loader.entries.add(new SwipeDecoder.Entry("teh", 2000000));
        loader.version++;
        drain();
        assertEquals("teh ", text());
        type("teh");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("teh teh ", text());
        assertTrue(loader.loads > 0);
    }

    @Test public void productionWorkerReloadsBundledAndPersonalDictionary() throws InterruptedException {
        controller.close();
        PersonalDictionary personal = new PersonalDictionary(context);
        personal.removeWord("Qirivelo");
        controller = new AutoCorrectController(context, provider, () -> connection.finishComposingText());
        controller.setSuggestionStrip(strip);
        try {
            type("qirivelo");
            awaitCandidate("qirivelo", 0);
            personal.addWord("Qirivelo");
            controller.cancel();
            setText("");
            type("qirivelo");
            awaitCandidate("Qirivelo", 1);
            assertTrue(controller.commitSpace());
            awaitNotBusy();
            assertEquals("qirivelo ", text());
        } finally {
            controller.close();
            personal.removeWord("Qirivelo");
        }
    }

    @Test public void staleSuggestionDoesNotReplaceNewerInputOrClearItAfterCancel() {
        type("teh");
        type("x");
        drain();
        assertEquals("tehx", text());
        assertEquals("tehx", candidate(0));
        controller.cancel();
        click(0);
        assertEquals("tehx", text());
    }

    private void type(String letters) {
        for (char c : letters.toCharArray()) {
            connection.commitText(String.valueOf(c), 1);
            controller.onTypedTextUpdated(false);
        }
    }

    private void nonletter(char c) {
        connection.commitText(String.valueOf(c), 1);
        controller.onNonLetterTyped();
    }

    private void compose(String syllable) {
        connection.setComposingText(syllable, 1);
        controller.onTypedTextUpdated(true);
    }

    private void commitComposedAndStart(String firstJamo) {
        connection.finishComposingText();
        compose(firstJamo);
    }

    private void setText(String text) {
        editor.setText(text);
        Selection.setSelection(editor.getText(), editor.length());
    }

    private String text() { return editor.getText().toString(); }
    private TextView candidateView(int index) { return (TextView) ((LinearLayout) strip.getChildAt(0)).getChildAt(index); }
    private String candidate(int index) { return candidateView(index).getText().toString(); }
    private void click(int index) { candidateView(index).performClick(); }

    private void drain() {
        int count = 0;
        while (!worker.tasks.isEmpty() && count++ < 100) {
            worker.tasks.removeFirst().run();
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue("Worker should settle", worker.tasks.isEmpty());
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void awaitCandidate(String expected, int index) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            LinearLayout content = (LinearLayout) strip.getChildAt(0);
            if (content.getChildCount() > index && expected.equals(candidate(index))) return;
            Thread.sleep(10);
        }
        fail("Timed out waiting for candidate " + expected);
    }

    private void awaitNotBusy() throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            if (!controller.deferIfBusy(null)) return;
            Thread.sleep(10);
        }
        fail("Timed out waiting for correction");
    }

    private static final class Provider implements AutoCorrectController.ConnectionProvider {
        InputConnection current;
        Provider(InputConnection current) { this.current = current; }
        @Override public InputConnection get() { return current; }
    }

    private static final class TestConnection extends BaseInputConnection {
        final EditText editor;
        TestConnection(EditText editor) { super((View) editor, true); this.editor = editor; }
        @Override public Editable getEditable() { return editor.getText(); }
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
    }

    private static final class Loader implements AutoCorrectController.DictionaryLoader {
        final List<SwipeDecoder.Entry> entries = new ArrayList<>(Arrays.asList(
                new SwipeDecoder.Entry("the", 100000),
                new SwipeDecoder.Entry("hello", 100000),
                new SwipeDecoder.Entry("world", 100000),
                new SwipeDecoder.Entry("can't", 100000),
                new SwipeDecoder.Entry("안녕하세요", 100000)));
        int version, loads;
        boolean fail, failVersion;
        @Override public int version() {
            if (failVersion) throw new IllegalStateException("Version unavailable");
            return version;
        }
        @Override public List<SwipeDecoder.Entry> load(boolean korean) throws IOException {
            loads++;
            if (fail) throw new IOException("Dictionary missing");
            return new ArrayList<>(entries);
        }
    }
}
