package com.gazlaws.codeboard.layout.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.GridView;
import android.widget.HorizontalScrollView;

import com.gazlaws.codeboard.R;
import com.gazlaws.codeboard.theme.ThemeDefinitions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EmojiPickerViewTest {
    @Before public void clearRecent() {
        RuntimeEnvironment.getApplication().getSharedPreferences("emoji_picker", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void categoriesInsertEmojiAndRecentSurvivesReopening() {
        Fixture fixture = new Fixture();
        fixture.pick(0);
        fixture.panel.findViewById(R.id.emoji_category_hearts).performClick();
        fixture.pick(0);
        fixture.panel.findViewById(R.id.emoji_category_food).performClick();
        fixture.pick(0);
        fixture.panel.findViewById(R.id.emoji_category_flags).performClick();
        fixture.pick(0);
        assertEquals(Arrays.asList("😀", "❤️", "🍎", "🇨🇦"), fixture.inserted);

        Fixture reopened = new Fixture();
        reopened.panel.findViewById(R.id.emoji_category_recent).performClick();
        assertEquals(4, reopened.grid.getAdapter().getCount());
        assertEquals("🇨🇦", reopened.grid.getAdapter().getItem(0));
        assertEquals("🍎", reopened.grid.getAdapter().getItem(1));
        assertEquals("😀", reopened.grid.getAdapter().getItem(3));
        reopened.pick(3);
        assertEquals("😀", reopened.grid.getAdapter().getItem(0));
        assertEquals(4, reopened.grid.getAdapter().getCount());
        assertEquals(Arrays.asList("😀"), reopened.inserted);
    }

    @Test public void emptyRecentAndPersistentControlsWorkInKeyboardSizedPanel() {
        Fixture fixture = new Fixture();
        fixture.panel.findViewById(R.id.emoji_category_recent).performClick();
        assertEquals(0, fixture.grid.getAdapter().getCount());
        assertEquals(View.VISIBLE, fixture.panel.findViewById(R.id.emoji_recent_empty).getVisibility());
        fixture.panel.findViewById(R.id.emoji_category_faces).performClick();
        fixture.layout();
        int minimum = Math.round(48 * fixture.activity.getResources().getDisplayMetrics().density);
        int[] controls = { R.id.emoji_category_recent, R.id.emoji_category_faces,
                R.id.emoji_category_hearts, R.id.emoji_category_food, R.id.emoji_category_flags,
                R.id.emoji_backspace, R.id.emoji_done };
        for (int id : controls) {
            View button = fixture.panel.findViewById(id);
            assertTrue(button.getWidth() >= minimum);
            assertTrue(button.getHeight() >= minimum);
        }
        assertTrue(fixture.grid.getHeight() < fixture.panel.getHeight());
        assertTrue(fixture.grid.getChildAt(0).getHeight() >= minimum);
        fixture.panel.findViewById(R.id.emoji_backspace).performClick();
        fixture.panel.findViewById(R.id.emoji_done).performClick();
        assertEquals(1, fixture.deletions);
        assertEquals(1, fixture.done);
    }

    @Test public void categoriesRemainReachableAtReducedKeyboardWidth() {
        Fixture fixture = new Fixture();
        fixture.layout(216);
        int minimum = Math.round(48 * fixture.activity.getResources().getDisplayMetrics().density);
        HorizontalScrollView scroll = fixture.panel.findViewById(R.id.emoji_categories_scroll);
        int[] controls = { R.id.emoji_category_recent, R.id.emoji_category_faces,
                R.id.emoji_category_hearts, R.id.emoji_category_food, R.id.emoji_category_flags };
        for (int id : controls) assertTrue(fixture.panel.findViewById(id).getWidth() >= minimum);
        scroll.scrollTo(scroll.getChildAt(0).getWidth(), 0);
        assertTrue(scroll.getScrollX() > 0);
        View flags = fixture.panel.findViewById(R.id.emoji_category_flags);
        assertTrue(flags.getRight() <= scroll.getScrollX() + scroll.getWidth());
        flags.performClick();
        fixture.pick(0);
        assertEquals(Arrays.asList("🇨🇦"), fixture.inserted);
    }

    private static final class Fixture implements EmojiPickerView.Listener {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final EmojiPickerView panel = new EmojiPickerView(activity, ThemeDefinitions.Default(), this);
        final GridView grid = panel.findViewById(R.id.emoji_grid);
        final List<String> inserted = new ArrayList<>();
        int deletions;
        int done;

        Fixture() {
            activity.setContentView(panel);
            layout();
        }

        void layout() { layout(320); }

        void layout(int widthDp) {
            float density = activity.getResources().getDisplayMetrics().density;
            panel.measure(View.MeasureSpec.makeMeasureSpec(Math.round(widthDp * density), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(240 * density), View.MeasureSpec.EXACTLY));
            panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        }

        void pick(int index) {
            View item = grid.getAdapter().getView(index, null, grid);
            assertTrue(grid.performItemClick(item, index, index));
        }

        @Override public void onEmoji(String emoji) { inserted.add(emoji); }
        @Override public void onBackspace() { deletions++; }
        @Override public void onDone() { done++; }
    }
}
