package com.selene.onestepflight.mobile;
public interface PushEventRepository extends org.springframework.data.jpa.repository.JpaRepository<PushEvent, String> {
 boolean existsBySubscriptionIdAndSnapshotVersionAndEventType(String subscriptionId, long snapshotVersion, String eventType);
}
