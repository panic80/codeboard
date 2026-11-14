package com.gazlaws.codeboard.layout.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.gazlaws.codeboard.R;
import com.gazlaws.codeboard.theme.ThemeInfo;

import java.util.List;

/** A horizontally scrollable suggestion row with a fixed personal-dictionary action. */
// Created programmatically with the keyboard theme; this view is not inflated from XML.
@SuppressLint("ViewConstructor")
public class SuggestionStripView extends LinearLayout {
    public interface OnCandidateClickListener {
        void onCandidateClick(String candidate);
    }

    private final int heightPx;
    private final int textColor;
    private final HorizontalScrollView candidateScroll;
    private final LinearLayout content;
    private final LinearLayout learnAction;
    private final TextView learnLabel;
    private final TextView learnWord;
    private CharSequence hintText = "Swipe across letters to type";
    private boolean showingHint = true;

    public SuggestionStripView(Context context, ThemeInfo theme) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        heightPx = Math.round(48 * density);
        textColor = theme.foregroundColor;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(theme.backgroundColor);
        setMinimumHeight(heightPx);

        candidateScroll = new HorizontalScrollView(context);
        candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateScroll.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        candidateScroll.addView(content, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        addView(candidateScroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));

        learnAction = new LinearLayout(context);
        learnAction.setId(R.id.inline_learning_action);
        learnAction.setOrientation(VERTICAL);
        learnAction.setGravity(Gravity.CENTER);
        int horizontalPadding = Math.round(4 * density);
        learnAction.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        learnLabel = createTextView();
        learnLabel.setId(R.id.inline_learning_label);
        learnLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        learnLabel.setTypeface(learnLabel.getTypeface(), Typeface.BOLD);
        learnLabel.setEllipsize(TextUtils.TruncateAt.END);
        learnLabel.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        learnAction.addView(learnLabel, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        learnWord = createTextView();
        learnWord.setId(R.id.inline_learning_word);
        learnWord.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        learnWord.setEllipsize(TextUtils.TruncateAt.END);
        learnWord.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        learnAction.addView(learnWord, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        GradientDrawable actionBackground = new GradientDrawable();
        actionBackground.setColor(ColorUtils.blendARGB(theme.backgroundColor, textColor, .12f));
        actionBackground.setCornerRadius(Math.round(4 * density));
        learnAction.setBackground(new RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 64)),
                actionBackground, null));
        learnAction.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (host.isClickable()) info.setClassName(Button.class.getName());
            }
        });
        addView(learnAction, new LayoutParams(Math.round(88 * density), LayoutParams.MATCH_PARENT));
        clearLearningAction();
        showHint();
    }

    public void setHintText(CharSequence hint) {
        hintText = hint;
        if (showingHint) showHint();
    }

    /** Shows up to five candidates, or a hint if the list has no displayable entries. */
    public void setCandidates(List<String> candidates, OnCandidateClickListener listener) {
        setCandidates(candidates, listener, false);
    }

    /** Shows typed-word candidates with the original spelling marked as the first item. */
    public void setTypedCandidates(List<String> candidates, OnCandidateClickListener listener) {
        setCandidates(candidates, listener, true);
    }

    private void setCandidates(List<String> candidates, OnCandidateClickListener listener,
                               boolean markTypedWord) {
        content.removeAllViews();
        int count = 0;
        if (candidates != null) {
            for (String candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                if (count == 5) break;
                TextView text = createTextView();
                text.setText(candidate);
                text.setEllipsize(TextUtils.TruncateAt.END);
                if (markTypedWord && count == 0) {
                    text.setTypeface(text.getTypeface(), Typeface.ITALIC);
                    text.setContentDescription(getResources().getString(
                            R.string.inline_learning_typed_word_description, candidate));
                }
                text.setClickable(listener != null);
                text.setFocusable(listener != null);
                if (listener != null) {
                    text.setOnClickListener(v -> listener.onCandidateClick(candidate));
                }
                float density = getResources().getDisplayMetrics().density;
                int padding = Math.round(16 * density);
                int minWidth = Math.round(96 * density);
                int maxWidth = Math.round(180 * density);
                int neededWidth = (int) Math.ceil(text.getPaint().measureText(candidate)) + padding * 2;
                text.setPadding(padding, 0, padding, 0);
                content.addView(text, new LayoutParams(
                        Math.min(maxWidth, Math.max(minWidth, neededWidth)),
                        LayoutParams.MATCH_PARENT, 1));
                count++;
            }
        }
        showingHint = count == 0;
        if (showingHint) showHint();
        candidateScroll.scrollTo(0, 0);
    }

    /** Offers to save the currently tracked word without changing the editor. */
    public void setLearnWord(String word, Runnable onAdd) {
        if (word == null || word.isEmpty() || onAdd == null) {
            clearLearningAction();
            return;
        }
        learnLabel.setText(R.string.inline_learning_add_word);
        learnWord.setText(word);
        learnAction.setContentDescription(getResources().getString(
                R.string.inline_learning_add_description, word));
        learnAction.setOnClickListener(v -> onAdd.run());
        learnAction.setClickable(true);
        learnAction.setFocusable(true);
        learnAction.setVisibility(VISIBLE);
    }

    /** Replaces the action with a non-clickable confirmation for the current word. */
    public void showLearningFeedback(String word) {
        showLearningStatus(word, R.string.inline_learning_saved,
                R.string.inline_learning_saved_description);
    }

    /** Replaces the action with a non-clickable failure message for the current word. */
    public void showLearningError(String word) {
        showLearningStatus(word, R.string.inline_learning_error,
                R.string.inline_learning_error_description);
    }

    private void showLearningStatus(String word, int textRes, int descriptionRes) {
        if (word == null || word.isEmpty()) {
            clearLearningAction();
            return;
        }
        learnAction.setOnClickListener(null);
        learnAction.setClickable(false);
        learnAction.setFocusable(false);
        learnLabel.setText(textRes);
        learnWord.setText(word);
        learnAction.setContentDescription(getResources().getString(descriptionRes, word));
        learnAction.setVisibility(VISIBLE);
        learnAction.announceForAccessibility(learnAction.getContentDescription());
    }

    /** Removes any action or feedback as soon as it no longer describes the current word. */
    public void clearLearningAction() {
        learnAction.setOnClickListener(null);
        learnAction.setClickable(false);
        learnAction.setFocusable(false);
        learnAction.setContentDescription(null);
        learnAction.setVisibility(GONE);
    }

    /** Number of actual suggestions, excluding the non-interactive empty-state hint. */
    public int getCandidateCount() {
        return showingHint ? 0 : content.getChildCount();
    }

    /** Returns the visible row item at an index; the first item can be the empty-state hint. */
    public TextView getCandidateView(int index) {
        return (TextView) content.getChildAt(index);
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
        content.addView(hint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = resolveSize(heightPx, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
