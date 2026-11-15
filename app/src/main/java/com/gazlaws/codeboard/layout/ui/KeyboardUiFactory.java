package com.gazlaws.codeboard.layout.ui;

import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.view.ViewGroup;

import com.gazlaws.codeboard.layout.Key;
import com.gazlaws.codeboard.theme.ThemeDefinitions;
import com.gazlaws.codeboard.theme.ThemeInfo;
import com.gazlaws.codeboard.theme.UiTheme;

import java.util.Collection;

public class KeyboardUiFactory {

    private final KeyboardView.OnKeyboardActionListener inputService;
    public ThemeInfo theme = ThemeDefinitions.Default();

    public KeyboardUiFactory(KeyboardView.OnKeyboardActionListener inputService) {
        this.inputService = inputService;
    }

    public KeyboardLayoutView createKeyboardView(Context context, Collection<Key> keys) {
        UiTheme uiTheme = UiTheme.buildFromInfo(this.theme);
        KeyboardLayoutView layout = new KeyboardLayoutView(context, uiTheme);
        for (Key key : keys) {
            KeyboardButtonView view = new KeyboardButtonView(context, key, inputService, uiTheme);
            layout.addView(view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return layout;
    }
}
