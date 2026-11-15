package com.gazlaws.codeboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.KeyboardView;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import android.widget.Toast;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.media.MediaPlayer; // for keypress sound
import android.widget.LinearLayout;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.ColorUtils;

import com.gazlaws.codeboard.input.HangulComposer;
import com.gazlaws.codeboard.input.TypingConveniences;
import com.gazlaws.codeboard.input.KoreanKeyMap;
import com.gazlaws.codeboard.gesture.SwipeDecoder;
import com.gazlaws.codeboard.gesture.SwipeTypingController;
import com.gazlaws.codeboard.gesture.AutoCorrectController;
import com.gazlaws.codeboard.gesture.TouchModel;
import com.gazlaws.codeboard.layout.Box;
import com.gazlaws.codeboard.layout.Definitions;
import com.gazlaws.codeboard.layout.Key;
import com.gazlaws.codeboard.layout.builder.KeyboardLayoutBuilder;
import com.gazlaws.codeboard.layout.builder.KeyboardLayoutException;
import com.gazlaws.codeboard.layout.ui.KeyboardLayoutView;
import com.gazlaws.codeboard.layout.ui.KeyboardUiFactory;
import com.gazlaws.codeboard.layout.ui.SwipeGesture;
import com.gazlaws.codeboard.layout.ui.SuggestionStripView;
import com.gazlaws.codeboard.layout.ui.ResizableInputView;
import com.gazlaws.codeboard.layout.ui.KeyboardResizeView;
import com.gazlaws.codeboard.layout.ui.KeyboardToolsView;
import com.gazlaws.codeboard.layout.ui.EmojiPickerView;
import com.gazlaws.codeboard.theme.ThemeDefinitions;
import com.gazlaws.codeboard.theme.ThemeInfo;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;
import static android.view.inputmethod.InputMethodManager.HIDE_IMPLICIT_ONLY;
import static android.view.inputmethod.InputMethodManager.HIDE_NOT_ALWAYS;

/*Created by Ruby(aka gazlaws) on 13/02/2016.
 */


public class CodeBoardIME extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {
    private static final String NOTIFICATION_CHANNEL_ID = "Codeboard";
    EditorInfo sEditorInfo;
    private boolean vibratorOn;
    private int vibrateLength;
    private boolean soundOn;
    private boolean shiftLock = false;
    private boolean ctrlLock = false;
    private boolean shift = false;
    private boolean ctrl = false;
    private int mKeyboardState = R.integer.keyboard_normal;
    private Timer timerLongPress = null;
    private KeyboardUiFactory mKeyboardUiFactory = null;
    private KeyboardLayoutView mCurrentKeyboardLayoutView = null;
    private boolean longPressedSpaceButton = false;
    private final HangulComposer hangulComposer = new HangulComposer();
    private boolean koreanLayoutSelected = false;
    private boolean koreanMode = true;
    private boolean swipeTypingEnabled = true;
    private boolean autoCorrectEnabled = true;
    private boolean useTopSuggestion = true;
    private boolean spellingSuggestionsEnabled = true;
    private int editorSelectionStart = -1;
    private int editorSelectionEnd = -1;
    private SwipeTypingController swipeTypingController;
    private AutoCorrectController autoCorrectController;
    private SuggestionStripView swipeSuggestions;
    private ResizableInputView inputContainer;
    private View auxiliaryPanel;
    private VoiceInputBridge voiceInputBridge;
    private boolean autoCapitalizationEnabled;
    private boolean doubleSpacePeriodEnabled;
    private boolean manualShift;
    private boolean capitalizationSuppressed;
    private boolean cursorDragging;
    private long longPressGeneration;
    private final TypingConveniences typingConveniences = new TypingConveniences();
    private int lastSpaceCursor = -1;

    @Override
    public void onKey(int primaryCode, int[] KeyCodes) {
        if (voiceInputBridge != null) voiceInputBridge.onManualInput();
        final int originalCode = primaryCode;
        if (deferPendingInput(() -> onKey(originalCode, KeyCodes))) return;
        if (primaryCode != 32) {
            typingConveniences.clear();
            lastSpaceCursor = -1;
        }
        //NOTE: Long press goes second, this is onDown
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            hangulComposer.reset();
            return;
        }
        boolean typingAssistAvailable = isTypingAssistAvailable();
        char koreanKey = KoreanKeyMap.qwertyKeyFor(primaryCode);
        boolean koreanLetter = koreanKey != 0 && koreanLayoutSelected && koreanMode
                && mKeyboardState == R.integer.keyboard_normal
                && !ctrl;
        if (koreanKey != 0) primaryCode = koreanKey;
        if (autoCorrectController != null && primaryCode != 16 && primaryCode != 32
                && primaryCode != -5 && primaryCode != -1 && (!typingAssistAvailable || primaryCode < 32 || primaryCode > 126)) {
            autoCorrectController.cancel();
            if (primaryCode != 17 && primaryCode != -1
                    && primaryCode != Definitions.CODE_LANGUAGE_SWITCH) {
                autoCorrectController.invalidateEditorPosition();
            }
        }
        if (swipeTypingController != null && primaryCode != 16 && primaryCode != -5) {
            boolean letter = primaryCode >= 'a' && primaryCode <= 'z'
                    || primaryCode >= 'A' && primaryCode <= 'Z';
            swipeTypingController.finish(ic, letter && !ctrl
                    && mKeyboardState == R.integer.keyboard_normal);
        }
        if (!koreanLetter && primaryCode != 16 && primaryCode != -5) {
            finishKoreanComposition(ic);
        }
        char code = (char) primaryCode;
        Log.d(getClass().getSimpleName(), "onKey: " + primaryCode);

