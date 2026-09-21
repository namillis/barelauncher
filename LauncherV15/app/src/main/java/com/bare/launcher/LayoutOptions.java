package com.bare.launcher;

/** Pure validation and stepping rules for user-selectable home/grid layout. */
final class LayoutOptions {
    static final int MIN_COLUMNS = 4;
    static final int MAX_COLUMNS = 7;
    static final int DEFAULT_COLUMNS = 6;

    static final int MIN_CORNER_PERCENT = 0;
    static final int MAX_CORNER_PERCENT = 30;
    static final int CORNER_STEP_PERCENT = 2;
    static final int DEFAULT_CORNER_PERCENT = 20;

    private LayoutOptions() { /* no instances */ }

    static int sanitizeColumns(int columns) {
        return Math.max(MIN_COLUMNS, Math.min(columns, MAX_COLUMNS));
    }

    static int sanitizeCornerPercent(int percent) {
        int clamped = Math.max(MIN_CORNER_PERCENT, Math.min(percent, MAX_CORNER_PERCENT));
        int stepped = Math.round(clamped / (float) CORNER_STEP_PERCENT)
                * CORNER_STEP_PERCENT;
        return Math.min(stepped, MAX_CORNER_PERCENT);
    }

    static int stepColumns(int columns, int direction) {
        int delta = Integer.compare(direction, 0);
        return sanitizeColumns(sanitizeColumns(columns) + delta);
    }

    static int stepCornerPercent(int percent, int direction) {
        int delta = Integer.compare(direction, 0) * CORNER_STEP_PERCENT;
        return sanitizeCornerPercent(sanitizeCornerPercent(percent) + delta);
    }
}
