package com.gazlaws.codeboard.layout.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.gazlaws.codeboard.R;
import com.gazlaws.codeboard.theme.ThemeInfo;

/** Inline controls for changing keyboard size without opening a separate window. */
@SuppressLint("ViewConstructor")
public class KeyboardResizeView extends LinearLayout {
    public interface Listener {
        void onSizeChanged(int heightPercent, int widthPercent);
        void onReset();
        void onDone();
    }

    private final int minHeight;
    private final int maxHeight;
    private final int minWidth;
    private final int maxWidth;
    private final Listener listener;
    private final TextView heightLabel;
    private final TextView widthLabel;
    private final SeekBar heightSlider;
    private final SeekBar widthSlider;
    private int heightPercent;
    private int widthPercent;
    private boolean updating;

    public KeyboardResizeView(Context context, ThemeInfo theme,
                              int heightPercent, int widthPercent,
                              int minHeight, int maxHeight, int minWidth, int maxWidth,
                              Listener listener) {
        super(context);
        checkBounds(minHeight, maxHeight);
        checkBounds(minWidth, maxWidth);
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.minWidth = minWidth;
        this.maxWidth = maxWidth;
        this.listener = listener;
        this.heightPercent = clamp(heightPercent, minHeight, maxHeight);
        this.widthPercent = clamp(widthPercent, minWidth, maxWidth);

        setOrientation(VERTICAL);
        setBackgroundColor(theme.backgroundColor);
        setPadding(dp(8), 0, dp(8), 0);

        heightLabel = createLabel(R.id.keyboard_resize_height_label, theme.foregroundColor);
        heightSlider = createSlider(R.id.keyboard_resize_height_slider,
                minHeight, maxHeight, theme.foregroundColor);
        addView(createSliderRow(heightLabel, heightSlider));

        widthLabel = createLabel(R.id.keyboard_resize_width_label, theme.foregroundColor);
        widthSlider = createSlider(R.id.keyboard_resize_width_slider,
                minWidth, maxWidth, theme.foregroundColor);
        addView(createSliderRow(widthLabel, widthSlider));

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setMinimumHeight(dp(48));
        Button reset = createButton(R.id.keyboard_resize_reset_button,
                R.string.keyboard_resize_reset, theme);
        Button done = createButton(R.id.keyboard_resize_done_button,
                R.string.keyboard_resize_done, theme);
        LayoutParams resetParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        resetParams.setMarginEnd(dp(4));
        actions.addView(reset, resetParams);
        LayoutParams doneParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        doneParams.setMarginStart(dp(4));
        actions.addView(done, doneParams);
        addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        reset.setOnClickListener(v -> { if (this.listener != null) this.listener.onReset(); });
        done.setOnClickListener(v -> { if (this.listener != null) this.listener.onDone(); });
        setSize(this.heightPercent, this.widthPercent);
        heightSlider.setOnSeekBarChangeListener(createChangeListener(true));
        widthSlider.setOnSeekBarChangeListener(createChangeListener(false));
    }

    /** Updates both controls without reporting a user change. */
    public void setSize(int heightPercent, int widthPercent) {
        updating = true;
        this.heightPercent = clamp(heightPercent, minHeight, maxHeight);
        this.widthPercent = clamp(widthPercent, minWidth, maxWidth);
        heightSlider.setProgress(this.heightPercent);
        widthSlider.setProgress(this.widthPercent);
        updateLabels();
        updating = false;
    }

    public int getHeightPercent() { return heightPercent; }
    public int getWidthPercent() { return widthPercent; }

    private SeekBar.OnSeekBarChangeListener createChangeListener(boolean height) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                int minimum = height ? minHeight : minWidth;
                // Android 6 and 7 do not support SeekBar.setMin().
                if (value < minimum) {
                    seekBar.setProgress(minimum);
                    return;
                }
                if (updating) return;
                if (height) {
                    if (heightPercent == value) return;
                    heightPercent = value;
                } else {
                    if (widthPercent == value) return;
                    widthPercent = value;
                }
                updateLabels();
                if (listener != null) listener.onSizeChanged(heightPercent, widthPercent);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
    }

    private TextView createLabel(int id, int color) {
        TextView label = new TextView(getContext());
        label.setId(id);
        label.setTextColor(color);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        // The adjacent slider exposes both its label and value to accessibility services.
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return label;
    }

    private SeekBar createSlider(int id, int minimum, int maximum, int color) {
        SeekBar slider = new SeekBar(getContext());
        slider.setId(id);
        slider.setMax(maximum);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) slider.setMin(minimum);
        slider.setProgressTintList(ColorStateList.valueOf(color));
        slider.setThumbTintList(ColorStateList.valueOf(color));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(color, 90)));
        slider.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                        minimum, maximum, slider.getProgress()));
            }
        });
        return slider;
    }

    private LinearLayout createSliderRow(TextView label, SeekBar slider) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.addView(label, new LayoutParams(dp(96), LayoutParams.WRAP_CONTENT));
        row.addView(slider, new LayoutParams(0, dp(48), 1));
        row.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return row;
    }

    private Button createButton(int id, int text, ThemeInfo theme) {
        Button button = new Button(getContext());
        button.setId(id);
        button.setText(text);
        button.setTextColor(theme.foregroundColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setAllCaps(false);
        button.setMinimumHeight(dp(48));
        button.setMinHeight(dp(48));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ColorUtils.blendARGB(theme.backgroundColor, theme.foregroundColor, .12f));
        background.setCornerRadius(dp(4));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(theme.foregroundColor, 64)), background, null));
        return button;
    }

    private void updateLabels() {
        heightLabel.setText(getResources().getString(R.string.keyboard_resize_height, heightPercent));
        widthLabel.setText(getResources().getString(R.string.keyboard_resize_width, widthPercent));
        heightSlider.setContentDescription(getResources().getString(
                R.string.keyboard_resize_height_description, heightPercent));
        widthSlider.setContentDescription(getResources().getString(
                R.string.keyboard_resize_width_description, widthPercent));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void checkBounds(int minimum, int maximum) {
        if (minimum < 1 || maximum > 100 || minimum > maximum) {
            throw new IllegalArgumentException("Size bounds must be between 1 and 100 percent");
        }
    }
}
