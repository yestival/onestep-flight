package com.selene.onestepflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(html.contains("styles.css?v=24"));
        assertTrue(html.contains("locale.js?v=24"));
        assertTrue(html.contains("app.js?v=24"));
        assertTrue(worker.contains("CACHE_PREFIX + 'v24'"));
        assertTrue(worker.contains("'/locale.js'"));
    }

    @Test
    void bothPagesUseSharedLocaleResolutionAndEnglishNoScriptDefaults() throws IOException {
        String index = resource("index.html");
        String app = resource("app.js");
        String shareHtml = resource("share.html");
        String shareJs = resource("share.js");
        String locale = resource("locale.js");

        assertTrue(index.contains("<html lang=\"en\">"));
        assertTrue(shareHtml.contains("<html lang=\"en\">"));
        assertTrue(index.indexOf("<option value=\"en\">English</option>")
                < index.indexOf("<option value=\"zh\">中文</option>"));
        assertTrue(shareHtml.indexOf("<option value=\"en\">English</option>")
                < shareHtml.indexOf("<option value=\"zh\">中文</option>"));
        assertTrue(index.contains("aria-label=\"Choose language\""));
        assertTrue(shareHtml.contains("aria-label=\"Choose language\""));
        assertTrue(index.contains("Now, take the next step"));
        assertTrue(shareHtml.contains("Loading the shared journey"));
        assertTrue(index.contains("locale.js?v=24"));
        assertTrue(shareHtml.contains("/share.css?v=24"));
        assertTrue(shareHtml.contains("/locale.js?v=24"));
        assertTrue(shareHtml.contains("/share.js?v=24"));
        assertFalse(index.contains("?v=23"));
        assertFalse(shareHtml.contains("?v=23"));
        assertTrue(app.contains("window.OneStepLocale.resolveLocalePreference(stored, navigator.languages, navigator.language)"));
        assertTrue(shareJs.contains("window.OneStepLocale.resolveLocalePreference(stored, navigator.languages, navigator.language)"));
        assertTrue(app.contains("window.OneStepLocale.orderLocaleOptions(localeSelect, currentLocale)"));
        assertTrue(shareJs.contains("window.OneStepLocale.orderLocaleOptions(select, currentLocale)"));
        assertTrue(locale.contains("var STABLE_OPTION_ORDER = ['en', 'zh', 'es']"));
        assertTrue(locale.contains("return localeFromLanguageTag(language) || 'en'"));
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

    @Test
    void compactActionGridsRetainMobileColumnsAndLargeSemanticIcons() throws IOException {
        String css = resource("styles.css");

        assertTrue(css.contains("--icon-yes:#188A50"));
        assertTrue(css.contains("--icon-no:#C83F4D"));
        assertTrue(css.contains("--icon-unsure:#B98200"));
        assertTrue(css.contains("--icon-yes-on-dark:#72E3A5"));
        assertTrue(css.contains("--icon-no-on-dark:#FF8F99"));
        assertTrue(css.contains("--icon-unsure-on-dark:#FFD166"));
        assertTrue(css.contains(".answer-choice-grid{grid-template-columns:repeat(3,minmax(44px,1fr))}"));
        assertTrue(css.contains(".two-choice-grid,.confirm-assistance-actions,.short-action-grid,.screen-confirm .confirm-choice-actions{display:grid;grid-template-columns:repeat(2,minmax(44px,1fr))}"));
        assertTrue(css.contains(".step-assistance-actions{grid-template-columns:repeat(3,minmax(44px,1fr))}"));
        assertTrue(css.contains(".action-full-row,.primary-action-row,.danger-action-row{grid-column:1/-1}"));
        assertTrue(css.contains("--action-control-font-size:clamp(20px,3vw,24px)"));
        assertTrue(css.contains("--action-control-icon-size:clamp(44px,18vw,52px)"));
        assertTrue(css.contains(".button,.home-back-button,.back-button,.card-close,.text-button,.support-button,.scenario-actions button,.journey-end-action .end-journey-link,.family-actions .button,.family-actions .text-button,.journey-secondary-actions .button,.journey-primary-action .journey-primary{min-width:44px;min-height:64px;padding-block:6px;font-size:var(--action-control-font-size);font-weight:800;line-height:1.05}"));
        assertTrue(css.contains(".button-icon,.button-icon.outline,.icon-button svg,.support-button svg,.journey-end-action .end-journey-link svg{width:var(--action-control-icon-size);height:var(--action-control-icon-size)"));
        assertTrue(css.contains(".button-primary .icon-yes{color:var(--icon-yes-on-dark)}"));
        assertTrue(css.contains(".button-primary .icon-no{color:var(--icon-no-on-dark)}"));
        assertTrue(css.contains(".button-primary .icon-unsure{color:var(--icon-unsure-on-dark)}"));
        assertTrue(css.contains(".staff-help-button .staff-icon-person{color:var(--staff-person-on-dark);stroke:var(--staff-person-on-dark)}"));
        assertTrue(css.contains(".staff-help-button .staff-icon-question{color:var(--icon-unsure-on-dark);stroke:var(--icon-unsure-on-dark)}"));
        assertTrue(css.contains("stroke-width:2.9"));
        assertTrue(css.contains(":root{--action-control-font-size:clamp(21px,6vw,27px)}"));
        assertTrue(css.contains(":root{--action-control-font-size:18px;--action-control-icon-size:36px;--action-control-text-icon-size:28px}"));
        assertTrue(css.contains(".dialog-speech-button{width:100%;min-height:64px"));
        assertFalse(css.contains(".step-assistance-actions,.journey-secondary-actions,.in-flight-details{grid-template-columns:1fr}"));
        assertFalse(css.matches("(?s).*\\.step-assistance-actions[^{}]*:nth-child.*"));
    }

    @Test
    void questionAndAssistanceMarkupUsesExplicitGridsAndInlineSvgSymbols() throws IOException {
        String html = resource("index.html");
        String welcome = html.substring(html.indexOf("data-screen=\"welcome\""), html.indexOf("data-screen=\"upload\""));

        assertFalse(welcome.contains("data-action=\"family\""));
        assertTrue(html.contains("class=\"step-actions answer-choice-grid\" data-checkin-choices"));
        assertTrue(html.contains("class=\"choice-actions confirm-choice-actions two-choice-grid\""));
        assertTrue(html.contains("class=\"step-actions two-choice-grid\""));
        assertTrue(html.contains("class=\"confirm-assistance-actions\""));
        assertTrue(html.contains("class=\"choice-actions complete-actions short-action-grid\""));
        assertTrue(html.contains("class=\"choice-icon icon-yes\""));
        assertTrue(html.contains("class=\"choice-icon icon-no\""));
        assertTrue(html.contains("class=\"choice-icon icon-unsure\""));
        assertTrue(html.contains("class=\"icon-yes\" viewBox=\"2.5 3 19 16.5\""));
        assertTrue(html.contains("class=\"choice-icon icon-no\" viewBox=\"3.5 3.5 17 17\""));
        assertTrue(html.contains("class=\"choice-icon icon-unsure\" viewBox=\"6.5 3.5 11 16.5\""));
        assertTrue(html.contains("viewBox=\"5.5 2.5 16 19\""));
        assertTrue(html.contains("class=\"speech-icon\" viewBox=\"2 4 20 16\""));
        assertTrue(html.contains("data-action=\"confirm-board\" data-i18n=\"confirmBoard\""));
        assertTrue(html.contains("data-action=\"cancel-board\" data-i18n=\"cancelBoard\""));
        assertTrue(html.contains("class=\"staff-icon-person\""));
        assertTrue(html.contains("class=\"staff-icon-question\""));
        assertTrue(html.contains("data-i18n-aria=\"checkInYesAria\""));
        assertTrue(html.contains("data-i18n-aria=\"baggageUnsureAria\""));
    }

    @Test
    void helpQuestionAndFamilyShareIconsHaveSafeSemanticGeometry() throws IOException {
        String html = resource("index.html");

        assertTrue(html.contains("class=\"staff-help-icon\" viewBox=\"1 0 24 24\""));
        assertTrue(html.contains("class=\"staff-icon-question\" d=\"M17 8a3 3 0 1 1 2.2 2.9c-.8.3-1.2.8-1.2 1.6M18 16h.01\""));
        assertEquals(4, html.split("class=\"family-share-icon\"", -1).length - 1);
        assertEquals(4, html.split("viewBox=\"1 0 22 24\" fill=\"none\" stroke=\"currentColor\"", -1).length - 1);
        assertEquals(12, html.split("class=\"family-share-node\"", -1).length - 1);
        assertEquals(8, html.split("class=\"family-share-link\"", -1).length - 1);
        assertFalse(html.contains("M7.3 4.5 9.7 8 8 9.7a15 15 0 006.3 6.3"));
        assertFalse(html.contains("M15 4a3 3 0 110 6m-2.5 2.2a5.5 5.5 0 016.5.8"));
    }

    @Test
    void buttonSvgMarkupReservesPaintedStrokeSafetyMargins() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");

        assertEquals(6, html.split("class=\"speech-icon\" viewBox=\"2 4 20 16\"", -1).length - 1);
        assertEquals(5, html.split("viewBox=\"2.5 3 19 16.5\"", -1).length - 1);
        assertTrue(html.contains("class=\"end-journey-icon\" viewBox=\"1.5 1.5 21 21\""));
        assertTrue(html.contains("<circle cx=\"12\" cy=\"12\" r=\"8.6\"/>"));
        assertTrue(css.contains(".support-button.assistance-button .speech-icon,.support-button.dialog-speech-button .speech-icon{width:calc(var(--action-control-icon-size) + 4px);height:var(--action-control-icon-size)}"));
        assertFalse(html.contains("viewBox=\"2.5 4.5 17 15\""));
        assertFalse(html.contains("viewBox=\"3 3 18 16.5\""));
        assertFalse(html.contains("class=\"end-journey-icon\" viewBox=\"1.5 1.5 21 21\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"12\" cy=\"12\" r=\"9\""));
    }

    @Test
    void extremeZoomRulesPreserveContainmentAndNativeDateControl() throws IOException {
        String css = resource("styles.css");

        assertTrue(css.contains("@media(max-width:220px)"));
        assertTrue(css.contains(".screen,.site-header,.site-footer{width:calc(100% - 12px)}"));
        assertTrue(css.contains(".brand{flex:0 0 44px;min-width:44px;justify-content:center}"));
        assertTrue(css.contains(".language-select{width:96px;min-width:96px"));
        assertTrue(css.contains(".answer-choice-grid,.step-assistance-actions{gap:4px}"));
        assertTrue(css.contains(".two-choice-grid .answer-choice,.screen-confirm .confirm-choice-actions .answer-choice{flex-direction:column"));
        assertTrue(css.contains(".date-control-value{padding-inline:18px}"));
        assertTrue(css.contains("white-space:nowrap;overflow:hidden;text-overflow:ellipsis"));
        assertTrue(css.contains(".screen-confirm .route-summary{grid-template-columns:minmax(0,1fr) 14px minmax(0,1fr);gap:4px}"));
        assertTrue(css.contains(".screen-confirm .airport-code{font-size:20px;letter-spacing:0}"));
        assertTrue(css.contains(".screen-confirm .flight-details-core{grid-template-columns:minmax(0,1fr)}"));
        assertTrue(css.contains(".screen h1,.screen h2,.section-heading>*,.confirm-card h1,.confirm-card p{min-width:0;max-width:100%;overflow-wrap:anywhere}"));
        assertTrue(css.contains("height:auto;min-height:64px;overflow:visible;flex-direction:column"));
        assertTrue(css.contains("width:var(--action-control-icon-size);height:var(--action-control-icon-size);min-height:var(--action-control-icon-size)"));
        assertTrue(css.contains("position:static;background:transparent;padding:0;font-size:var(--action-control-font-size)"));
        assertTrue(css.contains("button,.button,.support-button,.text-button,input,select{box-sizing:border-box;min-width:0;max-width:100%"));
    }

    @Test
    void automaticAndManualSpeechShareOneBuilderAndDeduplicateViewEntries() throws IOException {
        String js = resource("app.js");
        String html = resource("index.html");
        String builder = js.substring(js.indexOf("function buildSpeechTextForCurrentView()"),
                js.indexOf("function updateSpeechButtons()"));

        assertTrue(js.contains("function buildSpeechTextForCurrentView()"));
        assertTrue(js.contains("var text = buildSpeechTextForCurrentView();"));
        assertTrue(js.contains("if (speechViewKey() === key) startSpeech(true);"));
        assertTrue(js.contains("startSpeech(false);"));
        assertTrue(js.contains("if (key === lastAutomaticSpeechKey) return;"));
        assertTrue(js.contains("lastAutomaticSpeechKey = key;"));
        assertTrue(js.contains("document.querySelectorAll('[data-action=\"speak\"]').forEach"));
        assertTrue(js.contains("languageChangeInProgress = true;"));
        assertTrue(builder.contains("currentScreen === 'confirm'"));
        assertTrue(builder.contains("currentScreen === 'step'"));
        assertTrue(builder.contains("currentScreen === 'complete'"));
        assertTrue(builder.contains("[data-board-overlay]"));
        assertTrue(builder.contains("[data-landed-overlay]"));
        assertFalse(builder.contains("currentScreen === 'welcome'"));
        assertFalse(builder.contains("currentScreen === 'upload'"));
        assertFalse(builder.contains("currentScreen === 'manual'"));
        assertFalse(builder.contains("currentScreen === 'loading'"));
        assertTrue(html.split("data-action=\"speak\"", -1).length - 1 >= 6);
        assertTrue(html.split("data-action=\"speak\"", -1).length
                == html.split("aria-pressed=\"false\"", -1).length);
    }

    @Test
    void earlyShareStatesAreGuardedAndHaveDistinctFamilyCopy() throws IOException {
        String html = resource("index.html");
        String app = resource("app.js");
        String share = resource("share.js");
        String welcome = html.substring(html.indexOf("data-screen=\"welcome\""),
                html.indexOf("data-screen=\"upload\""));

        assertFalse(welcome.contains("data-action=\"family\""));
        assertTrue(html.contains("class=\"confirm-assistance-actions\""));
        assertTrue(html.contains("data-action=\"family\" data-share-entry hidden"));
        assertTrue(app.contains("function canShareCurrentState()"));
        assertTrue(app.contains("validDocumentFlightNumber(currentFlight.flightNumber)"));
        assertTrue(app.contains("validDocumentDepartureDate(currentFlight.departureDate)"));
        assertTrue(app.contains("'CONFIRM_FLIGHT', 'CHECK_IN_QUESTION', 'BAGGAGE_QUESTION'"));
        assertTrue(app.contains("if (!canShareCurrentState() || !shareActionCode) return null;"));
        assertFalse(app.contains("请先进入乘机步骤"));
        assertTrue(share.contains("CONFIRM_FLIGHT: ['actionConfirmFlightTitle'"));
        assertTrue(share.contains("CHECK_IN_QUESTION: ['actionCheckInQuestionTitle'"));
        assertTrue(share.contains("BAGGAGE_QUESTION: ['actionBaggageQuestionTitle'"));
        assertTrue(share.contains("actionConfirmFlightTitle: '正在确认航班'"));
        assertTrue(share.contains("actionCheckInQuestionTitle: '正在确认值机状态'"));
        assertTrue(share.contains("actionBaggageQuestionTitle: '正在确认行李情况'"));
    }

    @Test
    void answerAndAssistanceButtonsUseExplicitGridsSvgIconsAndAccessibleShortLabels() throws IOException {
        String html = resource("index.html");

        assertTrue(html.contains("class=\"step-actions answer-choice-grid\" data-checkin-choices"));
        assertTrue(html.contains("class=\"step-actions answer-choice-grid\"><button"));
        assertTrue(html.contains("answer-choice answer-yes"));
        assertTrue(html.contains("answer-choice answer-no"));
        assertTrue(html.contains("answer-choice answer-unsure"));
        assertTrue(html.contains("class=\"choice-icon icon-unsure\""));
        assertTrue(html.contains("class=\"staff-icon-person\""));
        assertTrue(html.contains("class=\"staff-icon-question\""));
        assertTrue(html.contains("data-i18n=\"helpShort\""));
        assertTrue(html.contains("data-i18n=\"shareShort\""));
        assertTrue(html.contains("data-i18n-aria=\"checkInYesAria\""));
        assertTrue(html.contains("data-i18n-aria=\"baggageUnsureAria\""));
        assertTrue(html.contains("data-i18n-aria=\"yesContinueAria\""));
        assertFalse(html.contains("✓ 是，我已经完成值机"));
    }
}
