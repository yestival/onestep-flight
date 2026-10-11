package com.selene.onestepflight.mobile;
public interface MobileSubscriptionRepository extends org.springframework.data.jpa.repository.JpaRepository<MobileSubscription, String> {
 java.util.Optional<MobileSubscription> findByInstallationId(String installationId);
 java.util.List<MobileSubscription> findByWatchIdAndActiveTrue(String watchId);
 java.util.List<MobileSubscription> findTop100ByActiveTrueAndEndsAtLessThanEqual(long now);
}
