package com.selene.onestepflight.mobile;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MobilePushSender {
    private final PushDeliveryRepository deliveries;
    private final MobileSubscriptionRepository subscriptions;
    private final MobileInstallationRepository installations;
    private final ApnsClient apns;
    private final TransactionTemplate tx;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    public MobilePushSender(PushDeliveryRepository deliveries,MobileSubscriptionRepository subscriptions,MobileInstallationRepository installations,ApnsClient apns,PlatformTransactionManager manager) { this.deliveries=deliveries; this.subscriptions=subscriptions; this.installations=installations; this.apns=apns; tx=new TransactionTemplate(manager); }
    public void tick() {
        long now=Instant.now().getEpochSecond();
        for(var candidate:deliveries.findTop100ByStatusAndDueAtLessThanEqualAndLeaseUntilLessThanOrderByDueAt("PENDING",now,now)) {
            String owner=UUID.randomUUID().toString();
            if(!Boolean.TRUE.equals(tx.execute(status->deliveries.claim(candidate.id,owner,now+60,now)==1))) continue;
            // Serialize with journey replacement/revocation while submitting this bounded HTTP request.
            tx.executeWithoutResult(status->{
                var d=deliveries.findById(candidate.id).orElseThrow(); if(!owner.equals(d.leaseOwner) || !"PENDING".equals(d.status)) return;
                var s=subscriptions.findById(d.subscriptionId).orElse(null);
                if(s==null) { d.status="OBSOLETE"; deliveries.save(d); return; }
                var i=installations.lock(s.installationId).orElseThrow();
                // A revocation may have committed while this sender waited for the installation lock.
                entityManager.refresh(d);
                entityManager.refresh(s);
                if(!"PENDING".equals(d.status)) return;
                s=subscriptions.findById(d.subscriptionId).orElse(null);
                String currentToken="ACTIVITY".equals(d.channel) ? s==null ? null : s.activityToken : i.deviceToken;
                if(s==null || !s.active && !d.finalMessage || !Objects.equals(currentToken,d.token) || !Objects.equals(i.environment,d.environment) || d.expiresAt<=now) { d.status="OBSOLETE"; deliveries.save(d); return; }
                var result=apns.send(d); d.attempts++; d.reason=result.reason();
                if(result.status()==200) d.status="ACCEPTED";
                else if(result.status()==410 || result.status()==400 && Set.of("BadDeviceToken","DeviceTokenNotForTopic").contains(result.reason())) {
                    d.status="TOKEN_INVALID";
                    if("ACTIVITY".equals(d.channel)) { if(Objects.equals(s.activityToken,d.token)) {s.activityToken=null; subscriptions.save(s);} }
                    else if(Objects.equals(i.deviceToken,d.token)) { i.deviceToken=null; installations.save(i); }
                } else if(result.status()==403) d.status="AUTH_FAILED";
                else if(result.status()==0) { d.dueAt=now+900; d.attempts--; }
                else if((result.status()==429 || result.status()>=500) && d.attempts<5) d.dueAt=now+Math.max(result.retryAfter(),new long[]{15,30,60,120,300}[Math.min(d.attempts-1,4)]);
                else d.status="FAILED";
                d.leaseOwner=null; d.leaseUntil=0; deliveries.save(d);
            });
        }
    }
}
