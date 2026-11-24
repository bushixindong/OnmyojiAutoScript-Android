package com.oas.android.vision;

import java.util.List;

/** Rejects multiple numeric regions instead of joining them into an invented larger value. */
public final class DetectedNumber {
    private DetectedNumber(){}
    public static Long parse(List<String> text,List<Double> confidence,long maximum){
        if(text.size()!=confidence.size())throw new IllegalArgumentException("OCR results differ in length");
        Long value=null;
        for(int i=0;i<text.size();i++){
            String token=text.get(i).trim();
            if(!token.matches(".*[0-9IDSBOod？].*"))continue;
            if(token.startsWith("/")||token.startsWith("／"))continue;
            Long candidate=ResourceNumber.parse(token,maximum);
            if(candidate==null||confidence.get(i)<.85||value!=null)return null;
            value=candidate;
        }
        return value;
    }
}
