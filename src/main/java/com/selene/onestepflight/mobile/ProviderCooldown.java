package com.selene.onestepflight.mobile;

import jakarta.persistence.*;

@Entity
@Table(name="mobile_provider_cooldown")
public class ProviderCooldown {
    @Id public String id;
    public long retryAt;
}
