package com.gazlaws.codeboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.speech.RecognizerIntent;

import androidx.core.content.ContextCompat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VoiceInputActivityTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final List<Intent> broadcasts = new ArrayList<>();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { broadcasts.add(intent); }
    };

    @Before public void register() {
        ContextCompat.registerReceiver(context, receiver,
                new IntentFilter(VoiceInputActivity.ACTION_VOICE_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    @After public void unregister() { context.unregisterReceiver(receiver); }

    @Test public void recognizerResultReturnsToPrivateReceiver() throws Exception {
        addRecognizer();
        Intent hostIntent = VoiceInputActivity.createIntent(context, "ko-KR", "request-1");
        ActivityController<VoiceInputActivity> controller = Robolectric.buildActivity(
                VoiceInputActivity.class, hostIntent).create().start().resume();
        VoiceInputActivity activity = controller.get();
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(started);
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, started.intent.getAction());
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                started.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
        assertEquals("ko-KR", started.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        Intent response = new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,
                new ArrayList<>(Arrays.asList("안녕하세요")));
        activity.onActivityResult(started.requestCode, Activity.RESULT_OK, response);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, broadcasts.size());
        Intent delivered = broadcasts.get(0);
        assertEquals(context.getPackageName(), delivered.getPackage());
        assertEquals("request-1", delivered.getStringExtra(VoiceInputActivity.EXTRA_REQUEST_ID));
        assertEquals("안녕하세요", delivered.getStringExtra(VoiceInputActivity.EXTRA_TEXT));
        assertTrue(activity.isFinishing());
        ActivityInfo info = context.getPackageManager().getActivityInfo(
                new ComponentName(context, VoiceInputActivity.class), 0);
        assertFalse(info.exported);
    }

    @Test public void unavailableRecognizerFinishesAndClearsRequest() {
        Intent host = VoiceInputActivity.createIntent(context, "en-CA", "request-2");
        VoiceInputActivity activity = Robolectric.buildActivity(VoiceInputActivity.class, host)
                .create().get();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(activity.isFinishing());
        assertEquals("Voice typing unavailable", ShadowToast.getTextOfLatestToast());
        assertEquals(1, broadcasts.size());
        assertEquals("request-2", broadcasts.get(0).getStringExtra(VoiceInputActivity.EXTRA_REQUEST_ID));
        assertNull(broadcasts.get(0).getStringExtra(VoiceInputActivity.EXTRA_TEXT));
    }

    @Test public void cancellationAndEmptyResultsNeverProduceText() {
        addRecognizer();
        VoiceInputActivity activity = Robolectric.buildActivity(VoiceInputActivity.class,
                VoiceInputActivity.createIntent(context, null, "request-3")).create().get();
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(started);
        assertNull(started.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        activity.onActivityResult(started.requestCode, Activity.RESULT_CANCELED, null);
        activity.onActivityResult(started.requestCode, Activity.RESULT_OK,
                new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,
                        new ArrayList<>(Arrays.asList("should be ignored"))));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, broadcasts.size());
        assertNull(broadcasts.get(0).getStringExtra(VoiceInputActivity.EXTRA_TEXT));
    }

    private void addRecognizer() {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "example.recognizer";
        info.activityInfo.name = "example.recognizer.Recognizer";
        info.activityInfo.exported = true;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(intent, info);
        assertNotNull(context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY));
    }
}
