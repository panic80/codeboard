package com.gazlaws.codeboard.layout.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.graphics.ColorUtils;

import com.gazlaws.codeboard.R;
import com.gazlaws.codeboard.theme.ThemeInfo;

/** The keyboard's compact menu of common actions. */
@SuppressLint("ViewConstructor")
public class KeyboardToolsView extends LinearLayout {
    public enum Action { EMOJI, CLIPBOARD, VOICE, RESIZE, LANGUAGE, SETTINGS, REVIEW, PRACTICE }

    public interface Listener {
        void onAction(Action action);
    }

    public KeyboardToolsView(Context context, ThemeInfo theme, Listener listener) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(4), dp(4), dp(4), dp(4));
        setBackgroundColor(theme.backgroundColor);

        Action[] actions = Action.values();
        int[] labels = { R.string.keyboard_tools_emoji, R.string.keyboard_tools_clipboard,
                R.string.keyboard_tools_voice, R.string.keyboard_tools_resize,
                R.string.keyboard_tools_language, R.string.keyboard_tools_settings, R.string.keyboard_tools_review, R.string.keyboard_tools_practice };
        int[] ids = { R.id.keyboard_tools_emoji, R.id.keyboard_tools_clipboard,
                R.id.keyboard_tools_voice, R.id.keyboard_tools_resize,
                R.id.keyboard_tools_language, R.id.keyboard_tools_settings, R.id.keyboard_tools_review, R.id.keyboard_tools_practice };
        for (int rowIndex = 0; rowIndex < (actions.length + 2) / 3; rowIndex++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                int index = rowIndex * 3 + col;
                if (index >= actions.length) break;
                Button button = createButton(theme, ids[index], labels[index]);
                Action action = actions[index];
                button.setOnClickListener(v -> {
                    if (listener != null) listener.onAction(action);
                });
                LayoutParams params = new LayoutParams(0, dp(48), 1);
                params.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(button, params);
            }
            addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }

    private Button createButton(ThemeInfo theme, int id, int label) {
        Button button = new Button(getContext());
        button.setId(id);
        button.setText(label);
        button.setTextColor(theme.foregroundColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(2), 0, dp(2), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(ColorUtils.blendARGB(theme.backgroundColor, theme.foregroundColor, .12f));
        background.setCornerRadius(dp(4));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(theme.foregroundColor, 64)), background, null));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
