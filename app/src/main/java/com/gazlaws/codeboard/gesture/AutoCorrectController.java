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
    }

    interface PersonalWordStore {
        boolean contains(String word);
        boolean add(String word);
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
    private boolean wordProtected;
    private long generation;

    public AutoCorrectController(Context context, ConnectionProvider connections, Runnable beforeEdit) {
        this(connections, beforeEdit, Executors.newSingleThreadExecutor(), null,
                assetLoader(context.getApplicationContext()), new Handler(Looper.getMainLooper()),
                personalWordStore(new PersonalDictionary(context.getApplicationContext())));
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

    public void setSuggestionStrip(SuggestionStripView strip) {
        if (this.strip != null && this.strip != strip) clearSuggestions();
        this.strip = strip;
        if (strip != null && word != null && validTrackedText(readContext(wordConnection))) {
            requestSuggestions();
        } else if (strip != null && boundary != null && validBoundaryLocation(boundary)) {
            showBoundaryCandidates(boundary);
        }
    }

    /** Controls automatic replacement without disabling manual suggestions or learning. */
    public void setAutoCorrectionEnabled(boolean enabled) {
        if (autoCorrectionEnabled == enabled) return;
        autoCorrectionEnabled = enabled;
        cancel();
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
        invalidateBoundary();
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
        invalidateBoundary();
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
        invalidateBoundary();
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
        if (blocked || word == null || connection != wordConnection || text == null ||
                !validTrackedText(text)) {
            clearWord(); blocked = false; blockedSelection = null; invalidateBoundary(); return false;
        }
        String original = word, suffix = punctuation;
        boolean language = korean;
        boolean protectedWord = wordProtected || isPersonalWord(original);
        int version = dictionaryVersion();
        long token = ++generation;
        cancelQueuedSuggestions();
        applyingEdit = true;
        boolean inserted;
        try { inserted = connection.commitText(" ", 1); }
        finally { applyingEdit = false; }
        clearWord(); blocked = false; blockedSelection = null;
        clearSuggestions();
        if (!inserted) return false;
        ContextText after = readContext(connection);
        if (after == null || !after.before.endsWith(original + suffix + " ")) return true;
        Boundary next = new Boundary(connection, original, suffix, language, protectedWord,
                after.selection, version, token);
        boundary = next;
        next.pending = version != Integer.MIN_VALUE;
        // Manual suggestions cannot rewrite the editor, so typing need not wait for their search.
        busy = next.pending && autoCorrectionEnabled;
        showBoundaryCandidates(next);
        if (!next.pending) return true;
        try {
            worker.execute(() -> {
                TypingCorrector.Result result = compute(original, language, version);
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
        if (!replaceBoundary(current, current.original)) return false;
        current.automatic = false;
        showBoundaryCandidates(current);
        return true;
    }

    /** Invalidates a word if the actual editor selection moves away from its tracked location. */
    public void onSelectionChanged(int oldStart, int oldEnd, int newStart, int newEnd, int candidatesEnd) {
        if (closed || applyingEdit) return;
        InputConnection connection = connections.get();
        Snapshot actual = selection(connection);
        if (blocked && word == null && boundary == null && !busy) {
            if (blockedSelection == null || !blockedSelection.equals(actual)) {
                blocked = false;
                blockedSelection = null;
            }
            return;
        }
        Snapshot expected = busy || boundary != null ? (boundary == null ? null : boundary.selection) : wordSelection;
        if (expected != null && expected.equals(actual) &&
                (word == null || validTrackedText(readContext(connection)))) return;
        if (word == null && boundary == null && !busy) return;
        generation++;
        cancelQueuedSuggestions();
        clearWord(); blocked = false; blockedSelection = null; boundary = null; busy = false; clearSuggestions();
        // Pending input belongs to the original caret location, not the new selection.
        deferred.clear();
    }

    /** Resets state on focus, editor, mode, preference, paste or non-keyboard changes. */
    public void cancel() {
        generation++;
        cancelQueuedSuggestions();
        clearWord(); blocked = false; blockedSelection = null; boundary = null; busy = false;
        deferred.clear();
        clearSuggestions();
    }

    public void close() {
        if (closed) return;
        cancel();
        closed = true;
        if (ownedWorker != null) ownedWorker.shutdownNow();
        strip = null;
    }

    private void requestSuggestions() {
        long token = ++generation;
        showCurrentWord(token);
        int version = dictionaryVersion();
        if (version == Integer.MIN_VALUE) return;
        Request request = new Request(wordConnection, word, korean, version, token);
        synchronized (requestLock) {
            queuedSuggestion = request;
            if (suggestionScheduled) return;
            suggestionScheduled = true;
        }
        scheduleSuggestionWorker();
    }

    private void showCurrentWord(long token) {
        if (strip == null || word == null) return;
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
        showBoundaryCandidates(current);
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

    private void scheduleSuggestionWorker() {
        try {
            worker.execute(() -> {
                Request request;
                synchronized (requestLock) { request = queuedSuggestion; queuedSuggestion = null; }
                if (request != null) {
                    TypingCorrector.Result result = compute(request.word, request.korean, request.version);
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

    private TypingCorrector.Result compute(String typed, boolean korean, int version) {
        try {
            if (dictionaryVersion() != version) return null;
            int language = korean ? 1 : 0;
            if (engines[language] == null || engineVersions[language] != version) {
                engines[language] = new TypingCorrector(loader.load(korean), korean);
                engineVersions[language] = version;
            }
            TypingCorrector.Result result = engines[language].suggest(typed, 5);
            return dictionaryVersion() == version ? result : null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not load or search the typing dictionary", e);
            return null;
        }
    }

    private int dictionaryVersion() {
        try { return loader.version(); }
        catch (RuntimeException e) {
            Log.w(TAG, "Could not read typing dictionary version", e);
            return Integer.MIN_VALUE;
        }
    }

    private void showSuggestions(Request request, TypingCorrector.Result result) {
        if (closed || result == null || request.token != generation || busy || word == null ||
                request.connection != connections.get() || !request.word.equals(word) ||
                request.version != dictionaryVersion() || !validTrackedText(readContext(request.connection))) return;
        if (strip != null) {
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
            if (autoCorrectionEnabled && !current.protectedWord && !result.exactWord &&
                    result.correction != null && !result.correction.equals(current.original) &&
                    replaceBoundary(current, result.correction)) current.automatic = true;
            if (!validBoundaryLocation(current)) {
                boundary = null; clearSuggestions(); deferred.clear();
                return;
            }
        } else {
            // Stale or unavailable dictionaries must not hide an otherwise valid Add action.
            current.version = dictionaryVersion();
        }
        showBoundaryCandidates(current);
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
        String replace = word + punctuation;
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
        if (!changed) return;
        word = candidate;
        ContextText text = readContext(connection);
        if (text == null) { blockWord(); return; }
        wordSelection = text.selection;
        // A manually selected spelling is final for this word, but can still be edited.
        generation++;
        clearSuggestions();
        preserveChosenCandidate();
    }

    private void preserveChosenCandidate() {
        // Do not second-guess a candidate explicitly selected by the user at the next space.
        clearWord();
        blocked = true;
        blockedSelection = selection(connections.get());
    }

    private void showBoundaryCandidates(Boundary current) {
        if (strip == null || boundary != current) return;
        long token = current.token;
        strip.setTypedCandidates(current.candidates, candidate -> {
            if (deferIfBusy(() -> selectBoundaryCandidate(current, token, candidate))) return;
            selectBoundaryCandidate(current, token, candidate);
        });
        showBoundaryLearning(current);
    }

    private void selectBoundaryCandidate(Boundary current, long token, String candidate) {
        if (closed || boundary != current || token != generation || current.token != token ||
                candidate == null || !validBoundary(current)) return;
        if (replaceBoundary(current, candidate)) {
            current.automatic = false;
            showBoundaryCandidates(current);
        }
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
        return text != null && current.selection.equals(text.selection) &&
                text.before.endsWith(current.output + current.punctuation + " ");
    }

    private boolean validTrackedText(ContextText text) {
        String expected = word == null ? null : word + punctuation;
        return text != null && expected != null && connections.get() == wordConnection &&
                wordSelection != null && wordSelection.equals(text.selection) && safeAfter(text.after) &&
                text.before.endsWith(expected) && samePrefix(text.before, expected, prefix);
    }

    private void invalidateBoundary() {
        if (boundary != null) { boundary = null; generation++; clearSuggestions(); }
    }

    private void blockWord() {
        generation++;
        cancelQueuedSuggestions();
        clearWord(); blocked = true; blockedSelection = selection(connections.get()); clearSuggestions();
    }

    private void clearWord() {
        word = null; wordConnection = null; wordSelection = null; prefix = null; punctuation = "";
        wordProtected = false;
    }

    private void clearSuggestions() {
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

    private static ContextText readContext(InputConnection connection) {
        if (connection == null) return null;
        try {
            Snapshot selection = selection(connection);
            CharSequence before = connection.getTextBeforeCursor(CONTEXT_LIMIT, 0);
            CharSequence after = connection.getTextAfterCursor(CONTEXT_LIMIT, 0);
            if (selection == null || before == null || after == null) return null;
            return new ContextText(before.toString(), after.toString(), selection);
        } catch (RuntimeException e) { return null; }
    }

    private static Snapshot selection(InputConnection connection) {
        if (connection == null) return null;
        try {
            ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
            if (extracted == null || extracted.selectionStart < 0 ||
                    extracted.selectionStart != extracted.selectionEnd) return null;
            return new Snapshot(extracted.startOffset, extracted.selectionStart);
        } catch (RuntimeException e) { return null; }
    }

    private static PersonalWordStore personalWordStore(PersonalDictionary personal) {
        return new PersonalWordStore() {
            @Override public boolean contains(String word) {
                String normalized = Normalizer.normalize(word, Normalizer.Form.NFC).replace('’', '\'');
                for (String stored : personal.getWords()) {
                    if (stored.replace('’', '\'').equalsIgnoreCase(normalized)) return true;
                }
                return false;
            }
            @Override public boolean add(String word) { return personal.addWord(word); }
        };
    }

    private static DictionaryLoader assetLoader(Context context) {
        PersonalDictionary personal = new PersonalDictionary(context);
        return new DictionaryLoader() {
            @Override public int version() { return personal.getVersion(); }
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
        final String word;
        final boolean korean;
        final int version;
        final long token;
        Request(InputConnection connection, String word, boolean korean, int version, long token) {
            this.connection = connection; this.word = word; this.korean = korean;
            this.version = version; this.token = token;
        }
    }

    private static final class Boundary {
        final InputConnection connection;
        final String original, punctuation;
        final boolean korean;
        int version;
        long token;
        String output;
        Snapshot selection;
        boolean automatic;
        boolean pending;
        boolean protectedWord;
        boolean learningFailed;
        List<String> candidates;
        Boundary(InputConnection connection, String original, String punctuation, boolean korean,
                 boolean protectedWord, Snapshot selection,
                 int version, long token) {
            this.connection = connection; this.original = original; this.punctuation = punctuation;
            this.korean = korean; this.protectedWord = protectedWord;
            this.output = original; this.selection = selection; this.version = version; this.token = token;
            this.candidates = Collections.singletonList(original);
        }
    }
}
