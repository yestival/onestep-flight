package com.selene.onestepflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class StaticFrontendRegressionTest {

    private String resource(String name) throws IOException {
        return new ClassPathResource("static/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void nativeDateInputIsAbsoluteAndVisibleDateTracksValueAndLanguage() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");
        String js = resource("app.js");

        assertTrue(html.contains("class=\"date-control\""));
        assertTrue(html.contains("data-date-display"));
        assertTrue(css.contains(".date-control input[type=\"date\"]{position:absolute;inset:0"));
        assertTrue(css.contains("overflow:hidden"));
        assertTrue(css.contains(".date-control:focus-within"));
        assertTrue(js.contains("input.addEventListener('input', syncVisibleDates)"));
        assertTrue(js.contains("input.addEventListener('change', syncVisibleDates)"));
        assertTrue(js.contains("'zh-CN'"));
        assertTrue(js.contains("'en-GB'"));
        assertTrue(js.contains("'es-ES'"));
    }

    @Test
    void pngEncodingSupportsToBlobAndDataUrlFallback() throws IOException {
        String js = resource("app.js");

        assertTrue(js.contains("canvas.toBlob(function (blob)"));
        assertTrue(js.contains("if (blob)"));
        assertTrue(js.contains("canvas.toDataURL('image/png')"));
        assertTrue(js.contains("toBlob returned null"));
        assertTrue(js.contains("toBlob timeout"));
        assertFalse(js.contains("roundRect("));
        assertFalse(js.contains("OffscreenCanvas"));
        assertFalse(js.contains("convertToBlob"));
    }

    @Test
    void pngShareUsesPreparedFileAndHandlesFallbackAndCancellation() throws IOException {
        String js = resource("app.js");

        assertTrue(js.contains("prepareFamilyCard().catch(function () {})"));
        assertTrue(js.contains("navigator.canShare({ files: [pngCardCache.file] })"));
        assertTrue(js.contains("navigator.share({ files: [pngCardCache.file]"));
        assertTrue(js.contains("if (error && error.name === 'AbortError') return"));
        assertTrue(js.contains("saveCachedPngFallback()"));
        assertTrue(js.contains("showCachedPngPreview(true)"));
        assertTrue(js.contains("link.download = pngCardCache.file.name"));
    }

    @Test
    void pngDoesNotRequireShareSessionAndReportsStageCodes() throws IOException {
        String js = resource("app.js");

        assertTrue(js.contains("function cardPayload()"));
        assertTrue(js.contains("if (!currentFlight) return null"));
        assertTrue(js.contains("PNG_RENDER_FAILED"));
        assertTrue(js.contains("PNG_TO_BLOB_FAILED"));
        assertTrue(js.contains("PNG_FILE_FAILED"));
        assertTrue(js.contains("PNG_SHARE_FAILED"));
        assertTrue(js.contains("console.error(code"));
        assertTrue(js.contains("卡片图片编码失败，请重试。"));
    }
}
