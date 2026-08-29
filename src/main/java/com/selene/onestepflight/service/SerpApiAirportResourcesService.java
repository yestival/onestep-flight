package com.selene.onestepflight.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.selene.onestepflight.dto.AirportResourcesRequest;
import com.selene.onestepflight.dto.AirportResourcesResponse;
import com.selene.onestepflight.dto.AirportResourcesResponse.ContactResource;
import com.selene.onestepflight.dto.AirportResourcesResponse.ContactStatus;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapResource;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapResultType;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapStatus;
import com.selene.onestepflight.service.SerpApiOfficialAirportDomainResolver.ContactPage;
import com.selene.onestepflight.service.SerpApiOfficialAirportDomainResolver.Resolution;
import com.selene.onestepflight.service.SerpApiOfficialAirportDomainResolver.ResolutionStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Canonical-identity airport resource discovery with per-resource verification and bounded requests. */
@Service
public class SerpApiAirportResourcesService {
	private static final Logger LOGGER = LoggerFactory.getLogger(SerpApiAirportResourcesService.class);
	private static final Duration VERIFIED_TTL = Duration.ofDays(7);
	private static final Duration UNVERIFIED_TTL = Duration.ofMinutes(5);
	private static final Duration ERROR_TTL = Duration.ofMinutes(1);
	private static final int REQUEST_BUDGET = 6;
	static final String NO_DEDICATED_PHONE_FIELD = "NO_DEDICATED_PHONE_FIELD";
	static final String NO_PHONE_ROLE = "NO_PHONE_ROLE";
	static final String NO_BOUND_OFFICIAL_SOURCE = "NO_BOUND_OFFICIAL_SOURCE";
	static final String AIRPORT_IDENTITY_MISMATCH = "AIRPORT_IDENTITY_MISMATCH";
	static final String THIRD_PARTY_SOURCE = "THIRD_PARTY_SOURCE";
	static final String SPECIALIZED_SERVICE_PHONE = "SPECIALIZED_SERVICE_PHONE";
	static final String AI_MAPS_PHONE_CONFLICT = "AI_MAPS_PHONE_CONFLICT";
	static final String OBVIOUS_NON_PHONE_NUMBER = "OBVIOUS_NON_PHONE_NUMBER";
	static final String UNVERIFIED_OPERATOR = "UNVERIFIED_OPERATOR";
	static final String CONTACT_PAGE_ONLY = "CONTACT_PAGE_ONLY";

	private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(12, runnable -> {
		Thread thread = new Thread(runnable, "airport-resource-io");
		thread.setDaemon(true);
		return thread;
	});

	private final ObjectMapper mapper;
	private final RestClient restClient;
	private final Supplier<String> apiKeySupplier;
	private final Clock clock;
	private final SerpApiOfficialAirportDomainResolver domainResolver;
	private final Executor ioExecutor;
	private final AirportIdentityCatalog catalog;
	private final AirportIdentityMatcher identityMatcher = new AirportIdentityMatcher();
	private final AirportIdentityReconciler identityReconciler = new AirportIdentityReconciler();
	private final AirportPhoneNormalizer phoneNormalizer = new AirportPhoneNormalizer();
	private final PageAudienceClassifier audienceClassifier = new PageAudienceClassifier();
	private final PassengerContactPhoneExtractor contactPhoneExtractor = new PassengerContactPhoneExtractor();
	private final AirportContactSelector contactSelector = new AirportContactSelector(phoneNormalizer);
	private final AirportMapSelector mapSelector = new AirportMapSelector();
	private final AiResponseCandidateExtractor aiCandidateExtractor;
	private final SerpApiGoogleMapsAirportPlaceParser mapsParser;
	private final Map<ResourceCacheKey, CachedResponse> cache = new ConcurrentHashMap<>();
	private final Map<FlightKey, CompletableFuture<AirportResourcesResponse>> inFlight = new ConcurrentHashMap<>();

	@Autowired
	public SerpApiAirportResourcesService(ObjectMapper mapper,
			SerpApiOfficialAirportDomainResolver domainResolver) {
		this(mapper, buildRestClient(), () -> System.getenv("SERPAPI_API_KEY"), Clock.systemUTC(),
				new OfficialTravelSourcePolicy(), domainResolver, IO_EXECUTOR, new AirportIdentityCatalog(mapper));
	}

	SerpApiAirportResourcesService(ObjectMapper mapper) {
		this(mapper, new SerpApiOfficialAirportDomainResolver(mapper));
	}

	SerpApiAirportResourcesService(ObjectMapper mapper, RestClient restClient,
			Supplier<String> apiKeySupplier, Clock clock, OfficialTravelSourcePolicy sourcePolicy,
			SerpApiOfficialAirportDomainResolver domainResolver) {
		this(mapper, restClient, apiKeySupplier, clock, sourcePolicy, domainResolver, IO_EXECUTOR,
				new AirportIdentityCatalog(mapper));
	}

	SerpApiAirportResourcesService(ObjectMapper mapper, RestClient restClient,
			Supplier<String> apiKeySupplier, Clock clock, OfficialTravelSourcePolicy sourcePolicy,
			SerpApiOfficialAirportDomainResolver domainResolver, Executor ioExecutor) {
		this(mapper, restClient, apiKeySupplier, clock, sourcePolicy, domainResolver, ioExecutor,
				new AirportIdentityCatalog(mapper));
	}

	SerpApiAirportResourcesService(ObjectMapper mapper, RestClient restClient,
			Supplier<String> apiKeySupplier, Clock clock, OfficialTravelSourcePolicy sourcePolicy,
			SerpApiOfficialAirportDomainResolver domainResolver, Executor ioExecutor,
			AirportIdentityCatalog catalog) {
		this.mapper = mapper;
		this.aiCandidateExtractor = new AiResponseCandidateExtractor(mapper);
		this.restClient = restClient;
		this.apiKeySupplier = apiKeySupplier;
		this.clock = clock;
		this.domainResolver = domainResolver;
		this.ioExecutor = ioExecutor;
		this.catalog = catalog;
		this.mapsParser = new SerpApiGoogleMapsAirportPlaceParser(sourcePolicy, identityMatcher, phoneNormalizer);
	}

