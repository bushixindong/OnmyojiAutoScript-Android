package com.oas.android.vision;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OAS income parsing: unknown stays unknown, including a decimal with a missing unit. */
public final class ResourceNumber {
    private static final Pattern VALUE = Pattern.compile("(\\d+(?:\\.\\d+)?)([千万亿]?)");
    private ResourceNumber() { }
    public static Long parse(String text, long maximum) {
        if (text == null) return null;
        String value = text.replaceAll("[\\s,，]", "").replace('I','1').replace('D','0')
            .replace('S','5').replace('B','8').replace('？','2').replace('d','6').replace('o','0').replace('O','0');
        value = value.split("[/／]", -1)[0];
        Matcher match = VALUE.matcher(value);
        if (!match.matches()) return null;
        String unit = match.group(2);
        if (unit.isEmpty() && match.group(1).contains(".")) return null;
        long scale = unit.equals("千") ? 1000 : unit.equals("万") ? 10000 : unit.equals("亿") ? 100000000 : 1;
        try {
            long result = new BigDecimal(match.group(1)).multiply(BigDecimal.valueOf(scale)).longValueExact();
            return result >= 0 && result <= maximum ? result : null;
        } catch (ArithmeticException e) { return null; }
    }
}
