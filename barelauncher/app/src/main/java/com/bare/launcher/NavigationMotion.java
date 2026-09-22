package com.bare.launcher;

/** Pure geometry for lightweight, directional launcher navigation motion. */
final class NavigationMotion {

    static final int SURFACE_DURATION_MS = 220;
    static final int GRID_FOCUS_DURATION_MS = 110;
    static final int GRID_FOCUS_GLIDE_DP = 10;

    private static final float MAX_SURFACE_TRAVEL_FRACTION = 0.55f;

    private NavigationMotion() {}

    /**
     * Returns the translation that visually aligns a resting target with the
     * source center. Travel is bounded so malformed geometry cannot move the
     * full-screen drawer completely outside the viewport.
     */
    static float surfaceOffsetY(float sourceCenterY, float targetCenterY,
                                int viewportHeight) {
        if (!Float.isFinite(sourceCenterY) || !Float.isFinite(targetCenterY)
                || viewportHeight <= 0) {
            return 0f;
        }
        float limit = viewportHeight * MAX_SURFACE_TRAVEL_FRACTION;
        return clamp(sourceCenterY - targetCenterY, -limit, limit);
    }

    /** Target enters from the horizontal direction of the previously focused cell. */
    static float focusOffsetX(int columns, int fromIndex, int toIndex,
                              int homeCount, float distance) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex || distance <= 0f) {
            return 0f;
        }
        int fromRow = HomeDrawerModel.rowOf(columns, fromIndex, homeCount);
        int toRow = HomeDrawerModel.rowOf(columns, toIndex, homeCount);
        if (fromRow != toRow) return 0f;
        int fromCol = HomeDrawerModel.colOf(columns, fromIndex, homeCount);
        int toCol = HomeDrawerModel.colOf(columns, toIndex, homeCount);
        if (toCol > fromCol) return -distance;
        if (toCol < fromCol) return distance;
        return 0f;
    }

    /** Target enters from the vertical direction of the previously focused cell. */
    static float focusOffsetY(int columns, int fromIndex, int toIndex,
                              int homeCount, float distance) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex || distance <= 0f) {
            return 0f;
        }
        int fromRow = HomeDrawerModel.rowOf(columns, fromIndex, homeCount);
        int toRow = HomeDrawerModel.rowOf(columns, toIndex, homeCount);
        if (toRow > fromRow) return -distance;
        if (toRow < fromRow) return distance;
        return 0f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
