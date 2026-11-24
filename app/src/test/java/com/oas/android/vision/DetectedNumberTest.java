package com.oas.android.vision;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DetectedNumberTest {
    @Test public void ignoresSeparateIconsAndCapacityTail(){
        assertEquals(Long.valueOf(421),DetectedNumber.parse(Arrays.asList("图标","421"),Arrays.asList(.95,.99),10000));
        assertEquals(Long.valueOf(633),DetectedNumber.parse(Arrays.asList("633","/180"),Arrays.asList(.99,.99),10000));
    }
    @Test public void rejectsMultipleValuesLowConfidenceAndMissingUnit(){
        assertNull(DetectedNumber.parse(Arrays.asList("42","10"),Arrays.asList(.99,.99),10000));
        assertNull(DetectedNumber.parse(Arrays.asList("42"),Arrays.asList(.7),10000));
        assertNull(DetectedNumber.parse(Arrays.asList("1.2方"),Arrays.asList(.99),10000));
    }
}
