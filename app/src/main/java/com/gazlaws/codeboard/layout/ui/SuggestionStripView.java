package com.gazlaws.codeboard.layout.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gazlaws.codeboard.theme.ThemeInfo;

import java.util.List;

/** A dedicated, horizontally scrollable row above the keyboard. */
// Created programmatically with the keyboard theme; this view is not inflated from XML.
@SuppressLint("ViewConstructor")
public class SuggestionStripView extends HorizontalScrollView {
    public interface OnCandidateClickListener {
        void onCandidateClick(String candidate);
    }

    private final int heightPx;
    private final int textColor;
    private final LinearLayout content;
    private CharSequence hintText = "Swipe across letters to type";
    private boolean showingHint = true;

    public SuggestionStripView(Context context, ThemeInfo theme) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        heightPx = Math.round(44 * density);
        textColor = theme.foregroundColor;
        setBackgroundColor(theme.backgroundColor);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        setMinimumHeight(heightPx);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        addView(content, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        showHint();
    }

    public void setHintText(CharSequence hint) {
        hintText = hint;
        if (showingHint) showHint();
    }

    /** Shows up to five candidates, or a hint if the list has no displayable entries. */
    public void setCandidates(List<String> candidates, OnCandidateClickListener listener) {
        content.removeAllViews();
        int count = 0;
        if (candidates != null) {
            for (String candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                if (count == 5) break;
                TextView text = createTextView();
                text.setText(candidate);
                text.setClickable(listener != null);
                text.setFocusable(listener != null);
                if (listener != null) {
                    text.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            listener.onCandidateClick(candidate);
                        }
                    });
                }
                int padding = Math.round(16 * getResources().getDisplayMetrics().density);
                int minWidth = Math.round(96 * getResources().getDisplayMetrics().density);
                int neededWidth = (int) Math.ceil(text.getPaint().measureText(candidate)) + padding * 2;
                text.setPadding(padding, 0, padding, 0);
                content.addView(text, new LinearLayout.LayoutParams(
                        Math.max(minWidth, neededWidth), LayoutParams.MATCH_PARENT, 1));
                count++;
            }
        }
        showingHint = count == 0;
        if (showingHint) showHint();
        scrollTo(0, 0);
    }

    private TextView createTextView() {
        TextView text = new TextView(getContext());
        text.setTextColor(textColor);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        return text;
    }

    private void showHint() {
        content.removeAllViews();
        TextView hint = createTextView();
        hint.setText(hintText);
        hint.setAlpha(.7f);
        content.addView(hint, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = resolveSize(heightPx, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
