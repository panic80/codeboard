package com.gazlaws.codeboard.layout.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.view.MotionEvent;
import android.view.View;

import com.gazlaws.codeboard.layout.Box;
import com.gazlaws.codeboard.layout.Definitions;
import com.gazlaws.codeboard.layout.builder.KeyboardLayoutBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeyboardResizeMeasurementTest {
    @Test public void changingSizeRemeasuresKeysAndTouchHitsTheirNewPositions() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        KeyboardLayoutBuilder builder = new KeyboardLayoutBuilder(context).setBox(Box.create(0, 0, 1, 1));
        Definitions.addQwertyRows(builder);
        Actions actions = new Actions();
        KeyboardUiFactory factory = new KeyboardUiFactory(actions);
        factory.theme.size = .4f;
        factory.theme.sizeLandscape = .4f;
        KeyboardLayoutView keyboard = factory.createKeyboardView(context, builder.build());
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;

        layout(keyboard, 500, screenHeight);
        assertEquals((int) (screenHeight * .4f), keyboard.getHeight());
        View q = keyboard.getChildAt(0);
        int oldKeyWidth = q.getWidth();
        int oldKeyHeight = q.getHeight();
        keyboard.setKeyboardHeightPercent(60);
        layout(keyboard, 300, screenHeight);
        assertEquals(300, keyboard.getWidth());
        assertEquals((int) (screenHeight * .6f), keyboard.getHeight());
        assertEquals((int) (300 * .1f), q.getWidth());
        assertEquals(oldKeyWidth * 3 / 5, q.getWidth());
        assertEquals(oldKeyHeight * 3 / 2, q.getHeight(), 1);

        float x = (q.getLeft() + q.getRight()) / 2f;
        float y = (q.getTop() + q.getBottom()) / 2f;
        send(keyboard, MotionEvent.ACTION_DOWN, x, y);
        send(keyboard, MotionEvent.ACTION_UP, x, y);
        assertEquals(Collections.singletonList((int) 'q'), actions.keys);

        // An IME can constrain the keyboard to make room for the resize panel.
        layout(keyboard, 300, 150);
        assertEquals(150, keyboard.getHeight());
        keyboard.clearKeyboardHeightOverride();
        layout(keyboard, 300, screenHeight);
        assertEquals((int) (screenHeight * .4f), keyboard.getHeight());
    }

    private static void layout(KeyboardLayoutView keyboard, int width, int maximumHeight) {
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maximumHeight, View.MeasureSpec.AT_MOST));
        keyboard.layout(0, 0, keyboard.getMeasuredWidth(), keyboard.getMeasuredHeight());
    }

    private static void send(View view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(100, action == MotionEvent.ACTION_DOWN ? 100 : 120,
                action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private static final class Actions implements KeyboardView.OnKeyboardActionListener {
        final List<Integer> keys = new ArrayList<>();
        @Override public void onPress(int code) { }
        @Override public void onRelease(int code) { }
        @Override public void onKey(int code, int[] codes) { keys.add(code); }
        @Override public void onText(CharSequence text) { }
        @Override public void swipeLeft() { }
        @Override public void swipeRight() { }
        @Override public void swipeUp() { }
        @Override public void swipeDown() { }
    }
}
