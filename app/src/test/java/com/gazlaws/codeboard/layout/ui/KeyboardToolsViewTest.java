package com.gazlaws.codeboard.layout.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import com.gazlaws.codeboard.R;
import com.gazlaws.codeboard.theme.ThemeDefinitions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeyboardToolsViewTest {
    @Test public void everyVisibleActionHasATouchTargetAndInvokesItsOwnCallback() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        List<KeyboardToolsView.Action> chosen = new ArrayList<>();
        KeyboardToolsView panel = new KeyboardToolsView(activity, ThemeDefinitions.Default(), chosen::add);
        activity.setContentView(panel);
        float density = activity.getResources().getDisplayMetrics().density;
        panel.measure(View.MeasureSpec.makeMeasureSpec(Math.round(320 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(240 * density), View.MeasureSpec.EXACTLY));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        int[] ids = { R.id.keyboard_tools_emoji, R.id.keyboard_tools_clipboard,
                R.id.keyboard_tools_voice, R.id.keyboard_tools_resize,
                R.id.keyboard_tools_language, R.id.keyboard_tools_settings };
        int minimum = Math.round(48 * density);
        for (int id : ids) {
            View button = panel.findViewById(id);
            assertTrue(button.getWidth() >= minimum);
            assertTrue(button.getHeight() >= minimum);
            assertTrue(button.performClick());
        }
        assertEquals(Arrays.asList(KeyboardToolsView.Action.values()), chosen);
    }
}
