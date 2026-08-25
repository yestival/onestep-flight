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
        assertTrue(css.contains("--manual-control-font:800 var(--action-control-font-size)/1.05 var(--data)"));
        assertTrue(css.contains(".manual-fields input{display:block;width:100%;inline-size:100%;max-width:100%;min-width:0;min-height:60px"));
        assertTrue(css.contains("color:var(--navy);font:var(--manual-control-font)"));
        assertTrue(css.contains("font:var(--manual-control-font)"));
        assertTrue(css.contains(".date-control-value{display:block;width:100%;min-width:0;padding:0 24px;box-sizing:border-box;color:inherit;font:inherit;line-height:inherit"));
        assertFalse(css.contains("--manual-control-font:700 16px"));
        assertTrue(css.contains("input.manual-control:focus-visible,.date-control:focus-within"));
        assertTrue(css.contains("input.manual-control:user-invalid,.date-control:has(input:user-invalid)"));
        assertTrue(css.contains("input.manual-control:disabled,.date-control:has(input:disabled)"));
        assertTrue(css.contains(".date-control input[type=\"date\"]{position:absolute;inset:0"));
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
        assertTrue(js.contains("卡片图片编码失败，请重试。"));
        assertFalse(js.contains("预览后保存"));
        assertFalse(js.contains("save from the preview"));
        assertFalse(js.contains("guarda desde la vista previa"));
    }

    @Test
    void partialDocumentsAskOnlyForTheMissingFieldWithoutPreselectionDateDiscovery() throws IOException {
        String html = resource("index.html");
        String js = resource("app.js");

        assertTrue(js.contains("function classifyDocumentResult(result)"));
        assertTrue(js.contains("if (!segments.length) return { outcome: 'UNUSABLE'"));
        assertTrue(js.contains("assessment.outcome === 'UNUSABLE'"));
        assertTrue(js.contains("assessment.outcome === 'PARTIAL'"));
        String uploadFlow = js.substring(js.indexOf("function uploadDocument(file)"),
                js.indexOf("function beginFlight(flight, documentResult)"));
        assertFalse(uploadFlow.contains("discoverMissingDocumentDates("));
        assertFalse(uploadFlow.contains("/api/journeys/discover-flight-date"));
        assertTrue(uploadFlow.indexOf("if (assessment.outcome === 'UNUSABLE')")
                < uploadFlow.indexOf("if (assessment.outcome === 'PARTIAL')"));
        assertTrue(uploadFlow.contains("showPartialDocument(result, assessment)"));

        String dateDiscovery = js.substring(js.indexOf("function discoverMissingDocumentDates(result, assessment)"),
                js.indexOf("function resetDocumentResultState()"));
        assertTrue(dateDiscovery.contains("postFlightJson('/api/journeys/discover-flight-date'"));
        assertTrue(dateDiscovery.contains("flightNumber: normalizeFlightNumber(segments[index].flightNumber)"));
        assertTrue(dateDiscovery.contains("document: documentForDiscovery"));
        assertTrue(dateDiscovery.contains("segmentIndex: index"));
        assertTrue(js.contains("showPartialDocument(result, assessment)"));
        assertTrue(js.contains("documentSegments = assessment.segments.slice()"));
        assertTrue(js.contains("panel.hidden = !partialDocumentEntry || !documentSegments.length"));
        assertTrue(js.contains("flightField.hidden = hasFlightNumber && !hasDepartureDate"));
        assertTrue(js.contains("dateField.hidden = hasDepartureDate && !hasFlightNumber"));
        assertTrue(html.contains("data-screen=\"document-unusable\""));
        assertTrue(html.contains("data-action=\"choose-other-file\""));
        assertTrue(html.contains("data-manual-partial-notice"));
        assertTrue(html.contains("data-manual-document-segments"));
        assertTrue(html.contains("data-manual-segment-options"));
        assertTrue(html.contains("data-manual-flight-field"));
        assertTrue(html.contains("data-manual-date-field"));
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
        assertFalse(js.contains("selectedDocumentSegmentIndex = usableDocumentSegmentIndexes[0]"));
        assertTrue(js.contains("function showDocumentSegmentSelection(result, assessment)"));
        assertTrue(js.contains("function selectDocumentSegment(index, options)"));
        assertTrue(js.contains("function returnToDocumentSegmentSelection()"));
        assertTrue(js.contains("selectedDocumentSegmentIndex = null"));
        assertTrue(js.contains("awaitingDocumentSelection = true"));
        assertTrue(js.contains("currentFlight = null"));
        assertTrue(js.contains("item.dataset.action = 'select-segment'"));
        assertTrue(js.contains("item.disabled = !usable || lookupInFlight"));
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
        assertTrue(itinerary.contains("item.disabled = !usable"));
        String uploadFlow = js.substring(js.indexOf("function uploadDocument(file)"),
                js.indexOf("function beginFlight(flight, documentResult)"));
        assertTrue(uploadFlow.contains("if (assessment.segments.length > 1)"));
        assertTrue(uploadFlow.indexOf("result = normalizeDocumentResult(result)")
                < uploadFlow.indexOf("var assessment = classifyDocumentResult(result)"));
        assertTrue(uploadFlow.indexOf("var assessment = classifyDocumentResult(result)")
                < uploadFlow.indexOf("showDocumentSegmentSelection(result, assessment)"));
        assertFalse(uploadFlow.contains("discoverMissingDocumentDates("));
        assertFalse(uploadFlow.contains("/api/journeys/discover-flight-date"));
        String multiSegmentBranch = uploadFlow.substring(
                uploadFlow.indexOf("if (assessment.segments.length > 1)"),
                uploadFlow.indexOf("if (assessment.outcome === 'UNUSABLE')"));
        assertTrue(multiSegmentBranch.contains("showDocumentSegmentSelection(result, assessment)"));
        assertTrue(multiSegmentBranch.contains("return"));
        assertFalse(multiSegmentBranch.contains("discoverMissingDocumentDates("));
        assertFalse(multiSegmentBranch.contains("selectDocumentSegment("));
        assertFalse(multiSegmentBranch.contains("postFlightJson("));
        assertFalse(multiSegmentBranch.contains("resolveUploadedFlight("));
        assertFalse(multiSegmentBranch.contains("beginFlight("));
        String selectionFlow = js.substring(js.indexOf("function selectDocumentSegment(index, options)"),
                js.indexOf("function uploadDocument(file)"));
        assertTrue(selectionFlow.indexOf("selectedDocumentSegmentIndex = index")
                < selectionFlow.indexOf("beginFlight(extractedFlight, selectedResult)"));
        assertTrue(selectionFlow.indexOf("beginFlight(extractedFlight, selectedResult)")
                < selectionFlow.indexOf("return resolveUploadedFlight("));
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
    void documentSegmentsAreNormalizedBeforeTheirFirstClassification() throws IOException {
        String js = resource("app.js");
        String normalization = js.substring(js.indexOf("function normalizeDocumentSegment(segment)"),
                js.indexOf("function classifyDocumentResult(result)"));
        String uploadFlow = js.substring(js.indexOf("function uploadDocument(file)"),
                js.indexOf("function beginFlight(flight, documentResult)"));

        assertTrue(normalization.contains("segment.departureDate != null"));
        assertTrue(normalization.contains("String(segment.scheduledDeparture || '').trim()"));
        assertTrue(normalization.contains("validDocumentDepartureDate(match[1])"));
        assertTrue(normalization.contains("Object.assign({}, segment, { departureDate: match[1] })"));
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
                uploadFlow.indexOf("if (assessment.segments.length > 1)"),
                uploadFlow.indexOf("if (assessment.outcome === 'UNUSABLE')"));
        assertTrue(multiSegmentBranch.contains("showDocumentSegmentSelection(result, assessment)"));
        assertFalse(multiSegmentBranch.contains("discoverMissingDocumentDates("));
        assertFalse(multiSegmentBranch.contains("resolveUploadedFlight("));
    }

    @Test
    void welcomeSpeechNoticeFallbackCopyAndRestoreContractAreLocalizedAndSafe() throws IOException {
        String html = resource("index.html");
        String css = resource("styles.css");
        String js = resource("app.js");

        assertTrue(html.contains("class=\"welcome-speech-note\""));
        assertTrue(html.contains("data-i18n=\"welcomeSpeechNotice\""));
        assertTrue(html.contains("Voice guidance will play as you continue. Please check your volume. You can pause or turn it off at any time."));
        assertTrue(js.contains("接下来会播放语音提示，请留意音量。你可以随时暂停或关闭声音。"));
        assertTrue(js.contains("Voice guidance will play as you continue. Please check your volume. You can pause or turn it off at any time."));
        assertTrue(js.contains("La guía de voz se reproducirá durante el proceso. Compruebe el volumen. Puede pausarla o desactivarla en cualquier momento."));
        assertTrue(js.contains("upload: '上传出行文件'"));
        assertTrue(js.contains("uploadHint: '支持行程单、电子客票、订单截图或登机牌'"));
        assertTrue(js.contains("upload: 'Upload travel document'"));
        assertTrue(js.contains("uploadHint: 'Supports itineraries, e-tickets, order screenshots, or boarding passes'"));
        assertTrue(js.contains("upload: 'Subir documento de viaje'"));
        assertTrue(js.contains("uploadHint: 'Admite itinerarios, billetes electrónicos, capturas del pedido o tarjetas de embarque'"));
        assertTrue(html.contains("data-i18n=\"upload\">Upload travel document"));
        assertTrue(html.contains("data-i18n=\"uploadHint\">Supports itineraries, e-tickets, order screenshots, or boarding passes"));
        assertEquals(3, js.split("welcomeSpeechNotice:", -1).length - 1);
        assertEquals(3, js.split("liveStatusUnavailable:", -1).length - 1);
        assertTrue(html.contains("data-live-status-unavailable"));
        assertTrue(js.contains("暂时没有更新到实时航班状态。请确认下面是不是你的航班。"));
        assertTrue(js.contains("Live flight status has not updated yet. Please confirm whether the itinerary below is yours."));
        assertTrue(css.contains(".welcome-speech-note"));
        assertTrue(js.contains("flight.liveDataSource === 'DOCUMENT_FALLBACK'"));
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
    void liveProviderFailuresUseSpecificCopyAndLocalhostOnlySanitizedDebugDetails() throws IOException {
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
        assertFalse(errorScreen.contains("data-action=\"staff\""));
        assertTrue(fetchJson.contains("error.apiError = payload"));
        assertTrue(fetchJson.contains("error.status = response.status"));
        assertTrue(fetchJson.contains("error.requestUrl = url"));
        assertTrue(errorHandling.contains("details && details.code"));
        assertTrue(errorHandling.contains("details && details.providerResults"));
        assertTrue(errorHandling.contains("details && details.messageZh"));
        assertTrue(errorHandling.contains("details && details.messageEn"));
        assertTrue(errorHandling.contains("FLIGHT_NOT_FOUND"));
        assertTrue(errorHandling.contains("未找到该日期的航班"));
        assertTrue(errorHandling.contains("normalizedCode.indexOf('INVALID_') === 0 || status === 400"));
        assertTrue(errorHandling.contains("航班号或日期有误"));
        assertTrue(errorHandling.contains("status === 429"));
        assertTrue(errorHandling.contains("查询过于频繁，或外部数据源已达到限制"));
        assertTrue(errorHandling.contains("ALL_LIVE_FLIGHT_PROVIDERS_FAILED"));
        assertTrue(errorHandling.contains("status === 502"));
        assertTrue(errorHandling.contains("实时航班数据源暂时不可用"));
        assertTrue(errorHandling.contains("CLIENT_FLIGHT_TIMEOUT"));
        assertTrue(errorHandling.contains("/TIMEOUT/"));
        assertTrue(errorHandling.contains("实时数据源响应超时"));
        assertTrue(errorHandling.contains("实时航班状态暂未更新"));
        assertTrue(errorHandling.contains("localhost|127\\.0\\.0\\.1|\\[::1\\]"));
        assertTrue(errorHandling.contains("AeroDataBox provider result"));
        assertTrue(errorHandling.contains("SerpApi provider result"));
        assertTrue(errorHandling.contains("HTTP status"));
        assertTrue(errorHandling.contains("Error code"));
        assertTrue(errorHandling.contains("Backend message (zh)"));
        assertTrue(errorHandling.contains("Backend message (en)"));
        assertTrue(errorHandling.contains("details.hidden = !showDebug"));
        assertTrue(errorHandling.contains("content.textContent = showDebug ? lines.join('\\n') : ''"));
        assertFalse(safeProviderDebug.contains("sourceUrl"));
        assertFalse(safeProviderDebug.toLowerCase().contains("apikey"));
        assertFalse(safeProviderDebug.toLowerCase().contains("authorization"));
        assertFalse(errorHandling.contains("setStaffContent("));
        assertTrue(html.contains("data-i18n=\"errorTitle\">实时航班状态暂未更新"));
        assertTrue(html.contains("data-i18n=\"technicalDetails\">调试详情"));
        assertTrue(js.contains("translations.en.technicalDetails = 'Debug details'"));
    }

    @Test
    void refreshHasNoTimeGateButKeepsOfflineAndInFlightProtection() throws IOException {
        String js = resource("app.js");
        String refreshFlight = js.substring(js.indexOf("function refreshFlight()"),
                js.indexOf("function beginNewFlightInformation()"));
        String refreshArrival = js.substring(js.indexOf("function refreshArrivalInfo()"),
                js.indexOf("function maybeRefreshArrivalOnEntry()"));
        String refreshControls = js.substring(js.indexOf("function updateRefreshControls()"),
                js.indexOf("function setDeparturePassedStaffContent()"));
        String returnRefresh = js.substring(js.indexOf("function refreshAfterReturnToPage()"),
                js.indexOf("window.addEventListener('focus'"));
        String persistence = js.substring(js.indexOf("function persistJourney()"),
                js.indexOf("function pick(source, fields)"));
        String removedStatus = "REFRESH_" + "COOL" + "DOWN";
        String removedTimestamp = "lastRefresh" + "AttemptAt";

        assertFalse(js.contains(removedStatus + "_MS"));
        assertFalse(js.contains(removedTimestamp));
        assertFalse(js.contains(removedStatus));
        assertFalse(js.contains("function beginRefresh" + "Attempt()"));
        assertFalse(js.contains("function updateRefreshButton" + "Cool" + "down()"));
        assertTrue(refreshFlight.contains("if (!currentFlight || flightRequestController) return"));
        assertTrue(refreshFlight.contains("if (!navigator.onLine)"));
        assertTrue(refreshFlight.contains("finally(updateRefreshControls)"));
        assertFalse(refreshFlight.contains("Date.now()"));
        assertTrue(refreshArrival.contains("flightRequestController"));
        assertTrue(refreshArrival.contains("if (!navigator.onLine)"));
        assertTrue(refreshArrival.contains("finally(updateRefreshControls)"));
        assertTrue(refreshControls.contains("!navigator.onLine || Boolean(flightRequestController)"));
        assertTrue(returnRefresh.contains("|| flightRequestController) return"));
        assertFalse(returnRefresh.contains("Date.now()"));
        assertFalse(persistence.toLowerCase().contains("cool" + "down"));
        assertTrue(js.contains("restorableRefreshStatus(saved.refreshStatusState)"));
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
        assertTrue(html.contains("styles.css?v=26"));
        assertTrue(html.contains("locale.js?v=26"));
        assertTrue(html.contains("app.js?v=26"));
        assertFalse(html.contains("?v=25"));
        assertTrue(worker.contains("CACHE_PREFIX + 'v26'"));
        assertFalse(worker.contains("CACHE_PREFIX + 'v25'"));
        for (String asset : new String[] { "/styles.css?v=26", "/locale.js?v=26", "/app.js?v=26",
                "/share.css?v=26", "/share.js?v=26" }) {
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
        assertTrue(index.contains("locale.js?v=26"));
        assertTrue(shareHtml.contains("/share.css?v=26"));
        assertTrue(shareHtml.contains("/locale.js?v=26"));
        assertTrue(shareHtml.contains("/share.js?v=26"));
        assertFalse(index.contains("?v=25"));
        assertFalse(shareHtml.contains("?v=25"));
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
        assertEquals(5, staffActionCount);
        assertEquals(staffActionCount, html.split("staff-help-button", -1).length - 1);
        assertEquals(staffActionCount,
                html.split("class=\"staff-help-icon\" viewBox=\"0 0 26 24\" aria-hidden=\"true\" focusable=\"false\"", -1).length - 1);
        assertEquals(staffActionCount, html.split("class=\"staff-icon-person\"", -1).length - 1);
        assertEquals(staffActionCount,
                html.split("class=\"staff-icon-question\" d=\"M17 8a3 3 0 1 1 2.2 2.9c-.8.3-1.2.8-1.2 1.6M18 16h.01\"", -1).length - 1);
        assertTrue(html.contains("class=\"button button-secondary button-large staff-help-button\" type=\"button\" data-action=\"staff\""));
        assertTrue(html.contains("class=\"button button-primary button-large staff-help-button\" type=\"button\" data-action=\"staff\""));
        assertTrue(html.contains("class=\"support-button assistance-button staff-help-button\" type=\"button\" data-action=\"staff\""));
        assertTrue(html.contains("class=\"button button-secondary button-large action-full-row staff-help-button\" type=\"button\" data-action=\"staff\""));
        assertTrue(css.contains("--staff-person-on-dark:#A7C7E3"));
        assertTrue(css.contains("--icon-unsure-on-dark:#FFD166"));
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
    void staffSpeechIsExactSingleAndCancelableWhileConfirmationReadsDisplayedOperationalFacts() throws IOException {
        String js = resource("app.js");

        assertTrue(js.contains("staffCardInstruction: '请把这个页面拿给工作人员看'"));
        assertTrue(js.contains("staffCardInstruction: 'Please show this page to a member of staff.'"));
        assertTrue(js.contains("staffCardInstruction: 'Por favor, muestra esta página a un miembro del personal.'"));
        assertTrue(js.contains("function startSpeechText(text, automatic)"));
        String speechStarter = js.substring(js.indexOf("function startSpeechText(text, automatic)"),
                js.indexOf("function startSpeech(automatic)"));
        assertTrue(speechStarter.contains("if (!speechIsSupported())"));
        assertTrue(speechStarter.contains("new SpeechSynthesisUtterance(text)"));
        assertTrue(speechStarter.contains("window.speechSynthesis.speak(utterance)"));

        String showStaff = js.substring(js.indexOf("function showStaff()"),
                js.indexOf("function closeStaff()"));
        assertTrue(showStaff.indexOf("stopSpeech(false)") < showStaff.indexOf("openDialog("));
        assertTrue(showStaff.indexOf("openDialog(") < showStaff.indexOf("startSpeechText(t('staffCardInstruction'), true)"));
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
        assertTrue(css.contains(".family-actions .button{width:100%;min-height:64px"));
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
}
