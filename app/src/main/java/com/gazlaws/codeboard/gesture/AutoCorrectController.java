package com.gazlaws.codeboard.gesture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import com.gazlaws.codeboard.layout.ui.SuggestionStripView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Tracks words typed at the keyboard, and corrects them only at a space boundary. */
public final class AutoCorrectController {
    private static final String TAG = "AutoCorrectController";
    private static final int CONTEXT_LIMIT = 256;
    private static final int PREFIX_LIMIT = 32;
    private static final int WORD_LIMIT = 80;

    public interface ConnectionProvider { InputConnection get(); }

    interface DictionaryLoader {
        List<SwipeDecoder.Entry> load(boolean korean) throws IOException;
        default int version() { return 0; }
        default TypingCorrector.ContextModel loadContext() throws IOException { return null; }
        default TypingCorrector.ContextModel loadContext(boolean korean) throws IOException { return korean ? null : loadContext(); }
    }

    interface PersonalWordStore {
        boolean contains(String word);
        boolean add(String word);
        default List<String> getEmailAddresses() { return Collections.emptyList(); }
    }

    private final ConnectionProvider connections;
    private final Runnable beforeEdit;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private final Handler main;
    private final DictionaryLoader loader;
    private final PersonalWordStore personalWords;
    private final ArrayDeque<Runnable> deferred = new ArrayDeque<>();
    private final Object requestLock = new Object();
    private Request queuedSuggestion;
    private boolean suggestionScheduled;
    // Only the worker accesses these caches.
    private final TypingCorrector[] engines = new TypingCorrector[2];
    private final int[] engineVersions = {Integer.MIN_VALUE, Integer.MIN_VALUE};
    private TypingCorrector.ContextModel contextModel;
    private boolean contextModelLoaded;
    private TypingCorrector.ContextModel koreanContextModel;
    private boolean koreanContextLoaded;
    private AdaptiveTypingModel adaptive;
    private TouchModel touchModel;
    private volatile boolean learningEnabled, touchEnabled;
    private volatile int adaptationEpoch;
    private final ArrayList<TouchModel.Sample> taps = new ArrayList<>();
    private TouchModel.Sample pendingTap;
    private final BoundarySuggestions[] boundaries = new BoundarySuggestions[2];

    private SuggestionStripView strip;
    private InputConnection wordConnection;
    private String word;
    private String punctuation = "";
    private String prefix;
    private boolean korean;
    private boolean blocked;
    private Snapshot blockedSelection;
    private Snapshot wordSelection;
    private Boundary boundary;
    private boolean busy;
    private boolean closed;
    private boolean applyingEdit;
    private boolean autoCorrectionEnabled = true;
    private boolean useTopSuggestion;
    private boolean emailOnly;
    private boolean wordProtected;
    private long generation;
    // Email addresses are literal entries, never spelling-dictionary words. Track only input
    // entered one character at a time at a verified token boundary.
    private Literal literal;
    private LiteralBoundary literalBoundary;
    private boolean literalVisible;
    // Some editors support surrounding text but not getExtractedText(). In those editors we
    // retain a caret only while the editor and its surrounding text remain accounted for.
    private InputConnection fallbackConnection;
    private Snapshot fallbackSelection;
    private Surrounding fallbackSurrounding;
    private ManualEdit manualEdit;
    private ChosenWord chosenWord;
    private ChosenWord pendingChosenSpace;
    private final ArrayDeque<FallbackTransition> pendingFallbackTransitions = new ArrayDeque<>();

    public AutoCorrectController(Context context, ConnectionProvider connections, Runnable beforeEdit) {
        this(connections, beforeEdit, Executors.newSingleThreadExecutor(), null,
                assetLoader(context.getApplicationContext()), new Handler(Looper.getMainLooper()),
                personalWordStore(new PersonalDictionary(context.getApplicationContext())));
        adaptive = new AdaptiveTypingModel(context);
        touchModel = new TouchModel(context);
    }

    /** Package-private dependencies permit deterministic testing of work and cancellation. */
    AutoCorrectController(ConnectionProvider connections, Runnable beforeEdit, Executor worker,
                          ExecutorService ownedWorker, DictionaryLoader loader, Handler main) {
        this(connections, beforeEdit, worker, ownedWorker, loader, main, null);
    }

    AutoCorrectController(ConnectionProvider connections, Runnable beforeEdit, Executor worker,
                          ExecutorService ownedWorker, DictionaryLoader loader, Handler main,
                          PersonalWordStore personalWords) {
        if (connections == null || beforeEdit == null || worker == null || loader == null || main == null) {
            throw new IllegalArgumentException("Controller dependencies must not be null");
        }
        this.connections = connections;
        this.beforeEdit = beforeEdit;
        this.worker = worker;
        this.ownedWorker = ownedWorker != null ? ownedWorker :
                (worker instanceof ExecutorService ? (ExecutorService) worker : null);
        this.loader = loader;
        this.main = main;
        this.personalWords = personalWords;
    }

    public void setLearningEnabled(boolean enabled, boolean touch) {
        if (learningEnabled == enabled && touchEnabled == touch) return;
        learningEnabled = enabled; touchEnabled = touch;
        adaptationEpoch++;
        cancel();
    }

    public void recordTap(TouchModel.Sample sample) { pendingTap = touchEnabled ? sample : null; }

    private void confirmChoice(String previous, String original, String chosen, List<TouchModel.Sample> samples) {
        if (learningEnabled && adaptive != null) adaptive.choose(previous, original, chosen, knownWord(chosen));
        if (touchEnabled && touchModel != null) touchModel.learn(samples, chosen);
    }

    private boolean knownWord(String word) {
        int language = word != null && !word.isEmpty() && word.charAt(0) >= 0xac00 ? 1 : 0;
        BoundarySuggestions dictionary = boundaries[language];
        return dictionary != null && dictionary.knows(word);
    }

    public void setSuggestionStrip(SuggestionStripView strip) {
        if (this.strip != null && this.strip != strip) {
            clearSuggestions();
            if (literal != null || literalBoundary != null) {
                generation++;
                cancelQueuedSuggestions();
                if (literalBoundary != null) literalBoundary.token = generation;
            }
        }
        this.strip = strip;
        if (strip != null && literalBoundary != null && validLiteralBoundary(literalBoundary)) {
            showLiteralBoundary(literalBoundary);
        } else if (strip != null && literal != null && validLiteralText(readContext(literal.connection)) &&
                showLiteralSuggestions()) {
            // The literal row takes precedence over word suggestions for this token.
        } else if (strip != null && word != null && validTrackedText(readContext(wordConnection))) {
            requestSuggestions();
        } else if (strip != null && boundary != null && validBoundaryLocation(boundary)) {
            showBoundaryCandidates(boundary);
            if (!boundary.pending && boundary.predictions.isEmpty()) requestBoundaryPredictions(boundary);
        }
    }

    /** Seeds a caret reported by EditorInfo for an editor that cannot return extracted text. */
    public void onStartInput(InputConnection connection, int start, int end) {
        cancel();
        clearFallback();
        if (closed || connection == null || connection != connections.get() || start < 0 || start != end) return;
        Surrounding surrounding = readSurrounding(connection);
        if (!fitsSelection(surrounding, start)) return;
        setFallback(connection, start, surrounding);
    }

    /** Returns the verified collapsed cursor position, or -1 when the editor is unknown. */
    public int getVerifiedCursorPosition() {
        if (closed) return -1;
        InputConnection connection = connections.get();
        SelectionRead selection = readSelection(connection);
        Surrounding surrounding = readSurrounding(connection);
        if (selection == null || selection.snapshot == null || surrounding == null ||
                selection.snapshot.offset < 0 || connection != connections.get()) return -1;
        long position = (long) selection.snapshot.offset + selection.snapshot.start;
        if (position < 0 || position > Integer.MAX_VALUE) return -1;
        if (selection.fallback && (!surrounding.equals(fallbackSurrounding) ||
                !fitsSelection(surrounding, (int) position))) return -1;
        return (int) position;
    }

    /** Returns the verified caret after the current word boundary, including any correction. */
    public int getTrackedSpaceCursorPosition() {
        // A prediction inserts its own space, but it must not extend the IME's timer for
        // a preceding keyboard Space. Otherwise the next tap could become a period.
        if (closed || boundary == null || !boundary.typedSpace) return -1;
        Boundary current = boundary;
        int position = getVerifiedCursorPosition();
        return position >= 0 && boundary == current && validBoundary(current) ? position : -1;
    }

    /** Records the verified editor context immediately before a manual keyboard edit. */
    public void beforeManualEdit() {
        manualEdit = null;
        InputConnection connection = connections.get();
        if (closed || connection == null || connection != fallbackConnection || fallbackSelection == null) return;
        ContextText current = readContext(connection);
        if (current == null) {
            clearFallback();
            return;
        }
        // If extracted selection is available it is authoritative, including its document offset.
        int position = current.selection.offset + current.selection.start;
        Surrounding surrounding = new Surrounding(current.before, current.after);
        if (!fitsSelection(surrounding, position)) {
            clearFallback();
            return;
        }
        setFallback(connection, position, surrounding);
        manualEdit = new ManualEdit(connection, position, surrounding);
    }

