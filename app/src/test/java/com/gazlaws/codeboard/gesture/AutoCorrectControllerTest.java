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

import com.gazlaws.codeboard.R;
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
    private PersonalDictionary personal;
    private TestPersonalWords personalWords;
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
        context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit().clear().commit();
        personal = new PersonalDictionary(context);
        personalWords = new TestPersonalWords(personal);
        loader.personal = personal;
        strip = new SuggestionStripView(context, new ThemeInfo());
        controller = new AutoCorrectController(provider, () -> {
            edits++;
            if (provider.current != null) provider.current.finishComposingText();
        }, worker, null, loader, new Handler(Looper.getMainLooper()), personalWords);
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
        boolean[] ran = {false};
        assertTrue(controller.deferIfBusy(() -> { ran[0] = true; connection.commitText("X", 1); }));
        Selection.setSelection(editor.getText(), 1);
        controller.onSelectionChanged(4, 4, 1, 1, -1);
        drain();
        assertEquals("teh ", text());
        assertFalse(ran[0]);
        assertFalse(controller.undoCorrection());
    }

    @Test public void movementWithoutCallbackAlsoRejectsCorrection() {
        type("teh");
        assertTrue(controller.commitSpace());
        boolean[] ran = {false};
        assertTrue(controller.deferIfBusy(() -> { ran[0] = true; connection.commitText("X", 1); }));
        Selection.setSelection(editor.getText(), 1);
        drain();
        assertEquals("teh ", text());
        assertFalse(ran[0]);
    }

    @Test public void oldSelectionCallbacksDoNotCancelAnUnmovedWord() {
        type("teh");
        controller.onSelectionChanged(0, 0, 1, 1, -1);
        assertTrue(controller.commitSpace());
        assertTrue(controller.deferIfBusy(() -> connection.commitText("X", 1)));
        controller.onSelectionChanged(1, 1, 2, 2, -1);
        drain();
        assertEquals("the X", text());
    }

    @Test public void changedDictionaryStillReplaysQueuedInputAtUnmovedCursor() {
        type("teh");
        assertTrue(controller.commitSpace());
        assertTrue(controller.deferIfBusy(() -> connection.commitText("X", 1)));
        loader.version++;
        drain();
        assertEquals("teh X", text());
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

    @Test public void addIsImmediateAndProtectsTheCurrentWordAtSpace() {
        type("teh");
        assertEquals("teh", candidate(0));
        assertLearning("Add word", true);
        assertTrue("The engine has not loaded yet", loader.loads == 0);
        learn();
        assertEquals("teh", text());
        assertEquals(1, edits);
        assertEquals(Arrays.asList("teh"), personal.getWords());
        assertLearning("Saved", false);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("teh ", text());
        assertFalse(controller.undoCorrection());
        assertLearning("Saved", false);
    }

    @Test public void addingAfterCorrectionRestoresOriginalAndPersistsIt() {
        type("teh");
        nonletter('.');
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("the. ", text());
        assertLearning("Add word", true);
        learn();
        assertEquals("teh. ", text());
        assertEquals(Arrays.asList("teh"), new PersonalDictionary(context).getWords());
        assertLearning("Saved", false);
        assertFalse(controller.undoCorrection());

        type("teh");
        assertLearning("Saved", false);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("teh. teh ", text());
    }

    @Test public void learningWhileSpaceIsPendingCancelsItsCorrectionAndReplaysInput() {
        type("teh");
        assertTrue(controller.commitSpace());
        assertLearning("Add word", true);
        assertTrue(controller.deferIfBusy(() -> type("x")));
        learn();
        assertEquals("teh x", text());
        assertEquals(Arrays.asList("teh"), personal.getWords());
        drain();
        assertEquals("teh x", text());
    }

    @Test public void typingDoesNotLearnAutomaticallyAndKnownWordsMayBeAdded() {
        type("the");
        drain();
        assertEquals(1, strip.getCandidateCount());
        assertLearning("Add word", true);
        assertTrue(personal.getWords().isEmpty());
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("the ", text());
        assertTrue(personal.getWords().isEmpty());
        learn();
        assertEquals(Arrays.asList("the"), personal.getWords());
    }

    @Test public void savedWordsAreCaseInsensitiveAndAreNotAddedTwice() {
        assertTrue(personal.addWord("Teh"));
        type("teh");
        assertLearning("Saved", false);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("teh ", text());
        assertEquals(Arrays.asList("Teh"), personal.getWords());
        assertLearning("Saved", false);
    }

    @Test public void staleLearningCallbacksCannotSaveChangedWordsOrEditors() {
        type("te");
        View.OnClickListener previous = learningListener();
        type("h");
        previous.onClick(learningAction());
        assertTrue(personal.getWords().isEmpty());

        View.OnClickListener changedEditor = learningListener();
        EditText second = new EditText(context);
        provider.current = new TestConnection(second);
        changedEditor.onClick(learningAction());
        assertTrue(personal.getWords().isEmpty());

        provider.current = connection;
        View.OnClickListener movedCursor = learningListener();
        Selection.setSelection(editor.getText(), 1);
        movedCursor.onClick(learningAction());
        assertTrue(personal.getWords().isEmpty());
        controller.cancel();
        Selection.setSelection(editor.getText(), editor.length());
        movedCursor.onClick(learningAction());
        assertTrue(personal.getWords().isEmpty());
        assertEquals(View.GONE, learningAction().getVisibility());
    }

    @Test public void koreanMustBeComposedAndLearningFinishesTheSyllable() {
        compose("ㅇ");
        assertEquals(View.GONE, learningAction().getVisibility());
        compose("아");
        assertLearning("Add word", true);
        compose("안");
        learn();
        assertEquals("안", text());
        assertEquals(1, edits);
        assertEquals(Arrays.asList("안"), personal.getWords());
        assertLearning("Saved", false);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("안 ", text());

        compose("ㄱ");
        assertEquals(View.GONE, learningAction().getVisibility());
        compose("가");
        commitComposedAndStart("ㄴ");
        assertEquals(View.GONE, learningAction().getVisibility());
    }

    @Test public void learningRespectsPersonalDictionaryLengthAndCompletedContractions() {
        type("a");
        assertEquals(View.GONE, learningAction().getVisibility());
        type("b");
        assertLearning("Add word", true);
        nonletter('\'');
        assertEquals(View.GONE, learningAction().getVisibility());
        type("c");
        assertLearning("Add word", true);
        controller.cancel();
        setText("");
        for (int i = 0; i < 48; i++) type("a");
        assertLearning("Add word", true);
        type("a");
        assertEquals(View.GONE, learningAction().getVisibility());

        controller.cancel();
        setText("");
        for (int i = 0; i < 21; i++) {
            connection.commitText("각", 1);
            controller.onTypedTextUpdated(true);
        }
        assertLearning("Add word", true);
        connection.commitText("각", 1);
        controller.onTypedTextUpdated(true);
        assertEquals(View.GONE, learningAction().getVisibility());
    }

    @Test public void dictionaryLoadFailureStillOffersAddAndSaveFailureIsNotReportedAsSaved() {
        loader.fail = true;
        type("teh");
        drain();
        assertLearning("Add word", true);
        personalWords.fail = true;
        learn();
        assertLearning("Not saved", false);
        assertTrue(personal.getWords().isEmpty());
        type("s");
        assertLearning("Add word", true);
        personalWords.fail = false;
        learn();
        assertLearning("Saved", false);
        assertEquals(Arrays.asList("tehs"), personal.getWords());
    }

    @Test public void disablingAutomaticCorrectionKeepsSuggestionsAndLearning() {
        controller.setAutoCorrectionEnabled(false);
        type("teh");
        assertLearning("Add word", true);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("teh ", text());
        assertEquals("teh", candidate(0));
        assertEquals("the", candidate(1));
        assertLearning("Add word", true);
        assertTrue(personal.getWords().isEmpty());
        click(1);
        assertEquals("the ", text());
    }

    @Test public void manualSuggestionsDoNotBlockTypingWhileSpaceSearchIsPending() {
        controller.setAutoCorrectionEnabled(false);
        type("teh");
        assertTrue(controller.commitSpace());
        View.OnClickListener oldAction = learningListener();
        assertFalse(controller.deferIfBusy(null));
        type("helo");
        assertEquals("teh helo", text());
        assertLearning("Add word", true);
        oldAction.onClick(learningAction());
        assertTrue(personal.getWords().isEmpty());
        drain();
        assertEquals("teh helo", text());
        assertEquals("helo", candidate(0));
        assertEquals("hello", candidate(1));
        assertLearning("Add word", true);
        TextView target = strip.findViewById(R.id.inline_learning_word);
        assertEquals("helo", target.getText().toString());
    }

    @Test public void teachingAtNonblockingBoundaryInvalidatesItsPendingResult() {
        controller.setAutoCorrectionEnabled(false);
        type("teh");
        assertTrue(controller.commitSpace());
        assertFalse(controller.deferIfBusy(null));
        learn();
        assertEquals("teh ", text());
        assertEquals(Arrays.asList("teh"), personal.getWords());
        drain();
        assertEquals("teh ", text());
        assertEquals(1, strip.getCandidateCount());
        assertEquals("teh", candidate(0));
        assertLearning("Saved", false);
    }

    @Test public void missingExtractedTextStillSupportsSuggestionsLearningAndCorrection() {
        enableFallback();
        typeFallback("teh");
        drain();
        assertEquals("teh", candidate(0));
        assertEquals("the", candidate(1));
        assertLearning("Add word", true);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("the ", text());
        assertLearning("Add word", true);
        learn();
        assertEquals("teh ", text());
        assertEquals(Arrays.asList("teh"), personal.getWords());
        assertLearning("Saved", false);
    }

    @Test public void missingExtractedTextDoesNotTrustChangedTextCaretOrConnection() {
        enableFallback();
        typeFallback("teh");
        View.OnClickListener moved = learningListener();
        Selection.setSelection(editor.getText(), 0);
        moved.onClick(learningAction());
        assertTrue(personal.getWords().isEmpty());

        setText("");
        controller.onStartInput(connection, 0, 0);
        typeFallback("teh");
        View.OnClickListener changed = learningListener();
        editor.getText().replace(0, 3, "xyz");
        changed.onClick(learningAction());
        assertTrue(personal.getWords().isEmpty());

        setText("");
        controller.onStartInput(connection, 0, 0);
        typeFallback("teh");
        View.OnClickListener switched = learningListener();
        EditText other = new EditText(context);
        other.setText("teh");
        Selection.setSelection(other.getText(), 3);
        provider.current = new TestConnection(other);
        switched.onClick(learningAction());
        assertTrue(personal.getWords().isEmpty());
    }

    @Test public void missingExtractedTextNeedsKnownCollapsedSelectionAndBothSides() {
        connection.noExtractedText = true;
        controller.onStartInput(connection, -1, -1);
        typeFallback("teh");
        assertEquals(0, strip.getCandidateCount());
        assertFalse(controller.commitSpace());

        setText("");
        controller.onStartInput(connection, 0, 1);
        typeFallback("teh");
        assertEquals(0, strip.getCandidateCount());
        setText("");
        connection.noAfter = true;
        controller.onStartInput(connection, 0, 0);
        typeFallback("teh");
        assertEquals(0, strip.getCandidateCount());
        connection.noAfter = false;
        setText("");
        connection.noBefore = true;
        controller.onStartInput(connection, 0, 0);
        typeFallback("teh");
        assertEquals(0, strip.getCandidateCount());
    }

    @Test public void fallbackHandlesDelayedCallbacksAndLongContexts() {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 300; i++) prefix.append('x');
        prefix.append(' ');
        setText(prefix.toString());
        connection.noExtractedText = true;
        controller.onStartInput(connection, 301, 301);
        typeFallback("teh");
        controller.onSelectionChanged(301, 301, 302, 302, -1);
        controller.onSelectionChanged(302, 302, 303, 303, -1);
        controller.onSelectionChanged(303, 303, 304, 304, -1);
        drain();
        assertEquals("teh", candidate(0));
        assertLearning("Add word", true);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals(prefix + "the ", text());
    }

    @Test public void fallbackTracksKoreanCompositionAndBackspace() {
        enableFallback();
        controller.beforeManualEdit();
        compose("ㅇ");
        controller.beforeManualEdit();
        compose("아");
        controller.beforeManualEdit();
        compose("안");
        assertLearning("Add word", true);
        controller.beforeManualEdit();
        connection.setComposingText("아", 1);
        controller.onBackspaceTyped(true);
        assertEquals("아", text());
        assertLearning("Add word", true);
        learn();
        assertEquals(Arrays.asList("아"), personal.getWords());
    }

    @Test public void fallbackAcceptsSelectionCallbackDuringManualEdit() {
        enableFallback();
        controller.beforeManualEdit();
        connection.commitText("t", 1);
        controller.onSelectionChanged(0, 0, 1, 1, -1);
        controller.onTypedTextUpdated(false);
        typeFallback("eh");
        assertLearning("Add word", true);
        drain();
        assertEquals("teh", candidate(0));
        assertEquals("the", candidate(1));
    }

    @Test public void untrackedLeadingSpaceRetainsCaretForTheNextWord() {
        enableFallback();
        assertFalse(controller.commitSpace());
        controller.beforeManualEdit();
        connection.commitText(" ", 1);
        controller.onManualSpaceInserted();
        typeFallback("teh");
        controller.onSelectionChanged(0, 0, 4, 4, -1);
        assertLearning("Add word", true);
        drain();
        assertEquals(" teh", text());
        assertEquals("teh", candidate(0));
    }

    @Test public void invalidatingFallbackRequiresANewSelectionReport() {
        enableFallback();
        controller.invalidateEditorPosition();
        typeFallback("x");
        assertEquals(0, strip.getCandidateCount());
        setText("");
        controller.onSelectionChanged(1, 1, 0, 0, -1);
        typeFallback("teh");
        assertLearning("Add word", true);
    }

    private void enableFallback() {
        connection.noExtractedText = true;
        controller.onStartInput(connection, 0, 0);
    }

    private void typeFallback(String letters) {
        for (char c : letters.toCharArray()) {
            controller.beforeManualEdit();
            connection.commitText(String.valueOf(c), 1);
            controller.onTypedTextUpdated(false);
        }
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
    private TextView candidateView(int index) { return strip.getCandidateView(index); }
    private String candidate(int index) { return candidateView(index).getText().toString(); }
    private void click(int index) { candidateView(index).performClick(); }

    private View learningAction() { return strip.findViewById(R.id.inline_learning_action); }
    private void assertLearning(String label, boolean clickable) {
        assertEquals(View.VISIBLE, learningAction().getVisibility());
        TextView text = strip.findViewById(R.id.inline_learning_label);
        assertEquals(label, text.getText().toString());
        assertEquals(clickable, learningAction().isClickable());
    }
    private void learn() {
        assertLearning("Add word", true);
        assertTrue(learningAction().performClick());
    }
    private View.OnClickListener learningListener() {
        View.OnClickListener listener = shadowOf(learningAction()).getOnClickListener();
        assertNotNull(listener);
        return listener;
    }

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
            if (strip.getCandidateCount() > index && expected.equals(candidate(index))) return;
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
        boolean noExtractedText, noBefore, noAfter;
        TestConnection(EditText editor) { super((View) editor, true); this.editor = editor; }
        @Override public Editable getEditable() { return editor.getText(); }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            if (noExtractedText) return null;
            ExtractedText text = new ExtractedText();
            text.selectionStart = Selection.getSelectionStart(editor.getText());
            text.selectionEnd = Selection.getSelectionEnd(editor.getText());
            return text;
        }
        @Override public CharSequence getTextBeforeCursor(int n, int flags) {
            return noBefore ? null : super.getTextBeforeCursor(n, flags);
        }
        @Override public CharSequence getTextAfterCursor(int n, int flags) {
            return noAfter ? null : super.getTextAfterCursor(n, flags);
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
        PersonalDictionary personal;
        @Override public int version() {
            if (failVersion) throw new IllegalStateException("Version unavailable");
            return version + (personal == null ? 0 : personal.getVersion());
        }
        @Override public List<SwipeDecoder.Entry> load(boolean korean) throws IOException {
            loads++;
            if (fail) throw new IOException("Dictionary missing");
            ArrayList<SwipeDecoder.Entry> all = new ArrayList<>(entries);
            if (personal != null) all.addAll(personal.getEntries(korean));
            return all;
        }
    }

    private static final class TestPersonalWords implements AutoCorrectController.PersonalWordStore {
        final PersonalDictionary personal;
        boolean fail;
        TestPersonalWords(PersonalDictionary personal) { this.personal = personal; }
        @Override public boolean contains(String word) {
            for (String saved : personal.getWords()) {
                if (saved.replace('’', '\'').equalsIgnoreCase(word.replace('’', '\''))) return true;
            }
            return false;
        }
        @Override public boolean add(String word) {
            if (fail) throw new IllegalStateException("Simulated saving failure");
            return personal.addWord(word);
        }
    }
}
