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
    void journeyProblemEntryDialogControlsAndCacheVersionAreStaticRegressionProtected() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");
        String worker = resource("service-worker.js");
        String welcome = html.substring(html.indexOf("data-screen=\"welcome\""), html.indexOf("data-screen=\"upload\""));
        String manual = html.substring(html.indexOf("data-screen=\"manual\""), html.indexOf("data-screen=\"error\""));
        String confirm = html.substring(html.indexOf("data-screen=\"confirm\""), html.indexOf("data-screen=\"step\""));
        String ordinaryConfirm = confirm.substring(0, confirm.indexOf("data-departure-passed-confirmation"))
                + confirm.substring(confirm.indexOf("data-segment-selector"));
        String step = html.substring(html.indexOf("data-screen=\"step\""), html.indexOf("data-screen=\"complete\""));
        String departed = confirm.substring(confirm.indexOf("data-departure-passed-confirmation"),
                confirm.indexOf("data-segment-selector"));
        String overlay = html.substring(html.indexOf("data-journey-problem-overlay"), html.indexOf("data-end-overlay"));

        assertFalse(welcome.contains("data-journey-problem-entry"));
        assertFalse(manual.contains("data-journey-problem-entry"));
        assertFalse(ordinaryConfirm.contains("data-journey-problem-entry"));
        assertTrue(step.indexOf("step-assistance-actions") < step.indexOf("data-journey-problem-entry"));
        assertTrue(step.indexOf("data-journey-problem-entry") < step.indexOf("journey-end-action"));
        assertTrue(departed.contains("data-journey-problem-entry"));
        assertTrue(overlay.contains("journey-problem-close"));
        assertEquals(2, overlay.split("data-action=\"journey-problem-connection\"", -1).length - 1);
        assertTrue(overlay.contains("请尽力说明下一班的起飞时间、航班号和目的地。"));
        assertFalse(html.contains("不清楚的内容可以不写。"));
        assertTrue(overlay.contains("data-journey-problem-statement"));
        assertTrue(overlay.contains("journey-problem-microphone-icon"));
        assertTrue(overlay.contains("data-action=\"journey-problem-voice\""));
        assertTrue(overlay.contains("data-journey-problem-submit"));
        assertTrue(overlay.contains("data-journey-problem-result"));
        assertTrue(html.contains("styles.css?v=42"));
        assertTrue(html.contains("locale.js?v=42"));
        assertTrue(html.contains("app.js?v=45"));
        assertTrue(worker.contains("onestep-flight-shell-"));
        assertTrue(worker.contains("CACHE_PREFIX + 'v45'"));
        assertTrue(worker.contains("/app.js?v=45"));
        assertTrue(js.contains("POST") || js.contains("method: 'POST'"));
    }

    @Test
    void journeyProblemHistoricalSubmissionAndSingleFallbackContractsAreStaticRegressionProtected()
            throws IOException {
        String js = resource("app.js");

        assertFalse(js.contains("return 'HISTORICAL_FLIGHT'"));
        assertFalse(js.contains("historicalFlightTitle"));
        assertTrue(js.contains("departureTimePassedStatus: '起飞时间已过'"));
        assertTrue(js.contains("departureTimePassedStatus: 'Departure time passed'"));
        assertTrue(js.contains("departureTimePassedStatus: 'Hora de salida pasada'"));
        assertFalse(js.contains("function journeyProblemFallbackSituation(payload)"));
        assertTrue(js.contains("journeyProblemFallback: '先别着急，请保留登机牌和航班信息"));
        assertTrue(js.contains("journeyProblemFallback: 'Please don’t worry. Keep your boarding pass and flight details"));
        assertTrue(js.contains("journeyProblemFallback: 'No te preocupes. Conserva tu tarjeta de embarque y los datos del vuelo"));
        assertFalse(js.contains("journeyProblemFallbackHistorical"));
        assertFalse(js.contains("journeyProblemFallbackMissed"));
        assertFalse(js.contains("journeyProblemFallbackConnection"));
        assertTrue(js.contains("renderJourneyProblemResult(response.answer, payload.locale, payload)"));
        assertTrue(js.contains("window.setTimeout(function () { controller.abort(); }, 45000)"));
    }

    @Test
    void nativeDateInputIsAbsoluteAndVisibleDateTracksValueAndLanguage() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");
        String js = resource("app.js");

        assertTrue(html.contains("class=\"manual-control date-control\""));
        assertTrue(html.contains("data-date-display"));
        assertTrue(css.contains(".date-control input[type=\"date\"]{position:absolute;inset:0"));
        assertTrue(css.contains("-webkit-text-fill-color:transparent;background:transparent;opacity:0"));
        assertFalse(css.contains("opacity:.01"));
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
        assertTrue(css.contains("--manual-control-font:800 clamp(18px,4vw,22px)/1.2 var(--data)"));
        assertTrue(css.contains(".manual-fields input{display:block;width:100%;inline-size:100%;max-width:100%;min-width:0;min-height:60px"));
        assertTrue(css.contains("color:var(--navy);font:var(--manual-control-font)"));
        assertTrue(css.contains("font:var(--manual-control-font)"));
        assertTrue(css.contains(".date-control-value{display:block;width:100%;min-width:0;padding:0 24px;box-sizing:border-box;color:inherit;font:inherit;line-height:inherit"));
        assertFalse(css.contains("--manual-control-font:700 16px"));
        assertTrue(css.contains("input.manual-control:focus-visible,.date-control:focus-within"));
        assertTrue(css.contains("input.manual-control:user-invalid,.date-control:has(input:user-invalid)"));
        assertTrue(css.contains("input.manual-control:disabled,.date-control:has(input:disabled)"));
        assertTrue(css.contains(".date-control input[type=\"date\"]{position:absolute;inset:0"));
        assertTrue(css.contains("input.manual-control[name=\"flight\"]::placeholder{text-transform:none}"));
    }

    @Test
    void whyThisStepPanelIsRemovedWithoutRemovingDocumentFailureExplanation() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");
        String js = resource("app.js");

        assertFalse(html.contains("step-evidence-panel"));
        assertFalse(html.contains("data-evidence-panel"));
        assertFalse(html.contains("data-action-source"));
        assertFalse(js.contains("whyThisStep"));
        assertFalse(js.contains("renderEvidence"));
        assertFalse(js.contains("sourceDescription"));
        assertFalse(css.contains(".evidence-panel"));
        assertFalse(css.contains(".evidence-group"));
        assertTrue(html.contains("data-i18n=\"documentUnusableWhy\">为什么没有读到？"));
        assertTrue(js.contains("documentUnusableWhy"));
    }

    @Test
    void shareCreationSeparatesServerAndNetworkFailures() throws IOException {
        String js = resource("app.js");
        String createShare = js.substring(js.indexOf("function createFamilyShare()"),
                js.indexOf("function shareFamily()"));

        assertTrue(createShare.contains("var status = Number(error && error.status) || 0"));
        assertTrue(createShare.contains("status >= 500 && status <= 599"));
        assertTrue(createShare.contains("分享服务暂时不可用，请稍后重试。"));
        assertTrue(createShare.contains("else if (!status)"));
        assertTrue(createShare.contains("网络连接失败，请检查网络后重试。"));
        assertFalse(createShare.contains("暂时无法创建链接，请检查网络后重试。"));
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
    void pngShareUsesPreparedFileAndHandlesDownloadFallbackWithoutRenderingAPreview() throws IOException {
        String js = resource("app.js");

        assertTrue(js.contains("prepareFamilyCard().catch(function () {})"));
        assertTrue(js.contains("navigator.canShare({ files: [pngCardCache.file] })"));
        assertTrue(js.contains("navigator.share({ files: [pngCardCache.file]"));
        assertTrue(js.contains("if (error && error.name === 'AbortError') return"));
        assertTrue(js.contains("saveCachedPngFallback()"));
        assertTrue(js.contains("link.download = pngCardCache.file.name"));
        assertTrue(js.contains("function isIosBrowser()"));
        assertFalse(js.contains("function showCachedPngPreview("));
        assertFalse(js.contains("function previewFamilyCard("));
        assertFalse(js.contains("showCachedPngPreview(true)"));
        assertFalse(js.contains("showCachedPngPreview(false)"));
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
        assertTrue(js.contains("图片准备失败"));
        assertTrue(js.contains("Image could not be prepared"));
        assertTrue(js.contains("No se pudo preparar la imagen"));
        assertFalse(js.contains("卡片图片编码失败，请重试。"));
        assertFalse(js.contains("预览后保存"));
        assertFalse(js.contains("save from the preview"));
        assertFalse(js.contains("guarda desde la vista previa"));
    }

    @Test
    void documentCompletionShowsOnlyMissingOrBlockingFieldsWithoutYearInput() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");

        assertTrue(js.contains("function classifyDocumentResult(result)"));
        assertTrue(js.contains("if (!segments.length) return { outcome: 'UNUSABLE'"));
        String classifier = js.substring(js.indexOf("function classifyDocumentResult(result)"),
                js.indexOf("function resetDocumentResultState()"));
        assertTrue(classifier.contains("documentSegmentLookupReady(segment, index, result && result.needsReviewFields,"));
        assertTrue(classifier.contains("result && result.reviewIssues"));
        assertFalse(classifier.contains("documentSegmentFlightNumberTrusted(segment, index)"));
        String uploadFlow = js.substring(js.indexOf("function uploadDocument(file)"),
                js.indexOf("function beginFlight(flight, documentResult)"));
        assertFalse(uploadFlow.contains("discoverMissingDocumentDates("));
        assertFalse(uploadFlow.contains("/api/journeys/discover-flight-date"));
        assertFalse(js.contains("function discoverMissingDocumentDates("));
        assertFalse(js.contains("/api/journeys/discover-flight-date"));
        assertTrue(uploadFlow.contains("if (assessment.segments.length)"));
        assertTrue(uploadFlow.contains("showDocumentSegmentSelection(result, assessment)"));
        assertTrue(js.contains("documentSegments = assessment.segments.slice()"));
        assertTrue(js.contains("panel.hidden = !partialDocumentEntry || !documentSegments.length"));
        assertTrue(js.contains("!hasFlightNumber || flightConflict || flightReview"));
        assertTrue(js.contains("!hasDepartureDate || dateConflict || dateReview"));
        assertTrue(js.contains("document.getElementById('flight-confirm-input')"));
        assertTrue(js.contains("document.getElementById('date-confirm-input')"));
        assertFalse(js.contains("combineDocumentDepartureYear"));
        assertFalse(js.contains("year-input"));
        assertFalse(html.contains("departureYear"));
        assertFalse(html.contains("data-manual-year"));
        assertTrue(html.contains("id=\"date-input\" name=\"departureDate\" type=\"date\""));
        assertTrue(html.contains("data-screen=\"document-unusable\""));
        assertTrue(html.contains("data-action=\"choose-other-file\""));
        assertEquals(1, html.split("data-manual-status", -1).length - 1);
        assertFalse(html.contains("data-manual-partial-notice"));
        assertTrue(html.contains("data-manual-document-segments"));
        assertTrue(html.contains("data-manual-segment-options"));
        assertTrue(html.contains("data-manual-flight-field"));
        assertTrue(html.contains("data-manual-date-field"));
        assertTrue(html.contains("data-review-candidates"));
        assertTrue(html.contains("data-review-candidate-fields"));
        assertFalse(html.contains("data-document-advisory"));
        assertFalse(html.contains("review-candidates-title"));
        assertTrue(js.contains("function reviewIssueIsBlockingCritical(issue)"));
        assertTrue(js.contains("issue.blocking !== true || issue.resolved"));
        assertFalse(js.contains("issue.candidates.length >= 2"));
        assertTrue(js.contains("function renderManualReviewCandidates(index, segment)"));
        assertTrue(js.contains("function selectDocumentReviewCandidate(button)"));
        assertTrue(js.contains("button.dataset.action = 'select-review-candidate'"));
        assertFalse(html.substring(html.indexOf("data-screen=\"document-unusable\""),
                html.indexOf("data-screen=\"confirm\"")).contains("Nutrient"));
    }

    @Test
    void uploadedItineraryWaitsForAnAccessibleSegmentChoiceBeforeResolvingOneLeg() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");
        String js = resource("app.js");

        assertTrue(js.contains("fetchJson('/api/documents/travel-document'"));
        assertFalse(js.contains("fetchJson('/api/documents/boarding-pass'"));
        assertTrue(js.contains("documentSegments = assessment.segments.slice()"));
        assertTrue(js.contains("usableDocumentSegmentIndexes = assessment.usableIndexes.slice()"));
        String selectionSetup = js.substring(js.indexOf("function showDocumentSegmentSelection(result, assessment)"),
                js.indexOf("function selectDocumentSegment(index)"));
        assertTrue(selectionSetup.contains("selectedDocumentSegmentIndex = null"));
        assertFalse(selectionSetup.contains("usableDocumentSegmentIndexes[0]"));
        assertTrue(js.contains("function showDocumentSegmentSelection(result, assessment)"));
        assertTrue(js.contains("function selectDocumentSegment(index)"));
        assertTrue(js.contains("function returnToDocumentSegmentSelection()"));
        assertTrue(js.contains("selectedDocumentSegmentIndex = null"));
        assertTrue(js.contains("awaitingDocumentSelection = true"));
        assertTrue(js.contains("currentFlight = null"));
        assertTrue(js.contains("item.dataset.action = 'select-segment'"));
        assertTrue(js.contains("item.disabled = lookupInFlight"));
        assertTrue(js.contains("continueButton.disabled = lookupInFlight || awaitingDocumentSelection || !currentFlight"));
        assertTrue(js.contains("segments: segments, usableIndexes: usableIndexes"));
        assertTrue(js.contains("segments: documentSegments.slice()"));
        assertTrue(html.contains("data-segment-context"));
        assertTrue(html.contains("data-segment-options"));
        assertTrue(html.contains("data-flight-correction"));
        assertTrue(html.contains("data-action=\"choose-other-file\""));
        assertTrue(html.contains("data-action=\"unusable-manual\""));
        assertTrue(js.contains("}).slice(0, 3)"));
        String alternatives = js.substring(js.indexOf("function explicitFlightAlternatives()"),
                js.indexOf("function renderFlightCorrection()"));
        assertFalse(alternatives.contains("documentSegments"));
        assertTrue(alternatives.contains("currentFlight.providerResults"));
        assertTrue(alternatives.contains("candidate.outcome === 'SUCCESS'"));
        assertTrue(alternatives.contains("candidate.live !== true && candidate.verified !== true"));
        assertTrue(css.contains(".screen-confirm.is-awaiting-segment"));
        assertTrue(css.contains(".screen-confirm.is-correcting-flight>:not(.home-back-button):not([data-flight-correction])"));

        String classifier = js.substring(js.indexOf("function classifyDocumentResult(result)"),
                js.indexOf("function resetDocumentResultState()"));
        assertTrue(classifier.contains("result.segments.slice()"));
        assertTrue(classifier.contains("segments.forEach(function (segment, index)"));
        assertTrue(classifier.contains("usableIndexes.push(index)"));
        assertFalse(classifier.contains(".sort("));
        String itinerary = js.substring(js.indexOf("function createSegmentDisplay(segment, index)"),
                js.indexOf("function explicitFlightAlternatives()"));
        assertTrue(itinerary.contains("document.createElement('button')"));
        assertTrue(itinerary.contains("item.type = 'button'"));
        assertTrue(itinerary.contains("item.dataset.action = 'select-segment'"));
        assertTrue(itinerary.contains("item.dataset.segmentIndex = String(index)"));
        assertTrue(itinerary.contains("documentSegments.forEach(function (segment, index)"));
        assertTrue(itinerary.contains("is-incomplete"));
        assertTrue(itinerary.contains("aria-current"));
        assertTrue(itinerary.contains("item.disabled = lookupInFlight"));
        assertFalse(itinerary.contains("item.disabled = !usable"));
        String uploadFlow = js.substring(js.indexOf("function uploadDocument(file)"),
                js.indexOf("function beginFlight(flight, documentResult)"));
        assertTrue(uploadFlow.contains("if (assessment.segments.length)"));
        assertTrue(uploadFlow.indexOf("result = normalizeDocumentResult(result)")
                < uploadFlow.indexOf("var assessment = classifyDocumentResult(result)"));
        assertTrue(uploadFlow.indexOf("var assessment = classifyDocumentResult(result)")
                < uploadFlow.indexOf("showDocumentSegmentSelection(result, assessment)"));
        assertFalse(uploadFlow.contains("discoverMissingDocumentDates("));
        assertFalse(uploadFlow.contains("/api/journeys/discover-flight-date"));
        String multiSegmentBranch = uploadFlow.substring(
                uploadFlow.indexOf("if (assessment.segments.length)"),
                uploadFlow.indexOf("showUnusableDocument()"));
        assertTrue(multiSegmentBranch.contains("showDocumentSegmentSelection(result, assessment)"));
        assertTrue(multiSegmentBranch.contains("return"));
        assertFalse(multiSegmentBranch.contains("discoverMissingDocumentDates("));
        assertFalse(multiSegmentBranch.contains("selectDocumentSegment("));
        assertFalse(multiSegmentBranch.contains("postFlightJson("));
        assertFalse(multiSegmentBranch.contains("resolveUploadedFlight("));
        assertFalse(multiSegmentBranch.contains("beginFlight("));
        String selectionFlow = js.substring(js.indexOf("function selectDocumentSegment(index)"),
                js.indexOf("function uploadDocument(file)"));
        assertTrue(selectionFlow.indexOf("selectedDocumentSegmentIndex = index")
                < selectionFlow.indexOf("showScreen('manual')"));
        assertFalse(selectionFlow.contains("resolveUploadedFlight("));
        assertFalse(selectionFlow.contains("startFlightLookup("));
        assertFalse(selectionFlow.contains("postFlightJson("));
        String explicitSubmit = js.substring(
                js.indexOf("document.querySelector('[data-action=\"manual-submit\"]').addEventListener('submit'"),
                js.indexOf("['flight-input', 'date-input', 'flight-confirm-input', 'date-confirm-input'].forEach"));
        assertTrue(explicitSubmit.contains("applyDocumentSegmentCompletion(index, flightNumber, departureDate, {"));
        assertTrue(explicitSubmit.contains("returnToDocumentSegmentSelection()"));
        assertFalse(explicitSubmit.contains("startFlightLookup(completed.flightNumber"));
        assertTrue(explicitSubmit.contains("if (documentSegmentLookupReady(segment, index)"));
        assertTrue(explicitSubmit.contains("manualPrimary.dataset.mode === 'query'"));
        assertTrue(explicitSubmit.contains("startFlightLookup(segment.flightNumber, segment.departureDate, false, readyResult)"));
        String returnFlow = js.substring(js.indexOf("function returnToDocumentSegmentSelection()"),
                js.indexOf("function renderErrorMessage()"));
        assertTrue(returnFlow.contains("selectedDocumentSegmentIndex = null"));
        assertTrue(returnFlow.contains("awaitingDocumentSelection = true"));
        assertTrue(returnFlow.contains("renderSegmentSelector()"));
        assertFalse(returnFlow.contains("documentSegments = []"));
        String resolutionFlow = js.substring(js.indexOf("function resolveUploadedFlight(options)"),
                js.indexOf("function createJourneyContext()"));
        assertEquals(1, resolutionFlow.split("showScreen\\('confirm'\\)", -1).length - 1);

        assertFalse(html.contains("data-field-review"));
        assertFalse(html.contains("data-review-confidence"));
        assertFalse(html.contains("data-provider-summary"));
        assertFalse(html.contains("data-evidence-list"));
        assertFalse(html.contains("data-flight-source"));
        assertFalse(html.contains("data-checkin-preset"));
        assertFalse(html.contains("checkin-preset-wrong"));
        assertFalse(html.contains("data-extraction-note"));
        assertFalse(html.contains("confirm-data-sources"));
        assertFalse(js.contains("function confirmDocumentField("));
        assertFalse(js.contains("function renderDocumentReview("));
        assertFalse(js.contains("function renderProviderEvidenceLegacy("));
        assertFalse(js.contains("function dismissBoardingPassPreset("));
        assertFalse(css.contains(".extraction-note"));
        assertFalse(css.contains(".provider-summary"));
        assertFalse(css.contains(".provider-chip"));
        assertFalse(css.contains(".confirm-data-sources"));
    }

    @Test
    void liveLookupHasFinalFormatDateConflictAndConcurrencyGuards() throws IOException {
        String js = resource("app.js");
        String start = js.substring(js.indexOf("function startFlightLookup("),
                js.indexOf("function validDocumentFlightNumber("));
        String guard = js.substring(js.indexOf("function lookupGuardReason("),
                js.indexOf("function normalizeDocumentSegment("));
        String submit = js.substring(
                js.indexOf("document.querySelector('[data-action=\"manual-submit\"]').addEventListener('submit'"),
                js.indexOf("['flight-input', 'date-input', 'flight-confirm-input', 'date-confirm-input'].forEach"));

        assertTrue(start.contains("if (lookupInFlight) return"));
        assertTrue(start.contains("var guardReason = lookupGuardReason("));
        assertTrue(start.indexOf("if (lookupInFlight) return") < start.indexOf("postFlightJson("));
        assertTrue(start.indexOf("lookupGuardReason(") < start.indexOf("postFlightJson("));
        assertEquals(1, start.split("postFlightJson\\('/api/journeys/resolve-flight'", -1).length - 1);
        assertTrue(guard.contains("validDocumentFlightNumber(flightNumber)"));
        assertTrue(guard.contains("validDocumentDepartureDate(departureDate)"));
        assertFalse(guard.contains("documentSegmentFlightNumberTrusted(segment, index)"));
        assertTrue(guard.contains("documentSegmentHasCriticalConflict(index, segment,"));
        assertFalse(guard.contains("departureAirport"));
        assertFalse(guard.contains("arrivalAirport"));
        assertTrue(submit.contains("returnToDocumentSegmentSelection()"));
        assertTrue(submit.contains("flightConfirmation.checked"));
        assertTrue(submit.contains("dateConfirmation.checked"));
        assertFalse(js.contains("primary.textContent = documentQueryTargetLabel(selected)"));
        assertTrue(js.contains("primary.dataset.i18n = 'searchFlightPartial'"));
        assertTrue(js.contains("searchFlightPartial: '查询航班'"));
    }

    @Test
    void documentSegmentsAreNormalizedBeforeTheirFirstClassification() throws IOException {
        String js = resource("app.js");
        String normalization = js.substring(js.indexOf("function normalizeDocumentSegment(segment)"),
                js.indexOf("function classifyDocumentResult(result)"));
        String uploadFlow = js.substring(js.indexOf("function uploadDocument(file)"),
                js.indexOf("function beginFlight(flight, documentResult)"));

        assertTrue(normalization.contains("String(segment.departureDate || '').trim()"));
        assertTrue(normalization.contains("String(segment.scheduledDeparture || '').trim()"));
        assertTrue(normalization.contains("validDocumentDepartureDate(match[1])"));
        assertTrue(normalization.contains("normalized.departureDate = match[1]"));
        assertTrue(normalization.contains("normalized.departureMonthDay = documentMonthDayFromDate(match[1])"));
        assertTrue(normalization.contains("validDocumentMonthDay(segment.departureMonthDay)"));
        assertTrue(normalization.contains("normalized.segments.map(normalizeDocumentSegment)"));
        assertFalse(normalization.contains("Date.parse("));
        assertFalse(normalization.contains("toISOString("));
        assertTrue(uploadFlow.indexOf("result = normalizeDocumentResult(result)")
                < uploadFlow.indexOf("var assessment = classifyDocumentResult(result)"));
        assertEquals(1, uploadFlow.split("result = normalizeDocumentResult\\(", -1).length - 1);
        assertTrue(uploadFlow.indexOf("var assessment = classifyDocumentResult(result)")
                < uploadFlow.indexOf("showDocumentSegmentSelection(result, assessment)"));
        assertFalse(uploadFlow.contains("discoverMissingDocumentDates("));
        assertFalse(uploadFlow.contains("/api/journeys/discover-flight-date"));

        String multiSegmentBranch = uploadFlow.substring(
                uploadFlow.indexOf("if (assessment.segments.length)"),
                uploadFlow.indexOf("showUnusableDocument()"));
        assertTrue(multiSegmentBranch.contains("showDocumentSegmentSelection(result, assessment)"));
        assertFalse(multiSegmentBranch.contains("discoverMissingDocumentDates("));
        assertFalse(multiSegmentBranch.contains("resolveUploadedFlight("));
    }

    @Test
    void recognitionAndLiveLookupUseOneLocalizedCurrentLoadingStage() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");
        String loading = html.substring(html.indexOf("data-screen=\"loading\""),
                html.indexOf("data-screen=\"document-unusable\""));

        assertEquals(1, loading.split("data-loading-fixed", -1).length - 1);
        assertFalse(loading.contains("data-loading-status"));
        assertTrue(loading.contains("aria-live=\"polite\""));
        assertTrue(js.contains("['正在上传文件', 'Uploading the file', 'Subiendo el archivo']"));
        assertTrue(js.contains("['正在读取航班信息', 'Reading flight information', 'Leyendo la información del vuelo']"));
        assertTrue(js.contains("['正在查询航班', 'Searching for the flight', 'Buscando el vuelo']"));
        assertTrue(js.contains("['正在准备下一步', 'Preparing the next step', 'Preparando el siguiente paso']"));
        assertTrue(js.contains("runLoading(uploadLoadingMessages, 'documentRecognitionWaiting')"));
        assertTrue(js.contains("runLoading(loadingMessages, 'liveFlightLookupWaiting')"));
    }

    @Test
    void welcomeSpeechNoticeFallbackCopyAndRestoreContractAreLocalizedAndSafe() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");
        String js = resource("app.js");

        assertTrue(html.contains("class=\"welcome-speech-note\""));
        assertTrue(html.contains("data-i18n=\"welcomeSpeechNotice\""));
        assertTrue(html.contains("Voice guidance will play automatically. Select “Stop” while it is reading."));
        assertTrue(js.contains("接下来会自动朗读；朗读时可点“停止”。"));
        assertTrue(js.contains("Voice guidance will play automatically. Select “Stop” while it is reading."));
        assertTrue(js.contains("La guía de voz se reproducirá automáticamente. Pulsa «Detener» durante la lectura."));
        assertTrue(js.contains("upload: '上传旅行文件'"));
        assertTrue(js.contains("uploadHint: '支持行程单、电子客票、预订截图或登机牌'"));
        assertTrue(js.contains("upload: 'Upload a travel document'"));
        assertTrue(js.contains("uploadHint: 'Supports itineraries, e-tickets, booking screenshots, or boarding passes'"));
        assertTrue(js.contains("upload: 'Subir un documento de viaje'"));
        assertTrue(js.contains("uploadHint: 'Admite itinerarios, billetes electrónicos, capturas de reserva o tarjetas de embarque'"));
        assertTrue(html.contains("data-i18n=\"upload\">Upload a travel document"));
        assertTrue(html.contains("data-i18n=\"uploadHint\">Supports itineraries, e-tickets, booking screenshots, or boarding passes"));
        assertTrue(html.contains("data-live-status-unavailable"));
        assertTrue(js.contains("实时状态暂不可用，请核对下方行程。"));
        assertTrue(js.contains("Live status is unavailable. Check the journey below."));
        assertTrue(css.contains(".welcome-speech-note"));
        assertTrue(js.contains("flight.liveDataSource === 'DOCUMENT_FALLBACK'"));
		assertTrue(js.contains("continueButton.disabled = Boolean(documentFallback"));
		assertTrue(js.contains("if (isDocumentFallbackFlight(currentFlight))"));
		assertTrue(js.contains("上传文件中的信息尚未经过实时航班验证，不能进入值机流程。"));
        assertTrue(js.contains("function normalizeSavedFlight(flight)"));
        assertTrue(js.contains("currentFlight = normalizeSavedFlight(saved.flight)"));
        for (String field : new String[] { "documentScheduledDeparture", "documentBoardingTime",
                "documentTerminal", "documentGate", "documentDataSource", "liveDataSource" }) {
            assertTrue(js.substring(js.indexOf("function safeFlightSnapshot(flight)"),
                    js.indexOf("function safeActionSnapshot(action)")).contains("'" + field + "'"), field);
        }
        assertTrue(js.contains("normalized.boardingPassDetected = false"));
        assertTrue(js.contains("checkInStatusAnswer = null"));
        String normalizer = js.substring(js.indexOf("function normalizeSavedFlight(flight)"),
                js.indexOf("function safeActionSnapshot(action)"));
        assertFalse(normalizer.contains("normalized.scheduledDeparture ="));
        assertTrue(js.contains("if (isDocumentFallbackFlight(flight)) return null"));
        assertTrue(js.contains("!isDocumentFallbackFlight(currentFlight) && currentFlight.timeContext"));
        assertTrue(js.contains("postFlightJson('/api/journeys/resolve-flight', resolvePayload("));
        assertTrue(js.contains("DOCUMENT_FALLBACK_RETAINED"));
    }

    @Test
    void checkInAnswerIsNeverPresetButSurvivesHistoryAndSessionRestore() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");
        String showQuestion = js.substring(js.indexOf("function showCheckInQuestion()"),
                js.indexOf("function buildQaAction()"));
        String answer = js.substring(js.indexOf("function answerCheckIn(answer)"),
                js.indexOf("function restoreReversibleEvents(events)"));

        assertFalse(html.contains("data-checkin-preset"));
        assertFalse(showQuestion.contains("checkInStatusAnswer = null"));
        assertTrue(answer.indexOf("checkInStatusAnswer = answer") < answer.indexOf("snapshotJourney()"));
        assertTrue(js.contains("checkInStatusAnswer: checkInStatusAnswer"));
        assertTrue(js.contains("checkInStatusAnswer = previous.checkInStatusAnswer == null ? null : previous.checkInStatusAnswer"));
        assertTrue(js.contains("saved.checkInStatusAnswer === 'no' || saved.checkInStatusAnswer === 'unsure'"));
        assertTrue(js.contains("normalized.boardingPassDetected = false"));
        assertFalse(js.contains("Boolean(result.boardingPassDetected)"));
        assertEquals(2, js.split("showCheckInQuestion\\(", -1).length - 1);
        assertTrue(js.contains("checkInQuestion: '你已经完成值机了吗？'"));
        assertTrue(js.contains("checkInQuestion: 'Have you checked in?'"));
        assertTrue(js.contains("checkInQuestion: '¿Ya has facturado?'"));
        assertFalse(js.contains("你已经完成值机，或已经拿到登机牌了吗？"));
    }

    @Test
    void liveProviderFailuresUseSpecificCopyAndKeepDiagnosticsOutOfThePage() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");
        String errorScreen = html.substring(html.indexOf("data-screen=\"error\""),
                html.indexOf("data-screen=\"step\""));
        String fetchJson = js.substring(js.indexOf("function fetchJson(url, options)"),
                js.indexOf("function validDocumentFlightNumber"));
        String errorHandling = js.substring(js.indexOf("function isLocalDebugEnvironment()"),
                js.indexOf("function speechIsSupported()"));
        String safeProviderDebug = js.substring(js.indexOf("function safeProviderDebugResult(result)"),
                js.indexOf("function namedProviderResult"));

        assertTrue(errorScreen.contains("data-action=\"retry\""));
        assertTrue(errorScreen.contains("data-action=\"manual\""));
        assertTrue(errorScreen.contains("data-action=\"staff\""));
        assertEquals(1, errorScreen.split("data-action=\"retry\"", -1).length - 1);
        assertEquals(1, errorScreen.split("data-action=\"manual\"", -1).length - 1);
        assertEquals(1, errorScreen.split("data-action=\"staff\"", -1).length - 1);
        assertTrue(errorScreen.indexOf("data-action=\"retry\"") < errorScreen.indexOf("data-action=\"manual\""));
        assertTrue(errorScreen.indexOf("data-action=\"manual\"") < errorScreen.indexOf("data-action=\"staff\""));
        assertTrue(errorScreen.contains("class=\"choice-actions two-choice-grid error-actions\""));
        assertTrue(errorScreen.contains("staff-help-button action-full-row"));
        String errorStaffButton = errorScreen.substring(errorScreen.indexOf("data-action=\"staff\""),
                errorScreen.indexOf("</button>", errorScreen.indexOf("data-action=\"staff\"")));
        assertTrue(errorStaffButton.contains("<svg"));
        assertTrue(errorStaffButton.contains("aria-hidden=\"true\""));
        assertTrue(errorStaffButton.contains("focusable=\"false\""));
        assertTrue(errorStaffButton.indexOf("<svg") < errorStaffButton.indexOf("<span"));
        assertFalse(errorScreen.toLowerCase().contains(">svg<"));
        assertTrue(fetchJson.contains("error.apiError = payload"));
        assertTrue(fetchJson.contains("error.status = response.status"));
        assertTrue(fetchJson.contains("error.requestUrl = url"));
        assertTrue(errorHandling.contains("details && details.code"));
        assertTrue(errorHandling.contains("details && details.providerResults"));
        assertTrue(errorHandling.contains("details && details.messageZh"));
        assertTrue(errorHandling.contains("details && details.messageEn"));
        assertTrue(errorHandling.contains("FLIGHT_NOT_FOUND"));
		assertTrue(errorHandling.contains("没有找到这个航班"));
        assertTrue(errorHandling.contains("normalizedCode.indexOf('INVALID_') === 0 || status === 400"));
        assertTrue(errorHandling.contains("航班号或日期有误"));
        assertTrue(errorHandling.contains("status === 429"));
        assertTrue(errorHandling.contains("查询次数达到限制"));
        assertTrue(errorHandling.contains("航班信息暂时不可用"));
        assertTrue(errorHandling.contains("CLIENT_FLIGHT_TIMEOUT"));
		assertTrue(errorHandling.contains("normalizedCode === 'LIVE_FLIGHT_TIMEOUT'"));
        assertTrue(errorHandling.contains("查询超时"));
        assertTrue(errorHandling.contains("localhost|127\\.0\\.0\\.1|\\[::1\\]"));
        assertTrue(errorHandling.contains("AeroDataBox provider result"));
        assertTrue(errorHandling.contains("Secondary flight-data provider result"));
        assertTrue(errorHandling.contains("HTTP status"));
        assertTrue(errorHandling.contains("Error code"));
        assertTrue(errorHandling.contains("Backend message (zh)"));
        assertTrue(errorHandling.contains("Backend message (en)"));
        assertFalse(safeProviderDebug.contains("sourceUrl"));
        assertFalse(safeProviderDebug.toLowerCase().contains("apikey"));
        assertFalse(safeProviderDebug.toLowerCase().contains("authorization"));
        assertTrue(errorHandling.contains("setStaffContent("));
        assertTrue(html.contains("data-i18n=\"errorTitle\">航班信息暂时不可用"));
        assertTrue(html.contains("data-i18n=\"technicalDetails\">调试详情"));
        assertTrue(js.contains("translations.en.technicalDetails = 'Debug details'"));
    }

    @Test
    void automaticRefreshUsesOneDeadlineSchedulerAndSilentRequestBranches() throws IOException {
        String js = resource("app.js");
        String refreshFlight = js.substring(js.indexOf("function refreshFlight(options)"),
                js.indexOf("function beginNewFlightInformation()"));
        String refreshArrival = js.substring(js.indexOf("function refreshArrivalInfo(options)"),
                js.indexOf("function maybeRefreshArrivalOnEntry()"));
        String refreshControls = js.substring(js.indexOf("function updateRefreshControls()"),
                js.indexOf("function setDeparturePassedStaffContent("));
        String scheduler = js.substring(js.indexOf("function autoRefreshDelayMs("),
                js.indexOf("function refreshFlight(options)"));

        assertTrue(js.contains("var AUTO_REFRESH_FAR_MS = 300000"));
        assertTrue(js.contains("var AUTO_REFRESH_NEAR_MS = 120000"));
        assertTrue(js.contains("var AUTO_REFRESH_BOUNDARY_MS = 90 * 60 * 1000"));
        assertTrue(scheduler.contains("untilDeparture <= AUTO_REFRESH_BOUNDARY_MS"));
        assertTrue(scheduler.contains("Math.min(AUTO_REFRESH_FAR_MS, untilDeparture - AUTO_REFRESH_BOUNDARY_MS)"));
        assertTrue(scheduler.contains("journeyContext.boarded === true"));
        assertTrue(scheduler.contains("window.setTimeout(runDueAutoRefresh"));
        assertFalse(scheduler.contains("setInterval"));
        assertTrue(refreshFlight.contains("var silent = settings.silent === true"));
        assertTrue(refreshFlight.contains("if (!navigator.onLine)"));
        assertTrue(refreshFlight.contains("if (!silent) updateRefreshControls()"));
        assertTrue(refreshFlight.contains("scheduleAutoRefresh(true)"));
        assertFalse(refreshFlight.contains("Date.now()"));
        assertTrue(refreshArrival.contains("flightRequestController"));
        assertTrue(refreshArrival.contains("if (!navigator.onLine)"));
        assertTrue(refreshArrival.contains("var silent = settings.silent === true"));
        assertTrue(refreshArrival.contains("if (!silent) updateRefreshControls()"));
        assertTrue(js.contains("refreshArrivalInfo({ silent: true, source: 'arrival-entry' })"));
        assertTrue(refreshControls.contains("!navigator.onLine || Boolean(flightRequestController)"));
        assertTrue(js.contains("function refreshAfterReturnToPage()"));
        assertTrue(js.contains("resumeAutoRefresh();"));
        assertTrue(js.contains("requestNextAction({ refresh: true, silent: true, source: 'auto' })"));
        assertTrue(js.contains("refreshFlight({ silent: true, source: 'auto' })"));
        assertTrue(js.contains("restorableRefreshStatus(saved.refreshStatusState)"));
    }

    @Test
    void operationalChangeAlertIsTopLevelAssertiveAndUsesDedicatedPrioritySpeech() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");
        String js = resource("app.js");

        String confirm = html.substring(html.indexOf("data-screen=\"confirm\""),
                html.indexOf("data-screen=\"manual\""));
        String step = html.substring(html.indexOf("data-screen=\"step\""),
                html.indexOf("data-screen=\"complete\""));
        String alertMarkup = "data-flight-change-alert role=\"alert\" aria-live=\"assertive\" aria-atomic=\"true\" hidden";
        assertEquals(2, html.split(alertMarkup, -1).length - 1);
        assertTrue(confirm.indexOf("data-action=\"journey-back\"") < confirm.indexOf("data-flight-change-alert"));
        assertTrue(confirm.indexOf("data-flight-change-alert") < confirm.indexOf("id=\"confirm-title\""));
        assertTrue(step.indexOf("data-flight-change-alert") < step.indexOf("class=\"step-progress\""));

        assertTrue(css.contains("--flight-change-bg:#163D75"));
        assertTrue(css.contains("--flight-change-text:#F0D68A"));
        assertTrue(css.contains("--flight-change-border:#D2B45F"));
        assertTrue(css.contains("border-radius:var(--radius-medium)"));
        assertTrue(css.contains(".flight-change-alert.is-critical"));

        assertTrue(js.contains("function presentNewFlightChangeBatch()"));
        assertTrue(js.contains("startSpeechText(flightChangeSpeechText(groups, currentLocale), true)"));
        assertTrue(js.contains("if (hasNewChangeBatch) presentNewFlightChangeBatch()"));
    }

    @Test
    void flightChangeNoticesPersistAndLocalizedControlsKeepWholeWords()
            throws IOException {
        String css = resource("styles.css");
        String js = resource("app.js");
        String beginFlight = js.substring(js.indexOf("function beginFlight("),
                js.indexOf("function renderScenarioTestCards()"));
        String requestNextAction = js.substring(js.indexOf("function requestNextAction("),
                js.indexOf("var spanishActionCopy"));
        String restore = js.substring(js.indexOf("function applySavedJourney("),
                js.indexOf("function restoreJourney()"));
        String renderNotice = js.substring(js.indexOf("function renderFlightChanges()"),
                js.indexOf("function flightChangeCopyContext()"));

        assertFalse(css.contains("button:not(.brand)"));
        assertTrue(css.contains("@media(max-width:220px)"));
        assertFalse(css.contains("overflow-wrap:anywhere"));
        assertFalse(css.contains("hyphens:auto"));
        assertTrue(css.contains("word-break:normal;overflow-wrap:normal;hyphens:none"));
        assertTrue(css.contains("--full-row-control-font-size:clamp(28px,7vw,32px)"));
        assertTrue(css.contains("--manual-control-font:800 clamp(18px,4vw,22px)"));
        assertTrue(css.contains("--multi-control-font-size:26px"));
        assertTrue(css.contains("html[lang=\"en\"]{--multi-control-font-size:25px}"));
        assertTrue(css.contains("html[lang=\"es\"]{--multi-control-font-size:25px}"));
        assertFalse(css.contains("--action-control-font-size"));
        assertFalse(css.contains("--three-choice-font-size"));
        assertTrue(css.contains("@media(max-width:280px)"));
        assertTrue(css.contains(".answer-choice-grid,.step-assistance-actions,.step-assistance-actions.share-unavailable{grid-template-columns:minmax(0,1fr)}"));
        assertTrue(css.contains(".scenario-actions{display:grid;grid-template-columns:repeat(3,minmax(0,1fr))"));
        assertTrue(css.contains(".scenario-actions button{width:100%;min-height:60px"));
        assertTrue(css.contains("font:800 14px/1.25 var(--body)"));
        assertTrue(css.contains("--flight-change-bg:#163D75"));
        assertTrue(css.contains("--flight-change-text:#F0D68A"));
        assertTrue(css.contains("--flight-change-border:#D2B45F"));
        assertTrue(css.contains("font-size:clamp(21px,3vw,25px)"));
        assertTrue(css.contains("font-size:clamp(18px,2.7vw,22px)"));
        assertTrue(css.contains("-webkit-text-fill-color:transparent;background:transparent;opacity:0"));

        assertFalse(js.contains("FLIGHT_CHANGE_NOTICE_MS"));
        assertFalse(js.contains("setTimeout(dismissFlightChangeNotice"));
        assertTrue(js.contains("function dismissFlightChangeNotice()"));
        assertTrue(renderNotice.contains("dismissButton.dataset.action = 'dismiss-flight-change'"));
        assertTrue(renderNotice.contains("dismissButton.className = 'flight-change-dismiss'"));
        assertFalse(renderNotice.contains("flight-change-dismiss button"));
        assertTrue(renderNotice.contains("'关闭航班变化提示'"));
        assertTrue(renderNotice.contains("'Dismiss flight change notice'"));
        assertTrue(renderNotice.contains("'Cerrar aviso de cambio de vuelo'"));
        assertTrue(renderNotice.contains("dismissButton.setAttribute('aria-label', dismissLabel)"));
        assertTrue(renderNotice.contains("dismissButton.title = dismissLabel"));
        assertTrue(renderNotice.contains("dismissMark.setAttribute('aria-hidden', 'true')"));
        assertTrue(renderNotice.contains("dismissMark.textContent = '×'"));
        assertFalse(renderNotice.contains("localeText('关闭提示', 'Dismiss', 'Cerrar')"));
        assertTrue(css.contains(".flight-change-dismiss{"));
        assertTrue(css.contains("width:44px"));
        assertTrue(css.contains("height:44px"));
        assertTrue(css.contains(".flight-change-dismiss:focus-visible{outline:4px solid #D7EEFF"));
        assertTrue(css.contains(".flight-change-dismiss{border-color:ButtonText;background:ButtonFace;color:ButtonText;forced-color-adjust:auto}"));
        assertFalse(js.contains("showCurrentFlightChangesWithoutRepeatSuppression"));
        assertFalse(beginFlight.contains("latestChanges"));
        assertTrue(requestNextAction.contains("if (!refreshRequested) dismissFlightChangeNotice()"));
        assertFalse(restore.contains("latestChanges"));
        assertTrue(renderNotice.contains("alert.closest('.screen')"));
        assertTrue(renderNotice.contains("ownerScreen.dataset.screen === currentScreen"));
        assertTrue(renderNotice.contains("delete alert.dataset.renderSignature"));
    }

    @Test
    void confirmationRouteUsesFourSharedRowsAtEveryViewportWidth() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");

        assertTrue(html.contains("<div class=\"route-summary\"><div><span class=\"airport-city\" data-departure-name>"));
        assertTrue(html.contains("<div class=\"align-right\"><span class=\"airport-city\" data-arrival-name>"));
        assertTrue(css.contains(".screen-confirm .route-summary{grid-template-columns:minmax(0,1fr) 32px minmax(0,1fr);grid-template-rows:auto auto auto auto"));
        assertTrue(css.contains(".screen-confirm .route-summary>div{display:contents}"));
        assertTrue(css.contains("[data-departure-name]{grid-column:1;grid-row:1}"));
        assertTrue(css.contains("[data-departure-airport]{grid-column:1;grid-row:2}"));
        assertTrue(css.contains("[data-route-departure-label]{grid-column:1;grid-row:3}"));
        assertTrue(css.contains("[data-route-departure-time]{grid-column:1;grid-row:4}"));
        assertTrue(css.contains("[data-arrival-name]{grid-column:3;grid-row:1"));
        assertTrue(css.contains("[data-arrival-airport]{grid-column:3;grid-row:2"));
        assertTrue(css.contains("[data-route-arrival-label]{grid-column:3;grid-row:3"));
        assertTrue(css.contains("[data-route-arrival-time]{grid-column:3;grid-row:4"));
        assertTrue(css.contains(".screen-confirm .route-summary .route-arrow{grid-column:2;grid-row:2;align-self:center;justify-self:center;margin:0}"));
        assertTrue(css.contains(".screen-confirm .route-summary{grid-template-columns:minmax(0,1fr) 24px minmax(0,1fr);gap:6px"));
        assertTrue(css.contains(".screen-confirm .route-summary{grid-template-columns:minmax(0,1fr) 14px minmax(0,1fr);gap:4px}"));
        assertFalse(css.contains(".screen-confirm .route-arrow{width:24px;margin-top:30px}"));
        assertFalse(css.contains(".route-summary{overflow:hidden}"));
    }

    @Test
    void namedDemoScenariosExposeAllRealTwoStageRefreshEntrypointsOnlyBehindTheExactQueryFlag()
            throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");
        String js = resource("app.js");
        String trigger = js.substring(js.indexOf("function triggerDemoScenarioRefresh()"),
                js.indexOf("function restartDemoScenario()"));
        String reset = js.substring(js.indexOf("function resetFlightChangePresentation()"),
                js.indexOf("function updateRefreshChangeState("));
        String dismiss = js.substring(js.indexOf("function dismissFlightChangeNotice()"),
                js.indexOf("function resetFlightChangePresentation()"));

        assertEquals(17, html.split("data-action=\"scenario\"", -1).length - 1);
        for (String id : new String[] { "NORMAL", "TERMINAL_CHANGE", "GATE_CHANGE",
                "CHECK_IN_COUNTER_CHANGE", "BAG_DROP_DESK_CHANGE", "BOARDING_TIME_CHANGE",
                "DEPARTURE_TIME_CHANGE", "DELAY", "BOARDING", "CANCEL", "DEPARTED", "DIVERTED",
                "RETURNED", "ARRIVAL_TIME_CHANGE", "ARRIVED", "BAGGAGE_BELT_CHANGE", "STALE" }) {
            assertTrue(html.contains("data-scenario=\"" + id + "\""), id);
        }
        assertEquals(3, html.split("class=\"scenario-group\"", -1).length - 1);
        assertEquals(2, html.split("data-scenario-test-card", -1).length - 1);
        assertTrue(js.contains(".get('demoScenarios') === 'true'"));
        assertTrue(js.contains("document.querySelector('[data-scenario-panel]').hidden = !demoScenariosEnabled"));
        assertTrue(trigger.contains("refreshFlight({ silent: true, source: 'scenario-test' })"));
        assertTrue(trigger.contains("requestNextAction({ refresh: true, silent: true, source: 'scenario-test' })"));
        assertTrue(trigger.contains("refreshArrivalInfo({ silent: true, source: 'scenario-test' })"));
        assertFalse(trigger.contains("flightChangeAlertCopy"));
        assertFalse(trigger.contains("querySelector"));
        assertTrue(reset.contains("dismissFlightChangeNotice()"));
        assertTrue(reset.contains("presentedFlightChangeSignatures = Object.create(null)"));
        assertTrue(dismiss.contains("displayedFlightChanges = []"));
        assertTrue(dismiss.contains("displayedFlightChangeFlight = null"));
        assertTrue(dismiss.contains("stepChangeAlert = null"));
        assertTrue(css.contains("grid-template-columns:repeat(3,minmax(0,1fr))"));
        assertTrue(css.contains(".scenario-actions button{width:100%;min-height:60px"));
        assertTrue(css.contains("font:800 14px/1.25 var(--body)"));
        assertTrue(css.contains("@media(max-width:280px){.scenario-actions{grid-template-columns:1fr}}"));
        assertTrue(css.contains(":not([data-flight-change-alert]):not([data-scenario-test-card])"));
    }

    @Test
    void documentFailureCopyAndVersionedAppShellAreComplete() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");
        String worker = resource("service-worker.js");

        assertTrue(html.contains("没有读到航班信息"));
        assertTrue(js.contains("No flight information was found"));
        assertTrue(js.contains("No se encontró información del vuelo"));
        assertTrue(js.contains("Only some information was found"));
        assertTrue(js.contains("Solo se encontró parte de la información"));
        assertTrue(html.contains("styles.css?v=42"));
        assertTrue(html.contains("locale.js?v=42"));
        assertTrue(html.contains("app.js?v=45"));
        assertFalse(html.contains("?v=37"));
        assertTrue(worker.contains("CACHE_PREFIX + 'v45'"));
        assertFalse(worker.contains("CACHE_PREFIX + 'v37'"));
        for (String asset : new String[] { "/styles.css?v=42", "/locale.js?v=42", "/app.js?v=45",
                "/share.css?v=33", "/share.js?v=33" }) {
            assertTrue(worker.contains("'" + asset + "'"), asset);
        }
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
        assertTrue(index.contains("locale.js?v=42"));
        assertTrue(shareHtml.contains("/share.css?v=33"));
        assertTrue(shareHtml.contains("/locale.js?v=42"));
        assertFalse(shareHtml.contains("/locale.js?v=33"));
        assertTrue(shareHtml.contains("/share.js?v=33"));
        assertFalse(index.contains("?v=35"));
        assertFalse(shareHtml.contains("?v=29"));
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
    void compactActionGridsRetainEqualLocalizedTypographyAndSemanticIcons() throws IOException {
        String css = resource("styles.css");
        String twoHundredPercent = css.substring(css.lastIndexOf("@media(max-width:220px)"));

        assertTrue(css.contains("--icon-yes:#188A50"));
        assertTrue(css.contains("--icon-no:#C83F4D"));
        assertTrue(css.contains("--icon-unsure:#9A6B00"));
        assertTrue(css.contains("--icon-yes-on-dark:#72E3A5"));
        assertTrue(css.contains("--icon-no-on-dark:#FF8F99"));
        assertTrue(css.contains("--icon-unsure-on-dark:#D0A13A"));
        assertTrue(css.contains(".answer-choice-grid{grid-template-columns:repeat(3,minmax(44px,1fr))}"));
        assertTrue(css.contains(".two-choice-grid,.confirm-assistance-actions,.short-action-grid,.screen-confirm .confirm-choice-actions{display:grid;grid-template-columns:repeat(2,minmax(44px,1fr))}"));
        assertTrue(css.contains(".step-assistance-actions{grid-template-columns:repeat(3,minmax(44px,1fr))}"));
        assertTrue(css.contains(".action-full-row,.primary-action-row,.danger-action-row{grid-column:1/-1}"));
        assertTrue(css.contains("--full-row-control-font-size:clamp(28px,7vw,32px)"));
        assertTrue(css.contains("--multi-control-font-size:26px"));
        assertTrue(css.contains("html[lang=\"en\"]{--multi-control-font-size:25px}"));
        assertTrue(css.contains("html[lang=\"es\"]{--multi-control-font-size:25px}"));
        assertTrue(css.contains(":root{--full-row-control-font-size:22px;--multi-control-font-size:20px;--action-control-icon-size:32px"));
        assertTrue(css.contains("html[lang=\"en\"]{--multi-control-font-size:18px}"));
        assertTrue(css.contains(".journey-problem-connection-actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))"));
        assertFalse(css.contains("@media(max-width:520px){.journey-problem-connection-actions"));
        assertTrue(twoHundredPercent.contains(".journey-problem-connection-actions{grid-template-columns:minmax(0,1fr)}"));
        assertFalse(css.contains(".airport-resource-actions{grid-template-columns:minmax(0,1fr)}"));
        assertTrue(css.contains(".airport-resource-actions{grid-template-columns:repeat(2,minmax(0,1fr));gap:0}"));
        assertTrue(css.contains(".airport-resource-button{min-height:104px;flex-direction:column;gap:4px;padding-inline:4px;font-size:20px"));
        assertTrue(css.contains(".airport-resource-button{min-height:104px;padding:8px 0;font-size:20px"));
        assertTrue(css.contains("html[lang=\"en\"] .airport-resource-button,html[lang=\"es\"] .airport-resource-button{font-size:18px}"));
        assertTrue(css.contains(".airport-resource-button svg{width:32px;height:32px}"));
        assertFalse(css.contains("letter-spacing:-.08em"));
        assertFalse(css.contains(".scenario-actions button{font-size:13px}"));
        assertFalse(css.contains(".screen h1,.screen h2,.screen-step h1,.departure-passed-confirmation h1,.airport-resource-qa h2{font-size:22px}"));
        assertFalse(css.contains(".review-candidate span,.segment-option>span{font-size:16px}"));
        assertTrue(css.contains(".welcome-speech-note{flex-direction:column;align-items:flex-start;gap:6px}"));
        assertTrue(css.contains(".screen-step.is-departure-exception .step-assistance-actions{grid-template-columns:minmax(0,1fr)}"));
        assertTrue(css.contains(".site-header{height:68px}"));
        assertTrue(css.contains(".screen:not(.screen-welcome),.screen-step,.screen-confirm{padding-top:20px}"));
        assertTrue(css.contains(".section-heading{margin-bottom:22px}"));
        assertTrue(css.contains(".section-heading .eyebrow,.eyebrow{margin-bottom:9px}"));
        assertTrue(css.contains(".site-header{height:56px}"));
        assertTrue(css.contains(".screen:not(.screen-welcome),.screen-step,.screen-confirm{padding-top:10px}"));
        assertTrue(css.contains("--action-control-icon-size:clamp(40px,10vw,48px)"));
        assertTrue(css.contains(".button,.journey-primary-action .journey-primary,.journey-secondary-actions-single .button{min-height:72px;padding-block:14px;padding-inline:18px;font-size:var(--full-row-control-font-size);line-height:1.12}"));
        assertTrue(css.contains(".home-back-button,.back-button{min-height:48px;padding-block:8px;font-size:clamp(24px,4vw,26px)"));
        assertTrue(css.contains(".card-close,.text-button,.support-button,.journey-end-action .end-journey-link{min-height:48px;padding-block:8px;font-size:clamp(22px,3vw,24px)"));
        assertTrue(css.contains(".language-select{min-height:48px;font-size:clamp(22px,3vw,24px)"));
        assertTrue(css.contains(".brand{min-width:44px}"));
        assertTrue(css.contains(".answer-choice-grid,.two-choice-grid,.confirm-assistance-actions,.short-action-grid,.step-assistance-actions,.family-actions,.manual-actions.is-partial-document,.journey-secondary-actions:not(.journey-secondary-actions-single),.journey-problem-connection-actions{font-size:var(--multi-control-font-size)}"));
        assertTrue(css.contains(".journey-problem-connection-actions .button{width:100%;max-width:none;font-size:inherit"));
        assertTrue(css.contains(".answer-choice-grid .button,.step-assistance-actions .assistance-button{min-width:44px;min-height:104px"));
        assertTrue(css.contains(".manual-actions.is-partial-document .button{width:100%;max-width:none;min-height:76px;padding:12px;font-size:inherit"));
        assertTrue(css.contains(".short-action-grid .action-full-row{min-height:72px;padding:14px 18px;font-size:var(--full-row-control-font-size)"));
        assertTrue(css.contains(".button-icon,.button-icon.outline,.icon-button svg,.support-button svg,.staff-help-button .staff-help-icon,.journey-end-action .end-journey-link svg{width:var(--action-control-icon-size);height:var(--action-control-icon-size)"));
        assertTrue(css.contains(".button-primary .icon-yes{color:var(--icon-yes-on-dark)}"));
        assertTrue(css.contains(".button-primary .icon-no{color:var(--icon-no-on-dark)}"));
        assertTrue(css.contains(".button-primary .icon-unsure{color:var(--icon-unsure-on-dark)}"));
        assertTrue(css.contains(".staff-help-button .staff-icon-person{color:var(--staff-person-on-dark);stroke:var(--staff-person-on-dark)}"));
        assertTrue(css.contains(".staff-icon-question{color:var(--icon-unsure);stroke:var(--icon-unsure)}"));
        assertTrue(css.contains(".button-primary.staff-help-button .staff-icon-question,.step-assistance-actions .staff-help-button .staff-icon-question{color:var(--icon-unsure-on-dark);stroke:var(--icon-unsure-on-dark)}"));
        assertFalse(css.contains("}.staff-help-button .staff-icon-question{color:var(--icon-unsure-on-dark)"));
        assertTrue(css.contains("stroke-width:2.9"));
        assertTrue(css.contains(".review-candidate-list{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}"));
        assertTrue(css.contains(".review-candidate{display:flex;align-items:center;justify-content:center"));
        assertTrue(css.contains(".manual-actions.is-partial-document{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))"));
        assertTrue(css.contains(":root{--action-control-icon-size:40px;--action-control-text-icon-size:32px}"));
        assertTrue(css.contains(".dialog-speech-button{width:100%;min-height:72px"));
        assertTrue(css.contains(".answer-choice-grid .answer-choice>span,.step-assistance-actions .assistance-button>span{font-size:inherit;white-space:normal}"));
        assertTrue(css.contains("@media(max-width:280px)"));
        assertTrue(css.contains(".answer-choice-grid,.step-assistance-actions,.step-assistance-actions.share-unavailable{grid-template-columns:minmax(0,1fr)}"));
        assertFalse(css.matches("(?s).*\\.step-assistance-actions[^{}]*:nth-child.*"));
    }

    @Test
    void questionAndAssistanceMarkupUsesExplicitGridsAndInlineSvgSymbols() throws IOException {
        String html = resource("index.html");
        String welcome = html.substring(html.indexOf("data-screen=\"welcome\""), html.indexOf("data-screen=\"upload\""));

        assertFalse(welcome.contains("data-action=\"family\""));
        assertTrue(html.contains("class=\"step-actions answer-choice-grid\" data-checkin-choices"));
        assertTrue(html.contains("class=\"choice-actions confirm-choice-actions two-choice-grid\""));
        assertFalse(html.contains("data-checkin-preset"));
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
        String css = resource("styles.css");

        int staffActionCount = html.split("data-action=\"staff\"", -1).length - 1;
        assertEquals(6, staffActionCount);
        int documentHelpCount = html.split("data-action=\"manual-document-help\"", -1).length - 1;
        assertEquals(1, documentHelpCount);
        assertEquals(staffActionCount + documentHelpCount, html.split("staff-help-button", -1).length - 1);
        assertEquals(staffActionCount + documentHelpCount,
                html.split("class=\"staff-help-icon\" viewBox=\"0 0 26 24\" aria-hidden=\"true\" focusable=\"false\"", -1).length - 1);
        assertEquals(staffActionCount + documentHelpCount, html.split("class=\"staff-icon-person\"", -1).length - 1);
        assertEquals(staffActionCount + documentHelpCount,
                html.split("class=\"staff-icon-question\" d=\"M17 8a3 3 0 1 1 2.2 2.9c-.8.3-1.2.8-1.2 1.6M18 16h.01\"", -1).length - 1);
        assertTrue(html.contains("class=\"button button-secondary button-large staff-help-button\" type=\"button\" data-action=\"staff\""));
        assertTrue(html.contains("class=\"button button-primary button-large staff-help-button\" type=\"button\" data-action=\"staff\""));
        assertTrue(html.contains("class=\"support-button assistance-button staff-help-button\" type=\"button\" data-action=\"staff\""));
        assertTrue(html.contains("class=\"button button-secondary button-large action-full-row staff-help-button\" type=\"button\" data-action=\"staff\""));
        assertTrue(css.contains("--staff-person-on-dark:#A7C7E3"));
        assertTrue(css.contains("--icon-unsure:#9A6B00"));
        assertTrue(css.contains("--icon-unsure-on-dark:#D0A13A"));
        int semanticRuleStart = css.indexOf(".staff-help-button{");
        int semanticRuleEnd = css.indexOf('}', semanticRuleStart);
        assertTrue(semanticRuleStart >= 0 && semanticRuleEnd > semanticRuleStart);
        String semanticRule = css.substring(semanticRuleStart, semanticRuleEnd);
        assertFalse(semanticRule.contains("background:"));
        assertFalse(semanticRule.contains("border-color:"));
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

        assertEquals(7, html.split("class=\"speech-icon\" viewBox=\"2 4 20 16\"", -1).length - 1);
        assertEquals(4, html.split("viewBox=\"2.5 3 19 16.5\"", -1).length - 1);
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
        assertTrue(css.contains(".two-choice-grid .icon-button,.screen-confirm .confirm-choice-actions .icon-button,.confirm-assistance-actions .assistance-button"));
        assertTrue(css.contains(".date-control-value{padding-inline:18px}"));
        assertTrue(css.contains("white-space:nowrap;overflow:visible;text-overflow:clip"));
        assertTrue(css.contains(".screen-confirm .route-summary{grid-template-columns:minmax(0,1fr) 14px minmax(0,1fr);gap:4px}"));
        assertTrue(css.contains(".screen-confirm .airport-code{font-size:20px;letter-spacing:0}"));
        assertTrue(css.contains(".screen-confirm .flight-details-core{grid-template-columns:minmax(0,1fr)}"));
        assertTrue(css.contains(".screen h1,.screen h2,.section-heading>*,.confirm-card h1,.confirm-card p{min-width:0;max-width:100%;word-break:normal;overflow-wrap:normal;hyphens:none}"));
        assertTrue(css.contains("height:auto;min-height:76px;overflow:visible;flex-direction:column"));
        assertTrue(css.contains("width:var(--action-control-icon-size);height:var(--action-control-icon-size);min-height:var(--action-control-icon-size)"));
        assertTrue(css.contains("position:static;background:transparent;padding:0;font-weight:800"));
        assertTrue(css.contains("button,.button,.support-button,.text-button,input,select{box-sizing:border-box;min-width:0;max-width:100%"));
    }

    @Test
    void automaticAndManualSpeechShareOneBuilderAndDeduplicateViewEntries() throws IOException {
        String js = resource("app.js");
        String html = resource("index.html");
        String builder = js.substring(js.indexOf("function buildSpeechTextForCurrentView()"),
                js.indexOf("function updateSpeechButtons()"));

        assertTrue(js.contains("function buildSpeechTextForCurrentView()"));
        assertTrue(js.contains("startSpeechText(buildSpeechTextForCurrentView(), automatic);"));
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
    void journeyKeepsMapAndContactWhileHelpCardKeepsOnlyContactAndClose() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");
        String css = resource("styles.css");
        String overlay = html.substring(html.indexOf("data-staff-overlay"), html.indexOf("data-family-overlay"));
		String journeyResources = html.substring(html.indexOf("class=\"airport-resource-actions\""),
				html.indexOf("data-time-status", html.indexOf("class=\"airport-resource-actions\"")));
		String helpTopbar = overlay.substring(overlay.indexOf("staff-card-topbar"),
				overlay.indexOf("data-staff-card-view"));

        assertEquals(1, overlay.split("role=\"dialog\"", -1).length - 1);
        assertTrue(overlay.contains("data-staff-card-view"));
        assertTrue(overlay.contains("data-airport-resource-view"));
		assertTrue(journeyResources.contains("data-action=\"open-airport-map\""));
		assertTrue(journeyResources.contains("data-action=\"contact-airport-resource\""));
        assertTrue(overlay.contains("staff-card-topbar"));
		assertTrue(helpTopbar.contains("data-staff-contact-button"));
		assertTrue(helpTopbar.indexOf("data-staff-contact-button") < helpTopbar.indexOf("data-action=\"close-staff\""));
		assertEquals(1, overlay.split("data-staff-contact-button", -1).length - 1);
		assertEquals(1, overlay.split("data-action=\"close-staff\"", -1).length - 1);
		assertFalse(overlay.contains("staff-card-secondary-actions"));
		assertFalse(helpTopbar.contains("open-airport-map"));
		assertFalse(helpTopbar.contains("viewAirportMap"));
		assertTrue(css.contains(".staff-card-topbar{display:grid;grid-template-columns:minmax(0,1fr) 56px"));
		assertTrue(css.contains(".staff-card-topbar{position:sticky;top:0"));
		assertTrue(css.contains("@media(max-width:420px)"));
		assertTrue(css.contains(".staff-card-topbar{grid-template-columns:minmax(0,1fr) 56px;gap:8px}"));
		assertTrue(css.contains(".staff-card-topbar{grid-template-columns:minmax(0,1fr) 56px;gap:2px}"));
		assertTrue(css.contains(".staff-contact-button{padding-left:2px;padding-right:2px;gap:4px;font-size:18px}"));
		assertTrue(css.contains("html{min-width:0;overflow-x:hidden}"));
		assertTrue(css.contains(".staff-contact-button{width:100%"));
		assertTrue(css.contains(".staff-card .staff-overlay-close{display:block;grid-column:2"));
		assertTrue(overlay.contains("data-airport-map-result target=\"_blank\" rel=\"noopener noreferrer\""));
        assertTrue(overlay.contains("data-airport-contact-result target=\"_blank\" rel=\"noopener noreferrer\""));
        assertTrue(overlay.contains("data-action=\"back-to-staff-card\""));
        assertFalse(overlay.contains("copy-staff"));
        assertFalse(overlay.contains("find-staff"));
        assertFalse(overlay.contains("extra-assistance"));
        assertFalse(js.contains("function staffCardText("));
        assertFalse(js.contains("function copyText("));
        assertFalse(js.contains("copyCardAria"));
        assertTrue(js.contains("staffOverlayView = 'CARD'"));
		assertTrue(js.contains("airportResourceOrigin = origin"));
		assertTrue(js.contains("if (mode === 'MAP' && origin !== 'JOURNEY_STEP') return"));
        assertTrue(js.contains("airportResourceState = 'IDLE'"));
        assertTrue(js.contains("airportResourceGeneration"));
        assertTrue(js.contains("/api/assistance/airport-resources"));
		assertFalse(js.contains("data-qa-airport-debug"));
		assertFalse(js.contains("qaAirportDebug"));
		assertFalse(js.contains("qa-airport-debug"));
		String qaResult = js.substring(js.indexOf("function renderAirportQaResult("),
				js.indexOf("function appendQaHeading("));
		assertFalse(qaResult.contains("document.createElement('details')"));
		assertFalse(qaResult.contains("serpApiRequestCount"));
    }

    @Test
    void staffSpeechIsExactSingleAndCancelableWhileConfirmationReadsDisplayedOperationalFacts() throws IOException {
        String js = resource("app.js");

        assertTrue(js.contains("staffCardInstruction: '请把这个页面给工作人员看。需要时可联系机场。'"));
        assertTrue(js.contains("staffCardInstruction: 'Please show this screen to a member of staff. Contact the airport if needed.'"));
        assertTrue(js.contains("staffCardInstruction: 'Muestre esta pantalla a un miembro del personal. Contacte con el aeropuerto si lo necesita.'"));
        assertTrue(js.contains("function startSpeechText(text, automatic, localeOverride)"));
        String speechStarter = js.substring(js.indexOf("function startSpeechText(text, automatic, localeOverride)"),
                js.indexOf("function startSpeech(automatic)"));
        assertTrue(speechStarter.contains("if (!speechIsSupported())"));
        assertTrue(speechStarter.contains("new SpeechSynthesisUtterance(text)"));
        assertTrue(speechStarter.contains("window.speechSynthesis.speak(utterance)"));

        String showStaff = js.substring(js.indexOf("function showStaff(context)"),
                js.indexOf("function closeStaff()"));
        assertTrue(showStaff.indexOf("stopSpeech(false)") < showStaff.indexOf("openDialog("));
        assertTrue(showStaff.indexOf("openDialog(") < showStaff.indexOf("startSpeechText(t('staffCardInstruction')"));
        assertEquals(1, showStaff.split("startSpeechText\\(", -1).length - 1);
        String closeStaff = js.substring(js.indexOf("function closeStaff()"),
                js.indexOf("function openDialog("));
        assertTrue(closeStaff.contains("stopSpeech(false)"));

        String builder = js.substring(js.indexOf("function buildSpeechTextForCurrentView()"),
                js.indexOf("function updateSpeechButtons()"));
        String confirmSpeech = builder.substring(builder.indexOf("if (currentScreen === 'confirm' && currentFlight)"),
                builder.indexOf("if (currentScreen === 'step' && currentAction)"));
        assertTrue(confirmSpeech.contains("[data-terminal]"));
        assertTrue(confirmSpeech.contains("[data-gate]"));
        assertTrue(confirmSpeech.contains("[data-boarding-time]"));
        assertTrue(confirmSpeech.contains("spokenOperationalFact(t('terminal'), terminal, true)"));
        assertTrue(confirmSpeech.contains("spokenOperationalFact(t('gate'), gate, true)"));
        assertTrue(confirmSpeech.contains("spokenOperationalFact(t('boardingTime'), boardingTime, false)"));
        String displayedFact = js.substring(js.indexOf("function spokenConfirmValue("),
                js.indexOf("function buildSpeechTextForCurrentView()"));
        assertTrue(displayedFact.contains("value === '—'"));
        assertTrue(displayedFact.contains("t('notPublished')"));
        assertTrue(js.contains("formatTerminalValue("));
        assertTrue(js.contains("formatGateValue("));
        assertTrue(js.contains("formatAtDeparture("));
        assertFalse(confirmSpeech.contains("localeText('航站楼 ', 'Terminal ', 'Terminal ') + terminal"));
        assertFalse(confirmSpeech.contains("localeText('登机口 ', 'Gate ', 'Puerta ') + gate"));
    }

    @Test
    void documentReviewUsesOneStatusOneHelpEntryAndValueOnlyCandidates() throws IOException {
        String js = resource("app.js");
        String html = resource("index.html");
        String manualRender = js.substring(js.indexOf("function renderManualDocumentEntry()"),
                js.indexOf("function syncUsableDocumentSegmentIndexes()"));
        String candidateRender = js.substring(js.indexOf("function renderManualReviewCandidates("),
                js.indexOf("function selectDocumentReviewCandidate("));

        assertEquals(1, html.split("data-manual-status", -1).length - 1);
        assertFalse(html.contains("data-manual-partial-notice"));
        assertFalse(html.contains("data-document-advisory"));
        assertFalse(js.contains("function renderFieldStaffHelp("));
        assertFalse(js.contains("button.dataset.action = 'field-staff'"));
        assertTrue(html.indexOf("data-action=\"manual-document-help\"")
                < html.indexOf("data-manual-primary"));
        assertTrue(manualRender.contains("state = flightConflict || dateConflict ? 'CONFLICT'"));
        assertTrue(manualRender.contains(": !hasFlightNumber || !hasDepartureDate ? 'MISSING'"));
        assertTrue(manualRender.contains(": flightReview || dateReview ? 'REVIEW' : 'READY'"));
        assertTrue(js.contains("fieldStaffHelp: '请工作人员帮忙'"));
        assertTrue(js.contains("fieldStaffHelp: 'Ask staff for help'"));
        assertTrue(js.contains("fieldStaffHelp: 'Pedir ayuda al personal'"));

        assertTrue(js.contains("titleZh: '请帮我确认航班号'"));
        assertTrue(js.contains("titleEn: 'Please help me confirm my flight number'"));
        assertTrue(js.contains("titleEs: 'Ayúdeme a confirmar mi número de vuelo'"));
        assertTrue(js.contains("titleZh: '请帮我确认出发日期'"));
        assertTrue(js.contains("titleEn: 'Please help me confirm my departure date'"));
        assertTrue(js.contains("titleEs: 'Ayúdeme a confirmar mi fecha de salida'"));
        assertTrue(js.contains("titleZh: '请帮我核对航班信息'"));
        assertTrue(js.contains("titleEn: 'Please help me check my flight information'"));
        assertTrue(js.contains("titleEs: 'Ayúdeme a comprobar la información de mi vuelo'"));
        assertTrue(js.contains("titleEn.hidden = currentLocale === 'en'"));
        assertTrue(js.contains("bodyEn.hidden = currentLocale === 'en'"));
        assertTrue(js.contains("startSpeechText(t('staffCardInstruction'), true)"));
        assertTrue(html.contains("data-action=\"speak\""));

        assertFalse(candidateRender.contains("reviewCandidateDetails"));
        assertFalse(candidateRender.contains("candidate.source"));
        assertFalse(candidateRender.contains("candidate.confidence"));
        assertFalse(candidateRender.contains("Math.round"));
        assertTrue(candidateRender.contains("button.dataset.candidateValue = candidate.value"));
        assertTrue(candidateRender.contains("formatFlightNumber(candidate.value)"));
        assertTrue(candidateRender.contains("manualButton.dataset.action = 'manual-review-entry'"));
        assertTrue(js.contains("部分航班信息缺失，请补全后继续。"));
        assertTrue(js.contains("manualStatusMissing: 'Some flight information is missing. Complete it to continue.'"));
        assertTrue(js.contains("manualStatusMissing: 'Falta parte de la información del vuelo. Complétala para continuar.'"));
        assertTrue(js.contains("请核对识别到的信息。"));
        assertTrue(js.contains("manualStatusReview: 'Check the information we found.'"));
        assertTrue(js.contains("manualStatusReview: 'Comprueba la información encontrada.'"));
        assertTrue(js.contains("识别到了不同的结果，请选择正确的信息。"));
        assertTrue(js.contains("manualStatusConflict: 'We found different results. Choose the correct information.'"));
        assertTrue(js.contains("manualStatusConflict: 'Encontramos resultados diferentes. Elige la información correcta.'"));
        assertFalse(js.contains("保存不会查询真实航班"));
        assertFalse(js.contains("Completing and saving do not search live flights"));
        assertFalse(js.contains("no consulta vuelos reales"));
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
    void familyShareDialogHasOnlyEqualLinkAndImageActionsWithoutPreviewOrClipboardControls() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");
        String js = resource("app.js");

        String familyOverlay = html.substring(html.indexOf("data-family-overlay"),
                html.indexOf("data-end-overlay"));
        String familyActions = familyOverlay.substring(familyOverlay.indexOf("class=\"family-actions\""),
                familyOverlay.indexOf("</div>", familyOverlay.indexOf("class=\"family-actions\"")));
        assertEquals(2, familyActions.split("<button", -1).length - 1);
        assertTrue(familyActions.contains("data-action=\"share-family\""));
        assertTrue(familyActions.contains("data-action=\"share-family-card\""));
        assertTrue(familyActions.contains("data-i18n=\"shareLink\""));
        assertTrue(familyActions.contains("data-i18n=\"sharePng\""));

        for (String removed : new String[] { "data-share-link-box", "data-family-share-url",
                "data-png-preview", "preview-family-share", "preview-family-card", "copy-family",
                "stop-family-share", "create-family-share" }) {
            assertFalse(familyOverlay.contains(removed), removed);
        }
        for (String removedCode : new String[] { "function showCachedPngPreview(",
                "function previewFamilyCard(", "dataset.action = 'preview-family-card'",
                "if (action === 'preview-family-share')", "if (action === 'preview-family-card')",
                "if (action === 'copy-family')", "if (action === 'stop-family-share')",
                "if (action === 'create-family-share')" }) {
            assertFalse(js.contains(removedCode), removedCode);
        }
        for (String removedTranslation : new String[] { "shareLinkLabel:", "createShare:",
                "previewShare:", "copyCurrent:", "stopShare:", "previewPng:", "previewPngAria:" }) {
            assertFalse(js.contains(removedTranslation), removedTranslation);
        }

        assertTrue(js.contains("shareLink: '分享链接'"));
        assertTrue(js.contains("sharePng: '分享图片'"));
        assertTrue(js.contains("shareLink: 'Share link'"));
        assertTrue(js.contains("sharePng: 'Share image'"));
        assertTrue(js.contains("shareLink: 'Compartir enlace'"));
        assertTrue(js.contains("sharePng: 'Compartir imagen'"));

        assertTrue(css.contains(".family-actions{grid-template-columns:repeat(2,minmax(44px,1fr))}"));
        assertTrue(css.contains(".family-actions .button{width:100%;min-height:76px"));
        String linkShare = js.substring(js.indexOf("function shareFamily()"),
                js.indexOf("function syncFamilyShare()"));
        assertTrue(linkShare.contains("navigator.share("));
        assertFalse(linkShare.contains("copyText("));
        assertTrue(js.contains("function shareSessionMatchesPayload(session, payload)"));
        assertTrue(js.contains("expiresAt <= Date.now()"));
        assertTrue(js.contains("familyShareJourneyKey(JSON.parse(session.lastFingerprint || 'null'))"));
        String clearJourney = js.substring(js.indexOf("function clearJourney()"),
                js.indexOf("function returnHome()"));
        assertTrue(clearJourney.contains("resetFamilyShareSession()"));
        assertTrue(clearJourney.contains("invalidatePngCard()"));
        assertTrue(clearJourney.contains("renderDeparturePassedConfirmation(null)"));
        String shareSync = js.substring(js.indexOf("function syncFamilyShare()"),
                js.indexOf("function scheduleFamilyShareSync()"));
        assertTrue(shareSync.contains("var session = shareSession"));
        assertTrue(shareSync.indexOf("if (shareSession !== session) return")
                != shareSync.lastIndexOf("if (shareSession !== session) return"));
        assertTrue(shareSync.contains("if (!shareSessionMatchesPayload(session, payload))"));
        assertTrue(shareSync.contains("resetFamilyShareSession()"));
        assertFalse(shareSync.contains("shareSession.lastFingerprint = fingerprint"));
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

    @Test
    void journeyShortLabelsKeepFullAccessibleNamesAndShareDiscardQueuesServerRevocation() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");
        String reset = js.substring(js.indexOf("function resetFamilyShareSession()"),
                js.indexOf("function revokeDemoRecordingShare()"));
        String productionSpanish = js.substring(js.indexOf("// Production-facing copy"));

        assertTrue(html.contains("data-i18n=\"journeyProblemConnectionYes\" data-i18n-aria=\"journeyProblemConnectionYesAria\""));
        assertTrue(html.contains("data-i18n=\"journeyProblemConnectionNo\" data-i18n-aria=\"journeyProblemConnectionNoAria\""));
        assertTrue(html.contains("data-i18n=\"journeyAirportMap\""));
        assertTrue(html.contains("data-i18n=\"journeyContactAirport\""));
        assertTrue(js.contains("journeyProblemConnectionYes: 'Yes', journeyProblemConnectionYesAria: 'Yes, I have another flight after this one'"));
        assertTrue(js.contains("journeyProblemConnectionNo: 'No', journeyProblemConnectionNoAria: 'No, this is my last flight'"));
        assertTrue(js.contains("journeyProblemConnectionYes: 'Sí', journeyProblemConnectionYesAria: 'Sí, tengo otro vuelo después de este'"));
        assertTrue(js.contains("journeyProblemConnectionNo: 'No', journeyProblemConnectionNoAria: 'No, este es mi último vuelo'"));
        assertTrue(js.contains("journeyAirportMap: 'Ver mapa', journeyAirportMapAria: 'Ver mapa del aeropuerto'"));
        assertTrue(js.contains("journeyContactAirport: 'Contactar', journeyContactAirportAria: 'Contactar con el aeropuerto'"));
        assertTrue(productionSpanish.contains("shareShort: 'Familia', familyAria: 'Familia, compartir el viaje en directo'"));
        assertFalse(productionSpanish.contains("shareShort: 'Compartir'"));
        assertTrue(js.contains("SHARE_REVOCATION_PENDING_KEY"));
        assertTrue(js.contains("function retryPendingShareRevocations()"));
        assertTrue(reset.indexOf("revokeDiscardedShareSession(previousSession)") < reset.indexOf("shareSession = null"));
        assertTrue(js.contains("response.ok || response.status === 404 || response.status === 410"));
        assertTrue(js.indexOf("retryPendingShareRevocations();", js.indexOf("window.addEventListener('online'"))
                < js.indexOf("retryPendingFamilyShare();", js.indexOf("window.addEventListener('online'")));
    }
}
