package com.selene.onestepflight.service;

import com.selene.onestepflight.dto.ProviderFlightResult;
import javax.sql.DataSource;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Provider Retry-After survives restarts and applies even when another source succeeded. */
@Service
public class ProviderCooldownService {
    private final JdbcTemplate jdbc;
    public ProviderCooldownService(DataSource source) { jdbc=new JdbcTemplate(source); }
    public long retryAt(String provider) {
        return jdbc.query("select retry_at from mobile_provider_cooldown where id=?",(row,n)->row.getLong(1),provider).stream().findFirst().orElse(0L);
    }
    public void observe(ProviderFlightResult result) {
        long now=Instant.now().getEpochSecond(), wait=ProviderRetryAfter.seconds(result.errorCode(),now);
        if(wait<=0) return;
        long until=now+wait;
        int changed=jdbc.update("update mobile_provider_cooldown set retry_at=? where id=? and retry_at<?",until,result.provider(),until);
        if(changed==0) {
            try { jdbc.update("insert into mobile_provider_cooldown (id,retry_at) values (?,?)",result.provider(),until); }
            catch(DuplicateKeyException ignored) { jdbc.update("update mobile_provider_cooldown set retry_at=? where id=? and retry_at<?",until,result.provider(),until); }
        }
    }
}
