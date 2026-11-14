package com.gazlaws.codeboard;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.preference.Preference;

import com.gazlaws.codeboard.gesture.PersonalDictionary;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class PersonalDictionarySettingsTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("personal_dictionary", Context.MODE_PRIVATE).edit().clear().commit();
        new KeyboardPreferences(RuntimeEnvironment.getApplication()).setFirstStart(false);
    }

    @Test public void userCanAddAndRemoveAWordAndSeesValidationErrors() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        SettingsFragment settings = (SettingsFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.settings_container);
        assertNotNull(settings);
        Preference preference = settings.findPreference("personal_dictionary");
        assertNotNull(preference);
        settings.onPreferenceTreeClick(preference);
        shadowOf(Looper.getMainLooper()).idle();

        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        TextView message = list.findViewById(android.R.id.message);
        assertNotNull(message);
        assertTrue(message.getText().toString().contains("empty"));
        click(list.getButton(DialogInterface.BUTTON_POSITIVE));

        AlertDialog add = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = findInput(add.getWindow().getDecorView());
        assertNotNull(input);
        input.setText("two words");
        click(add.getButton(DialogInterface.BUTTON_POSITIVE));
        assertTrue(add.isShowing());
        assertNotNull(input.getError());
        input.setText("Kim");
        click(add.getButton(DialogInterface.BUTTON_POSITIVE));
        assertEquals("Kim", new PersonalDictionary(context).getWords().get(0));

        list = ShadowAlertDialog.getLatestAlertDialog();
        click(list.getButton(DialogInterface.BUTTON_POSITIVE));
        add = ShadowAlertDialog.getLatestAlertDialog();
        input = findInput(add.getWindow().getDecorView());
        input.setText("KIM");
        click(add.getButton(DialogInterface.BUTTON_POSITIVE));
        assertTrue(add.isShowing());
        assertTrue(input.getError().toString().contains("already"));
        click(add.getButton(DialogInterface.BUTTON_NEGATIVE));

        list = ShadowAlertDialog.getLatestAlertDialog();
        ListView words = list.getListView();
        assertEquals(1, words.getAdapter().getCount());
        words.performItemClick(null, 0, words.getAdapter().getItemId(0));
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog remove = ShadowAlertDialog.getLatestAlertDialog();
        click(remove.getButton(DialogInterface.BUTTON_POSITIVE));
        assertTrue(new PersonalDictionary(context).getWords().isEmpty());
        list = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(((TextView) list.findViewById(android.R.id.message)).getText().toString().contains("empty"));
    }

    private static void click(View view) {
        view.performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText result = findInput(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }
}
