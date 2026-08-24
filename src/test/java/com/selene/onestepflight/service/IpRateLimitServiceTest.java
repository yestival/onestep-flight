package com.selene.onestepflight.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IpRateLimitServiceTest {
	@Test
	void limitsEachFrameworkRemoteAddressAndResetsAfterOneHour() {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-24T00:00:00Z"));
		IpRateLimitService service = new IpRateLimitService(2, 1, 1, clock);
		MockHttpServletRequest firstIp = new MockHttpServletRequest();
		firstIp.setRemoteAddr("192.0.2.10");
		MockHttpServletRequest secondIp = new MockHttpServletRequest();
		secondIp.setRemoteAddr("192.0.2.11");

		service.check(IpRateLimitService.Category.LIVE_FLIGHT, firstIp);
		service.check(IpRateLimitService.Category.LIVE_FLIGHT, firstIp);
		RateLimitExceededException limited = assertThrows(RateLimitExceededException.class,
				() -> service.check(IpRateLimitService.Category.LIVE_FLIGHT, firstIp));
		assertTrue(limited.retryAfterSeconds() > 0);
		assertDoesNotThrow(() -> service.check(IpRateLimitService.Category.LIVE_FLIGHT, secondIp));

		clock.instant = clock.instant.plusSeconds(3600);
		assertDoesNotThrow(() -> service.check(IpRateLimitService.Category.LIVE_FLIGHT, firstIp));
	}

	private static final class MutableClock extends Clock {
		private Instant instant;
		private MutableClock(Instant instant) { this.instant = instant; }
		@Override public ZoneId getZone() { return ZoneId.of("UTC"); }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return instant; }
	}
}
