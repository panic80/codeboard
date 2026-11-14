package com.gazlaws.codeboard.layout.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.HashSet;
import java.util.Set;

/** A full-width IME container whose keyboard rows can be resized and centered. */
@SuppressLint("ViewConstructor")
public class ResizableInputView extends LinearLayout {
    public static final int MIN_WIDTH_PERCENT = 60;
    public static final int MAX_WIDTH_PERCENT = 100;
    private final Set<View> fullWidthViews = new HashSet<>();
    private int keyboardWidthPercent = MAX_WIDTH_PERCENT;

    public ResizableInputView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
    }

    /** Changes the width of keyboard children as a percentage of this container. */
    public void setKeyboardWidthPercent(int percent) {
        int clamped = Math.max(MIN_WIDTH_PERCENT, Math.min(MAX_WIDTH_PERCENT, percent));
        if (keyboardWidthPercent == clamped) return;
        keyboardWidthPercent = clamped;
        requestLayout();
    }

    public void addKeyboardView(View view) {
        addKeyboardView(view, -1);
    }

    public void addKeyboardView(View view, int index) {
        addSizedView(view, index, false);
    }

    public void addFullWidthView(View view) {
        addFullWidthView(view, -1);
    }

    public void addFullWidthView(View view, int index) {
        addSizedView(view, index, true);
    }

    private void addSizedView(View view, int index, boolean fullWidth) {
        ViewGroup.LayoutParams existing = view.getLayoutParams();
        LinearLayout.LayoutParams params;
        if (existing instanceof LinearLayout.LayoutParams) {
            params = (LinearLayout.LayoutParams) existing;
        } else if (existing != null) {
            params = new LinearLayout.LayoutParams(existing);
        } else {
            params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        }
        super.addView(view, index, params);
        if (fullWidth) fullWidthViews.add(view);
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        fullWidthViews.remove(child);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (mode == MeasureSpec.UNSPECIFIED) {
            // With no width constraint, first obtain the container's natural width.
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).getLayoutParams().width = LayoutParams.MATCH_PARENT;
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            width = getMeasuredWidth();
        }

        int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int keyboardWidth = Math.round(contentWidth * keyboardWidthPercent / 100f);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            params.width = fullWidthViews.contains(child) || keyboardWidthPercent == 100
                    ? LayoutParams.MATCH_PARENT : keyboardWidth;
        }
        // Even under AT_MOST the IME container spans the available width so that
        // narrower keyboard rows are centered within it.
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
    }
}
