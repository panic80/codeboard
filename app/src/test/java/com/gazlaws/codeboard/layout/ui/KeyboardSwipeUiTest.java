package com.gazlaws.codeboard.layout.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gazlaws.codeboard.layout.Box;
import com.gazlaws.codeboard.layout.Definitions;
import com.gazlaws.codeboard.layout.builder.KeyboardLayoutBuilder;
import com.gazlaws.codeboard.theme.ThemeDefinitions;
import com.gazlaws.codeboard.theme.ThemeInfo;

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
public class KeyboardSwipeUiTest {
    private static final long DOWN_TIME = 100;

    @Test
    public void letterTapSubmitsOnceOnReleaseWithCorrectOffsetGeometry() throws Exception {
        Fixture fixture = new Fixture(false, false);
        View q = fixture.layout.getChildAt(0);
        assertEquals(0, q.getTop()); // Child positions are local even if the keyboard is offset.
        send(fixture.layout, MotionEvent.ACTION_DOWN, 100, centerX(q), centerY(q));
        assertEquals(Collections.singletonList((int) 'q'), fixture.actions.presses);
        assertTrue(fixture.actions.keys.isEmpty());
        send(fixture.layout, MotionEvent.ACTION_UP, 120, centerX(q), centerY(q));
        assertEquals(Collections.singletonList((int) 'q'), fixture.actions.keys);
        assertEquals(Collections.singletonList((int) 'q'), fixture.actions.releases);
        assertTrue(fixture.swipes.isEmpty());
    }

    @Test
    public void swipeCapturesHistoricalSamplesAndFullKeyboardWithoutTypingStartKey()
            throws Exception {
        Fixture fixture = new Fixture(false, false);
        View q = fixture.layout.getChildAt(0);
        View w = fixture.layout.getChildAt(1);
        float middleX = (centerX(q) + centerX(w)) / 2;
        send(fixture.layout, MotionEvent.ACTION_DOWN, 100, centerX(q), centerY(q));
        MotionEvent move = MotionEvent.obtain(DOWN_TIME, 130, MotionEvent.ACTION_MOVE,
                middleX, centerY(q), 0);
        move.addBatch(140, centerX(w), centerY(w), 1, 1, 0);
        fixture.layout.dispatchTouchEvent(move);
        move.recycle();
        send(fixture.layout, MotionEvent.ACTION_UP, 150, centerX(w), centerY(w));

        assertTrue(fixture.actions.keys.isEmpty());
        assertEquals(Collections.singletonList(0), fixture.actions.releases);
        assertEquals(1, fixture.swipes.size());
        SwipeCapture swipe = fixture.swipes.get(0);
        assertEquals(4, swipe.path.size());
        assertEquals(130, swipe.path.get(1).timeMs);
        assertEquals(middleX, swipe.path.get(1).x, .01f);
        assertEquals(26, swipe.keys.size());
        assertEquals('q', swipe.keys.get(0).letter);
        assertEquals(centerX(q), swipe.keys.get(0).x, .01f);
        assertEquals(centerY(q), swipe.keys.get(0).y, .01f);
        assertEquals(q.getWidth(), swipe.keys.get(0).width, .01f);
    }

    @Test
    public void koreanGestureMapsPositionsAndExcludesCustomLetters() throws Exception {
        Fixture fixture = new Fixture(true, true);
        View q = fixture.layout.getChildAt(2);
        View w = fixture.layout.getChildAt(3);
        send(fixture.layout, MotionEvent.ACTION_DOWN, 100, centerX(q), centerY(q));
        send(fixture.layout, MotionEvent.ACTION_MOVE, 120, centerX(w), centerY(w));
        send(fixture.layout, MotionEvent.ACTION_UP, 130, centerX(w), centerY(w));
        assertEquals(1, fixture.swipes.size());
        List<SwipeGesture.KeyPosition> keys = fixture.swipes.get(0).keys;
        assertEquals(26, keys.size());
        assertEquals('q', keys.get(0).letter);
        assertEquals('w', keys.get(1).letter);

        // A custom literal letter still works as an immediate ordinary key.
        View customQ = fixture.layout.getChildAt(0);
        send(fixture.layout, MotionEvent.ACTION_DOWN, 200, centerX(customQ), centerY(customQ));
        assertEquals(Collections.singletonList((int) 'q'), fixture.actions.keys);
        send(fixture.layout, MotionEvent.ACTION_UP, 210, centerX(customQ), centerY(customQ));
    }

    @Test
    public void ctrlAndDisabledSwipeKeepImmediateKeyBehavior() throws Exception {
        Fixture fixture = new Fixture(false, false);
        View q = fixture.layout.getChildAt(0);
        fixture.layout.applyCtrlModifier(true);
        send(fixture.layout, MotionEvent.ACTION_DOWN, 100, centerX(q), centerY(q));
        assertEquals(Collections.singletonList((int) 'q'), fixture.actions.keys);
        send(fixture.layout, MotionEvent.ACTION_UP, 110, centerX(q), centerY(q));
        fixture.layout.applyCtrlModifier(false);
        fixture.layout.configureSwipe(false, fixture.listener);
        send(fixture.layout, MotionEvent.ACTION_DOWN, 200, centerX(q), centerY(q));
        assertEquals(Arrays.asList((int) 'q', (int) 'q'), fixture.actions.keys);
        send(fixture.layout, MotionEvent.ACTION_UP, 210, centerX(q), centerY(q));
        assertTrue(fixture.swipes.isEmpty());
    }

