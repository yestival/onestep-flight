package com.selene.onestepflight.mobile;

import com.selene.onestepflight.dto.ResolvedFlightContext;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MobilePushEvents {
    private final PushEventRepository events;
    private final PushDeliveryRepository deliveries;
    private final MobileFlightLogic logic;
    public MobilePushEvents(PushEventRepository events,PushDeliveryRepository deliveries,MobileFlightLogic logic) { this.events=events; this.deliveries=deliveries; this.logic=logic; }
    public record Change(String type,String oldValue,String newValue) {}
    public List<Change> changes(ResolvedFlightContext old,ResolvedFlightContext fresh) {
        List<Change> changes=new ArrayList<>();
        if(old==null) return changes;
        boolean importantStatus=Set.of("CANCELLED","BOARDING","DEPARTED","EN_ROUTE","IN_FLIGHT","ARRIVED","LANDED","DELAYED","DIVERTED","RETURNED").contains(Objects.toString(fresh.status(),""))
                || "DELAYED".equals(old.status()) && Set.of("ON_TIME","SCHEDULED").contains(Objects.toString(fresh.status(),""));
        add(changes,"STATUS",old.status(),fresh.status(),logic.reliable(fresh,"status") && importantStatus);
        add(changes,"GATE",old.gate(),fresh.gate(),logic.reliable(fresh,"gate"));
        add(changes,"TERMINAL",old.terminal(),fresh.terminal(),logic.reliable(fresh,"terminal"));
        time(changes,"BOARDING_TIME",old.timeContext()==null ? old.documentBoardingTime() : old.timeContext().boardingTime(),fresh.timeContext()==null ? fresh.documentBoardingTime() : fresh.timeContext().boardingTime(),logic.reliable(fresh,"boardingTime"));
        time(changes,"DEPARTURE_TIME",MobileFlightLogic.first(old.estimatedDeparture(),old.scheduledDeparture()),MobileFlightLogic.first(fresh.estimatedDeparture(),fresh.scheduledDeparture()),logic.reliable(fresh,fresh.estimatedDeparture()!=null ? "estimatedDeparture" : "scheduledDeparture"));
        time(changes,"ARRIVAL_TIME",MobileFlightLogic.first(old.estimatedArrival(),old.scheduledArrival()),MobileFlightLogic.first(fresh.estimatedArrival(),fresh.scheduledArrival()),logic.reliable(fresh,fresh.estimatedArrival()!=null ? "estimatedArrival" : "scheduledArrival"));
        add(changes,"BAGGAGE",old.arrivalBaggageBelt(),fresh.arrivalBaggageBelt(),logic.reliable(fresh,"arrivalBaggageBelt"));
        add(changes,"ARRIVAL_TERMINAL",old.arrivalTerminal(),fresh.arrivalTerminal(),logic.reliable(fresh,"arrivalTerminal"));
        changes.sort(Comparator.comparingInt(c->rank(c,fresh))); return changes;
    }
    private static void add(List<Change> out,String type,String old,String value,boolean reliable) {
        if(reliable && value!=null && !value.isBlank() && !value.equalsIgnoreCase(Objects.toString(old,""))) out.add(new Change(type,old,value));
    }
    private static void time(List<Change> out,String type,OffsetDateTime old,OffsetDateTime value,boolean reliable) {
        if(reliable && value!=null && (old==null || Math.abs(value.toEpochSecond()-old.toEpochSecond())>=60)) out.add(new Change(type,old==null ? null : old.toString(),value.toString()));
    }
    private static int rank(Change change,ResolvedFlightContext f) {
        return "STATUS".equals(change.type) && "CANCELLED".equals(f.status()) ? 0 : Set.of("GATE","TERMINAL").contains(change.type) ? 1 : "STATUS".equals(change.type) ? 2 : Set.of("BOARDING_TIME","DEPARTURE_TIME").contains(change.type) ? 3 : 4;
    }
    public void enqueue(MobileSubscription s,MobileInstallation i,ResolvedFlightContext fresh,FlightDisplayState display,List<Change> changes,boolean end,long now) {
        String type=changes.isEmpty() ? end ? "END" : "REFRESH" : changes.get(0).type();
        if(events.existsBySubscriptionIdAndSnapshotVersionAndEventType(s.id,s.snapshotVersion,type)) return;
        // A newer complete card replaces pending cards. Alerts survive pure freshness checks.
        for(var d:deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING")) {
            if("ACTIVITY".equals(d.channel) || !changes.isEmpty() || end) { d.status="OBSOLETE"; deliveries.save(d); }
        }
        PushEvent event=new PushEvent(); event.id=UUID.randomUUID().toString(); event.subscriptionId=s.id; event.snapshotVersion=s.snapshotVersion; event.eventType=type; event.createdAt=now; events.saveAndFlush(event);
        boolean urgent=!changes.isEmpty() && ("BOARDING".equals(fresh.status()) || criticalSoon(changes,fresh,now));
        if(!changes.isEmpty() && i.deviceToken!=null && !i.deviceToken.isBlank() && Set.of("authorized","provisional").contains(Objects.toString(i.authorization,""))) {
            var alert=alert(changes,fresh,display,logic.read(s.progress,MobileFlightLogic.Progress.class),i.language);
            Map<String,Object> aps=Map.of("alert",alert,"sound","default","interruption-level",urgent ? "time-sensitive" : "active");
            Map<String,Object> payload=Map.of("aps",aps,"eventId",event.id,"subscriptionId",s.id,"journeyKey",s.journeyKey,"snapshotVersion",s.snapshotVersion);
            long lifetime="BOARDING".equals(fresh.status()) ? 300 : 900;
            delivery(event,s,"ALERT",i.deviceToken,i.environment,payload,10,Math.min(s.endsAt,now+lifetime),now,end);
        }
        if(s.activityToken!=null && !s.activityToken.isBlank() && "active".equals(s.activityState)) {
            Map<String,Object> aps=new LinkedHashMap<>(); aps.put("timestamp",now); aps.put("event",end ? "end" : "update");
            aps.put("content-state",display); aps.put("stale-date",display.staleAt());
            if(end) aps.put("dismissal-date","CANCELLED".equals(fresh.status()) ? now+1800 : now);
            delivery(event,s,"ACTIVITY",s.activityToken,i.environment,Map.of("aps",aps),urgent ? 10 : 5,end ? now+900 : Math.min(s.endsAt,now+900),now,end);
        }
    }
    private boolean criticalSoon(List<Change> changes,ResolvedFlightContext f,long now) {
        boolean critical=changes.stream().anyMatch(c->Set.of("GATE","TERMINAL","STATUS","BOARDING_TIME","DEPARTURE_TIME").contains(c.type));
        var departure=MobileFlightLogic.first(f.timeContext()==null ? null : f.timeContext().boardingTime(),f.estimatedDeparture(),f.scheduledDeparture());
        return critical && departure!=null && departure.toEpochSecond()-now<=3600 && departure.toEpochSecond()>=now;
    }
    private Map<String,String> alert(List<Change> changes,ResolvedFlightContext f,FlightDisplayState d,MobileFlightLogic.Progress p,String lang) {
        var t=new MobileFlightLogic.Text(lang); var primary=changes.get(0);
        String label=switch(primary.type) {
            case "GATE" -> primary.oldValue==null ? t.l("登机口已公布","Gate announced","Puerta anunciada") : t.l("登机口变更","Gate changed","Cambio de puerta");
            case "TERMINAL" -> t.l("航站楼变更","Terminal changed","Cambio de terminal");
            case "STATUS" -> "CANCELLED".equals(f.status()) ? t.l("航班已取消","Flight cancelled","Vuelo cancelado") : t.l("航班状态更新","Flight status changed","Estado del vuelo actualizado");
            default -> t.l("航班信息更新","Flight update","Actualización del vuelo");
        };
        List<String> body=new ArrayList<>();
        for(var c:changes) {
            if("GATE".equals(c.type)) {
                body.add((c.oldValue==null ? c.newValue : c.oldValue+" → "+c.newValue)+"。");
                if(!"CANCELLED".equals(f.status())) body.add(p.securityCleared() ? t.l("请前往 "+c.newValue+" 登机口。","Go to gate "+c.newValue+".","Ve a la puerta "+c.newValue+".") : t.l("完成安检后前往 "+c.newValue+" 登机口。","After security, go to gate "+c.newValue+".","Después del control, ve a la puerta "+c.newValue+"."));
            } else if("STATUS".equals(c.type)) body.add("CANCELLED".equals(f.status()) ? d.nextStep() : statusText(c.newValue,t));
            else if(c.type.endsWith("TIME")) {
                String value="BOARDING_TIME".equals(c.type) ? d.boardingTime() : "DEPARTURE_TIME".equals(c.type) ? d.departureTime() : d.arrivalTime();
                String title="BOARDING_TIME".equals(c.type) ? t.l("登机时间","Boarding ","Embarque ") : "DEPARTURE_TIME".equals(c.type) ? d.metricThreeLabel() : t.l("到达时间","Arrival ","Llegada ");
                body.add(title+" "+value);
            } else body.add(("TERMINAL".equals(c.type) ? t.l("航站楼 ","Terminal ","Terminal ") : "BAGGAGE".equals(c.type) ? t.l("行李转盘 ","Baggage belt ","Cinta ") : t.l("到达航站楼 ","Arrival terminal ","Terminal de llegada "))+(c.oldValue==null ? "" : c.oldValue+" → ")+c.newValue);
        }
        if(!"CANCELLED".equals(f.status()) && changes.stream().anyMatch(c->"GATE".equals(c.type)) && changes.stream().noneMatch(c->"BOARDING_TIME".equals(c.type)) && !d.boardingTime().equals(d.missingLabel())) body.add(t.l("登机时间","Boarding ","Embarque ")+d.boardingTime()+t.l("（出发机场当地）。"," (departure airport local time)."," (hora local del aeropuerto de salida)."));
        return Map.of("title",d.flightNumber()+" "+label,"body",String.join(" ",body));
    }
    private static String statusText(String status,MobileFlightLogic.Text t) {
        return switch(status) {
            case "BOARDING" -> t.l("航班正在登机。","The flight is boarding.","El vuelo está embarcando.");
            case "DEPARTED","EN_ROUTE","IN_FLIGHT" -> t.l("航班已离港。","The flight has departed.","El vuelo ha salido.");
            case "ARRIVED","LANDED" -> t.l("航班已到达。","The flight has arrived.","El vuelo ha llegado.");
            case "DELAYED" -> t.l("航班延误，请留意最新时间。","Flight delayed. Check the updated time.","Vuelo retrasado. Consulta la nueva hora.");
            case "DIVERTED" -> t.l("航班备降，请联系航空公司。","Flight diverted. Contact the airline.","Vuelo desviado. Contacta con la aerolínea.");
            case "RETURNED" -> t.l("航班已返航，请听从机组安排。","The flight has returned. Follow crew guidance.","El vuelo ha regresado. Sigue las indicaciones de la tripulación.");
            case "ON_TIME","SCHEDULED" -> t.l("航班恢复正常，请核对最新时间。","The flight is back on schedule. Check the updated time.","El vuelo vuelve al horario previsto. Consulta la hora actualizada.");
            default -> t.l("航班状态已更新。","Flight status updated.","Estado del vuelo actualizado.");
        };
    }
    private void delivery(PushEvent event,MobileSubscription s,String channel,String token,String env,Object payload,int priority,long expiry,long now,boolean end) {
        PushDelivery d=new PushDelivery(); d.id=UUID.nameUUIDFromBytes((event.id+channel).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); d.eventId=event.id; d.subscriptionId=s.id; d.channel=channel; d.token=token; d.environment=env; d.payload=logic.json(payload); d.finalMessage=end; d.priority=priority; d.expiresAt=expiry; d.dueAt=now; d.status="PENDING";
        if(d.payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096) { d.status="FAILED"; d.reason="PAYLOAD_TOO_LARGE"; }
        deliveries.save(d);
    }
}
