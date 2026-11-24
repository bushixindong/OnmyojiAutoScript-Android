package com.oas.android.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/** Applies the OASX palette/tokens to the original, validated schema editor. */
public final class NativeFormStyle {
    private NativeFormStyle(){}
    public static boolean isDark(android.content.Context context){android.util.TypedValue value=new android.util.TypedValue();context.getTheme().resolveAttribute(android.R.attr.colorBackground,value,true);return Color.luminance(value.data)<.5f;}
    public static void apply(View view,boolean dark){
        int ink=Color.parseColor(dark?"#E6E1E5":"#1C1B1F"),muted=Color.parseColor(dark?"#CAC4D0":"#79747E"),
            accent=Color.parseColor(dark?"#D0BCFF":"#6750A4"),surface=Color.parseColor(dark?"#49454F":"#F3EDF7"),outline=Color.parseColor(dark?"#49454F":"#CAC4D0");
        float density=view.getResources().getDisplayMetrics().density;int pad=Math.round(12*density);
        if(view instanceof EditText){
            EditText input=(EditText)view;input.setTextSize(15);input.setTextColor(ink);input.setHintTextColor(muted);input.setMinHeight(Math.round(48*density));
            GradientDrawable background=new GradientDrawable();background.setColor(Color.TRANSPARENT);background.setCornerRadius(8*density);background.setStroke(Math.max(1,Math.round(density)),outline);input.setBackground(background);input.setPadding(pad,pad,pad,pad);
        }else if(view instanceof Button && !(view instanceof CompoundButton)){
            Button button=(Button)view;button.setAllCaps(false);button.setTextSize(14);button.setTextColor(accent);button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);button.setElevation(0);button.setMinHeight(Math.round(48*density));
            GradientDrawable background=new GradientDrawable();background.setColor(surface);background.setCornerRadius(10*density);button.setBackground(background);button.setPadding(pad,pad/2,pad,pad/2);
        }else if(view instanceof CompoundButton){
            CompoundButton option=(CompoundButton)view;option.setTextColor(ink);option.setTextSize(14);option.setButtonTintList(ColorStateList.valueOf(accent));option.setMinHeight(Math.round(48*density));
        }else if(view instanceof TextView){TextView text=(TextView)view;text.setTextColor(text.getTextSize()/view.getResources().getDisplayMetrics().scaledDensity<=12?muted:ink);}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)apply(group.getChildAt(i),dark);}
        if(view.getLayoutParams() instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)view.getLayoutParams();params.bottomMargin=Math.round(8*density);view.setLayoutParams(params);}
    }
}
