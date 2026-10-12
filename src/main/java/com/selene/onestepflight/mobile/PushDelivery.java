package com.selene.onestepflight.mobile;

import jakarta.persistence.*;

@Entity
@Table(name = "mobile_push_delivery", uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "channel"}))
public class PushDelivery {
    @Id public String id;
    @Column(name = "event_id", nullable = false) public String eventId;
    public String subscriptionId;
    public String channel;
    @Column(length = 4096) public String token;
    public String environment;
    @Column(columnDefinition = "text") public String payload;
    public boolean finalMessage;
    public int priority;
    public long expiresAt;
    public long dueAt;
    public long leaseUntil;
    public String leaseOwner;
    public int attempts;
    public String status;
    public String reason;
    @Version public long rowVersion;
}