    /** Discards the caret before an editor mutation that the controller does not track. */
    public void invalidateEditorPosition() {
        cancel();
        clearFallback();
    }

    /** Completes a normal space that the caller inserted after commitSpace returned false. */
    public void onManualSpaceInserted() {
        if (closed) return;
        ChosenWord chosen = pendingChosenSpace;
        finishManualEdit("", " ");
        clearAfterOwnedEdit();
        if (chosen == null || strip == null || chosen.connection != connections.get() ||
                chosen.version == Integer.MIN_VALUE || chosen.version != dictionaryVersion()) return;
        ContextText after = readContext(chosen.connection);
        String expected = tail(chosen.previousContext + chosen.word + chosen.punctuation + " ",
                CONTEXT_LIMIT);
        if (after == null || !validPosition(chosen.selection, after.selection, false) ||
                !after.before.endsWith(expected) || !safeAfter(after.after)) return;
        Boundary next = new Boundary(chosen.connection, chosen.word, chosen.punctuation,
                chosen.previousContext, chosen.korean, isPersonalWord(chosen.word),
                after.selection, chosen.version, generation);
        boundary = next;
        showBoundaryCandidates(next);
        requestBoundaryPredictions(next);
    }

    /** Accounts for a keyboard-owned replacement while preserving queued keyboard events. */
    public void onManualTextReplaced(String removed, String inserted) {
        if (closed) return;
        finishManualEdit(removed, inserted);
        clearAfterOwnedEdit();
    }

    private void clearAfterOwnedEdit() {
        generation++;
        cancelQueuedSuggestions();
        clearWord();
        clearLiteral();
        literalBoundary = null;
        chosenWord = null;
        pendingChosenSpace = null;
        blocked = false;
        blockedSelection = null;
        boundary = null;
        busy = false;
        clearSuggestions();
    }

    /** Controls automatic replacement without disabling manual suggestions or learning. */
    public void setAutoCorrectionEnabled(boolean enabled) {
        if (autoCorrectionEnabled == enabled) return;
        autoCorrectionEnabled = enabled;
        cancel();
    }

    /** Uses the highest eligible suggestion at a space instead of the conservative correction. */
    public void setUseTopSuggestion(boolean enabled) {
        if (useTopSuggestion == enabled) return;
        useTopSuggestion = enabled;
        cancel();
    }

    /** Limits assistance to saved email literals in fields intended for email input. */
    public void setEmailOnly(boolean enabled) {
        if (emailOnly == enabled) return;
        emailOnly = enabled;
        cancel();
    }

    /** Keeps a verified literal when switching keyboard pages, but discards word state. */
    public void onKeyboardPageChanged() {
        if (closed) return;
        generation++;
        cancelQueuedSuggestions();
        clearWord();
        chosenWord = null;
        pendingChosenSpace = null;
        blocked = false;
        blockedSelection = null;
        boundary = null;
        literalBoundary = null;
        busy = false;
        deferred.clear();
        manualEdit = null;
        if (literal != null && !validLiteralText(readContext(literal.connection))) clearLiteral();
        clearSuggestions();
        showLiteralSuggestions();
    }

    /** Ordinary keyboard input must wait while the preceding space is being resolved. */
    public boolean deferIfBusy(Runnable event) {
        if (!busy || closed) return false;
        if (event != null) deferred.addLast(event);
        return true;
    }

    /** Called immediately after the IME inserts a manual letter or updates a Hangul syllable. */
    public void onTypedTextUpdated(boolean korean) {
        if (closed) return;
        chosenWord = null;
        pendingChosenSpace = null;
        finishManualTyping(korean);
        invalidateBoundary();
        if (word == null) taps.clear();
        if (pendingTap != null && taps.size() < 64) taps.add(pendingTap);
        pendingTap = null;
        trackLiteralTyping(korean);
        if (!emailOnly) updateWordAfterTyping(korean);
        refreshLiteralSuggestions();
    }

    private void updateWordAfterTyping(boolean korean) {
        if (blocked) { blockedSelection = selection(connections.get()); return; }
        InputConnection connection = connections.get();
        ContextText text = readContext(connection);
        if (text == null || !safeAfter(text.after)) { blockWord(); return; }
        String current = letterSuffix(text.before);
        if (word == null) {
            int start = text.before.length() - current.length();
            if (current.codePointCount(0, current.length()) != 1 ||
                    (start > 0 && !Character.isWhitespace(text.before.codePointBefore(start))) ||
                    !validWord(current, korean)) { blockWord(); return; }
            wordConnection = connection;
            this.korean = korean;
            prefix = tail(text.before.substring(0, start), PREFIX_LIMIT);
        } else {
            boolean apostrophe = !korean && (punctuation.equals("'") || punctuation.equals("’"));
            if (apostrophe) current = word + punctuation + current;
            if (connection != wordConnection || korean != this.korean ||
                    (!punctuation.isEmpty() && !apostrophe) ||
                    !samePrefix(text.before, current, prefix) || !validWord(current, korean) ||
                    !(apostrophe ? current.length() == word.length() + 2 : validLetterUpdate(word, current, korean)) ||
                    !validPosition(wordSelection, text.selection, korean)) {
                blockWord(); return;
            }
            punctuation = "";
        }
        if (!current.equals(word)) wordProtected = false;
        word = current;
        wordSelection = text.selection;
        requestSuggestions();
    }

    /** Called immediately after a manually entered nonletter. Sentence punctuation is retained. */
    public void onNonLetterTyped() {
        if (closed) return;
        chosenWord = null;
        pendingChosenSpace = null;
        finishManualTyping(false);
        invalidateBoundary();
        trackLiteralTyping(false);
        if (!emailOnly) updateWordAfterNonLetter();
        refreshLiteralSuggestions();
    }

    private void updateWordAfterNonLetter() {
        if (blocked) { blockedSelection = selection(connections.get()); return; }
        ContextText text = readContext(connections.get());
        String expected = word == null ? null : word + punctuation;
        if (text == null || wordConnection != connections.get() || expected == null ||
                text.before.length() <= expected.length() ||
                !samePrefix(text.before, expected + text.before.substring(text.before.length() - 1), prefix)) {
            blockWord(); return;
        }
        char last = text.before.charAt(text.before.length() - 1);
        if (".,!?;:'’".indexOf(last) < 0 || !text.before.endsWith(expected + last) ||
                !safeAfter(text.after) || !validPosition(wordSelection, text.selection, false)) {
            blockWord(); return;
        }
        punctuation += last;
        wordSelection = text.selection;
        requestSuggestions();
    }

    /** Called after ordinary backspace, including Hangul's intermediate composing updates. */
    public void onBackspaceTyped(boolean korean) {
        if (closed) return;
        chosenWord = null;
        pendingChosenSpace = null;
        finishManualBackspace(korean);
        if (!taps.isEmpty()) taps.remove(taps.size() - 1);
        pendingTap = null;
        invalidateBoundary();
        trackLiteralBackspace(korean);
        if (!emailOnly) updateWordAfterBackspace(korean);
        refreshLiteralSuggestions();
    }

    private void updateWordAfterBackspace(boolean korean) {
        if (blocked) { blockedSelection = selection(connections.get()); return; }
        if (word == null || this.korean != korean) return;
        ContextText text = readContext(connections.get());
        if (text == null || connections.get() != wordConnection || !safeAfter(text.after) ||
                !validBackspacePosition(wordSelection, text.selection, korean && punctuation.isEmpty())) {
            blockWord(); return;
        }
        if (!punctuation.isEmpty()) {
            String shortened = punctuation.substring(0, punctuation.length() - 1);
            String expected = word + shortened;
            if (!text.before.endsWith(expected) || !samePrefix(text.before, expected, prefix)) {
                blockWord(); return;
            }
            punctuation = shortened;
        } else {
            String current = letterSuffix(text.before);
            if (current.isEmpty() && text.before.endsWith(prefix)) {
                clearWord(); blocked = false; clearSuggestions(); return;
            }
            if (!samePrefix(text.before, current, prefix) || !validWord(current, korean) ||
                    !validBackspaceUpdate(word, current, korean)) { blockWord(); return; }
            if (!current.equals(word)) wordProtected = false;
            word = current;
        }
        wordSelection = text.selection;
        requestSuggestions();
    }

