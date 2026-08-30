package io.github.jnesew.comicviewer.model;

public final class OpeningZoomPolicy {
    public static final String APP_DEFAULT = "";
    public static final String FIT_WIDTH = "fit_width";
    public static final String FIT_PAGE = "fit_page";

    private static final String MANUAL = "manual";
    private static final String CONTINUOUS = "continuous";

    private OpeningZoomPolicy() {
    }

    public static String normalizeGlobalDefault(String value) {
        if (FIT_WIDTH.equals(value) || FIT_PAGE.equals(value)) return value;
        return APP_DEFAULT;
    }

    public static OpeningZoom resolve(
            boolean rememberPerTitle,
            boolean hasRememberedPerTitle,
            String perTitleMode,
            float perTitleZoom,
            String globalDefault) {
        if (rememberPerTitle && hasRememberedPerTitle &&
                isRememberedMode(perTitleMode) &&
                Float.isFinite(perTitleZoom) && perTitleZoom > 0f) {
            return new OpeningZoom(perTitleMode, perTitleZoom);
        }

        String normalizedDefault = normalizeGlobalDefault(globalDefault);
        String mode = APP_DEFAULT.equals(normalizedDefault) ? FIT_WIDTH : normalizedDefault;
        return new OpeningZoom(mode, 1f);
    }

    private static boolean isRememberedMode(String mode) {
        return FIT_WIDTH.equals(mode) || FIT_PAGE.equals(mode) ||
                MANUAL.equals(mode) || CONTINUOUS.equals(mode);
    }

    public static final class OpeningZoom {
        private final String mode;
        private final float zoom;

        private OpeningZoom(String mode, float zoom) {
            this.mode = mode;
            this.zoom = zoom;
        }

        public String mode() {
            return mode;
        }

        public float zoom() {
            return zoom;
        }
    }
}
