package com.selene.onestepflight.mobile;
public interface MobileInstallationRepository extends org.springframework.data.jpa.repository.JpaRepository<MobileInstallation, String> {
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select i from MobileInstallation i where i.id=:id")
 java.util.Optional<MobileInstallation> lock(String id);
}
