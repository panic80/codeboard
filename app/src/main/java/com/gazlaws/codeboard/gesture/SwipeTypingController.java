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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Coordinates asynchronous gesture decoding, editor composition and the suggestion row. */
public final class SwipeTypingController {
    private static final String TAG = "SwipeTypingController";
    private static final String DEFAULT_HINT = "Swipe across letters to type";
    private static final String NO_MATCH_HINT = "No match. Try again.";

    /** Supplies the current editor connection. Called on the main thread. */
    public interface ConnectionProvider {
        InputConnection get();
    }

    interface DictionaryLoader {
        List<SwipeDecoder.Entry> load(boolean korean) throws IOException;
    }

    private final ConnectionProvider connections;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private final Handler mainHandler;
    private final DictionaryLoader loader;
    private PersonalDictionary personalDictionary;
    private int dictionaryVersion = Integer.MIN_VALUE;
    private final ArrayDeque<Runnable> deferred = new ArrayDeque<>();

    // These caches are only accessed by the single worker thread.
    private final List<SwipeDecoder.Entry>[] dictionaries;
    private final LinkedHashMap<Geometry, SwipeDecoder> decoders =
            new LinkedHashMap<Geometry, SwipeDecoder>(3, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Geometry, SwipeDecoder> eldest) {
                    return size() > 2;
                }
            };

    private SuggestionStripView strip;
    private InputConnection composingConnection;
    private List<String> candidates = Collections.emptyList();
    private String hint = DEFAULT_HINT;
    private boolean busy;
    private boolean closed;
    private boolean applyingEditorChange;
    private SelectionSnapshot pendingSelection;
    private SelectionSnapshot composingSelection;
    private long generation;
    private long compositionId;

    public SwipeTypingController(Context context, ConnectionProvider connections) {
        this(connections, Executors.newSingleThreadExecutor(), null,
                assetLoader(context.getApplicationContext()), new Handler(Looper.getMainLooper()));
        personalDictionary = new PersonalDictionary(context);
    }

    // Package-private injection makes ordering, failures and cancellation deterministic in tests.
    @SuppressWarnings("unchecked")
    SwipeTypingController(ConnectionProvider connections, Executor worker, ExecutorService ownedWorker,
                          DictionaryLoader loader, Handler mainHandler) {
        if (connections == null || worker == null || loader == null || mainHandler == null) {
            throw new IllegalArgumentException("Controller dependencies must not be null");
        }
        this.connections = connections;
        this.worker = worker;
        this.ownedWorker = ownedWorker != null ? ownedWorker :
                (worker instanceof ExecutorService ? (ExecutorService) worker : null);
        this.loader = loader;
        this.mainHandler = mainHandler;
        dictionaries = (List<SwipeDecoder.Entry>[]) new List<?>[2];
    }

    /** Installs or replaces the row; visibility remains controlled by the keyboard. */
    public void setSuggestionStrip(SuggestionStripView strip) {
        this.strip = strip;
        renderStrip();
    }

    /**
     * Defers an ordinary key, release, text, or gesture event while a preceding swipe is decoded.
     * The caller returns immediately when this method returns true. Events run in arrival order.
     */
    public boolean deferIfDecoding(Runnable event) {
        if (!busy || closed) return false;
        if (event != null) deferred.addLast(event);
        return true;
    }

    /** Starts a gesture using an immutable snapshot of its samples and key positions. */
    public void onSwipe(List<SwipeDecoder.Point> path, List<SwipeDecoder.KeyPosition> keys,
                        boolean korean, boolean capitalize, boolean capsLock) {
        if (closed) return;
        final List<SwipeDecoder.Point> pathCopy = path == null ? Collections.emptyList() : new ArrayList<>(path);
        final List<SwipeDecoder.KeyPosition> keyCopy = keys == null ? Collections.emptyList() : new ArrayList<>(keys);
        if (deferIfDecoding(() -> onSwipe(pathCopy, keyCopy, korean, capitalize, capsLock))) return;

        final InputConnection connection = connections.get();
        if (connection == null) {
            showHint(NO_MATCH_HINT);
            return;
        }
        busy = true;
        pendingSelection = readSelection(connection);
        final long token = ++generation;
        try {
            worker.execute(() -> {
                List<String> result = Collections.emptyList();
                int personalVersion = personalDictionary == null ? 0 : personalDictionary.getVersion();
                try {
                    if (dictionaryVersion != personalVersion) {
                        dictionaries[0] = null;
                        dictionaries[1] = null;
                        decoders.clear();
                        dictionaryVersion = personalVersion;
                    }
                    Geometry geometry = new Geometry(keyCopy, korean);
                    SwipeDecoder decoder = decoders.get(geometry);
                    if (decoder == null) {
                        int language = korean ? 1 : 0;
                        if (dictionaries[language] == null) {
                            dictionaries[language] = new ArrayList<>(loader.load(korean));
                            if (personalDictionary != null) {
                                dictionaries[language] = withPersonalWords(dictionaries[language],
                                        personalDictionary.getEntries(korean), korean);
                            }
                        }
                        decoder = new SwipeDecoder(keyCopy, dictionaries[language]);
                        decoders.put(geometry, decoder);
                    }
                    result = decoder.decode(pathCopy, 5);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "Could not decode swipe", e);
                }
                final List<String> decoded = result;
                mainHandler.post(() -> {
                    if (personalDictionary != null && personalDictionary.getVersion() != personalVersion) {
                        if (token == generation) {
                            busy = false;
                            pendingSelection = null;
                            showHint(NO_MATCH_HINT);
                            flushDeferred();
                        }
                        return;
                    }
                    onDecoded(token, connection, decoded, korean, capitalize, capsLock);
                });
            });
        } catch (RejectedExecutionException e) {
            busy = false;
            pendingSelection = null;
            Log.w(TAG, "Could not start swipe decoding", e);
            showHint(NO_MATCH_HINT);
            flushDeferred();
        }
    }

    public boolean hasComposition() {
        return composingConnection != null;
    }

    /** Removes the whole still-composing gesture word. */
    public boolean backspace(InputConnection connection) {
        if (composingConnection == null) return false;
        if (connection == null || connection != composingConnection) {
            clearComposition();
            return false;
        }
        applyingEditorChange = true;
        connection.beginBatchEdit();
        try {
            connection.setComposingText("", 1);
            connection.finishComposingText();
        } finally {
            connection.endBatchEdit();
            applyingEditorChange = false;
        }
        clearComposition();
        return true;
    }

    /** Finalizes the active word and optionally adds one separating space. */
    public void finish(InputConnection connection, boolean addSpace) {
        if (composingConnection == null) return;
        if (connection != null && connection == composingConnection) {
            applyingEditorChange = true;
            connection.beginBatchEdit();
            try {
                connection.finishComposingText();
                if (addSpace) connection.commitText(" ", 1);
            } finally {
                connection.endBatchEdit();
                applyingEditorChange = false;
            }
        }
        clearComposition();
    }

    /** Invalidates pending results and queued events, then finalizes any active word. */
    public void cancel(InputConnection connection) {
        generation++;
        busy = false;
        pendingSelection = null;
        deferred.clear();
        finish(connection, false);
        showHint(DEFAULT_HINT);
    }

    /** Finishes composition when the editor moves the cursor or selection away from its end. */
    public void onSelectionChanged(int newSelStart, int newSelEnd, int candidatesEnd) {
        onSelectionChanged(newSelStart, newSelEnd, newSelStart, newSelEnd, candidatesEnd);
    }

    /** Also rejects a pending result if the user moved to a different editor location. */
    public void onSelectionChanged(int oldSelStart, int oldSelEnd, int newSelStart,
                                   int newSelEnd, int candidatesEnd) {
        if (applyingEditorChange) return;
        boolean moved = oldSelStart != newSelStart || oldSelEnd != newSelEnd;
        boolean insideCompositionEnd = newSelStart == newSelEnd && candidatesEnd >= 0
                && newSelEnd == candidatesEnd;
        if (busy && moved && (composingConnection == null || !insideCompositionEnd)) {
            SelectionSnapshot current = readSelection(connections.get());
            // A delayed callback can describe an earlier key press even after a swipe starts.
            // If the editor is still where the gesture began, it is safe to keep decoding.
            boolean stillAtStart = pendingSelection != null && pendingSelection.equals(current);
            if (stillAtStart) return;
            cancel(connections.get());
            return;
        }
        if (composingConnection == null) return;
        if (!insideCompositionEnd) {
            SelectionSnapshot current = readSelection(connections.get());
            if (composingSelection != null && composingSelection.equals(current)) return;
            finish(connections.get(), false);
        }
    }

    /** Cancels queued work and releases the worker when the IME is destroyed. */
    public void close() {
        if (closed) return;
        cancel(connections.get());
        closed = true;
        if (ownedWorker != null) ownedWorker.shutdownNow();
        strip = null;
    }

    private void onDecoded(long token, InputConnection connection, List<String> decoded,
                           boolean korean, boolean capitalize, boolean capsLock) {
        if (closed || token != generation) return;
        busy = false;
        SelectionSnapshot expectedSelection = pendingSelection;
        pendingSelection = null;
        if (connections.get() != connection) {
            // Queued events belong to the old editor as well as the decode result.
            deferred.clear();
            clearComposition();
            return;
        }
        SelectionSnapshot currentSelection = readSelection(connection);
        if (expectedSelection != null && currentSelection != null &&
                !expectedSelection.equals(currentSelection)) {
            // The editor can move before it delivers its selection-change callback.
            deferred.clear();
            finish(connection, false);
            showHint(DEFAULT_HINT);
            return;
        }
        if (decoded.isEmpty()) {
            showHint(NO_MATCH_HINT);
            flushDeferred();
            return;
        }

        List<String> display = new ArrayList<>(decoded.size());
        for (String word : decoded) display.add(applyCapitalization(word, korean, capitalize, capsLock));
        applyingEditorChange = true;
        connection.beginBatchEdit();
        try {
            if (composingConnection == connection) connection.finishComposingText();
            composingConnection = null;
            if (needsSpace(connection)) connection.commitText(" ", 1);
            connection.setComposingText(display.get(0), 1);
            composingConnection = connection;
            compositionId++;
            candidates = display;
            hint = DEFAULT_HINT;
        } finally {
            connection.endBatchEdit();
            applyingEditorChange = false;
        }
        composingSelection = readSelection(connection);
        renderStrip();
        flushDeferred();
    }

    private void selectCandidate(long id, String word) {
        if (deferIfDecoding(() -> selectCandidate(id, word))) return;
        if (id != compositionId || composingConnection == null || connections.get() != composingConnection) return;
        applyingEditorChange = true;
        try {
            composingConnection.setComposingText(word, 1);
        } finally {
            applyingEditorChange = false;
        }
        composingSelection = readSelection(composingConnection);
    }

    private void flushDeferred() {
        while (!closed && !busy && !deferred.isEmpty()) deferred.removeFirst().run();
    }

    private void clearComposition() {
        composingConnection = null;
        composingSelection = null;
        compositionId++;
        candidates = Collections.emptyList();
        hint = DEFAULT_HINT;
        renderStrip();
    }

    private void showHint(String text) {
        hint = text;
        if (strip != null) {
            strip.setHintText(text);
            strip.setCandidates(Collections.emptyList(), null);
        }
    }

    private void renderStrip() {
        if (strip == null) return;
        strip.setHintText(hint);
        if (composingConnection != null && !candidates.isEmpty()) {
            final long id = compositionId;
            strip.setCandidates(candidates, candidate -> selectCandidate(id, candidate));
        } else {
            strip.setCandidates(Collections.emptyList(), null);
        }
    }

    private static boolean needsSpace(InputConnection connection) {
        CharSequence selected = connection.getSelectedText(0);
        if (selected != null && selected.length() > 0) return false;
        CharSequence before = connection.getTextBeforeCursor(2, 0);
        if (before == null || before.length() == 0) return false;
        int last = Character.codePointBefore(before, before.length());
        return Character.isLetterOrDigit(last) || last == '.' || last == ',' || last == '!'
                || last == '?' || last == ':' || last == ';';
    }

    private static SelectionSnapshot readSelection(InputConnection connection) {
        if (connection == null) return null;
        try {
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = 1;
            request.hintMaxLines = 1;
            ExtractedText text = connection.getExtractedText(request, 0);
            if (text == null || text.selectionStart < 0 || text.selectionEnd < 0) return null;
            return new SelectionSnapshot(text.startOffset, text.selectionStart, text.selectionEnd);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class SelectionSnapshot {
        final int offset;
        final int start;
        final int end;
        SelectionSnapshot(int offset, int start, int end) {
            this.offset = offset;
            this.start = start;
            this.end = end;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof SelectionSnapshot)) return false;
            SelectionSnapshot selection = (SelectionSnapshot) other;
            return offset == selection.offset && start == selection.start && end == selection.end;
        }
        @Override public int hashCode() { return 31 * (31 * offset + start) + end; }
    }

    private static String applyCapitalization(String word, boolean korean, boolean capitalize, boolean capsLock) {
        if (korean || word.isEmpty()) return word;
        if (capsLock) return word.toUpperCase(Locale.ROOT);
        if (capitalize || word.equals("i") || word.startsWith("i'") || word.startsWith("i’")) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return word;
    }

    private static DictionaryLoader assetLoader(Context context) {
        return korean -> {
            String name = korean ? "swipe/ko.tsv" : "swipe/en.tsv";
            ArrayList<SwipeDecoder.Entry> result = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open(name), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0 || tab == line.length() - 1) continue;
                    try {
                        int frequency = Integer.parseInt(line.substring(tab + 1));
                        result.add(new SwipeDecoder.Entry(line.substring(0, tab), frequency));
                    } catch (NumberFormatException ignored) {
                        // A corrupt row should not prevent the rest of the dictionary from loading.
                    }
                }
            }
            return result;
        };
    }

    private static List<SwipeDecoder.Entry> withPersonalWords(List<SwipeDecoder.Entry> builtIn,
                                                             List<SwipeDecoder.Entry> personal,
                                                             boolean korean) {
        if (personal.isEmpty()) return builtIn;
        Map<String, SwipeDecoder.Entry> merged = new LinkedHashMap<>();
        for (SwipeDecoder.Entry entry : builtIn) {
            merged.put(wordIdentity(entry.word, korean), entry);
        }
        for (SwipeDecoder.Entry entry : personal) {
            String identity = wordIdentity(entry.word, korean);
            SwipeDecoder.Entry previous = merged.get(identity);
            merged.put(identity, new SwipeDecoder.Entry(entry.word, previous == null
                    ? entry.frequency : Math.max(entry.frequency, previous.frequency)));
        }
        return new ArrayList<>(merged.values());
    }

    private static String wordIdentity(String word, boolean korean) {
        return korean ? word : word.replace('\u2019', '\'').toLowerCase(Locale.ROOT);
    }

    /** Structural cache key, including language and actual on-screen positions. */
    private static final class Geometry {
        final boolean korean;
        final List<SwipeDecoder.KeyPosition> keys;
        final int hash;

        Geometry(List<SwipeDecoder.KeyPosition> keys, boolean korean) {
            this.korean = korean;
            this.keys = new ArrayList<>(keys);
            int value = korean ? 1 : 0;
            for (SwipeDecoder.KeyPosition key : keys) {
                value = 31 * value + (key == null ? 0 : keyHash(key));
            }
            hash = value;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Geometry)) return false;
            Geometry other = (Geometry) object;
            if (korean != other.korean || keys.size() != other.keys.size()) return false;
            for (int i = 0; i < keys.size(); i++) {
                SwipeDecoder.KeyPosition a = keys.get(i), b = other.keys.get(i);
                if (a == b) continue;
                if (a == null || b == null || a.key != b.key ||
                        Float.floatToIntBits(a.centerX) != Float.floatToIntBits(b.centerX) ||
                        Float.floatToIntBits(a.centerY) != Float.floatToIntBits(b.centerY) ||
                        Float.floatToIntBits(a.width) != Float.floatToIntBits(b.width) ||
                        Float.floatToIntBits(a.height) != Float.floatToIntBits(b.height)) return false;
            }
            return true;
        }

        private static int keyHash(SwipeDecoder.KeyPosition key) {
            int value = key.key;
            value = 31 * value + Float.floatToIntBits(key.centerX);
            value = 31 * value + Float.floatToIntBits(key.centerY);
            value = 31 * value + Float.floatToIntBits(key.width);
            return 31 * value + Float.floatToIntBits(key.height);
        }
    }
}
