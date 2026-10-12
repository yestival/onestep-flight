package com.selene.onestepflight.mobile;

import jakarta.persistence.*;

@Entity
@Table(name = "mobile_installation")
public class MobileInstallation {
    @Id public String id;
    @Column(nullable = false, length = 64) public String secretHash;
    @Column(length = 4096) public String deviceToken;
    @Column(nullable = false) public String platform = "ios";
    @Column(nullable = false) public String pushProvider = "apns";
    public String environment;
    @Column(name = "notification_authorization") public String authorization;
    public String language;
    public long clientRevision;
    @Version public long rowVersion;
}