	private static RestClient buildRestClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(4));
		factory.setReadTimeout(Duration.ofSeconds(25));
		return RestClient.builder().baseUrl("https://serpapi.com").requestFactory(factory).build();
	}

	public AirportResourcesResponse findResources(AirportResourcesRequest request) {
		Instant now = clock.instant();
		ResourceCacheKey cacheKey = cacheKey(request);
		if (!request.forceRefresh()) {
			CachedResponse cached = cache.get(cacheKey);
			if (cached != null && now.isBefore(cached.expiresAt())) {
				return cached.response().withCache(true, true);
			}
			if (cached != null) cache.remove(cacheKey, cached);
		}
		FlightKey flightKey = new FlightKey(cacheKey, request.forceRefresh());
		CompletableFuture<AirportResourcesResponse> own = new CompletableFuture<>();
		CompletableFuture<AirportResourcesResponse> shared = inFlight.putIfAbsent(flightKey, own);
		if (shared != null) return shared.join();
		try {
			AirportResourcesResponse response = execute(request);
			cache.put(cacheKey, new CachedResponse(response, now.plus(ttl(response.identityStatus()))));
			own.complete(response);
			return response;
		} catch (RuntimeException exception) {
			AirportResourcesResponse unavailable = unavailable(request, null, IdentityStatus.UPSTREAM_ERROR, null);
			cache.put(cacheKey, new CachedResponse(unavailable, now.plus(ERROR_TTL)));
			own.complete(unavailable);
			return unavailable;
		} finally {
			inFlight.remove(flightKey, own);
		}
	}

	private AirportResourcesResponse execute(AirportResourcesRequest request) {
		Instant started = clock.instant();
		AirportIdentity canonical = catalog.find(request.airportCode()).orElse(null);
		if (canonical == null) {
			return unavailable(request, null, IdentityStatus.UNVERIFIED,
					trace(request, null, IdentityStatus.UNVERIFIED, started, List.of(), List.of()));
		}
		boolean requestNameConflict = !identityMatcher.requestNameMatches(canonical, request.airportName());
		if (!requestNameConflict && request.airportName() != null) canonical = canonical.withRequestAlias(request.airportName());
		String apiKey = apiKeySupplier.get();
		if (apiKey == null || apiKey.isBlank()) {
			return unavailable(request, canonical, IdentityStatus.UPSTREAM_ERROR,
					trace(request, canonical, IdentityStatus.UPSTREAM_ERROR, started, List.of(), List.of()));
		}

		RequestBudget budget = new RequestBudget(REQUEST_BUDGET);
		AirportIdentity selectedCanonical = canonical;
		StageJson mapsStage = fetchMapsIdentity(request, selectedCanonical, apiKey, budget);
		SerpApiGoogleMapsAirportPlaceParser.Result maps = mapsParser.parse(mapsStage.root(), selectedCanonical);
		boolean searchSkippedStrongMaps = shouldSkipIdentitySearch(request, maps);
		Resolution search;
		if (searchSkippedStrongMaps) search = Resolution.notFound();
		else {
			budget.reserve("IDENTITY_SEARCH");
			search = domainResolver.resolve(selectedCanonical,
					requestNameConflict ? null : request.airportName(),
					catalog.datasetVersion(), request.forceRefresh());
		}
		IdentityDecision decision = identityReconciler.reconcile(selectedCanonical,
				search.domainEvidence(), search.identityCandidates(), maps,
				!searchSkippedStrongMaps && search.status() == ResolutionStatus.ERROR, mapsStage.error());
		int identityRequests = (search.requestMade() ? 1 : 0) + (mapsStage.requestMade() ? 1 : 0);
		IdentityBundle identity = new IdentityBundle(search, mapsStage, maps, decision,
				identityRequests, searchSkippedStrongMaps);

		CompletableFuture<ContactBranch> contactFuture = request.requestsContact()
				? CompletableFuture.supplyAsync(() -> discoverContact(request, identity, apiKey, budget), ioExecutor)
				: CompletableFuture.completedFuture(ContactBranch.notRequested());
		CompletableFuture<MapBranch> mapFuture = request.requestsMap()
				? CompletableFuture.supplyAsync(() -> discoverMap(request, identity, apiKey, budget), ioExecutor)
				: CompletableFuture.completedFuture(MapBranch.notRequested());
		ContactBranch contact = contactFuture.join();
		MapBranch map = mapFuture.join();

		List<DecisionTrace.Entry> entries = new ArrayList<>();
		entries.addAll(identityTraceEntries(identity, requestNameConflict, started));
		entries.addAll(contact.traceEntries());
		entries.addAll(map.traceEntries());
		List<DecisionTrace.HttpStage> stages = new ArrayList<>();
		stages.addAll(identity.search().httpStages());
		stages.addAll(identity.mapsStage().httpStages());
		stages.addAll(contact.httpStages());
		stages.addAll(map.httpStages());
		List<DecisionTrace.AiStage> aiStages = new ArrayList<>();
		aiStages.addAll(contact.aiStages());
		aiStages.addAll(map.aiStages());
		DecisionTrace decisionTrace = trace(request, selectedCanonical, identity.decision().status(), started,
				entries, stages, aiStages);
		LinkedHashSet<String> publicReasons = new LinkedHashSet<>();
		publicReasons.addAll(contact.publicReasons());
		publicReasons.addAll(map.publicReasons());
		int requestCount = identity.requestCount() + contact.requestCount() + map.requestCount();
		if (requestCount > REQUEST_BUDGET) throw new IllegalStateException("SerpApi request budget exceeded");
		AirportResourcesResponse response = new AirportResourcesResponse(request.airportCode(), selectedCanonical.name(),
				request.terminal(), valueOr(identity.maps().localName(), selectedCanonical.name()),
				selectedCanonical.countryName(), map.resource(), contact.resource(), requestCount,
				List.copyOf(publicReasons), clock.instant(), false,
				identity.decision().status().name(), decisionTrace);
		logResult(response, started);
		return response;
	}

	private ContactBranch discoverContact(AirportResourcesRequest request, IdentityBundle identity,
			String apiKey, RequestBudget budget) {
		Instant started = clock.instant();
		if (identity.decision().status() != IdentityStatus.VERIFIED) {
			ContactStatus status = identity.decision().status() == IdentityStatus.UPSTREAM_ERROR
					? ContactStatus.TEMPORARILY_UNAVAILABLE : ContactStatus.NOT_FOUND;
			return new ContactBranch(emptyContact(status, identity), 0,
					List.of(identity.decision().status() == IdentityStatus.PARTIAL
							? "OFFICIAL_CONTACT_CANDIDATES_UNVERIFIED" : "CANONICAL_AIRPORT_NOT_VERIFIED"),
					List.of(), List.of(), List.of());
		}
		Set<String> verifiedHosts = verifiedHosts(identity.decision());
		AirportContactCandidate mapsCandidate = mapsContactCandidate(identity);
		if (mapsCandidate != null) {
			AirportPhoneCandidate phone = mapsCandidate.phoneCandidate();
			ContactResource resource = new ContactResource(ContactStatus.PHONE_FOUND,
					phone.passengerDisplay(), phone.telUri(), null, phone.sourceUrl(),
					identity.maps().localName(), null, phone.sourceType(), phone.role().name(),
					phone.evidenceTier().name(), true, false, true, false, false,
					true, true, List.of());
			AirportContactSelector.Selection selection = contactSelector.select(List.of(mapsCandidate));
			return new ContactBranch(resource, 0, List.of(),
					contactTraceEntries(List.of(mapsCandidate), selection, started), List.of(), List.of());
		}
		List<AirportContactCandidate> candidates = new ArrayList<>();
		List<StageJson> stages = new ArrayList<>();
		StageJson aiStage = fetchContactAi(request, identity, verifiedHosts, apiKey, budget);
		stages.add(aiStage);
		AiContactDiscovery ai = parseContactAi(aiStage.root(), identity, verifiedHosts);
		candidates.addAll(ai.candidates());
		if (!hasUsableContact(candidates)) {
			StageJson supplemental = fetchContactSearch(request, identity, verifiedHosts, apiKey, budget,
					ai.localSearchQuery());
			stages.add(supplemental);
			candidates.addAll(parseContactSearch(supplemental.root(), identity, verifiedHosts));
		}

		AirportContactSelector.Selection selection = contactSelector.select(candidates);
		ContactStatus status = selection.phone() != null ? ContactStatus.PHONE_FOUND
				: selection.contact() != null ? ContactStatus.CONTACT_PAGE_ONLY
				: stages.stream().anyMatch(StageJson::error) ? ContactStatus.TEMPORARILY_UNAVAILABLE
				: ContactStatus.NOT_FOUND;
		AirportPhoneCandidate phone = selection.phone();
		AirportContactCandidate page = selection.contact();
		String sourceUrl = phone != null ? phone.sourceUrl() : page == null ? null : page.sourceUrl();
		ContactResource resource = new ContactResource(status,
				phone == null ? null : phone.passengerDisplay(), phone == null ? null : phone.telUri(),
				page == null ? null : page.contactUrl(), sourceUrl,
				page != null ? page.sourceTitle() : identity.maps().localName(),
				ai.sourceLanguage(), phone == null ? page == null ? null : page.sourceType() : phone.sourceType(),
				phone == null ? page == null ? null : page.role().name() : phone.role().name(),
				phone == null ? null : phone.evidenceTier().name(), true, ai.hadPhoneCandidates(),
				identity.maps().structuredPhonePresent(), selection.conflict(), false,
				identity.maps().structuredPhonePresent(), identity.maps().acceptedStructuredPhonePresent(), List.of());
		List<String> publicReasons = new ArrayList<>();
		if (status == ContactStatus.CONTACT_PAGE_ONLY) publicReasons.add("PHONE_NOT_VERIFIED");
		if (status == ContactStatus.NOT_FOUND) publicReasons.add("NO_VERIFIED_PASSENGER_CONTACT");
		if (status == ContactStatus.TEMPORARILY_UNAVAILABLE) publicReasons.add("CONTACT_UPSTREAM_ERROR");
		return new ContactBranch(resource, stages.stream().mapToInt(stage -> stage.requestMade() ? 1 : 0).sum(),
				List.copyOf(publicReasons), contactTraceEntries(candidates, selection, started), httpStages(stages),
				List.of(withSelected(ai.trace(), selectedAiContactCount(candidates, selection))));
	}

	private MapBranch discoverMap(AirportResourcesRequest request, IdentityBundle identity,
			String apiKey, RequestBudget budget) {
		Instant started = clock.instant();
		Set<String> verifiedHosts = verifiedHosts(identity.decision());
		List<AirportMapCandidate> candidates = new ArrayList<>();
		List<StageJson> stages = new ArrayList<>();
		List<DecisionTrace.AiStage> aiStages = new ArrayList<>();
		if (!verifiedHosts.isEmpty()) {
			StageJson search = fetchMapSearch(request, identity, verifiedHosts, apiKey, budget);
			stages.add(search);
			candidates.addAll(parseMapSearch(search.root(), request, identity, verifiedHosts));
			AirportMapSelector.Selection first = mapSelector.select(candidates, null);
			if (first.selectedCandidate() == null) {
				StageJson ai = fetchMapAi(request, identity, verifiedHosts, apiKey, budget);
				stages.add(ai);
				AiMapDiscovery discovery = parseMapAi(ai.root(), request, identity, verifiedHosts);
				candidates.addAll(discovery.candidates());
				aiStages.add(discovery.trace());
			}
		}
		AirportMapCandidate fallback = identity.maps().matchedAirport() && identity.maps().mapsLocationUrl() != null
				? new AirportMapCandidate("MAPS:LOCATION", identity.maps().mapsLocationUrl(),
						identity.maps().localName(), null, null, null, false, true,
						identity.decision().canonicalAirport().canonicalId(), request.terminal(), "GOOGLE_MAPS") : null;
		AirportMapSelector.Selection selected = mapSelector.select(candidates, fallback);
		MapResource resource;
		List<String> publicReasons = new ArrayList<>();
		if (selected.selectedCandidate() == null) {
			boolean error = stages.stream().anyMatch(StageJson::error)
					|| identity.decision().status() == IdentityStatus.UPSTREAM_ERROR;
			List<String> rejected = selected.invalidCandidates().stream()
					.map(AirportMapSelector.Decision::reason).filter(java.util.Objects::nonNull).distinct().toList();
			resource = emptyMap(error ? MapStatus.TEMPORARILY_UNAVAILABLE : MapStatus.NOT_FOUND, rejected);
			publicReasons.add(error ? "MAP_UPSTREAM_ERROR" : "NO_VERIFIED_AIRPORT_MAP");
		} else {
			AirportMapSelector.Decision decision = selected.selectedCandidate();
			AirportMapCandidate candidate = decision.candidate();
			MapResultType type = decision.classification().resultType();
			MapStatus status = type == MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION
					? MapStatus.AIRPORT_LOCATION : MapStatus.FOUND;
			List<String> rejected = type == MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION
					? selected.invalidCandidates().stream().map(AirportMapSelector.Decision::reason)
							.filter(java.util.Objects::nonNull).distinct().toList() : List.of();
			resource = new MapResource(status, candidate.url(), valueOr(candidate.sourceUrl(), candidate.url()),
					candidate.title(), type, detectLanguage(candidate.title()),
					request.terminal() == null ? "AIRPORT" : "REQUESTED_TERMINAL",
					decision.reason(), rejected, false);
			if (type == MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION) publicReasons.add("OFFICIAL_MAP_NOT_VERIFIED_USING_EXACT_LOCATION");
		}
		return new MapBranch(resource, stages.stream().mapToInt(stage -> stage.requestMade() ? 1 : 0).sum(),
				List.copyOf(publicReasons), mapTraceEntries(selected, started), httpStages(stages),
				aiStages.stream().map(stage -> withSelected(stage,
						selectedAiMapCount(candidates, selected))).toList());
	}

	private AirportContactCandidate mapsContactCandidate(IdentityBundle identity) {
		AirportPhoneCandidate mapsPhone = identity.maps().phoneCandidate();
		if (mapsPhone == null || !mapsPhone.accepted()) return null;
		return new AirportContactCandidate("MAPS:PHONE", identity.decision().canonicalAirport().canonicalId(),
					identity.maps().localName(), PageAudience.UNKNOWN, ContactRole.GENERAL_PASSENGER,
					mapsPhone, null, mapsPhone.sourceUrl(), identity.maps().localName(), "GOOGLE_MAPS",
					List.of(new ResourceEvidence("GOOGLE_MAPS", mapsPhone.sourceUrl(), mapsPhone.sourceUrl(),
							true, false, List.of("STRONG_CANONICAL_MAPS_PLACE"))), IdentityConfidence.VERY_HIGH);
	}

	private AiContactDiscovery parseContactAi(JsonNode root, IdentityBundle identity, Set<String> verifiedHosts) {
		AiResponseCandidateExtractor.Result extraction = aiCandidateExtractor.extract(root);
		JsonNode payload = extraction.strictJsonPayload();
		boolean confirmed = payload != null && payload.path("airportConfirmed").isBoolean()
				&& payload.path("airportConfirmed").asBoolean()
				&& identity.decision().canonicalAirport().iataCode()
						.equalsIgnoreCase(payload.path("airportCode").asText());
		Map<String, Reference> references = references(root);
		Map<String, ContactPage> organic = new LinkedHashMap<>();
		for (ContactPage page : identity.search().contactPages()) organic.put(normalizeUrl(page.url()), page);
		LinkedHashMap<String, AirportContactCandidate> candidates = new LinkedHashMap<>();
		AirportIdentity airport = identity.decision().canonicalAirport();
		int strictCandidateCount = 0;
		boolean hadPhoneCandidates = false;
		for (JsonNode phoneNode : iterable(confirmed ? payload.path("phoneCandidates") : null)) {
			strictCandidateCount++;
			hadPhoneCandidates = true;
			String raw = text(phoneNode, "value");
			String sourceUrl = safeHttps(text(phoneNode, "sourceUrl"));
			ContactRole role = parseRole(text(phoneNode, "role"));
			BoundSource bound = boundSource(sourceUrl, references, organic, verifiedHosts);
			if (raw == null || sourceUrl == null || bound == null || !role.generalPassenger()) continue;
			PageAudience audience = audienceClassifier.classify(sourceUrl, bound.title(), bound.snippet(), true);
			boolean verifiedPassengerPage = audience == PageAudience.PASSENGER_GENERAL;
			PhoneEvidenceTier tier = verifiedPassengerPage ? PhoneEvidenceTier.VERIFIED_PASSENGER_PAGE
					: PhoneEvidenceTier.AI_BOUND_DISCOVERY;
			AirportPhoneCandidate phone = raw.replaceAll("\\D", "").length() <= 6 && !raw.startsWith("+")
					? phoneNormalizer.normalizeShortCode(raw, airport, role, sourceUrl, "AI_MODE", tier,
							verifiedPassengerPage)
					: phoneNormalizer.normalize(raw, airport, role, sourceUrl, "AI_MODE", tier);
			if (!phone.accepted()) continue;
			AirportContactCandidate candidate = new AirportContactCandidate("AI:PHONE:" + candidates.size(), airport.canonicalId(),
					airport.name(), audience, role, phone,
					verifiedPassengerPage ? sourceUrl : null, sourceUrl, bound.title(), "AI_MODE",
					List.of(new ResourceEvidence("AI_MODE", sourceUrl, bound.citationUrl(), true,
							bound.organic(), List.of("CANDIDATE_LEVEL_SOURCE_BOUND"))), IdentityConfidence.MEDIUM,
					"$.strictJson.phoneCandidates[" + (strictCandidateCount - 1) + "]", "STRICT_JSON",
					bound.snippet(), bound.title(), bound.snippet(), bound.citationUrl(),
					List.of("STRICT_JSON_SOURCE_BOUND"));
			putContactCandidate(candidates, candidate);
		}
		int strictPageIndex = 0;
		for (JsonNode pageNode : iterable(confirmed ? payload.path("contactCandidates") : null)) {
			strictCandidateCount++;
			String url = safeHttps(firstNonBlank(text(pageNode, "url"), text(pageNode, "sourceUrl")));
			BoundSource bound = boundSource(url, references, organic, verifiedHosts);
			if (url == null || bound == null) continue;
			PageAudience audience = audienceClassifier.classify(url,
					firstNonBlank(text(pageNode, "title"), bound.title()), bound.snippet(), true);
			AirportContactCandidate candidate = new AirportContactCandidate("AI:CONTACT:" + candidates.size(), airport.canonicalId(),
					airport.name(), audience, parseRole(text(pageNode, "role")), null,
					audience == PageAudience.PASSENGER_GENERAL ? url : null, url,
					firstNonBlank(text(pageNode, "title"), bound.title()), "AI_MODE",
					List.of(new ResourceEvidence("AI_MODE", url, bound.citationUrl(), true,
							bound.organic(), List.of("CANDIDATE_LEVEL_SOURCE_BOUND"))), IdentityConfidence.MEDIUM,
					"$.strictJson.contactCandidates[" + strictPageIndex++ + "]", "STRICT_JSON",
					bound.snippet(), bound.title(), bound.snippet(), bound.citationUrl(),
					List.of("STRICT_JSON_SOURCE_BOUND"));
			putContactCandidate(candidates, candidate);
		}

		for (AiResponseCandidateExtractor.Candidate extracted : extraction.candidates()) {
			boolean official = hostIn(extracted.normalizedUrl(), verifiedHosts);
			AiResponseCandidateExtractor.Evidence evidence = preferredEvidence(extracted);
			String modes = extractionModes(extracted);
			String title = firstNonBlank(evidence.referenceTitle(), evidence.title(), evidence.linkText());
			String context = firstNonBlank(evidence.referenceSnippet(), evidence.context());
			List<String> provenance = new ArrayList<>();
			provenance.add("AI_STRUCTURALLY_BOUND_REFERENCE");
			if (!official) provenance.add("AI_REFERENCE_NOT_OFFICIAL_DOMAIN");
			PageAudience audience = official
					? audienceClassifier.classify(extracted.normalizedUrl(), title, context, true)
					: PageAudience.THIRD_PARTY_DIRECTORY;
			boolean passengerPage = official && audience == PageAudience.PASSENGER_GENERAL
					&& !isHomepage(extracted.normalizedUrl());
			AirportContactCandidate page = new AirportContactCandidate(
					"AI:NATURAL:CONTACT:" + candidates.size(), airport.canonicalId(), airport.name(), audience,
					audience == PageAudience.PASSENGER_GENERAL ? ContactRole.GENERAL_PASSENGER : ContactRole.UNKNOWN,
					null, passengerPage ? extracted.normalizedUrl() : null,
					extracted.normalizedUrl(), title, "AI_MODE",
					List.of(new ResourceEvidence("AI_MODE", extracted.normalizedUrl(), extracted.normalizedUrl(),
							true, false, provenance)), IdentityConfidence.MEDIUM,
					evidence.sourceJsonPath(), modes, context, evidence.referenceTitle(),
					evidence.referenceSnippet(), evidence.bindingPath(), provenance);
			putContactCandidate(candidates, page);
			if (!official) continue;
			for (AiResponseCandidateExtractor.Evidence phoneEvidence : extracted.evidence()) {
				String localTitle = firstNonBlank(phoneEvidence.title(), phoneEvidence.linkText(),
						phoneEvidence.referenceTitle());
				String localContext = firstNonBlank(phoneEvidence.context(), phoneEvidence.referenceSnippet());
				PageAudience localAudience = audienceClassifier.classify(extracted.normalizedUrl(),
						localTitle, localContext, true);
				if (localAudience != PageAudience.PASSENGER_GENERAL) continue;
				for (String raw : contactPhoneExtractor.extract(localTitle, localContext)) {
				hadPhoneCandidates = true;
				ContactRole role = ContactRole.GENERAL_PASSENGER;
				boolean localPassengerPage = !isHomepage(extracted.normalizedUrl());
				PhoneEvidenceTier tier = localPassengerPage ? PhoneEvidenceTier.VERIFIED_PASSENGER_PAGE
						: PhoneEvidenceTier.AI_BOUND_DISCOVERY;
				AirportPhoneCandidate phone = phoneNormalizer.normalize(raw, airport, role,
						extracted.normalizedUrl(), "AI_MODE", tier);
				if (!phone.accepted()) continue;
				AirportContactCandidate phoneCandidate = new AirportContactCandidate(
						"AI:NATURAL:PHONE:" + candidates.size(), airport.canonicalId(), airport.name(), localAudience,
						role, phone, localPassengerPage ? extracted.normalizedUrl() : null,
						extracted.normalizedUrl(), title, "AI_MODE",
						List.of(new ResourceEvidence("AI_MODE", extracted.normalizedUrl(), extracted.normalizedUrl(),
								true, false, List.of("AI_STRUCTURALLY_BOUND_REFERENCE", "ROLE_BOUND_LOCAL_PHONE"))),
						IdentityConfidence.MEDIUM, phoneEvidence.sourceJsonPath(), modes, localContext,
						phoneEvidence.referenceTitle(), phoneEvidence.referenceSnippet(), phoneEvidence.bindingPath(),
						List.of("AI_STRUCTURALLY_BOUND_REFERENCE", "ROLE_BOUND_LOCAL_PHONE"));
				putContactCandidate(candidates, phoneCandidate);
				}
			}
		}
		DecisionTrace.AiStage trace = aiTrace("CONTACT_AI", extraction, strictCandidateCount, 0);
		return new AiContactDiscovery(List.copyOf(candidates.values()),
				confirmed ? text(payload, "localContactSearchQuery") : null,
				confirmed ? text(payload, "sourceLanguage") : null, hadPhoneCandidates, trace);
	}

	private List<AirportContactCandidate> parseContactSearch(JsonNode root, IdentityBundle identity,
			Set<String> verifiedHosts) {
		if (!serpSuccess(root)) return List.of();
		List<AirportContactCandidate> result = new ArrayList<>();
		int index = 0;
		for (JsonNode node : iterable(root.path("organic_results"))) {
			if (index >= 10) break;
			addContactSearchCandidate(result, node, "$.organic_results[" + index++ + "]", identity, verifiedHosts);
		}
		return List.copyOf(result);
	}

	private void addContactSearchCandidate(List<AirportContactCandidate> result, JsonNode node, String path,
			IdentityBundle identity, Set<String> verifiedHosts) {
		String url = safeHttps(firstNonBlank(text(node, "link"), text(node, "url")));
		if (url == null || !hostIn(url, verifiedHosts)) return;
		String title = text(node, "title");
		String snippet = text(node, "snippet");
		PageAudience audience = audienceClassifier.classify(url, title, snippet, true);
		ContactRole role = audience == PageAudience.PASSENGER_GENERAL
				? ContactRole.GENERAL_PASSENGER : ContactRole.UNKNOWN;
		LinkedHashSet<String> rawPhones = new LinkedHashSet<>();
		String structuredPhone = firstNonBlank(text(node, "phone"), text(node, "phone_number"));
		if (structuredPhone != null) rawPhones.add(structuredPhone);
		if (audience == PageAudience.PASSENGER_GENERAL) {
			rawPhones.addAll(contactPhoneExtractor.extract(title, snippet));
		}
		int phoneIndex = 0;
		for (String rawPhone : rawPhones) {
			boolean shortCode = rawPhone.replaceAll("\\D", "").length() <= 6 && !rawPhone.startsWith("+");
			PhoneEvidenceTier tier = audience == PageAudience.PASSENGER_GENERAL
					? shortCode ? PhoneEvidenceTier.VERIFIED_PASSENGER_PAGE
							: PhoneEvidenceTier.VERIFIED_STRUCTURED_ENTITY
					: PhoneEvidenceTier.UNVERIFIED;
			AirportPhoneCandidate phone = shortCode
					? phoneNormalizer.normalizeShortCode(rawPhone, identity.decision().canonicalAirport(), role,
							url, "GOOGLE_SEARCH", tier, audience == PageAudience.PASSENGER_GENERAL)
					: phoneNormalizer.normalize(rawPhone, identity.decision().canonicalAirport(), role,
							url, "GOOGLE_SEARCH", tier);
			if (!phone.accepted()) continue;
			result.add(new AirportContactCandidate("SEARCH:CONTACT:" + path + ":PHONE:" + phoneIndex++,
					identity.decision().canonicalAirport().canonicalId(), identity.decision().canonicalAirport().name(),
					audience, role, phone, audience == PageAudience.PASSENGER_GENERAL ? url : null,
					url, title, "GOOGLE_SEARCH", List.of(new ResourceEvidence("GOOGLE_SEARCH", url, url,
							true, true, List.of(structuredPhone != null && structuredPhone.equals(rawPhone)
									? "STRUCTURED_PHONE_FIELD" : "ROLE_BOUND_ORGANIC_SNIPPET_PHONE"))),
					IdentityConfidence.HIGH, path, "SEARCH_ORGANIC", snippet, null, null, path,
					List.of(structuredPhone != null && structuredPhone.equals(rawPhone)
							? "STRUCTURED_PHONE_FIELD" : "ROLE_BOUND_ORGANIC_SNIPPET_PHONE")));
		}
		result.add(new AirportContactCandidate("SEARCH:CONTACT:" + path,
				identity.decision().canonicalAirport().canonicalId(), identity.decision().canonicalAirport().name(),
				audience, role, null, audience == PageAudience.PASSENGER_GENERAL ? url : null,
				url, title, "GOOGLE_SEARCH", List.of(new ResourceEvidence("GOOGLE_SEARCH", url, url,
						true, true, List.of("ORGANIC_CANDIDATE"))), IdentityConfidence.HIGH,
				path, "SEARCH_ORGANIC", snippet, null, null, path, List.of("ORGANIC_CANDIDATE")));
	}

	private List<AirportMapCandidate> parseMapSearch(JsonNode root, AirportResourcesRequest request,
			IdentityBundle identity, Set<String> verifiedHosts) {
		if (!serpSuccess(root)) return List.of();
		List<AirportMapCandidate> result = new ArrayList<>();
		int organicIndex = 0;
		for (JsonNode organic : iterable(root.path("organic_results"))) {
			if (organicIndex >= 10) break;
			String parentUrl = firstNonBlank(text(organic, "link"), text(organic, "url"));
			addMapCandidate(result, organic, "MAP_SEARCH:ORGANIC:" + organicIndex, parentUrl,
					false, request, identity, verifiedHosts);
			JsonNode sitelinks = organic.path("sitelinks");
			for (String kind : List.of("inline", "expanded", "list")) {
				int linkIndex = 0;
				for (JsonNode link : iterable(sitelinks.path(kind))) {
					addMapCandidate(result, link, "MAP_SEARCH:SITELINK:" + organicIndex + ":" + linkIndex++,
							parentUrl, true, request, identity, verifiedHosts);
				}
			}
			organicIndex++;
		}
		return List.copyOf(result);
	}

	private void addMapCandidate(List<AirportMapCandidate> result, JsonNode node, String id,
			String parentUrl, boolean sitelink, AirportResourcesRequest request,
			IdentityBundle identity, Set<String> verifiedHosts) {
		String url = safeHttps(firstNonBlank(text(node, "link"), text(node, "url")));
		if (url == null) return;
		boolean official = hostIn(url, verifiedHosts);
		boolean chain = official || sitelink && hostIn(parentUrl, verifiedHosts);
		String extractionMode = sitelink ? "SEARCH_SITELINK" : "SEARCH_ORGANIC";
		List<String> provenance = new ArrayList<>();
		provenance.add(extractionMode);
		if (sitelink && hostIn(parentUrl, verifiedHosts) && !official) {
			provenance.add("OFFICIAL_ORGANIC_TO_EXTERNAL_SITELINK_CHAIN");
		}
		result.add(new AirportMapCandidate(id, url, text(node, "title"), text(node, "snippet"),
				text(node, "title"), parentUrl, official, chain,
				identity.decision().canonicalAirport().canonicalId(), request.terminal(), "GOOGLE_SEARCH",
				AiResponseCandidateExtractor.normalizeUrl(url), id.replace(':', '.'), extractionMode,
				text(node, "snippet"), null, null, sitelink ? parentUrl : url,
				targetAirportMatched(identity.decision().canonicalAirport(), text(node, "title"), url),
				List.copyOf(provenance)));
	}

	private AiMapDiscovery parseMapAi(JsonNode root, AirportResourcesRequest request,
			IdentityBundle identity, Set<String> verifiedHosts) {
		AiResponseCandidateExtractor.Result extraction = aiCandidateExtractor.extract(root);
		JsonNode payload = extraction.strictJsonPayload();
		boolean confirmed = payload != null && payload.path("airportConfirmed").isBoolean()
				&& payload.path("airportConfirmed").asBoolean()
				&& identity.decision().canonicalAirport().iataCode()
						.equalsIgnoreCase(payload.path("airportCode").asText());
		Map<String, Reference> references = references(root);
		LinkedHashMap<String, AirportMapCandidate> result = new LinkedHashMap<>();
		int strictCandidateCount = 0;
		for (JsonNode node : iterable(confirmed ? payload.path("mapCandidates") : null)) {
			int index = strictCandidateCount++;
			String url = safeHttps(text(node, "url"));
			String sourceUrl = safeHttps(text(node, "sourceUrl"));
			Reference reference = references.get(normalizeUrl(sourceUrl));
			if (url == null || sourceUrl == null || reference == null) continue;
			boolean official = hostIn(url, verifiedHosts);
			AirportMapCandidate candidate = new AirportMapCandidate("MAP_AI:STRICT:" + index, url, text(node, "title"),
					text(node, "description"), text(node, "linkText"), sourceUrl, official, official,
					identity.decision().canonicalAirport().canonicalId(), request.terminal(), "AI_MODE",
					AiResponseCandidateExtractor.normalizeUrl(url), "$.strictJson.mapCandidates[" + index + "]",
					"STRICT_JSON", text(node, "description"), reference.title(), reference.snippet(),
					sourceUrl, targetAirportMatched(identity.decision().canonicalAirport(), text(node, "title"), url),
					List.of("STRICT_JSON_SOURCE_BOUND"));
			result.putIfAbsent(candidate.normalizedUrl(), candidate);
		}
		for (AiResponseCandidateExtractor.Candidate extracted : extraction.candidates()) {
			AiResponseCandidateExtractor.Evidence evidence = preferredEvidence(extracted);
			boolean official = hostIn(extracted.normalizedUrl(), verifiedHosts);
			List<String> provenance = new ArrayList<>();
			provenance.add("AI_STRUCTURALLY_BOUND_REFERENCE");
			if (!official) provenance.add("AI_REFERENCE_NOT_OFFICIAL_DOMAIN");
			AirportMapCandidate candidate = new AirportMapCandidate("MAP_AI:NATURAL:" + result.size(),
					extracted.normalizedUrl(), firstNonBlank(evidence.referenceTitle(), evidence.title()),
					firstNonBlank(evidence.referenceSnippet(), evidence.context()), evidence.linkText(),
					extracted.rawUrl(), official, official,
					identity.decision().canonicalAirport().canonicalId(), request.terminal(), "AI_MODE",
					extracted.normalizedUrl(), evidence.sourceJsonPath(), extractionModes(extracted),
					evidence.context(), evidence.referenceTitle(), evidence.referenceSnippet(), evidence.bindingPath(),
					targetAirportMatched(identity.decision().canonicalAirport(),
							firstNonBlank(evidence.title(), evidence.referenceTitle()), extracted.normalizedUrl()),
					List.copyOf(provenance));
			result.putIfAbsent(candidate.normalizedUrl(), candidate);
		}
		return new AiMapDiscovery(List.copyOf(result.values()),
				aiTrace("MAP_AI", extraction, strictCandidateCount, 0));
	}

	private StageJson fetchMapsIdentity(AirportResourcesRequest request, AirportIdentity airport,
			String apiKey, RequestBudget budget) {
		return fetch("IDENTITY_MAPS", budget, builder -> builder.path("/search")
				.queryParam("engine", "google_maps").queryParam("type", "search")
				.queryParam("q", airport.searchName() + " " + airport.iataCode())
				.queryParam("ll", String.format(Locale.ROOT, "@%.6f,%.6f,12z", airport.latitude(), airport.longitude()))
				.queryParam("hl", "en").queryParam("no_cache", request.forceRefresh() ? "true" : "false")
				.queryParam("api_key", apiKey.trim()).build());
	}

	private static boolean shouldSkipIdentitySearch(AirportResourcesRequest request,
			SerpApiGoogleMapsAirportPlaceParser.Result maps) {
		if (!maps.matchedAirport()) return false;
		boolean safeVerifiedWebsite = OfficialTravelSourcePolicy.safeUri(maps.websiteUrl()) != null
				&& maps.domainEvidence().stream().anyMatch(DomainEvidence::verified);
		boolean contactOnlyWithPhone = request.requestsContact() && !request.requestsMap()
				&& maps.phoneCandidate() != null && maps.phoneCandidate().accepted();
		return safeVerifiedWebsite || contactOnlyWithPhone;
	}

	private StageJson fetchContactAi(AirportResourcesRequest request, IdentityBundle identity,
			Set<String> verifiedHosts, String apiKey, RequestBudget budget) {
		return fetch("CONTACT_AI", budget, builder -> builder.path("/search")
				.queryParam("engine", "google_ai_mode").queryParam("q", contactPrompt(request, identity, verifiedHosts))
				.queryParam("hl", "en").queryParam("continuable", "false")
				.queryParam("no_cache", request.forceRefresh() ? "true" : "false")
				.queryParam("api_key", apiKey.trim()).build());
	}

	private StageJson fetchContactSearch(AirportResourcesRequest request, IdentityBundle identity,
			Set<String> verifiedHosts, String apiKey, RequestBudget budget, String localQuery) {
		String sites = siteExpression(verifiedHosts);
		String query = valueOr(localQuery, identity.decision().canonicalAirport().searchName()
				+ " general passenger customer service contact phone") + " " + sites;
		return fetch("CONTACT_SEARCH", budget, builder -> builder.path("/search")
				.queryParam("engine", "google").queryParam("q", query).queryParam("hl", "en")
				.queryParam("num", "10").queryParam("no_cache", request.forceRefresh() ? "true" : "false")
				.queryParam("api_key", apiKey.trim()).build());
	}

	private StageJson fetchMapSearch(AirportResourcesRequest request, IdentityBundle identity,
			Set<String> verifiedHosts, String apiKey, RequestBudget budget) {
		String terminal = request.terminal() == null ? "" : " terminal " + request.terminal();
		String mapIntent = String.join(" ", MapIntentLexicon.queryTerms(
				identity.decision().canonicalAirport().isoCountry()));
		String query = identity.decision().canonicalAirport().searchName() + " "
				+ identity.decision().canonicalAirport().iataCode() + terminal
				+ " " + mapIntent + " " + siteExpression(verifiedHosts);
		return fetch("MAP_SEARCH", budget, builder -> builder.path("/search")
				.queryParam("engine", "google").queryParam("q", query).queryParam("hl", "en")
				.queryParam("num", "10").queryParam("no_cache", request.forceRefresh() ? "true" : "false")
				.queryParam("api_key", apiKey.trim()).build());
	}

	private StageJson fetchMapAi(AirportResourcesRequest request, IdentityBundle identity,
			Set<String> verifiedHosts, String apiKey, RequestBudget budget) {
		return fetch("MAP_AI", budget, builder -> builder.path("/search")
				.queryParam("engine", "google_ai_mode").queryParam("q", mapPrompt(request, identity, verifiedHosts))
				.queryParam("hl", "en").queryParam("continuable", "false")
				.queryParam("no_cache", request.forceRefresh() ? "true" : "false")
				.queryParam("api_key", apiKey.trim()).build());
	}

	private StageJson fetch(String stage, RequestBudget budget,
			java.util.function.Function<org.springframework.web.util.UriBuilder, URI> uriBuilder) {
		budget.reserve(stage);
		Instant started = clock.instant();
		try {
			return restClient.get().uri(uriBuilder).exchange((request, response) -> {
				byte[] body = response.getBody().readAllBytes();
				JsonNode root = null;
				String parseError = null;
				try {
					root = body.length == 0 ? null : mapper.readTree(new String(body, StandardCharsets.UTF_8));
				} catch (RuntimeException exception) { parseError = "JSON_PARSE_ERROR:" + exception.getClass().getSimpleName(); }
				int status = response.getStatusCode().value();
				boolean success = status >= 200 && status < 300 && serpSuccess(root);
				String requestId = root == null ? null : text(root.path("search_metadata"), "id");
				if (requestId == null) requestId = response.getHeaders().getFirst("x-request-id");
				DecisionTrace.HttpStage http = new DecisionTrace.HttpStage(stage, status,
						response.getHeaders().getContentType() == null ? null : response.getHeaders().getContentType().toString(),
						requestId, body.length, fingerprint(body), parseError,
						started, clock.instant());
				return new StageJson(root, success, !success, true, List.of(http));
			});
		} catch (Exception exception) {
			DecisionTrace.HttpStage http = new DecisionTrace.HttpStage(stage, null, null, null, 0,
					null, exception.getClass().getName() + ":" + safeError(exception.getMessage()), started, clock.instant());
			return new StageJson(null, false, true, true, List.of(http));
		}
	}

	private String contactPrompt(AirportResourcesRequest request, IdentityBundle identity, Set<String> verifiedHosts) {
		AirportIdentity airport = identity.decision().canonicalAirport();
		return """
				Find general passenger contact resources for this exact canonical airport.
				Canonical airport: %s; IATA: %s; municipality: %s; country: %s.
				Verified official airport/operator domains only: %s.
				Return ONLY one JSON object, without prose or Markdown. Required top-level fields are airportCode as the string "%s", airportConfirmed as a JSON boolean, sourceLanguage, localContactSearchQuery, phoneCandidates, contactCandidates and mapCandidates. Each phone candidate has value, role, scope and sourceUrl. Each contact candidate has url, title, role and sourceUrl.
				Every candidate must have its own exact official sourceUrl. Do not return airline, parking, lost-property, cargo, B2B, IT, media, careers, procurement, investor or subsidiary contacts. Use [] when none.
				""".formatted(airport.name(), airport.iataCode(), valueOr(airport.municipality(), "unknown"),
				airport.countryName(), String.join(", ", verifiedHosts), airport.iataCode());
	}

	private String mapPrompt(AirportResourcesRequest request, IdentityBundle identity, Set<String> verifiedHosts) {
		AirportIdentity airport = identity.decision().canonicalAirport();
		return """
				Find official airport or requested-terminal maps for this exact canonical airport.
				Canonical airport: %s; IATA: %s; municipality: %s; country: %s; terminal: %s.
				Verified official airport/operator domains only: %s.
				Return ONLY one JSON object, without prose or Markdown. Required top-level fields are airportCode as the string "%s", airportConfirmed as a JSON boolean, phoneCandidates, contactCandidates and mapCandidates. Each map candidate has url, title, linkText, description and sourceUrl.
				Every map must have its own exact sourceUrl. Exclude parking, transport, noise, shopping, ordinary guides and unrelated terminal pages. Use [] when none.
				""".formatted(airport.name(), airport.iataCode(), valueOr(airport.municipality(), "unknown"),
				airport.countryName(), valueOr(request.terminal(), "not specified"),
				String.join(", ", verifiedHosts), airport.iataCode());
	}

	private Map<String, Reference> references(JsonNode root) {
		Map<String, Reference> result = new LinkedHashMap<>();
		if (root == null || root.isNull() || root.isMissingNode()) return result;
		for (JsonNode reference : iterable(root.path("references"))) {
			String url = safeHttps(firstNonBlank(text(reference, "link"), text(reference, "url")));
			if (url != null) result.put(normalizeUrl(url), new Reference(url, text(reference, "title"),
					text(reference, "snippet")));
		}
		return result;
	}

	private BoundSource boundSource(String url, Map<String, Reference> references,
			Map<String, ContactPage> organic, Set<String> verifiedHosts) {
		if (url == null || !hostIn(url, verifiedHosts)) return null;
		String normalized = normalizeUrl(url);
		Reference reference = references.get(normalized);
		if (reference != null) return new BoundSource(reference.title(), reference.snippet(), reference.url(), false);
		ContactPage page = organic.get(normalized);
		return page == null ? null : new BoundSource(page.title(), page.snippet(), page.url(), true);
	}

	private static void putContactCandidate(Map<String, AirportContactCandidate> candidates,
			AirportContactCandidate candidate) {
		AirportPhoneCandidate phone = candidate.phoneCandidate();
		String phoneKey = phone == null ? "PAGE" : valueOr(phone.e164(),
				valueOr(phone.nationalNumber(), phone.rawDisplay()));
		String key = normalizeUrl(candidate.sourceUrl()) + "|" + phoneKey;
		candidates.putIfAbsent(key, candidate);
	}

	private static AiResponseCandidateExtractor.Evidence preferredEvidence(
			AiResponseCandidateExtractor.Candidate candidate) {
		return candidate.evidence().stream().max(java.util.Comparator.comparingInt(evidence -> {
			int score = evidence.parseMode() == AiResponseCandidateExtractor.ParseMode.SNIPPET_LINK ? 30
					: evidence.parseMode() == AiResponseCandidateExtractor.ParseMode.REFERENCE ? 20 : 10;
			if (evidence.context() != null) score += 4;
			if (evidence.linkText() != null) score += 2;
			if (evidence.referenceTitle() != null) score += 1;
			return score;
		})).orElseThrow();
	}

	private static String extractionModes(AiResponseCandidateExtractor.Candidate candidate) {
		return candidate.evidence().stream().map(evidence -> evidence.parseMode().name())
				.distinct().reduce((left, right) -> left + "+" + right).orElse(null);
	}

	private static DecisionTrace.AiStage aiTrace(String stage,
			AiResponseCandidateExtractor.Result extraction, int strictCandidates, int selectedCandidates) {
		return new DecisionTrace.AiStage(stage, extraction.responseMode(), extraction.jsonParseAttempted(),
				extraction.jsonParsed(), extraction.jsonParseFailure(), strictCandidates,
				extraction.count(AiResponseCandidateExtractor.ParseMode.REFERENCE),
				extraction.count(AiResponseCandidateExtractor.ParseMode.SNIPPET_LINK),
				extraction.count(AiResponseCandidateExtractor.ParseMode.MARKDOWN_LINK),
				extraction.candidates().size(), selectedCandidates, extraction.diagnostics());
	}

	private static DecisionTrace.AiStage withSelected(DecisionTrace.AiStage stage, int selectedCandidates) {
		return new DecisionTrace.AiStage(stage.stage(), stage.responseMode(), stage.jsonParseAttempted(),
				stage.jsonParsed(), stage.jsonParseFailure(), stage.strictJsonCandidates(),
				stage.referenceCandidates(), stage.snippetLinkCandidates(), stage.markdownCandidates(),
				stage.uniqueUrlCandidates(), selectedCandidates, stage.diagnostics());
	}

	private static int selectedAiContactCount(List<AirportContactCandidate> candidates,
			AirportContactSelector.Selection selected) {
		int result = 0;
		for (AirportContactCandidate candidate : candidates) {
			if (!"AI_MODE".equals(candidate.sourceType())) continue;
			if (candidate.phoneCandidate() != null && selected.phone() == candidate.phoneCandidate()) result++;
			if (selected.contact() == candidate) result++;
		}
		return result;
	}

	private static int selectedAiMapCount(List<AirportMapCandidate> candidates,
			AirportMapSelector.Selection selected) {
		if (selected.selectedCandidate() == null) return 0;
		AirportMapCandidate selectedCandidate = selected.selectedCandidate().candidate();
		return "AI_MODE".equals(selectedCandidate.sourceType()) && candidates.contains(selectedCandidate) ? 1 : 0;
	}

	private static boolean targetAirportMatched(AirportIdentity airport, String title, String url) {
		String evidence = valueOr(title, "") + " " + valueOr(url, "");
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("(?<![A-Z0-9])([A-Z]{3})(?![A-Z0-9])").matcher(evidence);
		Set<String> generic = Set.of("PDF", "MAP", "WWW", "COM", "ORG", "NET", "THE", "AND", "FOR", "INT");
		while (matcher.find()) {
			String code = matcher.group(1);
			if (!generic.contains(code) && !airport.iataCode().equals(code)) return false;
		}
		return true;
	}

	private static boolean isHomepage(String url) {
		URI uri = OfficialTravelSourcePolicy.safeUri(url);
		if (uri == null) return false;
		String path = uri.getPath();
		return path == null || path.isBlank() || "/".equals(path)
				|| path.matches("(?i)^/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:index\\.html?)?/?$");
	}

	private static List<DecisionTrace.Entry> identityTraceEntries(IdentityBundle identity,
			boolean requestNameConflict, Instant started) {
		List<DecisionTrace.Entry> result = new ArrayList<>();
		Instant completed = Instant.now();
		if (requestNameConflict) result.add(new DecisionTrace.Entry("INPUT:AIRPORT_NAME", "INPUT",
				null, "USER_INPUT", null, null, null, null,
				identity.decision().canonicalAirport().canonicalId(), List.of(), null, null, null,
				"AIRPORT_NAME_ALIAS", IdentityConfidence.NONE, AirportIdentityCandidate.Decision.REJECTED,
				List.of("REQUEST_NAME_CONFLICTS_WITH_CANONICAL_IATA_ENTITY"), List.of(), started, completed));
		if (identity.searchSkippedStrongMaps()) {
			result.add(new DecisionTrace.Entry("IDENTITY_SEARCH_SKIPPED_STRONG_MAPS", "IDENTITY",
					null, "ORCHESTRATION", null, null, null, null,
					identity.decision().canonicalAirport().canonicalId(),
					List.of("STRONG_MAPS_CANONICAL_AIRPORT"), null, null, null,
					"AIRPORT_IDENTITY_SEARCH", identity.decision().confidence(),
					AirportIdentityCandidate.Decision.VALID_NOT_SELECTED,
					List.of("IDENTITY_SEARCH_SKIPPED_STRONG_MAPS"), List.of(), started, completed));
		}
		for (AirportIdentityCandidate candidate : identity.decision().candidates()) {
			result.add(new DecisionTrace.Entry(candidate.candidateId(), "IDENTITY", candidate.sourceJsonPath(),
					candidate.source(), candidate.websiteUrl(), candidate.rawPhone(),
					candidate.websiteHost(), null, identity.decision().canonicalAirport().canonicalId(),
					candidate.matchedSignals(), authorityFor(candidate, identity.decision()), null, null,
					"AIRPORT_IDENTITY", candidate.confidence(), candidate.decision(),
					candidate.reason() == null ? List.of() : List.of(candidate.reason()),
					candidate.decision() == AirportIdentityCandidate.Decision.ACCEPTED
							? List.of("airportIdentity") : List.of(), started, completed));
		}
		return List.copyOf(result);
	}

	private static List<DecisionTrace.Entry> contactTraceEntries(List<AirportContactCandidate> candidates,
			AirportContactSelector.Selection selected, Instant started) {
		List<DecisionTrace.Entry> result = new ArrayList<>();
		Instant completed = Instant.now();
		for (AirportContactCandidate candidate : candidates) {
			boolean phoneSelected = candidate.phoneCandidate() != null && selected.phone() == candidate.phoneCandidate();
			boolean pageSelected = selected.contact() == candidate;
			boolean valid = candidate.audience() != PageAudience.CORPORATE_SPECIALIZED
					&& candidate.audience() != PageAudience.THIRD_PARTY_DIRECTORY
					&& candidate.audience() != PageAudience.PASSENGER_SPECIALIZED;
			AirportIdentityCandidate.Decision decision = phoneSelected || pageSelected
					? AirportIdentityCandidate.Decision.ACCEPTED
					: valid ? AirportIdentityCandidate.Decision.VALID_NOT_SELECTED
					: AirportIdentityCandidate.Decision.REJECTED;
			List<String> reasons = valid ? List.of() : List.of("PAGE_AUDIENCE_" + candidate.audience());
			List<String> fields = new ArrayList<>();
			if (phoneSelected) fields.add("contact.phone");
			if (pageSelected) fields.add("contact.contactUrl");
			AirportPhoneCandidate phone = candidate.phoneCandidate();
			result.add(new DecisionTrace.Entry(candidate.candidateId(), "CONTACT", candidate.sourceJsonPath(),
					candidate.sourceType(), candidate.sourceUrl(), phone == null ? null : phone.rawDisplay(),
					normalizeUrl(candidate.sourceUrl()), phone == null ? null : valueOr(phone.e164(), phone.nationalNumber()),
					candidate.airportId(), List.of(), null, candidate.organization(), candidate.audience(),
					candidate.role().name(), candidate.confidence(), decision, reasons, List.copyOf(fields),
					started, completed, candidate.extractionMode(), candidate.context(), candidate.referenceTitle(),
					candidate.referenceSnippet(), candidate.structuralBindingPath(), candidate.provenanceSignals()));
		}
		return List.copyOf(result);
	}

	private static List<DecisionTrace.Entry> mapTraceEntries(AirportMapSelector.Selection selection, Instant started) {
		List<DecisionTrace.Entry> result = new ArrayList<>();
		Instant completed = Instant.now();
		if (selection.selectedCandidate() != null) result.add(mapTraceEntry(selection.selectedCandidate(), started, completed));
		for (AirportMapSelector.Decision decision : selection.validAlternatives()) result.add(mapTraceEntry(decision, started, completed));
		for (AirportMapSelector.Decision decision : selection.invalidCandidates()) result.add(mapTraceEntry(decision, started, completed));
		return List.copyOf(result);
	}

	private static DecisionTrace.Entry mapTraceEntry(AirportMapSelector.Decision decision,
			Instant started, Instant completed) {
		AirportMapCandidate candidate = decision.candidate();
		List<String> reasons = new ArrayList<>();
		if (decision.reason() != null) reasons.add(decision.reason());
		reasons.addAll(decision.classification().positiveEvidence());
		reasons.addAll(decision.classification().incidentalEvidence());
		reasons.addAll(candidate.provenanceSignals());
		return new DecisionTrace.Entry(candidate.candidateId(), "MAP", candidate.sourceJsonPath(), candidate.sourceType(),
				candidate.url(), null, candidate.normalizedUrl(), null, candidate.airportId(), List.of(), null,
				null, null, decision.classification().resultType().name(), IdentityConfidence.HIGH,
				decision.decision(), List.copyOf(new LinkedHashSet<>(reasons)),
				decision.decision() == AirportIdentityCandidate.Decision.ACCEPTED ? List.of("map.url") : List.of(),
				started, completed, candidate.extractionMode(), candidate.context(), candidate.referenceTitle(),
				candidate.referenceSnippet(), candidate.structuralBindingPath(), candidate.provenanceSignals());
	}

	private DecisionTrace trace(AirportResourcesRequest request, AirportIdentity airport,
			IdentityStatus status, Instant started, List<DecisionTrace.Entry> entries,
			List<DecisionTrace.HttpStage> stages) {
		return trace(request, airport, status, started, entries, stages, List.of());
	}

	private DecisionTrace trace(AirportResourcesRequest request, AirportIdentity airport,
			IdentityStatus status, Instant started, List<DecisionTrace.Entry> entries,
			List<DecisionTrace.HttpStage> stages, List<DecisionTrace.AiStage> aiStages) {
		return new DecisionTrace(request.airportCode(), airport == null ? null : airport.canonicalId(),
				catalog.datasetVersion(), AirportIdentityMatcher.VERIFIER_VERSION, status,
				started, clock.instant(), entries, stages, aiStages);
	}

	private AirportResourcesResponse unavailable(AirportResourcesRequest request, AirportIdentity airport,
			IdentityStatus status, DecisionTrace trace) {
		boolean upstream = status == IdentityStatus.UPSTREAM_ERROR;
		return new AirportResourcesResponse(request.airportCode(),
				airport == null ? valueOr(request.airportName(), request.airportCode()) : airport.name(),
				request.terminal(), airport == null ? null : airport.name(),
				airport == null ? null : airport.countryName(),
				upstream ? MapResource.temporarilyUnavailable() : MapResource.notFound(),
				upstream ? ContactResource.temporarilyUnavailable() : ContactResource.notFound(),
				0, List.of(upstream ? "IDENTITY_UPSTREAM_ERROR" : "CANONICAL_AIRPORT_NOT_VERIFIED"),
				clock.instant(), false, status.name(), trace);
	}

	private ContactResource emptyContact(ContactStatus status, IdentityBundle identity) {
		return new ContactResource(status, null, null, null, null, null, null, null, null, null,
				identity.decision().status() == IdentityStatus.VERIFIED, false,
				identity.maps().structuredPhonePresent(), false, false,
				identity.maps().structuredPhonePresent(), identity.maps().acceptedStructuredPhonePresent(), List.of());
	}

	private static MapResource emptyMap(MapStatus status, List<String> rejected) {
		return new MapResource(status, null, null, null,
				status == MapStatus.TEMPORARILY_UNAVAILABLE ? MapResultType.TEMPORARILY_UNAVAILABLE : MapResultType.NOT_FOUND,
				null, null, null, rejected, false);
	}

	private static DomainAuthority authorityFor(AirportIdentityCandidate candidate, IdentityDecision decision) {
		if (candidate.websiteHost() == null) return null;
		return decision.domains().stream().filter(domain -> domain.host().equals(candidate.websiteHost()))
				.map(DomainEvidence::authority).filter(authority -> authority != DomainAuthority.REJECTED)
				.findFirst().orElse(DomainAuthority.REJECTED);
	}

	private static Set<String> verifiedHosts(IdentityDecision decision) {
		return decision.verifiedDomains().stream().map(DomainEvidence::host)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

	private static boolean hasUsableContact(List<AirportContactCandidate> candidates) {
		return candidates.stream().anyMatch(candidate -> candidate.phoneCandidate() != null
				&& candidate.phoneCandidate().accepted() || candidate.hasPassengerPage());
	}

	private static boolean hostIn(String url, Set<String> hosts) {
		if (url == null) return false;
		try {
			String host = URI.create(url).getHost();
			if (host == null) return false;
			host = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
			for (String candidate : hosts) {
				String domain = candidate.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
				if (host.equals(domain) || host.endsWith("." + domain)) return true;
			}
			return false;
		} catch (IllegalArgumentException exception) { return false; }
	}

	private static String siteExpression(Set<String> hosts) {
		return "(" + hosts.stream().sorted().map(host -> "site:" + host)
				.reduce((left, right) -> left + " OR " + right).orElse("") + ")";
	}

	private static ContactRole parseRole(String value) {
		String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_");
		if (normalized.contains("SWITCHBOARD")) return ContactRole.AIRPORT_SWITCHBOARD;
		if (normalized.contains("INFORMATION")) return ContactRole.PASSENGER_INFORMATION;
		if (normalized.contains("GENERAL") || normalized.contains("PASSENGER") || normalized.contains("CUSTOMER")) {
			return ContactRole.GENERAL_PASSENGER;
		}
		return ContactRole.UNKNOWN;
	}

	private static String safeHttps(String value) {
		URI uri = OfficialTravelSourcePolicy.safeUri(value);
		return uri == null ? null : uri.toString();
	}

	private static String normalizeUrl(String value) {
		if (value == null) return "";
		try {
			URI uri = URI.create(value).normalize();
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
			String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath().replaceFirst("/+$", "");
			return host + path + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
		} catch (IllegalArgumentException exception) { return ""; }
	}

	private static boolean serpSuccess(JsonNode root) {
		return root != null && "Success".equalsIgnoreCase(root.path("search_metadata").path("status").asText());
	}

	private static Iterable<JsonNode> iterable(JsonNode node) {
		return node != null && node.isArray() ? node : List.of();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.path(field);
		return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) if (value != null && !value.isBlank()) return value;
		return null;
	}

	private static String valueOr(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static String detectLanguage(String value) {
		if (value == null || value.isBlank()) return null;
		if (value.matches(".*[\\u4E00-\\u9FFF].*")) return "zh";
		if (value.matches(".*[\\u3040-\\u30FF].*")) return "ja";
		if (value.matches(".*[\\uAC00-\\uD7AF].*")) return "ko";
		return "und";
	}

	private static String fingerprint(byte[] body) {
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		} catch (Exception exception) { return null; }
	}

	private static String safeError(String value) {
		if (value == null) return "";
		return value.replaceAll("(?i)api[_-]?key[^&\\s]*", "api_key=[REDACTED]")
				.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
	}

	private static List<DecisionTrace.HttpStage> httpStages(List<StageJson> stages) {
		return stages.stream().flatMap(stage -> stage.httpStages().stream()).toList();
	}

	private ResourceCacheKey cacheKey(AirportResourcesRequest request) {
		return new ResourceCacheKey(request.airportCode(), catalog.datasetVersion(),
				AirportIdentityMatcher.VERIFIER_VERSION, normalizeTerminal(request.terminal()),
				request.refreshTarget().name());
	}

	private static String normalizeTerminal(String value) {
		if (value == null) return "";
		return value.toUpperCase(Locale.ROOT).replaceFirst("^TERMINAL\\s*", "").replaceAll("[^A-Z0-9]", "");
	}

	private static Duration ttl(String identityStatus) {
		if (IdentityStatus.VERIFIED.name().equals(identityStatus)) return VERIFIED_TTL;
		if (IdentityStatus.UPSTREAM_ERROR.name().equals(identityStatus)) return ERROR_TTL;
		return UNVERIFIED_TTL;
	}

	private void logResult(AirportResourcesResponse response, Instant started) {
		LOGGER.info("airportResources code={} identity={} map={} contact={} requests={} cached={} elapsedMs={}",
				response.airportCode(), response.identityStatus(), response.map().status(), response.contact().status(),
				response.serpApiRequestCount(), response.fromCache(),
				Duration.between(started, clock.instant()).toMillis());
	}

	AirportResourcesResponse findContactLiveProbe(AirportResourcesRequest request) {
		return findResources(new AirportResourcesRequest(request.airportCode(), request.airportName(), request.terminal(),
				request.forceRefresh(), AirportResourcesRequest.RefreshTarget.CONTACT));
	}

	AirportResourcesResponse findMapLiveProbe(AirportResourcesRequest request) {
		return findResources(new AirportResourcesRequest(request.airportCode(), request.airportName(), request.terminal(),
				request.forceRefresh(), AirportResourcesRequest.RefreshTarget.MAP));
	}

	AirportResourcesResponse retryContactAiLiveStage(AirportResourcesRequest request, Set<String> ignoredDomains,
			String ignoredLocalName, String ignoredCountry) { return findContactLiveProbe(request); }
	AirportResourcesResponse retryMapSearchLiveStage(AirportResourcesRequest request, Set<String> ignoredDomains,
			String ignoredLocalName, String ignoredCountry) { return findMapLiveProbe(request); }
	AirportResourcesResponse retryMapLocationLiveStage(AirportResourcesRequest request, Set<String> ignoredDomains) {
		return findMapLiveProbe(request);
	}

	AirportResourcesResponse parseSavedFixture(JsonNode root, AirportResourcesRequest request, Set<String> ignoredDomains) {
		AirportIdentity airport = catalog.find(request.airportCode()).orElse(null);
		if (airport == null) return unavailable(request, null, IdentityStatus.UNVERIFIED, null);
		Instant started = clock.instant();
		JsonNode mapsRoot = savedStage(root, "IDENTITY_GOOGLE_MAPS");
		SerpApiGoogleMapsAirportPlaceParser.Result maps = mapsParser.parse(mapsRoot, airport);
		boolean searchSkippedStrongMaps = shouldSkipIdentitySearch(request, maps);
		JsonNode searchRoot = savedStage(root, "IDENTITY_OFFICIAL_DOMAIN");
		Resolution search = searchSkippedStrongMaps ? Resolution.notFound()
				: domainResolver.parse(searchRoot, airport, request.airportName());
		IdentityDecision identityDecision = identityReconciler.reconcile(airport, search.domainEvidence(),
				search.identityCandidates(), maps,
				!searchSkippedStrongMaps && search.status() == ResolutionStatus.ERROR, !serpSuccess(mapsRoot));
		IdentityBundle identity = new IdentityBundle(search,
				new StageJson(mapsRoot, serpSuccess(mapsRoot), !serpSuccess(mapsRoot), false, List.of()),
				maps, identityDecision, 0, searchSkippedStrongMaps);

		Set<String> verifiedHosts = verifiedHosts(identityDecision);
		List<AirportContactCandidate> contactCandidates = new ArrayList<>();
		List<DecisionTrace.AiStage> aiStages = new ArrayList<>();
		if (identityDecision.status() == IdentityStatus.VERIFIED) {
			AirportContactCandidate mapsCandidate = mapsContactCandidate(identity);
			if (mapsCandidate != null) {
				contactCandidates.add(mapsCandidate);
			} else {
				AiContactDiscovery ai = parseContactAi(savedStage(root, "CONTACT_AI_MODE"), identity, verifiedHosts);
				contactCandidates.addAll(ai.candidates());
				aiStages.add(ai.trace());
				if (!hasUsableContact(contactCandidates)) {
					contactCandidates.addAll(parseContactSearch(
							savedStage(root, "CONTACT_SUPPLEMENTAL_SEARCH"), identity, verifiedHosts));
				}
			}
		}
		AirportContactSelector.Selection contactSelection = contactSelector.select(contactCandidates);
		ContactStatus contactStatus = contactSelection.phone() != null ? ContactStatus.PHONE_FOUND
				: contactSelection.contact() != null ? ContactStatus.CONTACT_PAGE_ONLY : ContactStatus.NOT_FOUND;
		AirportPhoneCandidate selectedPhone = contactSelection.phone();
		AirportContactCandidate selectedPage = contactSelection.contact();
		ContactResource contact = new ContactResource(contactStatus,
				selectedPhone == null ? null : selectedPhone.passengerDisplay(),
				selectedPhone == null ? null : selectedPhone.telUri(),
				selectedPage == null ? null : selectedPage.contactUrl(),
				selectedPhone != null ? selectedPhone.sourceUrl() : selectedPage == null ? null : selectedPage.sourceUrl(),
				selectedPage != null ? selectedPage.sourceTitle() : maps.localName(), null,
				selectedPhone != null ? selectedPhone.sourceType() : selectedPage == null ? null : selectedPage.sourceType(),
				selectedPhone != null ? selectedPhone.role().name() : selectedPage == null ? null : selectedPage.role().name(),
				selectedPhone == null ? null : selectedPhone.evidenceTier().name(),
				identityDecision.status() == IdentityStatus.VERIFIED, false, maps.structuredPhonePresent(),
				contactSelection.conflict(), false, maps.structuredPhonePresent(),
				maps.acceptedStructuredPhonePresent(), List.of());

		List<AirportMapCandidate> mapCandidates = new ArrayList<>();
		if (!verifiedHosts.isEmpty()) {
			mapCandidates.addAll(parseMapSearch(savedStage(root, "MAP_OFFICIAL_SEARCH"), request,
					identity, verifiedHosts));
			if (mapSelector.select(mapCandidates, null).selectedCandidate() == null) {
				AiMapDiscovery ai = parseMapAi(savedStage(root, "MAP_AI_MODE"), request, identity, verifiedHosts);
				mapCandidates.addAll(ai.candidates());
				aiStages.add(ai.trace());
			}
		}
		AirportMapCandidate fallback = maps.matchedAirport() && maps.mapsLocationUrl() != null
				? new AirportMapCandidate("MAPS:LOCATION", maps.mapsLocationUrl(), maps.localName(), null, null,
					null, false, true, airport.canonicalId(), request.terminal(), "GOOGLE_MAPS") : null;
		AirportMapSelector.Selection mapSelection = mapSelector.select(mapCandidates, fallback);
		MapResource map;
		if (mapSelection.selectedCandidate() == null) {
			map = emptyMap(MapStatus.NOT_FOUND, mapSelection.invalidCandidates().stream()
					.map(AirportMapSelector.Decision::reason).filter(java.util.Objects::nonNull).distinct().toList());
		} else {
			AirportMapSelector.Decision decision = mapSelection.selectedCandidate();
			AirportMapCandidate candidate = decision.candidate();
			MapResultType type = decision.classification().resultType();
			map = new MapResource(type == MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION
					? MapStatus.AIRPORT_LOCATION : MapStatus.FOUND,
					candidate.url(), valueOr(candidate.sourceUrl(), candidate.url()), candidate.title(), type,
					detectLanguage(candidate.title()), request.terminal() == null ? "AIRPORT" : "REQUESTED_TERMINAL",
					decision.reason(), List.of(), false);
		}

		List<DecisionTrace.Entry> entries = new ArrayList<>(identityTraceEntries(identity, false, started));
		entries.addAll(contactTraceEntries(contactCandidates, contactSelection, started));
		entries.addAll(mapTraceEntries(mapSelection, started));
		LinkedHashSet<String> publicReasons = new LinkedHashSet<>();
		if (contactStatus == ContactStatus.NOT_FOUND) publicReasons.add("NO_VERIFIED_PASSENGER_CONTACT");
		if (map.status() == MapStatus.NOT_FOUND) publicReasons.add("NO_VERIFIED_AIRPORT_MAP");
		if (map.status() == MapStatus.AIRPORT_LOCATION) {
			publicReasons.add("OFFICIAL_MAP_NOT_VERIFIED_USING_EXACT_LOCATION");
		}
		int recordedRequests = root == null ? 0 : root.path("observedHttpRequestCount").asInt(0);
		List<DecisionTrace.AiStage> completedAiStages = aiStages.stream().map(stage -> {
			int selectedCount = "CONTACT_AI".equals(stage.stage())
					? selectedAiContactCount(contactCandidates, contactSelection)
					: selectedAiMapCount(mapCandidates, mapSelection);
			return withSelected(stage, selectedCount);
		}).toList();
		return new AirportResourcesResponse(request.airportCode(), airport.name(), request.terminal(),
				valueOr(maps.localName(), airport.name()), airport.countryName(), map, contact, recordedRequests,
				List.copyOf(publicReasons), clock.instant(), false, identityDecision.status().name(),
				trace(request, airport, identityDecision.status(), started, entries, List.of(), completedAiStages));
	}

	private static JsonNode savedStage(JsonNode envelope, String name) {
		if (envelope == null) return null;
		for (JsonNode stage : iterable(envelope.path("stages"))) {
			if (name.equals(stage.path("stage").asText())) return stage.path("sanitizedResponse");
		}
		return null;
	}

	private record IdentityBundle(Resolution search, StageJson mapsStage,
			SerpApiGoogleMapsAirportPlaceParser.Result maps, IdentityDecision decision, int requestCount,
			boolean searchSkippedStrongMaps) { }
	private record StageJson(JsonNode root, boolean success, boolean error, boolean requestMade,
			List<DecisionTrace.HttpStage> httpStages) {
		StageJson { httpStages = httpStages == null ? List.of() : List.copyOf(httpStages); }
	}
	private record ContactBranch(ContactResource resource, int requestCount, List<String> publicReasons,
			List<DecisionTrace.Entry> traceEntries, List<DecisionTrace.HttpStage> httpStages,
			List<DecisionTrace.AiStage> aiStages) {
		ContactBranch {
			publicReasons = publicReasons == null ? List.of() : List.copyOf(publicReasons);
			traceEntries = traceEntries == null ? List.of() : List.copyOf(traceEntries);
			httpStages = httpStages == null ? List.of() : List.copyOf(httpStages);
			aiStages = aiStages == null ? List.of() : List.copyOf(aiStages);
		}
		static ContactBranch notRequested() {
			return new ContactBranch(ContactResource.notFound(), 0, List.of(), List.of(), List.of(), List.of());
		}
	}
	private record MapBranch(MapResource resource, int requestCount, List<String> publicReasons,
			List<DecisionTrace.Entry> traceEntries, List<DecisionTrace.HttpStage> httpStages,
			List<DecisionTrace.AiStage> aiStages) {
		MapBranch {
			publicReasons = publicReasons == null ? List.of() : List.copyOf(publicReasons);
			traceEntries = traceEntries == null ? List.of() : List.copyOf(traceEntries);
			httpStages = httpStages == null ? List.of() : List.copyOf(httpStages);
			aiStages = aiStages == null ? List.of() : List.copyOf(aiStages);
		}
		static MapBranch notRequested() { return new MapBranch(MapResource.notFound(), 0, List.of(), List.of(), List.of(), List.of()); }
	}
	private record AiContactDiscovery(List<AirportContactCandidate> candidates, String localSearchQuery,
			String sourceLanguage, boolean hadPhoneCandidates, DecisionTrace.AiStage trace) {
		AiContactDiscovery { candidates = candidates == null ? List.of() : List.copyOf(candidates); }
		static AiContactDiscovery empty(DecisionTrace.AiStage trace) {
			return new AiContactDiscovery(List.of(), null, null, false, trace);
		}
	}
	private record AiMapDiscovery(List<AirportMapCandidate> candidates, DecisionTrace.AiStage trace) {
		AiMapDiscovery { candidates = candidates == null ? List.of() : List.copyOf(candidates); }
	}
	private record Reference(String url, String title, String snippet) { }
	private record BoundSource(String title, String snippet, String citationUrl, boolean organic) { }
	private record ResourceCacheKey(String iataCode, String datasetVersion, String verifierVersion,
			String terminal, String target) { }
	private record FlightKey(ResourceCacheKey key, boolean forceRefresh) { }
	private record CachedResponse(AirportResourcesResponse response, Instant expiresAt) { }

	private static final class RequestBudget {
		private final int maximum;
		private final AtomicInteger used = new AtomicInteger();
		private RequestBudget(int maximum) { this.maximum = maximum; }
		private void reserve(String stage) {
			int count = used.incrementAndGet();
			if (count > maximum) throw new IllegalStateException("SerpApi request budget exceeded at " + stage);
		}
	}
}
