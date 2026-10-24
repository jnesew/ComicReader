package io.github.jnesew.comicviewer.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageButton;

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

    public static ImageButton iconButton(Context context, int icon, String description) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(TEXT));
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setFocusable(true);
        button.setClickable(true);
        button.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        button.setBackground(rounded(Color.TRANSPARENT, dp(context, 24), 0, 0));
        button.setLayoutParams(new ViewGroup.LayoutParams(dp(context, 52), dp(context, 52)));
        return button;
    }

    public static Drawable icon(Context context, int resource, int sizeDp, int color) {
        Drawable drawable = context.getDrawable(resource).mutate();
        drawable.setTint(color);
        drawable.setBounds(0, 0, dp(context, sizeDp), dp(context, sizeDp));
        return drawable;
    }

    public static TextView actionButton(Context context, int label, int icon) {
        TextView button = text(context, context.getString(label), 15, ACCENT);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        button.setMinimumHeight(dp(context, 48));
        button.setCompoundDrawablesRelative(icon(context, icon, 22, ACCENT), null, null, null);
        button.setCompoundDrawablePadding(dp(context, 10));
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(rounded(SURFACE, dp(context, 12), SURFACE_HIGH, dp(context, 1)));
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