        switch (primaryCode) {
            case Definitions.CODE_CURSOR_DRAG_START:
                cursorDragging = true;
                clearLongPressTimer();
                break;
            case Definitions.CODE_CURSOR_DRAG_END:
                cursorDragging = false;
                consumeShift(ic);
                break;
            case Definitions.CODE_TOOLS:
                showTools();
                break;
            case Definitions.CODE_RESIZE:
                showResize();
                break;
            //First handle cases that  don't use shift/ctrl meta modifiers
            case Definitions.CODE_LANGUAGE_SWITCH:
                if (koreanLayoutSelected) {
                    koreanMode = !koreanMode;
                    shift = shiftLock = manualShift = false;
                    ctrl = ctrlLock = false;
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT));
                    setInputView(onCreateInputView());
                    controlKeyUpdateView();
                    shiftKeyUpdateView();
                }
                break;
            case 53737:
                ic.performContextMenuAction(android.R.id.selectAll);
                break;
            case 53738:
                ic.performContextMenuAction(android.R.id.cut);
                break;
            case 53739:
                ic.performContextMenuAction(android.R.id.copy);
                break;
            case 53740:
                ic.performContextMenuAction(android.R.id.paste);
                break;
            case 53741:
                ic.performContextMenuAction(android.R.id.undo);
                break;
            case 53742:
                ic.performContextMenuAction(android.R.id.redo);
                break;
            case -1:
                // Preserve an email token while opening the punctuation page.
                if (autoCorrectController != null) autoCorrectController.onKeyboardPageChanged();
                if (mKeyboardState == R.integer.keyboard_normal && !ctrl) {
                    mKeyboardState = R.integer.keyboard_sym;
                } else if (ctrl) {
                    mKeyboardState = R.integer.keyboard_clipboard;
                } else {
                    mKeyboardState = R.integer.keyboard_normal;
                }
                manualShift = false;
                // regenerate view
                //Simple remove shift
                if (shift) {
                    shift = false;
                    shiftLock = false;
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT));
                }
                if (ctrl) {
                    ctrl = false;
                    ctrlLock = false;
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT));
                }
                setInputView(onCreateInputView());
                controlKeyUpdateView();
                shiftKeyUpdateView();
                break;

            case 17: //KEYCODE_CTRL_LEFT:
                // emulates a press down of the ctrl key
                if (!ctrlLock && !ctrl) {
                    //Simple ctrl to true
                    ctrl = true;
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT));
                } else if (!ctrlLock && ctrl) {
                    //Simple remove ctrl
                    ctrl = false;
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT));
                }
                //else if (ctrl && ctrlLock) {
                //Stay ctrled if previously ctrled
                //}
                controlKeyUpdateView();
                break;

            case 16: //KEYCODE_SHIFT_LEFT
                capitalizationSuppressed = shift;
                manualShift = !shift;
                // emulates press of shift key - this helps for selection with arrow keys
                if (!shiftLock && !shift) {
                    //Simple shift to true
                    shift = true;
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT));
                } else if (!shiftLock && shift) {
                    //Simple remove shift
                    shift = false;
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT));
                }
                //else if (shift && shiftLock) {
                //Stay shifted if previously shifted
                //}
                shiftKeyUpdateView();
                break;

            default: {
                int meta = 0;
                if (shift) {
                    meta = KeyEvent.META_SHIFT_ON;
                    code = Character.toUpperCase(code);
                    if (!shiftLock && primaryCode != 32 && !cursorDragging) {
                        manualShift = false;
                        shift = false;
                        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT));
                        shiftKeyUpdateView();
                    }
                }
                if (ctrl) {
                    meta = meta | KeyEvent.META_CTRL_ON;
                    if (!ctrlLock) {
                        ctrl = false;
                        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT));
                        controlKeyUpdateView();
                    }
                }
                //Now shift/ctrl metadata is set
                if (primaryCode == -5 && (meta & KeyEvent.META_CTRL_ON) != 0
                        && autoCorrectController != null) {
                    autoCorrectController.invalidateEditorPosition();
                }
                if (primaryCode == -5 && (meta & KeyEvent.META_CTRL_ON) == 0
                        && autoCorrectController != null && autoCorrectController.undoCorrection()) {
                    break;
                }
                if (primaryCode == -5 && (meta & KeyEvent.META_CTRL_ON) == 0
                        && swipeTypingController != null && swipeTypingController.backspace(ic)) {
                    break;
                }
                if (primaryCode == -5 && swipeTypingController != null) {
                    swipeTypingController.finish(ic, false);
                }
                if (koreanLetter) {
                    if (typingAssistAvailable) autoCorrectController.beforeManualEdit();
                    updateKoreanComposition(ic, hangulComposer.input(KoreanKeyMap.toJamo(code)));
                    if (typingAssistAvailable && autoCorrectController != null) {
                        autoCorrectController.onTypedTextUpdated(true);
                    }
                    break;
                }
                if (primaryCode == -5 && (meta & KeyEvent.META_CTRL_ON) == 0
                        && hangulComposer.hasComposition()) {
                    if (typingAssistAvailable) autoCorrectController.beforeManualEdit();
                    updateKoreanComposition(ic, hangulComposer.backspace());
                    if (typingAssistAvailable && autoCorrectController != null) {
                        autoCorrectController.onBackspaceTyped(true);
                    }
                    break;
                }
                finishKoreanComposition(ic);
                if (typingAssistAvailable && (meta & KeyEvent.META_CTRL_ON) == 0) {
                    if (primaryCode == -5) {
                        autoCorrectController.beforeManualEdit();
                        deleteTypedCharacter(ic);
                        autoCorrectController.onBackspaceTyped(koreanLayoutSelected && koreanMode);
                        break;
                    }
                    if (primaryCode >= 33 && primaryCode <= 126) {
                        autoCorrectController.beforeManualEdit();
                        ic.commitText(String.valueOf(code), 1);
                        if (Character.isLetter(code)) autoCorrectController.onTypedTextUpdated(false);
                        else autoCorrectController.onNonLetterTyped();
                        break;
                    }
                }
                if ((autoCapitalizationEnabled || doubleSpacePeriodEnabled) && isOrdinaryTypingField()
                        && (meta & KeyEvent.META_CTRL_ON) == 0 && !typingAssistAvailable) {
                    if (primaryCode >= 33 && primaryCode <= 126) {
                        ic.commitText(String.valueOf(code), 1);
                        break;
                    }
                    if (primaryCode == -5) {
                        deleteTypedCharacter(ic);
                        break;
                    }
                }
                //Convert primaryCode to KeyEvent:
                //primaryCode is the char value, it doesn't correspond to the KeyEvent that we want to press
                int ke = 0;
                switch (primaryCode) {
                    case 9:
                        ke = KeyEvent.KEYCODE_TAB;
                        //ic.commitText("\u0009", 1);
                        break;
                    case -2:
                        ke = KeyEvent.KEYCODE_ESCAPE;
                        break;
                    case 32:
                        ke = KeyEvent.KEYCODE_SPACE;
                        break;
                    case -5:
                        ke = KeyEvent.KEYCODE_DEL;
                        break;
                    case -4:
                        ke = KeyEvent.KEYCODE_ENTER;
                        break;
                    case -6:
                        ke = KeyEvent.KEYCODE_F1;
                        break;
                    case -7:
                        ke = KeyEvent.KEYCODE_F2;
                        break;
                    case -8:
                        ke = KeyEvent.KEYCODE_F3;
                        break;
                    case -9:
                        ke = KeyEvent.KEYCODE_F4;
                        break;
                    case -10:
                        ke = KeyEvent.KEYCODE_F5;
                        break;
                    case -11:
                        ke = KeyEvent.KEYCODE_F6;
                        break;
                    case -12:
                        ke = KeyEvent.KEYCODE_F7;
                        break;
                    case -13:
                        ke = KeyEvent.KEYCODE_F8;
                        break;
                    case -14:
                        ke = KeyEvent.KEYCODE_F9;
                        break;
                    case -15:
                        ke = KeyEvent.KEYCODE_F10;
                        break;
                    case -16:
                        ke = KeyEvent.KEYCODE_F11;
                        break;
                    case -17:
                        ke = KeyEvent.KEYCODE_F12;
                        break;
                    case -18:
                        ke = KeyEvent.KEYCODE_MOVE_HOME;
                        break;
                    case -19:
                        ke = KeyEvent.KEYCODE_MOVE_END;
                        break;
                    case -20:
                        ke = KeyEvent.KEYCODE_INSERT;
                        break;
                    case -21:
                        ke = KeyEvent.KEYCODE_FORWARD_DEL;
                        break;
                    case -22:
                        ke = KeyEvent.KEYCODE_PAGE_UP;
                        break;
                    case -23:
                        ke = KeyEvent.KEYCODE_PAGE_DOWN;
                        break;

                    //These are like a directional joystick - can jump outside the inputConnection
                    case 5000:
                        ke = KeyEvent.KEYCODE_DPAD_LEFT;
                        break;
                    case 5001:
                        ke = KeyEvent.KEYCODE_DPAD_DOWN;
                        break;
                    case 5002:
                        ke = KeyEvent.KEYCODE_DPAD_UP;
                        break;
                    case 5003:
                        ke = KeyEvent.KEYCODE_DPAD_RIGHT;
                        break;
                    default:
                        //(t key) code 116-> ke 48
                        if (Character.isLetter(code)) {
                            ke = KeyEvent.keyCodeFromString("KEYCODE_" + Character.toUpperCase(code));
                        }
//                        if (primaryCode >= 48 && primaryCode <= 57) {
//                            ke = KeyEvent.keyCodeFromString("KEYCODE_" + code);
//                        }
                }
                if (ke != 0) {

                    Log.d(getClass().getSimpleName(), "onKey: keyEvent " + ke);

                    /*
                     *   The if statement was added in order to prevent the space button
                     *   from having an action down event attached to it.
                     *   Reason being that we first want to check
                     *   whether the space button has been long pressed or not
                     *   and afterwards produce the right output to the screen.
                     *   TODO: Investigate whether KeyEvent.ACTION_UP is still required.
                     */
                    if (primaryCode != 32) {
                        ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, ke, 0, meta));
                    }

                    ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_UP, ke, 0, meta));
                } else {
                    //All non-letter characters are handled here
                    // This doesn't use modifiers.
                    // For most users, this usage makes sense.
                    //eg. (0 key) code 48 -> ke 7
                    // If we handled '0' with a keyEvent, shift+0 would result in ')'
                    Log.i(getClass().getSimpleName(), "onKey: committext " + String.valueOf(code));
                    ic.commitText(String.valueOf(code), 1);
                }
            }
        }
        if ((primaryCode >= 33 && primaryCode <= 126) || primaryCode == -5 || primaryCode == -4) {
            capitalizationSuppressed = false;
            updateAutomaticShift();
        }
        refreshSuggestionHint();
    }

    public void onPress(final int primaryCode) {
        if (soundOn) {
            MediaPlayer keypressSoundPlayer = MediaPlayer.create(this, R.raw.keypress_sound);
            keypressSoundPlayer.start();
            keypressSoundPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    mp.release();
                }
            });
        }
        if (vibratorOn) {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null)
                vibrator.vibrate(vibrateLength);
        }

        clearLongPressTimer();
        final long generation = longPressGeneration;
        timerLongPress = new Timer();
        timerLongPress.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    Handler uiHandler = new Handler(Looper.getMainLooper());
                    Runnable runnable = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (generation == longPressGeneration)
                                    CodeBoardIME.this.onKeyLongPress(primaryCode);
                            } catch (Exception e) {
                                Log.e(getClass().getSimpleName(), "uiHandler.run: " + e.getMessage(), e);
                            }
                        }
                    };
                    uiHandler.post(runnable);
                } catch (Exception e) {
                    Log.e(getClass().getSimpleName(), "Timer.run: " + e.getMessage(), e);
                }
            }
        }, ViewConfiguration.getLongPressTimeout());
    }

    @Override
    public void onExtractingInputChanged(EditorInfo ei){
        Log.d(getClass().getSimpleName(), "onExtractingInputChanged: ");
    }
    @Override
    public void requestHideSelf(int flags){
        int new_flag = HIDE_IMPLICIT_ONLY;
        new_flag = flags;
        Log.d(getClass().getSimpleName(), "requestHideSelf: "+new_flag + ","+ flags);
        // do nothing
        super.requestHideSelf(new_flag);
    }

    @Override
    public void onWindowHidden() {
        if (voiceInputBridge != null) voiceInputBridge.onEditorUnavailable();
        cancelTypingState();
        finishKoreanComposition(getCurrentInputConnection());
        super.onWindowHidden();
        clearLongPressTimer();
    }

    @Override
    public void onViewClicked(boolean focusChanged) {
        if (focusChanged) cancelTypingState();
        if (focusChanged) finishKoreanComposition(getCurrentInputConnection());
        super.onViewClicked(focusChanged);
        clearLongPressTimer();
        refreshSuggestionHint();
    }

    public void onRelease(int primaryCode) {
        if (primaryCode == 32 && deferPendingInput(() -> onRelease(primaryCode))) {
            clearLongPressTimer();
            return;
        }

        /*
         *   After the space button is released,
         *   we check whether it was long pressed or not.
         *   If it was, we don't do anything,
         *   but If it wasn't, we print a "space" to the screen.
         */
        if ((primaryCode == 32) && (!longPressedSpaceButton)) {

            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                if (swipeTypingController != null) swipeTypingController.finish(ic, false);
                finishKoreanComposition(ic);
                if (tryDoubleSpace(ic)) {
                    clearLongPressTimer();
                    return;
                }
                if (!isTypingAssistAvailable() || autoCorrectController == null
                        || !autoCorrectController.commitSpace()) {
                    boolean trackSpace = isTypingAssistAvailable() && autoCorrectController != null;
                    if (trackSpace) autoCorrectController.beforeManualEdit();
                    else if (autoCorrectController != null) autoCorrectController.invalidateEditorPosition();
                    boolean inserted = ic.commitText(String.valueOf((char) primaryCode), 1);
                    if (trackSpace) {
                        if (inserted) autoCorrectController.onManualSpaceInserted();
                        else autoCorrectController.invalidateEditorPosition();
                    }
                }
                if (isOrdinaryTypingField()) {
                    typingConveniences.recordSpace(SystemClock.uptimeMillis());
                    CharSequence before = ic.getTextBeforeCursor(1, 0);
                    lastSpaceCursor = before == null || !before.toString().equals(" ")
                            ? -1 : verifiedEditorCursor();
                }
                consumeShift(ic);
                updateAutomaticShift();
            }
        }

        longPressedSpaceButton = false;

        clearLongPressTimer();
    }

    public void onKeyLongPress(int keyCode) {
        // Process long-click here
        // This is following an onKey()
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (keyCode == 16) {
            shiftLock = !shiftLock;
            if (shiftLock) {
                shift = true;
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT));
            } else {
                shift = false;
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT));
            }
            shiftKeyUpdateView();
        }

        if (keyCode == 17) {
            ctrlLock = !ctrlLock;
            if (ctrlLock) {
                ctrl = true;
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT));
            } else {
                ctrl = false;
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT));
            }
            controlKeyUpdateView();
        }

        if (keyCode == 32) {

            longPressedSpaceButton = true;

            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.showInputMethodPicker();
        }

        if (vibratorOn) {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null)
                vibrator.vibrate(vibrateLength);
        }
    }

    public void onText(CharSequence text) {
        if (voiceInputBridge != null) voiceInputBridge.onManualInput();
        typingConveniences.clear();
        lastSpaceCursor = -1;
        String value = text.toString();
        if (deferPendingInput(() -> onText(value))) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (autoCorrectController != null) {
                autoCorrectController.cancel();
                autoCorrectController.invalidateEditorPosition();
            }
            if (swipeTypingController != null) swipeTypingController.finish(ic, false);
            finishKoreanComposition(ic);
            ic.commitText(text, 1);
        }
        clearLongPressTimer();
    }

    @Override
    public void swipeLeft() {

    }

    @Override
    public void swipeRight() {

    }

    @Override
    public void swipeDown() {

    }

    @Override
    public void swipeUp() {

    }

    @Override
    public View onCreateInputView() {

        if (mKeyboardUiFactory == null) {
            mKeyboardUiFactory = new KeyboardUiFactory(this);
        }
        KeyboardPreferences sharedPreferences = new KeyboardPreferences(this);
        setNotification(sharedPreferences.getNotification());
        if (sharedPreferences.getCustomTheme()) {
            mKeyboardUiFactory.theme = getDefaultThemeInfo();
            mKeyboardUiFactory.theme.foregroundColor = sharedPreferences.getFgColor();
            mKeyboardUiFactory.theme.backgroundColor = sharedPreferences.getBgColor();
        } else {
            mKeyboardUiFactory.theme = setThemeByIndex(sharedPreferences, sharedPreferences.getThemeIndex());
        }
        // Keyboard Features
        vibrateLength = sharedPreferences.getVibrateLength();
        vibratorOn = sharedPreferences.isVibrateEnabled();
        soundOn = sharedPreferences.isSoundEnabled();
        swipeTypingEnabled = sharedPreferences.isSwipeTypingEnabled();
        autoCorrectEnabled = sharedPreferences.isAutoCorrectEnabled();
        useTopSuggestion = sharedPreferences.useTopSuggestion();
        spellingSuggestionsEnabled = sharedPreferences.isSpellingSuggestionsEnabled();
        autoCapitalizationEnabled = sharedPreferences.isAutoCapitalizationEnabled();
        doubleSpacePeriodEnabled = sharedPreferences.isDoubleSpacePeriodEnabled();
        auxiliaryPanel = null;
        mKeyboardUiFactory.theme.enablePreview = sharedPreferences.isPreviewEnabled();
        mKeyboardUiFactory.theme.enableBorder = sharedPreferences.isBorderEnabled();
        mKeyboardUiFactory.theme.fontSize = sharedPreferences.getFontSizeAsSp();
        int mSize = sharedPreferences.getPortraitSize();
        int sizeLandscape = sharedPreferences.getLandscapeSize();
        mKeyboardUiFactory.theme.size = mSize / 100.0f;
        mKeyboardUiFactory.theme.sizeLandscape = sizeLandscape / 100.0f;
        if (sharedPreferences.getNavBarDark()) {
            Objects.requireNonNull(getWindow().getWindow()).
                    setNavigationBarColor(
                            ColorUtils.blendARGB(mKeyboardUiFactory.theme.backgroundColor,
                                    Color.BLACK, 0.2f));
        } else if (sharedPreferences.getNavBar()) {
            Objects.requireNonNull(getWindow().getWindow()).
                    setNavigationBarColor(mKeyboardUiFactory.theme.backgroundColor);
        }
        //Key Layout
        boolean mToprow = sharedPreferences.getTopRowActions();
        String mCustomSymbolsMain = sharedPreferences.getCustomSymbolsMain();
        String mCustomSymbolsMain2 = sharedPreferences.getCustomSymbolsMain2();
        String mCustomSymbolsSym = sharedPreferences.getCustomSymbolsSym();
        String mCustomSymbolsSym2 = sharedPreferences.getCustomSymbolsSym2();
        String mCustomSymbolsSym3 = sharedPreferences.getCustomSymbolsSym3();
        String mCustomSymbolsSym4 = sharedPreferences.getCustomSymbolsSym4();
        String mCustomSymbolsMainBottom = sharedPreferences.getCustomSymbolsMainBottom();
        int mLayout = sharedPreferences.getLayoutIndex();
        boolean nextKoreanLayout = mLayout == Definitions.LAYOUT_KOREAN;
        if (koreanLayoutSelected != nextKoreanLayout) {
            cancelTypingState();
            finishKoreanComposition(getCurrentInputConnection());
            koreanMode = true;
        }
        koreanLayoutSelected = nextKoreanLayout;

        //Need this to get resources for drawables
        Definitions definitions = new Definitions(this);
        try {
            KeyboardLayoutBuilder builder = new KeyboardLayoutBuilder(this);
            builder.setBox(Box.create(0, 0, 1, 1));

            if (mToprow) {
                definitions.addCopyPasteRow(builder);
            } else {
                definitions.addArrowsRow(builder);
            }

            if (mKeyboardState == R.integer.keyboard_sym) {
                if (!mCustomSymbolsSym.isEmpty()) {
                    Definitions.addCustomRow(builder, mCustomSymbolsSym);
                }
                if (!mCustomSymbolsSym2.isEmpty()) {
                    Definitions.addCustomRow(builder, mCustomSymbolsSym2);
                }
                if (!mCustomSymbolsSym3.isEmpty()) {
                    Definitions.addCustomRow(builder, mCustomSymbolsSym3);
                }
                if (!mCustomSymbolsSym4.isEmpty()) {
                    Definitions.addCustomRow(builder, mCustomSymbolsSym4);
                }
                if (mCustomSymbolsSym3.isEmpty() && mCustomSymbolsSym4.isEmpty()) {
                    definitions.addSymbolRows(builder);
                } else {
                    definitions.addCustomSpaceRow(builder, mCustomSymbolsMainBottom);
                }
            } else if (mKeyboardState == R.integer.keyboard_normal) {
                if (!mCustomSymbolsMain.isEmpty()) {
                    Definitions.addCustomRow(builder, mCustomSymbolsMain);
                }
                if (!mCustomSymbolsMain2.isEmpty()) {
                    Definitions.addCustomRow(builder, mCustomSymbolsMain2);
                }
                switch (mLayout) {
                    default:
                    case 0:
                        Definitions.addQwertyRows(builder);
                        break;
                    case 1:
                        Definitions.addAzertyRows(builder);
                        break;
                    case 2:
                        Definitions.addDvorakRows(builder);
                        break;
                    case 3:
                        Definitions.addQwertzRows(builder);
                        break;
                    case Definitions.LAYOUT_KOREAN:
                        if (koreanMode) {
                            Definitions.addKoreanRows(builder);
                        } else {
                            Definitions.addQwertyRows(builder);
                        }
                        break;
                }
                definitions.addCustomSpaceRow(builder, mCustomSymbolsMainBottom, koreanLayoutSelected);
            } else if (mKeyboardState == R.integer.keyboard_clipboard) {
                definitions.addClipboardActions(builder);

                ClipboardManager clipboard = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard.hasPrimaryClip()
                        && clipboard.getPrimaryClipDescription().hasMimeType(MIMETYPE_TEXT_PLAIN)) {
                    ClipData pr = clipboard.getPrimaryClip();
                    //Android only allows one item in Clipboard
                    String s = pr.getItemAt(0).getText().toString();
                    builder.newRow().addKey(s);
                } else {
                    builder.newRow().addKey("Nothing copied").withOutputText("");
                }
                builder.addKey(sharedPreferences.getPin1());
                builder.newRow()
                        .addKey(sharedPreferences.getPin2())
                        .addKey(sharedPreferences.getPin3());
                builder.newRow()
                        .addKey(sharedPreferences.getPin4())
                        .addKey(sharedPreferences.getPin5());
                builder.newRow()
                        .addKey(sharedPreferences.getPin6())
                        .addKey(sharedPreferences.getPin7());
            }

            Collection<Key> keyboardLayout = builder.build();
            mCurrentKeyboardLayoutView = mKeyboardUiFactory.createKeyboardView(this, keyboardLayout);
            boolean landscape = isLandscape();
            mCurrentKeyboardLayoutView.setKeyboardHeightPercent(landscape ? sizeLandscape : mSize);
            mCurrentKeyboardLayoutView.setCursorControlEnabled(sharedPreferences.isCursorControlEnabled());
            if (swipeTypingEnabled || spellingSuggestionsEnabled || autoCorrectEnabled) {
                if (swipeTypingController == null) {
                    swipeTypingController = new SwipeTypingController(this,
                            new SwipeTypingController.ConnectionProvider() {
                                @Override
                                public InputConnection get() {
                                    return getCurrentInputConnection();
                                }
                            });
                }
                swipeSuggestions = new SuggestionStripView(this, mKeyboardUiFactory.theme);
                swipeTypingController.setSuggestionStrip(swipeSuggestions);
                if (spellingSuggestionsEnabled || autoCorrectEnabled) {
                    boolean created = false;
                    if (autoCorrectController == null) {
                        autoCorrectController = new AutoCorrectController(this,
                                new AutoCorrectController.ConnectionProvider() {
                                    @Override public InputConnection get() {
                                        return getCurrentInputConnection();
                                    }
                                }, () -> finishKoreanComposition(getCurrentInputConnection()));
                        created = true;
                    }
                    autoCorrectController.setAutoCorrectionEnabled(autoCorrectEnabled);
                    autoCorrectController.setUseTopSuggestion(useTopSuggestion);
                    autoCorrectController.setEmailOnly(sEditorInfo != null
                            && supportsEmailLiteralInputType(sEditorInfo.inputType));
                    autoCorrectController.setSuggestionStrip(spellingSuggestionsEnabled ? swipeSuggestions : null);
                    if (created) seedTypingAssistEditor();
                } else if (autoCorrectController != null) {
                    autoCorrectController.cancel();
                    autoCorrectController.setSuggestionStrip(null);
                }
                swipeSuggestions.setHintText(spellingSuggestionsEnabled
                        ? (swipeTypingEnabled ? "Swipe or type for suggestions" : "Type for suggestions")
                        : "Swipe across letters to type");
            } else {
                cancelTypingState();
                if (swipeTypingController != null) swipeTypingController.setSuggestionStrip(null);
                if (autoCorrectController != null) autoCorrectController.setSuggestionStrip(null);
                swipeSuggestions = null;
            }
            controlKeyUpdateView();
            shiftKeyUpdateView();
            updateSwipeAvailability();
            inputContainer = new ResizableInputView(this);
            inputContainer.setKeyboardWidthPercent(sharedPreferences.getKeyboardWidth(landscape));
            if (swipeSuggestions != null) inputContainer.addKeyboardView(swipeSuggestions);
            inputContainer.addKeyboardView(mCurrentKeyboardLayoutView);
            updateAutomaticShift();
            return inputContainer;

        } catch (KeyboardLayoutException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void onUpdateExtractingVisibility(EditorInfo ei) {
        ei.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        super.onUpdateExtractingVisibility(ei);
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        sEditorInfo = attribute;
        setInputView(onCreateInputView());
        notifyVoiceEditorReady(restarting);
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        cancelTypingState();
        finishKoreanComposition(getCurrentInputConnection());
        sEditorInfo = attribute;
        typingConveniences.clear();
        lastSpaceCursor = -1;
        manualShift = capitalizationSuppressed = cursorDragging = false;
        shift = shiftLock = ctrl = ctrlLock = false;
        editorSelectionStart = attribute.initialSelStart;
        editorSelectionEnd = attribute.initialSelEnd;
        seedTypingAssistEditor();
        updateSwipeAvailability();
        updateAutomaticShift();
        notifyVoiceEditorReady(restarting);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        if (voiceInputBridge != null) voiceInputBridge.onEditorUnavailable();
        cancelTypingState();
        finishKoreanComposition(getCurrentInputConnection());
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        if (voiceInputBridge != null) voiceInputBridge.onEditorUnavailable();
        cancelTypingState();
        finishKoreanComposition(getCurrentInputConnection());
        clearLongPressTimer();
        super.onFinishInput();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart,
                                  int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        if (voiceInputBridge != null) voiceInputBridge.onSelectionChanged(newSelStart, newSelEnd);
        editorSelectionStart = newSelStart;
        editorSelectionEnd = newSelEnd;
        if (swipeTypingController != null) {
            swipeTypingController.onSelectionChanged(oldSelStart, oldSelEnd,
                    newSelStart, newSelEnd, candidatesEnd);
        }
        if (autoCorrectController != null) {
            if (sEditorInfo != null && supportsTypingAssistInputType(sEditorInfo.inputType)
                    && (spellingSuggestionsEnabled || autoCorrectEnabled)) {
                autoCorrectController.onSelectionChanged(oldSelStart, oldSelEnd,
                        newSelStart, newSelEnd, candidatesEnd);
            } else {
                autoCorrectController.invalidateEditorPosition();
            }
        }
        reconcileFirstSpaceCursor();
        updateAutomaticShift();
        notifyVoiceEditorReady(false);
        if (hangulComposer.hasComposition()
                && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            finishKoreanComposition(getCurrentInputConnection());
        }
    }

    private void updateKoreanComposition(InputConnection ic, HangulComposer.Update update) {
        ic.beginBatchEdit();
        try {
            if (!update.committedText.isEmpty()) {
                ic.commitText(update.committedText, 1);
            }
            ic.setComposingText(update.composingText, 1);
        } finally {
            ic.endBatchEdit();
        }
    }

    private void finishKoreanComposition(InputConnection ic) {
        if (hangulComposer.hasComposition()) {
            hangulComposer.reset();
            if (ic != null) ic.finishComposingText();
        }
    }

    public void controlKeyUpdateView() {
        if (mCurrentKeyboardLayoutView != null) mCurrentKeyboardLayoutView.applyCtrlModifier(ctrl);
        updateSwipeAvailability();
    }

    private boolean deferPendingInput(Runnable event) {
        if (autoCorrectController != null && autoCorrectController.deferIfBusy(event)) return true;
        return swipeTypingController != null && swipeTypingController.deferIfDecoding(event);
    }

    private void cancelTypingState() {
        if (autoCorrectController != null) autoCorrectController.cancel();
        if (swipeTypingController != null) swipeTypingController.cancel(getCurrentInputConnection());
    }

    private boolean isTypingAssistAvailable() {
        return (spellingSuggestionsEnabled || autoCorrectEnabled) && autoCorrectController != null && !ctrl
                && mKeyboardState != R.integer.keyboard_clipboard && sEditorInfo != null
                && supportsTypingAssistInputType(sEditorInfo.inputType);
    }

    private void seedTypingAssistEditor() {
        if (autoCorrectController == null) return;
        boolean allowed = (spellingSuggestionsEnabled || autoCorrectEnabled)
                && sEditorInfo != null && supportsTypingAssistInputType(sEditorInfo.inputType);
        autoCorrectController.setEmailOnly(sEditorInfo != null
                && supportsEmailLiteralInputType(sEditorInfo.inputType));
        autoCorrectController.onStartInput(allowed ? getCurrentInputConnection() : null,
                allowed ? editorSelectionStart : -1, allowed ? editorSelectionEnd : -1);
    }

    private void refreshSuggestionHint() {
        if (swipeSuggestions == null) return;
        if (!spellingSuggestionsEnabled) {
            swipeSuggestions.setHintText(getString(R.string.spelling_suggestions_disabled_hint));
        } else if (sEditorInfo != null && !supportsTypingAssistInputType(sEditorInfo.inputType)) {
            swipeSuggestions.setHintText(getString(R.string.spelling_suggestions_unavailable_hint));
        } else {
            swipeSuggestions.setHintText(getString(swipeTypingEnabled
                    ? R.string.swipe_and_type_suggestions_hint : R.string.type_suggestions_hint));
        }
    }

    private static boolean supportsTypingAssistInputType(int inputType) {
        return supportsAutoCorrectInputType(inputType) || supportsEmailLiteralInputType(inputType);
    }

    static boolean supportsEmailLiteralInputType(int inputType) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
                || (inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
    }

    private boolean isOrdinaryTypingField() {
        return sEditorInfo != null && supportsAutoCorrectInputType(sEditorInfo.inputType)
                && mKeyboardState == R.integer.keyboard_normal && !ctrl;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void consumeShift(InputConnection ic) {
        if (shift && !shiftLock) {
            shift = manualShift = false;
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT));
            shiftKeyUpdateView();
        }
    }

    private void updateAutomaticShift() {
        if (manualShift || shiftLock || cursorDragging) return;
        boolean enabled = autoCapitalizationEnabled && isOrdinaryTypingField()
                && !(koreanLayoutSelected && koreanMode) && !capitalizationSuppressed
                && verifiedEditorCursor() >= 0;
        boolean next = enabled && TypingConveniences.shouldCapitalize(
                getCurrentInputConnection(), sEditorInfo.inputType);
        if (next != shift) {
            shift = next;
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.sendKeyEvent(new KeyEvent(next ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_SHIFT_LEFT));
            shiftKeyUpdateView();
        }
    }

    private void reconcileFirstSpaceCursor() {
        if (lastSpaceCursor < 0 || verifiedEditorCursor() == lastSpaceCursor) return;
        int tracked = autoCorrectController == null ? -1 : autoCorrectController.getTrackedSpaceCursorPosition();
        if (tracked >= 0) lastSpaceCursor = tracked;
        else {
            typingConveniences.clear();
            lastSpaceCursor = -1;
        }
    }

    private boolean tryDoubleSpace(InputConnection ic) {
        reconcileFirstSpaceCursor();
        if (!doubleSpacePeriodEnabled || !isOrdinaryTypingField()
                || lastSpaceCursor < 0 || verifiedEditorCursor() != lastSpaceCursor) return false;
        CharSequence before = ic.getTextBeforeCursor(256, 0);
        if (before == null || before.length() < 2 || before.charAt(before.length() - 1) != ' ') return false;
        int end = before.length() - 1;
        int start = end;
        while (start > 0 && !Character.isWhitespace(before.charAt(start - 1))) start--;
        if (start == 0 && before.length() == 256) return false;
        String token = before.subSequence(start, end).toString();
        // Natural words only: code, numbers, URLs and email addresses stay literal.
        if (!token.matches("(?:[a-z]+(?:'[a-z]+)?|[A-Z][a-z]*(?:'[a-z]+)?|[가-힣]+)")) return false;
        if (autoCorrectController != null) autoCorrectController.beforeManualEdit();
        boolean replaced = typingConveniences.tryDoubleSpace(ic, SystemClock.uptimeMillis());
        if (autoCorrectController != null)
            autoCorrectController.onManualTextReplaced(replaced ? " " : "", replaced ? ". " : "");
        if (!replaced) return false;
        lastSpaceCursor = -1;
        capitalizationSuppressed = false;
        updateAutomaticShift();
        return true;
    }

    private int verifiedEditorCursor() {
        if (sEditorInfo == null || !supportsTypingAssistInputType(sEditorInfo.inputType)) return -1;
        if (autoCorrectController != null) return autoCorrectController.getVerifiedCursorPosition();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return -1;
        android.view.inputmethod.ExtractedText text = ic.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (text == null || text.selectionStart < 0 || text.selectionStart != text.selectionEnd) return -1;
        long position = (long) text.startOffset + text.selectionStart;
        return position >= 0 && position <= Integer.MAX_VALUE ? (int) position : -1;
    }

    private void notifyVoiceEditorReady(boolean restarting) {
        if (voiceInputBridge != null) voiceInputBridge.onEditorReady(getCurrentInputConnection(),
                sEditorInfo, verifiedEditorCursor(), restarting);
    }

    private void startVoiceInput() {
        hideAuxiliaryPanel();
        InputConnection ic = getCurrentInputConnection();
        finishKoreanComposition(ic);
        if (swipeTypingController != null && ic != null) swipeTypingController.finish(ic, false);
        if (voiceInputBridge == null) voiceInputBridge = new VoiceInputBridge(this, new VoiceInputBridge.Listener() {
            @Override public InputConnection getCurrentInputConnection() { return CodeBoardIME.this.getCurrentInputConnection(); }
            @Override public void onVoiceText(String text) {
                cancelTypingState();
                onText(text);
            }
        });
        if (!voiceInputBridge.launch(ic, sEditorInfo, verifiedEditorCursor(), koreanLayoutSelected && koreanMode)) {
            Toast.makeText(this, R.string.voice_unavailable_in_field, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideAuxiliaryPanel() {
        if (inputContainer == null) return;
        if (auxiliaryPanel != null) inputContainer.removeView(auxiliaryPanel);
        auxiliaryPanel = null;
        if (mCurrentKeyboardLayoutView != null) mCurrentKeyboardLayoutView.setVisibility(View.VISIBLE);
        updateSwipeAvailability();
    }

    private void showTools() {
        if (inputContainer == null) return;
        if (auxiliaryPanel instanceof KeyboardToolsView) {
            hideAuxiliaryPanel();
            return;
        }
        hideAuxiliaryPanel();
        cancelTypingState();
        auxiliaryPanel = new KeyboardToolsView(this, mKeyboardUiFactory.theme, action -> {
            switch (action) {
                case EMOJI: showEmoji(); break;
                case RESIZE: showResize(); break;
                case CLIPBOARD:
                    mKeyboardState = R.integer.keyboard_clipboard;
                    setInputView(onCreateInputView());
                    break;
                case LANGUAGE:
                    if (!koreanLayoutSelected) {
                        PreferenceManager.getDefaultSharedPreferences(this).edit()
                                .putString("layout", String.valueOf(Definitions.LAYOUT_KOREAN)).apply();
                        koreanMode = true;
                        mKeyboardState = R.integer.keyboard_normal;
                        setInputView(onCreateInputView());
                    } else onKey(Definitions.CODE_LANGUAGE_SWITCH, null);
                    break;
                case SETTINGS:
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case VOICE: startVoiceInput(); break;
                case REVIEW: showTextReview(); break;
                case PRACTICE:
                    startActivity(new Intent(this, TypingBenchmarkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
            }
        });
        inputContainer.addKeyboardView(auxiliaryPanel, 0);
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private void showTextReview() {
        hideAuxiliaryPanel();
        InputConnection connection = getCurrentInputConnection();
        if (!isOrdinaryTypingField() || connection == null) return;
        finishKoreanComposition(connection);
        if (swipeTypingController != null) swipeTypingController.finish(connection, false);
        int cursor = verifiedEditorCursor();
        CharSequence before = connection.getTextBeforeCursor(2001, 0);
        CharSequence after = connection.getTextAfterCursor(1, 0);
        if (before == null || after == null || after.length() != 0 || before.length() > 2000
                || cursor < 0 || cursor != before.length()) {
            Toast.makeText(this, "Place the cursor at the end of a message under 2,000 characters.", Toast.LENGTH_SHORT).show();
            return;
        }
        String original = before.toString();
        String proposed = com.gazlaws.codeboard.input.TextReview.review(original,
                new KeyboardPreferences(this).isChatSpacingEnabled());
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setPadding(16, 12, 16, 12);
        android.widget.TextView preview = new android.widget.TextView(this);
        preview.setTextColor(mKeyboardUiFactory.theme.foregroundColor);
        preview.setText(proposed.equals(original) ? "No supported repairs found. This is a basic English/Korean checker."
                : "Suggested text:\n" + proposed);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(preview); panel.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1,
                Math.round(140 * getResources().getDisplayMetrics().density)));
        android.widget.Button apply = new android.widget.Button(this);
        apply.setText("Apply"); apply.setEnabled(!proposed.equals(original));
        final boolean[] applied = {false};
        apply.setOnClickListener(v -> {
            String expected = applied[0] ? proposed : original;
            String replacement = applied[0] ? original : proposed;
            CharSequence current = connection.getTextBeforeCursor(2001, 0);
            CharSequence following = connection.getTextAfterCursor(1, 0);
            if (connection != getCurrentInputConnection() || current == null || following == null
                    || following.length() != 0 || !expected.contentEquals(current)
                    || verifiedEditorCursor() != expected.length()) {
                hideAuxiliaryPanel(); return;
            }
            cancelTypingState();
            if (autoCorrectController != null) autoCorrectController.beforeManualEdit();
            boolean changed = false;
            connection.beginBatchEdit();
            try {
                if (connection.deleteSurroundingText(expected.length(), 0)) {
                    changed = connection.commitText(replacement, 1);
                    if (!changed) connection.commitText(expected, 1);
                }
            } finally { connection.endBatchEdit(); }
            if (autoCorrectController != null) autoCorrectController.onManualTextReplaced(
                    changed ? expected : "", changed ? replacement : "");
            if (changed) {
                applied[0] = !applied[0]; apply.setText(applied[0] ? "Undo" : "Apply");
                preview.setText(applied[0] ? replacement : "Suggested text:\n" + proposed);
            }
        });
        panel.addView(apply);
        android.widget.Button done = new android.widget.Button(this); done.setText("Done");
        done.setOnClickListener(v -> hideAuxiliaryPanel()); panel.addView(done);
        auxiliaryPanel = panel;
        mCurrentKeyboardLayoutView.setVisibility(View.GONE);
        inputContainer.addKeyboardView(panel, 0);
    }

    private void applyKeyboardSize(int height, int width) {
        new KeyboardPreferences(this).setKeyboardSize(isLandscape(), height, width);
        mCurrentKeyboardLayoutView.setKeyboardHeightPercent(height);
        inputContainer.setKeyboardWidthPercent(width);
    }

    private void showResize() {
        if (inputContainer == null) return;
        hideAuxiliaryPanel();
        KeyboardPreferences prefs = new KeyboardPreferences(this);
        boolean landscape = isLandscape();
        auxiliaryPanel = new KeyboardResizeView(this, mKeyboardUiFactory.theme,
                landscape ? prefs.getLandscapeSize() : prefs.getPortraitSize(),
                prefs.getKeyboardWidth(landscape), 20, landscape ? 85 : 70, 60, 100,
                new KeyboardResizeView.Listener() {
                    @Override public void onSizeChanged(int height, int width) { applyKeyboardSize(height, width); }
                    @Override public void onReset() {
                        int height = isLandscape() ? 70 : 40;
                        applyKeyboardSize(height, 100);
                        ((KeyboardResizeView) auxiliaryPanel).setSize(height, 100);
                    }
                    @Override public void onDone() { hideAuxiliaryPanel(); }
                });
        inputContainer.addFullWidthView(auxiliaryPanel, 0);
    }

    private void showEmoji() {
        if (inputContainer == null) return;
        hideAuxiliaryPanel();
        cancelTypingState();
        int height = mCurrentKeyboardLayoutView.getHeight();
        if (height <= 0) height = Math.round(getResources().getDisplayMetrics().heightPixels
                * (isLandscape() ? mKeyboardUiFactory.theme.sizeLandscape : mKeyboardUiFactory.theme.size));
        auxiliaryPanel = new EmojiPickerView(this, mKeyboardUiFactory.theme, new EmojiPickerView.Listener() {
            @Override public void onEmoji(String emoji) { onText(emoji); }
            @Override public void onBackspace() {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                cancelTypingState();
                if (autoCorrectController != null) autoCorrectController.invalidateEditorPosition();
                deleteEmojiCharacter(ic);
            }
            @Override public void onDone() { hideAuxiliaryPanel(); }
        });
        auxiliaryPanel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(height, Math.round(240 * getResources().getDisplayMetrics().density))));
        mCurrentKeyboardLayoutView.setVisibility(View.GONE);
        if (swipeSuggestions != null) swipeSuggestions.setVisibility(View.GONE);
        inputContainer.addKeyboardView(auxiliaryPanel, 0);
    }

    private void deleteEmojiCharacter(InputConnection ic) {
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) { ic.commitText("", 1); return; }
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (before == null || before.length() == 0) return;
        int end = before.length();
        int start = previousEmojiUnit(before, end);
        int cp = Character.codePointBefore(before, end);
        if (cp >= 0x1f1e6 && cp <= 0x1f1ff && start > 0) {
            int previous = Character.codePointBefore(before, start);
            if (previous >= 0x1f1e6 && previous <= 0x1f1ff) start -= Character.charCount(previous);
        }
        while (start > 0 && Character.codePointBefore(before, start) == 0x200d) {
            start = previousEmojiUnit(before, start - 1);
        }
        ic.deleteSurroundingText(end - start, 0);
    }

    private static int previousEmojiUnit(CharSequence text, int end) {
        int cp = Character.codePointBefore(text, end);
        int start = end - Character.charCount(cp);
        if ((cp == 0xfe0f || cp == 0x20e3 || cp >= 0x1f3fb && cp <= 0x1f3ff) && start > 0) {
            int prior = Character.codePointBefore(text, start);
            start -= Character.charCount(prior);
            if (prior == 0xfe0f && start > 0) start -= Character.charCount(Character.codePointBefore(text, start));
        }
        return start;
    }

    static boolean supportsAutoCorrectInputType(int inputType) {
        if (!supportsSwipeInputType(inputType)
                || (inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation != InputType.TYPE_TEXT_VARIATION_URI
                && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
    }

    private void deleteTypedCharacter(InputConnection ic) {
        deleteEmojiCharacter(ic);
    }

    private boolean isSwipeAvailable() {
        return swipeTypingEnabled && !ctrl && mKeyboardState == R.integer.keyboard_normal
                && sEditorInfo != null && supportsSwipeInputType(sEditorInfo.inputType);
    }

    static boolean supportsSwipeInputType(int inputType) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    // This flag is an inlined bit, also understood by Codeboard on API 23-25.
    @SuppressLint("InlinedApi")
    private boolean allowsPersonalLearning() {
        return sEditorInfo != null && supportsAutoCorrectInputType(sEditorInfo.inputType)
                && (sEditorInfo.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
    }

    private void onTypingTap(char letter, float x, float y, List<SwipeGesture.KeyPosition> keys, int width, int height) {
        if (deferPendingInput(() -> onTypingTap(letter, x, y, keys, width, height))) return;
        if (autoCorrectController == null || !allowsPersonalLearning() || ctrl) return;
        List<SwipeDecoder.KeyPosition> geometry = new ArrayList<>();
        for (SwipeGesture.KeyPosition key : keys)
            geometry.add(new SwipeDecoder.KeyPosition(key.letter, key.x, key.y, key.width, key.height));
        float density = getResources().getDisplayMetrics().density;
        String profile = (isLandscape() ? "L" : "P") + (koreanLayoutSelected && koreanMode ? "K" : "E")
                + Math.round(width / density / 40) + ":" + Math.round(height / density / 40);
        autoCorrectController.recordTap(new TouchModel.Sample(letter, x, y, profile, geometry));
    }

    private void updateSwipeAvailability() {
        KeyboardPreferences preferences = new KeyboardPreferences(this);
        boolean learning = allowsPersonalLearning();
        if (autoCorrectController != null) autoCorrectController.setLearningEnabled(
                learning && preferences.isLearningEnabled(), learning && preferences.isTouchLearningEnabled());
        if (swipeTypingController != null) swipeTypingController.setLearningEnabled(learning && preferences.isLearningEnabled());
        if (swipeTypingController != null) {
            swipeTypingController.setContextRankingEnabled(autoCorrectEnabled && useTopSuggestion
                    && sEditorInfo != null && supportsAutoCorrectInputType(sEditorInfo.inputType));
        }
        if (mCurrentKeyboardLayoutView == null) return;
        boolean enabled = isSwipeAvailable();
        mCurrentKeyboardLayoutView.configureSwipe(enabled, this::onSwipeGesture);
        mCurrentKeyboardLayoutView.setTapListener(learning ? this::onTypingTap : null);
        if (swipeSuggestions != null) swipeSuggestions.setVisibility(
                enabled || (spellingSuggestionsEnabled && isTypingAssistAvailable()) ? View.VISIBLE : View.GONE);
        refreshSuggestionHint();
    }

    private void onSwipeGesture(List<SwipeGesture.Point> path, List<SwipeGesture.KeyPosition> keys) {
        clearLongPressTimer();
        if (voiceInputBridge != null) voiceInputBridge.onManualInput();
        typingConveniences.clear();
        if (deferPendingInput(() -> onSwipeGesture(path, keys))) return;
        if (!isSwipeAvailable() || swipeTypingController == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (autoCorrectController != null) {
            autoCorrectController.cancel();
            autoCorrectController.invalidateEditorPosition();
        }
        finishKoreanComposition(ic);
        boolean capitalize = shift;
        boolean capsLock = shiftLock;
        if (shift && !shiftLock) {
            shift = false;
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT));
            shiftKeyUpdateView();
        }
        List<SwipeDecoder.Point> points = new ArrayList<>(path.size());
        for (SwipeGesture.Point point : path) {
            points.add(new SwipeDecoder.Point(point.x, point.y, point.timeMs));
        }
        List<SwipeDecoder.KeyPosition> positions = new ArrayList<>(keys.size());
        for (SwipeGesture.KeyPosition key : keys) {
            positions.add(new SwipeDecoder.KeyPosition(key.letter, key.x, key.y, key.width, key.height));
        }
        swipeTypingController.onSwipe(points, positions, koreanLayoutSelected && koreanMode,
                capitalize, capsLock);
    }

    @Override
    public void onDestroy() {
        if (voiceInputBridge != null) voiceInputBridge.close();
        cancelTypingState();
        if (swipeTypingController != null) swipeTypingController.close();
        if (autoCorrectController != null) autoCorrectController.close();
        clearLongPressTimer();
        super.onDestroy();
    }

    public void shiftKeyUpdateView() {
        if (mCurrentKeyboardLayoutView != null) mCurrentKeyboardLayoutView.applyShiftModifier(shift);
    }

    private void clearLongPressTimer() {
        longPressGeneration++;
        if (timerLongPress != null) {
            timerLongPress.cancel();
        }
        timerLongPress = null;
    }

    private ThemeInfo setThemeByIndex(KeyboardPreferences keyboardPreferences, int index) {
        ThemeInfo themeInfo = ThemeDefinitions.Default();
        switch (index) {
            case 0:
                switch (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) {
                    case Configuration.UI_MODE_NIGHT_YES:
                        themeInfo = ThemeDefinitions.MaterialDark();
                        break;
                    case Configuration.UI_MODE_NIGHT_NO:
                        themeInfo = ThemeDefinitions.MaterialWhite();
                        break;
                }
                break;
            case 1:
                themeInfo = ThemeDefinitions.MaterialDark();
                break;
            case 2:
                themeInfo = ThemeDefinitions.MaterialWhite();
                break;
            case 3:
                themeInfo = ThemeDefinitions.PureBlack();
                break;
            case 4:
                themeInfo = ThemeDefinitions.White();
                break;
            case 5:
                themeInfo = ThemeDefinitions.Blue();
                break;
            case 6:
                themeInfo = ThemeDefinitions.Purple();
                break;
            default:
                themeInfo = ThemeDefinitions.Default();
                break;
        }
        keyboardPreferences.setBgColor(String.valueOf(themeInfo.backgroundColor));
        keyboardPreferences.setFgColor(String.valueOf(themeInfo.foregroundColor));
        return themeInfo;
    }

    private ThemeInfo getDefaultThemeInfo() {
        return ThemeDefinitions.Default();
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.notification_channel_name);
            String description = getString(R.string.notification_channel_description);
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private NotificationReceiver mNotificationReceiver;

    private static final int NOTIFICATION_ONGOING_ID = 1001;

    //Code from Hacker keyboard's source
    @SuppressLint("MissingPermission")
    private void setNotification(boolean visible) {
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (visible && mNotificationReceiver == null) {
            CharSequence text = "Keyboard notification enabled.";
            Log.i(getClass().getSimpleName(), "setNotification:" + text);

            createNotificationChannel();
            mNotificationReceiver = new NotificationReceiver(this);
            final IntentFilter pFilter = new IntentFilter(NotificationReceiver.ACTION_SHOW);

            androidx.core.content.ContextCompat.registerReceiver(this, mNotificationReceiver,
                    pFilter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);


            Intent imeIntent = new Intent(NotificationReceiver.ACTION_SHOW);
            PendingIntent imePendingIntent =
                    PendingIntent.getBroadcast(getApplicationContext(),
                            1, imeIntent, PendingIntent.FLAG_IMMUTABLE);

            // BUG: IM closes when notification drawer is closed
            // try making a input field here?
            // Key for the string that's delivered in the action's intent.
            String KEY_TEXT_REPLY = "key_text_reply";

            RemoteInput remoteInput = new RemoteInput.Builder(KEY_TEXT_REPLY)
                    .setLabel("Now click first icon")
                    .build();


            // Build a PendingIntent for the keyboard action to trigger first
            int flags = PendingIntent.FLAG_MUTABLE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT;
            }
            PendingIntent replyPendingIntent =
                    PendingIntent.getBroadcast(getApplicationContext(),
                            2,
                            imeIntent,
                            flags);


            // Create the reply action and add the remote input.
            NotificationCompat.Action action =
                    new NotificationCompat.Action.Builder(R.drawable.icon_large,
                            getString(R.string.notification_action_open_keyboard_workaround), replyPendingIntent)
                            .addRemoteInput(remoteInput)
                            .build();

            Intent settingsIntent = new Intent(this, MainActivity.class);
            settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent settingsPendingIntent =
                    PendingIntent.getActivity(this,0
                            , settingsIntent, PendingIntent.FLAG_IMMUTABLE);
            String title = "Show Codeboard Keyboard";
            String body = "Select this to open the keyboard. Disable in settings. You may have to fix open the fix as a workaround for newer Android versions";

            NotificationCompat.Builder mBuilder =
                    new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                            .setSmallIcon(R.drawable.icon_large)
                            .setColor(0xff220044)
                            .setAutoCancel(false)
                            .setTicker(text)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setContentIntent(imePendingIntent)
                            .setOngoing(true)
                            .addAction(R.drawable.icon_large, getString(R.string.notification_action_open_keyboard),
                                    imePendingIntent)
                            .addAction(R.drawable.icon_large, getString(R.string.notification_action_settings),
                                    settingsPendingIntent)
                            .addAction(action)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);

            notificationManager.notify(NOTIFICATION_ONGOING_ID, mBuilder.build());

        } else if (!visible && mNotificationReceiver != null) {
            mNotificationManager.cancel(NOTIFICATION_ONGOING_ID);
            unregisterReceiver(mNotificationReceiver);
            mNotificationReceiver = null;
        }
    }

    @Override
    public AbstractInputMethodImpl onCreateInputMethodInterface() {
        return new MyInputMethodImpl();
    }

    IBinder mToken;

    public class MyInputMethodImpl extends InputMethodImpl {
        @Override
        public void attachToken(IBinder token) {
            super.attachToken(token);
            Log.i(getClass().getSimpleName(), "attachToken " + token);
            if (mToken == null) {
                mToken = token;
            }
        }
    }

}
