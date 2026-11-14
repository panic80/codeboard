package com.gazlaws.codeboard.layout.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.TextView;

import com.gazlaws.codeboard.R;
import com.gazlaws.codeboard.theme.ThemeDefinitions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeyboardResizeViewTest {
    @Test public void draggingUpdatesValuesAndAccessibleLabels() {
        Fixture f = new Fixture(35, 85);
        f.layout();
        assertEquals(0, f.changes);
        assertEquals("Height: 35%", f.heightLabel.getText().toString());
        assertEquals("Width: 85%", f.widthLabel.getText().toString());

        drag(f.height, .25f, .85f);
        assertTrue(f.changes > 0);
        assertTrue(f.reportedHeight > 35);
        assertEquals(85, f.reportedWidth);
        assertEquals("Height: " + f.reportedHeight + "%", f.heightLabel.getText().toString());
        assertEquals("Keyboard height, " + f.reportedHeight + " percent",
                f.height.getContentDescription().toString());

        int height = f.reportedHeight;
        drag(f.width, .85f, .25f);
        assertEquals(height, f.reportedHeight);
        assertTrue(f.reportedWidth < 85);
        assertEquals("Width: " + f.reportedWidth + "%", f.widthLabel.getText().toString());
        AccessibilityNodeInfo node = f.width.createAccessibilityNodeInfo();
        assertEquals(50, (int) node.getRangeInfo().getMin());
        assertEquals(100, (int) node.getRangeInfo().getMax());
        assertEquals(f.reportedWidth, (int) node.getRangeInfo().getCurrent());

        int minTouch = Math.round(48 * f.activity.getResources().getDisplayMetrics().density);
        assertTrue(f.height.getHeight() >= minTouch);
        assertTrue(f.width.getHeight() >= minTouch);
        assertTrue(f.panel.findViewById(R.id.keyboard_resize_reset_button).getHeight() >= minTouch);
        assertTrue(f.panel.findViewById(R.id.keyboard_resize_done_button).getHeight() >= minTouch);
    }

    @Test public void controlsClampValuesAndResetIsSilentUntilUserChangesThem() {
        Fixture f = new Fixture(10, 120);
        assertEquals(20, f.panel.getHeightPercent());
        assertEquals(100, f.panel.getWidthPercent());
        f.panel.setSize(90, 10);
        assertEquals(70, f.panel.getHeightPercent());
        assertEquals(50, f.panel.getWidthPercent());
        assertEquals(0, f.changes);

        f.panel.findViewById(R.id.keyboard_resize_reset_button).performClick();
        assertEquals(1, f.resets);
        assertEquals(40, f.panel.getHeightPercent());
        assertEquals(100, f.panel.getWidthPercent());
        assertEquals("Height: 40%", f.heightLabel.getText().toString());
        assertEquals(0, f.changes);

        f.height.setProgress(45);
        assertEquals(1, f.changes);
        assertEquals(45, f.reportedHeight);
        assertEquals(100, f.reportedWidth);
        f.panel.findViewById(R.id.keyboard_resize_done_button).performClick();
        assertEquals(1, f.done);
    }

    private static void drag(View view, float from, float to) {
        float y = view.getHeight() / 2f;
        long downTime = 100;
        send(view, downTime, 100, MotionEvent.ACTION_DOWN, view.getWidth() * from, y);
        send(view, downTime, 120, MotionEvent.ACTION_MOVE, view.getWidth() * to, y);
        send(view, downTime, 140, MotionEvent.ACTION_UP, view.getWidth() * to, y);
    }

    private static void send(View view, long downTime, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, time, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private static final class Fixture implements KeyboardResizeView.Listener {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final KeyboardResizeView panel;
        final SeekBar height;
        final SeekBar width;
        final TextView heightLabel;
        final TextView widthLabel;
        int changes;
        int reportedHeight;
        int reportedWidth;
        int resets;
        int done;

        Fixture(int initialHeight, int initialWidth) {
            panel = new KeyboardResizeView(activity, ThemeDefinitions.Default(),
                    initialHeight, initialWidth, 20, 70, 50, 100, this);
            activity.setContentView(panel);
            height = panel.findViewById(R.id.keyboard_resize_height_slider);
            width = panel.findViewById(R.id.keyboard_resize_width_slider);
            heightLabel = panel.findViewById(R.id.keyboard_resize_height_label);
            widthLabel = panel.findViewById(R.id.keyboard_resize_width_label);
        }

        void layout() {
            float density = activity.getResources().getDisplayMetrics().density;
            panel.measure(View.MeasureSpec.makeMeasureSpec(Math.round(320 * density),
                            View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(600 * density),
                            View.MeasureSpec.AT_MOST));
            panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        }

        @Override public void onSizeChanged(int heightPercent, int widthPercent) {
            changes++;
            reportedHeight = heightPercent;
            reportedWidth = widthPercent;
        }
        @Override public void onReset() {
            resets++;
            panel.setSize(40, 100);
        }
        @Override public void onDone() { done++; }
    }
}
