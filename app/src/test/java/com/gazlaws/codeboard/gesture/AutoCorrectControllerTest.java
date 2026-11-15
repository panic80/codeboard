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

    @Test public void emailCanBeSavedExplicitlyAndCompletedWithItsOriginalSpelling() {
        String address = "Learner80+class@example.com";
        typeLiteral(address);
        drain();
        assertEquals(address, candidate(0));
        assertLearning("Add word", true);
        assertEquals(address, ((TextView) strip.findViewById(R.id.inline_learning_word)).getText().toString());
        assertTrue(personal.getWords().isEmpty());
        learn();
        assertEquals(Arrays.asList(address), personal.getEmailAddresses());
        assertTrue(personal.getEntries(false).isEmpty());
        assertLearning("Saved", false);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals(address + " ", text());
        assertLearning("Saved", false);

        typeLiteral("learn");
        drain();
        assertEquals("learn", candidate(0));
        assertEquals(address, candidate(1));
        assertEquals(View.GONE, learningAction().getVisibility());
        click(1);
        assertEquals(address + " " + address, text());
        assertLearning("Saved", false);
        assertTrue(controller.commitSpace());
        drain();
        assertEquals(address + " " + address + " ", text());
    }

    @Test public void emailCanBeLearnedAfterSpaceAndTrailingCommaIsPreserved() {
        String address = "learner80@example.com";
        typeLiteral(address + ",");
        assertLearning("Add word", true);
        assertEquals(address, ((TextView) strip.findViewById(R.id.inline_learning_word)).getText().toString());
        assertTrue(controller.commitSpace());
        drain();
        assertEquals(address + ", ", text());
        assertLearning("Add word", true);
        assertTrue(personal.getEmailAddresses().isEmpty());
        learn();
        assertEquals(Arrays.asList(address), personal.getEmailAddresses());
        assertLearning("Saved", false);
        assertFalse(controller.undoCorrection());
    }

    @Test public void emailCallbacksRejectChangedTextCursorAndConnection() {
        String address = "learner80@example.com";
        typeLiteral(address);
        View.OnClickListener staleAdd = learningListener();
        nonletter('x');
        staleAdd.onClick(learningAction());
        assertTrue(personal.getEmailAddresses().isEmpty());

        controller.cancel(); setText("");
        typeLiteral(address);
        staleAdd = learningListener();
        Selection.setSelection(editor.getText(), 1);
        staleAdd.onClick(learningAction());
        assertTrue(personal.getEmailAddresses().isEmpty());

        controller.cancel(); setText("");
        typeLiteral(address);
        staleAdd = learningListener();
        editor.getText().replace(0, 1, "L");
        Selection.setSelection(editor.getText(), editor.length());
        staleAdd.onClick(learningAction());
        assertTrue(personal.getEmailAddresses().isEmpty());

        controller.cancel(); setText("");
        typeLiteral(address);
        staleAdd = learningListener();
        EditText other = new EditText(context);
        other.setText(address);
        Selection.setSelection(other.getText(), other.length());
        provider.current = new TestConnection(other);
        staleAdd.onClick(learningAction());
        assertTrue(personal.getEmailAddresses().isEmpty());
    }

    @Test public void staleEmailCompletionCannotReplaceMovedOrChangedToken() {
        String address = "learner80@example.com";
        assertTrue(personal.addWord(address));
        typeLiteral("learn");
        View.OnClickListener stale = shadowOf(candidateView(1)).getOnClickListener();
        typeLiteral("e");
        stale.onClick(candidateView(1));
        assertEquals("learne", text());

        View.OnClickListener moved = shadowOf(candidateView(1)).getOnClickListener();
        Selection.setSelection(editor.getText(), 1);
        moved.onClick(candidateView(1));
        assertEquals("learne", text());
        controller.onSelectionChanged(6, 6, 1, 1, -1);
        assertEquals(0, strip.getCandidateCount());
    }

    @Test public void emailCompletionRechecksSurroundingTextAndConnection() {
        String address = "learner80@example.com";
        assertTrue(personal.addWord(address));
        typeLiteral("learn");
        View.OnClickListener changed = shadowOf(candidateView(1)).getOnClickListener();
        editor.getText().replace(0, 1, "L");
        Selection.setSelection(editor.getText(), editor.length());
        changed.onClick(candidateView(1));
        assertEquals("Learn", text());

        controller.cancel(); setText("");
        typeLiteral("learn");
        View.OnClickListener switched = shadowOf(candidateView(1)).getOnClickListener();
        EditText other = new EditText(context);
        other.setText("learn");
        Selection.setSelection(other.getText(), other.length());
        provider.current = new TestConnection(other);
        switched.onClick(candidateView(1));
        assertEquals("learn", other.getText().toString());
        assertEquals("learn", text());
    }

    @Test public void emailLearningAfterSpaceRejectsAChangedContext() {
        typeLiteral("learner80@example.com");
        assertTrue(controller.commitSpace());
        View.OnClickListener stale = learningListener();
        editor.getText().replace(0, 1, "L");
        Selection.setSelection(editor.getText(), editor.length());
        stale.onClick(learningAction());
        assertTrue(personal.getEmailAddresses().isEmpty());
    }

    @Test public void replacingTheStripInvalidatesOldEmailActions() {
        String address = "learner80@example.com";
        typeLiteral(address);
        View.OnClickListener stale = learningListener();
        View oldAction = learningAction();
        controller.setSuggestionStrip(null);
        stale.onClick(oldAction);
        assertTrue(personal.getEmailAddresses().isEmpty());
        controller.setSuggestionStrip(strip);
        assertTrue(controller.commitSpace());
        View.OnClickListener staleBoundary = learningListener();
        controller.setSuggestionStrip(null);
        staleBoundary.onClick(oldAction);
        assertTrue(personal.getEmailAddresses().isEmpty());
        controller.setSuggestionStrip(strip);
        learn();
        assertEquals(Arrays.asList(address), personal.getEmailAddresses());
    }

    @Test public void verifiedCursorUsesExtractedOffsetAndRejectsRangesOrMissingContext() {
        setText("abcdefgh");
        connection.extractOffset = 5;
        assertEquals(8, controller.getVerifiedCursorPosition());
        Selection.setSelection(editor.getText(), 6, 8);
        assertEquals(-1, controller.getVerifiedCursorPosition());
        Selection.setSelection(editor.getText(), 8);
        connection.noAfter = true;
        assertEquals(-1, controller.getVerifiedCursorPosition());
        connection.noAfter = false;
        assertEquals(8, controller.getVerifiedCursorPosition());
    }

    @Test public void verifiedFallbackCursorDoesNotMutateTrackingWhenContextIsUnavailable() {
        connection.noExtractedText = true;
        assertEquals(-1, controller.getVerifiedCursorPosition());
        controller.onStartInput(connection, 0, 0);
        typeLiteral("learn");
        assertEquals(5, controller.getVerifiedCursorPosition());
        connection.noBefore = true;
        assertEquals(-1, controller.getVerifiedCursorPosition());
        connection.noBefore = false;
        assertEquals(5, controller.getVerifiedCursorPosition());
        editor.getText().replace(0, 1, "L");
        Selection.setSelection(editor.getText(), 5);
        assertEquals(-1, controller.getVerifiedCursorPosition());
        editor.getText().replace(0, 1, "l");
        Selection.setSelection(editor.getText(), 5);
        assertEquals(5, controller.getVerifiedCursorPosition());
    }

    @Test public void trackedSpaceCursorFollowsVerifiedLengthChangingCorrection() {
        enableFallback();
        controller.setUseTopSuggestion(true);
        assertEquals(-1, controller.getTrackedSpaceCursorPosition());
        typeFallback("helo");
        assertTrue(controller.commitSpace());
        assertEquals(5, controller.getTrackedSpaceCursorPosition());
        drain();
        assertEquals("hello ", text());
        assertEquals(6, controller.getTrackedSpaceCursorPosition());
        Selection.setSelection(editor.getText(), 1);
        assertEquals(-1, controller.getTrackedSpaceCursorPosition());
        Selection.setSelection(editor.getText(), editor.length());
        assertEquals(6, controller.getTrackedSpaceCursorPosition());
        loader.version++;
        assertEquals(-1, controller.getTrackedSpaceCursorPosition());
    }

    @Test public void literalInputRequiresManualBoundaryAndRejectsCodeAndUrls() {
        assertTrue(personal.addWord("learner80@example.com"));
        setText("learner80@example.");
        typeLiteral("com");
        assertEquals(View.GONE, learningAction().getVisibility());
        assertEquals(0, strip.getCandidateCount());
        for (String token : Arrays.asList("url:https://learner80@example.com",
                "name:learner80@example.com", "learner80@example")) {
            controller.cancel(); setText("");
            typeLiteral(token);
            assertEquals(token, View.GONE, learningAction().getVisibility());
            assertFalse(token, controller.commitSpace());
        }
        assertEquals(Arrays.asList("learner80@example.com"), personal.getEmailAddresses());
    }

    @Test public void literalBackspaceAndFallbackRetainOnlyVerifiedInput() {
        enableFallback();
        String address = "learner80@example.com";
        typeLiteral(address);
        assertLearning("Add word", true);
        View.OnClickListener stale = learningListener();
        controller.beforeManualEdit();
        connection.deleteSurroundingText(1, 0);
        controller.onBackspaceTyped(false);
        stale.onClick(learningAction());
        assertTrue(personal.getEmailAddresses().isEmpty());
        assertLearning("Add word", true); // The shorter .co domain is also a complete address.
        typeLiteral("m");
        assertLearning("Add word", true);
        learn();
        assertEquals(Arrays.asList(address), personal.getEmailAddresses());
        assertTrue(controller.commitSpace());
        typeLiteral("learner80@");
        assertEquals(address, candidate(1));
        click(1);
        assertEquals(address + " " + address, text());
    }

    @Test public void emailOnlyModeDoesNotOfferOrCorrectOrdinaryWords() {
        controller.setEmailOnly(true);
        typeLiteral("teh");
        drain();
        assertEquals(0, strip.getCandidateCount());
        assertEquals(View.GONE, learningAction().getVisibility());
        assertFalse(controller.commitSpace());
        controller.beforeManualEdit();
        connection.commitText(" ", 1);
        controller.onManualSpaceInserted();
        typeLiteral("learner80@example.com");
        assertLearning("Add word", true);
        assertTrue(controller.commitSpace());
        assertEquals("teh learner80@example.com ", text());
    }

    @Test public void pageChangesPreserveVerifiedEmailAndInvalidateWordActions() {
        enableFallback();
        typeLiteral("learner");
        View.OnClickListener staleWordAction = learningListener();
        View action = learningAction();
        controller.onKeyboardPageChanged();
        assertEquals(View.GONE, action.getVisibility());
        staleWordAction.onClick(action);
        assertTrue(personal.getWords().isEmpty());

        typeLiteral("80@");
        controller.onKeyboardPageChanged();
        typeLiteral("example");
        controller.onKeyboardPageChanged();
        typeLiteral(".");
        controller.onKeyboardPageChanged();
        typeLiteral("com");
        assertEquals(21, controller.getVerifiedCursorPosition());
        assertLearning("Add word", true);
        learn();
        assertEquals(Arrays.asList("learner80@example.com"), personal.getEmailAddresses());
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("learner80@example.com ", text());
    }

    @Test public void pageChangesRebuildEmailCompletionsAndRejectStaleActions() {
        String address = "learner80@example.com";
        assertTrue(personal.addWord(address));
        typeLiteral("learn");
        View.OnClickListener staleCandidate = shadowOf(candidateView(1)).getOnClickListener();
        View oldCandidate = candidateView(1);
        controller.onKeyboardPageChanged();
        assertEquals(address, candidate(1));
        staleCandidate.onClick(oldCandidate);
        assertEquals("learn", text());
        click(1);
        assertEquals(address, text());

        controller.cancel(); setText("");
        typeLiteral("learner81@example.com");
        View.OnClickListener staleEmailAction = learningListener();
        editor.getText().replace(0, 1, "L");
        Selection.setSelection(editor.getText(), editor.length());
        controller.onKeyboardPageChanged();
        staleEmailAction.onClick(learningAction());
        assertEquals(View.GONE, learningAction().getVisibility());
        assertEquals(0, strip.getCandidateCount());
        assertEquals(Arrays.asList(address), personal.getEmailAddresses());
    }

    @Test public void ownedReplacementPreservesDeferredInputAndFallbackCursor() {
        enableFallback();
        typeFallback("teh");
        assertTrue(controller.commitSpace());
        assertTrue(controller.deferIfBusy(() -> {
            controller.beforeManualEdit();
            assertTrue(connection.deleteSurroundingText(1, 0));
            assertTrue(connection.commitText(". ", 1));
            controller.onManualTextReplaced(" ", ". ");
            assertEquals(5, controller.getVerifiedCursorPosition());
        }));
        assertTrue(controller.deferIfBusy(() -> typeFallback("world")));
        drain();
        assertEquals("the. world", text());
        assertEquals(10, controller.getVerifiedCursorPosition());
        assertLearning("Add word", true);
    }

    @Test public void manualSpacePreservesDeferredInputAndFallbackCursor() {
        enableFallback();
        typeFallback("teh");
        assertTrue(controller.commitSpace());
        assertTrue(controller.deferIfBusy(() -> {
            assertFalse(controller.commitSpace());
            controller.beforeManualEdit();
            assertTrue(connection.commitText(" ", 1));
            controller.onManualSpaceInserted();
        }));
        assertTrue(controller.deferIfBusy(() -> typeFallback("world")));
        drain();
        assertEquals("the  world", text());
        assertEquals(10, controller.getVerifiedCursorPosition());
        assertLearning("Add word", true);
    }

    @Test public void topSuggestionModeIsOptionalAndRespectsAutomaticCorrectionSetting() {
        loader.entries.add(new SwipeDecoder.Entry("help", 3000));
        controller.setUseTopSuggestion(true);
        type("hellp");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("hello ", text());

        controller.setAutoCorrectionEnabled(false);
        type("hellp");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("hello hellp ", text());
    }

    @Test public void strongContextCorrectsARealWordAndUndoRestoresTheOriginal() {
        installContextFixture();
        controller.setUseTopSuggestion(true);
        setText("it is ");
        type("bot");
        drain();
        assertEquals("bot", candidate(0));
        assertEquals("not", candidate(1));
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("it is not ", text());
        assertTrue(controller.undoCorrection());
        assertEquals("it is bot ", text());
    }

    @Test public void savingAContextCorrectedWordRestoresAndProtectsIt() {
        installContextFixture();
        controller.setUseTopSuggestion(true);
        setText("is ");
        type("bot");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("is not ", text());
        learn();
        assertEquals("is bot ", text());
        assertEquals(Arrays.asList("bot"), personal.getWords());
        controller.cancel();
        setText("is ");
        type("bot");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("is bot ", text());
    }

    @Test public void exactWordContextCorrectionRespectsModesAndExplicitChoice() {
        installContextFixture();
        setText("is ");
        type("bot");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("is bot ", text()); // Conservative mode keeps dictionary words.

        controller.setUseTopSuggestion(true);
        controller.setAutoCorrectionEnabled(false);
        setText("is ");
        type("bot");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("is bot ", text());

        controller.setAutoCorrectionEnabled(true);
        setText("the ");
        type("bot");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("the bot ", text()); // An ordinary use has no contrary evidence.

        controller.cancel();
        setText("is ");
        type("bot");
        drain();
        click(0); // Explicitly keep the original even though "not" is suggested.
        assertFalse(controller.commitSpace());
        connection.commitText(" ", 1);
        assertEquals("is bot ", text());
    }

    @Test public void manualContextAlternativeWorksWhenAutomaticCorrectionIsOff() {
        installContextFixture();
        controller.setUseTopSuggestion(true);
        controller.setAutoCorrectionEnabled(false);
        setText("is ");
        type("bot");
        assertTrue(controller.commitSpace());
        assertFalse(controller.deferIfBusy(null));
        drain();
        assertEquals("is bot ", text());
        int alternative = candidateIndex("not");
        assertTrue("the contextual alternative must be selectable", alternative >= 0);
        click(alternative);
        assertEquals("is not ", text());
        assertFalse(controller.undoCorrection()); // The replacement was a deliberate choice.
    }

    @Test public void weakContextOffersAnAlternativeButDoesNotReplaceTheTypedWord() {
        loader.entries.add(new SwipeDecoder.Entry("meat", 33050));
        loader.entries.add(new SwipeDecoder.Entry("meet", 247466));
        loader.contextModel = new TypingCorrector.ContextModel() {
            @Override public TypingCorrector.ContextEvidence score(String previous, String candidate) {
                return new TypingCorrector.ContextEvidence(
                        previous.endsWith("will ") && candidate.equals("meet") ? 224 : 0, 0);
            }
            @Override public long bigramOmittedUpperBound() { return 210; }
            @Override public long trigramOmittedUpperBound() { return 130; }
        };
        controller.setUseTopSuggestion(true);
        setText("I will ");
        type("meat");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("I will meat ", text());
        int alternative = candidateIndex("meet");
        assertTrue("meet must be offered for a deliberate choice", alternative >= 0);
        click(alternative);
        assertEquals("I will meet ", text());
    }

    @Test public void queuedInputRemainsInOrderAfterKnownWordCorrection() {
        installContextFixture();
        controller.setUseTopSuggestion(true);
        setText("is ");
        type("bot");
        assertTrue(controller.commitSpace());
        assertTrue(controller.deferIfBusy(() -> type("fine")));
        drain();
        assertEquals("is not fine", text());
    }

    @Test public void changingOnlyTheContextDuringSpaceSearchRejectsTheOldResult() {
        installContextFixture();
        controller.setUseTopSuggestion(true);
        setText("is ");
        type("bot");
        assertTrue(controller.commitSpace());
        boolean[] ran = {false};
        assertTrue(controller.deferIfBusy(() -> ran[0] = true));
        setText("us bot "); // Same caret and target, changed earlier text without a callback.
        drain();
        assertEquals("us bot ", text());
        assertFalse(ran[0]);
        assertFalse(controller.undoCorrection());
    }

    @Test public void unavailableContextFallsBackToNormalSpelling() {
        controller.setUseTopSuggestion(true);
        loader.entries.add(new SwipeDecoder.Entry("help", 3000));
        loader.contextModel = (previous, candidate) -> { throw new IllegalStateException("unavailable"); };
        setText("is ");
        type("hellp");
        assertTrue(controller.commitSpace());
        drain();
        assertEquals("is hello ", text());
    }

    private void installContextFixture() {
        loader.entries.add(new SwipeDecoder.Entry("bot", 100));
        loader.entries.add(new SwipeDecoder.Entry("not", 100000));
        loader.contextModel = new TypingCorrector.ContextModel() {
            @Override public TypingCorrector.ContextEvidence score(String previous, String candidate) {
                return new TypingCorrector.ContextEvidence(
                        previous.endsWith("is ") && candidate.equals("not") ? 10000 : 0, 0);
            }
            @Override public long bigramOmittedUpperBound() { return 210; }
            @Override public long trigramOmittedUpperBound() { return 130; }
        };
    }

    private void typeLiteral(String token) {
        for (char c : token.toCharArray()) {
            controller.beforeManualEdit();
            connection.commitText(String.valueOf(c), 1);
            if (Character.isLetter(c)) controller.onTypedTextUpdated(false);
            else controller.onNonLetterTyped();
        }
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
    private int candidateIndex(String expected) {
        for (int i = 0; i < strip.getCandidateCount(); i++) {
            if (expected.equals(candidate(i))) return i;
        }
        return -1;
    }
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
        int extractOffset;
        TestConnection(EditText editor) { super((View) editor, true); this.editor = editor; }
        @Override public Editable getEditable() { return editor.getText(); }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            if (noExtractedText) return null;
            ExtractedText text = new ExtractedText();
            text.startOffset = extractOffset;
            text.selectionStart = Selection.getSelectionStart(editor.getText()) - extractOffset;
            text.selectionEnd = Selection.getSelectionEnd(editor.getText()) - extractOffset;
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
        TypingCorrector.ContextModel contextModel;
        @Override public TypingCorrector.ContextModel loadContext() { return contextModel; }
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
        @Override public boolean contains(String word) { return personal.contains(word); }
        @Override public boolean add(String word) {
            if (fail) throw new IllegalStateException("Simulated saving failure");
            return personal.addWord(word);
        }
        @Override public List<String> getEmailAddresses() { return personal.getEmailAddresses(); }
    }
}