    @Test
    public void canceledAndMultiFingerGesturesNeverSubmitText() throws Exception {
        Fixture fixture = new Fixture(false, false);
        View q = fixture.layout.getChildAt(0);
        View w = fixture.layout.getChildAt(1);
        send(fixture.layout, MotionEvent.ACTION_DOWN, 100, centerX(q), centerY(q));
        send(fixture.layout, MotionEvent.ACTION_CANCEL, 110, centerX(q), centerY(q));
        send(fixture.layout, MotionEvent.ACTION_UP, 120, centerX(q), centerY(q));
        assertTrue(fixture.actions.keys.isEmpty());
        assertEquals(Collections.singletonList(0), fixture.actions.releases);

        send(fixture.layout, MotionEvent.ACTION_DOWN, 200, centerX(q), centerY(q));
        sendTwoPointers(fixture.layout, 210, MotionEvent.ACTION_POINTER_DOWN,
                centerX(q), centerY(q), centerX(w), centerY(w));
        sendTwoPointers(fixture.layout, 220, MotionEvent.ACTION_POINTER_UP,
                centerX(q), centerY(q), centerX(w), centerY(w));
        send(fixture.layout, MotionEvent.ACTION_UP, 230, centerX(q), centerY(q));
        assertTrue(fixture.actions.keys.isEmpty());
        assertEquals(Arrays.asList(0, 0), fixture.actions.releases);
        assertTrue(fixture.swipes.isEmpty());
    }

    @Test
    public void canceledSpaceDoesNotEmitTheSpaceRelease() throws Exception {
        Fixture fixture = new Fixture(false, false);
        View space = fixture.layout.getChildAt(fixture.layout.getChildCount() - 2);
        send(fixture.layout, MotionEvent.ACTION_DOWN, 100, centerX(space), centerY(space));
        send(fixture.layout, MotionEvent.ACTION_CANCEL, 110, centerX(space), centerY(space));
        assertEquals(Collections.singletonList((int) ' '), fixture.actions.keys);
        assertEquals(Collections.singletonList(0), fixture.actions.releases);
    }

    @Test
    public void stripReservesItsOwnHeightAndCandidatesAreClickable() throws Exception {
        Fixture fixture = new Fixture(false, false);
        Context context = RuntimeEnvironment.getApplication();
        SuggestionStripView strip = new SuggestionStripView(context, ThemeDefinitions.Default());
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(fixture.layout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int width = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST);
        container.measure(width, height);
        container.layout(0, 0, container.getMeasuredWidth(), container.getMeasuredHeight());
        assertTrue(strip.getHeight() > 0);
        assertEquals(strip.getBottom(), fixture.layout.getTop());
        TextView hint = (TextView) ((ViewGroup) strip.getChildAt(0)).getChildAt(0);
        assertEquals("Swipe across letters to type", hint.getText().toString());

        ArrayList<String> selections = new ArrayList<>();
        strip.setCandidates(Arrays.asList("hello", "안녕하세요"), selections::add);
        ViewGroup choices = (ViewGroup) strip.getChildAt(0);
        assertEquals(2, choices.getChildCount());
        container.measure(width, height);
        container.layout(0, 0, container.getMeasuredWidth(), container.getMeasuredHeight());
        assertEquals(strip.getHeight(), choices.getHeight());
        assertEquals(strip.getHeight(), choices.getChildAt(0).getHeight());
        assertTrue(choices.getChildAt(0).getWidth() > 0);
        assertTrue(choices.getChildAt(1).performClick());
        assertEquals(Collections.singletonList("안녕하세요"), selections);
        strip.setCandidates(Collections.emptyList(), selections::add);
        assertFalse(((ViewGroup) strip.getChildAt(0)).getChildAt(0).isClickable());
    }

    private static void send(View target, int action, long time, float x, float y) {
        MotionEvent event = MotionEvent.obtain(DOWN_TIME, time, action, x, y, 0);
        target.dispatchTouchEvent(event);
        event.recycle();
    }

    private static void sendTwoPointers(View target, long time, int action,
                                        float x0, float y0, float x1, float y1) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = i == 0 ? x0 : x1;
            coords[i].y = i == 0 ? y0 : y1;
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(DOWN_TIME, time,
                action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        target.dispatchTouchEvent(event);
        event.recycle();
    }

    private static float centerX(View v) { return (v.getLeft() + v.getRight()) / 2f; }
    private static float centerY(View v) { return (v.getTop() + v.getBottom()) / 2f; }

    private static final class Fixture {
        final RecordingActions actions = new RecordingActions();
        final List<SwipeCapture> swipes = new ArrayList<>();
        final SwipeGesture.Listener listener = (path, keys) -> swipes.add(new SwipeCapture(path, keys));
        final KeyboardLayoutView layout;

        Fixture(boolean korean, boolean customRow) throws Exception {
            Context context = RuntimeEnvironment.getApplication();
            KeyboardLayoutBuilder builder = new KeyboardLayoutBuilder(context)
                    .setBox(Box.create(0, 0, 1, 1));
            if (customRow) Definitions.addCustomRow(builder, "qz");
            if (korean) Definitions.addKoreanRows(builder);
            else Definitions.addQwertyRows(builder);
            new Definitions(context).addCustomSpaceRow(builder, "", korean);
            KeyboardUiFactory factory = new KeyboardUiFactory(actions);
            factory.theme.size = .5f;
            factory.theme.sizeLandscape = .5f;
            layout = factory.createKeyboardView(context, builder.build());
            layout.configureSwipe(true, listener);
            layout.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
            layout.layout(0, 50, 1000, 550);
        }
    }

    private static final class SwipeCapture {
        final List<SwipeGesture.Point> path;
        final List<SwipeGesture.KeyPosition> keys;
        SwipeCapture(List<SwipeGesture.Point> path, List<SwipeGesture.KeyPosition> keys) {
            this.path = path;
            this.keys = keys;
        }
    }

    private static final class RecordingActions implements KeyboardView.OnKeyboardActionListener {
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
