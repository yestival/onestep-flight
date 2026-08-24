package com.selene.onestepflight.service;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "onestep.share-session.cleanup-enabled", havingValue = "true", matchIfMissing = true)
class ShareSessionCleanupConfiguration {

	private final ShareSessionService shareSessionService;

	ShareSessionCleanupConfiguration(ShareSessionService shareSessionService) {
		this.shareSessionService = shareSessionService;
	}

	@Scheduled(cron = "${onestep.share-session.cleanup-cron:0 0 * * * *}", zone = "UTC")
	void deleteExpiredShareSessions() {
		shareSessionService.deleteExpiredSessions(Instant.now());
	}
}
