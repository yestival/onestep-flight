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

        assertTrue(html.contains("class=\"manual-control date-control\""));
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
    void manualFlightAndDateControlsShareTheSameVisualContract() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");

        assertTrue(html.contains("class=\"manual-control\" id=\"flight-input\""));
        assertTrue(html.contains("class=\"manual-control date-control\""));
        assertTrue(css.contains("--manual-control-height:60px"));
        assertTrue(css.contains(".manual-control{display:flex;align-items:center;justify-content:center"));
        assertTrue(css.contains("height:var(--manual-control-height);min-height:var(--manual-control-height)"));
        assertTrue(css.contains("border:var(--manual-control-border)"));
        assertTrue(css.contains("padding:var(--manual-control-padding)"));
        assertTrue(css.contains("font:var(--manual-control-font)"));
        assertTrue(css.contains("input.manual-control:focus-visible,.date-control:focus-within"));
        assertTrue(css.contains("input.manual-control:user-invalid,.date-control:has(input:user-invalid)"));
        assertTrue(css.contains("input.manual-control:disabled,.date-control:has(input:disabled)"));
        assertTrue(css.contains(".date-control input[type=\"date\"]{position:absolute;inset:0"));
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

    @Test
    void unusableAndPartialDocumentsCannotEnterBlankConfirmationOrResolveAutomatically() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");

        assertTrue(js.contains("function classifyDocumentResult(result)"));
        assertTrue(js.contains("if (!segments.length) return { outcome: 'UNUSABLE'"));
        assertTrue(js.contains("assessment.outcome === 'UNUSABLE'"));
        assertTrue(js.contains("assessment.outcome === 'PARTIAL'"));
        assertTrue(js.indexOf("if (assessment.outcome === 'UNUSABLE')")
                < js.indexOf("beginFlight(extractedFlight, selectedResult)"));
        assertTrue(js.contains("showPartialDocument(assessment.partialSegment)"));
        assertTrue(html.contains("data-screen=\"document-unusable\""));
        assertTrue(html.contains("data-action=\"choose-other-file\""));
        assertTrue(html.contains("data-manual-partial-notice"));
        assertFalse(html.substring(html.indexOf("data-screen=\"document-unusable\""),
                html.indexOf("data-screen=\"confirm\"")).contains("Nutrient"));
    }

    @Test
    void documentFailureCopyAndCacheVersionAreComplete() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");
        String worker = resource("service-worker.js");

        assertTrue(html.contains("没有读到航班信息"));
        assertTrue(js.contains("No flight information was found"));
        assertTrue(js.contains("No se encontró información del vuelo"));
        assertTrue(js.contains("Only some information was found"));
        assertTrue(js.contains("Solo se encontró parte de la información"));
        assertTrue(html.contains("styles.css?v=18"));
        assertTrue(html.contains("app.js?v=18"));
        assertTrue(worker.contains("CACHE_PREFIX + 'v18'"));
    }

    @Test
    void staticFrontendUsesTheApprovedSemanticColorSystem() throws IOException {
        String index = resource("index.html");
        String styles = resource("styles.css");
        String app = resource("app.js");
        String shareHtml = resource("share.html");
        String shareStyles = resource("share.css");
        String shareJs = resource("share.js");
        String combined = String.join("\n", index, styles, app, shareHtml, shareStyles, shareJs);

        for (String variable : new String[] { "--color-bg", "--color-surface", "--color-surface-muted",
                "--color-surface-emphasis", "--color-text", "--color-text-secondary", "--color-text-muted",
                "--color-primary", "--color-primary-hover", "--color-border", "--color-border-strong",
                "--color-panel-dark", "--color-panel-deep", "--color-on-dark", "--color-success",
                "--color-danger", "--color-focus", "--color-shadow" }) {
            assertTrue(styles.contains(variable + ":"), variable + " must be defined in styles.css");
            assertTrue(shareStyles.contains(variable + ": "), variable + " must be defined in share.css");
        }

        assertFalse(combined.matches("(?is).*#(?:fff(?:fff)?|000(?:000)?)(?![0-9a-f]).*"));
        assertTrue(app.contains("context.fillStyle = '#F3F4F7'"));
        assertTrue(app.contains("context.fillStyle = '#1B2537'"));
        assertTrue(index.contains("name=\"theme-color\" content=\"#1B2537\""));
        assertTrue(shareHtml.contains("name=\"theme-color\" content=\"#1B2537\""));
    }

    @Test
    void documentFailureLayoutRetainsMobileContainmentAndReflow() throws IOException {
        String css = resource("styles.css");

        assertTrue(css.contains(".document-unusable-actions{max-width:520px}"));
        assertTrue(css.contains(".document-unusable-actions .button{width:100%;max-width:100%}"));
        assertTrue(css.contains(".screen,.screen>*"));
        assertTrue(css.contains("min-width:0;max-width:100%"));
        assertTrue(css.contains("@media(max-width:420px)"));
        assertTrue(css.contains("@media(max-width:340px)"));
    }
}
