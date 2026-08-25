package com.selene.onestepflight.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.ProviderFlightResult;

class MultiSourceFlightRuntimeTest {
	private static final LocalDate DATE = LocalDate.of(2026, 8, 24);

	@Test
	void combinesConcurrentLookupsCachesForFiveMinutesAndRunsEveryForcedRefresh() {
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			CountingProvider aero = new CountingProvider("AERODATABOX", 100, success("AERODATABOX", "LIVE"));
			CountingProvider serp = new CountingProvider("SERPAPI", 20,
					ProviderFlightResult.failure("SERPAPI", "NOT_FOUND", "SERPAPI_NO_RESULT", Instant.now()));
			MultiSourceFlightService service = service(List.of(aero, serp), executor);

			CompletableFuture<MultiSourceFlightSnapshot> first = CompletableFuture.supplyAsync(
					() -> service.findFlight("BA117", DATE, null, null, false), executor);
			CompletableFuture<MultiSourceFlightSnapshot> second = CompletableFuture.supplyAsync(
					() -> service.findFlight("BA117", DATE, null, null, false), executor);
			CompletableFuture.allOf(first, second).join();
			assertEquals(1, aero.calls.get(), "concurrent identical lookups should share the in-flight query");
			assertEquals(1, serp.calls.get());

			service.findFlight("BA117", DATE, "LHR", "JFK", false);
			assertEquals(2, aero.calls.get(), "different route hints must not share an aggregate cache key");
			assertEquals(2, serp.calls.get());

			service.findFlight("BA117", DATE, null, null, false);
			assertEquals(2, aero.calls.get(), "normal lookup should use the five-minute aggregate cache");

			service.findFlight("BA117", DATE, null, null, true);
			service.findFlight("BA117", DATE, null, null, true);
			assertEquals(4, aero.calls.get(), "each sequential forced refresh should query providers");
			assertEquals(4, serp.calls.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void returnsSerpPartialResultWhenAeroDataBoxTimesOut() {
		ExecutorService executor = Executors.newFixedThreadPool(3);
		try {
			CountingProvider slowAero = new CountingProvider("AERODATABOX", 500, success("AERODATABOX", "LIVE"));
			CountingProvider serp = new CountingProvider("SERPAPI", 0, success("SERPAPI", "SCHEDULE"));
			MultiSourceFlightService service = new MultiSourceFlightService(List.of(slowAero, serp),
					Duration.ofMinutes(5), Duration.ofMillis(350),
					Duration.ofMillis(50), Duration.ofMillis(200), executor);

			long started = System.nanoTime();
			MultiSourceFlightSnapshot snapshot = service.findFlight("BA117", DATE, "LHR", "JFK", false);
			long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
			assertTrue(elapsedMillis < 350, "aggregate should not wait for the slow provider");
			assertEquals("TIMEOUT", snapshot.providerResults().get("AERODATABOX").outcome());
			assertEquals("AERODATABOX_TIMEOUT", snapshot.providerResults().get("AERODATABOX").errorCode());
			assertEquals("SUCCESS", snapshot.providerResults().get("SERPAPI").outcome());
			assertEquals("LHR", snapshot.flight().departureAirport());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void returnsAeroDataBoxPartialResultWithoutWaitingForSlowSerpApi() {
		ExecutorService executor = Executors.newFixedThreadPool(3);
		try {
			CountingProvider aero = new CountingProvider("AERODATABOX", 20, success("AERODATABOX", "LIVE"));
			CountingProvider slowSerp = new CountingProvider("SERPAPI", 2_000, success("SERPAPI", "SCHEDULE"));
			MultiSourceFlightService service = service(List.of(aero, slowSerp), executor);

			long started = System.nanoTime();
			MultiSourceFlightSnapshot snapshot = service.findFlight("BA117", DATE, null, null, false);
			long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

			assertTrue(elapsedMillis < 1_000, "sufficient AeroDataBox data should not wait for SerpApi timeout");
			assertEquals("SUCCESS", snapshot.providerResults().get("AERODATABOX").outcome());
			assertEquals("PENDING", snapshot.providerResults().get("SERPAPI").outcome());
			assertEquals("SERPAPI_PENDING", snapshot.providerResults().get("SERPAPI").errorCode());
			assertEquals("LHR", snapshot.flight().departureAirport());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void returnsFastSerpApiResultWithoutWaitingForSlowAeroDataBox() {
		ExecutorService executor = Executors.newFixedThreadPool(3);
		try {
			CountingProvider slowAero = new CountingProvider("AERODATABOX", 2_000,
					success("AERODATABOX", "LIVE"));
			CountingProvider serp = new CountingProvider("SERPAPI", 20, success("SERPAPI", "SCHEDULE"));
			MultiSourceFlightService service = service(List.of(slowAero, serp), executor);

			long started = System.nanoTime();
			MultiSourceFlightSnapshot snapshot = service.findFlight("BA117", DATE, null, null, false);
			long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

			assertTrue(elapsedMillis < 1_000, "sufficient SerpApi data should not wait for AeroDataBox timeout");
			assertEquals("PENDING", snapshot.providerResults().get("AERODATABOX").outcome());
			assertEquals("AERODATABOX_PENDING", snapshot.providerResults().get("AERODATABOX").errorCode());
			assertEquals("SUCCESS", snapshot.providerResults().get("SERPAPI").outcome());
			assertEquals("LHR", snapshot.flight().departureAirport());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void retriesSerpApiWithRouteHintsWhenAeroDataBoxFinishesAfterTheInitialStagger() {
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			CountingProvider aero = new CountingProvider("AERODATABOX", 300,
					success("AERODATABOX", "LIVE"));
			RouteDependentProvider serp = new RouteDependentProvider();
			MultiSourceFlightService service = service(List.of(aero, serp), executor);

			MultiSourceFlightSnapshot snapshot = service.findFlight("BA117", DATE, null, null, false);

			assertEquals(2, serp.calls.get());
			assertEquals("LHR/JFK", serp.lastRoute.get());
			assertEquals("SUCCESS", snapshot.providerResults().get("SERPAPI").outcome());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void neverCombinesOneDocumentAirportWithTheOppositeProviderAirport() {
		ExecutorService executor = Executors.newFixedThreadPool(3);
		try {
			CountingProvider aero = new CountingProvider("AERODATABOX", 0,
					success("AERODATABOX", "LIVE"));
			RouteDependentProvider serp = new RouteDependentProvider();
			MultiSourceFlightService service = service(List.of(aero, serp), executor);

			service.findFlight("BA117", DATE, "CDG", null, false);

			assertEquals("LHR/JFK", serp.lastRoute.get(),
					"a partial document route must not be mixed with AeroDataBox's other endpoint");
		} finally {
			executor.shutdownNow();
		}
	}

	private MultiSourceFlightService service(List<OperationalFlightDataProvider> providers, ExecutorService executor) {
		return new MultiSourceFlightService(providers, Duration.ofMinutes(5),
				Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(1), executor);
	}

	private ProviderFlightResult success(String provider, String quality) {
		boolean live = !"SCHEDULE".equals(quality);
		OffsetDateTime departure = OffsetDateTime.parse("2026-08-24T08:20:00Z");
		return new ProviderFlightResult(provider, "SUCCESS", null, "BA117", DATE,
				"LHR", "London Heathrow", "JFK", "John F. Kennedy", departure,
				live ? departure.plusMinutes(5) : null, null,
				departure.plusHours(8), null, null, null,
				live ? "5" : null, null, null, null, null,
				live ? "EXPECTED" : null, null, null, quality,
				Instant.parse("2026-08-24T08:00:00Z"), Instant.now(), null, null, live, false);
	}

	private static final class CountingProvider implements OperationalFlightDataProvider {
		private final String name;
		private final long delayMillis;
		private final ProviderFlightResult result;
		private final AtomicInteger calls = new AtomicInteger();

		private CountingProvider(String name, long delayMillis, ProviderFlightResult result) {
			this.name = name;
			this.delayMillis = delayMillis;
			this.result = result;
		}

		@Override public String providerName() { return name; }

		@Override
		public ProviderFlightResult findFlight(String flightNumber, LocalDate departureDate,
				String departureAirportHint, String arrivalAirportHint, boolean refresh) {
			calls.incrementAndGet();
			try { Thread.sleep(delayMillis); }
			catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			return result;
		}
	}

	private final class RouteDependentProvider implements OperationalFlightDataProvider {
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicReference<String> lastRoute = new AtomicReference<>();

		@Override public String providerName() { return "SERPAPI"; }

		@Override
		public ProviderFlightResult findFlight(String flightNumber, LocalDate departureDate,
				String departureAirportHint, String arrivalAirportHint, boolean refresh) {
			calls.incrementAndGet();
			lastRoute.set(departureAirportHint + "/" + arrivalAirportHint);
			if (departureAirportHint == null || arrivalAirportHint == null) {
				return ProviderFlightResult.failure("SERPAPI", "MISSING_ROUTE_HINT",
						"SERPAPI_MISSING_ROUTE_HINT", Instant.now());
			}
			return success("SERPAPI", "SCHEDULE");
		}
	}
}
