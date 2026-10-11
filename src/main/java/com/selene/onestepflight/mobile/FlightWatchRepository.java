package com.selene.onestepflight.mobile;
public interface FlightWatchRepository extends org.springframework.data.jpa.repository.JpaRepository<FlightWatch, String> {
 java.util.List<FlightWatch> findTop50ByDueAtLessThanEqualAndLeaseUntilLessThanOrderByDueAt(long now, long lease);
 @org.springframework.data.jpa.repository.Modifying
 @org.springframework.data.jpa.repository.Query("update FlightWatch w set w.leaseOwner=:owner, w.leaseUntil=:until where w.id=:id and w.dueAt<=:now and w.leaseUntil<:now")
 int claim(String id, String owner, long until, long now);
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select w from FlightWatch w where w.id=:id")
 java.util.Optional<FlightWatch> lock(String id);
 @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
 @org.springframework.data.jpa.repository.Modifying
 @org.springframework.data.jpa.repository.Query("update FlightWatch w set w.dueAt=:now where w.id=:id and w.dueAt>:now")
 int wake(String id,long now);
}
