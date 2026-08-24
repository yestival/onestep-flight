package com.selene.onestepflight.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.selene.onestepflight.dto.ShareSessionCreationResponse;
import com.selene.onestepflight.dto.ShareSessionRequest;
import com.selene.onestepflight.dto.ShareSessionViewResponse;
import com.selene.onestepflight.service.ShareSessionAccessException.Reason;

@Service
public class ShareSessionService {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final ShareSessionRepository repository;
	private final Duration lifetime;

	public ShareSessionService(ShareSessionRepository repository,
			@Value("${onestep.share-session.lifetime-hours:48}") long lifetimeHours) {
		this.repository = repository;
		if (lifetimeHours <= 0) {
			throw new IllegalArgumentException("Share-session lifetime must be positive");
		}
		this.lifetime = Duration.ofHours(lifetimeHours);
	}

	@Transactional
	public ShareSessionCreationResponse create(ShareSessionRequest request) {
		Instant now = Instant.now();
		String viewToken = uniqueViewToken();
		String updateToken = newToken();
		ShareSessionEntity entity = new ShareSessionEntity(
				tokenHash(viewToken), tokenHash(updateToken), now, now.plus(lifetime));
		entity.apply(request, now);
		repository.save(entity);
		return new ShareSessionCreationResponse(viewToken, updateToken,
				"/share/" + viewToken, entity.expiresAt());
	}

	@Transactional(readOnly = true)
	public ShareSessionViewResponse get(String viewToken) {
		return requireActive(viewToken, Instant.now()).toView();
	}

	@Transactional
	public ShareSessionViewResponse update(String viewToken, String updateToken, ShareSessionRequest request) {
		ShareSessionEntity entity = requireExisting(viewToken);
		verifyUpdateToken(entity, updateToken);
		Instant now = Instant.now();
		verifyActive(entity, now);
		entity.apply(request, now);
		return entity.toView();
	}

	@Transactional
	public void stop(String viewToken, String updateToken) {
		ShareSessionEntity entity = requireExisting(viewToken);
		verifyUpdateToken(entity, updateToken);
		Instant now = Instant.now();
		verifyActive(entity, now);
		entity.revoke(now);
	}

	@Transactional
	public long deleteExpiredSessions(Instant cutoff) {
		return repository.deleteByExpiresAtLessThanEqual(cutoff);
	}

	private ShareSessionEntity requireActive(String viewToken, Instant now) {
		ShareSessionEntity entity = requireExisting(viewToken);
		verifyActive(entity, now);
		return entity;
	}

	private ShareSessionEntity requireExisting(String viewToken) {
		return repository.findByViewTokenHash(tokenHash(viewToken == null ? "" : viewToken.trim()))
				.orElseThrow(() -> new ShareSessionAccessException(Reason.NOT_FOUND));
	}

	private void verifyActive(ShareSessionEntity entity, Instant now) {
		if (entity.revokedAt() != null) {
			throw new ShareSessionAccessException(Reason.STOPPED);
		}
		if (!entity.expiresAt().isAfter(now)) {
			throw new ShareSessionAccessException(Reason.EXPIRED);
		}
	}

	private void verifyUpdateToken(ShareSessionEntity entity, String updateToken) {
		String suppliedHash = tokenHash(updateToken == null ? "" : updateToken.trim());
		if (!MessageDigest.isEqual(entity.updateTokenHash().getBytes(StandardCharsets.US_ASCII),
				suppliedHash.getBytes(StandardCharsets.US_ASCII))) {
			throw new ShareSessionAccessException(Reason.INVALID_UPDATE_TOKEN);
		}
	}

	private String uniqueViewToken() {
		for (int attempt = 0; attempt < 5; attempt++) {
			String token = newToken();
			if (!repository.existsByViewTokenHash(tokenHash(token))) {
				return token;
			}
		}
		throw new IllegalStateException("Unable to allocate a unique share token");
	}

	private static String newToken() {
		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);
		return TOKEN_ENCODER.encodeToString(bytes);
	}

	private static String tokenHash(String token) {
		try {
			return java.util.HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
