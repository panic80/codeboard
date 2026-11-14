package com.gazlaws.codeboard.layout.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.gazlaws.codeboard.R;
import com.gazlaws.codeboard.theme.ThemeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A keyboard-sized picker using Unicode emoji and a locally stored recent list. */
@SuppressLint("ViewConstructor")
public class EmojiPickerView extends LinearLayout {
    public interface Listener {
        void onEmoji(String emoji);
        void onBackspace();
        void onDone();
    }

    private enum Category { RECENT, FACES, HEARTS, FOOD, FLAGS }

    private static final String PREFERENCES = "emoji_picker";
    private static final String RECENT_KEY = "recent";
    private static final int MAX_RECENT = 24;

    // These are individual, widely supported emoji rather than newer composed sequences.
    private static final String[] FACES = {
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "😊", "😇", "🙂",
            "😉", "😍", "😘", "😋", "😎", "😢", "😭", "😡", "😴", "😮"
    };
    private static final String[] HEARTS = {
            "❤️", "💕", "💖", "💗", "💓", "💞", "💘", "💝", "💔", "💜",
            "💙", "💚", "💛", "⭐", "✨", "👍", "👎", "👏", "🙌", "🙏"
    };
    private static final String[] FOOD = {
            "🍎", "🍊", "🍌", "🍉", "🍇", "🍓", "🍒", "🍍", "🍑", "🍋",
            "🍞", "🍔", "🍕", "🍟", "🍣", "🍜", "🍚", "🍰", "☕", "🍺"
    };
    private static final String[] FLAGS = {
            "🇨🇦", "🇰🇷", "🇺🇸", "🇬🇧", "🇯🇵", "🇫🇷", "🇩🇪", "🇮🇹", "🇨🇳", "🇦🇺"
    };

    private final ThemeInfo theme;
    private final Listener listener;
    private final SharedPreferences preferences;
    private final Set<String> knownEmoji = new HashSet<>();
    private final List<String> recent = new ArrayList<>();
    private final Button[] categoryButtons = new Button[Category.values().length];
    private final GridView grid;
    private final EmojiAdapter adapter = new EmojiAdapter();
    private Category category = Category.FACES;

    public EmojiPickerView(Context context, ThemeInfo theme, Listener listener) {
        super(context);
        this.theme = theme;
        this.listener = listener;
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        knownEmoji.addAll(Arrays.asList(FACES));
        knownEmoji.addAll(Arrays.asList(HEARTS));
        knownEmoji.addAll(Arrays.asList(FOOD));
        knownEmoji.addAll(Arrays.asList(FLAGS));
        loadRecent();

        setOrientation(VERTICAL);
        setBackgroundColor(theme.backgroundColor);

        HorizontalScrollView categoryScroll = new HorizontalScrollView(context);
        categoryScroll.setId(R.id.emoji_categories_scroll);
        categoryScroll.setFillViewport(true);
        categoryScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout categories = new LinearLayout(context);
        categories.setOrientation(HORIZONTAL);
        int[] labels = { R.string.emoji_category_recent, R.string.emoji_category_faces,
                R.string.emoji_category_hearts, R.string.emoji_category_food,
                R.string.emoji_category_flags };
        int[] ids = { R.id.emoji_category_recent, R.id.emoji_category_faces,
                R.id.emoji_category_hearts, R.id.emoji_category_food,
                R.id.emoji_category_flags };
        Category[] values = Category.values();
        for (int i = 0; i < values.length; i++) {
            final Category selected = values[i];
            Button button = makeButton(labels[i], ids[i], 12);
            button.setOnClickListener(v -> showCategory(selected));
            categoryButtons[i] = button;
            categories.addView(button, new LayoutParams(dp(64), dp(48), 1));
        }
        categoryScroll.addView(categories, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        addView(categoryScroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));

        FrameLayout gridContainer = new FrameLayout(context);
        grid = new GridView(context);
        grid.setId(R.id.emoji_grid);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(48));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setGravity(Gravity.CENTER);
        grid.setVerticalSpacing(0);
        grid.setHorizontalSpacing(0);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            String emoji = (String) adapter.getItem(position);
            remember(emoji);
            if (listener != null) listener.onEmoji(emoji);
        });
        gridContainer.addView(grid, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        TextView empty = new TextView(context);
        empty.setId(R.id.emoji_recent_empty);
        empty.setText(R.string.emoji_recent_empty);
        empty.setTextColor(theme.foregroundColor);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER);
        gridContainer.addView(empty, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        grid.setEmptyView(empty);
        addView(gridContainer, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(HORIZONTAL);
        Button backspace = makeButton(R.string.emoji_backspace, R.id.emoji_backspace, 14);
        backspace.setContentDescription(getResources().getString(R.string.emoji_backspace_description));
        backspace.setOnClickListener(v -> { if (listener != null) listener.onBackspace(); });
        Button done = makeButton(R.string.emoji_done, R.id.emoji_done, 14);
        done.setOnClickListener(v -> { if (listener != null) listener.onDone(); });
        actions.addView(backspace, new LayoutParams(0, dp(48), 1));
        actions.addView(done, new LayoutParams(0, dp(48), 1));
        addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        showCategory(Category.FACES);
    }

    private void showCategory(Category selected) {
        category = selected;
        for (int i = 0; i < categoryButtons.length; i++) {
            Button button = categoryButtons[i];
            boolean active = Category.values()[i] == selected;
            button.setSelected(active);
            button.setBackground(makeBackground(active ? .24f : .08f));
        }
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
    }

    private void loadRecent() {
        String saved = preferences.getString(RECENT_KEY, "");
        if (saved == null || saved.isEmpty()) return;
        for (String emoji : saved.split("\n")) {
            if (knownEmoji.contains(emoji) && !recent.contains(emoji)) recent.add(emoji);
            if (recent.size() == MAX_RECENT) break;
        }
    }

    private void remember(String emoji) {
        recent.remove(emoji);
        recent.add(0, emoji);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        StringBuilder saved = new StringBuilder();
        for (String item : recent) {
            if (saved.length() > 0) saved.append('\n');
            saved.append(item);
        }
        preferences.edit().putString(RECENT_KEY, saved.toString()).apply();
        if (category == Category.RECENT) adapter.notifyDataSetChanged();
    }

    private List<String> items() {
        switch (category) {
            case RECENT: return recent;
            case HEARTS: return Arrays.asList(HEARTS);
            case FOOD: return Arrays.asList(FOOD);
            case FLAGS: return Arrays.asList(FLAGS);
            case FACES:
            default: return Arrays.asList(FACES);
        }
    }

    private Button makeButton(int text, int id, int textSize) {
        Button button = new Button(getContext());
        button.setId(id);
        button.setText(text);
        button.setTextColor(theme.foregroundColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(1), 0, dp(1), 0);
        button.setBackground(makeBackground(.12f));
        return button;
    }

    private RippleDrawable makeBackground(float blend) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ColorUtils.blendARGB(theme.backgroundColor, theme.foregroundColor, blend));
        shape.setCornerRadius(dp(3));
        return new RippleDrawable(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(theme.foregroundColor, 64)), shape, null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class EmojiAdapter extends BaseAdapter {
        @Override public int getCount() { return items().size(); }
        @Override public Object getItem(int position) { return items().get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TextView cell;
            if (recycled instanceof TextView) {
                cell = (TextView) recycled;
            } else {
                cell = new TextView(getContext());
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                cell.setTextColor(theme.foregroundColor);
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
            }
            String emoji = (String) getItem(position);
            cell.setText(emoji);
            cell.setContentDescription(emoji);
            return cell;
        }
    }
}
