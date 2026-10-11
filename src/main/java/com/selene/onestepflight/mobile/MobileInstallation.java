package com.selene.onestepflight.mobile;

import jakarta.persistence.*;

@Entity
@Table(name = "mobile_installation")
public class MobileInstallation {
    @Id public String id;
    @Column(nullable = false, length = 64) public String secretHash;
    @Column(length = 512) public String deviceToken;
    public String environment;
    @Column(name = "notification_authorization") public String authorization;
    public String language;
    public long clientRevision;
    @Version public long rowVersion;
}