    /** Inserts the space immediately and resolves a tracked word in the background. */
    public boolean commitSpace() {
        if (closed) return false;
        InputConnection connection = connections.get();
        ContextText text = readContext(connection);
        if (literal != null && validLiteralText(text) &&
                PersonalDictionary.isValidEmailAddress(literal.value)) {
            return commitLiteralSpace(connection);
        }
        clearLiteral();
        if (emailOnly) { invalidateBoundary(); clearSuggestions(); return false; }
        if (blocked && chosenWord != null && strip != null && validChosenWord(chosenWord)) {
            // Keep the established manual-space path for an explicitly chosen candidate.
            pendingChosenSpace = chosenWord;
            chosenWord = null;
            blocked = false;
            blockedSelection = null;
            invalidateBoundary();
            return false;
        }
        chosenWord = null;
        pendingChosenSpace = null;
        if (blocked || word == null || connection != wordConnection || text == null ||
                !validTrackedText(text)) {
            clearWord(); blocked = false; blockedSelection = null; invalidateBoundary(); return false;
        }
        String original = word, suffix = punctuation;
        List<TouchModel.Sample> committedTaps = new ArrayList<>(taps);
        String previousContext = precedingText(text.before, original + suffix);
        boolean language = korean;
        boolean protectedWord = wordProtected || isPersonalWord(original);
        boolean observed = !autoCorrectionEnabled && learningEnabled && adaptive != null;
        int version = dictionaryVersion();
        long token = ++generation;
        cancelQueuedSuggestions();
        beforeManualEdit();
        applyingEdit = true;
        boolean inserted;
        try { inserted = connection.commitText(" ", 1); }
        finally { applyingEdit = false; }
        finishManualEdit("", inserted ? " " : "");
        clearWord(); blocked = false; blockedSelection = null;
        clearSuggestions();
        if (!inserted) return false;
        ContextText after = readContext(connection);
        if (after == null || !after.before.endsWith(original + suffix + " ")) return true;
        if (observed) { adaptive.observe(previousContext, original, false, knownWord(original)); version = dictionaryVersion(); }
        Boundary next = new Boundary(connection, original, suffix, previousContext, language, protectedWord,
                after.selection, version, token);
        boundary = next;
        next.taps = committedTaps;
        next.observed = observed;
        next.pending = version != Integer.MIN_VALUE;
        // Manual suggestions cannot rewrite the editor, so typing need not wait for their search.
        busy = next.pending && autoCorrectionEnabled;
        showBoundaryCandidates(next);
        if (!next.pending) return true;
        final int searchVersion = version;
        try {
            worker.execute(() -> {
                TypingCorrector.Result result = compute(original, language, searchVersion, previousContext, false, committedTaps);
                main.post(() -> finishBoundary(next, token, result));
            });
        } catch (RejectedExecutionException e) {
            next.pending = false;
            busy = false;
            if (validBoundaryLocation(next)) flushDeferred();
            else { boundary = null; clearSuggestions(); deferred.clear(); }
        }
        return true;
    }

    /** Reverts the most recent automatic replacement while retaining its typed space. */
    public boolean undoCorrection() {
        Boundary current = boundary;
        if (closed || busy || current == null || !current.automatic || !validBoundary(current)) return false;
        String rejected = current.output;
        if (!replaceBoundary(current, current.original)) return false;
        if (learningEnabled && adaptive != null) adaptive.reject(current.original, rejected);
        current.automatic = false;
        current.token = ++generation;
        current.predictions = Collections.emptyList();
        showBoundaryCandidates(current);
        requestBoundaryPredictions(current);
        return true;
    }

    /** Invalidates a word if the actual editor selection moves away from its tracked location. */
    public void onSelectionChanged(int oldStart, int oldEnd, int newStart, int newEnd, int candidatesEnd) {
        if (closed) return;
        if (manualEdit != null) {
            manualEdit.recordSelection(oldStart, oldEnd, newStart, newEnd);
            return;
        }
        if (applyingEdit) return;
        InputConnection connection = connections.get();
        updateFallbackSelection(connection, oldStart, oldEnd, newStart, newEnd);
        Snapshot actual = selection(connection);
        if (literal != null && !validLiteralText(readContext(connection))) {
            clearLiteral();
            if (literalVisible) { generation++; cancelQueuedSuggestions(); clearSuggestions(); }
        }
        if (literalBoundary != null) {
            if (validLiteralBoundary(literalBoundary)) return;
            literalBoundary = null;
            generation++;
            clearSuggestions();
        }
        if (blocked && word == null && boundary == null && !busy) {
            if (blockedSelection == null || !blockedSelection.equals(actual)) {
                blocked = false;
                blockedSelection = null;
                chosenWord = null;
            }
            return;
        }
        Snapshot expected = busy || boundary != null ? (boundary == null ? null : boundary.selection) : wordSelection;
        if (expected != null && expected.equals(actual) &&
                (word == null || validTrackedText(readContext(connection)))) return;
        if (word == null && boundary == null && !busy) return;
        generation++;
        cancelQueuedSuggestions();
        clearWord(); clearLiteral(); blocked = false; blockedSelection = null; boundary = null; busy = false; clearSuggestions();
        chosenWord = null;
        pendingChosenSpace = null;
        // Pending input belongs to the original caret location, not the new selection.
        deferred.clear();
    }

    /** Resets state on focus, editor, mode, preference, paste or non-keyboard changes. */
    public void cancel() {
        generation++;
        cancelQueuedSuggestions();
        clearWord(); clearLiteral(); literalBoundary = null; blocked = false; blockedSelection = null; boundary = null; busy = false;
        chosenWord = null;
        pendingChosenSpace = null;
        deferred.clear();
        clearSuggestions();
        manualEdit = null;
    }

    public void close() {
        if (closed) return;
        cancel();
        closed = true;
        clearFallback();
        if (ownedWorker != null) ownedWorker.shutdownNow();
        strip = null;
    }

    private void requestSuggestions() {
        long token = ++generation;
        showCurrentWord(token);
        int version = dictionaryVersion();
        if (version == Integer.MIN_VALUE) return;
        ContextText text = readContext(wordConnection);
        if (!validTrackedText(text)) return;
        String previousContext = precedingText(text.before, word + punctuation);
        Request request = new Request(wordConnection, word, previousContext, korean, version, token, taps);
        synchronized (requestLock) {
            queuedSuggestion = request;
            if (suggestionScheduled) return;
            suggestionScheduled = true;
        }
        scheduleSuggestionWorker();
    }

    private void showCurrentWord(long token) {
        if (strip == null || word == null) return;
        literalVisible = false;
        String current = word;
        InputConnection connection = wordConnection;
        strip.setTypedCandidates(Collections.singletonList(current),
                candidate -> selectWordCandidate(token, candidate));
        if (personalWords == null || !canLearn(current, korean, punctuation)) {
            strip.clearLearningAction();
        } else if (wordProtected || isPersonalWord(current)) {
            strip.showLearningFeedback(current);
        } else {
            strip.setLearnWord(current, () -> learnCurrentWord(token, connection, current));
        }
    }

    private void learnCurrentWord(long token, InputConnection connection, String target) {
        if (!validLearningTarget(token, connection, target)) return;
        // Finishing the composing syllable does not change its spelling or insert a space.
        beforeEdit.run();
        if (!validLearningTarget(token, connection, target)) return;
        try {
            personalWords.add(target); // false means the word was already saved.
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not save personal word", e);
            if (validLearningTarget(token, connection, target) && strip != null) {
                strip.showLearningError(target);
            }
            return;
        }
        if (!validLearningTarget(token, connection, target)) return;
        wordProtected = true;
        // Invalidate the old result and rebuild against the changed personal dictionary version.
        requestSuggestions();
    }

    private boolean validLearningTarget(long token, InputConnection connection, String target) {
        return !closed && !busy && personalWords != null && token == generation &&
                connection == wordConnection && target.equals(word) &&
                canLearn(target, korean, punctuation) && validTrackedText(readContext(connection));
    }

    private void showBoundaryLearning(Boundary current) {
        if (strip == null) return;
        if (personalWords == null || !canLearn(current.original, current.korean, current.punctuation)) {
            strip.clearLearningAction();
        } else if (current.learningFailed) {
            strip.showLearningError(current.original);
        } else if (current.protectedWord || isPersonalWord(current.original)) {
            strip.showLearningFeedback(current.original);
        } else {
            long token = current.token;
            strip.setLearnWord(current.original, () -> learnBoundaryWord(current, token));
        }
    }

