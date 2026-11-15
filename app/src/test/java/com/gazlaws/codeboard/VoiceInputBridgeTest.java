package com.gazlaws.codeboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VoiceInputBridgeTest {
    private final Application context = RuntimeEnvironment.getApplication();
    private final List<String> inserted = new ArrayList<>();
    private VoiceInputBridge bridge;
    private Editor editor;
    private InputConnection current;
    private EditorInfo info;

    @Before public void setUp() {
        editor = new Editor(context, "hello world", 5);
        current = editor;
        info = field();
        bridge = new VoiceInputBridge(context, new VoiceInputBridge.Listener() {
            @Override public InputConnection getCurrentInputConnection() { return current; }
            @Override public void onVoiceText(String text) { inserted.add(text); }
        });
    }

    @After public void tearDown() { bridge.close(); }

    @Test public void resultWaitsUntilOriginalEditorReturnsAndIsDeliveredOnce() {
        String id = launch(false);
        Intent launch = lastStarted;
        assertEquals("en", launch.getStringExtra("com.gazlaws.codeboard.extra.VOICE_LANGUAGE_TAG"));
        assertTrue((launch.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        deliver("wrong-request", "bad");
        deliver(id, "hello from voice");
        assertTrue(inserted.isEmpty());
        bridge.onEditorUnavailable();
        bridge.onEditorReady(editor, info, 5, false);
        assertEquals(Arrays.asList("hello from voice"), inserted);
        deliver(id, "duplicate");
        assertEquals(1, inserted.size());
    }

    @Test public void resultArrivingAfterSameEditorReturnsIsDelivered() {
        String id = launch(true);
        assertEquals("ko-KR", lastStarted.getStringExtra("com.gazlaws.codeboard.extra.VOICE_LANGUAGE_TAG"));
        bridge.onEditorUnavailable();
        bridge.onEditorReady(editor, info, 5, false);
        assertTrue(inserted.isEmpty());
        deliver(id, "안녕하세요");
        assertEquals(Arrays.asList("안녕하세요"), inserted);
    }

    @Test public void otherEditorWithIdenticalTextAndMetadataCannotReceiveResult() {
        String id = launch(false);
        bridge.onEditorUnavailable();
        Editor other = new Editor(context, "hello world", 5);
        current = other;
        bridge.onEditorReady(other, field(), 5, false);
        deliver(id, "wrong field");
        assertTrue(inserted.isEmpty());
    }

    @Test public void platformConfirmedSameEditorRestartMayReplaceWrapper() {
        String id = launch(false);
        bridge.onEditorUnavailable();
        Editor wrapper = new Editor(context, editor.getEditable());
        current = wrapper;
        bridge.onEditorReady(wrapper, field(), 5, true);
        deliver(id, "same editor");
        assertEquals(Arrays.asList("same editor"), inserted);
    }

    @Test public void delayedCursorAndRestartedWrapperCanBecomeReadyAfterSelectionUpdate() {
        String id = launch(false);
        bridge.onEditorUnavailable();
        Editor wrapper = new Editor(context, editor.getEditable());
        current = wrapper;
        wrapper.extractedUnavailable = true;
        bridge.onEditorReady(wrapper, field(), -1, true);
        deliver(id, "wait for editor");
        assertTrue(inserted.isEmpty());
        wrapper.extractedUnavailable = false;
        bridge.onSelectionChanged(5, 5);
        bridge.onEditorReady(wrapper, field(), 5, false);
        assertEquals(Arrays.asList("wait for editor"), inserted);
    }

    @Test public void changedTextSelectionOrManualInputDiscardsResult() {
        String id = launch(false);
        bridge.onEditorUnavailable();
        editor.getEditable().replace(0, 1, "H");
        bridge.onEditorReady(editor, info, 5, false);
        deliver(id, "stale text");
        assertTrue(inserted.isEmpty());

        editor = new Editor(context, "hello world", 5);
        current = editor;
        id = launch(false);
        bridge.onEditorUnavailable();
        Selection.setSelection(editor.getEditable(), 4);
        bridge.onEditorReady(editor, info, 5, false);
        deliver(id, "stale cursor");
        assertTrue(inserted.isEmpty());

        editor = new Editor(context, "hello world", 5);
        current = editor;
        id = launch(false);
        bridge.onEditorUnavailable();
        bridge.onSelectionChanged(4, 4);
        bridge.onSelectionChanged(5, 5);
        bridge.onEditorReady(editor, info, 5, false);
        deliver(id, "cursor moved and returned");
        assertTrue(inserted.isEmpty());

        id = launch(false);
        bridge.onManualInput();
        bridge.onEditorUnavailable();
        bridge.onEditorReady(editor, info, 5, false);
        deliver(id, "manual edit");
        assertTrue(inserted.isEmpty());
    }

    @Test public void protectedOrUnverifiableFieldsNeverLaunch() {
        int[] types = { InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD };
        for (int type : types) {
            EditorInfo protectedField = field();
            protectedField.inputType = type;
            assertFalse(bridge.launch(editor, protectedField, 5, false));
        }
        assertFalse(bridge.launch(editor, info, -1, false));
        Selection.setSelection(editor.getEditable(), 3, 5);
        assertFalse(bridge.launch(editor, info, 5, false));
        Selection.setSelection(editor.getEditable(), 5);
        editor.extractedUnavailable = true;
        assertFalse(bridge.launch(editor, info, 5, false));
        editor.extractedUnavailable = false;
        editor.beforeUnavailable = true;
        assertFalse(bridge.launch(editor, info, 5, false));
        editor.beforeUnavailable = false;
        editor.afterUnavailable = true;
        assertFalse(bridge.launch(editor, info, 5, false));
    }

    @Test public void pendingTextExpiresAndCancellationClearsIt() {
        String id = launch(false);
        bridge.onEditorUnavailable();
        deliver(id, "expired");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121));
        bridge.onEditorReady(editor, info, 5, false);
        assertTrue(inserted.isEmpty());

        id = launch(false);
        bridge.onEditorUnavailable();
        deliver(id, null);
        bridge.onEditorReady(editor, info, 5, false);
        deliver(id, "after cancel");
        assertTrue(inserted.isEmpty());
    }

    @Test public void currentConnectionIsRecheckedAtDelivery() {
        String id = launch(false);
        bridge.onEditorUnavailable();
        bridge.onEditorReady(editor, info, 5, false);
        current = new Editor(context, "hello world", 5);
        deliver(id, "should not land elsewhere");
        assertTrue(inserted.isEmpty());
    }

    private Intent lastStarted;
    private String launch(boolean korean) {
        assertTrue(bridge.launch(editor, info, 5, korean));
        lastStarted = shadowOf(context).getNextStartedActivity();
        assertNotNull(lastStarted);
        String id = lastStarted.getStringExtra(VoiceInputActivity.EXTRA_REQUEST_ID);
        assertNotNull(id);
        return id;
    }

    private void deliver(String id, String text) {
        Intent result = new Intent(VoiceInputActivity.ACTION_VOICE_RESULT);
        result.setPackage(context.getPackageName());
        result.putExtra(VoiceInputActivity.EXTRA_REQUEST_ID, id);
        if (text != null) result.putExtra(VoiceInputActivity.EXTRA_TEXT, text);
        context.sendBroadcast(result);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static EditorInfo field() {
        EditorInfo result = new EditorInfo();
        result.packageName = "example.editor";
        result.fieldId = 101;
        result.inputType = InputType.TYPE_CLASS_TEXT;
        result.initialSelStart = result.initialSelEnd = 5;
        return result;
    }

    private static final class Editor extends BaseInputConnection {
        private final Editable editable;
        boolean extractedUnavailable;
        boolean beforeUnavailable;
        boolean afterUnavailable;

        Editor(Context context, String text, int cursor) {
            this(context, new SpannableStringBuilder(text));
            Selection.setSelection(editable, cursor);
        }

        Editor(Context context, Editable text) {
            super(new View(context), true);
            editable = text;
        }

        @Override public Editable getEditable() { return editable; }
        @Override public CharSequence getTextBeforeCursor(int n, int flags) {
            return beforeUnavailable ? null : super.getTextBeforeCursor(n, flags);
        }
        @Override public CharSequence getTextAfterCursor(int n, int flags) {
            return afterUnavailable ? null : super.getTextAfterCursor(n, flags);
        }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            if (extractedUnavailable) return null;
            ExtractedText result = new ExtractedText();
            result.text = editable.toString();
            result.startOffset = 0;
            result.selectionStart = Selection.getSelectionStart(editable);
            result.selectionEnd = Selection.getSelectionEnd(editable);
            return result;
        }
    }
}
