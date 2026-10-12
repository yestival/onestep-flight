package com.selene.onestepflight.mobile;

import com.selene.onestepflight.dto.ResolvedFlightContext;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MobileJourneyService {
    public record InstallationUpdate(String deviceToken,String environment,String authorization,String language,String platform,String pushProvider) {
        public InstallationUpdate(String deviceToken,String environment,String authorization,String language) { this(deviceToken,environment,authorization,language,null,null); }
    }
    public record JourneySync(String journeyKey,ResolvedFlightContext flight,MobileFlightLogic.Progress progress,String locale,long clientRevision,long snapshotVersion) {}
    public record ActivityBinding(String journeyKey,String activityId,String token,String state,long generation) {}
    private final MobileInstallationRepository installations;
    private final MobileSubscriptionRepository subscriptions;
    private final FlightWatchRepository watches;
    private final PushDeliveryRepository deliveries;
    private final MobileFlightLogic logic;
    private final ApnsClient apns;
    private final FcmClient fcm;
    public MobileJourneyService(MobileInstallationRepository installations,MobileSubscriptionRepository subscriptions,FlightWatchRepository watches,PushDeliveryRepository deliveries,MobileFlightLogic logic,ApnsClient apns,FcmClient fcm) {
        this.installations=installations; this.subscriptions=subscriptions; this.watches=watches; this.deliveries=deliveries; this.logic=logic; this.apns=apns; this.fcm=fcm;
    }
    @Transactional
    public Map<String,Object> register(String id,String authorization) { return register(id,authorization,null); }
    @Transactional
    public Map<String,Object> register(String id,String authorization,String platform) {
        if(platform!=null && !Set.of("ios","android").contains(platform)) throw error(HttpStatus.BAD_REQUEST,"INVALID_PLATFORM");
        try { UUID.fromString(id); } catch(Exception ex) { throw error(HttpStatus.BAD_REQUEST,"INVALID_INSTALLATION"); }
        String secret=secret(authorization);
        var existing=installations.findById(id);
        if(existing.isPresent()) authenticate(id,authorization);
        else {
            MobileInstallation i=new MobileInstallation(); i.id=id; i.secretHash=hash(secret); i.environment="production"; i.language="en"; i.authorization="notDetermined";
            i.platform=platform==null ? "ios" : platform; i.pushProvider="android".equals(i.platform) ? "fcm" : "apns";
            installations.saveAndFlush(i);
        }
        var result=new LinkedHashMap<String,Object>(readiness(installations.findById(id).orElseThrow())); result.put("id",id); return result;
    }
    MobileInstallation authenticate(String id,String authorization) {
        var i=installations.lock(id).orElseThrow(()->error(HttpStatus.UNAUTHORIZED,"INSTALLATION_AUTH_REQUIRED"));
        if(!MessageDigest.isEqual(i.secretHash.getBytes(StandardCharsets.US_ASCII),hash(secret(authorization)).getBytes(StandardCharsets.US_ASCII))) throw error(HttpStatus.UNAUTHORIZED,"INSTALLATION_AUTH_REQUIRED");
        return i;
    }
    private static String secret(String authorization) {
        if(authorization==null || !authorization.startsWith("Bearer ")) throw error(HttpStatus.UNAUTHORIZED,"INSTALLATION_AUTH_REQUIRED");
        String value=authorization.substring(7);
        try { if(Base64.getDecoder().decode(value).length<32 || value.length()>512) throw new IllegalArgumentException(); }
        catch(Exception ex) { throw error(HttpStatus.UNAUTHORIZED,"INSTALLATION_AUTH_REQUIRED"); }
        return value;
    }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
    static ResponseStatusException error(HttpStatus status,String reason) { return new ResponseStatusException(status,reason); }
    static void token(String token) { if(token!=null && !token.isEmpty() && !token.matches("[a-fA-F0-9]{32,512}")) throw error(HttpStatus.BAD_REQUEST,"INVALID_PUSH_TOKEN"); }
    @Transactional
    public Map<String,Object> update(String id,String auth,InstallationUpdate input) {
        var i=authenticate(id,auth);
        String platform=input.platform()==null ? i.platform : input.platform();
        String provider=input.pushProvider()==null ? "android".equals(platform) ? "fcm" : "apns" : input.pushProvider();
        if(!Objects.equals(platform,i.platform) || !("android".equals(platform) && "fcm".equals(provider) || "ios".equals(platform) && "apns".equals(provider))) throw error(HttpStatus.BAD_REQUEST,"INVALID_PLATFORM");
        if("android".equals(platform)) {
            if(input.deviceToken()!=null && !input.deviceToken().isEmpty() && !input.deviceToken().matches("[A-Za-z0-9_:.\\-]{20,4096}")) throw error(HttpStatus.BAD_REQUEST,"INVALID_PUSH_TOKEN");
        } else token(input.deviceToken());
        if(!Set.of("sandbox","production").contains(Objects.toString(input.environment(),"")) || !Set.of("zh","en","es").contains(Objects.toString(input.language(),"")) || !Set.of("notDetermined","denied","authorized","provisional","ephemeral").contains(Objects.toString(input.authorization(),""))) throw error(HttpStatus.BAD_REQUEST,"INVALID_INSTALLATION_SETTINGS");
        if(!Objects.equals(i.deviceToken,input.deviceToken()) || !Objects.equals(i.environment,input.environment())) {
            subscriptions.findByInstallationId(id).ifPresent(s->deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING").stream().filter(d->!"ACTIVITY".equals(d.channel)).forEach(d->{d.status="OBSOLETE"; deliveries.save(d);}));
        }
        i.deviceToken=input.deviceToken(); i.environment=input.environment(); i.authorization=input.authorization(); i.language=input.language(); i.pushProvider=provider; installations.save(i);
        return readiness(i);
    }
    @Transactional
    public Map<String,Object> sync(String id,String auth,JourneySync input) {
        var i=authenticate(id,auth);
        var f=input.flight(); var p=input.progress();
        if(f==null || p==null || f.departureDate()==null || f.flightNumber()==null || !f.flightNumber().replaceAll("\\s","").matches("[A-Z0-9]{2,3}[0-9]{1,4}[A-Z]?") || f.departureAirport()==null || !f.departureAirport().matches("[A-Z]{3}") || f.arrivalAirport()==null || !f.arrivalAirport().matches("[A-Z]{3}") || input.journeyKey()==null || input.journeyKey().length()>512) throw error(HttpStatus.BAD_REQUEST,"INVALID_JOURNEY");
        if(f.demo() || f.demoSessionId()!=null || f.demoScenario()!=null) throw error(HttpStatus.BAD_REQUEST,"DEMO_PUSH_DISABLED");
        if(!Set.of("en","zh","es").contains(Objects.toString(input.locale(),""))) throw error(HttpStatus.BAD_REQUEST,"INVALID_LANGUAGE");
        if(f.conflicts()!=null && f.conflicts().stream().anyMatch(c->c.field()==null && c.blocking() || c.field()!=null && Set.of("flightNumber","departureDate","departureAirport","arrivalAirport").contains(c.field())) && !p.conflictsConfirmed()) throw error(HttpStatus.CONFLICT,"IDENTITY_REVIEW_REQUIRED");
        if(input.clientRevision()<i.clientRevision) throw error(HttpStatus.CONFLICT,"OLD_CLIENT_REVISION");
        String watchId=MobileFlightLogic.identity(f);
        var existing=subscriptions.findByInstallationId(id);
        MobileSubscription s=existing.orElseGet(MobileSubscription::new);
        boolean sameIdentity=s.id!=null && watchId.equals(s.watchId) && input.journeyKey().equals(s.journeyKey);
        boolean cancelled=sameIdentity && s.facts!=null && "CANCELLED".equals(logic.read(s.facts,ResolvedFlightContext.class).status());
        boolean newJourney=!sameIdentity || !s.active && !cancelled && !p.exitedAirport();
        if(newJourney && input.clientRevision()<=i.clientRevision && s.id!=null) throw error(HttpStatus.CONFLICT,"OLD_CLIENT_REVISION");
        if(!newJourney && input.clientRevision()==s.clientRevision) return response(s);
        if(newJourney) {
            if(s.id!=null) { invalidate(s); subscriptions.delete(s); subscriptions.flush(); }
            s=new MobileSubscription(); s.id=UUID.randomUUID().toString(); s.installationId=id; s.watchId=watchId; s.journeyKey=input.journeyKey();
            s.facts=logic.json(logic.minimal(f)); s.active=true; s.endsAt=MobileFlightLogic.endAt(f);
            if(!watches.existsById(watchId)) {
                FlightWatch w=new FlightWatch(); w.id=watchId; w.flightNumber=f.flightNumber().replaceAll("\\s",""); w.flightDate=f.departureDate().toString(); w.departureAirport=f.departureAirport(); w.arrivalAirport=f.arrivalAirport(); w.dueAt=Instant.now().getEpochSecond(); watches.saveAndFlush(w);
            } else {
                var watch=watches.findById(watchId).orElseThrow();
                if(watch.facts!=null) {
                    s.facts=logic.json(logic.retain(f,logic.read(watch.facts,ResolvedFlightContext.class)));
                    s.endsAt=MobileFlightLogic.endAt(logic.read(s.facts,ResolvedFlightContext.class));
                    s.baseline=true;
                }
            }
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { watches.wake(watchId,Instant.now().getEpochSecond()); }
            });
        }
        i.clientRevision=input.clientRevision(); i.language=input.locale(); installations.save(i);
        // A progress write cannot overwrite operational facts learned by the server or APNs.
        var previousProgress=s.progress==null ? null : logic.read(s.progress,MobileFlightLogic.Progress.class);
        boolean guidanceObsolete=p.exitedAirport() || p.boarded() && (previousProgress==null || !previousProgress.boarded())
                || p.needsAssistance() && (previousProgress==null || !previousProgress.needsAssistance());
        for(var pending:deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING")) {
            if("ACTIVITY".equals(pending.channel) || "FCM_SYNC".equals(pending.channel) || guidanceObsolete) { pending.status="OBSOLETE"; deliveries.save(pending); }
        }
        var savedFlight=logic.read(s.facts,ResolvedFlightContext.class);
        // A cached atGate check-in refers to that cached gate, never a new server gate.
        if(p.atGate() && (!Objects.equals(f.gate(),savedFlight.gate()) || !Objects.equals(f.terminal(),savedFlight.terminal()))) p=p.gateChanged();
        s.clientRevision=input.clientRevision(); s.progress=logic.json(p); s.snapshotVersion++;
        if(p.exitedAirport()) { s.active=false; invalidate(s); }
        var display=logic.display(savedFlight,p,i.language,s.journeyKey,s.lastAction,s.snapshotVersion,s.clientRevision,s.endsAt);
        s.lastAction=display.actionCode(); s.displayState=logic.json(display); subscriptions.save(s);
        return response(s);
    }
    @Transactional
    public Map<String,Object> bind(String id,String auth,ActivityBinding input) {
        if("android".equals(authenticate(id,auth).platform)) throw error(HttpStatus.BAD_REQUEST,"ACTIVITYKIT_IOS_ONLY");
        var s=subscriptions.findByInstallationId(id).orElseThrow(()->error(HttpStatus.CONFLICT,"NO_CURRENT_JOURNEY"));
        if(!Objects.equals(s.journeyKey,input.journeyKey()) || input.generation()<s.activityGeneration) throw error(HttpStatus.CONFLICT,"OLD_ACTIVITY_BINDING");
        token(input.token());
        if(input.activityId()==null || input.activityId().length()>255 || !Set.of("active","ended","dismissed").contains(Objects.toString(input.state(),""))) throw error(HttpStatus.BAD_REQUEST,"INVALID_ACTIVITY_BINDING");
        if(input.generation()==s.activityGeneration && s.activityId!=null && !s.activityId.equals(input.activityId())) throw error(HttpStatus.CONFLICT,"OLD_ACTIVITY_BINDING");
        if(!Objects.equals(s.activityToken,input.token())) deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING").stream().filter(d->"ACTIVITY".equals(d.channel)).forEach(d->{d.status="OBSOLETE"; deliveries.save(d);});
        s.activityGeneration=input.generation(); s.activityId=input.activityId(); s.activityState=input.state(); s.activityToken="active".equals(input.state()) ? input.token() : null; subscriptions.save(s);
        return response(s);
    }
    @Transactional
    public Map<String,Object> get(String id,String auth) { var i=authenticate(id,auth); return subscriptions.findByInstallationId(id).map(this::response).orElseGet(()->{ var r=new LinkedHashMap<String,Object>(readiness(i)); r.put("active",false); return r; }); }
    @Transactional
    public void clear(String id,String auth,long revision,String journeyKey) {
        var i=authenticate(id,auth);
        if(revision<i.clientRevision) throw error(HttpStatus.CONFLICT,"OLD_CLIENT_REVISION");
        i.clientRevision=revision; installations.save(i);
        subscriptions.findByInstallationId(id).ifPresent(s->{
            if(journeyKey!=null && !journeyKey.isEmpty() && !journeyKey.equals(s.journeyKey)) throw error(HttpStatus.CONFLICT,"OLD_JOURNEY");
            invalidate(s); s.active=false; s.activityToken=null; subscriptions.save(s);
        });
    }
    public void invalidate(MobileSubscription s) { for(var d:deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING")) { d.status="OBSOLETE"; deliveries.save(d); } }
    Map<String,Object> response(MobileSubscription s) {
        Map<String,Object> r=new LinkedHashMap<>(readiness(installations.findById(s.installationId).orElseThrow())); r.put("subscriptionId",s.id); r.put("journeyKey",s.journeyKey); r.put("active",s.active); r.put("clientRevision",s.clientRevision); r.put("snapshotVersion",s.snapshotVersion);
        if(s.displayState!=null) r.put("displayState",logic.wireDisplay(logic.read(s.displayState,FlightDisplayState.class),logic.read(s.facts,ResolvedFlightContext.class))); return r;
    }
    private Map<String,Object> readiness(MobileInstallation i) {
        boolean android="android".equals(i.platform), ready=android ? fcm.ready() : apns.ready();
        return Map.of("pushReady",ready,"pushStatus",ready ? "READY" : android ? "FCM_NOT_CONFIGURED" : "APNS_NOT_CONFIGURED","platform",i.platform,"pushProvider",i.pushProvider);
    }
}
