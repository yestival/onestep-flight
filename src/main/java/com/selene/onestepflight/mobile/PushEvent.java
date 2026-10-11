package com.selene.onestepflight.mobile;

import jakarta.persistence.*;

@Entity
@Table(name = "mobile_push_event", uniqueConstraints = @UniqueConstraint(columnNames = {"subscription_id", "snapshot_version", "event_type"}))
public class PushEvent {
    @Id public String id;
    @Column(name = "subscription_id", nullable = false) public String subscriptionId;
    @Column(name = "snapshot_version") public long snapshotVersion;
    @Column(name = "event_type") public String eventType;
    public long createdAt;
}