    private void learnBoundaryWord(Boundary current, long token) {
        if (!validBoundaryLearningTarget(current, token)) return;
        if (!current.original.equals(current.output)) {
            // Restore what was typed before changing the dictionary version, retaining punctuation
            // and the already inserted space. Location checks also cover an external version change.
            if (!replaceBoundary(current, current.original, false)) return;
            current.automatic = false;
        } else {
            beforeEdit.run();
        }
        if (!validBoundaryLearningTarget(current, token)) return;
        try {
            personalWords.add(current.original);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not save personal word", e);
            if (validBoundaryLearningTarget(current, token)) {
                current.learningFailed = true;
                showBoundaryCandidates(current);
            }
            return;
        }
        if (!validBoundaryLearningTarget(current, token)) return;
        boolean wasBusy = busy;
        busy = false;
        current.pending = false;
        current.protectedWord = true;
        current.learningFailed = false;
        current.version = dictionaryVersion();
        current.token = ++generation;
        current.predictions = Collections.emptyList();
        showBoundaryCandidates(current);
        requestBoundaryPredictions(current);
        // An older calculation may still complete, but it cannot overwrite this explicit choice.
        if (wasBusy) {
            if (validBoundaryLocation(current)) flushDeferred();
            else { boundary = null; clearSuggestions(); deferred.clear(); }
        }
    }

    private boolean validBoundaryLearningTarget(Boundary current, long token) {
        return !closed && personalWords != null && boundary == current && token == generation &&
                token == current.token && canLearn(current.original, current.korean, current.punctuation) &&
                validBoundaryLocation(current);
    }

    private boolean isPersonalWord(String word) {
        if (personalWords == null || word == null) return false;
        try { return personalWords.contains(word); }
        catch (RuntimeException e) {
            Log.w(TAG, "Could not read personal words", e);
            return false;
        }
    }

