package com.gazlaws.codeboard.layout.ui;

import java.util.List;

/** The trajectory and keyboard geometry for one completed swipe. */
public final class SwipeGesture {
    private SwipeGesture() { }

    public interface Listener {
        void onSwipe(List<Point> path, List<KeyPosition> keys);
    }

    /** A sample in keyboard-local pixels and the MotionEvent uptime in milliseconds. */
    public static final class Point {
        public final float x;
        public final float y;
        public final long timeMs;

        public Point(float x, float y, long timeMs) {
            this.x = x;
            this.y = y;
            this.timeMs = timeMs;
        }
    }

    /** A lowercase QWERTY position and its key bounds in keyboard-local pixels. */
    public static final class KeyPosition {
        public final char letter;
        public final float x;
        public final float y;
        public final float width;
        public final float height;

        public KeyPosition(char letter, float x, float y, float width, float height) {
            this.letter = letter;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}
