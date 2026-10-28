package io.github.jnesew.comicviewer.data;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.render.BufferingPolicy;
import io.github.jnesew.comicviewer.util.Ui;

/** Appearance preference behavior, platform palettes, and independence from reader backgrounds. */
final class AppearanceTests {
    static void preferencesAndPalettes(Instrumentation instrumentation) {
        Context context = instrumentation.getTargetContext();
        String file = "appearance-instrumentation";
        context.deleteSharedPreferences(file);
        try {
            ReaderPreferences preferences = new ReaderPreferences(context, file, BufferingPolicy.STANDARD);
            equal("system", preferences.appearance());
            preferences.setCanvasTheme("paper");
            int canvas = preferences.canvasColor();
            for (String mode : new String[]{"light", "dark", "system"}) {
                preferences.setAppearance(mode);
                ReaderPreferences restored = new ReaderPreferences(context, file, BufferingPolicy.STANDARD);
                equal(mode, restored.appearance());
                equal(canvas, restored.canvasColor());
                for (boolean night : new boolean[]{false, true}) {
                    Configuration configuration = new Configuration(context.getResources().getConfiguration());
                    configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                            | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
                    Context system = context.createConfigurationContext(configuration);
                    equal("light".equals(mode) || ("system".equals(mode) && !night),
                            Ui.lightAppearance(system, restored.appearance()));
                }
            }
            preferences.setAppearance("unknown");
            equal("system", preferences.appearance());
            Context dark = new ContextThemeWrapper(context, R.style.Theme_ComicViewer);
            Context light = new ContextThemeWrapper(context, R.style.Theme_ComicViewer_Light);
            check(Ui.background(dark) != Ui.background(light));
            for (Context themed : new Context[]{dark, light}) {
                for (int background : new int[]{Ui.background(themed), Ui.surface(themed), Ui.surfaceHigh(themed)}) {
                    check(contrast(Ui.text(themed), background) >= 4.5);
                    check(contrast(Ui.textMuted(themed), background) >= 4.5);
                    check(contrast(Ui.accent(themed), background) >= 4.5);
                }
            }
        } finally { context.deleteSharedPreferences(file); }
    }

    private static double contrast(int foreground, int background) {
        double first = luminance(foreground), second = luminance(background);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
    }

    private static double luminance(int color) {
        double[] channels = new double[]{android.graphics.Color.red(color) / 255d,
                android.graphics.Color.green(color) / 255d, android.graphics.Color.blue(color) / 255d};
        for (int index = 0; index < channels.length; index++) {
            double value = channels[index];
            channels[index] = value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
        }
        return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
    }

    private static void check(boolean value) { if (!value) throw new AssertionError("Appearance or contrast mismatch"); }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