    private static boolean canLearn(String word, boolean korean, String punctuation) {
        // A trailing apostrophe may be the start of a contraction, not a completed word.
        if (!punctuation.isEmpty() && (punctuation.charAt(0) == '\'' || punctuation.charAt(0) == '’')) {
            return false;
        }
        String normalized = Normalizer.normalize(word, Normalizer.Form.NFC);
        if (normalized.isEmpty() || normalized.length() > 48) return false;
        int letters = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (korean) {
                if (c < '\uac00' || c > '\ud7a3') return false;
                letters++;
            } else if (isAsciiLetter(c)) {
                letters++;
            } else if ((c != '\'' && c != '’') || i == 0 || i == normalized.length() - 1 ||
                    !isAsciiLetter(normalized.charAt(i - 1)) || !isAsciiLetter(normalized.charAt(i + 1))) {
                return false;
            }
        }
        if (letters < (korean ? 1 : 2)) return false;
        String keys = SwipeDecoder.keySequenceForWord(normalized);
        return keys != null && keys.length() <= 64;
    }

    private static boolean isAsciiLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private void trackLiteralTyping(boolean korean) {
        InputConnection connection = connections.get();
        ContextText text = readContext(connection);
        if (korean || text == null || !safeAfter(text.after) || text.before.isEmpty()) {
            clearLiteral();
            return;
        }
        char added = text.before.charAt(text.before.length() - 1);
        if (added < 33 || added > 126) { clearLiteral(); return; }

        if (literal == null) {
            // A paste, an existing token, or a cursor in the middle of a token is not manual input.
            int start = text.before.length() - 1;
            if (added == ',' || (start > 0 && !literalDelimiter(text.before.charAt(start - 1)))) return;
            literal = new Literal(connection, String.valueOf(added), "", text);
            return;
        }

        Literal previous = literal;
        if (connection != previous.connection || !validPosition(previous.selection, text.selection, false) ||
                !previous.after.equals(text.after) ||
                !tail(previous.before + added, CONTEXT_LIMIT).equals(text.before)) {
            clearLiteral();
            return;
        }
        if (!previous.punctuation.isEmpty() && added != ',') {
            // A comma terminates the previous literal and starts a new one.
            literal = new Literal(connection, String.valueOf(added), "", text);
        } else if (added == ',') {
            if (!PersonalDictionary.isValidEmailAddress(previous.value)) {
                clearLiteral();
                return;
            }
            literal = new Literal(connection, previous.value, previous.punctuation + added, text);
        } else if (previous.value.length() < 254) {
            literal = new Literal(connection, previous.value + added, "", text);
        } else {
            clearLiteral();
        }
    }

    private void trackLiteralBackspace(boolean korean) {
        Literal previous = literal;
        if (previous == null) return;
        InputConnection connection = connections.get();
        ContextText text = readContext(connection);
        if (korean || connection != previous.connection || text == null || !safeAfter(text.after) ||
                !validBackspacePosition(previous.selection, text.selection, false) ||
                !previous.after.equals(text.after) || previous.before.isEmpty()) {
            clearLiteral();
            return;
        }
        String remaining = previous.before.substring(0, previous.before.length() - 1);
        boolean sameBefore = previous.before.length() == CONTEXT_LIMIT
                ? text.before.endsWith(remaining) : text.before.equals(remaining);
        if (!sameBefore) { clearLiteral(); return; }
        if (!previous.punctuation.isEmpty()) {
            literal = new Literal(connection, previous.value,
                    previous.punctuation.substring(0, previous.punctuation.length() - 1), text);
        } else if (previous.value.length() > 1) {
            literal = new Literal(connection, previous.value.substring(0, previous.value.length() - 1), "", text);
        } else {
            clearLiteral();
        }
    }

    private static boolean literalDelimiter(char c) {
        return Character.isWhitespace(c) || c == ',';
    }

    private boolean validLiteralText(ContextText text) {
        return literal != null && text != null && literal.connection == connections.get() &&
                literal.selection.equals(text.selection) && literal.before.equals(text.before) &&
                literal.after.equals(text.after) && safeAfter(text.after);
    }

    private List<String> matchingEmails(String prefix) {
        if (personalWords == null) return Collections.emptyList();
        ArrayList<String> matches = new ArrayList<>();
        try {
            for (String email : personalWords.getEmailAddresses()) {
                if (email != null && PersonalDictionary.isValidEmailAddress(email) &&
                        email.length() >= prefix.length() &&
                        email.regionMatches(true, 0, prefix, 0, prefix.length())) matches.add(email);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read saved email addresses", e);
        }
        return matches;
    }

    private void refreshLiteralSuggestions() {
        if (showLiteralSuggestions()) return;
        if (literalVisible) {
            generation++;
            cancelQueuedSuggestions();
            clearSuggestions();
        }
    }

    private boolean showLiteralSuggestions() {
        if (strip == null || personalWords == null || literal == null ||
                !validLiteralText(readContext(literal.connection))) return false;
        String target = literal.value;
        boolean complete = PersonalDictionary.isValidEmailAddress(target);
        List<String> matches = matchingEmails(target);
        if (!complete && matches.isEmpty()) return false;

        long token = ++generation;
        cancelQueuedSuggestions();
        InputConnection connection = literal.connection;
        ArrayList<String> candidates = new ArrayList<>();
        candidates.add(target);
        for (String match : matches) if (!match.equals(target)) candidates.add(match);
        strip.setTypedCandidates(candidates, candidate -> selectLiteralCandidate(token, connection, target, candidate));
        if (!complete) strip.clearLearningAction();
        else if (isPersonalWord(target)) strip.showLearningFeedback(target);
        else strip.setLearnWord(target, () -> learnLiteral(token, connection, target));
        literalVisible = true;
        return true;
    }

    private boolean validLiteralTarget(long token, InputConnection connection, String target) {
        return !closed && !busy && token == generation && literal != null &&
                literal.connection == connection && literal.value.equals(target) &&
                validLiteralText(readContext(connection));
    }

    private void learnLiteral(long token, InputConnection connection, String target) {
        if (personalWords == null || !PersonalDictionary.isValidEmailAddress(target) ||
                !validLiteralTarget(token, connection, target)) return;
        beforeEdit.run();
        if (!validLiteralTarget(token, connection, target)) return;
        try { personalWords.add(target); }
        catch (RuntimeException e) {
            Log.w(TAG, "Could not save email address", e);
            if (validLiteralTarget(token, connection, target) && strip != null) strip.showLearningError(target);
            return;
        }
        if (validLiteralTarget(token, connection, target)) showLiteralSuggestions();
    }

    private void selectLiteralCandidate(long token, InputConnection connection, String target, String candidate) {
        if (!validLiteralTarget(token, connection, target) || candidate == null ||
                !matchingEmails(target).contains(candidate)) return;
        beforeEdit.run();
        if (!validLiteralTarget(token, connection, target) || !matchingEmails(target).contains(candidate)) return;
        String old = literal.value + literal.punctuation;
        String replacement = candidate + literal.punctuation;
        String punctuation = literal.punctuation;
        beforeManualEdit();
        applyingEdit = true;
        connection.beginBatchEdit();
        boolean changed;
        try {
            changed = connection.deleteSurroundingText(old.length(), 0);
            if (changed && !connection.commitText(replacement, 1)) {
                connection.commitText(old, 1);
                changed = false;
            }
        } finally { connection.endBatchEdit(); applyingEdit = false; }
        finishManualEdit(changed ? old : "", changed ? replacement : "");
        if (!changed) return;
        ContextText text = readContext(connection);
        if (text == null || !text.before.endsWith(replacement)) {
            clearLiteral(); blockWord(); return;
        }
        literal = new Literal(connection, candidate, punctuation, text);
        clearWord();
        blocked = true;
        blockedSelection = text.selection;
        showLiteralSuggestions();
    }

    private boolean commitLiteralSpace(InputConnection connection) {
        Literal current = literal;
        generation++;
        cancelQueuedSuggestions();
        beforeManualEdit();
        applyingEdit = true;
        boolean inserted;
        try { inserted = connection.commitText(" ", 1); }
        finally { applyingEdit = false; }
        finishManualEdit("", inserted ? " " : "");
        clearWord(); clearLiteral(); blocked = false; blockedSelection = null;
        boundary = null; busy = false; clearSuggestions();
        if (!inserted) return false;
        ContextText after = readContext(connection);
        if (after == null || !after.before.endsWith(current.value + current.punctuation + " ")) return true;
        literalBoundary = new LiteralBoundary(connection, current.value, current.punctuation, after, generation);
        showLiteralBoundary(literalBoundary);
        return true;
    }

    private boolean validLiteralBoundary(LiteralBoundary current) {
        if (current == null || current.connection != connections.get()) return false;
        ContextText text = readContext(current.connection);
        return text != null && current.selection.equals(text.selection) &&
                current.before.equals(text.before) && current.after.equals(text.after) &&
                text.before.endsWith(current.value + current.punctuation + " ");
    }

    private void showLiteralBoundary(LiteralBoundary current) {
        if (strip == null || literalBoundary != current) return;
        strip.setTypedCandidates(Collections.singletonList(current.value), null);
        if (personalWords == null) strip.clearLearningAction();
        else if (current.learningFailed) strip.showLearningError(current.value);
        else if (isPersonalWord(current.value)) strip.showLearningFeedback(current.value);
        else {
            long token = current.token;
            strip.setLearnWord(current.value, () -> learnLiteralBoundary(current, token));
        }
        literalVisible = true;
    }

    private void learnLiteralBoundary(LiteralBoundary current, long token) {
        if (closed || personalWords == null || literalBoundary != current ||
                token != generation || token != current.token || !validLiteralBoundary(current)) return;
        beforeEdit.run();
        if (literalBoundary != current || token != generation || token != current.token ||
                !validLiteralBoundary(current)) return;
        try { personalWords.add(current.value); }
        catch (RuntimeException e) {
            Log.w(TAG, "Could not save email address", e);
            if (literalBoundary == current && token == generation && token == current.token &&
                    validLiteralBoundary(current)) {
                current.learningFailed = true;
                showLiteralBoundary(current);
            }
            return;
        }
        if (literalBoundary == current && token == generation && token == current.token &&
                validLiteralBoundary(current)) {
            showLiteralBoundary(current);
        }
    }

    private void clearLiteral() { literal = null; }

    private void scheduleSuggestionWorker() {
        try {
            worker.execute(() -> {
                Request request;
                synchronized (requestLock) { request = queuedSuggestion; queuedSuggestion = null; }
                if (request != null) {
                    TypingCorrector.Result result = compute(request.word, request.korean,
                            request.version, request.previousContext, true, request.taps);
                    main.post(() -> showSuggestions(request, result));
                }
                boolean again;
                synchronized (requestLock) {
                    again = queuedSuggestion != null;
                    if (!again) suggestionScheduled = false;
                }
                if (again) scheduleSuggestionWorker();
            });
        } catch (RejectedExecutionException e) {
            synchronized (requestLock) { suggestionScheduled = false; queuedSuggestion = null; }
        }
    }

    private void cancelQueuedSuggestions() {
        synchronized (requestLock) { queuedSuggestion = null; }
    }

    private TypingCorrector.Result compute(String typed, boolean korean, int version,
                                            String previousContext, boolean completions, List<TouchModel.Sample> samples) {
        try {
            if (dictionaryVersion() != version) return null;
            int language = korean ? 1 : 0;
            if (engines[language] == null || engineVersions[language] != version) {
                TypingCorrector.ContextModel model = effectiveContext(korean);
                List<SwipeDecoder.Entry> entries = new ArrayList<>(loader.load(korean));
                if (learningEnabled && adaptive != null) entries.addAll(adaptive.entries(korean));
                engines[language] = new TypingCorrector(entries, korean, model);
                boundaries[language] = new BoundarySuggestions(entries);
                engineVersions[language] = version;
            }
            TypingCorrector.Result result;
            try { result = completions
                    ? engines[language].suggestWithCompletions(typed, 12, previousContext)
                    : engines[language].suggest(typed, 12, previousContext); }
            catch (RuntimeException e) {
                Log.w(TAG, "Could not use typing context; falling back to spelling", e);
                result = engines[language].suggest(typed, 5);
            }
            result = PersonalizedSuggestions.apply(result, typed, previousContext,
                    learningEnabled ? adaptive : null, touchEnabled ? touchModel : null, samples,
                    boundaries[language].split(typed, effectiveContext(korean)), 5);
            return dictionaryVersion() == version ? result : null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not load or search the typing dictionary", e);
            return null;
        }
    }

    // Called on the worker, which owns the context-model cache.
    private void ensureContextModelLoaded() {
        if (contextModelLoaded) return;
        try { contextModel = loader.loadContext(); }
        catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not load the typing context model", e);
        }
        contextModelLoaded = true;
    }

    private TypingCorrector.ContextModel effectiveContext(boolean korean) {
        if (korean && !koreanContextLoaded) {
            try { koreanContextModel = loader.loadContext(true); }
            catch (IOException ignored) { }
            koreanContextLoaded = true;
        } else if (!korean) ensureContextModelLoaded();
        TypingCorrector.ContextModel base = korean ? koreanContextModel : contextModel;
        return learningEnabled && adaptive != null ? new PersonalContextModel(base, adaptive) : base;
    }

    private int dictionaryVersion() {
        try { return adaptationEpoch * 100003 + loader.version() * 31 + (learningEnabled && adaptive != null ? adaptive.lexiconVersion() : 0); }
        catch (RuntimeException e) {
            Log.w(TAG, "Could not read typing dictionary version", e);
            return Integer.MIN_VALUE;
        }
    }

    private void showSuggestions(Request request, TypingCorrector.Result result) {
        if (closed || result == null || request.token != generation || busy || word == null ||
                request.connection != connections.get() || !request.word.equals(word) ||
                request.version != dictionaryVersion()) return;
        ContextText text = readContext(request.connection);
        if (!validTrackedText(text) || !request.previousContext.equals(
                precedingText(text.before, word + punctuation))) return;
        if (strip != null) {
            literalVisible = false;
            final long token = generation;
            strip.setTypedCandidates(result.candidates, candidate -> selectWordCandidate(token, candidate));
        }
    }

    private void finishBoundary(Boundary current, long token, TypingCorrector.Result result) {
        if (closed || !current.pending || boundary != current || current.token != token ||
                token != generation) return;
        current.pending = false;
        busy = false;
        if (!validBoundaryLocation(current)) {
            boundary = null; clearSuggestions();
            deferred.clear();
            return;
        }
        if (result != null && current.version == dictionaryVersion()) {
            current.candidates = result.candidates;
            String correction = useTopSuggestion ? result.contextCorrection : null;
            if (correction == null && !result.exactWord) {
                correction = useTopSuggestion ? result.automaticSuggestion : result.correction;
            }
            if (autoCorrectionEnabled && !current.protectedWord &&
                    correction != null && !correction.equals(current.original) &&
                    replaceBoundary(current, correction)) current.automatic = true;
            if (!validBoundaryLocation(current)) {
                boundary = null; clearSuggestions(); deferred.clear();
                return;
            }
        } else {
            // Stale or unavailable dictionaries must not hide an otherwise valid Add action.
            current.version = dictionaryVersion();
        }
        if (!current.automatic && !current.observed && learningEnabled && adaptive != null) {
            adaptive.observe(current.previousContext, current.output, false, result != null && result.exactWord);
            current.observed = true;
            current.version = dictionaryVersion();
        }
        showBoundaryCandidates(current);
        requestBoundaryPredictions(current);
        if (validBoundaryLocation(current)) flushDeferred();
        else { boundary = null; clearSuggestions(); deferred.clear(); }
    }

    private void selectWordCandidate(long token, String candidate) {
        if (deferIfBusy(() -> selectWordCandidate(token, candidate))) return;
        if (closed || token != generation || word == null || candidate == null ||
                !validTrackedText(readContext(connections.get()))) return;
        beforeEdit.run();
        InputConnection connection = wordConnection;
        if (!validTrackedText(readContext(connection))) return;
        String originalWord = word;
        String priorContext = precedingText(readContext(connection).before, word + punctuation);
        List<TouchModel.Sample> confirmedTaps = new ArrayList<>(taps);
        String replace = word + punctuation;
        beforeManualEdit();
        applyingEdit = true;
        connection.beginBatchEdit();
        boolean changed;
        try {
            changed = connection.deleteSurroundingText(replace.length(), 0);
            if (changed && !connection.commitText(candidate + punctuation, 1)) {
                connection.commitText(replace, 1);
                changed = false;
            }
        } finally { connection.endBatchEdit(); applyingEdit = false; }
        finishManualEdit(changed ? replace : "", changed ? candidate + punctuation : "");
        if (!changed) return;
        word = candidate;
        ContextText text = readContext(connection);
        if (text == null) { blockWord(); return; }
        wordSelection = text.selection;
        confirmChoice(priorContext, originalWord, candidate, confirmedTaps);
        // A manually selected spelling is final for this word, but can still be edited.
        generation++;
        clearSuggestions();
        preserveChosenCandidate();
    }

    private void preserveChosenCandidate() {
        // Do not second-guess a candidate explicitly selected by the user at the next space.
        ContextText text = readContext(wordConnection);
        if (validTrackedText(text)) {
            chosenWord = new ChosenWord(wordConnection, word, punctuation,
                    precedingText(text.before, word + punctuation), korean, text.selection,
                    dictionaryVersion());
        } else {
            chosenWord = null;
        }
        clearWord();
        blocked = true;
        blockedSelection = selection(connections.get());
    }

    private boolean validChosenWord(ChosenWord chosen) {
        if (chosen.connection != connections.get() || chosen.version == Integer.MIN_VALUE ||
                chosen.version != dictionaryVersion()) return false;
        ContextText text = readContext(chosen.connection);
        String expected = tail(chosen.previousContext + chosen.word + chosen.punctuation, CONTEXT_LIMIT);
        return text != null && chosen.selection.equals(text.selection) &&
                text.before.endsWith(expected) && safeAfter(text.after);
    }

    private void showBoundaryCandidates(Boundary current) {
        if (strip == null || boundary != current) return;
        literalVisible = false;
        long token = current.token;
        SuggestionStripView shownStrip = strip;
        shownStrip.setBoundaryCandidates(current.candidates, current.predictions, candidate -> {
            if (deferIfBusy(() -> selectBoundaryCandidate(current, token, candidate))) return;
            selectBoundaryCandidate(current, token, candidate);
        }, candidate -> selectNextWord(current, token, candidate, shownStrip));
        showBoundaryLearning(current);
    }

    private void selectBoundaryCandidate(Boundary current, long token, String candidate) {
        if (closed || boundary != current || token != generation || current.token != token ||
                candidate == null || !validBoundary(current)) return;
        String oldOutput = current.output;
        boolean wasAutomatic = current.automatic;
        if (replaceBoundary(current, candidate)) {
            if (wasAutomatic && candidate.equals(current.original) && learningEnabled && adaptive != null)
                adaptive.reject(current.original, oldOutput);
            else confirmChoice(current.previousContext, current.original, candidate, current.taps);
            current.version = dictionaryVersion();
            current.automatic = false;
            current.token = ++generation;
            current.predictions = Collections.emptyList();
            showBoundaryCandidates(current);
            requestBoundaryPredictions(current);
        }
    }

    private void requestBoundaryPredictions(Boundary current) {
        if (strip == null || closed || boundary != current || current.pending ||
                current.version == Integer.MIN_VALUE ||
                !current.punctuation.isEmpty() || !validBoundary(current)) return;
        final long token = current.token;
        final int version = current.version;
        final String output = current.output;
        final String preceding = current.previousContext + output + " ";
        try {
            worker.execute(() -> {
                List<String> predictions = Collections.emptyList();
                try {
                    TypingCorrector.ContextModel model = effectiveContext(current.korean);
                    if (dictionaryVersion() == version && model != null) {
                        predictions = validPredictions(model.predict(preceding, 5), current.korean);
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not predict the next word", e);
                }
                final List<String> result = predictions;
                main.post(() -> {
                    if (closed || strip == null || boundary != current || token != generation ||
                            current.token != token || current.version != version ||
                            !output.equals(current.output) || !validBoundary(current)) return;
                    current.predictions = result;
                    showBoundaryCandidates(current);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // The prior-word suggestions remain usable if prediction work is unavailable.
        }
    }

    private static List<String> validPredictions(List<String> predictions, boolean korean) {
        if (predictions == null || predictions.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String candidate : predictions) {
            if (candidate != null && validWord(candidate, korean) && !result.contains(candidate)) {
                result.add(candidate);
                if (result.size() == 5) break;
            }
        }
        return Collections.unmodifiableList(result);
    }

    private void selectNextWord(Boundary current, long token, String candidate,
                                SuggestionStripView shownStrip) {
        if (!validNextWordTarget(current, token, candidate, shownStrip)) return;
        beforeEdit.run();
        if (!validNextWordTarget(current, token, candidate, shownStrip)) return;
        InputConnection connection = current.connection;
        String inserted = candidate + " ";
        beforeManualEdit();
        applyingEdit = true;
        boolean changed;
        try { changed = connection.commitText(inserted, 1); }
        finally { applyingEdit = false; }
        finishManualEdit("", changed ? inserted : "");
        if (!changed) {
            if (!validBoundaryLocation(current)) cancel();
            return;
        }

        ContextText after = readContext(connection);
        String preceding = tail(current.previousContext + current.output + " ", CONTEXT_LIMIT);
        long nextToken = ++generation;
        cancelQueuedSuggestions();
        if (connection != connections.get() || after == null ||
                !after.before.endsWith(tail(preceding + inserted, CONTEXT_LIMIT)) ||
                !safeAfter(after.after)) {
            boundary = null;
            clearSuggestions();
            return;
        }
        confirmChoice(preceding, candidate, candidate, Collections.emptyList());
        Boundary next = new Boundary(connection, candidate, "", preceding, current.korean,
                isPersonalWord(candidate), after.selection, dictionaryVersion(), nextToken);
        next.typedSpace = false;
        boundary = next;
        showBoundaryCandidates(next);
        requestBoundaryPredictions(next);
    }

    private boolean validNextWordTarget(Boundary current, long token, String candidate,
                                        SuggestionStripView shownStrip) {
        if (closed || busy || strip != shownStrip || boundary != current || token != generation || current.token != token ||
                candidate == null || !current.predictions.contains(candidate) || !validBoundary(current)) {
            return false;
        }
        ContextText text = readContext(current.connection);
        return text != null && safeAfter(text.after);
    }

    private boolean replaceBoundary(Boundary current, String replacement) {
        return replaceBoundary(current, replacement, true);
    }

    private boolean replaceBoundary(Boundary current, String replacement, boolean requireVersion) {
        if (!(requireVersion ? validBoundary(current) : validBoundaryLocation(current))) return false;
        beforeEdit.run();
        if (!(requireVersion ? validBoundary(current) : validBoundaryLocation(current))) return false;
        InputConnection connection = current.connection;
        String old = current.output + current.punctuation + " ";
        String next = replacement + current.punctuation + " ";
        beforeManualEdit();
        applyingEdit = true;
        connection.beginBatchEdit();
        boolean changed;
        try {
            changed = connection.deleteSurroundingText(old.length(), 0);
            if (changed && !connection.commitText(next, 1)) {
                connection.commitText(old, 1);
                changed = false;
            }
        } finally { connection.endBatchEdit(); applyingEdit = false; }
        finishManualEdit(changed ? old : "", changed ? next : "");
        if (!changed) return false;
        current.output = replacement;
        current.selection = selection(connection);
        return current.selection != null;
    }

    private boolean validBoundary(Boundary current) {
        return current.version == dictionaryVersion() && validBoundaryLocation(current);
    }

    private boolean validBoundaryLocation(Boundary current) {
        if (current.connection != connections.get()) return false;
        ContextText text = readContext(current.connection);
        String expectedBefore = tail(current.previousContext + current.output + current.punctuation + " ",
                CONTEXT_LIMIT);
        return text != null && current.selection.equals(text.selection) &&
                text.before.endsWith(expectedBefore);
    }

    private boolean validTrackedText(ContextText text) {
        String expected = word == null ? null : word + punctuation;
        return text != null && expected != null && connections.get() == wordConnection &&
                wordSelection != null && wordSelection.equals(text.selection) && safeAfter(text.after) &&
                text.before.endsWith(expected) && samePrefix(text.before, expected, prefix);
    }

    private void invalidateBoundary() {
        if (boundary != null || literalBoundary != null) {
            boundary = null; literalBoundary = null; generation++; clearSuggestions();
        }
    }

    private void blockWord() {
        generation++;
        cancelQueuedSuggestions();
        clearWord(); blocked = true; blockedSelection = selection(connections.get()); clearSuggestions();
    }

    private void clearWord() {
        taps.clear();
        pendingTap = null;
        word = null; wordConnection = null; wordSelection = null; prefix = null; punctuation = "";
        wordProtected = false;
    }

    private void clearSuggestions() {
        literalVisible = false;
        if (strip != null) {
            strip.setCandidates(Collections.emptyList(), null);
            strip.clearLearningAction();
        }
    }

    private void flushDeferred() {
        while (!closed && !busy && !deferred.isEmpty()) deferred.removeFirst().run();
    }

    private static boolean validPosition(Snapshot before, Snapshot after, boolean korean) {
        if (before == null || after == null || before.offset != after.offset) return false;
        int delta = after.start - before.start;
        return delta == 1 || (korean && delta == 0);
    }

    private static boolean validBackspacePosition(Snapshot before, Snapshot after, boolean korean) {
        if (before == null || after == null || before.offset != after.offset) return false;
        int delta = after.start - before.start;
        return delta == -1 || (korean && delta == 0);
    }

    private static boolean validLetterUpdate(String old, String current, boolean korean) {
        if (!korean) return current.length() == old.length() + 1 && current.startsWith(old);
        int oldPrefix = old.offsetByCodePoints(old.length(), -1);
        return current.startsWith(old.substring(0, oldPrefix)) &&
                (current.codePointCount(0, current.length()) == old.codePointCount(0, old.length()) ||
                 current.codePointCount(0, current.length()) == old.codePointCount(0, old.length()) + 1);
    }

    private static boolean validBackspaceUpdate(String old, String current, boolean korean) {
        int oldPrefix = old.offsetByCodePoints(old.length(), -1);
        if (!korean) return current.equals(old.substring(0, oldPrefix));
        return current.startsWith(old.substring(0, oldPrefix)) &&
                (current.codePointCount(0, current.length()) == old.codePointCount(0, old.length()) ||
                 current.codePointCount(0, current.length()) == old.codePointCount(0, old.length()) - 1);
    }

    private static boolean validWord(String word, boolean korean) {
        if (word.isEmpty() || word.length() > WORD_LIMIT) return false;
        boolean sawLower = false;
        int uppercase = 0;
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (korean) {
                if (!(ch >= '\uac00' && ch <= '\ud7a3') && !(ch >= '\u3131' && ch <= '\u318e')) return false;
            } else if (ch >= 'A' && ch <= 'Z') {
                if (sawLower) return false;
                uppercase++;
            } else if (ch >= 'a' && ch <= 'z') {
                if (uppercase > 1) return false;
                sawLower = true;
            } else if ((ch == '\'' || ch == '’') && i > 0 && i < word.length() - 1 &&
                    word.charAt(i - 1) != '\'' && word.charAt(i - 1) != '’') {
                // A single apostrophe inside a word is part of an English contraction.
            } else return false;
        }
        return true;
    }

    private static String letterSuffix(String text) {
        int start = text.length();
        while (start > 0) {
            int cp = text.codePointBefore(start);
            if (!Character.isLetter(cp)) break;
            start -= Character.charCount(cp);
        }
        return text.substring(start);
    }

    private static boolean samePrefix(String before, String suffix, String prefix) {
        if (prefix == null || !before.endsWith(suffix)) return false;
        return before.substring(0, before.length() - suffix.length()).endsWith(prefix);
    }

    private static String precedingText(String before, String suffix) {
        return before.substring(0, before.length() - suffix.length());
    }

    private static boolean safeAfter(String after) {
        if (after.isEmpty() || Character.isWhitespace(after.codePointAt(0))) return true;
        // A terminal sentence mark is safe, but a mark embedded in an identifier is not.
        int i = 0;
        while (i < after.length() && ".,!?;:".indexOf(after.charAt(i)) >= 0) i++;
        return i > 0 && (i == after.length() || Character.isWhitespace(after.codePointAt(i)));
    }

    private static String tail(String value, int limit) {
        return value.substring(Math.max(0, value.length() - limit));
    }

    private ContextText readContext(InputConnection connection) {
        if (connection == null) return null;
        try {
            SelectionRead selection = readSelection(connection);
            Surrounding surrounding = readSurrounding(connection);
            if (selection == null || selection.snapshot == null || surrounding == null) return null;
            if (selection.fallback && (!surrounding.equals(fallbackSurrounding) ||
                    !fitsSelection(surrounding, selection.snapshot.start))) {
                clearFallback();
                return null;
            }
            return new ContextText(surrounding.before, surrounding.after, selection.snapshot);
        } catch (RuntimeException e) { return null; }
    }

    private Snapshot selection(InputConnection connection) {
        if (connection == null) return null;
        SelectionRead read = readSelection(connection);
        if (read == null || read.snapshot == null) return null;
        if (read.fallback) {
            Surrounding surrounding = readSurrounding(connection);
            if (!surroundingEqualsFallback(surrounding, read.snapshot.start)) {
                clearFallback();
                return null;
            }
        }
        return read.snapshot;
    }

    private SelectionRead readSelection(InputConnection connection) {
        if (connection == null) return null;
        ExtractedText extracted = null;
        try { extracted = connection.getExtractedText(new ExtractedTextRequest(), 0); }
        catch (RuntimeException ignored) { }
        if (extracted != null) {
            if (extracted.selectionStart < 0 || extracted.selectionStart != extracted.selectionEnd) {
                return new SelectionRead(null, false);
            }
            return new SelectionRead(new Snapshot(extracted.startOffset, extracted.selectionStart), false);
        }
        if (connection != fallbackConnection || fallbackSelection == null) return null;
        return new SelectionRead(fallbackSelection, true);
    }

    private static Surrounding readSurrounding(InputConnection connection) {
        if (connection == null) return null;
        try {
            CharSequence before = connection.getTextBeforeCursor(CONTEXT_LIMIT, 0);
            CharSequence after = connection.getTextAfterCursor(CONTEXT_LIMIT, 0);
            return before == null || after == null ? null : new Surrounding(before.toString(), after.toString());
        } catch (RuntimeException e) { return null; }
    }

    private static boolean fitsSelection(Surrounding text, int position) {
        return text != null && position >= 0 && text.before.length() == Math.min(position, CONTEXT_LIMIT);
    }

    private boolean surroundingEqualsFallback(Surrounding surrounding, int position) {
        return fitsSelection(surrounding, position) && surrounding.equals(fallbackSurrounding);
    }

    private void setFallback(InputConnection connection, int position, Surrounding surrounding) {
        if (fallbackConnection != connection) pendingFallbackTransitions.clear();
        fallbackConnection = connection;
        fallbackSelection = new Snapshot(0, position);
        fallbackSurrounding = surrounding;
    }

    private void clearFallback() {
        fallbackConnection = null;
        fallbackSelection = null;
        fallbackSurrounding = null;
        manualEdit = null;
        pendingFallbackTransitions.clear();
    }

    private void updateFallbackSelection(InputConnection connection, int oldStart, int oldEnd,
                                         int start, int end) {
        if (connection == null) { clearFallback(); return; }
        SelectionRead actual = readSelection(connection);
        if (actual != null && !actual.fallback) {
            if (actual.snapshot == null) { clearFallback(); return; }
            int position = actual.snapshot.offset + actual.snapshot.start;
            Surrounding surrounding = readSurrounding(connection);
            pendingFallbackTransitions.clear();
            if (fitsSelection(surrounding, position)) setFallback(connection, position, surrounding);
            else clearFallback();
            return;
        }
        if (start < 0 || start != end) { clearFallback(); return; }
        Surrounding surrounding = readSurrounding(connection);
        if (fallbackConnection == connection && fallbackSelection != null &&
                surroundingEqualsFallback(surrounding, fallbackSelection.start)) {
            // Updates can be delayed or coalesced. Ignore only a transition caused by our own
            // verified edits, consuming it so later movement to an old position is not ignored.
            boolean expected = oldStart == oldEnd && consumeFallbackTransitions(oldStart, start);
            if (start == fallbackSelection.start) {
                if (!expected) pendingFallbackTransitions.clear();
                return;
            }
            if (expected) return;
        }
        pendingFallbackTransitions.clear();
        if (fitsSelection(surrounding, start)) setFallback(connection, start, surrounding);
        else clearFallback();
    }

    private boolean consumeFallbackTransitions(int oldStart, int newStart) {
        int index = 0, match = -1;
        int previous = oldStart;
        for (FallbackTransition transition : pendingFallbackTransitions) {
            if (transition.before != previous) break;
            previous = transition.after;
            if (previous == newStart) { match = index; break; }
            index++;
        }
        if (match < 0) return false;
        for (int i = 0; i <= match; i++) pendingFallbackTransitions.removeFirst();
        return true;
    }

    private void finishManualTyping(boolean korean) {
        ManualEdit edit = manualEdit;
        if (edit == null) return;
        Surrounding after = readSurrounding(edit.connection);
        if (after == null || after.before.isEmpty()) { clearFallback(); return; }
        if (korean && word != null && punctuation.isEmpty()) {
            finishManualEdit(word, letterSuffix(after.before));
        } else {
            finishManualEdit("", after.before.substring(after.before.length() - 1));
        }
    }

    private void finishManualBackspace(boolean korean) {
        ManualEdit edit = manualEdit;
        if (edit == null) return;
        Surrounding after = readSurrounding(edit.connection);
        if (after == null) { clearFallback(); return; }
        if (korean && word != null && punctuation.isEmpty()) {
            String current = letterSuffix(after.before);
            if (!current.isEmpty() && current.length() == word.length() &&
                    validBackspaceUpdate(word, current, true)) {
                finishManualEdit(word, current);
                return;
            }
        }
        if (edit.surrounding.before.isEmpty()) {
            finishManualEdit("", "");
            return;
        }
        String before = edit.surrounding.before;
        int length = Character.charCount(before.codePointBefore(before.length()));
        finishManualEdit(before.substring(before.length() - length), "");
    }

    private void finishManualEdit(String removed, String inserted) {
        ManualEdit edit = manualEdit;
        manualEdit = null;
        if (edit == null) return;
        if (edit.connection != connections.get() || edit.connection != fallbackConnection ||
                fallbackSelection == null || fallbackSelection.start != edit.position ||
                !edit.surrounding.equals(fallbackSurrounding)) {
            clearFallback();
            return;
        }
        Surrounding after = readSurrounding(edit.connection);
        int nextPosition = edit.position - removed.length() + inserted.length();
        if (!fitsSelection(after, nextPosition) || !edit.surrounding.after.equals(after.after) ||
                !edit.surrounding.before.endsWith(removed)) {
            clearFallback();
            return;
        }
        String projected = edit.surrounding.before.substring(0,
                edit.surrounding.before.length() - removed.length()) + inserted;
        // Deletions at the context limit can reveal older characters on the left. Everything
        // still visible from the old window must match, as must the untouched right side.
        boolean matches = after.before.length() <= projected.length()
                ? after.before.equals(tail(projected, after.before.length()))
                : after.before.endsWith(projected);
        if (!matches) { clearFallback(); return; }
        setFallback(edit.connection, nextPosition, after);
        if (nextPosition != edit.position || !edit.surrounding.equals(after)) {
            pendingFallbackTransitions.addLast(new FallbackTransition(edit.position, nextPosition));
            while (pendingFallbackTransitions.size() > CONTEXT_LIMIT) pendingFallbackTransitions.removeFirst();
        }
        if (edit.notified) {
            updateFallbackSelection(edit.connection, edit.oldStart, edit.oldEnd, edit.newStart, edit.newEnd);
        }
    }

    private static PersonalWordStore personalWordStore(PersonalDictionary personal) {
        return new PersonalWordStore() {
            @Override public boolean contains(String word) { return personal.contains(word); }
            @Override public boolean add(String word) { return personal.addWord(word); }
            @Override public List<String> getEmailAddresses() { return personal.getEmailAddresses(); }
        };
    }

    private static DictionaryLoader assetLoader(Context context) {
        PersonalDictionary personal = new PersonalDictionary(context);
        return new DictionaryLoader() {
            @Override public int version() { return personal.getVersion(); }
            @Override public TypingCorrector.ContextModel loadContext() throws IOException {
                try (InputStream input = context.getAssets().open("swipe/en_context.tsv")) {
                    TypingCorrector.ContextModel base = EnglishContextModel.load(input);
                    try (InputStream phrases = context.getAssets().open("swipe/en_phrases.txt")) {
                        return new PhraseContextModel(phrases, base);
                    }
                }
            }
            @Override public TypingCorrector.ContextModel loadContext(boolean korean) throws IOException {
                if (!korean) return loadContext();
                try (InputStream phrases = context.getAssets().open("swipe/ko_phrases.txt")) {
                    return new PhraseContextModel(phrases, null);
                }
            }
            @Override public List<SwipeDecoder.Entry> load(boolean korean) throws IOException {
                List<SwipeDecoder.Entry> entries = new ArrayList<>();
                String path = korean ? "swipe/ko.tsv" : "swipe/en.tsv";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        context.getAssets().open(path), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int tab = line.indexOf('\t');
                        if (tab <= 0 || tab == line.length() - 1) continue;
                        try { entries.add(new SwipeDecoder.Entry(line.substring(0, tab),
                                Integer.parseInt(line.substring(tab + 1)))); }
                        catch (NumberFormatException ignored) { }
                    }
                }
                entries.addAll(personal.getEntries(korean));
                return entries;
            }
        };
    }

    private static final class ContextText {
        final String before, after;
        final Snapshot selection;
        ContextText(String before, String after, Snapshot selection) {
            this.before = before; this.after = after; this.selection = selection;
        }
    }

    private static final class Surrounding {
        final String before, after;
        Surrounding(String before, String after) { this.before = before; this.after = after; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Surrounding)) return false;
            Surrounding that = (Surrounding) other;
            return before.equals(that.before) && after.equals(that.after);
        }
        @Override public int hashCode() { return 31 * before.hashCode() + after.hashCode(); }
    }

    private static final class SelectionRead {
        final Snapshot snapshot;
        final boolean fallback;
        SelectionRead(Snapshot snapshot, boolean fallback) { this.snapshot = snapshot; this.fallback = fallback; }
    }

    private static final class ManualEdit {
        final InputConnection connection;
        final int position;
        final Surrounding surrounding;
        boolean notified;
        int oldStart, oldEnd, newStart, newEnd;
        ManualEdit(InputConnection connection, int position, Surrounding surrounding) {
            this.connection = connection; this.position = position; this.surrounding = surrounding;
        }
        void recordSelection(int oldStart, int oldEnd, int newStart, int newEnd) {
            this.notified = true;
            this.oldStart = oldStart; this.oldEnd = oldEnd;
            this.newStart = newStart; this.newEnd = newEnd;
        }
    }

    private static final class FallbackTransition {
        final int before, after;
        FallbackTransition(int before, int after) { this.before = before; this.after = after; }
    }

    private static final class Snapshot {
        final int offset, start;
        Snapshot(int offset, int start) { this.offset = offset; this.start = start; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Snapshot)) return false;
            Snapshot that = (Snapshot) other;
            return offset == that.offset && start == that.start;
        }
        @Override public int hashCode() { return 31 * offset + start; }
    }

    private static final class Request {
        final InputConnection connection;
        final String word, previousContext;
        final List<TouchModel.Sample> taps;
        final boolean korean;
        final int version;
        final long token;
        Request(InputConnection connection, String word, String previousContext,
                boolean korean, int version, long token, List<TouchModel.Sample> taps) {
            this.taps = new ArrayList<>(taps);
            this.connection = connection; this.word = word; this.previousContext = previousContext;
            this.korean = korean;
            this.version = version; this.token = token;
        }
    }

    private static final class Literal {
        final InputConnection connection;
        final String value, punctuation, before, after;
        final Snapshot selection;
        Literal(InputConnection connection, String value, String punctuation, ContextText text) {
            this.connection = connection; this.value = value; this.punctuation = punctuation;
            this.before = text.before; this.after = text.after; this.selection = text.selection;
        }
    }

    private static final class LiteralBoundary {
        final InputConnection connection;
        final String value, punctuation, before, after;
        final Snapshot selection;
        long token;
        boolean learningFailed;
        LiteralBoundary(InputConnection connection, String value, String punctuation,
                        ContextText text, long token) {
            this.connection = connection; this.value = value; this.punctuation = punctuation;
            this.before = text.before; this.after = text.after; this.selection = text.selection;
            this.token = token;
        }
    }

    private static final class ChosenWord {
        final InputConnection connection;
        final String word, punctuation, previousContext;
        final boolean korean;
        final Snapshot selection;
        final int version;
        ChosenWord(InputConnection connection, String word, String punctuation,
                   String previousContext, boolean korean, Snapshot selection, int version) {
            this.connection = connection;
            this.word = word;
            this.punctuation = punctuation;
            this.previousContext = previousContext;
            this.korean = korean;
            this.selection = selection;
            this.version = version;
        }
    }

    private static final class Boundary {
        final InputConnection connection;
        final String original, punctuation, previousContext;
        final boolean korean;
        int version;
        long token;
        String output;
        Snapshot selection;
        boolean automatic;
        boolean pending;
        boolean protectedWord;
        boolean learningFailed;
        boolean typedSpace = true;
        boolean observed;
        List<TouchModel.Sample> taps = Collections.emptyList();
        List<String> candidates;
        List<String> predictions = Collections.emptyList();
        Boundary(InputConnection connection, String original, String punctuation, String previousContext,
                 boolean korean,
                 boolean protectedWord, Snapshot selection,
                 int version, long token) {
            this.connection = connection; this.original = original; this.punctuation = punctuation;
            this.previousContext = previousContext;
            this.korean = korean; this.protectedWord = protectedWord;
            this.output = original; this.selection = selection; this.version = version; this.token = token;
            this.candidates = Collections.singletonList(original);
        }
    }
}
