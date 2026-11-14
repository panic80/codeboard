package com.gazlaws.codeboard.layout.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import com.gazlaws.codeboard.layout.Box;
import com.gazlaws.codeboard.layout.Definitions;
import com.gazlaws.codeboard.layout.builder.KeyboardLayoutBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ResizableInputViewTest {
    @Test public void keyboardRowsUseActualParentWidthAndFullWidthPanelStaysIndependent() {
        Context context = RuntimeEnvironment.getApplication();
        ResizableInputView root = new ResizableInputView(context);
        View suggestions = fixedHeight(context, 48);
        View keyboard = fixedHeight(context, 200);
        root.addKeyboardView(suggestions);
        root.addKeyboardView(keyboard);
        layout(root, 1000, 900);

        assertSame(suggestions, root.getChildAt(0));
        assertSame(keyboard, root.getChildAt(1));
        assertEquals(1000, root.getWidth());
        assertEquals(1000, suggestions.getWidth());
        assertEquals(1000, keyboard.getWidth());
        assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, keyboard.getLayoutParams().width);

        root.setKeyboardWidthPercent(80);
        View panel = fixedHeight(context, 144);
        root.addFullWidthView(panel, 0);
        layout(root, 1000, 900);
        assertEquals(1000, root.getWidth());
        assertEquals(1000, panel.getWidth());
        assertEquals(800, suggestions.getWidth());
        assertEquals(800, keyboard.getWidth());
        assertEquals(100, suggestions.getLeft());
        assertEquals(100, keyboard.getLeft());
        assertEquals(0, panel.getLeft());

        // Removing and reusing a full-width child must not retain its old role.
        root.removeView(panel);
        root.addKeyboardView(panel);
        root.setKeyboardWidthPercent(10); // Values outside 60–100 are clamped.
        layout(root, 1000, 900);
        assertEquals(600, panel.getWidth());
        assertEquals(200, panel.getLeft());
        root.setKeyboardWidthPercent(150);
        layout(root, 700, 900);
        assertEquals(700, keyboard.getWidth());
        assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, keyboard.getLayoutParams().width);
    }

    @Test public void keyTouchesFollowCenteredKeyboardAfterBothDimensionsChange() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ResizableInputView root = new ResizableInputView(context);
        View panel = fixedHeight(context, 100);
        View suggestions = fixedHeight(context, 48);
        KeyboardLayoutBuilder builder = new KeyboardLayoutBuilder(context).setBox(Box.create(0, 0, 1, 1));
        Definitions.addQwertyRows(builder);
        Actions actions = new Actions();
        KeyboardUiFactory factory = new KeyboardUiFactory(actions);
        factory.theme.size = .4f;
        factory.theme.sizeLandscape = .4f;
        KeyboardLayoutView keyboard = factory.createKeyboardView(context, builder.build());
        root.addFullWidthView(panel);
        root.addKeyboardView(suggestions);
        root.addKeyboardView(keyboard);

        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        keyboard.setKeyboardHeightPercent(60);
        root.setKeyboardWidthPercent(80);
        layout(root, 1000, screenHeight);
        assertEquals(800, keyboard.getWidth());
        assertEquals((int) (screenHeight * .6f), keyboard.getHeight());
        tapKey(root, keyboard, keyboard.getChildAt(0));

        keyboard.setKeyboardHeightPercent(30);
        root.setKeyboardWidthPercent(60);
        layout(root, 1000, screenHeight);
        assertEquals(600, keyboard.getWidth());
        assertEquals(200, keyboard.getLeft());
        assertEquals((int) (screenHeight * .3f), keyboard.getHeight());
        tapKey(root, keyboard, keyboard.getChildAt(0));
        assertEquals(Arrays.asList((int) 'q', (int) 'q'), actions.keys);

        // The panel remains visible if the window is smaller than the requested keyboard.
        keyboard.setKeyboardHeightPercent(85);
        layout(root, 1000, 300);
        assertEquals(100, panel.getHeight());
        assertEquals(48, suggestions.getHeight());
        assertTrue(keyboard.getBottom() <= root.getHeight());
    }

    private static View fixedHeight(Context context, int height) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        return view;
    }

    private static void layout(ResizableInputView root, int width, int maximumHeight) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maximumHeight, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private static void tapKey(ResizableInputView root, KeyboardLayoutView keyboard, View key) {
        float x = keyboard.getLeft() + (key.getLeft() + key.getRight()) / 2f;
        float y = keyboard.getTop() + (key.getTop() + key.getBottom()) / 2f;
        send(root, MotionEvent.ACTION_DOWN, x, y);
        send(root, MotionEvent.ACTION_UP, x, y);
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
