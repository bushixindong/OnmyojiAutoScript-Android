package com.oas.android.vision;
import org.junit.Test;
import static org.junit.Assert.*;
public class PreviewCoordinatesTest {
    @Test public void mapsScaledCenterAndCorners() {
        assertArrayEquals(new int[]{640, 360}, PreviewCoordinates.map(640, 360, 320, 180));
        assertArrayEquals(new int[]{0, 0}, PreviewCoordinates.map(640, 360, 0, 0));
        assertArrayEquals(new int[]{1278, 718}, PreviewCoordinates.map(640, 360, 639, 359));
    }
    @Test public void rejectsLetterboxAndOutsideEdges() {
        assertNull(PreviewCoordinates.map(640, 640, 320, 50));
        assertNull(PreviewCoordinates.map(640, 640, 320, 600));
        assertNull(PreviewCoordinates.map(640, 360, 640, 180));
        assertArrayEquals(new int[]{640, 360}, PreviewCoordinates.map(640, 640, 320, 320));
        assertNull(PreviewCoordinates.map(0, 360, 0, 0));
    }
}
