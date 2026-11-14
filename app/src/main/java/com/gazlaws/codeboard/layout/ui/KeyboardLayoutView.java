package com.gazlaws.codeboard.layout.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import com.gazlaws.codeboard.theme.UiTheme;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class KeyboardLayoutView extends ViewGroup {

    private final UiTheme uiTheme;
    private final Paint swipePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float touchSlop;
    private final ArrayList<SwipeGesture.Point> swipePath = new ArrayList<>();

    private boolean swipeEnabled;
    private boolean ctrlPressed;
    private SwipeGesture.Listener swipeListener;
    private KeyboardButtonView startButton;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean tracking;
    private boolean canceled;
    private boolean visitedAnotherKey;
    private boolean swiping;
    private float startX;
    private float startY;
    private float maxDistanceSquared;

    public KeyboardLayoutView(Context context, UiTheme uiTheme) {
        super(context);
        setBackgroundColor(uiTheme.backgroundColor);
        this.uiTheme = uiTheme;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipePaint.setColor(uiTheme.foregroundPaint.getColor());
        swipePaint.setAlpha(190);
        swipePaint.setStyle(Paint.Style.STROKE);
        swipePaint.setStrokeCap(Paint.Cap.ROUND);
        swipePaint.setStrokeJoin(Paint.Join.ROUND);
        swipePaint.setStrokeWidth(5 * getResources().getDisplayMetrics().density);
    }

    /** Enables gestures on letter keys. Controls and disabled layouts keep their normal behavior. */
    public void configureSwipe(boolean enabled, SwipeGesture.Listener listener) {
        swipeEnabled = enabled;
        swipeListener = listener;
        if (!enabled || listener == null) cancelActiveGesture();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int availableHeight = metrics.heightPixels;
        int availableWidth = metrics.widthPixels;
        float keyboardSize = availableHeight > availableWidth
                ? uiTheme.portraitSize : uiTheme.landscapeSize;
        int width = resolveSize(availableWidth, widthMeasureSpec);
        int height = resolveSize((int) (availableHeight * keyboardSize), heightMeasureSpec);
        setMeasuredDimension(width, height);

        for (KeyboardButtonView button : getKeyboardButtons()) {
            button.measureInParent(width, height);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        for (KeyboardButtonView button : getKeyboardButtons()) {
            button.layoutInParent(width, height);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            return swipeEnabled && !ctrlPressed && swipeListener != null
                    && findLetterAt(event.getX(), event.getY()) != null;
        }
        return tracking;
    }

    // A completed tap calls the child button's performClick in completeDeferredPress().
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!swipeEnabled || ctrlPressed || swipeListener == null) return false;
                KeyboardButtonView button = findLetterAt(event.getX(), event.getY());
                if (button == null) return false;
                cancelActiveGesture();
                resetGesture();
                tracking = true;
                startButton = button;
                activePointerId = event.getPointerId(0);
                startX = event.getX();
                startY = event.getY();
                addPoint(startX, startY, event.getEventTime());
                button.beginDeferredPress();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                // Do not turn an ambiguous multi-finger sequence into text.
                if (tracking) {
                    canceled = true;
                    cancelDeferredPress();
                    invalidate();
                }
                return tracking;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                if (!canceled && !collectSamples(event)) {
                    canceled = true;
                    cancelDeferredPress();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!tracking) return false;
                if (!canceled && !collectSamples(event)) canceled = true;
                finishGesture(event);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!tracking) return false;
                cancelActiveGesture();
                return true;
            default:
                return tracking;
        }
    }

    private boolean collectSamples(MotionEvent event) {
        int index = event.findPointerIndex(activePointerId);
        if (index < 0 || event.getPointerCount() != 1) return false;
        for (int i = 0; i < event.getHistorySize(); i++) {
            addPoint(event.getHistoricalX(index, i), event.getHistoricalY(index, i),
                    event.getHistoricalEventTime(i));
        }
        addPoint(event.getX(index), event.getY(index), event.getEventTime());
        return true;
    }

    private void addPoint(float x, float y, long timeMs) {
        swipePath.add(new SwipeGesture.Point(x, y, timeMs));
        float dx = x - startX;
        float dy = y - startY;
        maxDistanceSquared = Math.max(maxDistanceSquared, dx * dx + dy * dy);
        KeyboardButtonView current = findLetterAt(x, y);
        if (current != null && current != startButton) visitedAnotherKey = true;
        if (!swiping && visitedAnotherKey && maxDistanceSquared >= touchSlop * touchSlop) {
            swiping = true;
            cancelDeferredPress();
        }
    }

    private void finishGesture(MotionEvent event) {
        KeyboardButtonView button = startButton;
        SwipeGesture.Listener listener = swipeListener;
        boolean shouldSwipe = !canceled && swiping && swipeEnabled && !ctrlPressed
                && listener != null;
        boolean shouldTap = !canceled && !swiping && button != null
                && isInside(button, event.getX(), event.getY());
        List<SwipeGesture.Point> path = shouldSwipe
                ? Collections.unmodifiableList(new ArrayList<>(swipePath)) : null;
        List<SwipeGesture.KeyPosition> keys = shouldSwipe ? collectKeyPositions() : null;
        if (!shouldTap) cancelDeferredPress();
        resetGesture();
        invalidate();
        if (shouldSwipe) {
            listener.onSwipe(path, keys);
        } else if (shouldTap) {
            button.completeDeferredPress();
        }
    }

    private void cancelDeferredPress() {
        if (startButton != null) startButton.cancelPress();
    }

    private void cancelActiveGesture() {
        if (tracking) {
            cancelDeferredPress();
            resetGesture();
            invalidate();
        }
    }

    private void resetGesture() {
        startButton = null;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        tracking = false;
        canceled = false;
        visitedAnotherKey = false;
        swiping = false;
        maxDistanceSquared = 0;
        swipePath.clear();
    }

    private KeyboardButtonView findLetterAt(float x, float y) {
        // This runs for every historical touch sample, so avoid allocating a child list here.
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof KeyboardButtonView) {
                KeyboardButtonView button = (KeyboardButtonView) child;
                if (button.swipeLetter() != 0 && isInside(button, x, y)) return button;
            }
        }
        return null;
    }

    private boolean isInside(View view, float x, float y) {
        return x >= view.getLeft() && x < view.getRight()
                && y >= view.getTop() && y < view.getBottom();
    }

    private List<SwipeGesture.KeyPosition> collectKeyPositions() {
        ArrayList<SwipeGesture.KeyPosition> keys = new ArrayList<>();
        for (KeyboardButtonView button : getKeyboardButtons()) {
            char letter = button.swipeLetter();
            if (letter != 0) {
                keys.add(new SwipeGesture.KeyPosition(letter,
                        (button.getLeft() + button.getRight()) / 2f,
                        (button.getTop() + button.getBottom()) / 2f,
                        button.getWidth(), button.getHeight()));
            }
        }
        return Collections.unmodifiableList(keys);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!tracking || !swiping || canceled || swipePath.size() < 2) return;
        Path path = new Path();
        SwipeGesture.Point first = swipePath.get(0);
        path.moveTo(first.x, first.y);
        for (int i = 1; i < swipePath.size(); i++) {
            SwipeGesture.Point point = swipePath.get(i);
            path.lineTo(point.x, point.y);
        }
        canvas.drawPath(path, swipePaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelActiveGesture();
        super.onDetachedFromWindow();
    }

    public void applyShiftModifier(boolean shiftPressed) {
        for (KeyboardButtonView button : getKeyboardButtons()) {
            button.applyShiftModifier(shiftPressed);
        }
    }

    public void applyCtrlModifier(boolean ctrlPressed) {
        this.ctrlPressed = ctrlPressed;
        if (ctrlPressed) cancelActiveGesture();
        for (KeyboardButtonView button : getKeyboardButtons()) {
            button.applyCtrlModifier(ctrlPressed);
        }
    }

    private Collection<KeyboardButtonView> getKeyboardButtons() {
        int childCount = getChildCount();
        ArrayList<KeyboardButtonView> list = new ArrayList<>(childCount);
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child instanceof KeyboardButtonView) list.add((KeyboardButtonView) child);
        }
        return list;
    }
}
