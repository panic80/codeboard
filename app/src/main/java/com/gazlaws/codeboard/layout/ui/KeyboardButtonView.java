package com.gazlaws.codeboard.layout.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.KeyboardView;
import androidx.annotation.NonNull;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.gazlaws.codeboard.input.KoreanKeyMap;
import com.gazlaws.codeboard.layout.Box;
import com.gazlaws.codeboard.layout.Key;
import com.gazlaws.codeboard.theme.UiTheme;

import java.util.Timer;
import java.util.TimerTask;

public class KeyboardButtonView extends View {

    private static final String TAG = "KeyboardButtonView";

    private final Key key;
    private final KeyboardView.OnKeyboardActionListener inputService;
    private final UiTheme uiTheme;
    private Timer timer;
    private String currentLabel = null;
    private boolean isPressed = false;
    private boolean deferredPress = false;

    public KeyboardButtonView(Context context, Key key, KeyboardView.OnKeyboardActionListener inputService, UiTheme uiTheme) {
        super(context);
        this.inputService = inputService;
        this.key = key;
        this.uiTheme = uiTheme;
        this.currentLabel = key.info.label;
        //Enable shadow
        this.setOutlineProvider(ViewOutlineProvider.BOUNDS);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginPress(false);
                break;
            case MotionEvent.ACTION_UP:
                if (isPressed) {
                    onRelease();
                    performClick();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPress();
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** Only the normal alphabet rows participate, not literal letters in custom rows. */
    char swipeLetter() {
        if (key.info.isModifier || key.info.isRepeatable || key.info.outputText != null) return 0;
        char koreanPosition = KoreanKeyMap.qwertyKeyFor(key.info.code);
        if (koreanPosition != 0) return koreanPosition;
        int code = key.info.code;
        return code >= 'a' && code <= 'z' && key.info.onShiftLabel != null ? (char) code : 0;
    }

    void measureInParent(int width, int height) {
        Box box = key.box;
        int left = (int) (width * box.getLeft());
        int right = (int) (width * box.getRight());
        int top = (int) (height * box.getTop());
        int bottom = (int) (height * box.getBottom());
        measure(MeasureSpec.makeMeasureSpec(Math.max(0, right - left), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, bottom - top), MeasureSpec.EXACTLY));
    }

    void layoutInParent(int width, int height) {
        Box box = key.box;
        layout((int) (width * box.getLeft()), (int) (height * box.getTop()),
                (int) (width * box.getRight()), (int) (height * box.getBottom()));
    }

    /** Sends press feedback now, but waits to submit a character until it is known to be a tap. */
    void beginDeferredPress() {
        beginPress(true);
    }

    void completeDeferredPress() {
        if (!isPressed || !deferredPress) return;
        deferredPress = false;
        submitKeyEvent();
        if (isPressed) onRelease();
        performClick();
    }

    void cancelPress() {
        if (!isPressed) return;
        isPressed = false;
        deferredPress = false;
        stopRepeating();
        // A canceled space must not reach onRelease(32), which commits a space in the IME.
        // The zero code still lets the listener clear its pending long-press timer.
        inputService.onRelease(0);
        animateRelease();
    }

    @Override
    public void draw(Canvas canvas){
        drawButtonBody(canvas);
        drawButtonContent(canvas);
        super.draw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelPress();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        cancelPress();
    }

    private void drawButtonContent(Canvas canvas) {
        float x = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float labelWidth = uiTheme.foregroundPaint.measureText(currentLabel);
        float margin = uiTheme.buttonBodyPadding + 4 * getResources().getDisplayMetrics().density;
        float availableWidth = Math.max(0, getWidth() - 2 * margin);
        float availableHeight = Math.max(0, getHeight() - 2 * margin);
        float labelHeight = uiTheme.foregroundPaint.descent() - uiTheme.foregroundPaint.ascent();
        float scale = 1f;
        if (labelWidth > 0) scale = Math.min(scale, availableWidth / labelWidth);
        if (labelHeight > 0) scale = Math.min(scale, availableHeight / labelHeight);
        canvas.save();
        canvas.scale(scale, scale, x, centerY);
        canvas.drawText(currentLabel, x, centerY + uiTheme.fontHeight / 3, uiTheme.foregroundPaint);
        canvas.restore();

        if (key.info.icon != null){
            Drawable d = key.info.icon;
            d.setTint(uiTheme.foregroundPaint.getColor());

            int padding = (int)uiTheme.buttonBodyPadding*2;
            int top;
            int left;
            int squareSize;
            if (this.getWidth() > this.getHeight()){
                top = 2*padding;
                squareSize = (this.getHeight()/2) - top;
                left = (this.getWidth()/2) - squareSize;
            } else {
                left = 2*padding;
                squareSize = this.getWidth()/2-(left);
                top = this.getHeight()/2 - squareSize;
            }
            int right = left + (squareSize*2);
            int bottom = top + (squareSize*2);
            d.setBounds(left,top,right,bottom);
            d.draw(canvas);
        }
    }

    private void drawButtonBody(Canvas canvas) {
        float left = uiTheme.buttonBodyPadding;
        float top = uiTheme.buttonBodyPadding;
        float right = this.getWidth() - uiTheme.buttonBodyPadding;
        float bottom = this.getHeight() - uiTheme.buttonBodyPadding;
        float rx = uiTheme.buttonBodyBorderRadius;
        float ry = uiTheme.buttonBodyBorderRadius;
        canvas.drawRoundRect(left, top, right, bottom, rx, ry, uiTheme.buttonBodyPaint);
    }

    private void beginPress(boolean deferred) {
        if (isPressed) cancelPress();
        isPressed = true;
        deferredPress = deferred;
        inputService.onPress(key.info.code);
        if (key.info.isRepeatable) startRepeating();
        if (!deferred) submitKeyEvent();
        animatePress();
    }

    private void onRelease() {
        isPressed = false;
        deferredPress = false;
//      NOTE: If the arrow keys move out of the input view, the onRelease is never called
        if (key.info.code != 0){
            inputService.onRelease(key.info.code);
        }
        if (key.info.isRepeatable){
            stopRepeating();
        }
        animateRelease();
    }

    private void submitKeyEvent(){
        if (key.info.code != 0){
            inputService.onKey(key.info.code, null);
        }
        if (this.key.info.outputText != null){
            inputService.onText(key.info.outputText);
        }
    }

    private void stopRepeating() {
        if (timer == null){
            return;
        }
        timer.cancel();
        timer = null;
    }

    private void startRepeating() {
        if (timer != null){
            stopRepeating();
            return;
        }
        final Timer repeatTimer = new Timer();
        timer = repeatTimer;
        repeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (isPressed && timer == repeatTimer) submitKeyEvent();
                    }
                });
            }
        },400, 50);
    }

    private void animatePress(){
        if (uiTheme.enablePreview){
            this.setTranslationY(-200.0f);
            this.setScaleX(1.2f);
            this.setScaleY(1.2f);
            this.setElevation(21.0f);
        } else {
            this.setAlpha(.1f);
        }
    }
    private void animateRelease() {
        if (uiTheme.enablePreview){
            this.setTranslationY(0.0f);
            this.setScaleX(1.0f);
            this.setScaleY(1.0f);
            this.setElevation(0.0f);
        } else {
            this.animate().alpha(1.0f).setDuration(400);
        }
    }

    public void applyShiftModifier(boolean shiftPressed) {
        if (this.key.info.onShiftLabel != null){
            String nextLabel = shiftPressed
                    ? this.key.info.onShiftLabel
                    : this.key.info.label;
            setCurrentLabel(nextLabel);
        }
    }

    public void applyCtrlModifier(boolean ctrlPressed) {
        if (this.key.info.onCtrlLabel != null){
            String nextLabel = ctrlPressed
                    ? this.key.info.onCtrlLabel
                    : this.key.info.label;
            setCurrentLabel(nextLabel);
        }
    }

    private void setCurrentLabel(String nextLabel) {
        if (nextLabel != currentLabel){
            currentLabel = nextLabel;
            this.invalidate();
        }
    }
}
