package com.gazlaws.codeboard;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import androidx.core.content.ContextCompat;

import java.util.Objects;
import java.util.UUID;

/** Holds a recognizer result only until the same, unchanged editor is active again. */
public final class VoiceInputBridge implements AutoCloseable {
    public interface Listener {
        InputConnection getCurrentInputConnection();
        void onVoiceText(String text);
    }

    private static final long EXPIRATION_MS = 120_000L;
    private static final int CONTEXT_LENGTH = 256;
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expire = this::clearPending;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) { receive(intent); }
    };
    private Pending pending;
    private boolean closed;

    public VoiceInputBridge(Context context, Listener listener) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.listener = Objects.requireNonNull(listener);
        ContextCompat.registerReceiver(this.context, receiver,
                new IntentFilter(VoiceInputActivity.ACTION_VOICE_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** Returns false when the current field or its cursor cannot be verified. */
    public boolean launch(InputConnection connection, EditorInfo info, int cursor, boolean korean) {
        clearPending();
        if (closed || connection == null || !allowsVoice(info) || cursor < 0
                || listener.getCurrentInputConnection() != connection) return false;
        Snapshot snapshot = Snapshot.read(connection, cursor);
        if (snapshot == null || listener.getCurrentInputConnection() != connection) return false;
        Pending request = new Pending(connection, info, cursor, snapshot);
        pending = request;
        handler.postDelayed(expire, EXPIRATION_MS);
        Intent intent = VoiceInputActivity.createIntent(context, korean ? "ko-KR" : "en", request.id);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            clearPending();
            return false;
        }
    }

    /**
     * Call when the input editor or its view starts. Pass Android's restarting flag,
     * and pass -1 for a selection range or unknown cursor position.
     */
    public void onEditorReady(InputConnection connection, EditorInfo info, int cursor,
                              boolean restarting) {
        if (expired() || pending == null) return;
        Pending request = pending;
        if (!request.matchesInfo(info) || (cursor >= 0 && cursor != request.cursor)) {
            clearPending();
            return;
        }
        if (connection == null) {
            request.ready = false;
            return;
        }
        // A new wrapper is safe only when Android says input restarted in the same editor.
        if (connection != request.connection && !restarting) {
            clearPending();
            return;
        }
        request.connection = connection;
        if (cursor < 0) {
            request.ready = false;
            return;
        }
        Snapshot current = Snapshot.read(connection, cursor);
        // Some editors cannot provide selection/context immediately while regaining focus.
        if (current == null) {
            request.ready = false;
            return;
        }
        if (!request.snapshot.equals(current)) {
            clearPending();
            return;
        }
        if (!request.wasUnavailable) return;
        request.ready = true;
        deliverIfReady();
    }

    /** Conservative overload when the platform has not confirmed a same-editor restart. */
    public void onEditorReady(InputConnection connection, EditorInfo info, int cursor) {
        onEditorReady(connection, info, cursor, false);
    }

    /** Call when the input view/window becomes hidden or the editor loses input focus. */
    public void onEditorUnavailable() {
        if (expired() || pending == null) return;
        pending.ready = false;
        pending.wasUnavailable = true;
    }

    /** Selection changes invalidate the request even if repeated text looks identical. */
    public void onSelectionChanged(int start, int end) {
        if (expired() || pending == null) return;
        if ((start >= 0 && start != pending.cursor) || (end >= 0 && end != pending.cursor)) {
            clearPending();
        }
    }

    /** Call before a manual edit, so older speech can never overwrite it. */
    public void onManualInput() { clearPending(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        clearPending();
        context.unregisterReceiver(receiver);
    }

    private void receive(Intent intent) {
        if (expired() || pending == null || intent == null
                || !VoiceInputActivity.ACTION_VOICE_RESULT.equals(intent.getAction())
                || !pending.id.equals(intent.getStringExtra(VoiceInputActivity.EXTRA_REQUEST_ID))) return;
        String text = intent.getStringExtra(VoiceInputActivity.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) {
            clearPending();
            return;
        }
        pending.text = text;
        deliverIfReady();
    }

    private void deliverIfReady() {
        if (expired() || pending == null || !pending.ready || pending.text == null) return;
        Pending request = pending;
        if (listener.getCurrentInputConnection() != request.connection
                || !request.snapshot.equals(Snapshot.read(request.connection, request.cursor))
                || listener.getCurrentInputConnection() != request.connection) {
            clearPending();
            return;
        }
        String text = request.text;
        clearPending();
        listener.onVoiceText(text);
    }

    private boolean expired() {
        if (pending == null || SystemClock.elapsedRealtime() - pending.startedAt < EXPIRATION_MS) {
            return false;
        }
        clearPending();
        return true;
    }

    private void clearPending() {
        handler.removeCallbacks(expire);
        pending = null;
    }

    private static boolean allowsVoice(EditorInfo info) {
        if (info == null || (info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
                || (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    private static final class Pending {
        final String id = UUID.randomUUID().toString();
        final long startedAt = SystemClock.elapsedRealtime();
        final String packageName;
        final String fieldName;
        final int fieldId;
        final int inputType;
        final int cursor;
        final Snapshot snapshot;
        InputConnection connection;
        boolean wasUnavailable;
        boolean ready;
        String text;

        Pending(InputConnection connection, EditorInfo info, int cursor, Snapshot snapshot) {
            this.connection = connection;
            this.packageName = info.packageName;
            this.fieldName = info.fieldName;
            this.fieldId = info.fieldId;
            this.inputType = info.inputType;
            this.cursor = cursor;
            this.snapshot = snapshot;
        }

        boolean matchesInfo(EditorInfo info) {
            return allowsVoice(info) && Objects.equals(packageName, info.packageName)
                    && Objects.equals(fieldName, info.fieldName) && fieldId == info.fieldId
                    && inputType == info.inputType;
        }
    }

    private static final class Snapshot {
        final String before;
        final String after;

        Snapshot(String before, String after) {
            this.before = before;
            this.after = after;
        }

        static Snapshot read(InputConnection connection, int cursor) {
            if (connection == null || cursor < 0) return null;
            try {
                ExtractedTextRequest request = new ExtractedTextRequest();
                request.hintMaxChars = CONTEXT_LENGTH * 2;
                ExtractedText extracted = connection.getExtractedText(request, 0);
                if (extracted == null || extracted.selectionStart < 0 || extracted.selectionEnd < 0
                        || (long) extracted.startOffset + extracted.selectionStart != cursor
                        || (long) extracted.startOffset + extracted.selectionEnd != cursor) return null;
                CharSequence before = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0);
                CharSequence after = connection.getTextAfterCursor(CONTEXT_LENGTH, 0);
                if (before == null || after == null) return null;
                return new Snapshot(before.toString(), after.toString());
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override public boolean equals(Object value) {
            if (!(value instanceof Snapshot)) return false;
            Snapshot other = (Snapshot) value;
            return before.equals(other.before) && after.equals(other.after);
        }

        @Override public int hashCode() { return Objects.hash(before, after); }
    }
}
