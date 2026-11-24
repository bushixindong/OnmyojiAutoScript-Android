package com.oas.android.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResourceNumberTest {
    @Test public void acceptsUnitsSeparatorsAndCapacity() {
        assertEquals(Long.valueOf(12000), ResourceNumber.parse("1.2万", 100000));
        assertEquals(Long.valueOf(633000000), ResourceNumber.parse("6.33亿", 1000000000));
        assertEquals(Long.valueOf(12345), ResourceNumber.parse("12，345", 100000));
        assertEquals(Long.valueOf(5000), ResourceNumber.parse("5000/5000", 10000));
        assertEquals(Long.valueOf(1058), ResourceNumber.parse("I OSB", 10000));
        assertEquals(Long.valueOf(0), ResourceNumber.parse("0", 10000));
    }
    @Test public void rejectsMissingUnitAmbiguityOverflowAndGarbage() {
        for (String text : new String[]{"1.2", "1.2方", "1..2万", "100金20", "-100", "1e6", "", "金币100", "100亿"})
            assertNull(text, ResourceNumber.parse(text, 100000));
        assertNull(ResourceNumber.parse(null, 100000));
    }
}
