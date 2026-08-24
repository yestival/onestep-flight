package com.selene.onestepflight.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class IpRateLimitService {
	private static final Duration WINDOW = Duration.ofHours(1);
	private final Map<LimitKey, WindowCounter> counters = new ConcurrentHashMap<>();
	private final AtomicLong checks = new AtomicLong();
	private final Map<Category, Integer> limits;
	private final Clock clock;

	@Autowired
	public IpRateLimitService(
			@Value("${onestep.rate-limit.live-flight-per-hour:20}") int liveFlightLimit,
			@Value("${onestep.rate-limit.document-upload-per-hour:8}") int documentUploadLimit,
			@Value("${onestep.rate-limit.share-create-per-hour:10}") int shareCreateLimit) {
		this(liveFlightLimit, documentUploadLimit, shareCreateLimit, Clock.systemUTC());
	}

	IpRateLimitService(int liveFlightLimit, int documentUploadLimit, int shareCreateLimit, Clock clock) {
		this.limits = Map.of(Category.LIVE_FLIGHT, positive(liveFlightLimit),
				Category.DOCUMENT_UPLOAD, positive(documentUploadLimit),
				Category.SHARE_CREATE, positive(shareCreateLimit));
		this.clock = clock;
	}

	public void check(Category category, HttpServletRequest request) {
		check(category, request == null ? null : request.getRemoteAddr());
	}

	void check(Category category, String remoteAddress) {
		String address = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
		Instant now = clock.instant();
		if ((checks.incrementAndGet() & 255L) == 0L) removeExpiredCounters(now);
		WindowCounter counter = counters.computeIfAbsent(new LimitKey(category, address),
				ignored -> new WindowCounter(now));
		synchronized (counter) {
			if (!now.isBefore(counter.startedAt.plus(WINDOW))) {
				counter.startedAt = now;
				counter.count = 0;
			}
			if (counter.count >= limits.get(category)) {
				long retryAfter = Math.max(1,
						Duration.between(now, counter.startedAt.plus(WINDOW)).toSeconds());
				throw new RateLimitExceededException(category, retryAfter);
			}
			counter.count += 1;
		}
	}

	private void removeExpiredCounters(Instant now) {
		counters.entrySet().removeIf(entry -> {
			WindowCounter counter = entry.getValue();
			synchronized (counter) {
				return !now.isBefore(counter.startedAt.plus(WINDOW));
			}
		});
	}

	private static int positive(int value) {
		if (value < 1) throw new IllegalArgumentException("Rate limit must be positive");
		return value;
	}

	public enum Category { LIVE_FLIGHT, DOCUMENT_UPLOAD, SHARE_CREATE }
	private record LimitKey(Category category, String remoteAddress) {}
	private static final class WindowCounter {
		private Instant startedAt;
		private int count;
		private WindowCounter(Instant startedAt) { this.startedAt = startedAt; }
	}
}
