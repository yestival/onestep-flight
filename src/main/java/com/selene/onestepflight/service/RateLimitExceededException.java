package com.selene.onestepflight.service;

public class RateLimitExceededException extends RuntimeException {
	private final IpRateLimitService.Category category;
	private final long retryAfterSeconds;

	public RateLimitExceededException(IpRateLimitService.Category category, long retryAfterSeconds) {
		super(category.name() + " rate limit exceeded");
		this.category = category;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public IpRateLimitService.Category category() { return category; }
	public long retryAfterSeconds() { return retryAfterSeconds; }
}
