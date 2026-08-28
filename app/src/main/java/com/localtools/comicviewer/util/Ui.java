package com.localtools.comicviewer.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public final class Ui {
    public static final int HOME_BACKGROUND = Color.rgb(11, 13, 16);
    public static final int SURFACE = Color.rgb(25, 29, 36);
    public static final int SURFACE_HIGH = Color.rgb(36, 41, 50);
    public static final int TEXT = Color.rgb(244, 247, 255);
    public static final int TEXT_MUTED = Color.rgb(177, 185, 199);
    public static final int ACCENT = Color.rgb(155, 190, 255);

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context context, String value, float sp, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    public static TextView iconButton(Context context, String glyph, String description) {
        TextView button = text(context, glyph, 24, TEXT);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(rounded(Color.TRANSPARENT, dp(context, 24), 0, 0));
        button.setLayoutParams(new ViewGroup.LayoutParams(dp(context, 52), dp(context, 52)));
        return button;
    }

    public static GradientDrawable rounded(int color, float radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    public static void setPadding(View view, int horizontalDp, int verticalDp) {
        int horizontal = dp(view.getContext(), horizontalDp);
        int vertical = dp(view.getContext(), verticalDp);
        view.setPadding(horizontal, vertical, horizontal, vertical);
    }

    public static void bold(TextView text) {
        text.setTypeface(text.getTypeface(), Typeface.BOLD);
    }
}
