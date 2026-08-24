package com.selene.onestepflight.service;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface ShareSessionRepository extends JpaRepository<ShareSessionEntity, Long> {

	Optional<ShareSessionEntity> findByViewTokenHash(String viewTokenHash);

	boolean existsByViewTokenHash(String viewTokenHash);

	long deleteByExpiresAtLessThanEqual(Instant cutoff);
}
