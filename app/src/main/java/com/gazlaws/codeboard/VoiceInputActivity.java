package com.gazlaws.codeboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import java.util.ArrayList;

/** Launches the device's speech recognizer and returns its result to the keyboard. */
public class VoiceInputActivity extends Activity {
    public static final String ACTION_VOICE_RESULT = "com.gazlaws.codeboard.action.VOICE_RESULT";
    public static final String EXTRA_REQUEST_ID = "com.gazlaws.codeboard.extra.VOICE_REQUEST_ID";
    public static final String EXTRA_TEXT = "com.gazlaws.codeboard.extra.VOICE_TEXT";
    private static final String EXTRA_LANGUAGE_TAG = "com.gazlaws.codeboard.extra.VOICE_LANGUAGE_TAG";
    private static final String STATE_LAUNCHED = "recognizer_launched";
    private static final String STATE_COMPLETED = "recognizer_completed";
    private static final int RECOGNIZE_REQUEST = 1;

    private String requestId;
    private boolean launched;
    private boolean completed;

    /** Create a launch intent with a unique request ID and an optional BCP 47 language tag. */
    public static Intent createIntent(Context context, String languageTag, String requestId) {
        Intent intent = new Intent(context, VoiceInputActivity.class);
        intent.putExtra(EXTRA_REQUEST_ID, requestId);
        if (languageTag != null && !languageTag.trim().isEmpty()) {
            intent.putExtra(EXTRA_LANGUAGE_TAG, languageTag);
        }
        return intent;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        if (requestId == null || requestId.isEmpty()) {
            finish();
            return;
        }
        if (savedInstanceState != null) {
            launched = savedInstanceState.getBoolean(STATE_LAUNCHED);
            completed = savedInstanceState.getBoolean(STATE_COMPLETED);
        }
        if (completed) {
            finish();
            return;
        }
        if (launched) return;

        Intent recognition = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognition.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognition.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        String language = getIntent().getStringExtra(EXTRA_LANGUAGE_TAG);
        if (language != null && !language.trim().isEmpty()) {
            recognition.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        }
        if (getPackageManager().resolveActivity(recognition, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            unavailable();
            return;
        }
        try {
            launched = true;
            startActivityForResult(recognition, RECOGNIZE_REQUEST);
        } catch (ActivityNotFoundException | SecurityException e) {
            unavailable();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_LAUNCHED, launched);
        state.putBoolean(STATE_COMPLETED, completed);
        super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RECOGNIZE_REQUEST || completed) return;
        String text = null;
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null) {
                for (String result : results) {
                    if (result != null && !result.trim().isEmpty()) {
                        text = result;
                        break;
                    }
                }
            }
        }
        finishWithResult(text);
    }

    private void unavailable() {
        Toast.makeText(this, R.string.voice_input_unavailable, Toast.LENGTH_SHORT).show();
        finishWithResult(null);
    }

    private void finishWithResult(String text) {
        sendResult(text);
        finish();
    }

    private void sendResult(String text) {
        if (completed || requestId == null || requestId.isEmpty()) return;
        completed = true;
        Intent result = new Intent(ACTION_VOICE_RESULT);
        result.setPackage(getPackageName());
        result.putExtra(EXTRA_REQUEST_ID, requestId);
        if (text != null) result.putExtra(EXTRA_TEXT, text);
        sendBroadcast(result);
    }

    @Override protected void onDestroy() {
        // Also release the waiting request if the transient host is dismissed directly.
        if (isFinishing()) sendResult(null);
        super.onDestroy();
    }
}
