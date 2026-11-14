package com.gazlaws.codeboard.layout.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gazlaws.codeboard.R;
import com.gazlaws.codeboard.theme.ThemeInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SuggestionStripViewTest {
    @Test public void addWordStaysPinnedAndTappableWhileFiveCandidatesScroll() {
        Fixture f = new Fixture();
        AtomicInteger additions = new AtomicInteger();
        AtomicReference<String> chosen = new AtomicReference<>();
        f.strip.setTypedCandidates(Arrays.asList("teh", "the", "ten", "tea", "tech"), chosen::set);
        f.strip.setLearnWord("teh", additions::incrementAndGet);
        f.layout();

        assertEquals(5, f.strip.getCandidateCount());
        assertEquals(View.VISIBLE, f.action.getVisibility());
        assertEquals("Add word", f.label.getText().toString());
        assertEquals("teh", f.word.getText().toString());
        assertEquals("Add teh to personal dictionary", f.action.getContentDescription().toString());
        assertEquals(f.scroll.getRight(), f.action.getLeft());
        assertEquals(f.strip.getWidth(), f.action.getRight());
        int actionLeft = f.action.getLeft();
        f.scroll.scrollTo(f.scroll.getChildAt(0).getWidth(), 0);
        assertTrue(f.scroll.getScrollX() > 0);
        assertEquals(actionLeft, f.action.getLeft());
        assertTrue(f.strip.getCandidateView(4).getRight()
                <= f.scroll.getScrollX() + f.scroll.getWidth());

        float x = (f.action.getLeft() + f.action.getRight()) / 2f;
        float y = (f.action.getTop() + f.action.getBottom()) / 2f;
        MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(100, 120, MotionEvent.ACTION_UP, x, y, 0);
        assertTrue(f.strip.dispatchTouchEvent(down));
        assertTrue(f.strip.dispatchTouchEvent(up));
        down.recycle();
        up.recycle();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, additions.get());
        assertTrue(f.strip.getCandidateView(4).performClick());
        assertEquals("tech", chosen.get());
    }

    @Test public void typedWordAndLongTargetRemainIdentifiableWhenSuggestionsUpdate() {
        Fixture f = new Fixture();
        String word = "supercalifragilisticexpialidociouslongname";
        f.strip.setLearnWord(word, () -> { });
        f.strip.setTypedCandidates(Arrays.asList(word, "supercalifragilistic"), candidate -> { });
        f.layout();

        TextView original = f.strip.getCandidateView(0);
        assertTrue((original.getTypeface().getStyle() & Typeface.ITALIC) != 0);
        assertEquals("Typed word: " + word, original.getContentDescription().toString());
        assertEquals(word, f.word.getText().toString());
        assertEquals(TextUtils.TruncateAt.END, f.word.getEllipsize());
        assertTrue(f.word.getLayout().getEllipsisCount(0) > 0);
        assertTrue(original.getWidth() <= Math.round(180 * f.density));
        assertTrue(f.action.isClickable());

        f.strip.setTypedCandidates(Collections.singletonList(word), candidate -> { });
        assertEquals(View.VISIBLE, f.action.getVisibility());
        assertEquals(word, f.word.getText().toString());
    }

    @Test public void saveAndFailureFeedbackAreNotClickableAndClearOnRequest() {
        Fixture f = new Fixture();
        f.strip.setLearnWord("teh", () -> { });
        f.strip.showLearningFeedback("teh");
        assertEquals("Saved", f.label.getText().toString());
        assertEquals("teh", f.word.getText().toString());
        assertEquals("Added teh to personal dictionary", f.action.getContentDescription().toString());
        assertFalse(f.action.isClickable());
        assertFalse(f.action.performClick());

        f.strip.showLearningError("teh");
        assertEquals("Not saved", f.label.getText().toString());
        assertEquals("Could not add teh to personal dictionary", f.action.getContentDescription().toString());
        assertFalse(f.action.isClickable());
        f.strip.clearLearningAction();
        assertEquals(View.GONE, f.action.getVisibility());
        f.strip.setLearnWord(null, null);
        assertEquals(View.GONE, f.action.getVisibility());
    }

    private static final class Fixture {
        final float density;
        final SuggestionStripView strip;
        final HorizontalScrollView scroll;
        final LinearLayout action;
        final TextView label;
        final TextView word;

        Fixture() {
            Activity context = Robolectric.buildActivity(Activity.class).setup().get();
            density = context.getResources().getDisplayMetrics().density;
            ThemeInfo theme = new ThemeInfo();
            theme.backgroundColor = Color.BLACK;
            theme.foregroundColor = Color.WHITE;
            strip = new SuggestionStripView(context, theme);
            context.setContentView(strip);
            scroll = (HorizontalScrollView) strip.getChildAt(0);
            action = strip.findViewById(R.id.inline_learning_action);
            label = strip.findViewById(R.id.inline_learning_label);
            word = strip.findViewById(R.id.inline_learning_word);
        }

        void layout() {
            int width = Math.round(320 * density);
            strip.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(48 * density), View.MeasureSpec.EXACTLY));
            strip.layout(0, 0, strip.getMeasuredWidth(), strip.getMeasuredHeight());
        }
    }
}
