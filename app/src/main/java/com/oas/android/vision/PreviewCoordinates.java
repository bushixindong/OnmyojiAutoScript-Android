package com.oas.android.vision;
/** Maps a FIT_CENTER preview to the 1280x720 virtual screen, excluding letterbox bars. */
public final class PreviewCoordinates {
    private PreviewCoordinates() { }
    public static int[] map(int width, int height, float x, float y) {
        if (width <= 0 || height <= 0 || !Float.isFinite(x) || !Float.isFinite(y)) return null;
        float scale = Math.min(width / 1280f, height / 720f);
        float left = (width - 1280 * scale) / 2, top = (height - 720 * scale) / 2;
        if (x < left || x >= width - left || y < top || y >= height - top) return null;
        return new int[]{Math.min(1279, (int) ((x - left) / scale)), Math.min(719, (int) ((y - top) / scale))};
    }
}
