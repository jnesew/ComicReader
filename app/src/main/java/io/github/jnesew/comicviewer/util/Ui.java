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
import io.github.jnesew.comicviewer.R;

public final class Ui {
    private Ui() {}

    public static int background(Context context) { return color(context, R.attr.comicBackground, 0xFF0B0D10); }
    public static int surface(Context context) { return color(context, R.attr.comicSurface, 0xFF191D24); }
    public static int surfaceHigh(Context context) { return color(context, R.attr.comicSurfaceHigh, 0xFF242932); }
    public static int text(Context context) { return color(context, R.attr.comicText, 0xFFF4F7FF); }
    public static int textMuted(Context context) { return color(context, R.attr.comicTextMuted, 0xFFB1B9C7); }
    public static int accent(Context context) { return color(context, android.R.attr.colorAccent, 0xFF9BBEFF); }

    public static boolean lightAppearance(Context context, String mode) {
        if ("light".equals(mode)) return true;
        if ("dark".equals(mode)) return false;
        return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static int color(Context context, int attribute, int fallback) {
        TypedValue value = new TypedValue();
        return context.getTheme().resolveAttribute(attribute, value, true) ? value.data : fallback;
    }

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
        TextView button = text(context, glyph, 24, text(context));
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
        button.setImageTintList(ColorStateList.valueOf(text(context)));
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
        TextView button = text(context, context.getString(label), 15, accent(context));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        button.setMinimumHeight(dp(context, 48));
        button.setCompoundDrawablesRelative(icon(context, icon, 22, accent(context)), null, null, null);
        button.setCompoundDrawablePadding(dp(context, 10));
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(rounded(surface(context), dp(context, 12), surfaceHigh(context), dp(context, 1)));
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
