package com.selene.onestepflight.mobile;

import jakarta.persistence.*;

@Entity
@Table(name = "mobile_subscription", uniqueConstraints = @UniqueConstraint(columnNames = "installation_id"))
public class MobileSubscription {
    @Id public String id;
    @Column(name = "installation_id", nullable = false) public String installationId;
    @Column(nullable = false, length = 512) public String journeyKey;
    @Column(nullable = false, length = 256) public String watchId;
    @Column(columnDefinition = "text") public String facts;
    @Column(columnDefinition = "text") public String progress;
    @Column(columnDefinition = "text") public String displayState;
    public String lastAction;
    public String activityId;
    @Column(length = 512) public String activityToken;
    public String activityState;
    public long activityGeneration;
    public long clientRevision;
    public long snapshotVersion;
    public long endsAt;
    public boolean active;
    public boolean baseline;
    @Version public long rowVersion;
}
