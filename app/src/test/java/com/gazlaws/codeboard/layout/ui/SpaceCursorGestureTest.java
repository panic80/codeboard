package com.gazlaws.codeboard.layout.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpaceCursorGestureTest {
    @Test public void tapStillUsesSpaceRelease() throws Exception {
        Fixture f = new Fixture();
        f.send(MotionEvent.ACTION_DOWN, 100, 0, 0);
        f.send(MotionEvent.ACTION_UP, 130, 0, 0);
        assertEquals(Collections.singletonList(32), f.actions.presses);
        assertEquals(Collections.singletonList(32), f.actions.keys);
        assertEquals(Collections.singletonList(32), f.actions.releases);
    }

    @Test public void horizontalDragCancelsLongPressAndMovesBothWaysWithoutSpace() throws Exception {
        Fixture f = new Fixture();
        f.send(MotionEvent.ACTION_DOWN, 100, 0, 0);
        f.send(MotionEvent.ACTION_MOVE, 120, 3 * f.step, 0);
        f.send(MotionEvent.ACTION_MOVE, 130, f.step, 0);
        f.send(MotionEvent.ACTION_UP, 140, f.step, 0);
        assertEquals(Collections.singletonList(32), f.actions.presses);
        assertEquals(Arrays.asList(32, Definitions.CODE_CURSOR_DRAG_START,
                5003, 5003, 5003, 5000, 5000, Definitions.CODE_CURSOR_DRAG_END), f.actions.keys);
        assertEquals(Collections.singletonList(0), f.actions.releases);
    }

    @Test public void verticalMotionAndDisabledControlKeepOrdinaryTap() throws Exception {
        Fixture f = new Fixture();
        f.send(MotionEvent.ACTION_DOWN, 100, 0, 0);
        f.send(MotionEvent.ACTION_MOVE, 120, 2, 3 * f.step);
        f.send(MotionEvent.ACTION_UP, 130, 2, 3 * f.step);
        f.keyboard.setCursorControlEnabled(false);
        f.send(MotionEvent.ACTION_DOWN, 200, 0, 0);
        f.send(MotionEvent.ACTION_MOVE, 220, 3 * f.step, 0);
        f.send(MotionEvent.ACTION_UP, 230, 3 * f.step, 0);
        assertEquals(Arrays.asList(32, 32), f.actions.keys);
        assertEquals(Arrays.asList(32, 32), f.actions.releases);
    }

    @Test public void releasePositionCanFinishDragWithoutAnIntermediateMove() throws Exception {
        Fixture f = new Fixture();
        f.send(MotionEvent.ACTION_DOWN, 100, 0, 0);
        f.send(MotionEvent.ACTION_UP, 120, -2 * f.step, 0);
        assertEquals(Arrays.asList(32, Definitions.CODE_CURSOR_DRAG_START,
                5000, 5000, Definitions.CODE_CURSOR_DRAG_END), f.actions.keys);
        assertEquals(Collections.singletonList(0), f.actions.releases);
    }

    @Test public void cancelAndLargeMoveDoNotCommitSpaceOrFloodEvents() throws Exception {
        Fixture f = new Fixture();
        f.send(MotionEvent.ACTION_DOWN, 100, 0, 0);
        f.send(MotionEvent.ACTION_MOVE, 120, 200 * f.step, 0);
        f.send(MotionEvent.ACTION_CANCEL, 130, 200 * f.step, 0);
        assertEquals(11, f.actions.keys.size()); // space-down, start, eight arrows and end
        assertEquals(Collections.singletonList(0), f.actions.releases);
        assertEquals((Integer) Definitions.CODE_CURSOR_DRAG_START, f.actions.keys.get(1));
        assertTrue(f.actions.keys.subList(2, 10).stream().allMatch(code -> code == 5003));
        assertEquals((Integer) Definitions.CODE_CURSOR_DRAG_END, f.actions.keys.get(10));
    }

    @Test public void disablingControlDuringDragEndsItExactlyOnce() throws Exception {
        Fixture f = new Fixture();
        f.send(MotionEvent.ACTION_DOWN, 100, 0, 0);
        f.send(MotionEvent.ACTION_MOVE, 120, f.step, 0);
        f.keyboard.setCursorControlEnabled(false);
        f.send(MotionEvent.ACTION_UP, 130, f.step, 0);
        assertEquals(Arrays.asList(32, Definitions.CODE_CURSOR_DRAG_START, 5003,
                Definitions.CODE_CURSOR_DRAG_END), f.actions.keys);
        assertEquals(Collections.singletonList(0), f.actions.releases);
    }

    private static final class Fixture {
        final Actions actions = new Actions();
        final KeyboardLayoutView keyboard;
        final View space;
        final float x;
        final float y;
        final float step;

        Fixture() throws Exception {
            Context context = RuntimeEnvironment.getApplication();
            KeyboardLayoutBuilder builder = new KeyboardLayoutBuilder(context)
                    .setBox(Box.create(0, 0, 1, 1));
            Definitions.addQwertyRows(builder);
            new Definitions(context).addCustomSpaceRow(builder, "");
            KeyboardUiFactory factory = new KeyboardUiFactory(actions);
            keyboard = factory.createKeyboardView(context, builder.build());
            keyboard.configureSwipe(true, (path, keys) -> { throw new AssertionError("space is not a letter swipe"); });
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
            keyboard.layout(0, 0, 1000, 500);
            space = keyboard.getChildAt(keyboard.getChildCount() - 2);
            x = (space.getLeft() + space.getRight()) / 2f;
            y = (space.getTop() + space.getBottom()) / 2f;
            step = Math.max(1, 20 * context.getResources().getDisplayMetrics().density);
        }

        void send(int action, long time, float dx, float dy) {
            MotionEvent event = MotionEvent.obtain(100, time, action, x + dx, y + dy, 0);
            assertTrue(keyboard.dispatchTouchEvent(event));
            event.recycle();
        }
    }

    private static final class Actions implements KeyboardView.OnKeyboardActionListener {
        final List<Integer> presses = new ArrayList<>();
        final List<Integer> keys = new ArrayList<>();
        final List<Integer> releases = new ArrayList<>();
        @Override public void onPress(int code) { presses.add(code); }
        @Override public void onRelease(int code) { releases.add(code); }
        @Override public void onKey(int code, int[] codes) { keys.add(code); }
        @Override public void onText(CharSequence text) { }
        @Override public void swipeLeft() { }
        @Override public void swipeRight() { }
        @Override public void swipeUp() { }
        @Override public void swipeDown() { }
    }
}
