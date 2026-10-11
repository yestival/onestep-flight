package com.selene.onestepflight.mobile;

import com.selene.onestepflight.dto.ResolvedFlightContext;
import com.selene.onestepflight.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MobileFlightMonitor {
    private final FlightWatchRepository watches;
    private final MobileSubscriptionRepository subscriptions;
    private final MobileInstallationRepository installations;
    private final MultiSourceFlightService source;
    private final ResolvedFlightService resolver;
    private final MobileFlightLogic logic;
    private final MobilePushEvents events;
    private final MobileJourneyService journeys;
    private final TransactionTemplate tx;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{ Thread t=new Thread(r,"mobile-flight-verification"); t.setDaemon(true); return t; });
    public MobileFlightMonitor(FlightWatchRepository watches,MobileSubscriptionRepository subscriptions,MobileInstallationRepository installations,
            MultiSourceFlightService source,ResolvedFlightService resolver,MobileFlightLogic logic,MobilePushEvents events,MobileJourneyService journeys,PlatformTransactionManager manager) {
        this.watches=watches; this.subscriptions=subscriptions; this.installations=installations; this.source=source; this.resolver=resolver; this.logic=logic; this.events=events; this.journeys=journeys; tx=new TransactionTemplate(manager);
    }
    public void tick() {
        scan(false);
    }
    public void tickAsync() { scan(true); }
    @jakarta.annotation.PreDestroy void close() { workers.shutdownNow(); }
    private void scan(boolean asynchronous) {
        long now=Instant.now().getEpochSecond();
        for(var candidate:watches.findTop50ByDueAtLessThanEqualAndLeaseUntilLessThanOrderByDueAt(now,now)) {
            if(asynchronous && workers.getActiveCount()>=4) break;
            String owner=UUID.randomUUID().toString();
            if(!Boolean.TRUE.equals(tx.execute(status->watches.claim(candidate.id,owner,now+180,now)==1))) continue;
            Runnable work=()->process(candidate,owner,now);
            if(asynchronous) {
                try { workers.execute(work); }
                catch(RejectedExecutionException ex) { release(candidate.id,owner,now+15,false); }
            } else work.run();
        }
    }
    private void process(FlightWatch candidate,String owner,long now) {
            try {
                boolean needed=tx.execute(status->!subscriptions.findByWatchIdAndActiveTrue(candidate.id).isEmpty());
                if(!needed) { release(candidate.id,owner,now+86400,false); return; }
                // Exactly one provider lookup for the canonical flight, irrespective of installation count.
                var operational=source.findFlight(candidate.flightNumber,LocalDate.parse(candidate.flightDate),candidate.departureAirport,candidate.arrivalAirport,true);
                tx.executeWithoutResult(status->apply(candidate.id,owner,operational,Instant.now().getEpochSecond()));
            } catch(RuntimeException ex) {
                long retryAfter=ex instanceof AllFlightProvidersUnavailableException failed ? failed.providerResults().values().stream().mapToLong(v->ProviderRetryAfter.seconds(v.errorCode(),now)).max().orElse(0) : 0;
                tx.executeWithoutResult(status->{
                    var w=watches.lock(candidate.id).orElseThrow(); if(!owner.equals(w.leaseOwner)) return;
                    w.failures++; long delay=new long[]{60,120,300,900}[Math.min(w.failures-1,3)];
                    w.dueAt=Instant.now().getEpochSecond()+Math.max(delay,retryAfter); w.leaseUntil=0; w.leaseOwner=null; watches.save(w);
                });
            }
    }
    private void release(String id,String owner,long next,boolean failure) {
        tx.executeWithoutResult(status->{ var w=watches.lock(id).orElseThrow(); if(!owner.equals(w.leaseOwner)) return; w.dueAt=next; w.leaseUntil=0; w.leaseOwner=null; watches.save(w); });
    }
    private void apply(String watchId,String owner,MultiSourceFlightSnapshot operational,long now) {
        var w=watches.lock(watchId).orElseThrow();
        if(!owner.equals(w.leaseOwner) || w.leaseUntil<now) return;
        if(operational.flight().demo() || !w.flightDate.equals(Objects.toString(operational.flight().departureDate(),"")) || !w.departureAirport.equals(operational.flight().departureAirport()) || !w.arrivalAirport.equals(operational.flight().arrivalAirport())) throw new IllegalStateException("FLIGHT_IDENTITY_MISMATCH");
        var shared=resolver.resolveOperational(operational,null);
        if(!w.id.equals(MobileFlightLogic.identity(shared))) throw new IllegalStateException("FLIGHT_IDENTITY_MISMATCH");
        var previous=w.facts==null ? null : logic.read(w.facts,ResolvedFlightContext.class);
        if(previous!=null && previous.dataRetrievedAt()!=null && shared.dataRetrievedAt()!=null && !shared.dataRetrievedAt().isAfter(previous.dataRetrievedAt())) {
            w.dueAt=now+logic.interval(previous,now); w.leaseUntil=0; w.leaseOwner=null; watches.save(w); return;
        }
        w.snapshotVersion++; w.failures=0;
        w.facts=logic.json(logic.retain(previous,shared));
        for(var candidate:subscriptions.findByWatchIdAndActiveTrue(w.id)) {
            var installation=installations.lock(candidate.installationId).orElseThrow();
            var s=subscriptions.findByInstallationId(installation.id).orElseThrow();
            if(!s.active || !s.watchId.equals(w.id)) continue;
            var old=logic.read(s.facts,ResolvedFlightContext.class);
            var merged=logic.retain(old,resolver.resolveOperational(operational,old));
            var detected=events.changes(old,shared);
            var changes=s.baseline ? detected : List.<MobilePushEvents.Change>of();
            var progress=logic.read(s.progress,MobileFlightLogic.Progress.class);
            if(detected.stream().anyMatch(c->"GATE".equals(c.type()) || "TERMINAL".equals(c.type()))) { progress=progress.gateChanged(); s.progress=logic.json(progress); }
            s.baseline=true; s.snapshotVersion++; s.facts=logic.json(merged);
            s.endsAt=MobileFlightLogic.endAt(merged);
            boolean cancelled="CANCELLED".equals(merged.status());
            boolean end=cancelled || progress.exitedAirport() || s.endsAt<=now;
            if(cancelled) s.endsAt=Math.max(s.endsAt,now+1800);
            var display=logic.display(merged,progress,installation.language,s.journeyKey,s.lastAction,s.snapshotVersion,s.clientRevision,s.endsAt);
            s.lastAction=display.actionCode(); s.displayState=logic.json(display);
            events.enqueue(s,installation,shared,display,changes,end,now);
            if(end) s.active=false;
            subscriptions.save(s);
        }
        w.dueAt=now+logic.interval(shared,now); w.leaseUntil=0; w.leaseOwner=null; watches.save(w);
    }
    public void expireJourneys() {
        long now=Instant.now().getEpochSecond();
        for(var candidate:subscriptions.findTop100ByActiveTrueAndEndsAtLessThanEqual(now)) tx.executeWithoutResult(status->{
            var i=installations.lock(candidate.installationId).orElseThrow(); var s=subscriptions.findByInstallationId(i.id).orElseThrow();
            if(!s.active || s.endsAt>now) return;
            var f=logic.read(s.facts,ResolvedFlightContext.class); s.snapshotVersion++;
            var display=logic.display(f,logic.read(s.progress,MobileFlightLogic.Progress.class),i.language,s.journeyKey,s.lastAction,s.snapshotVersion,s.clientRevision,s.endsAt);
            s.displayState=logic.json(display); s.lastAction=display.actionCode();
            events.enqueue(s,i,f,display,List.of(),true,now); s.active=false; subscriptions.save(s);
        });
    }
}

@Configuration(proxyBeanMethods=false)
@EnableScheduling
class MobileMonitoringSchedule {
    private final MobileFlightMonitor monitor;
    private final MobilePushSender sender;
    private final boolean enabled;
    MobileMonitoringSchedule(MobileFlightMonitor monitor,MobilePushSender sender,@Value("${onestep.mobile.monitor-enabled:true}") boolean enabled) { this.monitor=monitor; this.sender=sender; this.enabled=enabled; }
    @Scheduled(fixedRate=15000,initialDelay=15000) void poll() { if(enabled) { monitor.expireJourneys(); monitor.tickAsync(); } }
    @Scheduled(fixedDelay=5000,initialDelay=5000) void send() { if(enabled) sender.tick(); }
}
