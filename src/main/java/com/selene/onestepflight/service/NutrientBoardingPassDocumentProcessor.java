package com.selene.onestepflight.service;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.BoundingBox;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.DocumentFlightSegment.DateSource;
import com.selene.onestepflight.dto.DocumentFlightSegment.FlightNumberConfidence;
import com.selene.onestepflight.dto.DocumentFieldCandidate;
import com.selene.onestepflight.dto.DocumentFieldCandidate.AnchorType;
import com.selene.onestepflight.dto.DocumentFieldCandidate.Disposition;
import com.selene.onestepflight.dto.DocumentReviewIssue;
import com.selene.onestepflight.dto.DocumentType;
import com.selene.onestepflight.dto.ExtractedField;
import com.selene.onestepflight.service.airline.AirlineRegistry;
import com.selene.onestepflight.service.airline.AirlineResolution;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class NutrientBoardingPassDocumentProcessor implements BoardingPassDocumentProcessor {

	private static final Logger LOGGER = LoggerFactory.getLogger(NutrientBoardingPassDocumentProcessor.class);
	private static final String EXTRACT_ENDPOINT = "https://api.nutrient.io/extraction/extract";
	private static final String PARSE_ENDPOINT = "https://api.nutrient.io/extraction/parse";
	private static final String DATA_SOURCE = "NUTRIENT_DWS_DATA_EXTRACTION";
	private static final String UNDERSTAND_STAGE = "understand";
	private static final String STRUCTURE_STAGE = "structure";
	private static final String TEXT_STAGE = "text";

	private static final Pattern FLIGHT_NUMBER_PATTERN = Pattern.compile(
			"^(?:[A-Z][A-Z0-9]|[0-9][A-Z]|[A-Z][A-Z0-9]{2}|[0-9][A-Z][A-Z0-9]|[0-9]{2}[A-Z])\\d{1,4}[A-Z]?$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern FLIGHT_NUMBER_IN_TEXT = Pattern.compile(
			"(?<![A-Z0-9])((?:[A-Z][A-Z0-9]|[0-9][A-Z]|[A-Z][A-Z0-9]{2}|[0-9][A-Z][A-Z0-9]|[0-9]{2}[A-Z])\\s*[-‐‑‒–— ]?\\s*\\d{1,4}[A-Z]?)(?![A-Z0-9])",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern FLIGHT_PREFIX_FRAGMENT = Pattern.compile("^(?=.*[A-Z])[A-Z0-9]{2,3}$");
	private static final Pattern FLIGHT_SUFFIX_FRAGMENT = Pattern.compile("^\\d{1,4}[A-Z]?$" );
	private static final Pattern FLIGHT_NUMERIC_SUFFIX = Pattern.compile("(\\d{1,4}[A-Z]?)$");
	private static final Pattern AIRLINE_NAME_WITH_SUFFIX = Pattern.compile(
			"^(.*?)[\\s:：#-]+(\\d{1,4}[A-Z]?)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern IATA_IN_TEXT = Pattern.compile("(?<![A-Za-z0-9])([A-Z]{3})(?![A-Za-z0-9])");
	private static final Pattern TIME_IN_TEXT = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):[0-5]\\d(?!\\d)");
	private static final Pattern TERMINAL_IN_TEXT = Pattern.compile(
			"(?i)(?:航站楼|TERMINAL|\\bT)\\s*([0-9][A-Z]?)\\b");
	private static final Pattern SEGMENT_REVIEW_PATTERN = Pattern.compile(
			"^segments\\[(\\d+)]\\.(.+)$");
	private static final Pattern CURRENCY_AMOUNT = Pattern.compile(
			"(?iu)(?<![A-Z0-9])([A-Z]{3})\\s+([0-9]+(?:[.,][0-9]{2})?)(?![A-Z0-9])");
	private static final Pattern STRICT_TIME_IN_TEXT = Pattern.compile(
			"(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
	private static final Pattern BARE_FLIGHT_SUFFIX = Pattern.compile("(?<![A-Z0-9])(\\d{1,4}[A-Z]?)(?![A-Z0-9])");
	private static final Pattern PHONE_IN_TEXT = Pattern.compile(
			"(?<![A-Z0-9])(?:\\+?\\d[\\d ().-]{6,}\\d)(?![A-Z0-9])");
	private static final Pattern EMAIL_IN_TEXT = Pattern.compile(
			"(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])");
	private static final Pattern URL_IN_TEXT = Pattern.compile(
			"(?i)(?<![A-Z0-9])(?:HTTPS?://|WWW\\.)\\S+|(?<![A-Z0-9])(?:[A-Z0-9-]+\\.)+(?:COM|NET|ORG|CN|UK|ES)(?:/\\S*)?");

	private static final List<String> SEGMENT_FIELD_NAMES = List.of(
			"flight_number", "airline_name", "departure_airport", "arrival_airport", "departure_date",
			"scheduled_departure", "scheduled_arrival", "departure_terminal", "arrival_terminal",
			"boarding_time", "terminal", "gate", "seat", "date", "datetime");
	private static final Set<String> REQUIRED_SEGMENT_FIELDS = Set.of(
			"flight_number", "departure_airport", "arrival_airport", "departure_date");
	private static final Set<String> BOOKING_LABELS = Set.of(
			"PNR", "AIRLINE PNR", "IE PNR", "BOOKING REFERENCE", "BOOKING REF",
			"RECORD LOCATOR", "RESERVATION CODE", "RESERVATION NUMBER", "CONFIRMATION CODE",
			"航空公司记录编号", "订座记录编号", "预订编号", "预订号", "订单编号", "订单号",
			"REFERENCIA DE RESERVA", "LOCALIZADOR", "CÓDIGO DE RESERVA", "CODIGO DE RESERVA",
			"NÚMERO DE RESERVA", "NUMERO DE RESERVA");
	private static final Set<String> TICKET_OR_IDENTITY_LABELS = Set.of(
			"TICKET", "TICKET NUMBER", "E-TICKET", "E-TICKET NUMBER", "ETKT", "ETKT NBR",
			"ID NUMBER", "PASSPORT", "PASSPORT NUMBER", "NAME", "PASSENGER",
			"票号", "电子票号", "机票号", "身份识别代码", "护照号", "旅客姓名", "乘客姓名",
			"BILLETE", "NÚMERO DE BILLETE", "NUMERO DE BILLETE", "PASAPORTE",
			"NÚMERO DE PASAPORTE", "NUMERO DE PASAPORTE", "NOMBRE", "PASAJERO");
	private static final Set<String> FINANCIAL_LABELS = Set.of(
			"FARE", "TAX", "TOTAL", "AMOUNT", "PRICE", "PAYMENT", "FORM OF PAYMENT", "CURRENCY",
			"机票款", "票价", "税", "税款", "总额", "金额", "付款", "付款方式", "支付", "支付方式", "币种",
			"TARIFA", "IMPUESTO", "IMPUESTOS", "IMPORTE", "PRECIO", "PAGO", "FORMA DE PAGO", "MONEDA");
	private static final Set<String> ISSUE_DATE_LABELS = Set.of(
			"DATE OF ISSUE", "ISSUE DATE", "BOOKING DATE", "PURCHASE DATE", "PAYMENT DATE",
			"出票时间", "出票日期", "预订日期", "购买日期", "支付日期",
			"FECHA DE EMISIÓN", "FECHA DE EMISION", "FECHA DE RESERVA", "FECHA DE COMPRA", "FECHA DE PAGO");
	private static final Set<String> CONTACT_LABELS = Set.of(
			"联系电话", "电话", "手机", "传真", "电子邮箱", "邮箱", "网址", "联系方式",
			"CONTACT", "CONTACT DETAILS", "TELEPHONE", "PHONE", "MOBILE", "EMAIL", "E-MAIL", "WEBSITE", "WEB",
			"CONTACTO", "TELÉFONO", "TELEFONO", "MÓVIL", "MOVIL", "CORREO", "CORREO ELECTRÓNICO",
			"CORREO ELECTRONICO", "SITIO WEB");
	private static final Set<String> SHORT_CONTACT_LABELS = Set.of(
			"TEL", "FAX", "NBR", "NO", "NUMBER", "NUM");
	private static final Set<String> FLIGHT_NUMBER_LABELS = Set.of(
			"航班", "航班号", "航班编号", "FLIGHT", "FLIGHT NO", "FLIGHT NUMBER", "FLT", "FLT NO",
			"VUELO", "NÚMERO DE VUELO", "NUMERO DE VUELO");
	private static final Set<String> AIRCRAFT_LABELS = Set.of(
			"机型", "飞机型号", "航空器", "飞机", "波音", "空客", "空中客车", "中国商飞",
			"AIRCRAFT", "AIRCRAFT TYPE", "EQUIPMENT", "EQUIP", "MODEL", "BOEING", "AIRBUS",
			"EMBRAER", "BOMBARDIER", "COMAC", "ATR", "AERONAVE", "TIPO DE AERONAVE", "EQUIPO", "MODELO");
	private static final Set<String> DEPARTURE_LABELS = Set.of(
			"计划", "计划起飞", "计划出发", "出发", "起飞", "出发日期", "起飞日期", "出发时间", "起飞时间",
			"SCHEDULED", "SCHEDULED DEPARTURE", "DEPARTURE", "DEPART", "TAKEOFF", "DEPARTURE DATE",
			"DEPARTURE TIME", "PROGRAMADO", "SALIDA", "SALIDA PROGRAMADA", "FECHA DE SALIDA", "HORA DE SALIDA");
	private static final Set<String> ARRIVAL_LABELS = Set.of(
			"到达", "抵达", "已到达", "航班已到达", "实际到达", "实际抵达", "到达日期", "到达时间", "实际到达时间",
			"ARRIVAL", "ARRIVE", "ARRIVED", "ACTUAL ARRIVAL", "ARRIVAL DATE", "ARRIVAL TIME", "LANDED",
			"LLEGADA", "LLEGÓ", "LLEGO", "LLEGADA REAL", "FECHA DE LLEGADA", "HORA DE LLEGADA",
			"ATERRIZÓ", "ATERRIZO");
	private static final Set<String> CURRENCY_CODES = Currency.getAvailableCurrencies().stream()
			.map(Currency::getCurrencyCode).collect(java.util.stream.Collectors.toUnmodifiableSet());
	private static final Comparator<EvidenceBlock> EVIDENCE_ORDER = Comparator
			.comparingInt(EvidenceBlock::pageIndex)
			.thenComparingDouble(EvidenceBlock::y)
			.thenComparingDouble(EvidenceBlock::x)
			.thenComparingInt(EvidenceBlock::documentOrder);
	private static final Comparator<PositionedToken<?>> TOKEN_ORDER = Comparator
			.comparingInt((PositionedToken<?> token) -> token.block().pageIndex())
			.thenComparingDouble(token -> token.block().y())
			.thenComparingDouble(token -> token.block().x())
			.thenComparingInt(token -> token.block().documentOrder())
			.thenComparingInt(PositionedToken::characterOffset);

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final ExtractionRequester extractionRequester;
	private final AirlineRegistry airlineRegistry;
	private final PdfInputClassifier pdfInputClassifier;
	private final TravelDocumentDateParser dateParser;
	private final boolean extractionDebug;

	enum MetadataContext {
		NONE,
		BOOKING,
		TICKET_OR_IDENTITY,
		FINANCIAL,
		ISSUE_DATE,
		CONTACT
	}

	@FunctionalInterface
	interface ExtractionRequester {
		JsonNode request(byte[] documentBytes, String filename, String contentType,
				String stage, Map<String, Object> instructions) throws JacksonException;
	}

	public NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper) {
		this(objectMapper, Clock.systemUTC(), null, AirlineRegistry.loadDefault(), false);
	}

	@Autowired
	public NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper,
			@Value("${onestep.document-extraction.debug:false}") boolean extractionDebug) {
		this(objectMapper, Clock.systemUTC(), null, AirlineRegistry.loadDefault(), extractionDebug);
	}

	NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper, Clock clock) {
		this(objectMapper, clock, null, AirlineRegistry.loadDefault(), false);
	}

	NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper, Clock clock,
			ExtractionRequester extractionRequester) {
		this(objectMapper, clock, extractionRequester, AirlineRegistry.loadDefault(), false);
	}

	NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper, Clock clock,
			ExtractionRequester extractionRequester, AirlineRegistry airlineRegistry) {
		this(objectMapper, clock, extractionRequester, airlineRegistry, false);
	}

	NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper, Clock clock,
			ExtractionRequester extractionRequester, AirlineRegistry airlineRegistry,
			boolean extractionDebug) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.airlineRegistry = Objects.requireNonNull(airlineRegistry, "airlineRegistry");
		this.pdfInputClassifier = new PdfInputClassifier();
		this.dateParser = new TravelDocumentDateParser(this.clock);
		this.extractionDebug = extractionDebug;
		if (extractionRequester != null) {
			this.restClient = null;
			this.extractionRequester = extractionRequester;
			return;
		}
		HttpClient.Builder clientBuilder = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				// A redirect would add an untracked provider request. Keep every declared
				// extraction stage literal and fail that stage instead.
				.followRedirects(redirectPolicy())
				.version(HttpClient.Version.HTTP_1_1);
		ProxySelector proxySelector = ProxySelector.getDefault();
		if (proxySelector != null) clientBuilder.proxy(proxySelector);
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(clientBuilder.build());
		requestFactory.setReadTimeout(Duration.ofSeconds(52));
		this.restClient = RestClient.builder()
				.requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
				.build();
		this.extractionRequester = this::requestExtraction;
	}

	static HttpClient.Redirect redirectPolicy() {
		return HttpClient.Redirect.NEVER;
	}

	@Override
	public BoardingPassExtractionResponse extract(byte[] documentBytes, String filename, String contentType) {
		PdfInputClassifier.Analysis pdfAnalysis = MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(contentType)
				? pdfInputClassifier.analyze(documentBytes) : PdfInputClassifier.Analysis.imageOrMixed();
		boolean nativeTextPdf = pdfAnalysis.type() == PdfInputClassifier.Type.NATIVE_TEXT;
		Map<String, ProviderAttempt> attempts = requestInitialPasses(
				documentBytes, filename, contentType, nativeTextPdf);
		ParsedPass understand = parseSchemaSafely(attempts.get(UNDERSTAND_STAGE),
				CandidateSource.UNDERSTAND, UNDERSTAND_STAGE);
		ParsedPass text = parseSchemaSafely(attempts.get(TEXT_STAGE),
				CandidateSource.TEXT, TEXT_STAGE);
		boolean textSucceeded = text != null;
		ProviderAttempt structureAttempt = attempts.get(STRUCTURE_STAGE);
		SpatialPass structure = parseSpatialSafely(structureAttempt,
				nativeTextPdf);

		if (understand == null && text == null && structure == null) {
			throwAllFailed(attempts.values());
		}
		BoardingPassExtractionResponse base = understand != null ? understand.response()
				: text != null ? text.response() : emptyResponse();
		List<SegmentCandidate> schemaCandidates = new ArrayList<>();
		if (understand != null) schemaCandidates.addAll(understand.candidates());
		if (text != null) schemaCandidates.addAll(text.candidates());
		List<SegmentCandidate> structureCandidates = structure == null ? List.of() : structure.candidates();
		if (structure != null) {
			schemaCandidates = new ArrayList<>(filterMetadataCandidates(schemaCandidates, structure.blocks()));
			schemaCandidates = new ArrayList<>(mapBundlesToStructureCards(schemaCandidates, structure.cards()));
		}
		if (nativeTextPdf) {
			List<SegmentCandidate> nativeRows = nativeFlightRows(pdfAnalysis);
			schemaCandidates.addAll(structure == null ? nativeRows
					: mapBundlesToStructureCards(nativeRows, structure.cards()));
		}
		List<EvidenceBlock> structureBlocks = structure == null ? List.of() : structure.blocks();
		MergeOutcome merged = mergeCandidates(schemaCandidates, structureCandidates, pdfAnalysis,
				structureBlocks);
		Set<String> coverageGapCards = nativeTextPdf ? Set.of()
				: strictCoverageGapCards(understand, merged, structure, schemaCandidates);
		if (!coverageGapCards.isEmpty()) {
			ProviderAttempt supplementalAttempt = requestSafely(documentBytes, filename, contentType,
					TEXT_STAGE, schemaInstructions(TEXT_STAGE, contentType));
			attempts = new LinkedHashMap<>(attempts);
			attempts.put(TEXT_STAGE, supplementalAttempt);
			ParsedPass supplemental = parseSchemaSafely(supplementalAttempt, CandidateSource.TEXT, TEXT_STAGE);
			if (supplemental != null && structure != null) {
				textSucceeded = true;
				List<SegmentCandidate> supplementalCandidates = mapBundlesToStructureCards(
						filterMetadataCandidates(supplemental.candidates(), structure.blocks()), structure.cards())
						.stream().filter(candidate -> candidate.cardId() != null
								&& coverageGapCards.contains(candidate.cardId())).toList();
				schemaCandidates.addAll(supplementalCandidates);
				merged = mergeCandidates(schemaCandidates, structureCandidates, pdfAnalysis,
						structureBlocks);
			}
		}
		BoardingPassExtractionResponse result = auditedResponse(base, merged, structure != null, structure);
		List<String> callTypes = List.copyOf(attempts.keySet());
		logOutcome(result, understand != null, textSucceeded,
				structure != null, callTypes, structure, merged);
		if (extractionDebug) logDebugAudit(result, merged, structure,
				callTypes);
		return result;
	}

	private Map<String, ProviderAttempt> requestInitialPasses(byte[] documentBytes,
			String filename, String contentType, boolean nativeTextPdf) {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Map<String, CompletableFuture<ProviderAttempt>> futures = new LinkedHashMap<>();
			String schemaStage = nativeTextPdf ? TEXT_STAGE : UNDERSTAND_STAGE;
			futures.put(schemaStage, CompletableFuture.supplyAsync(() -> requestSafely(documentBytes,
					filename, contentType, schemaStage, schemaInstructions(schemaStage, contentType)), executor));
			futures.put(STRUCTURE_STAGE, CompletableFuture.supplyAsync(() -> requestSafely(documentBytes,
					filename, contentType, STRUCTURE_STAGE, structureInstructions()), executor));
			Map<String, ProviderAttempt> attempts = new LinkedHashMap<>();
			futures.forEach((stage, future) -> attempts.put(stage, future.join()));
			return Collections.unmodifiableMap(attempts);
		} finally {
			executor.shutdown();
		}
	}

	private Set<String> strictCoverageGapCards(ParsedPass understand, MergeOutcome merged,
			SpatialPass structure, List<SegmentCandidate> schemaCandidates) {
		if (structure == null || understand == null) return Set.of();
		Set<String> understandFlights = understand.candidates().stream()
				.map(SegmentCandidate::flightNumber).map(this::normalizeFlightNumber)
				.filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
		Set<String> finalFlights = merged.segments().stream().map(DocumentFlightSegment::flightNumber)
				.map(this::normalizeFlightNumber).filter(Objects::nonNull)
				.collect(java.util.stream.Collectors.toSet());
		LinkedHashSet<String> gaps = new LinkedHashSet<>();
		for (SegmentCandidate candidate : structure.candidates()) {
			String flight = normalizeFlightNumber(candidate.flightNumber());
			String cardId = candidate.cardId();
			if (flight == null || cardId == null || understandFlights.contains(flight)
					|| finalFlights.contains(flight)) continue;
			long mapped = schemaCandidates.stream().filter(item -> cardId.equals(item.cardId())).count();
			if (mapped == 0) gaps.add(cardId);
		}
		return Set.copyOf(gaps);
	}

	private ProviderAttempt requestSafely(byte[] documentBytes, String filename, String contentType,
			String stage, Map<String, Object> instructions) {
		try {
			return new ProviderAttempt(extractionRequester.request(documentBytes, filename, contentType,
					stage, instructions), null);
		} catch (ExternalServiceNotConfiguredException exception) {
			logStageFailure(stage, exception, null);
			return new ProviderAttempt(null, exception);
		} catch (RestClientResponseException exception) {
			logStageFailure(stage, exception, exception.getStatusCode().value());
			return new ProviderAttempt(null, new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED"));
		} catch (RestClientException | ExternalServiceUnavailableException exception) {
			logStageFailure(stage, exception, null);
			return new ProviderAttempt(null, new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED"));
		} catch (JacksonException exception) {
			logStageFailure(stage, exception, null);
			return new ProviderAttempt(null, new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED"));
		} catch (RuntimeException exception) {
			// Keep channel failures isolated: an unexpected Understand failure must
			// never prevent the one unconditional Structure request (and vice versa).
			logStageFailure(stage, exception, null);
			return new ProviderAttempt(null, new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED"));
		}
	}

	private ParsedPass parseSchemaSafely(ProviderAttempt attempt, CandidateSource source,
			String sourceName) {
		if (attempt == null) return null;
		if (attempt.response() == null) return null;
		try {
			return parsePass(attempt.response(), source, sourceName);
		} catch (RuntimeException exception) {
			logStageFailure(sourceName + "-parse", exception, null);
			return null;
		}
	}

	private SpatialPass parseSpatialSafely(ProviderAttempt attempt, boolean nativePositionFallback) {
		if (attempt.response() == null) return null;
		try {
			return parseSpatial(attempt.response(), nativePositionFallback);
		} catch (RuntimeException exception) {
			logStageFailure(STRUCTURE_STAGE + "-parse", exception, null);
			return null;
		}
	}

	private void throwAllFailed(java.util.Collection<ProviderAttempt> attempts) {
		if (attempts.stream().map(ProviderAttempt::failure)
				.anyMatch(ExternalServiceNotConfiguredException.class::isInstance)) {
			throw new ExternalServiceNotConfiguredException("NUTRIENT");
		}
		throw new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED");
	}

	private void logStageFailure(String stage, Exception exception, Integer httpStatus) {
		LOGGER.warn("provider=NUTRIENT stage={} outcome=FAILED httpStatus={} errorType={}",
				stage, httpStatus == null ? "none" : httpStatus, exception.getClass().getSimpleName());
	}

	private JsonNode requestExtraction(byte[] documentBytes, String filename, String contentType,
			String stage, Map<String, Object> instructions) throws JacksonException {
		String apiKey = System.getenv("NUTRIENT_API_KEY");
		if (apiKey == null || apiKey.isBlank()) throw new ExternalServiceNotConfiguredException("NUTRIENT");
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", filePart(documentBytes, filename, contentType));
		body.add("instructions", instructionsPart(instructions));
		String endpoint = STRUCTURE_STAGE.equals(stage) ? PARSE_ENDPOINT : EXTRACT_ENDPOINT;
		ResponseEntity<JsonNode> response = restClient.post()
				.uri(endpoint)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body)
				.retrieve()
				.toEntity(JsonNode.class);
		LOGGER.info("provider=NUTRIENT stage={} httpStatus={} responseContentType={}", stage,
				response.getStatusCode().value(), response.getHeaders().getContentType());
		return response.getBody();
	}

	private HttpEntity<ByteArrayResource> filePart(byte[] bytes, String filename, String contentType) {
		ByteArrayResource resource = new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return filename;
			}
		};
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(contentType));
		return new HttpEntity<>(resource, headers);
	}

	private HttpEntity<String> instructionsPart(Map<String, Object> instructions) throws JacksonException {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new HttpEntity<>(objectMapper.writeValueAsString(instructions), headers);
	}

	Map<String, Object> understandInstructions(String contentType) {
		return schemaInstructions("understand", contentType);
	}

	Map<String, Object> firstPassInstructions(byte[] documentBytes, String contentType) {
		PdfInputClassifier.Analysis analysis = MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(contentType)
				? pdfInputClassifier.analyze(documentBytes) : PdfInputClassifier.Analysis.imageOrMixed();
		return firstPassInstructions(contentType, analysis);
	}

	private Map<String, Object> firstPassInstructions(String contentType,
			PdfInputClassifier.Analysis analysis) {
		boolean nativeTextPdf = MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(contentType)
				&& analysis.type() == PdfInputClassifier.Type.NATIVE_TEXT;
		return schemaInstructions(nativeTextPdf ? "text" : "understand", contentType);
	}

	private Map<String, Object> schemaInstructions(String mode, String contentType) {
		Map<String, Object> instructions = new LinkedHashMap<>();
		instructions.put("mode", mode);
		instructions.put("schema", documentSchema());
		instructions.put("includeCitations", true);
		instructions.put("strict", false);
		instructions.put("multimodal", "understand".equals(mode)
				&& (MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(contentType)
				|| MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(contentType)));
		return instructions;
	}

	Map<String, Object> structureInstructions() {
		return Map.of("mode", "structure", "output", Map.of("format", "spatial"));
	}

	Map<String, Object> documentSchema() {
		Map<String, Object> segmentProperties = new LinkedHashMap<>();
		segmentProperties.put("flight_number", stringProperty(
				"Extract the complete marketed flight designator exactly as printed. "
						+ "Do not create a flight number from an airline name and an aircraft type, "
						+ "equipment number, seat number, gate number, price, time, date or unrelated number. "
						+ "Aircraft models are not flight numbers. Do not use PNRs, booking references, "
						+ "record locators, ticket numbers, passenger IDs, fares, taxes, currency amounts "
						+ "or payment references."));
		segmentProperties.put("airline_name", stringProperty(
				"Copy only the airline name visibly printed in the same flight card or segment."));
		segmentProperties.put("departure_airport", stringProperty(
				"Extract only the origin location belonging to the flight route. "
						+ "Do not use currency codes, booking labels, ticket labels, fare/tax labels "
						+ "or unrelated uppercase text as airports. Do not use telephone, fax, email, website, "
						+ "contact, ticket-number or generic number labels as airports or route endpoints."));
		segmentProperties.put("arrival_airport", stringProperty(
				"Extract only the destination location belonging to the flight route. "
						+ "Do not use currency codes, booking labels, ticket labels, fare/tax labels "
						+ "or unrelated uppercase text as airports. Do not use telephone, fax, email, website, "
						+ "contact, ticket-number or generic number labels as airports or route endpoints."));
		segmentProperties.put("departure_date", stringProperty(
				"Extract the scheduled departure date. Do not use an actual arrival date, landed date, "
						+ "status timestamp, issue date, booking date or payment date. Return it exactly as visible, "
						+ "including month and day without a year."));
		segmentProperties.put("scheduled_departure", stringProperty(
				"Extract the exact scheduled departure time and preserve the printed minutes. "
						+ "Do not round or replace it with an arrival or status time."));
		segmentProperties.put("scheduled_arrival", stringProperty(
				"Copy the scheduled arrival time or date-time exactly as visibly supported."));
		segmentProperties.put("departure_terminal", stringProperty(
				"Copy the departure terminal visibly shown for this same segment."));
		segmentProperties.put("arrival_terminal", stringProperty(
				"Copy the arrival terminal visibly shown for this same segment."));
		segmentProperties.put("boarding_time", stringProperty("Copy the visible boarding time when present."));
		segmentProperties.put("terminal", stringProperty(
				"Copy the visible departure terminal for compatibility when only one terminal field is present."));
		segmentProperties.put("gate", stringProperty("Copy the visible departure gate when present."));
		segmentProperties.put("seat", stringProperty("Copy the visible seat when present."));

		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", "object");
		item.put("properties", segmentProperties);
		Map<String, Object> segmentArray = new LinkedHashMap<>();
		segmentArray.put("type", "array");
		segmentArray.put("description", "Keep every genuinely distinct flight segment. Do not create a separate segment from an arrival-status fragment. Do not split conflicting readings from the same visual flight card into separate segments. Preserve the visual order of the segments. Bind each flight number only to the departure and arrival airports shown for that same flight segment. Do not replace an individual segment route with the overall itinerary origin and final destination. Return dates exactly as visible. Do not invent a year when the document only shows month and day. Keep a genuinely distinct incomplete flight segment. Do not create extra segments from airport/time/date fragments that belong to another complete segment already extracted from the same flight card. Do not create separate segments from date/time fragments belonging to one visual flight card. Do not create a route from contact-information headers.");
		segmentArray.put("items", item);

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("document_type", enumProperty());
		properties.put("segments", segmentArray);
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("description", "Extract only flight-document fields. Never return passenger names, passport numbers, ticket numbers, order numbers, booking references, or barcode contents.");
		schema.put("properties", properties);
		schema.put("required", List.of("document_type", "segments"));
		return schema;
	}

	private Map<String, String> stringProperty(String description) {
		return Map.of("type", "string", "description", description);
	}

	private Map<String, Object> enumProperty() {
		return Map.of("type", "string", "enum",
				List.of("BOARDING_PASS", "ITINERARY", "E_TICKET", "BOOKING_CONFIRMATION", "UNKNOWN"),
				"description", "Classify only the visible travel document; use UNKNOWN when unclear.");
	}

	BoardingPassExtractionResponse toResponse(JsonNode response) {
		return parsePass(response, CandidateSource.UNDERSTAND, UNDERSTAND_STAGE).response();
	}

	private ParsedPass parsePass(JsonNode response, CandidateSource candidateSource,
			String sourceName) {
		JsonNode data = response == null ? null : response.path("output").path("data");
		if (data == null || !data.isObject()) {
			throw new ExternalServiceUnavailableException("NUTRIENT_RESPONSE_INVALID");
		}
		JsonNode metadata = response.path("output").path("metadata");
		Map<String, ExtractedField> rootFields = new LinkedHashMap<>();
		ExtractedField rawDocumentType = extractedField(data, metadata, "document_type", "document_type");
		DocumentType documentType = DocumentType.fromProviderValue(rawDocumentType.value());
		rootFields.put("document_type", new ExtractedField(documentType.name(), rawDocumentType.rawValue(),
				rawDocumentType.confidence(), rawDocumentType.page(), rawDocumentType.boundingBox(),
				rawDocumentType.sourceLabel()));

		List<String> review = new ArrayList<>();
		if (rawDocumentType.value() == null
				|| rawDocumentType.confidence() < DocumentType.HIGH_CONFIDENCE_THRESHOLD
				|| documentType == DocumentType.UNKNOWN) review.add("document_type");
		List<DocumentFlightSegment> segments = new ArrayList<>();
		List<SegmentCandidate> candidates = new ArrayList<>();
		JsonNode segmentData = data.path("segments");
		if (segmentData.isArray()) {
			for (int index = 0; index < segmentData.size(); index++) {
				String sourceBundleId = sourceName + ":" + index;
				DocumentFlightSegment segment = parseUnderstandSegment(segmentData.path(index),
						segmentMetadata(metadata, index), index, review, sourceName, sourceBundleId);
				segments.add(segment);
				candidates.add(new SegmentCandidate(segment, segment.flightNumber(), index,
						candidateSource, fieldSources(segment), null, sourceBundleId));
			}
		}
		if (segments.isEmpty() && hasLegacySegment(data)) {
			String sourceBundleId = sourceName + ":0";
			DocumentFlightSegment segment = parseUnderstandSegment(data, metadata, 0, review,
					sourceName, sourceBundleId);
			segments.add(segment);
			candidates.add(new SegmentCandidate(segment, segment.flightNumber(), 0,
					candidateSource, fieldSources(segment), null, sourceBundleId));
		}
		if (segments.size() == 1) {
			rootFields.putAll(segments.get(0).fields());
			review.replaceAll(field -> field.replace("segments[0].", ""));
		}
		BoardingPassExtractionResponse parsed = new BoardingPassExtractionResponse(
				documentType, null, null, null, null, null, null, null, null, null, null, null,
				false, false, rootFields, List.copyOf(segments), List.copyOf(new LinkedHashSet<>(review)),
				DATA_SOURCE, Instant.now(clock));
		return new ParsedPass(parsed, List.copyOf(candidates));
	}

	private DocumentFlightSegment parseUnderstandSegment(JsonNode data, JsonNode metadata, int index,
			List<String> review, String sourceName, String sourceBundleId) {
		Map<String, ExtractedField> fields = emptyFields();
		for (String name : SEGMENT_FIELD_NAMES) {
			fields.put(name, sanitizeField(name,
					extractedField(data, metadata, name, "segments[" + index + "]." + name,
							sourceName, sourceBundleId)));
		}
		ScheduleRawValue visibleSchedule = parseScheduleRawValue("departure_date",
				fields.get("departure_date").rawValue(), fields.get("departure_date").rawValue());
		ScheduleRawValue scheduledDeparture = parseScheduleRawValue("scheduled_departure",
				fields.get("scheduled_departure").rawValue(), fields.get("scheduled_departure").value());
		DateParts date = departureDateEvidence(visibleSchedule);
		DateParts scheduledDate = departureDateEvidence(scheduledDeparture);
		if (!date.hasValue() && !scheduledDate.hasValue()) {
			ExtractedField generic = fields.get("date") != null && fields.get("date").rawValue() != null
					? fields.get("date") : fields.get("datetime");
			if (generic != null) {
				DateParts genericDate = departureDateEvidence(parseScheduleRawValue("date",
						generic.rawValue(), generic.rawValue()));
				if (genericDate.hasValue()) {
					date = genericDate;
					fields.put("departure_date", new ExtractedField(genericDate.publicValue(),
							generic.rawValue(), generic.confidence(), generic.page(), generic.boundingBox(),
							generic.sourceLabel()));
				}
			}
		}
		DateParts combinedDate = combineDateEvidence(date, scheduledDate);
		if (combinedDate.source() == DateSource.CONFLICT) {
			ExtractedField visibleDate = fields.get("departure_date");
			fields.put("departure_date", new ExtractedField(null, visibleDate.rawValue(),
					visibleDate.confidence(), visibleDate.page(), visibleDate.boundingBox(),
					visibleDate.sourceLabel()));
			review.add("segments[" + index + "].conflict.departure_date");
			date = DateParts.CONFLICT;
		} else if (combinedDate.hasValue()) {
			date = combinedDate;
			ExtractedField visibleDate = fields.get("departure_date");
			if (!Objects.equals(visibleDate.value(), date.publicValue())) {
				fields.put("departure_date", new ExtractedField(date.publicValue(), visibleDate.rawValue(),
						visibleDate.confidence(), visibleDate.page(), visibleDate.boundingBox(),
						visibleDate.sourceLabel()));
			}
		}
		String terminal = firstNonBlank(fields.get("departure_terminal").value(), fields.get("terminal").value());
		if (Objects.equals(fields.get("departure_airport").value(), fields.get("arrival_airport").value())
				&& fields.get("departure_airport").value() != null) {
			ExtractedField arrival = fields.get("arrival_airport");
			fields.put("arrival_airport", new ExtractedField(null, arrival.rawValue(), arrival.confidence(),
					arrival.page(), arrival.boundingBox(), arrival.sourceLabel()));
		}
		ExtractedField identity = fields.get("flight_number");
		FlightAssessment flightAssessment = assessUnderstandFlight(identity, fields);
		if (flightAssessment.flightNumber() != null
				&& !Objects.equals(identity.value(), flightAssessment.flightNumber())) {
			fields.put("flight_number", new ExtractedField(flightAssessment.flightNumber(),
					identity.rawValue(), identity.confidence(), identity.page(), identity.boundingBox(),
					identity.sourceLabel() + "+airline-name-registry"));
		}
		DocumentFlightSegment segment = new DocumentFlightSegment(
				flightAssessment.flightNumber(), date.date(), date.monthDay(),
				fields.get("departure_airport").value(), fields.get("arrival_airport").value(),
				fields.get("scheduled_departure").value(), fields.get("scheduled_arrival").value(),
				fields.get("boarding_time").value(), terminal, fields.get("gate").value(),
				fields.get("seat").value(), segmentConfidence(fields), identity.page(),
				identity.boundingBox(), fields, flightAssessment.confidence(),
				flightAssessment.requiresConfirmation(), false, date.source());
		addMissingReviewFields(review, segment, index);
		for (String name : SEGMENT_FIELD_NAMES) {
			ExtractedField field = fields.get(name);
			if (field != null && field.value() != null && field.confidence() > 0.0d
					&& field.confidence() < confidenceThreshold(name)) review.add("segments[" + index + "]." + name);
		}
		return segment;
	}

	private DateParts combineDateEvidence(DateParts visible, DateParts scheduled) {
		if (visible != null && visible.source() == DateSource.CONFLICT) return visible;
		if (scheduled != null && scheduled.source() == DateSource.CONFLICT) return scheduled;
		if (visible == null || !visible.hasValue()) return scheduled == null ? DateParts.EMPTY : scheduled;
		if (scheduled == null || !scheduled.hasValue()) return visible;
		if (!Objects.equals(visible.monthDay(), scheduled.monthDay())) return DateParts.CONFLICT;
		if (Objects.equals(visible.date(), scheduled.date())) {
			return visible.source() == DateSource.EXPLICIT_FULL_DATE ? visible : scheduled;
		}
		if (visible.source() == DateSource.CURRENT_YEAR_FROM_MONTH_DAY
				&& scheduled.source() == DateSource.EXPLICIT_FULL_DATE) return scheduled;
		if (scheduled.source() == DateSource.CURRENT_YEAR_FROM_MONTH_DAY
				&& visible.source() == DateSource.EXPLICIT_FULL_DATE) return visible;
		return DateParts.CONFLICT;
	}

	SpatialPass parseSpatial(JsonNode response) {
		return parseSpatial(response, false);
	}

	private SpatialPass parseSpatial(JsonNode response, boolean nativePositionFallback) {
		if (response == null || response.isMissingNode() || response.isNull()
				|| (!response.isArray() && !response.isObject())) {
			throw new ExternalServiceUnavailableException("NUTRIENT_RESPONSE_INVALID");
		}
		EvidenceNormalization normalization = normalizeEvidence(response);
		List<EvidenceBlock> blocks = normalization.blocks();
		List<PositionedToken<String>> flightTokens = flightTokens(blocks);
		List<PositionedToken<String>> airports = airportTokens(blocks);
		List<PositionedToken<DateParts>> dates = dateTokens(blocks);
		List<PositionedToken<String>> times = timeTokens(blocks);
		List<PositionedToken<String>> terminals = terminalTokens(blocks);
		List<PositionedToken<String>> airlineNames = airlineNameTokens(blocks);
		List<StructureCard> cards = structureCards(blocks, flightTokens, airports, dates, times, terminals);
		List<SegmentAnchor> anchors = anchors(flightTokens, airports, dates, times, terminals).stream()
				.filter(anchor -> normalizeFlightNumber(anchor.flightNumber()) != null)
				.map(anchor -> anchor.withCard(cardForToken(anchor.token(), cards)))
				.toList();

		List<SegmentCandidate> candidates = new ArrayList<>();
		Set<PositionedToken<?>> boundTokens = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int index = 0; index < anchors.size(); index++) {
			SegmentAnchor anchor = anchors.get(index);
			SegmentAnchor previous = index == 0 ? null : anchors.get(index - 1);
			SegmentAnchor next = index + 1 < anchors.size() ? anchors.get(index + 1) : null;
			List<PositionedToken<String>> cardAirports = tokensInCard(airports, anchor);
			List<PositionedToken<DateParts>> cardDates = tokensInCard(dates, anchor);
			List<PositionedToken<String>> cardTimes = tokensInCard(times, anchor);
			PositionedToken<String> departure;
			PositionedToken<String> arrival;
			if (!cardAirports.isEmpty()) {
				departure = cardAirports.get(0);
				String departureAirport = departure.value();
				arrival = cardAirports.stream().filter(token -> !token.value().equals(departureAirport))
						.findFirst().orElse(null);
			} else if (nativePositionFallback) {
				departure = nearestPreviousAirport(airports, anchor.token(),
						previous == null ? null : previous.token(), anchors.get(0).token());
				arrival = nearestNext(airports, anchor.token(), next == null ? null : next.token());
				TokenRoute horizontalRoute = horizontalRoute(airports, anchor.token(),
						previous == null ? null : previous.token(), next == null ? null : next.token(),
						anchors.get(0).token());
				if (horizontalRoute != null) {
					departure = horizontalRoute.departure();
					arrival = horizontalRoute.arrival();
				}
			} else {
				departure = null;
				arrival = null;
			}
			if (departure != null && arrival != null && departure.value().equals(arrival.value())) {
				arrival = nearestNextDifferentAirport(airports, anchor.token(),
						next == null ? null : next.token(), departure.value());
			}
			PositionedToken<DateParts> date = !cardDates.isEmpty()
					? roleSelectedDate(blocks, cardDates, false)
					: nativePositionFallback ? departureDateForAnchor(dates, anchors, index) : null;
			PositionedToken<String> scheduledDeparture = !cardTimes.isEmpty()
					? roleSelectedTime(blocks, cardTimes, false)
					: nativePositionFallback ? reliableTimeForAnchor(nearestPrevious(times, anchor.token(),
							previous == null ? null : previous.token()), anchor, previous, true) : null;
			PositionedToken<String> scheduledArrival = !cardTimes.isEmpty()
					? roleSelectedTime(blocks, cardTimes, true)
					: nativePositionFallback ? reliableTimeForAnchor(nearestNext(times, anchor.token(),
							next == null ? null : next.token()), anchor, next, false) : null;
			PositionedToken<DateParts> arrivalDate = !cardDates.isEmpty()
					? roleSelectedDate(blocks, cardDates, true)
					: nativePositionFallback ? arrivalDateForAnchor(dates, anchors, index) : null;
			String scheduledArrivalValue = arrivalDateTime(arrivalDate, scheduledArrival);
			PositionedToken<String> departureTerminal = nearestPrevious(terminals, anchor.token(),
					previous == null ? null : previous.token());
			PositionedToken<String> arrivalTerminal = nearestNext(terminals, anchor.token(),
					next == null ? null : next.token());
			markBound(boundTokens, anchor.token(), departure, arrival, date, arrivalDate,
					scheduledDeparture, scheduledArrival, departureTerminal, arrivalTerminal);

			Map<String, ExtractedField> fields = emptyFields();
			putSpatialField(fields, "flight_number", anchor.flightNumber(), anchor.token());
			putSpatialField(fields, "airline_name", airlineNameForAnchor(anchor, airlineNames), anchor.token());
			putSpatialField(fields, "departure_airport", tokenValue(departure), departure);
			putSpatialField(fields, "arrival_airport", tokenValue(arrival), arrival);
			DateParts dateParts = date == null ? DateParts.EMPTY : date.value();
			putSpatialDateField(fields, dateParts, date);
			putSpatialField(fields, "scheduled_departure", tokenValue(scheduledDeparture), scheduledDeparture);
			putSpatialField(fields, "scheduled_arrival", scheduledArrivalValue, scheduledArrival);
			putSpatialField(fields, "departure_terminal", tokenValue(departureTerminal), departureTerminal);
			putSpatialField(fields, "arrival_terminal", tokenValue(arrivalTerminal), arrivalTerminal);
			FlightAssessment flightAssessment = assessSpatialFlight(anchor);
			DocumentFlightSegment segment = new DocumentFlightSegment(
					anchor.flightNumber(), dateParts.date(), dateParts.monthDay(), tokenValue(departure),
					tokenValue(arrival), tokenValue(scheduledDeparture), scheduledArrivalValue,
					null, tokenValue(departureTerminal), null, null, segmentConfidence(fields),
					anchor.token().block().pageIndex(), anchor.token().block().box(), fields,
					flightAssessment.confidence(), flightAssessment.requiresConfirmation(), false,
					dateParts.source());
			candidates.add(new SegmentCandidate(segment, anchor.flightNumber(), index,
					CandidateSource.STRUCTURE, fieldSources(segment), anchor.cardId(),
					anchor.cardId() == null ? "structure:" + index : anchor.cardId()));
		}
		int pages = (int) blocks.stream().map(EvidenceBlock::pageIndex).distinct().count();
		int duplicateFlightEvidence = Math.max(0,
				flightDisplayEvidenceCount(flightTokens) - anchors.size());
		List<DocumentFieldCandidate> diagnosticCandidates = spatialDiagnosticCandidates(
				flightTokens, airlineNames, dates, times, airports, terminals, anchors, boundTokens);
		return new SpatialPass(List.copyOf(blocks), List.copyOf(candidates), List.copyOf(cards),
				anchors.size(), pages,
				normalization.discardedEvidenceCount(), duplicateFlightEvidence,
				diagnosticCandidates);
	}

	private List<StructureCard> structureCards(List<EvidenceBlock> blocks,
			List<PositionedToken<String>> flights, List<PositionedToken<String>> airports,
			List<PositionedToken<DateParts>> dates, List<PositionedToken<String>> times,
			List<PositionedToken<String>> terminals) {
		Map<String, StructureCard> cards = new LinkedHashMap<>();
		for (PositionedToken<String> flight : flights) {
			EvidenceBlock block = flight.block();
			List<RegionRef> ancestors = block.regionAncestors();
			for (int ancestorIndex = ancestors.size() - 1; ancestorIndex >= 0; ancestorIndex--) {
				RegionRef ancestor = ancestors.get(ancestorIndex);
				String region = ancestor.path();
				boolean supportingEvidence = java.util.stream.Stream.of(
						airports.stream().map(PositionedToken::block),
						dates.stream().map(PositionedToken::block),
						times.stream().map(PositionedToken::block))
						.flatMap(Function.identity())
						.anyMatch(evidence -> evidence.pageIndex() == block.pageIndex()
								&& blockInRegion(evidence, region));
				if (!supportingEvidence) continue;
				boolean otherFlight = flights.stream().anyMatch(other ->
						other != flight && other.block().pageIndex() == block.pageIndex()
								&& blockInRegion(other.block(), region)
								&& !Objects.equals(normalizeFlightNumber(other.value()),
										normalizeFlightNumber(flight.value())));
				if (otherFlight) continue;
				String id = "card:" + block.pageIndex() + ":" + region;
				StructureCard existing = cards.get(id);
				List<BoundingBox> flightBoxes = new ArrayList<>(existing == null
						? List.of() : existing.flightBoundingBoxes());
				if (block.box() != null && !flightBoxes.contains(block.box())) flightBoxes.add(block.box());
				BoundingBox container = ancestor.boundingBox() != null ? ancestor.boundingBox()
						: existing == null ? null : existing.containerBoundingBox();
				cards.put(id, new StructureCard(id, block.pageIndex(), region, container,
						List.copyOf(flightBoxes)));
				break;
			}
		}
		return List.copyOf(cards.values());
	}

	private boolean blockInRegion(EvidenceBlock block, String regionPath) {
		return block != null && regionPath != null && !"$".equals(regionPath)
				&& block.regionAncestors().stream().anyMatch(region -> regionPath.equals(region.path()));
	}

	private StructureCard cardForToken(PositionedToken<?> token, List<StructureCard> cards) {
		if (token == null) return null;
		return cards.stream().filter(card -> card.pageIndex() == token.block().pageIndex()
				&& blockInRegion(token.block(), card.regionPath()))
				.max(Comparator.comparingInt(card -> card.regionPath().length())).orElse(null);
	}

	private List<SegmentCandidate> mapBundlesToStructureCards(List<SegmentCandidate> candidates,
			List<StructureCard> cards) {
		if (candidates == null || candidates.isEmpty() || cards == null || cards.isEmpty()) {
			return candidates == null ? List.of() : List.copyOf(candidates);
		}
		List<SegmentCandidate> mapped = new ArrayList<>();
		for (SegmentCandidate candidate : candidates) {
			String mappedCard = uniquelyMappedCard(candidate, cards);
			mapped.add(candidate.withCardId(mappedCard));
		}
		return List.copyOf(mapped);
	}

	private String uniquelyMappedCard(SegmentCandidate candidate, List<StructureCard> cards) {
		ExtractedField flightField = candidate.segment().fields().get("flight_number");
		Set<String> flightHits = new LinkedHashSet<>();
		if (flightField != null && flightField.boundingBox() != null && flightField.page() != null) {
			for (StructureCard card : cards) {
				if (card.pageIndex() != flightField.page()) continue;
				if (card.flightBoundingBoxes().stream().anyMatch(box ->
						boxesIntersect(flightField.boundingBox(), box))) flightHits.add(card.id());
			}
		}
		if (flightHits.size() == 1) return flightHits.iterator().next();
		if (flightHits.size() > 1) return null;

		Set<String> centerHits = new LinkedHashSet<>();
		for (ExtractedField field : candidate.segment().fields().values()) {
			if (field == null || field.boundingBox() == null || field.page() == null) continue;
			for (StructureCard card : cards) {
				if (card.pageIndex() == field.page()
						&& citationCenterInCard(field.boundingBox(), card.containerBoundingBox())) {
					centerHits.add(card.id());
				}
			}
		}
		if (centerHits.size() == 1) return centerHits.iterator().next();
		if (centerHits.size() > 1) return null;

		String commonCard = null;
		int mappedFieldCount = 0;
		for (ExtractedField field : candidate.segment().fields().values()) {
			if (field == null || field.boundingBox() == null || field.page() == null) continue;
			Set<String> fieldHits = cards.stream().filter(card -> card.pageIndex() == field.page()
					&& boxesIntersect(field.boundingBox(), card.containerBoundingBox()))
					.map(StructureCard::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			if (fieldHits.size() != 1) continue;
			String hit = fieldHits.iterator().next();
			if (commonCard != null && !commonCard.equals(hit)) return null;
			commonCard = hit;
			mappedFieldCount++;
		}
		return mappedFieldCount >= 2 ? commonCard : null;
	}

	private boolean boxesIntersect(BoundingBox leftBox, BoundingBox rightBox) {
		if (leftBox == null || rightBox == null) return false;
		return Math.min(leftBox.x() + leftBox.width(), rightBox.x() + rightBox.width())
				>= Math.max(leftBox.x(), rightBox.x())
				&& Math.min(leftBox.y() + leftBox.height(), rightBox.y() + rightBox.height())
				>= Math.max(leftBox.y(), rightBox.y());
	}

	private boolean citationCenterInCard(BoundingBox citation, BoundingBox container) {
		if (citation == null || container == null) return false;
		double centerX = citation.x() + citation.width() / 2.0d;
		double centerY = citation.y() + citation.height() / 2.0d;
		return centerX >= container.x() && centerX <= container.x() + container.width()
				&& centerY >= container.y() && centerY <= container.y() + container.height();
	}

	private int flightDisplayEvidenceCount(List<PositionedToken<String>> tokens) {
		Set<String> displays = new LinkedHashSet<>();
		for (PositionedToken<String> token : tokens) {
			EvidenceBlock block = token.block();
			String position = block.hasBounds()
					? String.format(Locale.ROOT, "%.3f|%.3f|%.3f|%.3f",
							block.x(), block.y(), block.width(), block.height())
					: Objects.toString(block.regionPath(), "$") + "|" + block.documentOrder();
			displays.add(token.value() + "|" + block.pageIndex() + "|" + position);
		}
		return displays.size();
	}

	private String airlineNameForAnchor(SegmentAnchor anchor,
			List<PositionedToken<String>> airlineNames) {
		if (anchor == null || anchor.token() == null) return null;
		if (anchor.token().reasonCode().startsWith("AIRLINE_NAME_")) {
			String text = anchor.token().block().text();
			Matcher matcher = AIRLINE_NAME_WITH_SUFFIX.matcher(text);
			return normalizedAirlineDisplayName(matcher.matches() ? matcher.group(1) : text);
		}
		if (anchor.cardId() == null) return null;
		List<String> localNames = tokensInCard(airlineNames, anchor).stream()
				.map(PositionedToken::value).distinct().toList();
		return localNames.size() == 1 ? localNames.get(0) : null;
	}

	private EvidenceNormalization normalizeEvidence(JsonNode root) {
		List<EvidenceBlock> blocks = new ArrayList<>();
		int[] order = { 0 };
		int[] discarded = { 0 };
		normalizeElement(root, new EvidenceContext(0, "unknown", null, null, "$", List.of()),
				blocks, order, discarded, "$" );
		blocks.sort(EVIDENCE_ORDER);
		return new EvidenceNormalization(List.copyOf(blocks), discarded[0]);
	}

	private void normalizeElement(JsonNode node, EvidenceContext parent, List<EvidenceBlock> blocks,
			int[] order, int[] discarded, String sourcePath) {
		if (node == null || node.isNull() || node.isMissingNode()) return;
		if (node.isArray()) {
			for (int index = 0; index < node.size(); index++) {
				normalizeElement(node.path(index), parent, blocks, order, discarded,
						sourcePath + "[" + index + "]");
			}
			return;
		}
		if (!node.isObject()) return;

		int pageIndex = pageIndex(node, parent.pageIndex());
		String declaredType = firstNonBlank(valueOrNull(node.path("type")),
				firstNonBlank(valueOrNull(node.path("kind")), valueOrNull(node.path("entityType"))));
		String type = firstNonBlank(declaredType, parent.elementType());
		BoundingBox ownBox = firstNonNull(bounds(node.path("bounds")), bounds(node.path("bbox")),
				bounds(node.path("boundingBox")), bounds(node.path("rect")));
		Double confidence = confidence(node, parent.confidence());
		boolean regionNode = declaredType != null && isRegionNode(declaredType);
		String regionPath = regionNode ? sourcePath : parent.regionPath();
		BoundingBox containerBox = regionNode && ownBox != null ? ownBox : parent.containerBoundingBox();
		List<RegionRef> regionAncestors = parent.regionAncestors();
		if (regionNode) {
			regionAncestors = new ArrayList<>(regionAncestors);
			regionAncestors.add(new RegionRef(sourcePath, ownBox));
			regionAncestors = List.copyOf(regionAncestors);
		}
		EvidenceContext context = new EvidenceContext(pageIndex, type, containerBox, confidence,
				regionPath, regionAncestors);
		Set<String> nodeFingerprints = new LinkedHashSet<>();

		for (String key : List.of("text", "value", "content", "plainText", "rawText")) {
			JsonNode textNode = node.path(key);
			if (textNode.isObject() || textNode.isArray()) {
				normalizeElement(textNode, context, blocks, order, discarded,
						sourcePath + "." + key);
				continue;
			}
			if (!textNode.isValueNode()) continue;
			String text = normalizeEvidenceText(textNode.asText());
			if (text == null) continue;
			String[] lines = text.split("\\n");
			for (int line = 0; line < lines.length; line++) {
				String value = normalizeEvidenceText(lines[line]);
				if (value == null) continue;
				BoundingBox lineBox = splitLineBox(ownBox, line, lines.length);
				int documentOrder = order[0]++;
				String fingerprint = Objects.toString(lineBox) + "|" + value;
				if (!nodeFingerprints.add(fingerprint)) {
					discarded[0]++;
					continue;
				}
				blocks.add(new EvidenceBlock(pageIndex, type, value, documentOrder, confidence,
						lineBox, containerBox, sourcePath + "." + key, regionPath,
						"STRUCTURE", "structure:" + pageIndex + ":" + regionPath,
						lines[line], value, regionAncestors));
			}
		}

		var fields = node.properties().iterator();
		while (fields.hasNext()) {
			var field = fields.next();
			if (Set.of("text", "value", "content", "plainText", "rawText", "bounds", "bbox",
					"boundingBox", "rect", "page", "pageIndex", "pageNumber", "confidence",
					"score", "type", "kind", "id", "entityType").contains(field.getKey())) continue;
			normalizeElement(field.getValue(), context, blocks, order, discarded,
					sourcePath + "." + field.getKey());
		}
	}

	private boolean isRegionNode(String type) {
		String normalized = Objects.toString(type, "").toLowerCase(Locale.ROOT);
		return normalized.contains("card") || normalized.contains("table")
				|| normalized.contains("row") || normalized.contains("keyvalue")
				|| normalized.contains("region") || normalized.contains("section");
	}

	private Double confidence(JsonNode node, Double fallback) {
		for (JsonNode candidate : List.of(node.path("confidence"), node.path("score"),
				node.path("confidence").path("score"), node.path("metadata").path("confidence"))) {
			if (candidate.isNumber()) return Double.valueOf(clampConfidence(candidate.asDouble()));
		}
		return fallback;
	}

	private int pageIndex(JsonNode node, int fallback) {
		if (node.path("pageIndex").canConvertToInt()) return Math.max(0, node.path("pageIndex").asInt());
		JsonNode page = node.path("page");
		if (page.path("pageIndex").canConvertToInt()) return Math.max(0, page.path("pageIndex").asInt());
		if (page.canConvertToInt()) return Math.max(0, page.asInt());
		if (node.path("pageNumber").canConvertToInt()) return Math.max(0, node.path("pageNumber").asInt() - 1);
		return fallback;
	}

	private BoundingBox splitLineBox(BoundingBox box, int line, int count) {
		if (box == null || count <= 1) return box;
		double height = box.height() / count;
		return new BoundingBox(box.x(), box.y() + height * line, box.width(), height);
	}

	private String normalizeEvidenceText(String text) {
		if (text == null) return null;
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
				.replace("\r\n", "\n").replace('\r', '\n')
				.replaceAll("[\\t\\x0B\\f ]+", " ")
				.replaceAll(" *\\n *", "\n").trim();
		return normalized.isBlank() ? null : normalized;
	}

	private List<PositionedToken<String>> flightTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<String>> tokens = new ArrayList<>();
		for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
			EvidenceBlock block = blocks.get(blockIndex);
			List<TextSpan> dateSpans = dateParser.findDates(block.text(),
					TravelDocumentDateParser.Source.FREE_TEXT).stream()
					.map(match -> new TextSpan(match.start(), match.end())).toList();
			Matcher matcher = FLIGHT_NUMBER_IN_TEXT.matcher(block.text().toUpperCase(Locale.ROOT));
			while (matcher.find()) {
				String normalized = normalizeFlightNumber(matcher.group(1));
				if (normalized != null && airlineRegistry.resolveFlightPrefix(normalized).candidates().isEmpty()) {
					String repaired = repairFlightLikeOccurrence(matcher.group(1),
							containsAnyLabel(block.text(), FLIGHT_NUMBER_LABELS),
							structureEvidenceCategoryCount(blocks, block), aircraftContext(blocks, block));
					if (repaired != null) normalized = repaired;
				}
				boolean overlapsDate = dateSpans.stream()
						.anyMatch(span -> span.overlaps(matcher.start(), matcher.end()));
				if (!overlapsDate && normalized != null
						&& !aircraftContext(blocks, block)
						&& metadataContext(blocks, blockIndex, matcher.start(), matcher.end())
								== MetadataContext.NONE
						&& validFlightContext(block.text(), matcher.start(), matcher.end())) {
					String matched = matcher.group(1);
					String reason = matched.matches(".*[\\s-].*")
							? "SPATIAL_WITHIN_ELEMENT_JOIN" : "SPATIAL_COMPLETE_TOKEN";
					tokens.add(new PositionedToken<>(block, matcher.start(), normalized,
							block.confidence() == null ? 0.55d : block.confidence(), reason,
							"element " + block.documentOrder()));
				}
			}
		}
		tokens.addAll(joinAdjacentFlightTokens(blocks));
		tokens.addAll(airlineNameDerivedFlightTokens(blocks));
		tokens.sort(TOKEN_ORDER);
		LinkedHashMap<String, PositionedToken<String>> unique = new LinkedHashMap<>();
		for (PositionedToken<String> token : tokens) {
			String key = token.value() + "|" + token.block().documentOrder() + "|" + token.reasonCode();
			unique.putIfAbsent(key, token);
		}
		return List.copyOf(unique.values());
	}

	private List<PositionedToken<String>> airlineNameDerivedFlightTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<String>> inferred = new ArrayList<>();
		for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
			EvidenceBlock suffixBlock = blocks.get(blockIndex);
			String region = Objects.toString(suffixBlock.regionPath(), "$");
			if ("$".equals(region) || aircraftContext(blocks, suffixBlock)) continue;
			boolean labelBound = containsAnyLabel(suffixBlock.text(), FLIGHT_NUMBER_LABELS)
					|| blocks.stream().anyMatch(block -> block.pageIndex() == suffixBlock.pageIndex()
							&& region.equals(block.regionPath())
							&& containsAnyLabel(block.text(), FLIGHT_NUMBER_LABELS));
			if (!labelBound || structureEvidenceCategoryCount(blocks, suffixBlock) < 2
					|| completeFlightExistsInRegion(blocks, suffixBlock)) continue;
			List<String> airlineNames = blocks.stream()
					.filter(block -> block.pageIndex() == suffixBlock.pageIndex()
							&& region.equals(block.regionPath()))
					.map(EvidenceBlock::text).map(this::normalizedAirlineDisplayName)
					.filter(Objects::nonNull).toList();
			if (airlineNames.size() != 1) continue;
			Matcher suffixMatcher = BARE_FLIGHT_SUFFIX.matcher(suffixBlock.text());
			while (suffixMatcher.find()) {
				if (metadataContext(blocks, blockIndex, suffixMatcher.start(1), suffixMatcher.end(1))
						!= MetadataContext.NONE) continue;
				String flight = inferredFlightFromAirlineName(airlineNames.get(0), suffixMatcher.group(1));
				if (flight != null) inferred.add(new PositionedToken<>(suffixBlock,
						suffixMatcher.start(1), flight,
						suffixBlock.confidence() == null ? 0.50d : suffixBlock.confidence(),
						"AIRLINE_NAME_FLIGHT_LABELLED_SUFFIX",
						"element " + suffixBlock.documentOrder()));
			}
		}
		return inferred;
	}

	private boolean completeFlightExistsInRegion(List<EvidenceBlock> blocks, EvidenceBlock reference) {
		String region = Objects.toString(reference.regionPath(), "$");
		for (EvidenceBlock block : blocks) {
			if (block.pageIndex() != reference.pageIndex() || !region.equals(block.regionPath())) continue;
			Matcher matcher = FLIGHT_NUMBER_IN_TEXT.matcher(block.text());
			while (matcher.find()) if (normalizeFlightNumber(matcher.group(1)) != null) return true;
		}
		return false;
	}

	private int structureEvidenceCategoryCount(List<EvidenceBlock> blocks, EvidenceBlock reference) {
		String region = Objects.toString(reference.regionPath(), "$");
		boolean airport = false;
		boolean date = false;
		boolean time = false;
		for (EvidenceBlock block : blocks) {
			if (block.pageIndex() != reference.pageIndex() || !region.equals(block.regionPath())) continue;
			airport |= IATA_IN_TEXT.matcher(block.text()).find();
			date |= !dateParser.findDates(block.text(), TravelDocumentDateParser.Source.FREE_TEXT).isEmpty();
			time |= STRICT_TIME_IN_TEXT.matcher(block.text()).find();
		}
		return (airport ? 1 : 0) + (date ? 1 : 0) + (time ? 1 : 0);
	}

	private boolean aircraftContext(List<EvidenceBlock> blocks, EvidenceBlock occurrence) {
		if (aircraftContext(occurrence.text())) return true;
		for (EvidenceBlock block : blocks) {
			if (block == occurrence || block.pageIndex() != occurrence.pageIndex()
					|| !sameLocalRegion(block, occurrence)) continue;
			if (Math.abs(block.documentOrder() - occurrence.documentOrder()) <= 1
					&& aircraftContext(block.text())) return true;
		}
		return false;
	}

	private boolean aircraftContext(String value) {
		return containsAnyLabel(value, AIRCRAFT_LABELS);
	}

	private boolean containsAnyLabel(String value, Set<String> labels) {
		String normalized = normalizeMetadataText(value);
		if (normalized == null) return false;
		for (String label : labels) {
			String normalizedLabel = normalizeMetadataText(label);
			if (normalizedLabel == null) continue;
			if (normalizedLabel.codePoints().anyMatch(codePoint -> codePoint > 127)) {
				if (normalized.contains(normalizedLabel)) return true;
			} else if (Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(normalizedLabel)
					+ "(?![A-Z0-9])").matcher(normalized).find()) return true;
		}
		return false;
	}

	private String inferredFlightFromAirlineName(String airlineName, String suffix) {
		if (airlineName == null || suffix == null) return null;
		AirlineResolution resolution = airlineRegistry.searchDisplayName(airlineName);
		if (resolution.ambiguous()) return null;
		String iata = resolution.uniqueCandidate().map(candidate -> candidate.iata()).orElse(null);
		return normalizeFlightNumber(iata == null ? null : iata + suffix);
	}

	private String normalizedAirlineDisplayName(String value) {
		if (value == null) return null;
		String candidate = Normalizer.normalize(value, Normalizer.Form.NFKC)
				.replaceFirst("(?i)^(?:AIRLINE|CARRIER|航空公司)[\\s:：-]*", "")
				.replaceAll("^[\\s:：|/-]+|[\\s:：|/-]+$", "").trim();
		return normalizeAirlineName(candidate);
	}

	private List<PositionedToken<String>> airlineNameTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<String>> tokens = new ArrayList<>();
		for (EvidenceBlock block : blocks) {
			Matcher matcher = AIRLINE_NAME_WITH_SUFFIX.matcher(block.text());
			String value = normalizedAirlineDisplayName(matcher.matches() ? matcher.group(1) : block.text());
			if (value == null) continue;
			tokens.add(new PositionedToken<>(block, 0, value,
					block.confidence() == null ? 0.50d : block.confidence(),
					"STRUCTURE_AIRLINE_NAME", "element " + block.documentOrder()));
		}
		tokens.sort(TOKEN_ORDER);
		return List.copyOf(tokens);
	}

	@SafeVarargs
	private final void markBound(Set<PositionedToken<?>> bound,
			PositionedToken<?>... tokens) {
		for (PositionedToken<?> token : tokens) if (token != null) bound.add(token);
	}

	private List<DocumentFieldCandidate> spatialDiagnosticCandidates(
			List<PositionedToken<String>> flights, List<PositionedToken<String>> airlineNames,
			List<PositionedToken<DateParts>> dates, List<PositionedToken<String>> times,
			List<PositionedToken<String>> airports, List<PositionedToken<String>> terminals,
			List<SegmentAnchor> anchors, Set<PositionedToken<?>> bound) {
		List<DocumentFieldCandidate> diagnostics = new ArrayList<>();
		for (PositionedToken<String> token : flights) {
			if (!bound.contains(token)) {
				boolean replaced = isReplacedByAirlineNameCorrection(token, flights);
				addSpatialDiagnostic(diagnostics, "flight_number", token.value(), token,
						nearestSegmentIndex(token, anchors),
						replaced ? Disposition.REPLACED : Disposition.IGNORED,
						replaced ? "AIRLINE_NAME_CORRECTION" : "UNBOUND_FLIGHT_TOKEN");
			}
		}
		for (PositionedToken<String> token : airlineNames) {
			boolean boundByDerivedFlight = anchors.stream().anyMatch(anchor ->
					anchor.token().reasonCode().startsWith("AIRLINE_NAME_")
							&& anchor.token().block() == token.block());
			if (!boundByDerivedFlight) addSpatialDiagnostic(diagnostics, "airline_name",
					token.value(), token, nearestSegmentIndex(token, anchors), "UNBOUND_AIRLINE_NAME");
		}
		for (PositionedToken<DateParts> token : dates) {
			if (!bound.contains(token) && !arrivalDateContext(token.block().text())) {
				addSpatialDiagnostic(diagnostics, "departure_date", token.value().publicValue(), token,
						nearestSegmentIndex(token, anchors), "UNBOUND_DEPARTURE_DATE");
			}
		}
		for (PositionedToken<String> token : times) {
			if (!bound.contains(token)) addSpatialDiagnostic(diagnostics,
					roleField(token, anchors, "departure_time", "arrival_time"), token.value(), token,
					nearestSegmentIndex(token, anchors), ambiguousTimeRole(token, anchors)
							? "AMBIGUOUS_TIME_ROLE" : "UNBOUND_TIME_EVIDENCE");
		}
		for (PositionedToken<String> token : airports) {
			if (!bound.contains(token)) addSpatialDiagnostic(diagnostics,
					roleField(token, anchors, "departure_airport", "arrival_airport"), token.value(), token,
					nearestSegmentIndex(token, anchors), "UNBOUND_AIRPORT_TOKEN");
		}
		for (PositionedToken<String> token : terminals) {
			if (!bound.contains(token)) addSpatialDiagnostic(diagnostics,
					roleField(token, anchors, "departure_terminal", "arrival_terminal"), token.value(), token,
					nearestSegmentIndex(token, anchors), "UNBOUND_TERMINAL_TOKEN");
		}
		LinkedHashMap<String, DocumentFieldCandidate> unique = new LinkedHashMap<>();
		for (DocumentFieldCandidate candidate : diagnostics) {
			String key = candidate.field() + "|" + candidate.normalizedValue() + "|"
					+ candidate.page() + "|" + candidate.boundingBox();
			unique.putIfAbsent(key, candidate);
		}
		return List.copyOf(unique.values());
	}

	private Integer nearestSegmentIndex(PositionedToken<?> token, List<SegmentAnchor> anchors) {
		if (anchors.isEmpty()) return null;
		for (int index = 0; index < anchors.size(); index++) {
			SegmentAnchor anchor = anchors.get(index);
			if (anchor.token().block().pageIndex() == token.block().pageIndex()
					&& comparePosition(token, anchor.token()) <= 0) return index;
		}
		for (int index = anchors.size() - 1; index >= 0; index--) {
			if (anchors.get(index).token().block().pageIndex() == token.block().pageIndex()) return index;
		}
		return null;
	}

	private boolean ambiguousTimeRole(PositionedToken<?> token, List<SegmentAnchor> anchors) {
		if (explicitTimeRole(token.block().text()) != TimeRole.UNKNOWN) return false;
		SegmentAnchor previous = null;
		SegmentAnchor next = null;
		for (SegmentAnchor anchor : anchors) {
			if (anchor.token().block().pageIndex() != token.block().pageIndex()) continue;
			if (comparePosition(anchor.token(), token) < 0) previous = anchor;
			else if (comparePosition(anchor.token(), token) > 0) {
				next = anchor;
				break;
			}
		}
		if (previous == null || next == null) return false;
		double previousDistance = evidenceDistance(token, previous.token());
		double nextDistance = evidenceDistance(token, next.token());
		double tolerance = token.block().hasBounds()
				? Math.max(4.0d, token.block().height() * 0.35d) : 0.25d;
		return Math.abs(previousDistance - nextDistance) <= tolerance;
	}

	private String roleField(PositionedToken<?> token, List<SegmentAnchor> anchors,
			String departureField, String arrivalField) {
		TimeRole explicitRole = explicitTimeRole(token.block().text());
		if (explicitRole == TimeRole.ARRIVAL) return arrivalField;
		if (explicitRole == TimeRole.DEPARTURE) return departureField;
		for (SegmentAnchor anchor : anchors) {
			if (anchor.token().block().pageIndex() == token.block().pageIndex()
					&& comparePosition(token, anchor.token()) < 0) return departureField;
		}
		return arrivalField;
	}

	private void addSpatialDiagnostic(List<DocumentFieldCandidate> destination, String field,
			String value, PositionedToken<?> token, Integer segmentIndex, String reason) {
		addSpatialDiagnostic(destination, field, value, token, segmentIndex, Disposition.IGNORED, reason);
	}

	private void addSpatialDiagnostic(List<DocumentFieldCandidate> destination, String field,
			String value, PositionedToken<?> token, Integer segmentIndex,
			Disposition disposition, String reason) {
		if (value == null || token == null) return;
		destination.add(new DocumentFieldCandidate(field, value, value,
				DocumentFieldCandidate.Source.STRUCTURE, token.confidence(),
				token.block().pageIndex(), token.block().box(), anchorType(field), segmentIndex,
				disposition, reason));
	}

	private List<PositionedToken<String>> joinAdjacentFlightTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<String>> joined = new ArrayList<>();
		for (int leftIndex = 0; leftIndex < blocks.size(); leftIndex++) {
			EvidenceBlock prefixBlock = blocks.get(leftIndex);
			String prefix = compactToken(prefixBlock.text());
			if (prefix == null || !FLIGHT_PREFIX_FRAGMENT.matcher(prefix).matches()) continue;
			for (int rightIndex = leftIndex + 1; rightIndex < blocks.size() && rightIndex <= leftIndex + 2;
					rightIndex++) {
				EvidenceBlock suffixBlock = blocks.get(rightIndex);
				String suffix = compactToken(suffixBlock.text());
				if (suffix == null || !FLIGHT_SUFFIX_FRAGMENT.matcher(suffix).matches()) continue;
				if (!validFlightContext(prefixBlock.text(), 0, prefixBlock.text().length())
						|| !validFlightContext(suffixBlock.text(), 0, suffixBlock.text().length())
						|| aircraftContext(blocks, prefixBlock) || aircraftContext(blocks, suffixBlock)
						|| metadataContext(blocks, leftIndex, 0, prefixBlock.text().length())
								!= MetadataContext.NONE
						|| metadataContext(blocks, rightIndex, 0, suffixBlock.text().length())
								!= MetadataContext.NONE) continue;
				if (!mayJoinFlightFragments(prefixBlock, suffixBlock)) continue;
				String flight = normalizeFlightNumber(prefix + suffix);
				if (flight == null || airlineRegistry.resolveFlightPrefix(flight).candidates().isEmpty()) continue;
				double confidence = Math.min(prefixBlock.confidence() == null ? 0.55d : prefixBlock.confidence(),
						suffixBlock.confidence() == null ? 0.55d : suffixBlock.confidence());
				joined.add(new PositionedToken<>(prefixBlock, 0, flight, confidence,
						"SPATIAL_ADJACENT_TOKENS", "elements " + prefixBlock.documentOrder()
								+ "+" + suffixBlock.documentOrder()));
			}
		}
		return joined;
	}

	private String compactToken(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
				.toUpperCase(Locale.ROOT).replaceAll("[\\s._/-]+", "");
		return normalized.isBlank() ? null : normalized;
	}

	private boolean mayJoinFlightFragments(EvidenceBlock prefix, EvidenceBlock suffix) {
		if (prefix.pageIndex() != suffix.pageIndex()) return false;
		String prefixRegion = Objects.toString(prefix.regionPath(), "$");
		String suffixRegion = Objects.toString(suffix.regionPath(), "$");
		if (!"$".equals(prefixRegion) || !"$".equals(suffixRegion)) {
			if (!sameLocalRegion(prefix, suffix)) return false;
		}
		if (!prefix.hasBounds() || !suffix.hasBounds()) {
			return sameLocalRegion(prefix, suffix)
					&& suffix.documentOrder() == prefix.documentOrder() + 1;
		}
		double overlap = Math.max(0.0d, Math.min(prefix.y() + prefix.height(), suffix.y() + suffix.height())
				- Math.max(prefix.y(), suffix.y()));
		double minHeight = Math.max(1.0d, Math.min(prefix.height(), suffix.height()));
		if (overlap / minHeight < 0.45d) return false;
		double gap = suffix.x() - (prefix.x() + prefix.width());
		double maxGap = Math.max(24.0d, Math.max(prefix.height(), suffix.height()) * 4.0d);
		return gap >= -Math.min(prefix.width(), suffix.width()) * 0.2d && gap <= maxGap;
	}

	private boolean validFlightContext(String text, int start, int end) {
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
		int from = Math.max(0, start - 28);
		int to = Math.min(normalized.length(), end + 28);
		String context = normalized.substring(from, to);
		return !context.matches(".*\\b\\d{1,3}\\s*(?:KG|KGS|LB|LBS)\\b.*");
	}

	MetadataContext metadataContext(List<EvidenceBlock> blocks, int blockIndex,
			int occurrenceStart, int occurrenceEnd) {
		if (blocks == null || blockIndex < 0 || blockIndex >= blocks.size()) {
			return MetadataContext.NONE;
		}
		EvidenceBlock block = blocks.get(blockIndex);
		String text = Objects.toString(block.text(), "");
		int start = Math.max(0, Math.min(occurrenceStart, text.length()));
		int end = Math.max(start, Math.min(occurrenceEnd, text.length()));
		MetadataContext inline = inlineMetadataContext(text, start, end);
		if (inline != MetadataContext.NONE) return inline;
		if (contactBlockContext(text)) return MetadataContext.CONTACT;
		if (currencyAmountOccurrence(text, start, end)
				|| currencyAmountPair(blocks, blockIndex)) return MetadataContext.FINANCIAL;
		for (int index = Math.max(0, blockIndex - 2);
				index <= Math.min(blocks.size() - 1, blockIndex + 2); index++) {
			if (index == blockIndex) continue;
			EvidenceBlock labelBlock = blocks.get(index);
			MetadataContext context = metadataLabelContext(labelBlock.text());
			if (context != MetadataContext.NONE && metadataPair(labelBlock, block)) return context;
			if (isShortContactLabel(labelBlock.text()) && metadataPair(labelBlock, block)
					&& (contactPayload(text) || pairedContactOrIdentityLabel(blocks, index, labelBlock))) {
				return MetadataContext.CONTACT;
			}
		}
		return MetadataContext.NONE;
	}

	private boolean pairedContactOrIdentityLabel(List<EvidenceBlock> blocks, int shortLabelIndex,
			EvidenceBlock shortLabel) {
		String shortValue = normalizeMetadataText(shortLabel.text());
		boolean genericNumber = Set.of("NBR", "NO", "NUMBER", "NUM").contains(shortValue);
		for (int index = Math.max(0, shortLabelIndex - 2);
				index <= Math.min(blocks.size() - 1, shortLabelIndex + 2); index++) {
			if (index == shortLabelIndex) continue;
			EvidenceBlock candidate = blocks.get(index);
			if (!metadataPair(candidate, shortLabel)) continue;
			MetadataContext context = metadataLabelContext(candidate.text());
			if (context == MetadataContext.CONTACT
					|| genericNumber && context == MetadataContext.TICKET_OR_IDENTITY) return true;
		}
		return false;
	}

	private MetadataContext inlineMetadataContext(String text, int start, int end) {
		String normalized = normalizeMetadataText(text);
		if (normalized == null) return MetadataContext.NONE;
		MetadataContext whitespaceSeparated = metadataLabelContext(text.substring(0, start));
		if (whitespaceSeparated != MetadataContext.NONE) return whitespaceSeparated;
		int separator = lastMetadataSeparatorBefore(text, start);
		if (separator >= 0) {
			MetadataContext valueContext = metadataLabelContext(text.substring(0, separator));
			if (valueContext != MetadataContext.NONE) return valueContext;
		}
		int firstSeparator = firstMetadataSeparator(text);
		if (firstSeparator >= 0 && end <= firstSeparator) {
			MetadataContext labelContext = metadataLabelContext(text.substring(0, firstSeparator));
			if (labelContext != MetadataContext.NONE) return labelContext;
		}
		return metadataLabelContext(text);
	}

	private int lastMetadataSeparatorBefore(String text, int start) {
		int result = -1;
		for (char separator : new char[] { ':', '：', '#' }) {
			int found = text.lastIndexOf(separator, Math.max(0, start - 1));
			if (found > result) result = found;
		}
		return result;
	}

	private int firstMetadataSeparator(String text) {
		int result = -1;
		for (char separator : new char[] { ':', '：', '#' }) {
			int found = text.indexOf(separator);
			if (found >= 0 && (result < 0 || found < result)) result = found;
		}
		return result;
	}

	private MetadataContext metadataLabelContext(String value) {
		String label = normalizeMetadataText(value);
		if (label == null) return MetadataContext.NONE;
		label = label.replaceAll("^[\\s:#：-]+|[\\s:#：-]+$", "").trim();
		if (contactBlockContext(value)) return MetadataContext.CONTACT;
		if (CONTACT_LABELS.contains(label)) return MetadataContext.CONTACT;
		if (BOOKING_LABELS.contains(label)) return MetadataContext.BOOKING;
		if (TICKET_OR_IDENTITY_LABELS.contains(label)) return MetadataContext.TICKET_OR_IDENTITY;
		if (FINANCIAL_LABELS.contains(label)) return MetadataContext.FINANCIAL;
		if (ISSUE_DATE_LABELS.contains(label)) return MetadataContext.ISSUE_DATE;
		if (!label.matches(".*\\d.*") && !label.matches(".*(?:→|->|\\bTO\\b).*")) {
			if (containsMetadataLabel(label, CONTACT_LABELS)) return MetadataContext.CONTACT;
			if (containsMetadataLabel(label, ISSUE_DATE_LABELS)) return MetadataContext.ISSUE_DATE;
			if (containsMetadataLabel(label, TICKET_OR_IDENTITY_LABELS)) {
				return MetadataContext.TICKET_OR_IDENTITY;
			}
			if (containsMetadataLabel(label, BOOKING_LABELS)) return MetadataContext.BOOKING;
			if (containsMetadataLabel(label, FINANCIAL_LABELS)) return MetadataContext.FINANCIAL;
		}
		return MetadataContext.NONE;
	}

	private boolean contactBlockContext(String value) {
		String text = normalizeMetadataText(value);
		if (text == null || hasRouteDelimiter(text)) return false;
		if (contactPayload(text)) return true;
		if (Pattern.compile("(?<![A-Z0-9])(?:TEL|FAX|NBR|NO|NUMBER|NUM)\\s*[:：]")
				.matcher(text).find()) return true;
		int labels = 0;
		for (String label : SHORT_CONTACT_LABELS) {
			Matcher matcher = Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(label)
					+ "(?![A-Z0-9])").matcher(text);
			while (matcher.find()) labels++;
		}
		for (String label : CONTACT_LABELS) {
			if (containsMetadataLabel(text, Set.of(label))) labels++;
		}
		return labels >= 2;
	}

	private boolean isShortContactLabel(String value) {
		String normalized = normalizeMetadataText(value);
		if (normalized == null) return false;
		normalized = normalized.replaceAll("^[\\s:#：-]+|[\\s:#：-]+$", "").trim();
		return SHORT_CONTACT_LABELS.contains(normalized);
	}

	private boolean contactPayload(String text) {
		String value = Objects.toString(text, "");
		if (EMAIL_IN_TEXT.matcher(value).find() || URL_IN_TEXT.matcher(value).find()) return true;
		boolean containsDate = !dateParser.findDates(value,
				TravelDocumentDateParser.Source.FREE_TEXT).isEmpty();
		Matcher phone = PHONE_IN_TEXT.matcher(value);
		while (phone.find()) {
			String matched = phone.group();
			long digits = matched.chars().filter(Character::isDigit).count();
			if (!containsDate && digits >= 9 || matched.contains("+")
					|| matched.contains("(") || matched.contains(")")) {
				return true;
			}
		}
		return false;
	}

	private boolean hasRouteDelimiter(String text) {
		return Pattern.compile("(?:→|->|(?<![A-Z0-9])TO(?![A-Z0-9]))",
				Pattern.CASE_INSENSITIVE).matcher(Objects.toString(text, "")).find();
	}

	private boolean containsMetadataLabel(String value, Set<String> labels) {
		for (String label : labels) {
			if (label.codePoints().anyMatch(codePoint -> codePoint > 127)) {
				if (value.contains(label)) return true;
			} else if (Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(label)
					+ "(?![A-Z0-9])").matcher(value).find()) return true;
		}
		return false;
	}

	private String normalizeMetadataText(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
				.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
		return normalized.isBlank() ? null : normalized;
	}

	private boolean currencyAmountOccurrence(String text, int start, int end) {
		String normalized = Normalizer.normalize(Objects.toString(text, ""), Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT);
		Matcher matcher = CURRENCY_AMOUNT.matcher(normalized);
		while (matcher.find()) {
			if (CURRENCY_CODES.contains(matcher.group(1))
					&& start < matcher.end() && end > matcher.start()) return true;
		}
		return false;
	}

	private boolean currencyAmountPair(List<EvidenceBlock> blocks, int blockIndex) {
		EvidenceBlock block = blocks.get(blockIndex);
		String token = normalizeMetadataText(block.text());
		boolean currency = token != null && CURRENCY_CODES.contains(token);
		boolean amount = token != null && token.matches("[0-9]+(?:[.,][0-9]{2})?");
		if (!currency && !amount) return false;
		for (int index = Math.max(0, blockIndex - 1);
				index <= Math.min(blocks.size() - 1, blockIndex + 1); index++) {
			if (index == blockIndex) continue;
			EvidenceBlock other = blocks.get(index);
			if (!metadataPair(block, other)) continue;
			String otherToken = normalizeMetadataText(other.text());
			if (currency && otherToken != null && otherToken.matches("[0-9]+(?:[.,][0-9]{2})?")) {
				return true;
			}
			if (amount && otherToken != null && CURRENCY_CODES.contains(otherToken)) return true;
		}
		return false;
	}

	private boolean metadataPair(EvidenceBlock label, EvidenceBlock value) {
		if (label.pageIndex() != value.pageIndex()) return false;
		if (!label.hasBounds() || !value.hasBounds()) {
			return sameLocalRegion(label, value)
					&& Math.abs(label.documentOrder() - value.documentOrder()) <= 1;
		}
		double verticalOverlap = Math.max(0.0d,
				Math.min(label.y() + label.height(), value.y() + value.height())
						- Math.max(label.y(), value.y()));
		double minHeight = Math.max(1.0d, Math.min(label.height(), value.height()));
		double horizontalGap = Math.max(0.0d,
				Math.max(label.x(), value.x())
						- Math.min(label.x() + label.width(), value.x() + value.width()));
		boolean sameRow = verticalOverlap / minHeight >= 0.45d
				&& horizontalGap <= Math.max(80.0d, minHeight * 6.0d);
		double verticalGap = Math.max(0.0d,
				Math.max(label.y(), value.y())
						- Math.min(label.y() + label.height(), value.y() + value.height()));
		boolean stackedPair = verticalGap <= Math.max(20.0d, minHeight * 1.5d)
				&& Math.abs(label.x() - value.x()) <= Math.max(48.0d, minHeight * 3.0d);
		return sameRow || stackedPair;
	}

	private List<SegmentAnchor> anchors(List<PositionedToken<String>> tokens,
			List<PositionedToken<String>> airports, List<PositionedToken<DateParts>> dates,
			List<PositionedToken<String>> times, List<PositionedToken<String>> terminals) {
		tokens = preferUniqueAirlineNameCorrections(tokens);
		List<PositionedToken<String>> best = new ArrayList<>();
		for (PositionedToken<String> token : tokens) {
			int duplicate = -1;
			for (int index = 0; index < best.size(); index++) {
				PositionedToken<String> existing = best.get(index);
				if (existing.value().equals(token.value())
						&& sameFlightDisplay(existing, token, airports, dates, times)) {
					duplicate = index;
					break;
				}
			}
			if (duplicate < 0) best.add(token);
			else if (anchorScore(token, airports, times, terminals)
					> anchorScore(best.get(duplicate), airports, times, terminals)) best.set(duplicate, token);
		}
		return best.stream()
				.map(token -> new SegmentAnchor(token.value(), token))
				.sorted((left, right) -> TOKEN_ORDER.compare(left.token(), right.token()))
				.toList();
	}

	private List<PositionedToken<String>> preferUniqueAirlineNameCorrections(
			List<PositionedToken<String>> tokens) {
		Set<PositionedToken<String>> suppressed = Collections.newSetFromMap(new IdentityHashMap<>());
		for (PositionedToken<String> token : tokens) {
			if (isReplacedByAirlineNameCorrection(token, tokens)) suppressed.add(token);
		}
		return tokens.stream().filter(token -> !suppressed.contains(token)).toList();
	}

	private boolean isReplacedByAirlineNameCorrection(PositionedToken<String> token,
			List<PositionedToken<String>> tokens) {
		if (token.reasonCode().startsWith("AIRLINE_NAME_")) return false;
		for (PositionedToken<String> derived : tokens) {
			if (!derived.reasonCode().startsWith("AIRLINE_NAME_")
					|| Objects.equals(normalizeFlightNumber(derived.value()), normalizeFlightNumber(token.value()))
					|| !airlineRegistry.resolveFlightPrefix(derived.value()).isHighConfidence()
					|| !Objects.equals(flightNumericSuffix(derived.value()),
							flightNumericSuffix(token.value()))
					|| token.block().pageIndex() != derived.block().pageIndex()
					|| !sameLocalRegion(token.block(), derived.block())
					|| evidenceDistance(token, derived) > 100.0d) continue;
			// An exact, uniquely resolved airline name in the same card is stronger
			// identity evidence than a conflicting OCR prefix. The original token is
			// retained as replaced diagnostic evidence rather than silently accepted.
			return true;
		}
		return false;
	}

	private boolean sameFlightDisplay(PositionedToken<String> left, PositionedToken<String> right,
			List<PositionedToken<String>> airports, List<PositionedToken<DateParts>> dates,
			List<PositionedToken<String>> times) {
		if (left.block().pageIndex() != right.block().pageIndex()) return false;
		String leftRegion = Objects.toString(left.block().regionPath(), "$");
		String rightRegion = Objects.toString(right.block().regionPath(), "$");
		if (!"$".equals(leftRegion) || !"$".equals(rightRegion)) return leftRegion.equals(rightRegion);
		DateParts leftDate = governingDateValue(dates, left);
		DateParts rightDate = governingDateValue(dates, right);
		if (leftDate != null && rightDate != null
				&& !Objects.equals(leftDate.date(), rightDate.date())) return false;
		String leftTime = localNearestValue(times, left);
		String rightTime = localNearestValue(times, right);
		if (leftTime != null && rightTime != null && !leftTime.equals(rightTime)) return false;
		TokenRoute leftRoute = localRoute(airports, left);
		TokenRoute rightRoute = localRoute(airports, right);
		if (leftRoute != null && rightRoute != null
				&& (!leftRoute.departure().value().equals(rightRoute.departure().value())
						|| !leftRoute.arrival().value().equals(rightRoute.arrival().value()))) return false;
		boolean bothDense = anchorLocalEvidenceCount(left, airports, times) >= 2
				&& anchorLocalEvidenceCount(right, airports, times) >= 2;
		return !bothDense || evidenceDistance(left, right) <= 120.0d;
	}

	private DateParts governingDateValue(List<PositionedToken<DateParts>> dates,
			PositionedToken<?> anchor) {
		PositionedToken<DateParts> selected = null;
		for (PositionedToken<DateParts> date : dates) {
			if (date.block().pageIndex() != anchor.block().pageIndex()) continue;
			if (comparePosition(date, anchor) >= 0) break;
			if (!arrivalDateContext(date.block().text()) && sameLocalRegion(date.block(), anchor.block())) {
				selected = date;
			}
		}
		return selected == null ? null : selected.value();
	}

	private String localNearestValue(List<PositionedToken<String>> tokens, PositionedToken<?> anchor) {
		PositionedToken<String> nearest = null;
		double bestDistance = Double.MAX_VALUE;
		for (PositionedToken<String> token : tokens) {
			if (token.block().pageIndex() != anchor.block().pageIndex()
					|| !sameLocalRegion(token.block(), anchor.block())) continue;
			double distance = evidenceDistance(token, anchor);
			if (distance < bestDistance && distance <= 100.0d) {
				bestDistance = distance;
				nearest = token;
			}
		}
		return nearest == null ? null : nearest.value();
	}

	private TokenRoute localRoute(List<PositionedToken<String>> airports, PositionedToken<?> anchor) {
		PositionedToken<String> before = null;
		PositionedToken<String> after = null;
		for (PositionedToken<String> airport : airports) {
			if (airport.block().pageIndex() != anchor.block().pageIndex()
					|| !sameLocalRegion(airport.block(), anchor.block())) continue;
			if (comparePosition(airport, anchor) < 0 && evidenceDistance(airport, anchor) <= 160.0d) before = airport;
			if (comparePosition(airport, anchor) > 0 && evidenceDistance(airport, anchor) <= 160.0d) {
				after = airport;
				break;
			}
		}
		return before == null || after == null ? null : new TokenRoute(before, after);
	}

	private int anchorLocalEvidenceCount(PositionedToken<?> anchor,
			List<PositionedToken<String>> airports, List<PositionedToken<String>> times) {
		int count = 0;
		for (PositionedToken<?> token : new ArrayList<PositionedToken<?>>(airports)) {
			if (token.block().pageIndex() == anchor.block().pageIndex()
					&& sameLocalRegion(token.block(), anchor.block())
					&& evidenceDistance(token, anchor) <= 160.0d) count++;
		}
		for (PositionedToken<?> token : new ArrayList<PositionedToken<?>>(times)) {
			if (token.block().pageIndex() == anchor.block().pageIndex()
					&& sameLocalRegion(token.block(), anchor.block())
					&& evidenceDistance(token, anchor) <= 100.0d) count++;
		}
		return count;
	}

	private double anchorScore(PositionedToken<String> token,
			List<PositionedToken<String>> airports, List<PositionedToken<String>> times,
			List<PositionedToken<String>> terminals) {
		String text = token.block().text().replaceAll("\\s", "");
		double isolated = normalizeFlightNumber(text) != null ? 1.0d : 0.0d;
		String type = Objects.toString(token.block().elementType(), "").toLowerCase(Locale.ROOT);
		double structured = type.contains("table") || type.contains("cell") || type.contains("keyvalue")
				? 0.2d : 0.0d;
		return token.confidence() + isolated + structured
				+ localEvidenceScore(token, airports, 0.8d)
				+ localEvidenceScore(token, times, 0.45d)
				+ localEvidenceScore(token, terminals, 0.2d);
	}

	private double localEvidenceScore(PositionedToken<?> anchor,
			List<? extends PositionedToken<?>> evidence, double weight) {
		double score = 0.0d;
		for (PositionedToken<?> token : evidence) {
			if (token.block().pageIndex() != anchor.block().pageIndex()) continue;
			int orderDistance = Math.abs(token.block().documentOrder()
					- anchor.block().documentOrder());
			if (orderDistance > 8) continue;
			score += weight / (1.0d + orderDistance);
		}
		return Math.min(weight * 2.0d, score);
	}

	private List<PositionedToken<String>> airportTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<String>> tokens = new ArrayList<>();
		for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
			EvidenceBlock block = blocks.get(blockIndex);
			List<TextSpan> flightSpans = new ArrayList<>();
			Matcher flightMatcher = FLIGHT_NUMBER_IN_TEXT.matcher(block.text());
			while (flightMatcher.find()) flightSpans.add(
					new TextSpan(flightMatcher.start(1), flightMatcher.end(1)));
			Matcher matcher = IATA_IN_TEXT.matcher(block.text());
			while (matcher.find()) {
				String value = matcher.group(1);
				if (flightSpans.stream().anyMatch(span ->
						span.overlaps(matcher.start(), matcher.end()))) continue;
				if (metadataContext(blocks, blockIndex, matcher.start(), matcher.end())
						!= MetadataContext.NONE) continue;
				tokens.add(new PositionedToken<>(block, matcher.start(), value,
						block.confidence() == null ? 0.55d : block.confidence()));
			}
		}
		tokens.sort(TOKEN_ORDER);
		return List.copyOf(tokens);
	}

	private List<PositionedToken<DateParts>> dateTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<DateParts>> tokens = new ArrayList<>();
		for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
			EvidenceBlock block = blocks.get(blockIndex);
			for (TravelDocumentDateParser.DateMatch match : dateParser.findDates(block.text(),
					TravelDocumentDateParser.Source.FREE_TEXT)) {
				DateParts value = DateParts.from(match);
				if (value.hasValue() && metadataContext(blocks, blockIndex, match.start(), match.end())
						== MetadataContext.NONE) tokens.add(new PositionedToken<>(block, match.start(), value,
						block.confidence() == null ? 0.55d : block.confidence()));
			}
		}
		tokens.sort(TOKEN_ORDER);
		return List.copyOf(tokens);
	}

	private List<PositionedToken<String>> timeTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<String>> tokens = new ArrayList<>();
		for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
			EvidenceBlock block = blocks.get(blockIndex);
			Matcher matcher = TIME_IN_TEXT.matcher(block.text());
			while (matcher.find()) {
				String value = normalizeTime(matcher.group());
				if (value != null && metadataContext(blocks, blockIndex, matcher.start(), matcher.end())
						== MetadataContext.NONE) {
					tokens.add(new PositionedToken<>(block, matcher.start(), value,
							block.confidence() == null ? 0.55d : block.confidence()));
				}
			}
		}
		tokens.sort(TOKEN_ORDER);
		return List.copyOf(tokens);
	}

	private List<PositionedToken<String>> terminalTokens(List<EvidenceBlock> blocks) {
		return textTokens(blocks, TERMINAL_IN_TEXT,
				matcher -> "T" + matcher.group(1).toUpperCase(Locale.ROOT));
	}

	private List<PositionedToken<String>> textTokens(List<EvidenceBlock> blocks, Pattern pattern,
			Function<Matcher, String> valueMapper) {
		List<PositionedToken<String>> tokens = new ArrayList<>();
		for (EvidenceBlock block : blocks) {
			Matcher matcher = pattern.matcher(block.text());
			while (matcher.find()) {
				String value = valueMapper.apply(matcher);
				if (value != null) tokens.add(new PositionedToken<>(block, matcher.start(), value,
						block.confidence() == null ? 0.55d : block.confidence()));
			}
		}
		tokens.sort(TOKEN_ORDER);
		return List.copyOf(tokens);
	}

	private <T> PositionedToken<T> nearestPrevious(List<PositionedToken<T>> tokens,
			PositionedToken<?> anchor, PositionedToken<?> lowerExclusive) {
		PositionedToken<T> result = null;
		for (PositionedToken<T> token : tokens) {
			if (token.block().pageIndex() != anchor.block().pageIndex()) continue;
			if (comparePosition(token, anchor) >= 0) break;
			if (lowerExclusive != null && comparePosition(token, lowerExclusive) <= 0) continue;
			result = token;
		}
		return result;
	}

	private PositionedToken<String> reliableTimeForAnchor(PositionedToken<String> time,
			SegmentAnchor current, SegmentAnchor adjacent, boolean departure) {
		if (time == null) return null;
		TimeRole explicitRole = explicitTimeRole(time.block().text());
		if (explicitRole != TimeRole.UNKNOWN) {
			return (departure && explicitRole == TimeRole.DEPARTURE)
					|| (!departure && explicitRole == TimeRole.ARRIVAL) ? time : null;
		}
		if (adjacent == null) return time;
		double currentDistance = evidenceDistance(time, current.token());
		double adjacentDistance = evidenceDistance(time, adjacent.token());
		double tolerance = time.block().hasBounds()
				? Math.max(4.0d, time.block().height() * 0.35d) : 0.25d;
		// Evidence in the middle of two cards has no reliable role. Leave it out of
		// both fields and retain it as an advisory candidate instead.
		return currentDistance + tolerance < adjacentDistance ? time : null;
	}

	private <T> List<PositionedToken<T>> tokensInCard(List<PositionedToken<T>> tokens,
			SegmentAnchor anchor) {
		if (anchor == null || anchor.cardId() == null) return List.of();
		String region = anchor.cardId().substring(anchor.cardId().indexOf(':', 5) + 1);
		return tokens.stream().filter(token -> token.block().pageIndex() == anchor.token().block().pageIndex()
				&& blockInRegion(token.block(), region)).toList();
	}

	private PositionedToken<DateParts> roleSelectedDate(List<EvidenceBlock> blocks,
			List<PositionedToken<DateParts>> dates,
			boolean arrival) {
		List<PositionedToken<DateParts>> roleMatches = dates.stream()
				.filter(token -> scheduleRole(blocks, token)
						== (arrival ? ScheduleRole.ARRIVAL : ScheduleRole.DEPARTURE)).toList();
		if (!roleMatches.isEmpty()) return uniqueDateToken(roleMatches);
		if (arrival) return null;
		List<PositionedToken<DateParts>> unknown = dates.stream()
				.filter(token -> scheduleRole(blocks, token) == ScheduleRole.UNKNOWN).toList();
		return uniqueDateToken(unknown);
	}

	private PositionedToken<DateParts> uniqueDateToken(List<PositionedToken<DateParts>> tokens) {
		if (tokens == null || tokens.isEmpty()) return null;
		LinkedHashSet<String> values = tokens.stream().map(PositionedToken::value)
				.map(DateParts::publicValue).filter(Objects::nonNull)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if (values.size() == 1) return tokens.get(0);
		PositionedToken<DateParts> first = tokens.get(0);
		return new PositionedToken<>(first.block(), first.characterOffset(), DateParts.CONFLICT,
				first.confidence(), "SCHEDULE_ROLE_CONFLICT", first.evidenceReference());
	}

	private PositionedToken<String> roleSelectedTime(List<EvidenceBlock> blocks,
			List<PositionedToken<String>> times,
			boolean arrival) {
		List<PositionedToken<String>> roleMatches = times.stream()
				.filter(token -> scheduleRole(blocks, token)
						== (arrival ? ScheduleRole.ARRIVAL : ScheduleRole.DEPARTURE)).toList();
		if (!roleMatches.isEmpty()) return uniqueTokenValue(roleMatches);
		if (arrival) return null;
		List<PositionedToken<String>> unknown = times.stream()
				.filter(token -> scheduleRole(blocks, token) == ScheduleRole.UNKNOWN).toList();
		return uniqueTokenValue(unknown);
	}

	private <T> PositionedToken<T> uniqueTokenValue(List<PositionedToken<T>> tokens) {
		if (tokens == null || tokens.isEmpty()) return null;
		LinkedHashMap<T, PositionedToken<T>> distinct = new LinkedHashMap<>();
		for (PositionedToken<T> token : tokens) distinct.putIfAbsent(token.value(), token);
		return distinct.size() == 1 ? distinct.values().iterator().next() : null;
	}

	private TimeRole explicitTimeRole(String text) {
		String normalized = Normalizer.normalize(Objects.toString(text, ""), Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT);
		if (normalized.matches(".*(?:到达|抵达|ARRIVAL|ARRIVE|\\bARR\\b).*")) {
			return TimeRole.ARRIVAL;
		}
		if (normalized.matches(".*(?:出发|起飞|DEPARTURE|DEPART|\\bDEP\\b).*")) {
			return TimeRole.DEPARTURE;
		}
		return TimeRole.UNKNOWN;
	}

	private PositionedToken<DateParts> departureDateForAnchor(
			List<PositionedToken<DateParts>> dates, List<SegmentAnchor> anchors, int anchorIndex) {
		SegmentAnchor current = anchors.get(anchorIndex);
		SegmentAnchor previous = anchorIndex == 0 ? null : anchors.get(anchorIndex - 1);
		PositionedToken<DateParts> selected = null;
		for (PositionedToken<DateParts> date : dates) {
			if (date.block().pageIndex() != current.token().block().pageIndex()) continue;
			if (comparePosition(date, current.token()) >= 0) break;
			if (arrivalDateContext(date.block().text())) continue;
			if (!sameLocalRegion(date.block(), current.token().block())) continue;
			if (previous != null && comparePosition(date, previous.token()) > 0
					&& !looksLikeDateHeading(date.block())
					&& evidenceDistance(date, current.token()) >= evidenceDistance(date, previous.token())) {
				// A standalone date that remains closer to the previous flight card is
				// treated as arrival evidence, never as the next departure title.
				continue;
			}
			selected = date;
		}
		return selected;
	}

	private PositionedToken<DateParts> arrivalDateForAnchor(
			List<PositionedToken<DateParts>> dates, List<SegmentAnchor> anchors, int anchorIndex) {
		SegmentAnchor current = anchors.get(anchorIndex);
		SegmentAnchor next = anchorIndex + 1 < anchors.size() ? anchors.get(anchorIndex + 1) : null;
		PositionedToken<DateParts> selected = null;
		for (PositionedToken<DateParts> date : dates) {
			if (date.block().pageIndex() != current.token().block().pageIndex()) continue;
			if (comparePosition(date, current.token()) <= 0) continue;
			if (next != null && comparePosition(date, next.token()) >= 0) break;
			if (!arrivalDateContext(date.block().text()) || !date.value().hasValue()) continue;
			selected = date;
		}
		return selected;
	}

	private String arrivalDateTime(PositionedToken<DateParts> date,
			PositionedToken<String> time) {
		if (date == null || date.value().date() == null || time == null) return tokenValue(time);
		String timeValue = timePart(time.value());
		if (timeValue == null) return tokenValue(time);
		try {
			return LocalDateTime.of(date.value().date(), LocalTime.parse(timeValue)).toString();
		} catch (DateTimeException exception) {
			return tokenValue(time);
		}
	}

	private boolean arrivalDateContext(String text) {
		String normalized = Normalizer.normalize(Objects.toString(text, ""), Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT);
		return normalized.matches(".*(?:到达|抵达|ARRIVAL|ARRIVE|\\bARR\\b).*");
	}

	private boolean looksLikeDateHeading(EvidenceBlock block) {
		String type = Objects.toString(block.elementType(), "").toLowerCase(Locale.ROOT);
		return type.contains("title") || type.contains("header") || type.contains("heading")
				|| type.contains("section");
	}

	private boolean sameLocalRegion(EvidenceBlock left, EvidenceBlock right) {
		String leftRegion = Objects.toString(left.regionPath(), "$");
		String rightRegion = Objects.toString(right.regionPath(), "$");
		return !"$".equals(leftRegion) && leftRegion.equals(rightRegion);
	}

	private double evidenceDistance(PositionedToken<?> left, PositionedToken<?> right) {
		if (left.block().hasBounds() && right.block().hasBounds()) {
			return Math.abs((left.block().y() + left.block().height() / 2.0d)
					- (right.block().y() + right.block().height() / 2.0d));
		}
		return Double.MAX_VALUE;
	}

	private <T> PositionedToken<T> nearestNext(List<PositionedToken<T>> tokens,
			PositionedToken<?> anchor, PositionedToken<?> upperExclusive) {
		for (PositionedToken<T> token : tokens) {
			if (token.block().pageIndex() != anchor.block().pageIndex()) continue;
			if (comparePosition(token, anchor) <= 0) continue;
			if (upperExclusive != null && comparePosition(token, upperExclusive) >= 0) break;
			return token;
		}
		return null;
	}

	private PositionedToken<String> nearestPreviousAirport(
			List<PositionedToken<String>> airports, PositionedToken<?> anchor,
			PositionedToken<?> lowerExclusive, PositionedToken<?> firstAnchor) {
		PositionedToken<String> fallback = null;
		PositionedToken<String> preferred = null;
		for (PositionedToken<String> token : airports) {
			if (token.block().pageIndex() != anchor.block().pageIndex()) continue;
			if (comparePosition(token, anchor) >= 0) break;
			if (lowerExclusive != null && comparePosition(token, lowerExclusive) <= 0) continue;
			fallback = token;
			if (!weakOverallRouteAirport(token, airports, firstAnchor)) preferred = token;
		}
		return preferred == null ? fallback : preferred;
	}

	private PositionedToken<String> nearestNextDifferentAirport(
			List<PositionedToken<String>> airports, PositionedToken<?> anchor,
			PositionedToken<?> upperExclusive, String excludedAirport) {
		for (PositionedToken<String> token : airports) {
			if (token.block().pageIndex() != anchor.block().pageIndex()) continue;
			if (comparePosition(token, anchor) <= 0) continue;
			if (upperExclusive != null && comparePosition(token, upperExclusive) >= 0) break;
			if (!Objects.equals(excludedAirport, token.value())) return token;
		}
		return null;
	}

	private boolean weakOverallRouteAirport(PositionedToken<String> token,
			List<PositionedToken<String>> airports, PositionedToken<?> firstAnchor) {
		if (token.block().pageIndex() != firstAnchor.block().pageIndex()
				|| EVIDENCE_ORDER.compare(token.block(), firstAnchor.block()) >= 0) return false;
		String type = Objects.toString(token.block().elementType(), "").toLowerCase(Locale.ROOT);
		if (type.contains("title") || type.contains("header") || type.contains("heading")) return true;
		long sameBlockAirports = airports.stream()
				.filter(candidate -> candidate.block().equals(token.block())).count();
		if (sameBlockAirports < 2) return false;
		String text = token.block().text();
		return text.contains("→") || text.contains("->") || text.matches("(?i).*\\bTO\\b.*");
	}

	private TokenRoute horizontalRoute(List<PositionedToken<String>> airports,
			PositionedToken<?> anchor, PositionedToken<?> lowerExclusive,
			PositionedToken<?> upperExclusive, PositionedToken<?> firstAnchor) {
		List<PositionedToken<String>> samePage = airports.stream()
				.filter(token -> token.block().pageIndex() == anchor.block().pageIndex())
				.filter(token -> lowerExclusive == null || comparePosition(token, lowerExclusive) > 0)
				.filter(token -> upperExclusive == null || comparePosition(token, upperExclusive) < 0)
				.filter(token -> !weakOverallRouteAirport(token, airports, firstAnchor))
				.toList();
		if (samePage.size() < 2) return null;
		if (anchor.block().height() <= 0.0d || anchor.block().width() <= 0.0d) return null;
		double band = anchor.block().height() * 2.5d;
		double anchorCenterX = tokenCenterX(anchor);
		double anchorCenterY = anchor.block().y() + anchor.block().height() / 2.0d;
		PositionedToken<String> left = null;
		PositionedToken<String> right = null;
		for (PositionedToken<String> token : samePage) {
			double centerY = token.block().y() + token.block().height() / 2.0d;
			if (Math.abs(centerY - anchorCenterY) > band) continue;
			double centerX = tokenCenterX(token);
			if (centerX <= anchorCenterX && (left == null || centerX > tokenCenterX(left))) left = token;
			if (centerX > anchorCenterX && (right == null || centerX < tokenCenterX(right))) right = token;
		}
		if (left == null || right == null || left.value().equals(right.value())) return null;
		double leftY = left.block().y() + left.block().height() / 2.0d;
		double rightY = right.block().y() + right.block().height() / 2.0d;
		return Math.abs(leftY - rightY) <= band / 2.0d ? new TokenRoute(left, right) : null;
	}

	private double tokenCenterX(PositionedToken<?> token) {
		EvidenceBlock block = token.block();
		int textLength = block.text() == null ? 0 : block.text().length();
		if (textLength <= 0 || block.width() <= 0.0d) return block.x() + block.width() / 2.0d;
		int valueLength = Math.max(1, Objects.toString(token.value(), "").length());
		double characterCenter = Math.min(textLength,
				Math.max(0.0d, token.characterOffset() + valueLength / 2.0d));
		return block.x() + block.width() * characterCenter / textLength;
	}

	private int comparePosition(PositionedToken<?> left, PositionedToken<?> right) {
		return TOKEN_ORDER.compare(left, right);
	}

	private <T> T tokenValue(PositionedToken<T> token) {
		return token == null ? null : token.value();
	}

	private void putSpatialField(Map<String, ExtractedField> fields, String name, String value,
			PositionedToken<?> token) {
		if (token == null || value == null) return;
		EvidenceBlock block = token.block();
		fields.put(name, new ExtractedField(value, value, token.confidence(), block.pageIndex(),
				block.box(), "Nutrient Structure Spatial " + token.evidenceReference()));
	}

	private void putSpatialDateField(Map<String, ExtractedField> fields, DateParts value,
			PositionedToken<DateParts> token) {
		if (token == null || value == null || !value.hasValue()) return;
		EvidenceBlock block = token.block();
		fields.put("departure_date", new ExtractedField(value.publicValue(), value.rawValue(),
				token.confidence(), block.pageIndex(), block.box(),
				"Nutrient Structure Spatial " + token.evidenceReference()));
	}

	private List<DocumentFieldCandidate> evidenceCandidates(SegmentCandidate candidate) {
		if (candidate == null || candidate.segment() == null) return List.of();
		List<DocumentFieldCandidate> evidence = new ArrayList<>();
		addEvidenceCandidate(evidence, candidate, "flight_number", "flight_number");
		addEvidenceCandidate(evidence, candidate, "airline_name", "airline_name");
		addDepartureDateEvidence(evidence, candidate);
		addEvidenceCandidate(evidence, candidate, "departure_time", "scheduled_departure");
		addEvidenceCandidate(evidence, candidate, "arrival_time", "scheduled_arrival");
		addEvidenceCandidate(evidence, candidate, "departure_airport", "departure_airport");
		addEvidenceCandidate(evidence, candidate, "arrival_airport", "arrival_airport");
		ExtractedField departureTerminal = candidate.segment().fields().get("departure_terminal");
		addEvidenceCandidate(evidence, candidate, "departure_terminal",
				departureTerminal != null && departureTerminal.value() != null
						? "departure_terminal" : "terminal");
		addEvidenceCandidate(evidence, candidate, "arrival_terminal", "arrival_terminal");
		return List.copyOf(evidence);
	}

	private void addDepartureDateEvidence(List<DocumentFieldCandidate> destination,
			SegmentCandidate candidate) {
		ExtractedField visible = candidate.segment().fields().get("departure_date");
		DateParts visibleDate = visible == null ? DateParts.EMPTY : departureDateEvidence(
				parseScheduleRawValue("departure_date", visible.rawValue(), visible.rawValue()));
		boolean retainVisibleConflictEvidence = candidate.segment().dateSource() == DateSource.CONFLICT;
		if (visible != null && (visible.value() != null || retainVisibleConflictEvidence)
				&& visibleDate.hasValue()) {
			for (LocalDate date : visibleDate.candidates()) {
				addEvidenceCandidate(destination, candidate, "departure_date", visible,
						date.toString(), "VISIBLE_DEPARTURE_DATE");
			}
		}
		ExtractedField scheduled = candidate.segment().fields().get("scheduled_departure");
		DateParts scheduledDate = scheduled == null ? DateParts.EMPTY : departureDateEvidence(
				parseScheduleRawValue("scheduled_departure", scheduled.rawValue(), scheduled.value()));
		if (scheduled != null && scheduled.value() != null && scheduledDate.hasValue()) {
			for (LocalDate date : scheduledDate.candidates()) {
				addEvidenceCandidate(destination, candidate, "departure_date", scheduled,
						date.toString(), "SCHEDULED_DEPARTURE_DATE");
			}
		}
	}

	private void addEvidenceCandidate(List<DocumentFieldCandidate> destination,
			SegmentCandidate candidate, String publicField, String internalField) {
		ExtractedField field = candidate.segment().fields().get(internalField);
		if (field == null || field.value() == null) return;
		String normalizedValue = switch (publicField) {
			case "departure_time", "arrival_time" -> timePart(field.value());
			default -> field.value();
		};
		if (normalizedValue == null) return;
		if ("flight_number".equals(publicField)
				&& field.sourceLabel() != null && field.sourceLabel().contains("airline-name-registry")) {
			String original = normalizeFlightNumber(field.rawValue());
			if (original != null && !original.equals(normalizedValue)) {
				destination.add(new DocumentFieldCandidate(publicField, field.rawValue(), original,
						DocumentFieldCandidate.Source.UNDERSTAND, field.confidence(), field.page(),
						field.boundingBox(), anchorType(publicField), null, Disposition.REPLACED,
						"AIRLINE_NAME_PREFIX_MISMATCH"));
			}
		}
		addEvidenceCandidate(destination, candidate, publicField, field, normalizedValue,
				field.sourceLabel() != null && field.sourceLabel().contains("airline-name-registry")
						? "AIRLINE_NAME_UNIQUE_CORRECTION"
						: candidate.source() != CandidateSource.STRUCTURE
								&& candidate.source() != CandidateSource.NATIVE_ROW
						? "UNDERSTAND_SCHEMA_FIELD" : "STRUCTURE_SPATIAL_BINDING");
	}

	private void addEvidenceCandidate(List<DocumentFieldCandidate> destination,
			SegmentCandidate candidate, String publicField, ExtractedField field,
			String normalizedValue, String reason) {
		if (field == null || normalizedValue == null) return;
		destination.add(new DocumentFieldCandidate(publicField, field.rawValue(), normalizedValue,
				candidate.source() != CandidateSource.STRUCTURE
						&& candidate.source() != CandidateSource.NATIVE_ROW
						? DocumentFieldCandidate.Source.UNDERSTAND
						: DocumentFieldCandidate.Source.STRUCTURE,
				field.confidence(), field.page(), field.boundingBox(), anchorType(publicField), null,
				Disposition.CANDIDATE, reason));
	}

	private AnchorType anchorType(String field) {
		return switch (field) {
			case "flight_number" -> AnchorType.FLIGHT_CARD;
			case "airline_name" -> AnchorType.AIRLINE_NAME;
			case "departure_date" -> AnchorType.DATE_HEADER;
			case "departure_airport" -> AnchorType.DEPARTURE_NODE;
			case "arrival_airport" -> AnchorType.ARRIVAL_NODE;
			case "departure_time" -> AnchorType.DEPARTURE_TIME;
			case "arrival_time" -> AnchorType.ARRIVAL_TIME;
			case "departure_terminal" -> AnchorType.DEPARTURE_TERMINAL;
			case "arrival_terminal" -> AnchorType.ARRIVAL_TERMINAL;
			default -> AnchorType.UNKNOWN;
		};
	}

	private List<DocumentFieldCandidate> reindexCandidates(
			List<DocumentFieldCandidate> candidates, int segmentIndex) {
		return candidates.stream().map(candidate -> new DocumentFieldCandidate(candidate.field(),
				candidate.rawValue(), candidate.normalizedValue(), candidate.source(),
				candidate.confidence(), candidate.page(), candidate.boundingBox(),
				candidate.nearbyAnchor(), segmentIndex, candidate.disposition(), candidate.reason()))
				.toList();
	}

	private List<SegmentCandidate> filterMetadataCandidates(List<SegmentCandidate> candidates,
			List<EvidenceBlock> blocks) {
		List<SegmentCandidate> filtered = new ArrayList<>();
		for (SegmentCandidate candidate : candidates) {
			DocumentFlightSegment source = candidate.segment();
			Map<String, ExtractedField> fields = new LinkedHashMap<>(source.fields());
			boolean clearFlight = metadataOnlyOccurrence(blocks, "flight_number",
					source.flightNumber(), source.departureDate(), source.departureMonthDay(),
					fieldPage(fields, "flight_number"), fieldBox(fields, "flight_number"));
			boolean clearDeparture = metadataOnlyOccurrence(blocks, "departure_airport",
					source.departureAirport(), null, null, fieldPage(fields, "departure_airport"),
					fieldBox(fields, "departure_airport"));
			boolean clearArrival = metadataOnlyOccurrence(blocks, "arrival_airport",
					source.arrivalAirport(), null, null, fieldPage(fields, "arrival_airport"),
					fieldBox(fields, "arrival_airport"));
			boolean clearDate = rejectedDepartureOccurrence(blocks, "departure_date", null,
					source.departureDate(), source.departureMonthDay(), fieldPage(fields, "departure_date"),
					fieldBox(fields, "departure_date"));
			boolean clearDepartureTime = rejectedDepartureOccurrence(blocks, "scheduled_departure",
					timePart(source.scheduledDeparture()), null, null, fieldPage(fields, "scheduled_departure"),
					fieldBox(fields, "scheduled_departure"));
			boolean clearArrivalTime = metadataOnlyOccurrence(blocks, "scheduled_arrival",
					timePart(source.scheduledArrival()), null, null, fieldPage(fields, "scheduled_arrival"),
					fieldBox(fields, "scheduled_arrival"));
			if (clearFlight) clearField(fields, "flight_number");
			if (clearDeparture) clearField(fields, "departure_airport");
			if (clearArrival) clearField(fields, "arrival_airport");
			if (clearDate) clearField(fields, "departure_date");
			if (clearDepartureTime) clearField(fields, "scheduled_departure");
			if (clearArrivalTime) clearField(fields, "scheduled_arrival");
			String flight = clearFlight ? null : source.flightNumber();
			LocalDate departureDate = clearDate ? null : source.departureDate();
			String monthDay = clearDate ? null : source.departureMonthDay();
			DocumentFlightSegment segment = new DocumentFlightSegment(flight, departureDate, monthDay,
					clearDeparture ? null : source.departureAirport(),
					clearArrival ? null : source.arrivalAirport(),
					clearDepartureTime ? null : source.scheduledDeparture(),
					clearArrivalTime ? null : source.scheduledArrival(),
					source.boardingTime(), source.terminal(), source.gate(),
					source.seat(), source.confidence(), source.page(), source.boundingBox(), fields,
					clearFlight ? FlightNumberConfidence.LOW : source.flightNumberConfidence(),
					false, source.flightNumberConfirmedByUser(),
					clearDate ? DateSource.MISSING : source.dateSource());
			filtered.add(new SegmentCandidate(segment, flight, candidate.documentOrder(),
					candidate.source(), fieldSources(segment), candidate.cardId(),
					candidate.sourceBundleId()));
		}
		return List.copyOf(filtered);
	}

	private Integer fieldPage(Map<String, ExtractedField> fields, String field) {
		ExtractedField evidence = fields.get(field);
		return evidence == null ? null : evidence.page();
	}

	private BoundingBox fieldBox(Map<String, ExtractedField> fields, String field) {
		ExtractedField evidence = fields.get(field);
		return evidence == null ? null : evidence.boundingBox();
	}

	private void clearField(Map<String, ExtractedField> fields, String field) {
		ExtractedField existing = fields.get(field);
		if (existing == null) return;
		fields.put(field, new ExtractedField(null, existing.rawValue(), existing.confidence(),
				existing.page(), existing.boundingBox(), existing.sourceLabel()));
	}

	private boolean metadataOnlyOccurrence(List<EvidenceBlock> blocks, String field,
			String value, LocalDate date, String monthDay, Integer expectedPage,
			BoundingBox expectedBox) {
		if (blocks == null || blocks.isEmpty()) return false;
		boolean found = false;
		boolean normal = false;
		for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
			EvidenceBlock block = blocks.get(blockIndex);
			if (expectedPage != null && block.pageIndex() != expectedPage) continue;
			if (expectedBox != null && block.box() != null && !boxesIntersect(expectedBox, block.box())) continue;
			List<TextSpan> occurrences = fieldOccurrences(block.text(), field, value, date, monthDay);
			for (TextSpan occurrence : occurrences) {
				found = true;
				if (metadataContext(blocks, blockIndex, occurrence.start(), occurrence.end())
						== MetadataContext.NONE) normal = true;
			}
		}
		return found && !normal;
	}

	private boolean rejectedDepartureOccurrence(List<EvidenceBlock> blocks, String field,
			String value, LocalDate date, String monthDay, Integer expectedPage,
			BoundingBox expectedBox) {
		if (blocks == null || blocks.isEmpty()) return false;
		boolean found = false;
		boolean departureOrUnknown = false;
		for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
			EvidenceBlock block = blocks.get(blockIndex);
			if (expectedPage != null && block.pageIndex() != expectedPage) continue;
			if (expectedBox != null && block.box() != null && !boxesIntersect(expectedBox, block.box())) continue;
			for (TextSpan occurrence : fieldOccurrences(block.text(), field, value, date, monthDay)) {
				found = true;
				ScheduleRole role = scheduleRole(blocks, blockIndex, occurrence);
				if (role == ScheduleRole.DEPARTURE || role == ScheduleRole.UNKNOWN) {
					departureOrUnknown = true;
				}
			}
		}
		return found && !departureOrUnknown;
	}

	private List<TextSpan> fieldOccurrences(String text, String field, String value,
			LocalDate date, String monthDay) {
		List<TextSpan> occurrences = new ArrayList<>();
		if ("flight_number".equals(field)) {
			String target = normalizeFlightNumber(value);
			if (target == null) return List.of();
			Matcher matcher = FLIGHT_NUMBER_IN_TEXT.matcher(Objects.toString(text, ""));
			while (matcher.find()) {
				if (target.equals(normalizeFlightNumber(matcher.group(1)))) {
					occurrences.add(new TextSpan(matcher.start(1), matcher.end(1)));
				}
			}
		} else if ("departure_airport".equals(field) || "arrival_airport".equals(field)) {
			String target = normalizeAirport(value);
			if (target == null) return List.of();
			Matcher matcher = IATA_IN_TEXT.matcher(Objects.toString(text, ""));
			while (matcher.find()) {
				if (target.equals(matcher.group(1))) occurrences.add(
						new TextSpan(matcher.start(1), matcher.end(1)));
			}
		} else if ("departure_date".equals(field)) {
			for (TravelDocumentDateParser.DateMatch match : dateParser.findDates(text,
					TravelDocumentDateParser.Source.FREE_TEXT)) {
				boolean sameDate = date != null && match.candidates().contains(date);
				boolean sameMonthDay = date == null && monthDay != null && match.candidates().stream()
						.anyMatch(candidate -> monthDay.equals("%02d-%02d".formatted(
								candidate.getMonthValue(), candidate.getDayOfMonth())));
				if (sameDate || sameMonthDay) occurrences.add(new TextSpan(match.start(), match.end()));
			}
		} else if ("scheduled_departure".equals(field) || "scheduled_arrival".equals(field)) {
			String target = normalizeTime(value);
			if (target == null) return List.of();
			Matcher matcher = STRICT_TIME_IN_TEXT.matcher(Objects.toString(text, ""));
			while (matcher.find()) {
				if (target.equals(normalizeTime(matcher.group()))) {
					occurrences.add(new TextSpan(matcher.start(), matcher.end()));
				}
			}
		}
		return occurrences;
	}

	private MergeOutcome mergeCandidates(List<SegmentCandidate> understand,
			List<SegmentCandidate> structure, PdfInputClassifier.Analysis pdfAnalysis,
			List<EvidenceBlock> structureBlocks) {
		List<MergedEntry> entries = new ArrayList<>();
		List<SegmentCandidate> ordered = new ArrayList<>();
		if (structure != null) ordered.addAll(structure);
		if (understand != null) ordered.addAll(understand);
		for (SegmentCandidate candidate : ordered) {
			if (candidate == null || candidate.segment() == null) continue;
			String key = normalizeFlightNumber(candidate.flightNumber());
			Integer target = key == null ? null : matchingFlight(entries, candidate, key);
			boolean sameCardMatch = false;
			if (target == null && !incompatibleExactFlightExists(entries, candidate, key)) {
				target = matchingCard(entries, candidate);
				sameCardMatch = target != null;
			}
			if (target == null) {
				entries.add(new MergedEntry(candidate, evidenceCandidates(candidate), new LinkedHashSet<>()));
				continue;
			}
			MergedEntry entry = entries.get(target);
			entry.addCandidate(candidate, evidenceCandidates(candidate));
			entry.segment = entry.primarySource == CandidateSource.STRUCTURE
					? mergeSameSegment(candidate.segment(), entry.segment, true, entry.conflicts,
							sameCardMatch, pdfAnalysis)
					: mergeSameSegment(entry.segment, candidate.segment(), false, entry.conflicts,
							sameCardMatch, pdfAnalysis);
		}
		for (MergedEntry entry : entries) reconcileCardConflicts(entry, pdfAnalysis);
		entries = deduplicateCrossChannelPartials(entries, pdfAnalysis);
		entries = coalesceExactFlightDuplicates(entries, pdfAnalysis);
		entries = coalesceUniqueCardFragments(entries, pdfAnalysis);
		entries = removeMetadataOnlySegments(entries, structureBlocks);
		entries = removeArrivalStatusFragments(entries, structureBlocks);
		entries = absorbSubsumedPartials(entries);
		List<List<DocumentFieldCandidate>> evidence = new ArrayList<>();
		for (int index = 0; index < entries.size(); index++) {
			evidence.add(reindexCandidates(entries.get(index).candidates, index));
		}
		return new MergeOutcome(entries.stream().map(entry -> entry.segment).toList(),
				entries.stream().map(entry -> Set.copyOf(entry.conflicts)).toList(),
				List.copyOf(evidence));
	}

	private Integer matchingFlight(List<MergedEntry> entries,
			SegmentCandidate candidate, String flightNumber) {
		Integer match = null;
		for (int index = 0; index < entries.size(); index++) {
			MergedEntry entry = entries.get(index);
			if (!entry.flightNumbers().contains(flightNumber)) continue;
			if (!exactFlightMergeCompatible(candidate.segment(), entry.segment)) continue;
			if (match != null) return match;
			match = index;
		}
		return match;
	}

	private boolean incompatibleExactFlightExists(List<MergedEntry> entries,
			SegmentCandidate candidate, String flightNumber) {
		if (flightNumber == null) return false;
		return entries.stream().anyMatch(entry -> entry.flightNumbers().contains(flightNumber)
				&& !exactFlightMergeCompatible(candidate.segment(), entry.segment));
	}

	private boolean exactFlightMergeCompatible(DocumentFlightSegment left, DocumentFlightSegment right) {
		if (left == null || right == null) return false;
		if (left.page() != null && right.page() != null && !left.page().equals(right.page())) return false;
		if (left.departureDate() != null && right.departureDate() != null
				&& !left.departureDate().equals(right.departureDate())) return false;
		return !differentCompleteOrderedRoutes(left, right);
	}

	private boolean differentCompleteOrderedRoutes(DocumentFlightSegment left, DocumentFlightSegment right) {
		return left.departureAirport() != null && left.arrivalAirport() != null
				&& right.departureAirport() != null && right.arrivalAirport() != null
				&& (!left.departureAirport().equals(right.departureAirport())
						|| !left.arrivalAirport().equals(right.arrivalAirport()));
	}

	private Integer matchingCard(List<MergedEntry> entries, SegmentCandidate candidate) {
		Integer match = null;
		for (int index = 0; index < entries.size(); index++) {
			MergedEntry entry = entries.get(index);
			if (!sameFlightCard(candidate, entry)) continue;
			if (match != null) return null;
			match = index;
		}
		return match;
	}

	private boolean sameFlightCard(SegmentCandidate candidate, MergedEntry entry) {
		if (candidate == null || entry == null) return false;
		if (candidate.segment().page() != null && entry.segment.page() != null
				&& !candidate.segment().page().equals(entry.segment.page())) return false;
		if (candidate.cardId() != null && entry.cardIds.contains(candidate.cardId())) return true;
		return sameFlightCard(candidate.segment(), entry.segment);
	}

	private boolean sameFlightCard(DocumentFlightSegment left, DocumentFlightSegment right) {
		if (left == null || right == null) return false;
		if (left.page() != null && right.page() != null && !left.page().equals(right.page())) return false;
		int airportMatches = intersectionSize(airportSet(left), airportSet(right));
		int timeMatches = intersectionSize(timeSet(left), timeSet(right));
		boolean sameDate = left.departureDate() != null
				&& left.departureDate().equals(right.departureDate());
		return airportMatches >= 2 && timeMatches >= 1
				|| sameDate && airportMatches >= 1 && timeMatches >= 1
				|| timeMatches >= 2 && airportMatches >= 1;
	}

	private void reconcileCardConflicts(MergedEntry entry, PdfInputClassifier.Analysis pdfAnalysis) {
		LinkedHashSet<String> flightNumbers = entry.flightNumbers();
		String selectedFlight = flightNumbers.size() == 1 ? flightNumbers.iterator().next() : null;
		String selectionReason = selectedFlight == null ? null : "ACCEPTED";
		if (flightNumbers.size() > 1) {
			String carrier = uniqueCarrierDesignator(entry.sourceCandidates);
			List<String> carrierMatches = flightNumbers.stream()
					.filter(flight -> carrier != null && carrier.equals(flightPrefix(flight))).toList();
			if (carrierMatches.size() == 1) {
				selectedFlight = carrierMatches.get(0);
				selectionReason = "SOURCE_BUNDLE_SELECTED";
			}
		}
		if (flightNumbers.size() > 1) {
			if (selectedFlight == null) entry.conflicts.add("flight_number");
			else {
				entry.conflicts.remove("flight_number");
				entry.conflicts.add("flight_number_resolved");
			}
		}

		LinkedHashSet<String> departureTimes = entry.sourceCandidates.stream()
				.map(candidate -> timePart(candidate.segment().scheduledDeparture()))
				.filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		String selectedDepartureTime = departureTimes.size() == 1
				? entry.sourceCandidates.stream().map(candidate -> candidate.segment().scheduledDeparture())
						.filter(Objects::nonNull).findFirst().orElse(departureTimes.iterator().next()) : null;
		if (departureTimes.size() > 1 && selectedFlight != null) {
			Map<String, Set<String>> timesByBundle = new LinkedHashMap<>();
			for (SegmentCandidate candidate : entry.sourceCandidates) {
				if (!selectedFlight.equals(normalizeFlightNumber(candidate.flightNumber()))) continue;
				String time = timePart(candidate.segment().scheduledDeparture());
				if (time == null) continue;
				timesByBundle.computeIfAbsent(candidate.sourceBundleId(), ignored -> new LinkedHashSet<>())
						.add(time);
			}
			LinkedHashSet<String> selectedFlightTimes = timesByBundle.values().stream()
					.flatMap(Set::stream).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			if (selectedFlightTimes.size() == 1) {
				selectedDepartureTime = selectedFlightTimes.iterator().next();
				selectionReason = "SOURCE_BUNDLE_SELECTED";
			}
		}
		if (departureTimes.size() > 1) {
			if (selectedDepartureTime == null) entry.conflicts.add("scheduled_departure");
			else entry.conflicts.remove("scheduled_departure");
		}
		FlightAssessment selectedAssessment = resolvedAssessment(entry, selectedFlight, selectionReason);
		entry.segment = withResolvedIdentityAndDepartureTime(entry.segment, selectedFlight,
				selectedDepartureTime, selectionReason, selectedAssessment);
		LinkedHashSet<LocalDate> departureDates = entry.candidates.stream()
				.filter(candidate -> "departure_date".equals(candidate.field()))
				.map(DocumentFieldCandidate::normalizedValue).filter(Objects::nonNull)
				.map(this::parseIsoDate).filter(Objects::nonNull)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if (departureDates.size() > 1) {
			entry.conflicts.add("departure_date");
			entry.segment = withResolvedDepartureDate(entry.segment, DateParts.CONFLICT);
		} else if (departureDates.size() == 1) {
			LocalDate selectedDate = departureDates.iterator().next();
			DateSource source = entry.sourceCandidates.stream()
					.filter(candidate -> selectedDate.equals(candidate.segment().departureDate()))
					.map(candidate -> candidate.segment().dateSource())
					.anyMatch(DateSource.EXPLICIT_FULL_DATE::equals)
							? DateSource.EXPLICIT_FULL_DATE : DateSource.CURRENT_YEAR_FROM_MONTH_DAY;
			entry.conflicts.remove("departure_date");
			entry.segment = withResolvedDepartureDate(entry.segment,
					new DateParts(selectedDate, monthDay(selectedDate), source));
		}
	}

	private FlightAssessment resolvedAssessment(MergedEntry entry, String flight, String reason) {
		if (flight == null) return FlightAssessment.LOW;
		FlightAssessment assessment = entry.sourceCandidates.stream()
				.filter(candidate -> flight.equals(normalizeFlightNumber(candidate.flightNumber())))
				.map(candidate -> assessmentOf(candidate.segment()))
				.max(Comparator.comparingInt(item -> confidenceRank(item.confidence())))
				.orElse(new FlightAssessment(flight, FlightNumberConfidence.LOW, false, "ACCEPTED"));
		if ("SOURCE_BUNDLE_SELECTED".equals(reason)
				&& airlineRegistry.resolveFlightPrefix(flight).isHighConfidence()) {
			return new FlightAssessment(flight, FlightNumberConfidence.HIGH, false, reason);
		}
		return new FlightAssessment(flight, assessment.confidence(), false,
				Objects.toString(reason, assessment.reasonCode()));
	}

	private LocalDate parseIsoDate(String value) {
		try {
			return value == null ? null : LocalDate.parse(value);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private DocumentFlightSegment withResolvedDepartureDate(DocumentFlightSegment source,
			DateParts date) {
		Map<String, ExtractedField> fields = new LinkedHashMap<>(source.fields());
		ExtractedField existing = fields.get("departure_date");
		String value = date == null ? null : date.publicValue();
		fields.put("departure_date", new ExtractedField(value,
				existing == null ? value : existing.rawValue(), existing == null ? 0.0d : existing.confidence(),
				existing == null ? source.page() : existing.page(),
				existing == null ? null : existing.boundingBox(), "Local schedule-role resolution"));
		return new DocumentFlightSegment(source.flightNumber(), date == null ? null : date.date(),
				date == null ? null : date.monthDay(), source.departureAirport(), source.arrivalAirport(),
				source.scheduledDeparture(), source.scheduledArrival(), source.boardingTime(),
				source.terminal(), source.gate(), source.seat(), source.confidence(), source.page(),
				source.boundingBox(), fields, source.flightNumberConfidence(),
				source.requiresFlightNumberConfirmation(), source.flightNumberConfirmedByUser(),
				date == null ? DateSource.MISSING : date.source());
	}

	private List<MergedEntry> deduplicateCrossChannelPartials(List<MergedEntry> entries,
			PdfInputClassifier.Analysis pdfAnalysis) {
		List<MergedEntry> unique = new ArrayList<>();
		for (MergedEntry candidate : entries) {
			MergedEntry duplicate = unique.stream()
					.filter(existing -> identicalPartial(existing.segment, candidate.segment))
					.findFirst().orElse(null);
			if (duplicate == null) {
				unique.add(candidate);
				continue;
			}
			for (SegmentCandidate sourceCandidate : candidate.sourceCandidates) {
				duplicate.addCandidate(sourceCandidate, evidenceCandidates(sourceCandidate));
			}
			duplicate.conflicts.addAll(candidate.conflicts);
			reconcileCardConflicts(duplicate, pdfAnalysis);
		}
		return unique;
	}

	private List<MergedEntry> coalesceExactFlightDuplicates(List<MergedEntry> entries,
			PdfInputClassifier.Analysis pdfAnalysis) {
		List<MergedEntry> coalesced = new ArrayList<>();
		for (MergedEntry candidate : entries) {
			String flight = normalizeFlightNumber(candidate.segment.flightNumber());
			MergedEntry target = flight == null ? null : coalesced.stream()
					.filter(existing -> flight.equals(normalizeFlightNumber(existing.segment.flightNumber())))
					.filter(existing -> exactFlightMergeCompatible(existing.segment, candidate.segment))
					.findFirst().orElse(null);
			if (target == null) {
				coalesced.add(candidate);
				continue;
			}
			absorbEntry(target, candidate, false, pdfAnalysis);
		}
		return coalesced;
	}

	private List<MergedEntry> coalesceUniqueCardFragments(List<MergedEntry> entries,
			PdfInputClassifier.Analysis pdfAnalysis) {
		List<MergedEntry> visible = new ArrayList<>(entries);
		for (MergedEntry fragment : new ArrayList<>(entries)) {
			if (normalizeFlightNumber(fragment.segment.flightNumber()) != null
					|| fragment.cardIds.size() != 1) continue;
			String cardId = fragment.cardIds.iterator().next();
			if (cardId == null || !cardId.startsWith("card:")) continue;
			List<MergedEntry> targets = visible.stream().filter(entry -> entry != fragment
					&& normalizeFlightNumber(entry.segment.flightNumber()) != null
					&& entry.cardIds.size() == 1 && entry.cardIds.contains(cardId)
					&& fragmentsDoNotConflict(entry.segment, fragment.segment)).toList();
			if (targets.size() != 1) continue;
			absorbEntry(targets.get(0), fragment, true, pdfAnalysis);
			visible.remove(fragment);
		}
		return visible;
	}

	private boolean fragmentsDoNotConflict(DocumentFlightSegment target, DocumentFlightSegment fragment) {
		if (target == null || fragment == null) return false;
		if (differentNonNull(target.departureAirport(), fragment.departureAirport())
				|| differentNonNull(target.arrivalAirport(), fragment.arrivalAirport())
				|| differentNonNull(target.departureDate(), fragment.departureDate())
				|| differentNonNull(timePart(target.scheduledDeparture()), timePart(fragment.scheduledDeparture()))
				|| differentNonNull(timePart(target.scheduledArrival()), timePart(fragment.scheduledArrival()))
				|| differentNonNull(target.terminal(), fragment.terminal())
				|| differentNonNull(target.gate(), fragment.gate())
				|| differentNonNull(target.seat(), fragment.seat())) return false;
		return true;
	}

	private boolean differentNonNull(Object left, Object right) {
		return left != null && right != null && !Objects.equals(left, right);
	}

	private void absorbEntry(MergedEntry target, MergedEntry source, boolean sameCardMatch,
			PdfInputClassifier.Analysis pdfAnalysis) {
		for (SegmentCandidate sourceCandidate : source.sourceCandidates) {
			target.addCandidate(sourceCandidate, evidenceCandidates(sourceCandidate));
		}
		target.conflicts.addAll(source.conflicts);
		target.segment = mergeSameSegment(target.segment, source.segment, false,
				target.conflicts, sameCardMatch, pdfAnalysis);
		reconcileCardConflicts(target, pdfAnalysis);
	}

	private List<MergedEntry> removeMetadataOnlySegments(List<MergedEntry> entries,
			List<EvidenceBlock> blocks) {
		if (blocks == null || blocks.isEmpty()) return entries;
		List<MergedEntry> visible = new ArrayList<>();
		for (MergedEntry entry : entries) {
			if (normalizeFlightNumber(entry.segment.flightNumber()) != null
					|| !metadataOnlyEvidence(entry, blocks)) visible.add(entry);
		}
		return visible;
	}

	private boolean metadataOnlyEvidence(MergedEntry entry, List<EvidenceBlock> blocks) {
		boolean found = false;
		for (SegmentCandidate candidate : entry.sourceCandidates) {
			for (String fieldName : List.of("flight_number", "departure_airport", "arrival_airport",
					"departure_date", "scheduled_departure", "scheduled_arrival")) {
				ExtractedField field = candidate.segment().fields().get(fieldName);
				if (field == null) continue;
				String raw = firstNonBlank(field.rawValue(), field.value());
				if (raw == null) continue;
				String value = switch (fieldName) {
					case "flight_number" -> normalizeFlightNumber(raw);
					case "departure_airport", "arrival_airport" -> normalizeAirport(raw);
					case "scheduled_departure", "scheduled_arrival" -> timePart(raw);
					default -> null;
				};
				DateParts date = "departure_date".equals(fieldName)
						? dateParts(raw) : DateParts.EMPTY;
				List<OccurrenceRef> occurrences = occurrenceRefs(blocks, fieldName, value,
						date.date(), date.monthDay(), field.page(), field.boundingBox());
				if (occurrences.isEmpty()) return false;
				found = true;
				if (occurrences.stream().anyMatch(occurrence -> metadataContext(blocks,
						occurrence.blockIndex(), occurrence.span().start(), occurrence.span().end())
						== MetadataContext.NONE)) return false;
			}
		}
		return found;
	}

	private List<OccurrenceRef> occurrenceRefs(List<EvidenceBlock> blocks, String field,
			String value, LocalDate date, String monthDay, Integer expectedPage,
			BoundingBox expectedBox) {
		List<OccurrenceRef> occurrences = new ArrayList<>();
		for (int index = 0; index < blocks.size(); index++) {
			EvidenceBlock block = blocks.get(index);
			if (expectedPage != null && block.pageIndex() != expectedPage) continue;
			if (expectedBox != null && block.box() != null && !boxesIntersect(expectedBox, block.box())) continue;
			for (TextSpan span : fieldOccurrences(block.text(), field, value, date, monthDay)) {
				occurrences.add(new OccurrenceRef(index, span));
			}
		}
		return List.copyOf(occurrences);
	}

	private boolean identicalPartial(DocumentFlightSegment left, DocumentFlightSegment right) {
		if (left == null || right == null) return false;
		if (left.page() != null && right.page() != null && !left.page().equals(right.page())) return false;
		List<Object> leftValues = List.of(
				Objects.toString(normalizeFlightNumber(left.flightNumber()), ""),
				Objects.toString(left.departureAirport(), ""), Objects.toString(left.arrivalAirport(), ""),
				Objects.toString(left.departureDate(), Objects.toString(left.departureMonthDay(), "")),
				Objects.toString(timePart(left.scheduledDeparture()), ""),
				Objects.toString(timePart(left.scheduledArrival()), ""));
		List<Object> rightValues = List.of(
				Objects.toString(normalizeFlightNumber(right.flightNumber()), ""),
				Objects.toString(right.departureAirport(), ""), Objects.toString(right.arrivalAirport(), ""),
				Objects.toString(right.departureDate(), Objects.toString(right.departureMonthDay(), "")),
				Objects.toString(timePart(right.scheduledDeparture()), ""),
				Objects.toString(timePart(right.scheduledArrival()), ""));
		long populated = leftValues.stream().filter(value -> !value.toString().isBlank()).count();
		return populated >= 2 && leftValues.equals(rightValues);
	}

	private String uniqueCarrierDesignator(List<SegmentCandidate> candidates) {
		LinkedHashSet<String> designators = new LinkedHashSet<>();
		for (SegmentCandidate candidate : candidates) {
			String airlineName = fieldValue(candidate.segment(), "airline_name");
			if (airlineName == null) continue;
			AirlineResolution resolution = airlineRegistry.searchDisplayName(airlineName);
			if (resolution.ambiguous()) return null;
			String designator = resolution.uniqueCandidate().map(item -> item.iata()).orElse(null);
			if (designator != null) designators.add(designator);
		}
		return designators.size() == 1 ? designators.iterator().next() : null;
	}

	private String flightPrefix(String flight) {
		String normalized = normalizeFlightNumber(flight);
		if (normalized == null) return null;
		Matcher matcher = FLIGHT_NUMERIC_SUFFIX.matcher(normalized);
		return matcher.find() ? normalized.substring(0, matcher.start(1)) : null;
	}

	private DocumentFlightSegment withResolvedIdentityAndDepartureTime(DocumentFlightSegment source,
			String flight, String departureTime, String reason, FlightAssessment assessment) {
		Map<String, ExtractedField> fields = new LinkedHashMap<>(source.fields());
		ExtractedField flightField = fields.get("flight_number");
		fields.put("flight_number", new ExtractedField(flight,
				flightField == null ? flight : flightField.rawValue(),
				flightField == null ? 0.0d : flightField.confidence(),
				flightField == null ? source.page() : flightField.page(),
				flightField == null ? source.boundingBox() : flightField.boundingBox(),
				"Local deterministic card resolution: " + Objects.toString(reason, "CONFLICT_RETAINED")));
		ExtractedField timeField = fields.get("scheduled_departure");
		fields.put("scheduled_departure", new ExtractedField(departureTime,
				timeField == null ? departureTime : timeField.rawValue(),
				timeField == null ? 0.0d : timeField.confidence(),
				timeField == null ? source.page() : timeField.page(),
				timeField == null ? null : timeField.boundingBox(),
				"Local deterministic source-bundle resolution"));
		return new DocumentFlightSegment(flight, source.departureDate(), source.departureMonthDay(),
				source.departureAirport(), source.arrivalAirport(), departureTime,
				source.scheduledArrival(), source.boardingTime(), source.terminal(), source.gate(),
				source.seat(), source.confidence(), source.page(), source.boundingBox(), fields,
				assessment.confidence(), false, source.flightNumberConfirmedByUser(), source.dateSource());
	}

	private <T> int intersectionSize(Set<T> left, Set<T> right) {
		Set<T> intersection = new LinkedHashSet<>(left);
		intersection.retainAll(right);
		return intersection.size();
	}

	private Set<String> airportSet(DocumentFlightSegment segment) {
		LinkedHashSet<String> values = new LinkedHashSet<>();
		if (segment.departureAirport() != null) values.add(segment.departureAirport());
		if (segment.arrivalAirport() != null) values.add(segment.arrivalAirport());
		return Set.copyOf(values);
	}

	private Set<String> timeSet(DocumentFlightSegment segment) {
		LinkedHashSet<String> values = new LinkedHashSet<>();
		String departure = timePart(segment.scheduledDeparture());
		String arrival = timePart(segment.scheduledArrival());
		if (departure != null) values.add(departure);
		if (arrival != null) values.add(arrival);
		return Set.copyOf(values);
	}

	private List<MergedEntry> absorbSubsumedPartials(List<MergedEntry> entries) {
		List<MergedEntry> completeTargets = entries.stream()
				.filter(entry -> completeAbsorptionTarget(entry.segment)).toList();
		if (completeTargets.isEmpty()) return entries;
		List<MergedEntry> visible = new ArrayList<>();
		for (MergedEntry partial : entries) {
			if (completeTargets.contains(partial)) {
				visible.add(partial);
				continue;
			}
			long matches = completeTargets.stream()
					.filter(target -> subsumes(target, partial)).count();
			if (matches != 1) visible.add(partial);
		}
		return visible;
	}

	private boolean completeAbsorptionTarget(DocumentFlightSegment segment) {
		if (normalizeFlightNumber(segment.flightNumber()) == null) return false;
		int categories = airportSet(segment).isEmpty() ? 0 : 1;
		if (segment.departureDate() != null) categories++;
		if (!timeSet(segment).isEmpty()) categories++;
		return categories >= 2;
	}

	private boolean subsumes(MergedEntry targetEntry, MergedEntry partialEntry) {
		DocumentFlightSegment target = targetEntry.segment;
		DocumentFlightSegment partial = partialEntry.segment;
		String targetFlight = normalizeFlightNumber(target.flightNumber());
		String partialFlight = normalizeFlightNumber(partial.flightNumber());
		if (partialFlight != null && !partialFlight.equals(targetFlight)) return false;
		if (target.page() != null && partial.page() != null && !target.page().equals(partial.page())) return false;
		Set<String> commonCards = new LinkedHashSet<>(targetEntry.cardIds);
		commonCards.retainAll(partialEntry.cardIds);
		if (commonCards.size() != 1) return false;
		Set<String> partialAirports = airportSet(partial);
		Set<String> partialTimes = timeSet(partial);
		if (!airportSet(target).containsAll(partialAirports)
				|| !timeSet(target).containsAll(partialTimes)) return false;
		if (partial.departureDate() != null
				&& !partial.departureDate().equals(target.departureDate())) return false;
		int matchingTimes = intersectionSize(timeSet(target), partialTimes);
		int matchingAirports = intersectionSize(airportSet(target), partialAirports);
		boolean matchingDate = partial.departureDate() != null
				&& partial.departureDate().equals(target.departureDate());
		boolean combinationA = matchingTimes >= 1 && (matchingAirports >= 1 || matchingDate);
		boolean combinationB = matchingAirports >= 2 && matchingDate;
		return combinationA || combinationB;
	}

	private List<MergedEntry> removeArrivalStatusFragments(List<MergedEntry> entries,
			List<EvidenceBlock> blocks) {
		List<MergedEntry> visible = new ArrayList<>();
		for (MergedEntry entry : entries) {
			DocumentFlightSegment segment = entry.segment;
			boolean noIdentityOrRoute = segment.flightNumber() == null
					&& segment.departureAirport() == null && segment.arrivalAirport() == null
					&& timePart(segment.scheduledDeparture()) == null;
			boolean hasDateOrArrival = segment.departureDate() != null
					|| segment.departureMonthDay() != null
					|| timePart(segment.scheduledArrival()) != null;
			List<ScheduleRole> roles = scheduleRoles(entry, blocks);
			boolean arrivalOnly = !roles.isEmpty()
					&& roles.stream().allMatch(role -> role == ScheduleRole.ARRIVAL
							|| role == ScheduleRole.ISSUE_OR_BOOKING);
			if (!(noIdentityOrRoute && hasDateOrArrival && arrivalOnly)) visible.add(entry);
		}
		return visible;
	}

	private List<ScheduleRole> scheduleRoles(MergedEntry entry, List<EvidenceBlock> blocks) {
		List<ScheduleRole> roles = new ArrayList<>();
		for (SegmentCandidate candidate : entry.sourceCandidates) {
			for (String fieldName : List.of("departure_date", "scheduled_departure",
					"scheduled_arrival", "date", "datetime")) {
				ExtractedField field = candidate.segment().fields().get(fieldName);
				if (field == null || field.rawValue() == null) continue;
				DateParts date = Set.of("departure_date", "date", "datetime").contains(fieldName)
						? dateParts(field.rawValue()) : DateParts.EMPTY;
				String occurrenceField = Set.of("date", "datetime").contains(fieldName)
						? "departure_date" : fieldName;
				String value = occurrenceField.startsWith("scheduled_")
						? timePart(field.rawValue()) : null;
				List<OccurrenceRef> occurrences = occurrenceRefs(blocks, occurrenceField, value,
						date.date(), date.monthDay(), field.page(), field.boundingBox());
				if (occurrences.isEmpty()) {
					roles.add(scheduleRole(fieldName, field.rawValue()));
				} else {
					for (OccurrenceRef occurrence : occurrences) {
						roles.add(scheduleRole(blocks, occurrence.blockIndex(), occurrence.span()));
					}
				}
			}
		}
		return List.copyOf(roles);
	}

	private String flightNumericSuffix(String value) {
		String normalized = normalizeFlightNumber(value);
		if (normalized == null) return null;
		Matcher matcher = FLIGHT_NUMERIC_SUFFIX.matcher(normalized);
		return matcher.find() ? matcher.group(1) : null;
	}

	private DocumentFlightSegment mergeSameSegment(DocumentFlightSegment understand,
			DocumentFlightSegment spatial, boolean spatialPreferred, Set<String> conflicts,
			boolean sameCardMatch, PdfInputClassifier.Analysis pdfAnalysis) {
		boolean completeSpatialRoute = spatialPreferred
				&& spatial.departureAirport() != null && spatial.arrivalAirport() != null;
		FlightMerge flightMerge = mergeFlightNumber(understand, spatial, conflicts,
				sameCardMatch, pdfAnalysis);
		String flight = flightMerge.flightNumber();
		String airlineName = mergeScalar(fieldValue(understand, "airline_name"),
				fieldValue(spatial, "airline_name"), "airline_name", conflicts, false);
		DateMerge departureDate = mergeDepartureDate(understand, spatial, conflicts);
		String departure = mergeScalar(understand.departureAirport(), spatial.departureAirport(),
				"departure_airport", conflicts, completeSpatialRoute);
		String arrival = mergeScalar(understand.arrivalAirport(), spatial.arrivalAirport(),
				"arrival_airport", conflicts, completeSpatialRoute);
		if (departure != null && departure.equals(arrival)) {
			arrival = null;
			conflicts.add("arrival_airport");
		}
		String scheduledDeparture = mergeSchedule(understand.scheduledDeparture(),
				spatial.scheduledDeparture(), "scheduled_departure", conflicts);
		String scheduledArrival = mergeSchedule(understand.scheduledArrival(), spatial.scheduledArrival(),
				"scheduled_arrival", conflicts);
		String boarding = mergeScalar(understand.boardingTime(), spatial.boardingTime(),
				"boarding_time", conflicts, false);
		String terminal = mergeScalar(understand.terminal(), spatial.terminal(), "terminal", conflicts, false);
		String arrivalTerminal = mergeScalar(fieldValue(understand, "arrival_terminal"),
				fieldValue(spatial, "arrival_terminal"), "arrival_terminal", conflicts, false);
		String gate = mergeScalar(understand.gate(), spatial.gate(), "gate", conflicts, false);
		String seat = mergeScalar(understand.seat(), spatial.seat(), "seat", conflicts, false);
		Map<String, ExtractedField> evidence = mergeEvidence(understand.fields(), spatial.fields(),
				flight, airlineName, departureDate.date(), departureDate.monthDay(), departure, arrival,
				scheduledDeparture, scheduledArrival, boarding, terminal, arrivalTerminal, gate, seat);
		return new DocumentFlightSegment(flight, departureDate.date(), departureDate.monthDay(), departure, arrival,
				scheduledDeparture, scheduledArrival, boarding, terminal, gate, seat,
				Math.max(understand.confidence(), spatial.confidence()),
				spatial.page() != null ? spatial.page() : understand.page(),
				spatial.boundingBox() != null ? spatial.boundingBox() : understand.boundingBox(), evidence,
				flightMerge.assessment().confidence(), flightMerge.assessment().requiresConfirmation(), false,
				departureDate.source());
	}

	private FlightMerge mergeFlightNumber(DocumentFlightSegment understand,
			DocumentFlightSegment spatial, Set<String> conflicts, boolean sameCardMatch,
			PdfInputClassifier.Analysis pdfAnalysis) {
		String left = normalizeFlightNumber(understand.flightNumber());
		String right = normalizeFlightNumber(spatial.flightNumber());
		if (left == null) return new FlightMerge(right, assessmentOf(spatial));
		if (right == null) return new FlightMerge(left, assessmentOf(understand));
		if (left.equals(right)) {
			FlightAssessment combined = strongerAssessment(assessmentOf(understand), assessmentOf(spatial));
			AirlineResolution registry = airlineRegistry.resolveFlightPrefix(left);
			if (registry.isHighConfidence()) {
				combined = new FlightAssessment(left, FlightNumberConfidence.HIGH, false,
						"UNDERSTAND_STRUCTURE_AGREEMENT");
			}
			return new FlightMerge(left, combined);
		}
		if (sameCardMatch && pdfAnalysis != null
				&& pdfAnalysis.type() == PdfInputClassifier.Type.NATIVE_TEXT) {
			int page = understand.page() != null ? understand.page()
					: spatial.page() == null ? -1 : spatial.page();
			String pageText = page >= 0 && page < pdfAnalysis.pageTexts().size()
					? pdfAnalysis.pageTexts().get(page) : null;
			boolean leftPresent = nativeFlightTokenPresent(pageText, left);
			boolean rightPresent = nativeFlightTokenPresent(pageText, right);
			if (leftPresent != rightPresent) {
				String selected = leftPresent ? left : right;
				conflicts.add("flight_number_resolved");
				return new FlightMerge(selected, new FlightAssessment(selected,
						FlightNumberConfidence.HIGH, false, "NATIVE_TEXT_EXACT_MATCH"));
			}
		}
		conflicts.add("flight_number");
		return new FlightMerge(null, FlightAssessment.LOW);
	}

	private List<SegmentCandidate> nativeFlightRows(PdfInputClassifier.Analysis analysis) {
		if (analysis == null || analysis.type() != PdfInputClassifier.Type.NATIVE_TEXT) return List.of();
		List<SegmentCandidate> candidates = new ArrayList<>();
		int documentOrder = 0;
		for (int page = 0; page < analysis.pageTexts().size(); page++) {
			String[] rows = Objects.toString(analysis.pageTexts().get(page), "").split("\\R");
			for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
				String rawRow = rows[rowIndex];
				String row = normalizeUnicodeDigits(Normalizer.normalize(rawRow, Normalizer.Form.NFKC));
				if (row.isBlank() || metadataLabelContext(row) != MetadataContext.NONE
						|| currencyAmountOccurrence(row, 0, row.length())) continue;
				List<TravelDocumentDateParser.DateMatch> rowDates = dateParser.findDates(row,
						TravelDocumentDateParser.Source.FREE_TEXT);
				List<TextSpan> rowTimes = new ArrayList<>();
				Matcher rowTimeMatcher = STRICT_TIME_IN_TEXT.matcher(row);
				while (rowTimeMatcher.find()) {
					rowTimes.add(new TextSpan(rowTimeMatcher.start(), rowTimeMatcher.end()));
				}
				List<TextSpan> flightSpans = new ArrayList<>();
				LinkedHashSet<String> flights = new LinkedHashSet<>();
				Matcher flightMatcher = FLIGHT_NUMBER_IN_TEXT.matcher(row.toUpperCase(Locale.ROOT));
				while (flightMatcher.find()) {
					boolean consumesDate = rowDates.stream().anyMatch(date ->
							flightMatcher.start(1) < date.end() && flightMatcher.end(1) > date.start());
					boolean consumesTime = rowTimes.stream().anyMatch(time ->
							time.overlaps(flightMatcher.start(1), flightMatcher.end(1)));
					if (consumesDate || consumesTime) continue;
					String flight = normalizeFlightNumber(flightMatcher.group(1));
					if (flight != null && !aircraftContext(row)) {
						flights.add(flight);
						flightSpans.add(new TextSpan(flightMatcher.start(1), flightMatcher.end(1)));
					}
				}
				if (flights.isEmpty()) continue;
				LinkedHashSet<String> airports = new LinkedHashSet<>();
				Matcher airportMatcher = IATA_IN_TEXT.matcher(row.toUpperCase(Locale.ROOT));
				while (airportMatcher.find()) {
					int start = airportMatcher.start(1);
					int end = airportMatcher.end(1);
					if (flightSpans.stream().noneMatch(span -> span.overlaps(start, end))) {
						String airport = normalizeAirport(airportMatcher.group(1));
						if (airport != null) airports.add(airport);
					}
				}
				LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
				for (TravelDocumentDateParser.DateMatch match : rowDates) dates.addAll(match.candidates());
				List<String> times = new ArrayList<>();
				Matcher timeMatcher = STRICT_TIME_IN_TEXT.matcher(row);
				while (timeMatcher.find()) times.add(normalizeTime(timeMatcher.group()));
				Matcher terminalMatcher = TERMINAL_IN_TEXT.matcher(row);
				String terminal = terminalMatcher.find()
						? "T" + terminalMatcher.group(1).toUpperCase(Locale.ROOT) : null;
				int categories = (airports.isEmpty() ? 0 : 1) + (dates.isEmpty() ? 0 : 1)
						+ (times.isEmpty() ? 0 : 1) + (terminal == null ? 0 : 1);
				if (categories < 2) continue;
				DateParts date = dates.size() == 1 ? DateParts.of(dates.iterator().next())
						: dates.size() > 1 ? new DateParts(null, null, DateSource.CONFLICT,
								List.copyOf(dates), rawRow, null) : DateParts.EMPTY;
				String departure = airports.stream().findFirst().orElse(null);
				String arrival = airports.stream().skip(1).findFirst().orElse(null);
				String departureTime = times.isEmpty() ? null : times.get(0);
				String arrivalTime = times.size() < 2 ? null : times.get(times.size() - 1);
				for (String flight : flights) {
					String bundle = "native:" + page + ":" + rowIndex;
					Map<String, ExtractedField> fields = emptyFields();
					putNativeField(fields, "flight_number", flight, rawRow, page, bundle);
					putNativeField(fields, "departure_airport", departure, rawRow, page, bundle);
					putNativeField(fields, "arrival_airport", arrival, rawRow, page, bundle);
					putNativeField(fields, "departure_date", date.publicValue(), rawRow, page, bundle);
					putNativeField(fields, "scheduled_departure", departureTime, rawRow, page, bundle);
					putNativeField(fields, "scheduled_arrival", arrivalTime, rawRow, page, bundle);
					putNativeField(fields, "departure_terminal", terminal, rawRow, page, bundle);
					DocumentFlightSegment segment = new DocumentFlightSegment(flight, date.date(), date.monthDay(),
							departure, arrival, departureTime, arrivalTime, null, terminal, null, null,
							0.90d, page, null, fields, FlightNumberConfidence.HIGH, false, false, date.source());
					candidates.add(new SegmentCandidate(segment, flight, documentOrder++, CandidateSource.NATIVE_ROW,
							fieldSources(segment), null, bundle));
				}
			}
		}
		return List.copyOf(candidates);
	}

	private void putNativeField(Map<String, ExtractedField> fields, String name, String value,
			String rawRow, int page, String bundle) {
		if (value == null) return;
		fields.put(name, new ExtractedField(value, value, 1.0d, page, null,
				"PDFBox native row bundle=" + bundle + " field=" + name));
	}

	boolean nativeFlightTokenPresent(String pageText, String flightNumber) {
		String canonical = normalizeFlightNumber(flightNumber);
		if (pageText == null || canonical == null) return false;
		Matcher suffixMatcher = FLIGHT_NUMERIC_SUFFIX.matcher(canonical);
		if (!suffixMatcher.find()) return false;
		String suffix = suffixMatcher.group(1);
		String prefix = canonical.substring(0, suffixMatcher.start(1));
		Pattern token = Pattern.compile("(?iu)(?<![A-Z0-9])" + Pattern.quote(prefix)
				+ "\\s*(?:-\\s*)?" + Pattern.quote(suffix) + "(?![A-Z0-9])");
		return token.matcher(pageText).find();
	}

	private FlightAssessment assessmentOf(DocumentFlightSegment segment) {
		return new FlightAssessment(segment.flightNumber(), segment.flightNumberConfidence(),
				segment.requiresFlightNumberConfirmation(), "MERGED_EVIDENCE");
	}

	private FlightAssessment strongerAssessment(FlightAssessment left, FlightAssessment right) {
		return confidenceRank(left.confidence()) >= confidenceRank(right.confidence()) ? left : right;
	}

	private int confidenceRank(FlightNumberConfidence confidence) {
		return switch (confidence == null ? FlightNumberConfidence.LOW : confidence) {
			case HIGH -> 3;
			case MEDIUM -> 2;
			case LOW -> 1;
		};
	}

	private <T> T mergeScalar(T understand, T spatial, String field, Set<String> conflicts,
			boolean preferSpatial) {
		if (understand == null) return spatial;
		if (spatial == null || Objects.equals(understand, spatial)) return understand;
		conflicts.add(field);
		return preferSpatial ? spatial : null;
	}

	private DateMerge mergeDepartureDate(DocumentFlightSegment understand,
			DocumentFlightSegment spatial, Set<String> conflicts) {
		DateParts understandDate = new DateParts(understand.departureDate(),
				understand.departureMonthDay(), understand.dateSource());
		DateParts spatialDate = new DateParts(spatial.departureDate(),
				spatial.departureMonthDay(), spatial.dateSource());
		DateParts selected = combineDateEvidence(understandDate, spatialDate);
		if (selected.source() == DateSource.CONFLICT) {
			conflicts.add("departure_date");
			return DateMerge.CONFLICT;
		}
		return new DateMerge(selected.date(), selected.monthDay(), selected.source());
	}

	private String mergeSchedule(String understand, String spatial, String field, Set<String> conflicts) {
		if (understand == null) return spatial;
		if (spatial == null || understand.equals(spatial)) return understand;
		String understandTime = timePart(understand);
		String spatialTime = timePart(spatial);
		if (understandTime != null && understandTime.equals(spatialTime)) {
			LocalDate understandDate = datePartsFromDateTime(understand).date();
			LocalDate spatialDate = datePartsFromDateTime(spatial).date();
			if (understandDate == null) return spatialDate == null ? understand : spatial;
			if (spatialDate == null || understandDate.equals(spatialDate)) return understand;
		}
		conflicts.add(field);
		return null;
	}

	private Map<String, ExtractedField> mergeEvidence(Map<String, ExtractedField> understand,
			Map<String, ExtractedField> spatial, String flight, String airlineName,
			LocalDate date, String monthDay,
			String departure, String arrival, String scheduledDeparture, String scheduledArrival,
			String boarding, String terminal, String arrivalTerminal, String gate, String seat) {
		Map<String, String> finalValues = new LinkedHashMap<>();
		finalValues.put("flight_number", flight);
		finalValues.put("airline_name", airlineName);
		finalValues.put("departure_date", date == null ? monthDay : date.toString());
		finalValues.put("departure_airport", departure);
		finalValues.put("arrival_airport", arrival);
		finalValues.put("scheduled_departure", scheduledDeparture);
		finalValues.put("scheduled_arrival", scheduledArrival);
		finalValues.put("boarding_time", boarding);
		finalValues.put("terminal", terminal);
		finalValues.put("departure_terminal", terminal);
		finalValues.put("arrival_terminal", arrivalTerminal);
		finalValues.put("gate", gate);
		finalValues.put("seat", seat);
		Map<String, ExtractedField> merged = emptyFields();
		for (String name : SEGMENT_FIELD_NAMES) {
			String value = finalValues.get(name);
			ExtractedField selected = evidenceForValue(value, spatial.get(name), understand.get(name));
			if (selected != null) merged.put(name, selected);
			else if (value != null) merged.put(name, new ExtractedField(value, value, 0.0d,
					null, null, "Local deterministic merge"));
		}
		return merged;
	}

	private String fieldValue(DocumentFlightSegment segment, String name) {
		ExtractedField field = segment.fields().get(name);
		return field == null ? null : field.value();
	}

	private ExtractedField evidenceForValue(String value, ExtractedField... fields) {
		if (value == null) return new ExtractedField(null, null, 0.0d, null, null, null);
		for (ExtractedField field : fields) {
			if (field != null && Objects.equals(value, field.value())) return field;
		}
		return null;
	}

	private BoardingPassExtractionResponse auditedResponse(BoardingPassExtractionResponse base,
			MergeOutcome merged, boolean structureSucceeded, SpatialPass spatial) {
		List<DocumentFlightSegment> segments = merged.segments();
		List<DocumentReviewIssue> reviewIssues = appendSpatialTimeAdvisories(
				buildReviewIssues(segments, merged), segments, merged, spatial);
		LinkedHashSet<String> review = new LinkedHashSet<>();
		propagateBaseReviewFields(review, base, segments);
		review.removeIf(this::internalOrLegacyConflictReviewField);
		for (int index = 0; index < segments.size(); index++) {
			DocumentFlightSegment segment = segments.get(index);
			addMissingReviewFields(review, segment, index);
		}
		for (DocumentReviewIssue issue : reviewIssues) {
			if (!issue.blocking()) continue;
			int index = issue.segmentIndex() == null ? 0 : issue.segmentIndex();
			review.add("segments[" + index + "].conflict." + issue.field());
		}
		for (int index = 0; index < segments.size(); index++) {
			reconcileResolvedReviewFields(review, segments.get(index), index, segments.size());
		}

		List<String> reviewFields = review.stream().map(field -> segments.size() == 1
				? field.replace("segments[0].", "") : field).toList();
		Map<String, ExtractedField> rootFields = new LinkedHashMap<>(base.fields());
		if (segments.size() == 1) rootFields.putAll(segments.get(0).fields());
		return new BoardingPassExtractionResponse(base.documentType(), null, null, null,
				FlightNumberConfidence.LOW, false, false, DateSource.MISSING,
				null, null, null, null, null, null, null, null, false, false, rootFields,
				segments, reviewFields, reviewIssues, DATA_SOURCE, base.extractedAt());
	}

	private List<DocumentReviewIssue> appendSpatialTimeAdvisories(
			List<DocumentReviewIssue> baseIssues, List<DocumentFlightSegment> segments,
			MergeOutcome merged, SpatialPass spatial) {
		if (spatial == null || spatial.diagnosticCandidates().isEmpty()) return baseIssues;
		List<DocumentReviewIssue> issues = new ArrayList<>(baseIssues);
		Map<String, List<DocumentFieldCandidate>> grouped = new LinkedHashMap<>();
		for (DocumentFieldCandidate candidate : spatial.diagnosticCandidates()) {
			if (!("departure_time".equals(candidate.field())
					|| "arrival_time".equals(candidate.field()))) continue;
			Integer index = candidate.segmentIndex();
			if (index == null || index < 0 || index >= segments.size()) continue;
			grouped.computeIfAbsent(index + "|" + candidate.field(), ignored -> new ArrayList<>())
					.add(candidate);
		}
		for (Map.Entry<String, List<DocumentFieldCandidate>> entry : grouped.entrySet()) {
			DocumentFieldCandidate first = entry.getValue().get(0);
			int index = first.segmentIndex();
			String field = first.field();
			int existingIndex = -1;
			for (int issueIndex = 0; issueIndex < issues.size(); issueIndex++) {
				DocumentReviewIssue issue = issues.get(issueIndex);
				if (Objects.equals(issue.segmentIndex(), index) && field.equals(issue.field())) {
					existingIndex = issueIndex;
					break;
				}
			}
			List<DocumentFieldCandidate> candidates = new ArrayList<>();
			if (existingIndex >= 0) candidates.addAll(issues.get(existingIndex).candidates());
			else candidates.addAll(merged.candidates().get(index).stream()
					.filter(candidate -> field.equals(candidate.field())).toList());
			candidates.addAll(entry.getValue());
			LinkedHashMap<String, DocumentFieldCandidate> distinct = new LinkedHashMap<>();
			for (DocumentFieldCandidate candidate : candidates) {
				String key = candidate.source() + "|" + candidate.normalizedValue() + "|"
						+ candidate.page() + "|" + candidate.boundingBox();
				distinct.putIfAbsent(key, candidate);
			}
			String selected = selectedPublicValue(segments.get(index), field);
			String reason = entry.getValue().stream()
					.anyMatch(candidate -> "AMBIGUOUS_TIME_ROLE".equals(candidate.reason()))
							? "AMBIGUOUS_TIME_ROLE" : "UNBOUND_TIME_EVIDENCE";
			DocumentReviewIssue advisory = new DocumentReviewIssue(index, field,
					DocumentReviewIssue.Kind.ADVISORY, false,
					List.copyOf(distinct.values()), selected, reason);
			if (existingIndex >= 0) issues.set(existingIndex, advisory);
			else issues.add(advisory);
		}
		return List.copyOf(issues);
	}

	private boolean internalOrLegacyConflictReviewField(String value) {
		String field = Objects.toString(value, "");
		return field.contains(".conflict.") || field.startsWith("conflict.")
				|| field.startsWith("spatial_") || field.startsWith("duplicate_")
				|| field.startsWith("cross_segment_");
	}

	private List<DocumentReviewIssue> buildReviewIssues(List<DocumentFlightSegment> segments,
			MergeOutcome merged) {
		List<DocumentReviewIssue> issues = new ArrayList<>();
		for (int index = 0; index < segments.size(); index++) {
			DocumentFlightSegment segment = segments.get(index);
			List<DocumentFieldCandidate> candidates = merged.candidates().get(index);
			Set<String> emitted = new LinkedHashSet<>();
			for (String internalConflict : merged.conflicts().get(index)) {
				if (internalConflict.endsWith("_resolved")) continue;
				String field = publicReviewField(internalConflict);
				if (field == null || !emitted.add(field)) continue;
				List<DocumentFieldCandidate> fieldCandidates = candidates.stream()
						.filter(candidate -> field.equals(candidate.field())).toList();
				String selected = selectedPublicValue(segment, field);
				boolean unresolvedCritical = ("flight_number".equals(field)
						|| "departure_date".equals(field))
						&& distinctCandidateValueCount(fieldCandidates) >= 2;
				if (unresolvedCritical) selected = null;
				issues.add(new DocumentReviewIssue(index, field,
						unresolvedCritical ? DocumentReviewIssue.Kind.ACTIONABLE
								: DocumentReviewIssue.Kind.ADVISORY,
						unresolvedCritical, finalizeCandidates(fieldCandidates, selected), selected,
						unresolvedCritical ? "MULTIPLE_UNRESOLVED_CANDIDATES"
								: "AUXILIARY_EVIDENCE_DISAGREEMENT"));
			}
			for (String criticalField : List.of("flight_number", "departure_date")) {
				if (emitted.contains(criticalField)
						|| selectedPublicValue(segment, criticalField) != null) continue;
				List<DocumentFieldCandidate> fieldCandidates = candidates.stream()
						.filter(candidate -> criticalField.equals(candidate.field())).toList();
				if (distinctCandidateValueCount(fieldCandidates) < 2) continue;
				emitted.add(criticalField);
				issues.add(new DocumentReviewIssue(index, criticalField,
						DocumentReviewIssue.Kind.ACTIONABLE, true,
						finalizeCandidates(fieldCandidates, null), null,
						"MULTIPLE_UNRESOLVED_CANDIDATES"));
			}
			if (segment.flightNumber() != null
					&& segment.flightNumberConfidence() == FlightNumberConfidence.LOW
					&& emitted.add("flight_number")) {
				List<DocumentFieldCandidate> flightCandidates = candidates.stream()
						.filter(candidate -> "flight_number".equals(candidate.field())).toList();
				issues.add(new DocumentReviewIssue(index, "flight_number",
						DocumentReviewIssue.Kind.ADVISORY, false,
						finalizeCandidates(flightCandidates, segment.flightNumber()),
						segment.flightNumber(), "SINGLE_LOW_CONFIDENCE_CANDIDATE"));
			}
		}
		return List.copyOf(issues);
	}

	private String publicReviewField(String internalField) {
		return switch (internalField) {
			case "scheduled_departure" -> "departure_time";
			case "scheduled_arrival" -> "arrival_time";
			case "terminal" -> "departure_terminal";
			case "flight_number", "airline_name", "departure_date", "departure_airport", "arrival_airport",
					"departure_terminal", "arrival_terminal" -> internalField;
			default -> null;
		};
	}

	private String selectedPublicValue(DocumentFlightSegment segment, String field) {
		return switch (field) {
			case "flight_number" -> segment.flightNumber();
			case "airline_name" -> fieldValue(segment, "airline_name");
			case "departure_date" -> segment.departureDate() == null
					? segment.departureMonthDay() : segment.departureDate().toString();
			case "departure_airport" -> segment.departureAirport();
			case "arrival_airport" -> segment.arrivalAirport();
			case "departure_time" -> timePart(segment.scheduledDeparture());
			case "arrival_time" -> timePart(segment.scheduledArrival());
			case "departure_terminal" -> segment.terminal();
			case "arrival_terminal" -> fieldValue(segment, "arrival_terminal");
			default -> null;
		};
	}

	private long distinctCandidateValueCount(List<DocumentFieldCandidate> candidates) {
		return candidates.stream().map(DocumentFieldCandidate::normalizedValue)
				.filter(Objects::nonNull).distinct().count();
	}

	private List<DocumentFieldCandidate> finalizeCandidates(List<DocumentFieldCandidate> candidates,
			String selectedValue) {
		LinkedHashMap<String, DocumentFieldCandidate> unique = new LinkedHashMap<>();
		for (DocumentFieldCandidate candidate : candidates) {
			String key = candidate.source() + "|" + candidate.normalizedValue() + "|"
					+ candidate.page() + "|" + candidate.boundingBox();
			unique.putIfAbsent(key, candidate);
		}
		candidates = List.copyOf(unique.values());
		long supportingSources = selectedValue == null ? 0 : candidates.stream()
				.filter(candidate -> selectedValue.equals(candidate.normalizedValue()))
				.map(DocumentFieldCandidate::source).distinct().count();
		return candidates.stream().map(candidate -> {
			if (selectedValue == null) {
				return candidate.withDisposition(Disposition.CANDIDATE, "AWAITING_USER_SELECTION");
			}
			if (selectedValue.equals(candidate.normalizedValue())) {
				return candidate.withDisposition(supportingSources > 1
						? Disposition.MERGED : Disposition.SELECTED,
						supportingSources > 1 ? "CROSS_CHANNEL_AGREEMENT" : "BEST_AVAILABLE_EVIDENCE");
			}
			return candidate.withDisposition(Disposition.REPLACED, "LOWER_RANKED_EVIDENCE");
		}).toList();
	}

	private void reconcileResolvedReviewFields(Set<String> review, DocumentFlightSegment segment,
			int index, int segmentCount) {
		String prefix = segmentCount == 1 ? "" : "segments[" + index + "].";
		if (segment.flightNumber() != null && !segment.requiresFlightNumberConfirmation()) {
			review.remove(prefix + "flight_number");
		}
		if (segment.departureDate() != null) review.remove(prefix + "departure_date");
		if (segment.departureAirport() != null) review.remove(prefix + "departure_airport");
		if (segment.arrivalAirport() != null) review.remove(prefix + "arrival_airport");
	}

	private void propagateBaseReviewFields(Set<String> review,
			BoardingPassExtractionResponse base, List<DocumentFlightSegment> mergedSegments) {
		List<DocumentFlightSegment> baseSegments = base.segments();
		for (String rawField : base.needsReviewFields()) {
			String field = Objects.toString(rawField, "");
			if (field.isBlank()) continue;
			Matcher indexed = SEGMENT_REVIEW_PATTERN.matcher(field);
			int sourceIndex = -1;
			String segmentField = null;
			if (indexed.matches()) {
				sourceIndex = Integer.parseInt(indexed.group(1));
				segmentField = indexed.group(2);
			} else if (baseSegments.size() == 1 && isSegmentReviewField(field)) {
				sourceIndex = 0;
				segmentField = field;
			}
			if (segmentField == null) {
				review.add(field);
				continue;
			}
			int targetIndex = mergedIndexForBaseSegment(baseSegments, sourceIndex, mergedSegments);
			if (targetIndex < 0) continue;
			review.add(mergedSegments.size() == 1 ? segmentField
					: "segments[" + targetIndex + "]." + segmentField);
		}
	}

	private boolean isSegmentReviewField(String field) {
		return field.startsWith("conflict.") || SEGMENT_FIELD_NAMES.contains(field);
	}

	private int mergedIndexForBaseSegment(List<DocumentFlightSegment> baseSegments,
			int sourceIndex, List<DocumentFlightSegment> mergedSegments) {
		if (sourceIndex < 0 || sourceIndex >= baseSegments.size()) return -1;
		String sourceFlight = normalizeFlightNumber(baseSegments.get(sourceIndex).flightNumber());
		if (sourceFlight != null) {
			for (int index = 0; index < mergedSegments.size(); index++) {
				if (sourceFlight.equals(normalizeFlightNumber(mergedSegments.get(index).flightNumber()))) {
					return index;
				}
			}
		}
		return sourceIndex < mergedSegments.size() ? sourceIndex : -1;
	}

	private BoardingPassExtractionResponse emptyResponse() {
		return new BoardingPassExtractionResponse(DocumentType.UNKNOWN, null, null, null,
				null, null, null, null, null, null, null, null, false, false, Map.of(),
				List.of(), List.of("document_type"), DATA_SOURCE, Instant.now(clock));
	}

	private void addMissingReviewFields(java.util.Collection<String> review,
			DocumentFlightSegment segment, int index) {
		String prefix = "segments[" + index + "].";
		if (segment.flightNumber() == null) review.add(prefix + "flight_number");
		if (segment.departureDate() == null) review.add(prefix + "departure_date");
		if (segment.departureAirport() == null) review.add(prefix + "departure_airport");
		if (segment.arrivalAirport() == null) review.add(prefix + "arrival_airport");
	}

	private void logOutcome(BoardingPassExtractionResponse result, boolean understandSucceeded,
			boolean textSucceeded, boolean structureSucceeded, List<String> callTypes,
			SpatialPass spatial, MergeOutcome merged) {
		long incomplete = result.segments().stream().filter(segment -> segment.flightNumber() == null
				|| segment.departureDate() == null || segment.departureAirport() == null
				|| segment.arrivalAirport() == null).count();
		long conflicts = merged.conflicts().stream().filter(values -> !values.isEmpty()).count();
		LOGGER.info("documentExtraction understandSucceeded={} textSucceeded={} structureSucceeded={} nutrientCallCount={} callTypes={} structureElementCount={} structurePageCount={} structureDistinctFlightCount={} structureDuplicateFlightEvidenceCount={} structureDiscardedEvidenceCount={} finalSegmentCount={} incompleteSegmentCount={} conflictSegmentCount={}",
				understandSucceeded, textSucceeded, structureSucceeded, callTypes.size(), callTypes,
				spatial == null ? 0 : spatial.blocks().size(),
				spatial == null ? 0 : spatial.pageCount(), spatial == null ? 0 : spatial.distinctFlightCount(),
				spatial == null ? 0 : spatial.duplicateFlightEvidenceCount(),
				spatial == null ? 0 : spatial.discardedEvidenceCount(), result.segments().size(),
				incomplete, conflicts);
	}

	private void logDebugAudit(BoardingPassExtractionResponse result, MergeOutcome merged,
			SpatialPass spatial, List<String> callTypes) {
		LOGGER.info("documentExtractionDebug enabled=true auditVersion=2 segmentCount={} nutrientCallCount={} callTypes={}",
				result.segments().size(), callTypes.size(), callTypes);
		for (int index = 0; index < merged.candidates().size(); index++) {
			DocumentFlightSegment segment = index < result.segments().size()
					? result.segments().get(index) : null;
			Map<String, String> selectedByField = new LinkedHashMap<>();
			for (String field : DocumentFieldCandidate.ALLOWED_FIELDS) {
				selectedByField.put(field, segment == null ? null : selectedPublicValue(segment, field));
			}
			for (DocumentReviewIssue issue : result.reviewIssues()) {
				if (issue.segmentIndex() == null || issue.segmentIndex() == index) {
					selectedByField.put(issue.field(), issue.selectedValue());
				}
			}
			List<DocumentFieldCandidate> evidence = merged.candidates().get(index);
			for (String field : DocumentFieldCandidate.ALLOWED_FIELDS.stream().sorted().toList()) {
				String selected = selectedByField.get(field);
				for (DocumentFieldCandidate candidate : finalizeCandidates(evidence.stream()
						.filter(item -> field.equals(item.field())).toList(), selected)) {
					LOGGER.info("documentExtractionDebug source={} pageIndex={} regionPath={} hasOwnBoundingBox={} cardId={} sourceBundleId={} fieldType={} rawValue={} normalizedValue={} scheduleRole={} disposition={} reason={} confidence={} bounds={} anchor={} segment={}",
							candidate.source(), candidate.page() == null ? "none" : candidate.page(),
							"unknown", candidate.boundingBox() != null, "unknown", "unknown",
							candidate.field(), candidate.rawValue(), candidate.normalizedValue(),
							scheduleRole(candidate.field(), candidate.rawValue()), candidate.disposition(),
							auditReason(candidate), candidate.confidence(), debugBounds(candidate.boundingBox()),
							candidate.nearbyAnchor(), index);
				}
			}
		}
		if (spatial != null) {
			for (EvidenceBlock block : spatial.blocks()) {
				LOGGER.info("documentExtractionDebug source={} pageIndex={} regionPath={} hasOwnBoundingBox={} cardId={} sourceBundleId={} fieldType={} normalizedValue={} scheduleRole={} disposition={} reason={}",
						block.source(), block.pageIndex(), block.regionPath(), block.hasOwnBoundingBox(),
						spatial.cards().stream().filter(card -> card.pageIndex() == block.pageIndex()
								&& blockInRegion(block, card.regionPath())).map(StructureCard::id)
								.findFirst().orElse("none"), block.sourceBundleId(), "structure_text",
						allowListedAuditValue(block.normalizedValue()), scheduleRole("", block.text()),
						"CANDIDATE", metadataLabelContext(block.text()) == MetadataContext.NONE
								? "ACCEPTED" : "METADATA_REJECTED");
			}
			for (DocumentFieldCandidate candidate : spatial.diagnosticCandidates()) {
				LOGGER.info("documentExtractionDebug source={} pageIndex={} regionPath={} hasOwnBoundingBox={} cardId={} sourceBundleId={} fieldType={} rawValue={} normalizedValue={} scheduleRole={} disposition={} reason={} confidence={} bounds={} anchor={} segment={}",
						candidate.source(), candidate.page() == null ? "none" : candidate.page(),
						"unknown", candidate.boundingBox() != null, "unknown", "structure:diagnostic",
						candidate.field(), candidate.rawValue(), candidate.normalizedValue(),
						scheduleRole(candidate.field(), candidate.rawValue()), candidate.disposition(),
						auditReason(candidate), candidate.confidence(), debugBounds(candidate.boundingBox()),
						candidate.nearbyAnchor(),
						candidate.segmentIndex() == null ? "unbound" : candidate.segmentIndex());
			}
		}
	}

	private String auditReason(DocumentFieldCandidate candidate) {
		String reason = Objects.toString(candidate.reason(), "");
		if (reason.contains("METADATA")) return "METADATA_REJECTED";
		if (reason.contains("AIRCRAFT")) return "AIRCRAFT_CONTEXT_REJECTED";
		if (reason.contains("BARE_NUMBER")) return "BARE_NUMBER_REJECTED";
		if (reason.contains("OCR")) return "AMBIGUOUS_OCR_REPAIR";
		if (reason.contains("AMBIGUOUS") && reason.contains("CARD")) return "AMBIGUOUS_CARD";
		if (reason.contains("ARRIVAL")) return "ARRIVAL_ROLE_REJECTED";
		if (reason.contains("BUNDLE")) return "SOURCE_BUNDLE_SELECTED";
		if (reason.contains("NATIVE")) return "NATIVE_ROW_SELECTED";
		if (reason.contains("PARTIAL")) return "PARTIAL_ABSORBED";
		if (reason.contains("FRAGMENT")) return "ARRIVAL_FRAGMENT_HIDDEN";
		if (candidate.disposition() == Disposition.CANDIDATE
				|| candidate.disposition() == Disposition.IGNORED) return "CONFLICT_RETAINED";
		return "ACCEPTED";
	}

	private String allowListedAuditValue(String value) {
		if (value == null) return "none";
		String flight = normalizeFlightNumber(value);
		if (flight != null) return flight;
		String airport = normalizeAirport(value);
		if (airport != null) return airport;
		String time = timePart(value);
		if (time != null) return time;
		DateParts date = dateParts(value);
		return date.hasValue() ? date.publicValue() : "redacted";
	}

	private String debugBounds(BoundingBox box) {
		if (box == null) return "none";
		return String.format(Locale.ROOT, "x=%.2f,y=%.2f,w=%.2f,h=%.2f",
				box.x(), box.y(), box.width(), box.height());
	}

	private boolean hasUnboundSpatialFlight(SpatialPass spatial,
			List<DocumentFlightSegment> segments) {
		Set<String> finalFlights = new LinkedHashSet<>();
		for (DocumentFlightSegment segment : segments) {
			String flight = normalizeFlightNumber(segment.flightNumber());
			if (flight != null) finalFlights.add(flight);
		}
		for (SegmentCandidate candidate : spatial.candidates()) {
			String flight = normalizeFlightNumber(candidate.flightNumber());
			if (flight != null && !finalFlights.contains(flight)) return true;
		}
		return false;
	}

	private boolean hasDuplicateSegments(List<DocumentFlightSegment> segments) {
		Set<String> seen = new LinkedHashSet<>();
		for (DocumentFlightSegment segment : segments) {
			String flight = normalizeFlightNumber(segment.flightNumber());
			if (flight == null) continue;
			String identity = flight + "|" + segment.departureDate() + "|" + segment.page()
					+ "|" + segment.departureAirport() + "|" + segment.arrivalAirport()
					+ "|" + timePart(segment.scheduledDeparture());
			if (!seen.add(identity)) return true;
		}
		return false;
	}

	private boolean hasNonAdjacentAirportEvidenceReuse(List<DocumentFlightSegment> segments) {
		Map<String, Integer> firstSegmentByEvidence = new LinkedHashMap<>();
		for (int index = 0; index < segments.size(); index++) {
			DocumentFlightSegment segment = segments.get(index);
			for (String fieldName : List.of("departure_airport", "arrival_airport")) {
				ExtractedField field = segment.fields().get(fieldName);
				if (field == null || field.value() == null || field.sourceLabel() == null
						|| !field.sourceLabel().startsWith("Nutrient Structure Spatial element ")) continue;
				Integer first = firstSegmentByEvidence.putIfAbsent(field.sourceLabel(), index);
				if (first != null && Math.abs(index - first) > 1) return true;
			}
		}
		return false;
	}

	private ExtractedField sanitizeField(String name, ExtractedField field) {
		String raw = field.rawValue();
		String providerValue = field.value();
		String value = switch (name) {
			case "flight_number" -> firstNonNull(normalizeFlightNumber(providerValue), normalizeFlightNumber(raw));
			case "airline_name" -> firstNonNull(normalizeAirlineName(providerValue), normalizeAirlineName(raw));
			case "departure_date" -> departureDateEvidence(
					parseScheduleRawValue(name, raw, providerValue)).publicValue();
			case "date", "datetime" -> departureDateEvidence(
					parseScheduleRawValue(name, raw, providerValue)).publicValue();
			case "departure_airport", "arrival_airport" -> firstNonNull(
					normalizeAirport(providerValue), normalizeAirport(raw));
			case "scheduled_departure", "scheduled_arrival" -> validScheduleValue(name, raw, providerValue);
			case "boarding_time" -> normalizeTime(raw);
			case "terminal", "departure_terminal", "arrival_terminal" -> safeTerminalValue(raw);
			case "gate", "seat" -> safeShortValue(raw);
			default -> null;
		};
		return new ExtractedField(value, raw, field.confidence(), field.page(), field.boundingBox(),
				field.sourceLabel());
	}

	private Map<String, ExtractedField> emptyFields() {
		Map<String, ExtractedField> fields = new LinkedHashMap<>();
		for (String name : SEGMENT_FIELD_NAMES) {
			fields.put(name, new ExtractedField(null, null, 0.0d, null, null, null));
		}
		return fields;
	}

	private Map<String, String> fieldSources(DocumentFlightSegment segment) {
		Map<String, String> sources = new LinkedHashMap<>();
		segment.fields().forEach((name, field) -> {
			if (field != null && field.value() != null) sources.put(name, field.sourceLabel());
		});
		return Map.copyOf(sources);
	}

	private boolean hasLegacySegment(JsonNode data) {
		return SEGMENT_FIELD_NAMES.stream().anyMatch(name -> {
			JsonNode field = data.path(name);
			return valueOrNull(field) != null || field.isObject()
					&& (valueOrNull(field.path("value")) != null
							|| valueOrNull(field.path("rawValue")) != null
							|| valueOrNull(field.path("raw_value")) != null);
		});
	}

	private JsonNode segmentMetadata(JsonNode metadata, int index) {
		JsonNode segments = metadata.path("segments");
		if (segments.isArray()) return segments.path(index);
		if (segments.isObject() && !segments.path(Integer.toString(index)).isMissingNode()) {
			return segments.path(Integer.toString(index));
		}
		return metadata;
	}

	private ExtractedField extractedField(JsonNode data, JsonNode metadata, String name, String sourcePath) {
		return extractedField(data, metadata, name, sourcePath, UNDERSTAND_STAGE, "understand:root");
	}

	private ExtractedField extractedField(JsonNode data, JsonNode metadata, String name,
			String sourcePath, String sourceName, String sourceBundleId) {
		JsonNode fieldData = data.path(name);
		String raw = fieldData.isObject() ? firstNonBlank(valueOrNull(fieldData.path("rawValue")),
				firstNonBlank(valueOrNull(fieldData.path("raw_value")), valueOrNull(fieldData.path("value"))))
				: valueOrNull(fieldData);
		String providerValue = fieldData.isObject()
				? firstNonBlank(valueOrNull(fieldData.path("value")), raw) : raw;
		JsonNode fieldMetadata = metadata.path(name);
		double confidence = clampConfidence(fieldMetadata.path("confidence").asDouble(0.0d));
		Integer page = fieldMetadata.path("pageIndex").isNumber()
				? fieldMetadata.path("pageIndex").asInt() : null;
		BoundingBox box = firstNonNull(bounds(fieldMetadata.path("bounds")), bounds(fieldMetadata.path("bbox")));
		return new ExtractedField(providerValue, raw, confidence, page, box,
				"Nutrient " + sourceName + " bundle=" + sourceBundleId + " citation: " + sourcePath);
	}

	private BoundingBox bounds(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) return null;
		if (node.isArray() && node.size() >= 4 && node.path(0).isNumber()
				&& node.path(1).isNumber() && node.path(2).isNumber() && node.path(3).isNumber()) {
			return new BoundingBox(node.path(0).asDouble(), node.path(1).asDouble(),
					node.path(2).asDouble(), node.path(3).asDouble());
		}
		if (!node.isObject()) return null;
		JsonNode xNode = node.path("x").isNumber() ? node.path("x") : node.path("left");
		JsonNode yNode = node.path("y").isNumber() ? node.path("y") : node.path("top");
		if (!xNode.isNumber() || !yNode.isNumber()) return null;
		double x = xNode.asDouble();
		double y = yNode.asDouble();
		double width;
		double height;
		if (node.path("width").isNumber() && node.path("height").isNumber()) {
			width = node.path("width").asDouble();
			height = node.path("height").asDouble();
		} else if (node.path("right").isNumber() && node.path("bottom").isNumber()) {
			width = node.path("right").asDouble() - x;
			height = node.path("bottom").asDouble() - y;
		} else return null;
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
				|| !Double.isFinite(height) || width < 0.0d || height < 0.0d) return null;
		return new BoundingBox(x, y, width, height);
	}

	private String valueOrNull(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) return null;
		String value = node.asText().trim();
		return value.isBlank() ? null : value;
	}

	String normalizeFlightNumber(String value) {
		if (value == null) return null;
		String normalized = normalizeUnicodeDigits(Normalizer.normalize(value, Normalizer.Form.NFKC))
				.replaceAll("[\\p{Z}\\s\\-‐‑‒–—]", "").toUpperCase(Locale.ROOT);
		return FLIGHT_NUMBER_PATTERN.matcher(normalized).matches() ? normalized : null;
	}

	private static String normalizeUnicodeDigits(String value) {
		if (value == null) return null;
		StringBuilder normalized = new StringBuilder(value.length());
		value.codePoints().forEach(codePoint -> {
			int digit = Character.digit(codePoint, 10);
			if (digit >= 0) normalized.append((char) ('0' + digit));
			else normalized.appendCodePoint(codePoint);
		});
		return normalized.toString();
	}

	String repairFlightLikeOccurrence(String value, boolean flightLabelBound,
			int evidenceCategories, boolean aircraftContext) {
		if (value == null || aircraftContext || evidenceCategories < 2) return null;
		String compact = normalizeUnicodeDigits(Normalizer.normalize(value, Normalizer.Form.NFKC))
				.replaceAll("[\\p{Z}\\s\\-‐‑‒–—]", "").toUpperCase(Locale.ROOT);
		boolean mixed = compact.matches(".*[A-Z].*") && compact.matches(".*\\d.*");
		if (!mixed && !flightLabelBound) return null;
		Map<Character, char[]> replacements = Map.ofEntries(
				Map.entry('O', new char[] { '0' }), Map.entry('0', new char[] { 'O' }),
				Map.entry('I', new char[] { '1' }), Map.entry('L', new char[] { '1' }),
				Map.entry('1', new char[] { 'I', 'L' }), Map.entry('S', new char[] { '5' }),
				Map.entry('5', new char[] { 'S' }), Map.entry('B', new char[] { '8' }),
				Map.entry('8', new char[] { 'B' }), Map.entry('Z', new char[] { '2' }),
				Map.entry('2', new char[] { 'Z' }));
		LinkedHashSet<String> repaired = new LinkedHashSet<>();
		for (int index = 0; index < compact.length(); index++) {
			char[] alternatives = replacements.get(compact.charAt(index));
			if (alternatives == null) continue;
			for (char alternative : alternatives) {
				String candidate = compact.substring(0, index) + alternative + compact.substring(index + 1);
				if (!candidate.equals(compact) && FLIGHT_NUMBER_PATTERN.matcher(candidate).matches()) {
					repaired.add(candidate);
				}
			}
		}
		return repaired.size() == 1 ? repaired.iterator().next() : null;
	}

	private String numericFlightSuffix(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
				.toUpperCase(Locale.ROOT).replaceAll("[\\p{Z}\\s\\-‐‑‒–—]", "");
		if (!FLIGHT_SUFFIX_FRAGMENT.matcher(normalized).matches()
				&& !FLIGHT_NUMBER_PATTERN.matcher(normalized).matches()) return null;
		Matcher matcher = FLIGHT_NUMERIC_SUFFIX.matcher(normalized);
		return matcher.find() ? matcher.group(1) : null;
	}

	private String normalizeAirlineName(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
				.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
		if (normalized.isBlank() || normalized.length() > 80) return null;
		return airlineRegistry.searchDisplayName(normalized).candidates().isEmpty() ? null : normalized;
	}

	private FlightAssessment assessUnderstandFlight(ExtractedField field,
			Map<String, ExtractedField> segmentFields) {
		String flight = field == null ? null : normalizeFlightNumber(field.value());
		int categories = segmentEvidenceCategoryCount(segmentFields);
		if (field != null && categories >= 2 && !aircraftContext(field.rawValue())
				&& (flight == null || airlineRegistry.resolveFlightPrefix(flight).candidates().isEmpty())) {
			String repaired = repairFlightLikeOccurrence(field.rawValue(), true, categories, false);
			if (repaired != null) flight = repaired;
		}
		if (flight == null) return FlightAssessment.LOW;
		AirlineResolution resolution = airlineRegistry.resolveFlightPrefix(flight);
		if (resolution.candidates().isEmpty()) {
			return new FlightAssessment(flight, FlightNumberConfidence.LOW, false,
					"UNKNOWN_AIRLINE_PREFIX_SINGLE_CANDIDATE");
		}
		double providerConfidence = field.confidence();
		if (providerConfidence > 0.0d && providerConfidence < 0.50d) {
			return new FlightAssessment(flight, FlightNumberConfidence.LOW, false,
					"LOW_PROVIDER_CONFIDENCE_SINGLE_CANDIDATE");
		}
		return new FlightAssessment(flight, FlightNumberConfidence.MEDIUM, false,
				resolution.ambiguous() ? "AIRLINE_PREFIX_AMBIGUOUS"
								: "UNDERSTAND_SINGLE_CHANNEL");
	}

	private int segmentEvidenceCategoryCount(Map<String, ExtractedField> fields) {
		if (fields == null) return 0;
		int categories = fields.get("departure_airport") != null
				&& fields.get("departure_airport").value() != null
				|| fields.get("arrival_airport") != null && fields.get("arrival_airport").value() != null ? 1 : 0;
		if (fields.get("departure_date") != null && fields.get("departure_date").value() != null) categories++;
		if (fields.get("scheduled_departure") != null && fields.get("scheduled_departure").value() != null
				|| fields.get("scheduled_arrival") != null && fields.get("scheduled_arrival").value() != null) {
			categories++;
		}
		return categories;
	}

	private FlightAssessment assessSpatialFlight(SegmentAnchor anchor) {
		if (anchor == null || anchor.token() == null) return FlightAssessment.LOW;
		String flight = normalizeFlightNumber(anchor.flightNumber());
		if (flight == null) return FlightAssessment.LOW;
		AirlineResolution resolution = airlineRegistry.resolveFlightPrefix(flight);
		if (resolution.candidates().isEmpty()) {
			return new FlightAssessment(flight, FlightNumberConfidence.LOW, false,
					"UNKNOWN_AIRLINE_PREFIX_SINGLE_CANDIDATE");
		}
		boolean reliableGeometry = anchor.token().confidence() >= 0.75d
				&& ("SPATIAL_COMPLETE_TOKEN".equals(anchor.token().reasonCode())
						|| "SPATIAL_WITHIN_ELEMENT_JOIN".equals(anchor.token().reasonCode())
						|| "SPATIAL_ADJACENT_TOKENS".equals(anchor.token().reasonCode()));
		if (resolution.isHighConfidence() && reliableGeometry) {
			return new FlightAssessment(flight, FlightNumberConfidence.HIGH, false,
					anchor.token().reasonCode());
		}
		return new FlightAssessment(flight, FlightNumberConfidence.MEDIUM, false,
				resolution.ambiguous() ? "AIRLINE_PREFIX_AMBIGUOUS" : anchor.token().reasonCode());
	}


	private String normalizeAirport(String value) {
		if (value == null) return null;
		String visible = normalizeUnicodeDigits(Normalizer.normalize(value, Normalizer.Form.NFKC))
				.trim().toUpperCase(Locale.ROOT);
		if (visible.matches("[A-Z]{3}")) return visible;
		LinkedHashSet<String> explicit = new LinkedHashSet<>();
		Matcher matcher = IATA_IN_TEXT.matcher(visible);
		while (matcher.find()) explicit.add(matcher.group(1));
		return explicit.size() == 1 ? explicit.iterator().next() : null;
	}

	DateParts dateParts(String value) {
		return DateParts.from(dateParser.parse(value,
				TravelDocumentDateParser.Source.STRUCTURED_DATE));
	}

	private DateParts datePartsFromDateTime(String value) {
		if (value == null) return DateParts.EMPTY;
		int separator = value.indexOf('T');
		String dateValue = separator > 0 ? value.substring(0, separator) : value;
		return DateParts.from(dateParser.parse(dateValue,
				TravelDocumentDateParser.Source.STRUCTURED_DATE));
	}

	private String validScheduleValue(String value) {
		return validScheduleValue("scheduled_departure", value, value);
	}

	private String validScheduleValue(String fieldName, String rawValue, String providerValue) {
		ScheduleRawValue parsed = parseScheduleRawValue(fieldName, rawValue, providerValue);
		if (parsed.role() == ScheduleRole.ISSUE_OR_BOOKING) return null;
		if ("scheduled_departure".equals(fieldName) && parsed.role() == ScheduleRole.ARRIVAL) return null;
		if ("scheduled_arrival".equals(fieldName) && parsed.role() == ScheduleRole.DEPARTURE) return null;
		String value = firstNonBlank(providerValue, rawValue);
		if (value == null) return null;
		try {
			OffsetDateTime.parse(value);
			return value;
		} catch (DateTimeParseException ignored) {
			// Continue with local date-time and time-only formats.
		}
		try {
			LocalDateTime.parse(value);
			return value;
		} catch (DateTimeParseException ignored) {
			if (parsed.times().isEmpty()) return null;
			return "scheduled_arrival".equals(fieldName)
					? parsed.times().get(parsed.times().size() - 1) : parsed.times().get(0);
		}
	}

	private ScheduleRawValue parseScheduleRawValue(String fieldName, String rawValue,
			String providerValue) {
		LinkedHashSet<String> inputs = new LinkedHashSet<>();
		if (rawValue != null && !rawValue.isBlank()) inputs.add(rawValue);
		if (providerValue != null && !providerValue.isBlank()) inputs.add(providerValue);
		LinkedHashMap<String, LocalDate> dates = new LinkedHashMap<>();
		List<DateParts> parsedDates = new ArrayList<>();
		List<String> times = new ArrayList<>();
		for (String input : inputs) {
			TravelDocumentDateParser.ParseResult structured = dateParser.parse(input,
					TravelDocumentDateParser.Source.STRUCTURED_DATE);
			DateParts structuredParts = DateParts.from(structured);
			if (structuredParts.hasValue()) parsedDates.add(structuredParts);
			for (LocalDate date : structured.candidates()) dates.putIfAbsent(date.toString(), date);
			DateParts isoPrefix = datePartsFromDateTime(input);
			if (isoPrefix.hasValue()) {
				parsedDates.add(isoPrefix);
				for (LocalDate date : isoPrefix.candidates()) dates.putIfAbsent(date.toString(), date);
			}
			for (TravelDocumentDateParser.DateMatch match : dateParser.findDates(input,
					TravelDocumentDateParser.Source.FREE_TEXT)) {
				DateParts matchedParts = DateParts.from(match);
				if (matchedParts.hasValue()) parsedDates.add(matchedParts);
				for (LocalDate date : match.candidates()) dates.putIfAbsent(date.toString(), date);
			}
			Matcher matcher = STRICT_TIME_IN_TEXT.matcher(
					normalizeUnicodeDigits(Normalizer.normalize(input, Normalizer.Form.NFKC)));
			while (matcher.find()) {
				String time = normalizeTime(matcher.group());
				if (time != null) times.add(time);
			}
		}
		DateParts date = dates.isEmpty() ? DateParts.EMPTY
				: dates.size() == 1 ? parsedDates.stream()
						.filter(parts -> parts.candidates().contains(dates.values().iterator().next()))
						.max(Comparator.comparingInt(parts -> parts.source() == DateSource.EXPLICIT_FULL_DATE ? 1 : 0))
						.orElseGet(() -> DateParts.of(dates.values().iterator().next()))
						: new DateParts(null, null, DateSource.CONFLICT,
								List.copyOf(dates.values()), rawValue, null);
		return new ScheduleRawValue(date, List.copyOf(times),
				scheduleRole(fieldName, String.join(" ", inputs)));
	}

	private DateParts departureDateEvidence(ScheduleRawValue evidence) {
		if (evidence == null || evidence.role() == ScheduleRole.ISSUE_OR_BOOKING
				|| evidence.role() == ScheduleRole.ARRIVAL) return DateParts.EMPTY;
		return evidence.date();
	}

	private ScheduleRole scheduleRole(String fieldName, String localText) {
		if (metadataLabelContext(localText) != MetadataContext.NONE) {
			return ScheduleRole.ISSUE_OR_BOOKING;
		}
		if (containsAnyLabel(localText, ARRIVAL_LABELS)) return ScheduleRole.ARRIVAL;
		if (containsAnyLabel(localText, DEPARTURE_LABELS)) return ScheduleRole.DEPARTURE;
		return switch (Objects.toString(fieldName, "")) {
			case "scheduled_arrival" -> ScheduleRole.ARRIVAL;
			case "departure_date", "scheduled_departure" -> ScheduleRole.DEPARTURE;
			default -> ScheduleRole.UNKNOWN;
		};
	}

	private ScheduleRole scheduleRole(List<EvidenceBlock> blocks, PositionedToken<?> token) {
		if (token == null || blocks == null) return ScheduleRole.UNKNOWN;
		for (int index = 0; index < blocks.size(); index++) {
			if (blocks.get(index) == token.block()) {
				return scheduleRole(blocks, index,
						new TextSpan(token.characterOffset(), token.characterOffset() + 1));
			}
		}
		return scheduleRole("", token.block().text());
	}

	ScheduleRole scheduleRole(List<EvidenceBlock> blocks, int blockIndex, TextSpan occurrence) {
		if (blocks == null || blockIndex < 0 || blockIndex >= blocks.size()) return ScheduleRole.UNKNOWN;
		EvidenceBlock block = blocks.get(blockIndex);
		if (metadataContext(blocks, blockIndex, occurrence.start(), occurrence.end())
				!= MetadataContext.NONE) return ScheduleRole.ISSUE_OR_BOOKING;
		List<String> localTexts = new ArrayList<>();
		localTexts.add(block.text());
		for (int index = 0; index < blocks.size(); index++) {
			if (index == blockIndex) continue;
			EvidenceBlock candidate = blocks.get(index);
			boolean paired = metadataPair(candidate, block) && scheduleLabelOnly(candidate.text());
			boolean adjacentLabel = sameNonRootRegion(candidate, block)
					&& Math.abs(candidate.documentOrder() - block.documentOrder()) <= 1
					&& scheduleLabelOnly(candidate.text());
			if (paired || adjacentLabel) localTexts.add(candidate.text());
		}
		if (localTexts.stream().anyMatch(text -> metadataLabelContext(text) != MetadataContext.NONE)) {
			return ScheduleRole.ISSUE_OR_BOOKING;
		}
		if (localTexts.stream().anyMatch(text -> containsAnyLabel(text, ARRIVAL_LABELS))) {
			return ScheduleRole.ARRIVAL;
		}
		if (localTexts.stream().anyMatch(text -> containsAnyLabel(text, DEPARTURE_LABELS))) {
			return ScheduleRole.DEPARTURE;
		}
		return ScheduleRole.UNKNOWN;
	}

	private boolean sameNonRootRegion(EvidenceBlock left, EvidenceBlock right) {
		return left != null && right != null && left.pageIndex() == right.pageIndex()
				&& left.regionPath() != null && !"$".equals(left.regionPath())
				&& left.regionPath().equals(right.regionPath());
	}

	private boolean scheduleLabelOnly(String value) {
		if (!containsAnyLabel(value, ARRIVAL_LABELS) && !containsAnyLabel(value, DEPARTURE_LABELS)
				&& metadataLabelContext(value) == MetadataContext.NONE) return false;
		return dateParser.findDates(value, TravelDocumentDateParser.Source.FREE_TEXT).isEmpty()
				&& !STRICT_TIME_IN_TEXT.matcher(Objects.toString(value, "")).find();
	}

	private String normalizeTime(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		Matcher matcher = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$").matcher(normalized);
		if (!matcher.matches()) return null;
		LocalTime time = LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
		return "%02d:%02d".formatted(time.getHour(), time.getMinute());
	}

	private String timePart(String value) {
		if (value == null) return null;
		Matcher matcher = TIME_IN_TEXT.matcher(value);
		return matcher.find() ? normalizeTime(matcher.group()) : null;
	}

	private String safeShortValue(String value) {
		if (value == null) return null;
		String cleaned = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		return cleaned.length() <= 24 && cleaned.matches("[\\p{L}\\p{N} ./-]+") ? cleaned : null;
	}

	private String safeTerminalValue(String value) {
		if (value == null) return null;
		String cleaned = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
				.toUpperCase(Locale.ROOT)
				.replaceFirst("^(?:TERMINAL|航站楼)\\s*", "")
				.replaceAll("\\s+", "");
		return cleaned.matches("(?:[A-Z]{1,2}|(?=[A-Z0-9-]*\\d)[A-Z0-9-]{1,6})")
				? cleaned : null;
	}

	private double segmentConfidence(Map<String, ExtractedField> fields) {
		return fields.entrySet().stream()
				.filter(entry -> REQUIRED_SEGMENT_FIELDS.contains(entry.getKey()))
				.mapToDouble(entry -> entry.getValue().confidence())
				.filter(value -> value > 0.0d).average().orElse(0.0d);
	}

	private double confidenceThreshold(String name) {
		return "flight_number".equals(name) || "departure_date".equals(name) ? 0.8d : 0.65d;
	}

	private double clampConfidence(double value) {
		return Double.isFinite(value) ? Math.max(0.0d, Math.min(1.0d, value)) : 0.0d;
	}

	private String monthDay(LocalDate date) {
		return date == null ? null : "%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth());
	}

	private String firstNonBlank(String first, String second) {
		return first == null || first.isBlank() ? second : first;
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) if (value != null) return value;
		return null;
	}

	record EvidenceBlock(int pageIndex, String elementType, String text, int documentOrder,
			Double confidence, BoundingBox ownBoundingBox, BoundingBox containerBoundingBox,
			String sourcePath, String regionPath, String source, String sourceBundleId,
			String rawValue, String normalizedValue, List<RegionRef> regionAncestors) {
		BoundingBox box() {
			return ownBoundingBox;
		}

		boolean hasBounds() {
			return ownBoundingBox != null;
		}

		boolean hasOwnBoundingBox() {
			return ownBoundingBox != null;
		}

		double x() {
			return ownBoundingBox == null ? 0.0d : ownBoundingBox.x();
		}

		double y() {
			return ownBoundingBox == null ? documentOrder : ownBoundingBox.y();
		}

		double width() {
			return ownBoundingBox == null ? 0.0d : ownBoundingBox.width();
		}

		double height() {
			return ownBoundingBox == null ? 0.0d : ownBoundingBox.height();
		}
	}

	record DateParts(LocalDate date, String monthDay, DateSource source,
			List<LocalDate> candidates, String rawValue, String normalizedValue) {
		static final DateParts EMPTY = new DateParts(null, null, DateSource.MISSING,
				List.of(), null, null);
		static final DateParts CONFLICT = new DateParts(null, null, DateSource.CONFLICT,
				List.of(), null, null);

		DateParts(LocalDate date, String monthDay, DateSource source) {
			this(date, monthDay, source, date == null ? List.of() : List.of(date),
					date == null ? monthDay : date.toString(), date == null ? monthDay : date.toString());
		}

		static DateParts of(LocalDate date) {
			return date == null ? EMPTY : new DateParts(date,
					"%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth()),
					DateSource.EXPLICIT_FULL_DATE);
		}

		static DateParts from(TravelDocumentDateParser.ParseResult result) {
			if (result == null || result.candidates().isEmpty()) return EMPTY;
			if (result.candidates().size() > 1) {
				return new DateParts(null, null, DateSource.CONFLICT, result.candidates(),
						result.rawValue(), result.normalizedValue());
			}
			LocalDate date = result.candidates().get(0);
			return new DateParts(date,
					"%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth()),
					result.explicitYear() ? DateSource.EXPLICIT_FULL_DATE
							: DateSource.CURRENT_YEAR_FROM_MONTH_DAY,
					result.candidates(), result.rawValue(), result.normalizedValue());
		}

		static DateParts from(TravelDocumentDateParser.DateMatch match) {
			if (match == null || match.candidates().isEmpty()) return EMPTY;
			if (match.candidates().size() > 1) {
				return new DateParts(null, null, DateSource.CONFLICT, match.candidates(),
						match.rawValue(), match.normalizedValue());
			}
			LocalDate date = match.candidates().get(0);
			return new DateParts(date,
					"%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth()),
					match.explicitYear() ? DateSource.EXPLICIT_FULL_DATE
							: DateSource.CURRENT_YEAR_FROM_MONTH_DAY,
					match.candidates(), match.rawValue(), match.normalizedValue());
		}

		boolean hasValue() {
			return date != null || monthDay != null || !candidates.isEmpty();
		}

		String publicValue() {
			return date == null ? monthDay : date.toString();
		}
	}

	private record DateMerge(LocalDate date, String monthDay, DateSource source) {
		private static final DateMerge CONFLICT = new DateMerge(null, null, DateSource.CONFLICT);
	}

	private record RegionRef(String path, BoundingBox boundingBox) {
	}

	private record EvidenceContext(int pageIndex, String elementType, BoundingBox containerBoundingBox,
			Double confidence, String regionPath, List<RegionRef> regionAncestors) {
	}

	private record EvidenceNormalization(List<EvidenceBlock> blocks, int discardedEvidenceCount) {
	}

	record TextSpan(int start, int end) {
		private boolean overlaps(int otherStart, int otherEnd) {
			return start < otherEnd && otherStart < end;
		}
	}

	private record OccurrenceRef(int blockIndex, TextSpan span) {
	}

	private record PositionedToken<T>(EvidenceBlock block, int characterOffset, T value,
			double confidence, String reasonCode, String evidenceReference) {
		private PositionedToken(EvidenceBlock block, int characterOffset, T value, double confidence) {
			this(block, characterOffset, value, confidence, "SPATIAL_TOKEN",
					"element " + block.documentOrder());
		}
	}

	private record SegmentAnchor(String flightNumber, PositionedToken<String> token,
			String cardId, BoundingBox cardBoundingBox) {
		private SegmentAnchor(String flightNumber, PositionedToken<String> token) {
			this(flightNumber, token, null, null);
		}

		private SegmentAnchor withCard(StructureCard card) {
			return card == null ? this : new SegmentAnchor(flightNumber, token,
					card.id(), card.containerBoundingBox());
		}
	}

	private record StructureCard(String id, int pageIndex, String regionPath,
			BoundingBox containerBoundingBox, List<BoundingBox> flightBoundingBoxes) {
	}

	private record TokenRoute(PositionedToken<String> departure, PositionedToken<String> arrival) {
	}

	private enum CandidateSource {
		UNDERSTAND, TEXT, STRUCTURE, NATIVE_ROW
	}

	private enum TimeRole {
		DEPARTURE, ARRIVAL, UNKNOWN
	}

	enum ScheduleRole {
		DEPARTURE, ARRIVAL, ISSUE_OR_BOOKING, UNKNOWN
	}

	private record ScheduleRawValue(DateParts date, List<String> times, ScheduleRole role) {
	}

	private record SegmentCandidate(DocumentFlightSegment segment, String flightNumber,
			int documentOrder, CandidateSource source, Map<String, String> fieldSources,
			String cardId, String sourceBundleId) {
		private SegmentCandidate withCardId(String mappedCardId) {
			return new SegmentCandidate(segment, flightNumber, documentOrder, source,
					fieldSources, mappedCardId, sourceBundleId);
		}
	}

	private record ParsedPass(BoardingPassExtractionResponse response, List<SegmentCandidate> candidates) {
	}

	record SpatialPass(List<EvidenceBlock> blocks, List<SegmentCandidate> candidates,
			List<StructureCard> cards,
			int distinctFlightCount, int pageCount, int discardedEvidenceCount,
			int duplicateFlightEvidenceCount,
			List<DocumentFieldCandidate> diagnosticCandidates) {
	}

	private record ProviderAttempt(JsonNode response, RuntimeException failure) {
	}

	private record FlightAssessment(String flightNumber, FlightNumberConfidence confidence,
			boolean requiresConfirmation, String reasonCode) {
		private static final FlightAssessment LOW = new FlightAssessment(null,
				FlightNumberConfidence.LOW, false, "UNSUPPORTED_FLIGHT_NUMBER");
	}

	private record FlightMerge(String flightNumber, FlightAssessment assessment) {
	}

	private record MergeOutcome(List<DocumentFlightSegment> segments, List<Set<String>> conflicts,
			List<List<DocumentFieldCandidate>> candidates) {
	}

	private static final class MergedEntry {
		private DocumentFlightSegment segment;
		private final int documentOrder;
		private final CandidateSource primarySource;
		private final Set<String> conflicts;
		private final List<DocumentFieldCandidate> candidates;
		private final List<SegmentCandidate> sourceCandidates;
		private final Set<String> cardIds;
		private final Set<String> sourceBundleIds;

		private MergedEntry(SegmentCandidate candidate, List<DocumentFieldCandidate> evidence,
				Set<String> conflicts) {
			this.segment = candidate.segment();
			this.documentOrder = candidate.documentOrder();
			this.primarySource = candidate.source();
			this.conflicts = conflicts;
			if (candidate.segment().dateSource() == DateSource.CONFLICT) {
				this.conflicts.add("departure_date");
			}
			this.candidates = new ArrayList<>(evidence);
			this.sourceCandidates = new ArrayList<>(List.of(candidate));
			this.cardIds = new LinkedHashSet<>();
			if (candidate.cardId() != null) this.cardIds.add(candidate.cardId());
			this.sourceBundleIds = new LinkedHashSet<>();
			if (candidate.sourceBundleId() != null) this.sourceBundleIds.add(candidate.sourceBundleId());
		}

		private void addCandidate(SegmentCandidate candidate, List<DocumentFieldCandidate> evidence) {
			if (candidate == null) return;
			candidates.addAll(evidence);
			sourceCandidates.add(candidate);
			if (candidate.cardId() != null) cardIds.add(candidate.cardId());
			if (candidate.sourceBundleId() != null) sourceBundleIds.add(candidate.sourceBundleId());
		}

		private LinkedHashSet<String> flightNumbers() {
			return sourceCandidates.stream().map(SegmentCandidate::flightNumber)
					.map(NutrientBoardingPassDocumentProcessor::staticNormalizeFlightForEntry)
					.filter(Objects::nonNull)
					.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		}
	}

	private static String staticNormalizeFlightForEntry(String value) {
		if (value == null) return null;
		String normalized = normalizeUnicodeDigits(Normalizer.normalize(value, Normalizer.Form.NFKC))
				.replaceAll("[\\p{Z}\\s\\-‐‑‒–—]", "").toUpperCase(Locale.ROOT);
		return FLIGHT_NUMBER_PATTERN.matcher(normalized).matches() ? normalized : null;
	}
}
