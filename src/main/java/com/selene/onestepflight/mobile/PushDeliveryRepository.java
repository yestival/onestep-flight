package com.selene.onestepflight.mobile;
public interface PushDeliveryRepository extends org.springframework.data.jpa.repository.JpaRepository<PushDelivery, String> {
 java.util.List<PushDelivery> findTop100ByStatusAndDueAtLessThanEqualAndLeaseUntilLessThanOrderByDueAt(String status, long now, long lease);
 java.util.List<PushDelivery> findBySubscriptionIdAndStatus(String subscriptionId, String status);
 @org.springframework.data.jpa.repository.Modifying
 @org.springframework.data.jpa.repository.Query("update PushDelivery d set d.leaseOwner=:owner, d.leaseUntil=:until where d.id=:id and d.status='PENDING' and d.dueAt<=:now and d.leaseUntil<:now")
 int claim(String id, String owner, long until, long now);
}
