package io.github.jnesew.comicviewer.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class OpeningZoomPolicyTest {
    @Test
    public void rememberedPerTitleZoomWinsOverGlobalDefault() {
        OpeningZoomPolicy.OpeningZoom result = OpeningZoomPolicy.resolve(
                true, true, "manual", 2.25f, OpeningZoomPolicy.FIT_PAGE);

        assertEquals("manual", result.mode());
        assertEquals(2.25f, result.zoom(), 0.0001f);
    }

    @Test
    public void globalDefaultIsUsedWithoutRememberedPerTitleZoom() {
        OpeningZoomPolicy.OpeningZoom result = OpeningZoomPolicy.resolve(
                true, false, OpeningZoomPolicy.FIT_WIDTH, 1f, OpeningZoomPolicy.FIT_PAGE);

        assertEquals(OpeningZoomPolicy.FIT_PAGE, result.mode());
        assertEquals(1f, result.zoom(), 0.0001f);
    }

    @Test
    public void globalDefaultWinsWhenPerTitleMemoryIsDisabled() {
        OpeningZoomPolicy.OpeningZoom result = OpeningZoomPolicy.resolve(
                false, true, "manual", 3f, OpeningZoomPolicy.FIT_PAGE);

        assertEquals(OpeningZoomPolicy.FIT_PAGE, result.mode());
        assertEquals(1f, result.zoom(), 0.0001f);
    }

    @Test
    public void builtInFitWidthIsUsedWhenNeitherSettingExists() {
        OpeningZoomPolicy.OpeningZoom result = OpeningZoomPolicy.resolve(
                true, false, "", 1f, OpeningZoomPolicy.APP_DEFAULT);

        assertEquals(OpeningZoomPolicy.FIT_WIDTH, result.mode());
        assertEquals(1f, result.zoom(), 0.0001f);
    }

    @Test
    public void invalidStoredValuesFallBackSafely() {
        OpeningZoomPolicy.OpeningZoom result = OpeningZoomPolicy.resolve(
                true, true, "unexpected", Float.NaN, "unexpected");

        assertEquals(OpeningZoomPolicy.FIT_WIDTH, result.mode());
        assertEquals(OpeningZoomPolicy.APP_DEFAULT,
                OpeningZoomPolicy.normalizeGlobalDefault("unexpected"));
    }

    @Test
    public void rememberedContinuousZoomIsPreserved() {
        OpeningZoomPolicy.OpeningZoom result = OpeningZoomPolicy.resolve(
                true, true, "continuous", 1.6f, OpeningZoomPolicy.FIT_PAGE);

        assertEquals("continuous", result.mode());
        assertEquals(1.6f, result.zoom(), 0.0001f);
    }
}
