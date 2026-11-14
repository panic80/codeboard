package com.gazlaws.codeboard.input;

import android.text.InputType;
import android.view.inputmethod.InputConnection;

/** Small editor-aware typing helpers. The caller chooses which layouts and fields enable them. */
public final class TypingConveniences {
    private static final long DOUBLE_SPACE_TIMEOUT_MS = 700;
    private static final int CONTEXT_LENGTH = 256;
    private long lastSpaceTime = -1;

    /**
     * Checks the editor's capitalization flags, defaulting to sentence capitalization.
     * Call only when automatic capitalization is enabled for the current language and layout.
     * Explicit shift and shift-lock state remain the caller's responsibility.
     */
    public static boolean shouldCapitalize(InputConnection connection, int inputType) {
        if (connection == null || !isOrdinaryText(inputType)) return false;
        int flags = inputType & InputType.TYPE_MASK_FLAGS;
        if ((flags & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) return true;

        CharSequence before = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0);
        if (before == null) return false;
        if (before.length() == 0) return true;
        if ((flags & InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0) {
            return Character.isWhitespace(before.charAt(before.length() - 1));
        }

        // A line break starts a new sentence. Otherwise require whitespace after . ! or ?.
        int end = before.length() - 1;
        if (!Character.isWhitespace(before.charAt(end))) return false;
        boolean lineBreak = false;
        while (end >= 0 && Character.isWhitespace(before.charAt(end))) {
            char c = before.charAt(end--);
            lineBreak |= c == '\n' || c == '\r';
        }
        if (lineBreak) return true;
        if (end < 0) {
            // A full buffer of spaces might be truncated, so do not assume it is the beginning.
            return before.length() < CONTEXT_LENGTH;
        }
        char punctuation = before.charAt(end);
        return punctuation == '.' || punctuation == '!' || punctuation == '?';
    }

    private static boolean isOrdinaryText(int inputType) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
                || (inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_URI
                && variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    /** Records the time of an ordinary space that the editor successfully inserted. */
    public void recordSpace(long uptimeMs) {
        lastSpaceTime = uptimeMs;
    }

    /** Discards the first-space candidate after an unrelated edit, selection or editor change. */
    public void clear() {
        lastSpaceTime = -1;
    }

    /**
     * Before inserting a second space, replace the first with a period and a space if safe.
     * The caller should not insert the second space when this returns true.
     */
    public boolean tryDoubleSpace(InputConnection connection, long uptimeMs) {
        long elapsed = uptimeMs - lastSpaceTime;
        if (connection == null || lastSpaceTime < 0 || elapsed < 0
                || elapsed > DOUBLE_SPACE_TIMEOUT_MS) return false;
        CharSequence selected = connection.getSelectedText(0);
        if (selected != null && selected.length() != 0) return false;
        CharSequence before = connection.getTextBeforeCursor(3, 0);
        CharSequence after = connection.getTextAfterCursor(1, 0);
        if (before == null || after == null || before.length() < 2
                || before.charAt(before.length() - 1) != ' '
                || !Character.isLetterOrDigit(Character.codePointBefore(before, before.length() - 1))
                || (after.length() > 0 && Character.isLetterOrDigit(Character.codePointAt(after, 0)))) {
            return false;
        }

        connection.beginBatchEdit();
        try {
            if (!connection.deleteSurroundingText(1, 0)) return false;
            if (!connection.commitText(". ", 1)) {
                // Preserve the first space if an unusual editor rejects the replacement.
                connection.commitText(" ", 1);
                return false;
            }
            clear();
            return true;
        } finally {
            connection.endBatchEdit();
        }
    }
}
